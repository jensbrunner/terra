package terra.climate

import terra.world.World
import kotlin.math.exp

/** Sole owner of the live atmosphere and its coupled landscape water stores. */
class ClimateSimulation(private val world: World) {
    private val atmosphere = Atmosphere(world)
    internal val surfaceState = LandscapeWater(world)
    private val annualRain = DoubleArray(world.width * world.height)
    private var ecologyYear = 1L
    private var updateSeconds = 0.0
    var elapsedSeconds = 0.0
        private set

    fun advance(seconds: Double) {
        require(seconds.isFinite() && seconds > 0)
        surfaceState.beginUpdate()
        atmosphere.advance(seconds, elapsedSeconds, surfaceState)
        val alpha = 1.0 - exp(-seconds / YEAR_SECONDS)
        val rain = atmosphere.rain
        for (i in annualRain.indices) annualRain[i] += ((rain[i] * YEAR_SECONDS / seconds) - annualRain[i]) * alpha
        elapsedSeconds += seconds
        while (elapsedSeconds >= ecologyYear * YEAR_SECONDS) {
            world.evolveWeatherCover(1.0, DoubleArray(atmosphere.count) { atmosphere.temperature[it] - 273.15 },
                surfaceState.growingWaterAvailability)
            ecologyYear++
        }
        updateSeconds = seconds
    }

    fun snapshot(): EnvironmentSnapshot {
        val a = atmosphere
        val rate = DoubleArray(a.count) { a.rain[it] * 3600 / maxOf(updateSeconds, 1.0) }
        return EnvironmentSnapshot(world.width, world.height, elapsedSeconds, updateSeconds,
            DoubleArray(a.count) { (a.vapor[it] / saturationWater(a.temperature[it])).coerceIn(0.0, 1.0) },
            DoubleArray(a.count) { 1.0 - exp(-a.cloud[it] / 2.0) }, rate, a.temperature.copyOf(), DoubleArray(a.count) { (a.u[it] + a.u[it / world.width * world.width + (it % world.width + world.width - 1) % world.width]) * 0.5 },
            DoubleArray(a.count) { (a.v[it] + if (it < world.width) 0.0 else a.v[it - world.width]) * 0.5 },
            DoubleArray(a.count) { a.h[it] * AIR_DENSITY * GRAVITY / 100.0 },
            annualRain.copyOf(), surfaceState.soil.copyOf(), surfaceState.soilCapacityMm,
            DoubleArray(world.width * world.height) { surfaceState.snowWaterMm(it) }, surfaceState.rain.copyOf(),
            surfaceState.drainage.copyOf(), surfaceState.overflow.copyOf())
    }
}
