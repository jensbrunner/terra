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
import terra.simulation.SimulationTimings
import terra.simulation.SimulationWork
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

/** Coarse prognostic atmosphere, conservative fine surface, and a stable tile-space render view.
 * Atmosphere and terrain wrap longitude; hydrology returns river discharge to its finite ocean.
 */
class DynamicalWeather(
    private val world: World,
    private val hydrology: HydrologySimulation,
    val config: DryAtmosphereConfig = DryAtmosphereConfig(),
    val physics: MoistPhysicsConfig = MoistPhysicsConfig(),
    private val timings: SimulationTimings? = null
) : WeatherModel {
    override val width = world.width
    override val height = world.height
    private val mapping = AtmosphereTileMapping(width, height, config)
    internal val surfaceState get() = surface
    internal val surfaceThermalState get() = model.surfaceThermalState
    override val pendingWaterFluxBatch get() = surface.pendingFluxBatch
    private val surface = TerrainMoistSurface(world, mapping, hydrology)
    private val initialWeather = InitialWeather(world.seed, config)
    private val model = MoistAtmosphere(config, physics, seed = world.seed, surface = surface, initializeFlow = initialWeather::flow, initializeColumns = { x, y ->
        val c = min(config.height - 1, (y / config.dy).toInt()) * config.width +
            min(config.width - 1, (x / config.dx).toInt())
        val moist = initialWeather.moistFraction(x / config.domainWidthMeters, y / config.domainHeightMeters,
            surface.initialWaterAvailability(c))
        // These are time-zero inventories, not ongoing moisture/temperature forcing.
        MoistCell(temperatureKelvin = 280.0, relativeHumidity = initialWeather.relativeHumidity(moist),
            cloudWaterKgM2 = initialWeather.cloudWaterMm(moist),
            surfaceTemperatureKelvin = 288.0 - 4.0 * surface.landFraction[c], surfaceWaterKgM2 = 0.0)
    })
    private val sunlight = PlanetarySunlight(width, height, physics.solar, northUp = false)
    private var view: MoistAtmosphereSnapshot = model.snapshot()
    private val temperatureIntegral = DoubleArray(config.cellCount)
    private val climate = SeasonalClimate(config.width, config.height, physics.solar.yearSeconds, physics.solar.initialYearFraction)
    private val climateSurfaceTemperature: (Int) -> Double = { model.surfaceTemperatureKelvinAt(it) - 273.15 }
    private val climateRain: (Int) -> Double = model::rainMmThisStepAt
    internal val climateHistory get() = climate
    internal val climateNormals by lazy { ClimateNormals(world, surface, physics.solar) }
    internal fun estimateClimate(worldSeconds: Double): ClimateEstimateSnapshot =
        climateNormals.estimate(worldSeconds, seasonalClimate)
    val seasonalClimate: SeasonalClimateSnapshot? get() = climate.snapshot
    val observedClimateYears: Int get() = climate.observedYears
    val atmosphereSnapshot: MoistAtmosphereSnapshot get() = view
    private var fastClimate: FastClimateStepper? = null
    internal val isClimateMode: Boolean get() = fastClimate != null
    internal val climateDiagnostics: ClimateIntervalDiagnostics? get() = climateResult?.diagnostics
    private var heldRepresentative: FastClimateResult? = null
    internal var detailedReentryPending = false
        private set
    internal var lastHandoff: terra.atmosphere.AtmosphereHandoff? = null
        private set
    internal val hasRepresentativeFields get() = climateResult != null
    private val climateResult get() = fastClimate?.latest ?: heldRepresentative
    override fun rainEventSummary(x: Int, y: Int) = climateResult?.rainEvents(index(x, y))
    override val fieldSemantics get() = climateResult?.semantics ?: WeatherFieldSemantics(WeatherFieldQuality.RESOLVED_NOW, elapsedSeconds - updateSeconds, updateSeconds)
    internal val detailedCompletedSteps get() = model.completedSteps
    private var updateSeconds = 0.0
    internal var dynamicsSuppressedForClimate = false
    override val hasResolvedDynamics get() = !dynamicsSuppressedForClimate && !isClimateMode && !detailedReentryPending
    override fun pressureAnomalyPercent(x: Int, y: Int): Double? = if (!hasResolvedDynamics) null else
        sample(x, y, view.horizontalDynamics::pressureAnomalyPercentAt)
    override fun relativeVorticityPerSecond(x: Int, y: Int): Double? = if (!hasResolvedDynamics) null else
        sample(x, y, view.horizontalDynamics::relativeVorticityPerSecondAt)
    override val elapsedSeconds get() = fastClimate?.completedSeconds ?: model.worldSeconds
    val diagnostics get() = view.diagnostics

    /** Execute whole physical steps only. The caller keeps the unconsumed requested time. */
    fun advanceBudgeted(requestedSeconds: Double, maximumSteps: Int = 8, publishSnapshot: Boolean = true): Double {
        check(!isClimateMode) { "Select detailed fidelity before detailed advancement" }
        require(requestedSeconds.isFinite() && requestedSeconds >= 0 && maximumSteps > 0)
        val steps = min(maximumSteps.toDouble(), floor(requestedSeconds / config.stepSeconds)).toInt()
        if (steps == 0) return 0.0
        surface.beginUpdate()
        repeat(steps) {
            timings?.begin(SimulationWork.INTEGRATION)
            model.step()
            timings?.end(SimulationWork.INTEGRATION)
            timings?.begin(SimulationWork.SURFACE_BUCKET)
            surface.finishStep(config.stepSeconds)
            timings?.end(SimulationWork.SURFACE_BUCKET)
            timings?.begin(SimulationWork.CLIMATE_OBSERVATION)
            climate.observe(config.stepSeconds, climateSurfaceTemperature, climateRain)
            for (c in temperatureIntegral.indices) temperatureIntegral[c] +=
                (model.temperatureKelvinAt(c) - 273.15) * config.stepSeconds / 86400.0
            timings?.end(SimulationWork.CLIMATE_OBSERVATION)
        }
        updateSeconds = steps * config.stepSeconds
        surface.sealFluxBatch(updateSeconds)
        detailedReentryPending = false
        heldRepresentative = null
        if (publishSnapshot) refreshSnapshot()
        return updateSeconds
    }

    /** Explicit owner entry only. Automatic speed/fidelity selection is a later step. */
    internal fun advanceClimateInterval(seconds: Double): ClimateIntervalDiagnostics {
        require(seconds.isFinite() && seconds >= FastClimateStepper.MIN_INTERVAL_SECONDS && seconds <= 20 * physics.solar.yearSeconds)
        val engine = fastClimate ?: FastClimateStepper(surface, hydrology, model, climate, climateNormals, elapsedSeconds) { duration, moisture ->
            world.advanceClimateBiomes(seasonalClimate, duration, moisture)
        }
        val result = engine.advance(seconds)
        fastClimate = engine
        detailedReentryPending = false
        heldRepresentative = null
        updateSeconds = seconds
        refreshSnapshot()
        return result.diagnostics
    }

    /** Rebuild only atmosphere; no surface/history/calendar integration is performed here. */
    internal fun resumeDetailed(): terra.atmosphere.AtmosphereHandoff? {
        val engine = fastClimate ?: return null
        check(surface.pendingFluxBatch?.consumed != false)
        val handoff = model.initializeFromClimate(engine.completedSeconds)
        heldRepresentative = engine.latest
        fastClimate = null
        detailedReentryPending = true
        lastHandoff = handoff
        refreshSnapshot()
        return handoff
    }

    fun refreshSnapshot() {
        timings?.begin(SimulationWork.SNAPSHOT)
        view = model.snapshot()
        timings?.end(SimulationWork.SNAPSHOT)
    }

    /** Snapshot owns copied fine surface fields; no live solver or transfer arrays escape. */
    internal fun captureReadOnly(): WeatherModel = DynamicalWeatherPresentation(
        width, height, config, physics, view, elapsedSeconds, updateSeconds,
        climateResult?.captureFields(surface) ?: arrayOf(surface.peakRainRate.copyOf(), surface.rain.copyOf(), surface.drainage.copyOf(),
            surface.overflow.copyOf(), surface.lakeRain.copyOf(), surface.climateRainRate.copyOf(),
            surface.soil.copyOf()), climateResult, hasResolvedDynamics)

    override fun step(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0)
        require(seconds / config.stepSeconds == floor(seconds / config.stepSeconds)) { "Use whole atmospheric steps" }
        if (seconds > 0) advanceBudgeted(seconds, Int.MAX_VALUE)
    }

    fun observedClimate(): ClimateSnapshot? {
        if (elapsedSeconds == 0.0 || model.worldSeconds != model.elapsedSeconds || hasRepresentativeFields) return null
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
    override fun relativeHumidity(x: Int, y: Int) = climateResult?.let { result -> sample(x, y) { cx, cy -> result.relativeHumidity(cy * config.width + cx) } } ?: sample(x, y, view::relativeHumidityAt)
    override fun cloudWaterMm(x: Int, y: Int) = climateResult?.let { result -> sample(x, y) { cx, cy -> result.cloudMm(cy * config.width + cx) } } ?: sample(x, y, view::cloudWaterMmAt)
    override fun cloudCover(x: Int, y: Int) = 1 - exp(-cloudWaterMm(x, y) / 0.15)
    override fun atmosphericWaterMm(x: Int, y: Int) = if (hasRepresentativeFields) sample(x, y) { cx, cy -> view.vaporMmAt(cx, cy) + view.cloudWaterMmAt(cx, cy) } else sample(x, y, view::vaporMmAt)
    override fun airTemperatureC(x: Int, y: Int) = if (hasRepresentativeFields) surfaceTemperatureC(x, y) - MoistAtmosphere.CLIMATE_AIR_SURFACE_OFFSET_K else sample(x, y, view::temperatureKelvinAt) - 273.15
    override fun surfaceTemperatureC(x: Int, y: Int) = sample(x, y, view::surfaceTemperatureKelvinAt) - 273.15
    override fun windUTilesPerDay(x: Int, y: Int) = if (hasRepresentativeFields) 0.0 else sample(x, y, view.dynamics::eastWindAt) * 86400 * width / config.domainWidthMeters
    override fun windVTilesPerDay(x: Int, y: Int) = if (hasRepresentativeFields) 0.0 else -sample(x, y, view.dynamics::northWindAt) * 86400 * height / config.domainHeightMeters
    override fun precipitationMmPerHour(x: Int, y: Int) = if (updateSeconds > 0) rainMmThisUpdate(x, y) * 3600 / updateSeconds else 0.0
    override fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int) = climateResult?.field(0, index(x, y)) ?: surface.peakRainRate[index(x, y)]
    override fun rainMmThisUpdate(x: Int, y: Int) = climateResult?.field(1, index(x, y)) ?: surface.rain[index(x, y)]
    override fun drainageMmThisUpdate(x: Int, y: Int) = climateResult?.field(2, index(x, y)) ?: surface.drainage[index(x, y)]
    override fun overflowMmThisUpdate(x: Int, y: Int) = climateResult?.field(3, index(x, y)) ?: surface.overflow[index(x, y)]
    override fun lakeDirectRainMmThisUpdate(x: Int, y: Int) = climateResult?.field(4, index(x, y)) ?: surface.lakeRain[index(x, y)]
    // Lake evaporation is already withdrawn; detailed physics pays latent heat, climate labels its energy approximation.
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
