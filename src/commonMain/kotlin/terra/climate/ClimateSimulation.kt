package terra.climate

import terra.hydrology.LandscapeWater
import terra.world.World
import terra.world.PlateTectonics

/** Annual climate/vegetation steps; no daily or atmospheric substeps during fast-forward. */
class ClimateSimulation(private val world: World) {
    private val geology = PlateTectonics(world)
    private val atmosphere = Atmosphere(world)
    internal val surfaceState = LandscapeWater(world)
    private val rain = atmosphere.annualRain
    private val evaporation = atmosphere.evaporation
    private val temperatureK = DoubleArray(rain.size) { atmosphere.temperatureC[it] + 273.15 }
    private var completedYears = 0L
    var elapsedSeconds = 0.0
        private set

    fun advance(seconds: Double) {
        require(seconds.isFinite() && seconds > 0)
        elapsedSeconds += seconds
        val year = (elapsedSeconds / YEAR_SECONDS).toLong()
        while (completedYears < year) {
            if ((completedYears + 1) % PlateTectonics.UPDATE_YEARS == 0L) {
                geology.advance(PlateTectonics.UPDATE_YEARS.toDouble(), rain, surfaceState.surfaceWater.snapshot())
                atmosphere.refreshGeography()
                surfaceState.refreshGeography(world)
                for (i in temperatureK.indices) temperatureK[i] = atmosphere.temperatureC[i] + 273.15
            }
            surfaceState.beginUpdate()
            surfaceState.advance(rain, evaporation, temperatureK, YEAR_SECONDS)
            world.evolveWeatherCover(1.0, atmosphere.temperatureC, atmosphere.moisture)
            completedYears++
        }
    }

    fun snapshot(): EnvironmentSnapshot = atmosphere.snapshot(elapsedSeconds, if (completedYears > 0) YEAR_SECONDS else 0.0, surfaceState)
}
