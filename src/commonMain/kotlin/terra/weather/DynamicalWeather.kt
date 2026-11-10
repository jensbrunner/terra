package terra.weather

import terra.atmosphere.AtmosphereTileMapping
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.MoistAtmosphere
import terra.atmosphere.MoistAtmosphereSnapshot
import terra.atmosphere.MoistCell
import terra.atmosphere.MoistPhysicsConfig
import terra.atmosphere.PlanetarySunlight
import terra.hydrology.HydrologySimulation
import terra.world.World
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

/** Coarse prognostic atmosphere, conservative fine surface, and a stable tile-space render view.
 * Atmosphere and terrain wrap longitude; hydrology returns river discharge to its finite ocean.
 */
class DynamicalWeather(
    world: World,
    private val hydrology: HydrologySimulation,
    val config: DryAtmosphereConfig = DryAtmosphereConfig(),
    val physics: MoistPhysicsConfig = MoistPhysicsConfig()
) : WeatherModel {
    override val width = world.width
    override val height = world.height
    private val mapping = AtmosphereTileMapping(width, height, config)
    private val surface = TerrainMoistSurface(world, mapping, hydrology)
    private val model = MoistAtmosphere(config, physics, seed = world.seed, surface = surface, initializeColumns = { x, y ->
        val c = min(config.height - 1, (y / config.dy).toInt()) * config.width +
            min(config.width - 1, (x / config.dx).toInt())
        MoistCell(temperatureKelvin = 280.0, relativeHumidity = 0.75,
            surfaceTemperatureKelvin = 288.0 - 4.0 * surface.landFraction[c], surfaceWaterKgM2 = 0.0)
    })
    private val sunlight = PlanetarySunlight(width, height, physics.solar, northUp = false)
    private var view: MoistAtmosphereSnapshot = model.snapshot()
    private val temperatureIntegral = DoubleArray(config.cellCount)
    private val climate = SeasonalClimate(config.width, config.height, physics.solar.yearSeconds, physics.solar.initialYearFraction)
    val seasonalClimate: SeasonalClimateSnapshot? get() = climate.snapshot
    val observedClimateYears: Int get() = climate.observedYears
    val atmosphereSnapshot: MoistAtmosphereSnapshot get() = view
    private var updateSeconds = 0.0
    override val elapsedSeconds get() = model.elapsedSeconds
    val diagnostics get() = view.diagnostics

    /** Execute whole physical steps only. The caller keeps the unconsumed requested time. */
    fun advanceBudgeted(requestedSeconds: Double, maximumSteps: Int = 8): Double {
        require(requestedSeconds.isFinite() && requestedSeconds >= 0 && maximumSteps > 0)
        val steps = min(maximumSteps.toDouble(), floor(requestedSeconds / config.stepSeconds)).toInt()
        if (steps == 0) return 0.0
        surface.beginUpdate()
        repeat(steps) {
            model.step()
            surface.finishStep(config.stepSeconds)
            climate.observe(config.stepSeconds, { model.surfaceTemperatureKelvinAt(it) - 273.15 }, model::rainMmThisStepAt)
            for (c in temperatureIntegral.indices) temperatureIntegral[c] +=
                (model.temperatureKelvinAt(c) - 273.15) * config.stepSeconds / 86400.0
        }
        updateSeconds = steps * config.stepSeconds
        view = model.snapshot()
        return updateSeconds
    }

    override fun step(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0)
        require(seconds / config.stepSeconds == floor(seconds / config.stepSeconds)) { "Use whole atmospheric steps" }
        if (seconds > 0) advanceBudgeted(seconds, Int.MAX_VALUE)
    }

    fun observedClimate(): ClimateSnapshot? {
        if (elapsedSeconds == 0.0) return null
        val temperature = DoubleArray(width * height) { i -> sample(i % width, i / width) { x, y ->
            temperatureIntegral[y * config.width + x]
        } }
        return ClimateSnapshot(width, height, elapsedSeconds / 86400.0, surface.observedRain.copyOf(), temperature)
    }

    /** State interpolation is for display. Water transfers always use exact rectangle overlaps. */
    private inline fun sample(x: Int, y: Int, field: (Int, Int) -> Double): Double {
        val px = (x + 0.5) * config.width / width - 0.5
        val py = config.height - (y + 0.5) * config.height / height - 0.5
        val left = floor(px).toInt(); val bottom = floor(py).toInt()
        val fx = px - left; val fy = py - bottom
        val x0 = (left + config.width) % config.width; val x1 = (x0 + 1) % config.width
        val y0 = if (config.periodicY) (bottom + config.height) % config.height else bottom.coerceIn(0, config.height - 1)
        val y1 = if (config.periodicY) (y0 + 1) % config.height else (bottom + 1).coerceIn(0, config.height - 1)
        return (field(x0, y0) * (1 - fx) + field(x1, y0) * fx) * (1 - fy) +
            (field(x0, y1) * (1 - fx) + field(x1, y1) * fx) * fy
    }

    private fun index(x: Int, y: Int) = y * width + x
    override fun relativeHumidity(x: Int, y: Int) = sample(x, y, view::relativeHumidityAt)
    override fun cloudWaterMm(x: Int, y: Int) = sample(x, y, view::cloudWaterMmAt)
    override fun cloudCover(x: Int, y: Int) = 1 - exp(-cloudWaterMm(x, y) / 0.15)
    override fun atmosphericWaterMm(x: Int, y: Int) = sample(x, y, view::vaporMmAt)
    override fun airTemperatureC(x: Int, y: Int) = sample(x, y, view::temperatureKelvinAt) - 273.15
    override fun surfaceTemperatureC(x: Int, y: Int) = sample(x, y, view::surfaceTemperatureKelvinAt) - 273.15
    override fun windUTilesPerDay(x: Int, y: Int) = sample(x, y, view.dynamics::eastWindAt) * 86400 * width / config.domainWidthMeters
    override fun windVTilesPerDay(x: Int, y: Int) = -sample(x, y, view.dynamics::northWindAt) * 86400 * height / config.domainHeightMeters
    override fun precipitationMmPerHour(x: Int, y: Int) = if (updateSeconds > 0) rainMmThisUpdate(x, y) * 3600 / updateSeconds else 0.0
    override fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int) = surface.peakRainRate[index(x, y)]
    override fun rainMmThisUpdate(x: Int, y: Int) = surface.rain[index(x, y)]
    override fun drainageMmThisUpdate(x: Int, y: Int) = surface.drainage[index(x, y)]
    override fun overflowMmThisUpdate(x: Int, y: Int) = surface.overflow[index(x, y)]
    override fun lakeDirectRainMmThisUpdate(x: Int, y: Int) = surface.lakeRain[index(x, y)]
    // Lake evaporation was already withdrawn and paid for during the physical surface exchange.
    override fun lakePotentialEvaporationMmThisUpdate(x: Int, y: Int) = 0.0
    override fun thirtyDayRainMmPerHour(x: Int, y: Int) = surface.climateRainRate[index(x, y)]
    override fun annualizedRainMmPerYear(x: Int, y: Int) = thirtyDayRainMmPerHour(x, y) * 24 * 365
    override fun soilWaterMm(x: Int, y: Int) = surface.soil[index(x, y)]
    override fun soilMoisture(x: Int, y: Int) = soilWaterMm(x, y) / 250.0
    override fun solarInputFactor(x: Int, y: Int): Double {
        sunlight.update(elapsedSeconds)
        return sunlight.inputFactor(x, y)
    }
    override fun nightOpacity(x: Int, y: Int): Double {
        sunlight.update(elapsedSeconds)
        return sunlight.nightOpacity(x, y)
    }
    override fun updateLakeWaterMask(mask: BooleanArray) = surface.updateLakeMask(mask)
    override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double) {
        check(amountMm == 0.0) { "Dynamical lake evaporation must be withdrawn during surface heat exchange" }
    }
    override fun hydrologyAccepted() = surface.transferredToHydrology()
}
