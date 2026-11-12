package terra.simulation

import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.MoistAtmosphereDiagnostics
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologySnapshot
import terra.hydrology.HydrologyTopology
import terra.weather.*
import terra.render.CloudRenderer
import terra.render.RasterFrame
import terra.render.SurfaceWaterRasterizer
import terra.world.TerrainGenerationSettings
import terra.world.World
import terra.world.WorldSnapshot

enum class SimulationFidelity { DETAILED, CLIMATE }

data class WorldRequest(
    val generation: Long = 0, val seed: Int = 31742, val width: Int = 283, val height: Int = 212,
    val terrain: TerrainGenerationSettings = TerrainGenerationSettings(), val dynamical: Boolean = false,
    // Native-grid six-hour comparison: 40 s retains accuracy with ample CFL margin at half the steps.
    val atmosphere: DryAtmosphereConfig = DryAtmosphereConfig(stepSeconds = 40.0)
)

data class SimulationProgress(
    val generation: Long, val revision: Long, val appliedControlRevision: Long,
    val completedSeconds: Double, val selectedSpeed: SimulationSpeed, val achievedSecondsPerWallSecond: Double,
    val pendingSeconds: Double, val unservedRequestedSeconds: Double, val pendingLimitSeconds: Double,
    val paused: Boolean, val advanceWallSeconds: Double, val publicationWallSeconds: Double,
    val completedTransactions: Long,
    val automaticFidelity: Boolean = false, val quantumSeconds: Double = 20.0,
    val coarsenedForLoad: Boolean = false
) {
    val rateLimited: Boolean get() = pendingSeconds >= pendingLimitSeconds * 0.75 && !paused
}

data class WorldPresentationSnapshot(
    val progress: SimulationProgress, val world: WorldSnapshot, val weather: WeatherPresentation,
    val hydrology: HydrologySnapshot, val climateReady: Boolean, val initializationStatus: String,
    val climate: ClimateSnapshot?, val seasonalClimate: SeasonalClimateSnapshot?, val observedClimateYears: Int,
    val yearSeconds: Double, val dynamical: Boolean, val diagnostics: MoistAtmosphereDiagnostics?,
    val clouds: RasterFrame?, val surfaceWater: RasterFrame, val hydrologyCompletedSeconds: Double,
    val climateDiagnostics: ClimateIntervalDiagnostics? = null,
    val fidelity: SimulationFidelity = SimulationFidelity.DETAILED, val fidelityRevision: Long = 0,
    val detailedReentryPending: Boolean = false, val lastHandoff: terra.atmosphere.AtmosphereHandoff? = null,
    val solar: terra.atmosphere.SolarConfig = terra.atmosphere.SolarConfig()
)

/** Single-owner numerical state. Contains no graphics or threading; worker owns every call. */
internal class WorldSimulation(val request: WorldRequest) {
    private val world = World(request.seed, request.width, request.height, request.terrain)
    private val hydrology = HydrologySimulation(HydrologyTopology(world))
    private val weather: WeatherModel = if (request.dynamical) DynamicalWeather(world, hydrology, request.atmosphere) else WeatherSimulation(world)
    private val bootstrap = (weather as? WeatherSimulation)?.let { ClimateBootstrap(it) { elapsed -> hydrology.acceptWeatherStep(weather, elapsed) } }
    private var observed: ClimateSnapshot? = null
    private var nextClimateSeconds = 3600.0
    private var lastEcologySeconds = 0.0
    private var worldView: WorldSnapshot? = null
    private var weatherView: WeatherPresentation? = null
    private var hydrologyView: HydrologySnapshot? = null
    private var hydrologyViewSeconds = 0.0
    private var waterFrame: RasterFrame? = null
    private val waterRasterizer = SurfaceWaterRasterizer(request.width, request.height)
    private val cloudRenderer by lazy { CloudRenderer(request.width, request.height, request.seed) }
    private val weatherIdentity = Any()
    private var climateQuantumSeconds = FastClimateStepper.DAY_SECONDS
    var fidelity = SimulationFidelity.DETAILED
        private set
    var fidelityRevision = 0L
        private set
    var completedSeconds = 0.0
        private set
    val climateReady: Boolean get() = bootstrap?.isComplete != false
    val stepSeconds: Double get() = if (fidelity == SimulationFidelity.CLIMATE) climateQuantumSeconds
        else if (request.dynamical) request.atmosphere.stepSeconds else 0.05

