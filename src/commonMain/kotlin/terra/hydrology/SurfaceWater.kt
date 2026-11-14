package terra.hydrology

import terra.world.World

/** Fixed drainage and lake geography, generated once. Weather never changes its
 * paths, widths, lake footprints, or raster during this world's lifetime.
 */
internal class SurfaceWater(world: World) {
    private val topology = HydrologyTopology(world)
    private val count = world.width * world.height
    private val lakes = IntArray(count) { -1 }
    private val geography: SurfaceWaterGeography
    private val wet = BooleanArray(count)
    val snapshot: SurfaceWaterSnapshot
    init {
        for (lake in topology.lakeCandidates) for (tile in lake.tileIndices) lakes[tile] = lake.id
        geography = SurfaceWaterGeography(world.width, world.height,
            BooleanArray(count) { topology.isLandTile(it) }, topology.downstreamIndex,
            topology.upstreamLandTileCount, BooleanArray(count) { topology.isVirtualSpillLink(it) }, lakes)
        for (lake in topology.lakeCandidates) {
            if (!lake.spillElevation.isFinite()) continue
            for (tile in lake.tileIndices) wet[tile] = topology.terrainElevation[tile] < lake.spillElevation
        }
        snapshot = SurfaceWaterSnapshot(geography, wet)
    }
}

internal class SurfaceWaterGeography(
    val width: Int, val height: Int, val land: BooleanArray, val downstream: IntArray,
    val upstream: IntArray, val virtualSpill: BooleanArray, val lakeIds: IntArray
)

class SurfaceWaterSnapshot internal constructor(
    private val geography: SurfaceWaterGeography, private val wet: BooleanArray
) {
    val width get() = geography.width
    val height get() = geography.height
    private fun index(x: Int, y: Int) = y * width + x
    fun isLandTile(x: Int, y: Int) = geography.land[index(x, y)]
    fun downstreamTileIndex(x: Int, y: Int) = geography.downstream[index(x, y)]
    fun upstreamLandTileCount(x: Int, y: Int) = geography.upstream[index(x, y)]
    fun isVirtualSpillLink(x: Int, y: Int) = geography.virtualSpill[index(x, y)]
    fun isLakeTileWet(x: Int, y: Int) = wet[index(x, y)]
    fun lakeIdAt(x: Int, y: Int) = geography.lakeIds[index(x, y)]
    fun isLakeCandidateTile(x: Int, y: Int) = lakeIdAt(x, y) >= 0
    fun isStreamTile(x: Int, y: Int) = isLandTile(x, y) && upstreamLandTileCount(x, y) >= 12
}
