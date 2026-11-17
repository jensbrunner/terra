package terra.simulation

import terra.MAP_WIDTH_TILES
import terra.MAP_HEIGHT_TILES
import terra.climate.*
import terra.hydrology.SurfaceWaterSnapshot
import terra.render.RasterFrame
import terra.render.SurfaceWaterRasterizer
import terra.world.TerrainGenerationSettings
import terra.world.World
import terra.world.WorldSnapshot

data class WorldRequest(
    val generation: Long = 0, val seed: Int = 31742,
    val width: Int = MAP_WIDTH_TILES, val height: Int = MAP_HEIGHT_TILES,
    val terrain: TerrainGenerationSettings = TerrainGenerationSettings()
)

data class SimulationProgress(
    val generation: Long, val completedSeconds: Double, val achievedSecondsPerWallSecond: Double,
    val pendingSeconds: Double, val paused: Boolean, val quantumSeconds: Double,
    val rateLimited: Boolean
)

data class WorldPresentationSnapshot(
    val progress: SimulationProgress, val world: WorldSnapshot, val weather: EnvironmentSnapshot,
    val drainage: SurfaceWaterSnapshot, val surfaceWater: RasterFrame
)

/** Mutable physics belongs to one worker; only immutable snapshots leave it. */
internal class WorldSimulation(val request: WorldRequest) {
    private val world = World(request.seed, request.width, request.height, request.terrain)
    private val climate = ClimateSimulation(world)
    private var worldView = world.snapshot(request.generation)
    private val waterRasterizer = SurfaceWaterRasterizer(request.width, request.height)
    private var waterView: SurfaceWaterSnapshot? = null
    private lateinit var waterFrame: RasterFrame
    val completedSeconds get() = climate.elapsedSeconds
    fun advance(seconds: Double) = climate.advance(seconds)
    fun snapshot(progress: SimulationProgress): WorldPresentationSnapshot {
        if (worldView.biomeRevision != world.biomeRevision) worldView = world.snapshot(request.generation, worldView)
        val drainage = climate.surfaceState.surfaceWater.snapshot()
        if (drainage !== waterView) {
            waterFrame = waterRasterizer.render(drainage)
            waterView = drainage
        }
        return WorldPresentationSnapshot(progress, worldView, climate.snapshot(), drainage,
            waterFrame)
    }
}

expect class SimulationWorker(request: WorldRequest) {
    fun updateControls(speed: SimulationSpeed, paused: Boolean)
    fun regenerate(request: WorldRequest)
    fun latestSnapshot(): WorldPresentationSnapshot?
    val failure: String?
    fun close()
}
