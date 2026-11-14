package terra.simulation

import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.MoistAtmosphereDiagnostics
import terra.hydrology.SurfaceWater
import terra.hydrology.SurfaceWaterSnapshot
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
    val terrain: TerrainGenerationSettings = TerrainGenerationSettings(),
    // Native-grid six-hour comparison: 40 s retains accuracy with ample CFL margin at half the steps.
    val atmosphere: DryAtmosphereConfig = DryAtmosphereConfig(stepSeconds = 40.0)
)

data class SimulationProgress(
    val generation: Long, val revision: Long, val appliedControlRevision: Long,
    val completedSeconds: Double, val selectedSpeed: SimulationSpeed, val achievedSecondsPerWallSecond: Double,
    val pendingSeconds: Double, val unservedRequestedSeconds: Double, val pendingLimitSeconds: Double,
    val paused: Boolean, val advanceWallSeconds: Double, val publicationWallSeconds: Double,
    val completedTransactions: Long,
    val automaticFidelity: Boolean = false, val quantumSeconds: Double = 20.0
) {
    val rateLimited: Boolean get() = pendingSeconds >= pendingLimitSeconds * 0.75 && !paused
}

data class WorldPresentationSnapshot(
    val progress: SimulationProgress, val world: WorldSnapshot, val weather: WeatherPresentation,
    val drainage: SurfaceWaterSnapshot,
    val seasonalClimate: SeasonalClimateSnapshot?, val observedClimateYears: Int,
    val yearSeconds: Double, val diagnostics: MoistAtmosphereDiagnostics?,
    val clouds: RasterFrame?, val surfaceWater: RasterFrame,
    val climateDiagnostics: ClimateIntervalDiagnostics? = null,
    val fidelity: SimulationFidelity = SimulationFidelity.DETAILED, val fidelityRevision: Long = 0,
    val lastHandoff: terra.atmosphere.AtmosphereHandoff? = null,
    val solar: terra.atmosphere.SolarConfig = terra.atmosphere.SolarConfig()
)

/** Single-owner numerical state. Contains no graphics or threading; worker owns every call. */
internal class WorldSimulation(val request: WorldRequest) {
    private val world = World(request.seed, request.width, request.height, request.terrain)
    private val drainageView = SurfaceWater(world).snapshot
    private val weather = DynamicalWeather(world, request.atmosphere)
    private var nextClimateSeconds = 3600.0
    private var lastEcologySeconds = 0.0
    private var worldView: WorldSnapshot? = null
    private var weatherView: WeatherPresentation? = null
    private val waterFrame = SurfaceWaterRasterizer(request.width, request.height).render(drainageView)
    private val cloudRenderer by lazy { CloudRenderer(request.width, request.height, request.seed) }
    private val weatherIdentity = Any()
    private var climateQuantumSeconds = FastClimateStepper.DAY_SECONDS
    var fidelity = SimulationFidelity.DETAILED
        private set
    var fidelityRevision = 0L
        private set
    var completedSeconds = 0.0
        private set
    val stepSeconds: Double get() = if (fidelity == SimulationFidelity.CLIMATE) climateQuantumSeconds
        else request.atmosphere.stepSeconds

    fun setClimateQuantum(seconds: Double) {
        require(seconds.isFinite() && seconds >= 1.0 && seconds <= 20 * 365 * 86400.0)
        climateQuantumSeconds = seconds
    }

    fun setFidelity(selected: SimulationFidelity) {
        if (selected == fidelity) return
        if (selected == SimulationFidelity.DETAILED) weather.resumeDetailed()
        weather.dynamicsSuppressedForClimate = selected == SimulationFidelity.CLIMATE
        fidelity = selected
        fidelityRevision++
        weatherView = null
    }

    fun quantum(speed: SimulationSpeed): Double = stepSeconds

    /** Whole completed transactions only; discarded presentation snapshots never carry transfers. */
    fun advance(pendingSeconds: Double, speed: SimulationSpeed): Double {
        if (pendingSeconds < quantum(speed)) return 0.0
        if (fidelity == SimulationFidelity.CLIMATE) {
            advanceClimateInterval(stepSeconds)
            return stepSeconds
        }
        val advanced = weather.advanceBudgeted(pendingSeconds, 1, publishSnapshot = false)
        if (weather.elapsedSeconds >= nextClimateSeconds) {
            nextClimateSeconds = weather.elapsedSeconds + 3600.0
            world.advanceClimateBiomes(weather.seasonalClimate, weather.elapsedSeconds - lastEcologySeconds)
            lastEcologySeconds = weather.elapsedSeconds
        }
        completedSeconds += advanced
        return advanced
    }

    /** Complete one explicitly requested climate interval; no worker speed policy is added here. */
    internal fun advanceClimateInterval(seconds: Double): ClimateIntervalDiagnostics {
        val detailed = weather
        check(detailed.elapsedSeconds == completedSeconds)
        val result = detailed.advanceClimateInterval(seconds)
        if (fidelity != SimulationFidelity.CLIMATE) { fidelity = SimulationFidelity.CLIMATE; fidelityRevision++ }
        completedSeconds = detailed.elapsedSeconds
        lastEcologySeconds = completedSeconds
        nextClimateSeconds = completedSeconds + 3600.0
        return result
    }

    fun snapshot(progress: SimulationProgress, cloudsVisible: Boolean = false, visualElapsedSeconds: Double = 0.0): WorldPresentationSnapshot {
        check(progress.generation == request.generation && progress.completedSeconds == completedSeconds)
        if (worldView?.biomeRevision != world.biomeRevision) worldView = world.snapshot(request.generation, worldView)
        if (weatherView?.elapsedSeconds != completedSeconds) {
            weather.refreshSnapshot()
            weatherView = WeatherPresentation.capture(weather, completedSeconds, weatherIdentity)
        }
        val cloudFrame = if (cloudsVisible) {
            cloudRenderer.update(weatherView!!, if (progress.paused) 0.0 else visualElapsedSeconds,
                detailedVisualSeconds = if (progress.paused || fidelity != SimulationFidelity.DETAILED) 0.0 else
                    visualElapsedSeconds * progress.selectedSpeed.simulatedSecondsPerRealSecond,
                maximumDetailedLeadSeconds = request.atmosphere.stepSeconds)
            cloudRenderer.frame
        } else null
        return WorldPresentationSnapshot(progress, worldView!!,
            weatherView!!, drainageView,
            weather.seasonalClimate, weather.observedClimateYears,
            weather.physics.solar.yearSeconds,
            weather.takeUnless { it.hasRepresentativeFields }?.diagnostics,
            cloudFrame, waterFrame, weather.climateDiagnostics,
            fidelity, fidelityRevision,
            weather.lastHandoff, weather.physics.solar)
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
        pendingSeconds += wallSeconds * rate
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