    fun setClimateQuantum(seconds: Double) {
        require(seconds.isFinite() && seconds >= 1.0 && seconds <= 20 * 365 * 86400.0)
        climateQuantumSeconds = seconds
    }

    fun setFidelity(selected: SimulationFidelity) {
        val dynamical = weather as? DynamicalWeather ?: return
        if (selected == fidelity) return
        check(weather.pendingWaterFluxBatch?.consumed != false)
        if (selected == SimulationFidelity.DETAILED) dynamical.resumeDetailed()
        dynamical.dynamicsSuppressedForClimate = selected == SimulationFidelity.CLIMATE
        fidelity = selected
        fidelityRevision++
        weatherView = null
    }

    fun quantum(speed: SimulationSpeed): Double = if (request.dynamical) stepSeconds else
        minOf(10800.0, speed.simulatedSecondsPerRealSecond * 0.05)

    /** Whole completed transactions only; discarded presentation snapshots never carry transfers. */
    fun advance(pendingSeconds: Double, speed: SimulationSpeed): Double {
        if (!climateReady) {
            val before = weather.elapsedSeconds
            bootstrap!!.advanceBatch()
            val elapsed = weather.elapsedSeconds - before
            completedSeconds += elapsed
            if (climateReady) world.applyClimateBiomes(bootstrap.snapshot!!)
            return 0.0 // Initialization does not consume interactive time demand.
        }
        if (pendingSeconds < quantum(speed)) return 0.0
        if (fidelity == SimulationFidelity.CLIMATE) {
            advanceClimateInterval(stepSeconds)
            return stepSeconds
        }
        val advanced = if (weather is DynamicalWeather) weather.advanceBudgeted(pendingSeconds, 1, publishSnapshot = false) else {
            minOf(pendingSeconds, 10800.0).also { weather.step(it) }
        }
        hydrology.acceptWeatherStep(weather, advanced)
        if (weather is DynamicalWeather && weather.elapsedSeconds >= nextClimateSeconds) {
            observed = weather.observedClimate()
            nextClimateSeconds = weather.elapsedSeconds + 3600.0
            world.advanceClimateBiomes(weather.seasonalClimate, weather.elapsedSeconds - lastEcologySeconds)
            lastEcologySeconds = weather.elapsedSeconds
        }
        completedSeconds += advanced
        return advanced
    }

    /** Complete one explicitly requested climate interval; no worker speed policy is added here. */
    internal fun advanceClimateInterval(seconds: Double): ClimateIntervalDiagnostics {
        val detailed = weather as? DynamicalWeather ?: error("Climate intervals require the dynamical shared surface")
        check(detailed.elapsedSeconds == completedSeconds)
        val result = detailed.advanceClimateInterval(seconds)
        if (fidelity != SimulationFidelity.CLIMATE) { fidelity = SimulationFidelity.CLIMATE; fidelityRevision++ }
        world.advanceClimateBiomes(detailed.seasonalClimate, result.ecologyEligibleSeconds)
        completedSeconds = detailed.elapsedSeconds
        lastEcologySeconds = completedSeconds
        nextClimateSeconds = completedSeconds + 3600.0
        observed = null // The old detailed air-temperature integral has no coarse-air interpretation.
        return result
    }

