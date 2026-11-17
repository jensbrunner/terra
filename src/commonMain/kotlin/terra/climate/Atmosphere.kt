package terra.climate

import kotlin.math.*
import terra.world.TerrainGenerationSettings
import terra.world.TerrainNoise
import terra.world.World

/** Statistical climate, not fluid dynamics. Geography is cached between geological ticks; weather is evaluated only when displayed. */
internal class Atmosphere(private val world: World) {
    private val width = world.width
    private val height = world.height
    private val count = width * height
    val temperatureC = DoubleArray(count)
    val annualRain = DoubleArray(count)
    val evaporation = DoubleArray(count)
    val moisture = DoubleArray(count)
    private val latitude = DoubleArray(height) { asin(1 - 2.0 * (it + 0.5) / height) }
    private val westExposure = DoubleArray(height) { (1 - cos(4 * latitude[it])) * 0.5 }
    private val eastWind = DoubleArray(height) { 12 * westExposure[it] - 6 }
    private val elevation = DoubleArray(count) { world.elevationMeters(it % width, it / width) }
    private val continentality = DoubleArray(count)
    private val noise = TerrainNoise(TerrainGenerationSettings())
    private val regionalNoise = DoubleArray(count) { i ->
        noise.periodic((i % width + 0.5) * 283 / width, (i / width + 0.5) * 212 / height,
            world.seed + 421, 3, 55.0)
    }
    private val weatherWidth = 64
    private val weatherHeight = 32
    private val weather = DoubleArray(weatherWidth * weatherHeight) {
        noise.periodic(it % weatherWidth * 283.0 / weatherWidth, it / weatherWidth * 212.0 / weatherHeight,
            world.seed + 951, 3, 18.0)
    }

    init { refreshGeography() }

    fun refreshGeography() {
        for (i in 0 until count) elevation[i] = world.elevationMeters(i % width, i / width)
        for (y in 0 until height) {
            val lat = abs(latitude[y]) * 180 / PI
            val tropics = exp(-(lat / 17).pow(2))
            val tropicalRain = 1750 * tropics
            val temperateRain = 850 * exp(-((lat - 52) / 17).pow(2))
            val exposure = Array(2) { DoubleArray(width) }
            val uplift = Array(2) { DoubleArray(width) }
            for (direction in 0..1) {
                var oceanMoisture = 0.0
                var previousElevation = 0.0
                // Two circuits remove the artificial seam at the first longitude.
                repeat(width * 2) { step ->
                    val x = if (direction == 0) step % width else width - 1 - step % width
                    val i = y * width + x
                    val rise = max(0.0, elevation[i] - previousElevation)
                    if (!world.isLand(x, y)) oceanMoisture = 1.0
                    else oceanMoisture *= exp(-2.0 / width - rise / 2500)
                    exposure[direction][x] = oceanMoisture
                    uplift[direction][x] = oceanMoisture * rise
                    previousElevation = elevation[i]
                }
            }
            for (x in 0 until width) {
                val i = y * width + x
                // Blend both ocean exposures, instead of reversing an entire row at a latitude boundary.
                val west = westExposure[y]
                val oceanMoisture = exposure[0][x] * west + exposure[1][x] * (1 - west)
                val rise = uplift[0][x] * west + uplift[1][x] * (1 - west)
                val regional = regionalNoise[i]
                continentality[i] = 1 - oceanMoisture
                temperatureC[i] = 29 - 53 * sin(latitude[y]).pow(2) - elevation[i] * 0.006 + regional * 2
                // Tropical moisture recycling keeps humid interiors; subtropical interiors stay dry.
                annualRain[i] = ((180 + tropicalRain + temperateRain) *
                    (0.30 + 0.30 * tropics + (0.70 - 0.30 * tropics) * oceanMoisture) * (1 + regional * 0.35) +
                    rise * 0.65).coerceIn(40.0, 4000.0)
                // Annual potential evaporation; cold land stays moist without becoming a desert.
                evaporation[i] = max(180.0, 350 + 32 * max(0.0, temperatureC[i] + 5))
                moisture[i] = (annualRain[i] / evaporation[i]).coerceIn(0.0, 1.0)
            }
        }
    }

    fun snapshot(seconds: Double, updateSeconds: Double, surface: terra.hydrology.LandscapeWater): EnvironmentSnapshot {
        val temperature = DoubleArray(count)
        val humidity = DoubleArray(count)
        val clouds = DoubleArray(count)
        val rain = DoubleArray(count)
        val east = DoubleArray(count)
        val south = DoubleArray(count)
        val pressure = DoubleArray(count)
        val season = sin(2 * PI * (seconds / YEAR_SECONDS - 0.25))
        val drift = seconds / (300 * DAY_SECONDS)
        val driftSouth = 0.015 * sin(seconds / (12 * DAY_SECONDS))
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val field = sampleWeather(x.toDouble() / width - drift,
                y.toDouble() / height + driftSouth)
            val detail = sampleWeather(x.toDouble() / width * 3 - seconds / (18 * DAY_SECONDS),
                y.toDouble() / height * 3 + seconds / (90 * DAY_SECONDS))
            val wet = annualRain[i] / 1800
            val cloud = ((field * 0.75 + detail * 0.25 + wet * 0.35 - 0.08) * 5).coerceIn(0.0, 1.0)
            clouds[i] = cloud
            humidity[i] = (0.25 + 0.45 * moisture[i] + 0.25 * cloud).coerceIn(0.0, 1.0)
            // A representative current shower, independent of the annual landscape budget.
            rain[i] = max(0.0, cloud - 0.55) * annualRain[i] / 500
            temperature[i] = 273.15 + temperatureC[i] + season * sin(latitude[y]) *
                (7 + 14 * continentality[i]) + field * 3
            east[i] = eastWind[y] + field * 4
            south[i] = detail * 5
            pressure[i] = 1013.25 - field * 18
        }
        return EnvironmentSnapshot(width, height, seconds, updateSeconds, humidity, clouds, rain, temperature,
            east, south, pressure, annualRain.copyOf(), surface.soil.copyOf(), surface.soilCapacityMm.copyOf(),
            DoubleArray(count) { surface.snowWaterMm(it) }, surface.rain.copyOf(),
            surface.drainage.copyOf(), surface.overflow.copyOf())
    }

    private fun sampleWeather(x: Double, y: Double): Double {
        val xx = (x - floor(x)) * weatherWidth
        val yy = (y - floor(y)) * weatherHeight
        val ix = floor(xx).toInt(); val iy = floor(yy).toInt()
        val tx = xx - ix; val ty = yy - iy
        val a = weather[iy * weatherWidth + ix]
        val b = weather[iy * weatherWidth + (ix + 1) % weatherWidth]
        val c = weather[(iy + 1) % weatherHeight * weatherWidth + ix]
        val d = weather[(iy + 1) % weatherHeight * weatherWidth + (ix + 1) % weatherWidth]
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    }
}
