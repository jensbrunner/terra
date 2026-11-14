package terra.simulation

import terra.climate.SolarConfig
import terra.hydrology.SurfaceWaterSnapshot
import terra.climate.*
import terra.render.RasterFrame
import terra.render.SurfaceWaterRasterizer
import terra.world.TerrainGenerationSettings
import terra.world.World
import terra.world.WorldSnapshot

data class TerrainElevationEdit(val x: Int, val y: Int, val elevation: Double)

data class WorldRequest(
    val generation: Long = 0, val seed: Int = 31744, val width: Int = 283, val height: Int = 212,
    val terrain: TerrainGenerationSettings = TerrainGenerationSettings(),
    val climate: ClimateConfig = ClimateConfig(width = width, height = height), val solar: SolarConfig = SolarConfig()
)

data class SimulationProgress(
    val generation: Long, val revision: Long, val appliedControlRevision: Long,
    val completedSeconds: Double, val selectedSpeed: SimulationSpeed, val achievedSecondsPerWallSecond: Double,
    val pendingSeconds: Double, val unservedRequestedSeconds: Double, val pendingLimitSeconds: Double,
    val paused: Boolean, val advanceWallSeconds: Double, val publicationWallSeconds: Double,
    val completedTransactions: Long, val quantumSeconds: Double = 1.0
) {
    val rateLimited: Boolean get() = pendingSeconds >= pendingLimitSeconds * 0.75 && !paused
}

data class WorldPresentationSnapshot(
    val progress: SimulationProgress, val world: WorldSnapshot, val weather: EnvironmentSnapshot,
    val drainage: SurfaceWaterSnapshot, val yearSeconds: Double, val surfaceWater: RasterFrame,
    val solar: SolarConfig
)

/** Single owner of the atlas and persistent landscape. Speed only changes batch size. */
internal class WorldSimulation(val request: WorldRequest) {
    private val world = World(request.seed, request.width, request.height, request.terrain)
    private val climate = ClimateSimulation(world, request.climate, request.solar)
    private var worldView: WorldSnapshot? = null
    private var weatherView: EnvironmentSnapshot? = null
    private var drainageView = climate.surfaceState.surfaceWater.snapshot()
    private val waterRasterizer = SurfaceWaterRasterizer(request.width, request.height)
    private var waterFrame = waterRasterizer.render(drainageView)
    private var waterViewSeconds = 0.0
    val completedSeconds get() = climate.elapsedSeconds
    fun editTerrain(edits: List<TerrainElevationEdit>) {
        for (edit in edits) world.setElevation(edit.x, edit.y, edit.elevation)
        climate.ensureAtlas()
        worldView = null; weatherView = null
        drainageView = climate.surfaceState.surfaceWater.snapshot()
        waterFrame = waterRasterizer.render(drainageView)
    }
    fun advance(seconds: Double): ClimateIntervalDiagnostics = climate.advance(seconds)
    fun snapshot(progress: SimulationProgress): WorldPresentationSnapshot {
        check(progress.generation == request.generation && progress.completedSeconds == completedSeconds)
        if (worldView?.biomeRevision != world.biomeRevision) worldView = world.snapshot(request.generation, worldView)
        if (weatherView?.elapsedSeconds != completedSeconds) weatherView = climate.snapshot()
        drainageView = climate.surfaceState.surfaceWater.snapshot()
        if (progress.paused || completedSeconds - waterViewSeconds >=
            maxOf(21600.0, progress.selectedSpeed.simulatedSecondsPerRealSecond * 0.5)) {
            waterFrame = waterRasterizer.render(drainageView)
            waterViewSeconds = completedSeconds
        }
        return WorldPresentationSnapshot(progress, worldView!!, weatherView!!, drainageView,
            request.solar.yearSeconds, waterFrame, request.solar)
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

/** Dedicated JVM owner and latest-only immutable publication. */
expect class SimulationWorker(request: WorldRequest) {
    fun updateControls(speed: SimulationSpeed, paused: Boolean, resetPending: Boolean = false): Long
    fun editTerrain(edits: List<TerrainElevationEdit>)
    fun regenerate(request: WorldRequest)
    fun latestSnapshot(): WorldPresentationSnapshot?
    val failure: String?
    val isRunning: Boolean
    fun close()
}
