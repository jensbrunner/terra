package terra.climate

import terra.hydrology.LandscapeWater
import terra.world.World
import terra.world.PlateTectonics
import terra.simulation.GeologySettings
import terra.simulation.GeologyStatus

/** Annual climate/vegetation steps; no daily or atmospheric substeps during fast-forward. */
class ClimateSimulation(private val world: World) {
    private val geology = PlateTectonics(world)
    private val atmosphere = Atmosphere(world)
    internal val surfaceState = LandscapeWater(world)
    private val rain = atmosphere.annualRain
    private val evaporation = atmosphere.evaporation
    private val temperatureK = DoubleArray(rain.size) { atmosphere.temperatureC[it] + 273.15 }
    var geologySettings = GeologySettings()
    val geologyStatus get() = GeologyStatus(geologySettings, geology.effectiveYears)
    // One credit per completed calendar year preserves rate changes within a pending interval.
    private val geologicalCredits = ArrayDeque<Double>()
    private var completedYears = 0L
    var elapsedSeconds = 0.0
        private set

    fun advance(seconds: Double) {
        require(seconds.isFinite() && seconds > 0)
        elapsedSeconds += seconds
        val year = (elapsedSeconds / YEAR_SECONDS).toLong()
        while (completedYears < year) {
            geologicalCredits.addLast(geologySettings.speedMultiplier.toDouble())
            while (geologicalCredits.size >= geologySettings.updateYears) {
                val interval = geologySettings.updateYears
                var effectiveYears = 0.0
                repeat(interval) { effectiveYears += geologicalCredits.removeFirst() }
                geology.advance(interval.toDouble(), rain, surfaceState.surfaceWater.snapshot(), effectiveYears / interval)
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