    fun snapshot(progress: SimulationProgress, refreshHydrology: Boolean = true, cloudsVisible: Boolean = false, visualElapsedSeconds: Double = 0.0): WorldPresentationSnapshot {
        check(progress.generation == request.generation && progress.completedSeconds == completedSeconds)
        if (worldView?.biomeRevision != world.biomeRevision) worldView = world.snapshot(request.generation, worldView)
        if (weatherView?.elapsedSeconds != completedSeconds) {
            (weather as? DynamicalWeather)?.refreshSnapshot()
            weatherView = WeatherPresentation.capture(weather, completedSeconds, weatherIdentity)
        }
        if (hydrologyView == null || refreshHydrology && hydrologyView!!.routedPeriodRevision != hydrology.routedPeriodRevision) {
            hydrologyView = hydrology.snapshot()
            waterFrame = waterRasterizer.render(hydrologyView!!)
        }
        if (refreshHydrology) hydrologyViewSeconds = completedSeconds
        val cloudFrame = if (cloudsVisible) {
            cloudRenderer.update(weatherView!!, if (progress.paused) 0.0 else visualElapsedSeconds,
                detailedVisualSeconds = if (progress.paused || fidelity != SimulationFidelity.DETAILED) 0.0 else
                    visualElapsedSeconds * progress.selectedSpeed.simulatedSecondsPerRealSecond,
                maximumDetailedLeadSeconds = request.atmosphere.stepSeconds)
            cloudRenderer.frame
        } else null
        return WorldPresentationSnapshot(progress, worldView!!,
            weatherView!!, hydrologyView!!, climateReady,
            bootstrap?.status ?: if ((weather as? DynamicalWeather)?.isClimateMode == true) "Approximate climate interval" else "Detailed weather", bootstrap?.snapshot ?: observed,
            (weather as? DynamicalWeather)?.seasonalClimate, (weather as? DynamicalWeather)?.observedClimateYears ?: 0,
            (weather as? DynamicalWeather)?.physics?.solar?.yearSeconds ?: 365 * 86400.0,
            request.dynamical, (weather as? DynamicalWeather)?.takeUnless { it.hasRepresentativeFields }?.diagnostics,
            cloudFrame, waterFrame!!, hydrologyViewSeconds, (weather as? DynamicalWeather)?.climateDiagnostics,
            fidelity, fidelityRevision, (weather as? DynamicalWeather)?.detailedReentryPending == true,
            (weather as? DynamicalWeather)?.lastHandoff, (weather as? DynamicalWeather)?.physics?.solar ?: terra.atmosphere.SolarConfig())
    }
}

/** Bounded uncommitted rate demand. It never changes a physical reservoir or world clock. */
internal class SimulationDemand(private var stepSeconds: Double) {
    var pendingSeconds = 0.0
        private set
    var unservedSeconds = 0.0
        private set
    var rate = 1.0
        private set
    var paused = false
        private set
    val limitSeconds: Double get() = stepSeconds + maxOf(stepSeconds, rate * 0.25)
    fun controls(speed: SimulationSpeed, paused: Boolean, resetPending: Boolean = false, stepSeconds: Double = this.stepSeconds) {
        require(stepSeconds.isFinite() && stepSeconds > 0)
        this.stepSeconds = stepSeconds
        rate = speed.simulatedSecondsPerRealSecond
        this.paused = paused
        if (resetPending) { unservedSeconds += pendingSeconds; pendingSeconds = 0.0 }
        trim()
    }
    fun request(wallSeconds: Double) {
        require(wallSeconds.isFinite() && wallSeconds >= 0)
        if (paused) return
        // OS suspension never creates an unbounded catch-up queue. Unserved demand is visible.
        val interactive = minOf(wallSeconds, 0.25)
        unservedSeconds += (wallSeconds - interactive) * rate
        pendingSeconds += interactive * rate
        trim()
    }
    private fun trim() {
        val excess = maxOf(0.0, pendingSeconds - limitSeconds)
        pendingSeconds -= excess
        unservedSeconds += excess
    }
    fun consume(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0 && seconds <= pendingSeconds + 1e-8)
        pendingSeconds = maxOf(0.0, pendingSeconds - seconds)
    }
}

/** One dedicated JVM owner, coalesced controls, and a latest-only publication mailbox. */
expect class SimulationWorker(request: WorldRequest) {
    fun updateControls(speed: SimulationSpeed, paused: Boolean, resetPending: Boolean = false): Long
    fun setFidelity(fidelity: SimulationFidelity): Long
    fun useAutomaticFidelity(): Long
    fun setCloudsVisible(visible: Boolean)
    fun regenerate(request: WorldRequest)
    fun latestSnapshot(): WorldPresentationSnapshot?
    val failure: String?
    val isRunning: Boolean
    fun close()
}
