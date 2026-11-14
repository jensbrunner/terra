package terra.experiments

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import terra.atmosphere.PlanetLatitude
import terra.world.Terrain
import terra.world.World
import terra.weather.DynamicalWeather

/**
 * Compact physical review atlas: terrain, annual rain, annual-mean surface temperature,
 * root-zone water and canopy cover, with explicit units and zonal statistics. The final
 * year is advanced month by month so winds and seasonal temperatures are annual means,
 * not an end-of-run snapshot. It is a review aid, not a test suite.
 */
fun main(args: Array<String>) {
    val years = args.firstOrNull { it.startsWith("--years=") }?.substringAfter('=')?.toInt() ?: 100
    val seed = args.firstOrNull { it.startsWith("--seed=") }?.substringAfter('=')?.toInt() ?: 31742
    require(years in 2..1000)
    val year = 365.0 * 86400.0
    val world = World(seed)

    val weather = DynamicalWeather(world)
    val detailed = weather.advanceBudgeted(40.0, 1, publishSnapshot = false)

    var residual = 0.0
    val started = System.nanoTime()
    var remaining = years - 1
    var evaporationKg = 0.0
    var rainKg = 0.0
    val traceYears = args.firstOrNull { it.startsWith("--trace=") }?.substringAfter('=')?.toInt() ?: 0
    if (traceYears > 0) {
        // Global-mean spin-up trace: one line per quarter.
        val area = weather.config.domainWidthMeters * weather.config.domainHeightMeters
        for (quarter in 0 until 4 * min(traceYears, remaining)) {
            val step = weather.advanceClimateInterval(year / 4)
            residual = max(residual, abs(step.relativeWaterResidual))
            weather.refreshSnapshot()
            var ts = 0.0; var ta = 0.0; var water = 0.0
            for (y in 0 until world.height) for (x in 0 until world.width) {
                ts += weather.surfaceTemperatureC(x, y); ta += weather.airTemperatureC(x, y)
                water += weather.atmosphericWaterMm(x, y)
            }
            val n = world.width * world.height
            println("TRACE q=$quarter Ts=${"%.1f".format(ts / n)} Ta=${"%.1f".format(ta / n)} W=${"%.1f".format(water / n)}mm " +
                "E=${"%.0f".format(step.evaporationKg / area * 4)} P=${"%.0f".format(step.rainfallKg / area * 4)} mm/y " +
                "SW=${"%.0f".format(step.absorbedShortwaveJoules / area / (year / 4))} OLR=${"%.0f".format(step.outgoingLongwaveJoules / area / (year / 4))} " +
                "wind=${"%.1f".format(step.meanWindMetersPerSecond)}")
        }
        remaining -= min(traceYears, remaining)
    }
    while (remaining > 0) {
        val chunk = min(20, remaining)
        residual = max(residual, abs(weather.advanceClimateInterval(chunk * year).relativeWaterResidual))
        remaining -= chunk
    }
    val width = world.width
    val height = world.height
    val bands = 12
    fun bandOf(y: Int) = y * bands / height
    val bandU = DoubleArray(bands); val bandV = DoubleArray(bands)
    val bandJanuary = DoubleArray(bands); val bandJuly = DoubleArray(bands)
    val bandCount = IntArray(bands)
    for (y in 0 until height) bandCount[bandOf(y)] += width
    val tileMetersX = weather.config.domainWidthMeters / width
    val tileMetersY = weather.config.domainHeightMeters / height
    var meanWind = 0.0; var maxWind = 0.0
    for (month in 0 until 12) {
        val step = weather.advanceClimateInterval(year / 12)
        residual = max(residual, abs(step.relativeWaterResidual))
        evaporationKg += step.evaporationKg; rainKg += step.rainfallKg
        meanWind += step.meanWindMetersPerSecond / 12; maxWind = max(maxWind, step.maximumWindMetersPerSecond)
        weather.refreshSnapshot()
        for (y in 0 until height) for (x in 0 until width) {
            val b = bandOf(y)
            bandU[b] += weather.windUTilesPerDay(x, y) * tileMetersX / 86400.0 / 12
            // Tile rows increase southward; report northward-positive wind.
            bandV[b] -= weather.windVTilesPerDay(x, y) * tileMetersY / 86400.0 / 12
            if (month == 0) bandJanuary[b] += weather.surfaceTemperatureC(x, y)
            if (month == 6) bandJuly[b] += weather.surfaceTemperatureC(x, y)
        }
    }
    val wall = (System.nanoTime() - started) / 1e9
    weather.refreshSnapshot()
    val climate = weather.seasonalClimate!!
    val panels = 5
    val image = BufferedImage(width * panels, height, BufferedImage.TYPE_INT_RGB)
    var land = 0; var landRain = 0.0; var oceanRain = 0.0; var ocean = 0
    var landTemperature = 0.0; var oceanTemperature = 0.0; var soilTotal = 0.0; var treeTotal = 0.0
    var minRain = Double.POSITIVE_INFINITY; var maxRain = 0.0
    var minTemperature = Double.POSITIVE_INFINITY; var maxTemperature = Double.NEGATIVE_INFINITY
    val bandLand = IntArray(bands); val bandOcean = IntArray(bands)
    val bandLandRain = DoubleArray(bands); val bandOceanRain = DoubleArray(bands)
    val bandLandT = DoubleArray(bands); val bandOceanT = DoubleArray(bands)
    val bandTree = DoubleArray(bands)
    for (y in 0 until height) for (x in 0 until width) {
        val cell = world[x, y]
        val b = bandOf(y)
        val rain = climate.annualPrecipitationAtTile(x, y, width, height)
        val temperature = climate.meanSurfaceTemperatureAtTile(x, y, width, height)
        val soil = weather.soilWaterMm(x, y)
        val tree = world.treeCover(x, y)
        minTemperature = min(minTemperature, temperature); maxTemperature = max(maxTemperature, temperature)
        val grass = world.grassCover(x, y)
        if (cell.terrain != Terrain.OCEAN) {
            land++; landRain += rain; landTemperature += temperature; soilTotal += soil; treeTotal += tree
            minRain = min(minRain, rain); maxRain = max(maxRain, rain)
            bandLand[b]++; bandLandRain[b] += rain; bandLandT[b] += temperature; bandTree[b] += tree
        } else {
            oceanRain += rain; ocean++; oceanTemperature += temperature
            bandOcean[b]++; bandOceanRain[b] += rain; bandOceanT[b] += temperature
        }
        val terrainColor = when (cell.terrain) {
            Terrain.OCEAN -> Color(25, 65, 105)
            Terrain.WOODS -> Color(25, 95, 45)
            Terrain.PLAINS -> Color(125, 160, 65)
            Terrain.STEPPE -> Color(175, 160, 80)
            Terrain.DESERT -> Color(215, 190, 125)
            Terrain.TUNDRA -> Color(165, 175, 165)
            Terrain.MOUNTAINS -> Color(110, 100, 95)
        }
        image.setRGB(x, y, terrainColor.rgb)
        // Rain: brown (dry) → green → blue (wet), 0..2500 mm/year.
        image.setRGB(x + width, y, Color.getHSBColor((0.08 + 0.58 * (rain / 2500.0).coerceIn(0.0, 1.0)).toFloat(),
            0.8f, 0.92f).rgb)
        // Temperature: blue (cold) → red (hot), −40..35 °C annual mean.
        image.setRGB(x + 2 * width, y, Color.getHSBColor(
            (0.66 - 0.66 * ((temperature + 40) / 75.0).coerceIn(0.0, 1.0)).toFloat(), 0.75f, 0.9f).rgb)
        image.setRGB(x + 3 * width, y, Color(0, (soil / 250 * 255).toInt().coerceIn(0, 255), 80).rgb)
        image.setRGB(x + 4 * width, y, Color(
            (30 + 40 * grass).toInt().coerceIn(0, 255),
            (40 + 190 * tree).toInt().coerceIn(0, 255),
            (30 + 30 * grass).toInt().coerceIn(0, 255)).rgb)
    }
    val output = File(args.firstOrNull { it.startsWith("--output=") }?.substringAfter('=') ?: "build/experiments").apply { mkdirs() }
    ImageIO.write(image, "png", File(output, "climate-atlas.png"))
    val area = weather.config.domainWidthMeters * weather.config.domainHeightMeters
    val text = buildString {
        appendLine("Terra climate review atlas")
        appendLine("world=${width}x${height} years=$years seed=${world.seed}")
        appendLine("panels: terrain | annual rain (0 brown → 2500 mm/y blue) | annual-mean surface temperature (−40 blue → 35 °C red) | root-zone soil (0–250 mm) | canopy/grass (green)")
        appendLine("wall_seconds=${"%.2f".format(wall)} years_per_second=${"%.2f".format(years / wall)} max_water_residual=$residual")
        appendLine("global_mean_t=${"%.1f".format((landTemperature + oceanTemperature) / (land + ocean))} °C " +
            "land_t=${"%.1f".format(landTemperature / max(1, land))} ocean_t=${"%.1f".format(oceanTemperature / max(1, ocean))} " +
            "range=${"%.1f".format(minTemperature)}..${"%.1f".format(maxTemperature)}")
        appendLine("global_rain=${"%.0f".format(rainKg / area)} mm/y global_evaporation=${"%.0f".format(evaporationKg / area)} mm/y " +
            "land_rain=${"%.0f".format(landRain / max(1, land))} ocean_rain=${"%.0f".format(oceanRain / max(1, ocean))} " +
            "land_range=${"%.0f".format(minRain)}..${"%.0f".format(maxRain)}")
        appendLine("wind_mean=${"%.2f".format(meanWind)} m/s wind_max=${"%.1f".format(maxWind)} m/s land_fraction=${"%.2f".format(land.toDouble() / (land + ocean))}")
        appendLine("soil_mean=${"%.0f".format(soilTotal / max(1, land))} mm canopy_mean=${"%.2f".format(treeTotal / max(1, land))} fire_disturbances=${world.fireCount}")
        appendLine("biomes=${world.snapshot(1).landBiomeSummary().replace('\n', ' ')}")
        appendLine("snow_kg=${(0 until weather.config.cellCount).sumOf { weather.surfaceState.snowMmInCell(it) } * weather.config.cellArea}")
        appendLine("observed_years=${climate.observedYears} aggregate=${climate.containsAggregateEstimates}")
        appendLine("  lat   landfr  rainL  rainO    T_L    T_O  T_jan  T_jul   u_low   v_low  tree")
        for (b in 0 until bands) {
            val y0 = b * height / bands; val y1 = (b + 1) * height / bands - 1
            val latitude = Math.toDegrees(PlanetLatitude.terrainRowRadians((y0 + y1) / 2, height))
            appendLine("%5.0f  %6.2f  %5.0f  %5.0f  %5.1f  %5.1f  %5.1f  %5.1f  %6.2f  %6.2f  %4.2f".format(
                latitude, bandLand[b].toDouble() / bandCount[b],
                bandLandRain[b] / max(1, bandLand[b]), bandOceanRain[b] / max(1, bandOcean[b]),
                bandLandT[b] / max(1, bandLand[b]), bandOceanT[b] / max(1, bandOcean[b]),
                bandJanuary[b] / bandCount[b], bandJuly[b] / bandCount[b],
                bandU[b] / bandCount[b], bandV[b] / bandCount[b], bandTree[b] / max(1, bandLand[b])))
        }

    }
    File(output, "climate-atlas.txt").writeText(text)
    print(text)
    println("CLIMATE_ATLAS ${File(output, "climate-atlas.png").absolutePath}")
}
