package terra.weather

import terra.atmosphere.AtmosphereTileMapping
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.MoistAtmosphere
import terra.atmosphere.MoistAtmosphereSnapshot
import terra.atmosphere.MoistCell
import terra.atmosphere.MoistPhysicsConfig
import terra.atmosphere.PlanetarySunlight
import terra.world.World
import terra.simulation.SimulationTimings
import terra.simulation.SimulationWork
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

/** Coarse prognostic atmosphere, conservative fine surface, and a stable tile-space render view.
 * Atmosphere and terrain wrap longitude; the surface owns soil and snow.
 */
class DynamicalWeather(
    private val world: World,
    val config: DryAtmosphereConfig = DryAtmosphereConfig(),
    val physics: MoistPhysicsConfig = MoistPhysicsConfig(),
    private val timings: SimulationTimings? = null
) : WeatherModel {
    override val width = world.width
    override val height = world.height
    private val mapping = AtmosphereTileMapping(width, height, config)
    internal val surfaceState get() = surface
    var atlas = ClimateAtlasBuilder(physics.solar).build(ClimateGeography(world, config))
        private set
    private val surface = TerrainMoistSurface(world, mapping).also {
        it.initializeClimate(atlas)
        world.evolveAtlasCover(atlas, 0.0, initialize = true, soilAvailability = it.growingWaterAvailability)
    }
    private val model = MoistAtmosphere(config, physics, surface = surface,
        initializeFlow = { _, _ -> terra.atmosphere.DryCell(config.meanHeightMeters, 0.0, 0.0) },
        initializeColumns = { _, _ -> MoistCell(relativeHumidity = 0.0, surfaceWaterKgM2 = 0.0) })
        .also { initializeDetailed(it, 0.0, initial = true) }
    private val sunlight = PlanetarySunlight(width, height, physics.solar, northUp = false)
    private var view: MoistAtmosphereSnapshot = model.snapshot()
    val atmosphereSnapshot: MoistAtmosphereSnapshot get() = view
    private var evolution: ClimateEvolution? = null
    internal val isClimateMode get() = evolution != null
    internal var lastHandoff: terra.atmosphere.AtmosphereHandoff? = null
        private set
    internal val hasRepresentativeFields get() = isClimateMode
    override val fieldSemantics get() = if (isClimateMode) WeatherFieldSemantics(WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL,
        elapsedSeconds - updateSeconds, updateSeconds) else WeatherFieldSemantics(WeatherFieldQuality.RESOLVED_NOW, elapsedSeconds - updateSeconds, updateSeconds)
    internal fun ensureAtlas() {
        if (atlas.geometryRevision == world.geometryRevision) return
        val replacement = ClimateAtlasBuilder(physics.solar).build(ClimateGeography(world, config))
        surface.refreshGeometry()
        atlas = replacement
    }
    internal val detailedCompletedSteps get() = model.completedSteps
    private var updateSeconds = 0.0
    internal var dynamicsSuppressedForClimate = false
    override val hasResolvedDynamics get() = !dynamicsSuppressedForClimate && !isClimateMode
    override fun pressureAnomalyPercent(x: Int, y: Int): Double? = if (!hasResolvedDynamics) null else
        sample(x, y, view.horizontalDynamics::pressureAnomalyPercentAt)
    override fun relativeVorticityPerSecond(x: Int, y: Int): Double? = if (!hasResolvedDynamics) null else
        sample(x, y, view.horizontalDynamics::relativeVorticityPerSecondAt)
    override val elapsedSeconds get() = evolution?.completedSeconds ?: model.worldSeconds
    val diagnostics get() = view.diagnostics

    /** Execute whole physical steps only. The caller keeps the unconsumed requested time. */
    fun advanceBudgeted(requestedSeconds: Double, maximumSteps: Int = 8, publishSnapshot: Boolean = true): Double {
        check(!isClimateMode) { "Select detailed fidelity before detailed advancement" }
        require(requestedSeconds.isFinite() && requestedSeconds >= 0 && maximumSteps > 0)
        val steps = min(maximumSteps.toDouble(), floor(requestedSeconds / config.stepSeconds)).toInt()
        if (steps == 0) return 0.0
        ensureAtlas()
        surface.beginUpdate()
        repeat(steps) {
            timings?.begin(SimulationWork.INTEGRATION)
            model.step()
            timings?.end(SimulationWork.INTEGRATION)
            timings?.begin(SimulationWork.SURFACE_BUCKET)
            for (cell in 0 until config.cellCount) surface.setCellTemperature(cell, model.surfaceTemperatureKelvinAt(cell) - 273.15)
            surface.finishStep(config.stepSeconds)
            timings?.end(SimulationWork.SURFACE_BUCKET)
            val previousYear = floor((model.worldSeconds - config.stepSeconds) / physics.solar.yearSeconds)
            if (floor(model.worldSeconds / physics.solar.yearSeconds) > previousYear) world.evolveAtlasCover(atlas, 1.0, drought = surface.climateDrought, soilAvailability = surface.growingWaterAvailability)
        }
        updateSeconds = steps * config.stepSeconds
        if (publishSnapshot) refreshSnapshot()
        return updateSeconds
    }

    /** Owner-thread surface transaction; diagnostic atlas fields remain unchanged. */
    internal fun advanceClimateInterval(seconds: Double): ClimateIntervalDiagnostics {
        require(seconds.isFinite() && seconds > 0 && seconds <= 20 * physics.solar.yearSeconds)
        ensureAtlas()
        val engine = evolution ?: ClimateEvolution(world, surface, elapsedSeconds)
        evolution = engine
        updateSeconds = seconds
        return engine.advance(atlas, seconds, model.completedSteps)
    }

    /** The detailed realization receives climatological T, RH and wind; no historical vapor inventory is implied. */
    internal fun resumeDetailed(): terra.atmosphere.AtmosphereHandoff? {
        val engine = evolution ?: return null
        ensureAtlas()
        val handoff = initializeDetailed(model, engine.completedSeconds)
        evolution = null
        updateSeconds = 0.0
        surface.beginUpdate()
        lastHandoff = handoff
        refreshSnapshot()
        return handoff
    }

    /** Shared world creation / reentry boundary. The atlas supplies the environmental
     * mean; persistent soil, snow, groundwater, lakes and vegetation are retained. */
    private fun initializeDetailed(atmosphere: MoistAtmosphere, seconds: Double, initial: Boolean = false): terra.atmosphere.AtmosphereHandoff {
        val month = atlas.monthAt(seconds)
        val tileFields = arrayOf(atlas.temperature[month], atlas.humidity[month], atlas.east[month], atlas.south[month])
        val fields = Array(4) { DoubleArray(config.cellCount).also { a -> atlas.geography.overlap.restrict(tileFields[it], a) } }
        val t = DoubleArray(config.cellCount); val rh = DoubleArray(config.cellCount)
        val u = DoubleArray(config.cellCount); val v = DoubleArray(config.cellCount)
        val surfaceT = DoubleArray(config.cellCount)
        for (i in t.indices) {
            val j = (config.height - 1 - i / config.width) * config.width + i % config.width
            t[i] = fields[0][j] + 273.15; rh[i] = fields[1][j]
            u[i] = fields[2][j]; v[i] = -fields[3][j]
            surfaceT[i] = if (surface.snowWaterMm(i) > 1.0) minOf(273.15, t[i]) else t[i]
            surface.setCellTemperature(i, surfaceT[i] - 273.15)
        }
        return atmosphere.initializeAtlasRealization(seconds, t, rh, u, v,
            ClimateAtlasBuilder.THERMAL_PRESSURE_COUPLING, surfaceT, initial)
    }

    fun refreshSnapshot() {
        timings?.begin(SimulationWork.SNAPSHOT)
        view = model.snapshot()
        timings?.end(SimulationWork.SNAPSHOT)
    }

    /** Snapshot owns copied fine surface fields; no live solver or transfer arrays escape. */
    internal fun captureReadOnly(): WeatherModel {
        ensureAtlas()
        val detailed = DynamicalWeatherPresentation(width, height, config, physics, view, elapsedSeconds, updateSeconds,
            arrayOf(surface.peakRainRate.copyOf(), surface.rain.copyOf(), surface.drainage.copyOf(), surface.overflow.copyOf(),
                surface.climateRainRate.copyOf(), surface.soil.copyOf(), surface.soilCapacityMm.copyOf()), hasResolvedDynamics = hasResolvedDynamics)
        return ClimatePresentation(detailed, atlas, atlas.monthAt(elapsedSeconds), surface.soil.copyOf(), surface.rain.copyOf(),
            surface.drainage.copyOf(), surface.overflow.copyOf(), elapsedSeconds, updateSeconds, isClimateMode)
    }

    override fun step(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0)
        require(seconds / config.stepSeconds == floor(seconds / config.stepSeconds)) { "Use whole atmospheric steps" }
        if (seconds > 0) advanceBudgeted(seconds, Int.MAX_VALUE)
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
    private val month get() = atlas.monthAt(elapsedSeconds)
    override fun relativeHumidity(x: Int, y: Int) = if (isClimateMode) atlas.relativeHumidity(month, index(x, y)) else sample(x, y, view::relativeHumidityAt)
    override fun cloudWaterMm(x: Int, y: Int) = if (isClimateMode) 0.0 else sample(x, y, view::cloudWaterMmAt)
    override fun cloudCover(x: Int, y: Int) = 1 - exp(-cloudWaterMm(x, y) / 0.15)
    override fun atmosphericWaterMm(x: Int, y: Int) = if (isClimateMode) 0.0 else sample(x, y) { cx, cy -> view.vaporMmAt(cx, cy) + view.cloudWaterMmAt(cx, cy) }
    override fun airTemperatureC(x: Int, y: Int) = if (isClimateMode) atlas.temperature[month][index(x, y)] else sample(x, y, view::temperatureKelvinAt) - 273.15
    override fun surfaceTemperatureC(x: Int, y: Int) = if (isClimateMode) airTemperatureC(x, y) else sample(x, y, view::surfaceTemperatureKelvinAt) - 273.15
    override fun windUTilesPerDay(x: Int, y: Int) = if (isClimateMode) atlas.east[month][index(x, y)] * 86400 * width / config.domainWidthMeters else sample(x, y, view.dynamics::eastWindAt) * 86400 * width / config.domainWidthMeters
    override fun windVTilesPerDay(x: Int, y: Int) = if (isClimateMode) atlas.south[month][index(x, y)] * 86400 * height / config.domainHeightMeters else -sample(x, y, view.dynamics::northWindAt) * 86400 * height / config.domainHeightMeters
    override fun windSpeedMetersPerSecond(x: Int, y: Int) = kotlin.math.hypot(
        windUTilesPerDay(x, y) * config.domainWidthMeters / width,
        windVTilesPerDay(x, y) * config.domainHeightMeters / height) / 86400
    override fun precipitationMmPerHour(x: Int, y: Int) = if (isClimateMode) thirtyDayRainMmPerHour(x, y) else if (updateSeconds > 0) rainMmThisUpdate(x, y) * 3600 / updateSeconds else 0.0
    override fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int) = if (isClimateMode) precipitationMmPerHour(x, y) else surface.peakRainRate[index(x, y)]
    override fun rainMmThisUpdate(x: Int, y: Int) = surface.rain[index(x, y)]
    override fun drainageMmThisUpdate(x: Int, y: Int) = surface.drainage[index(x, y)]
    override fun overflowMmThisUpdate(x: Int, y: Int) = surface.overflow[index(x, y)]
    override fun thirtyDayRainMmPerHour(x: Int, y: Int) = atlas.rain[month][index(x, y)] / (365.0 / 12 * 24)
    override fun monthlyClimateRainMm(x: Int, y: Int) = atlas.rain[month][index(x, y)]
    override fun climateAridity(x: Int, y: Int) = atlas.aridity(index(x, y))
    override fun meanClimateTemperatureC(x: Int, y: Int) = atlas.meanTemperature[index(x, y)]
    override fun annualizedRainMmPerYear(x: Int, y: Int) = atlas.annualRain[index(x, y)]
    override fun soilWaterMm(x: Int, y: Int) = surface.soil[index(x, y)]
    override fun soilCapacityMm(x: Int, y: Int) = surface.soilCapacityMm[index(x, y)]
    override fun soilMoisture(x: Int, y: Int) = soilWaterMm(x, y) / soilCapacityMm(x, y)
    override fun solarInputFactor(x: Int, y: Int): Double {
        sunlight.update(elapsedSeconds)
        return sunlight.inputFactor(x, y)
    }
    override fun nightOpacity(x: Int, y: Int): Double {
        sunlight.update(elapsedSeconds)
        return sunlight.nightOpacity(x, y)
    }
}
