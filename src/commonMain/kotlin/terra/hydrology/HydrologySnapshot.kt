package terra.hydrology

/** Static topology is copied once, private, and reused by immutable visual snapshots. */
internal class HydrologyGeography(
    val width: Int, val height: Int, val land: BooleanArray, val downstream: IntArray,
    val upstream: IntArray, val virtualSpill: BooleanArray, val lakeIds: IntArray
)

class HydrologySnapshot internal constructor(
    private val geography: HydrologyGeography, val routedPeriodRevision: Long,
    private val discharge: DoubleArray, private val wet: BooleanArray,
    private val storage: DoubleArray, private val level: DoubleArray,
    private val throughput: DoubleArray, val routedPeriodDays: Double, val aggregatedClimateInterval: Boolean
) {
    val width: Int get() = geography.width
    val height: Int get() = geography.height
    private fun index(x: Int, y: Int) = y * width + x
    fun isLandTile(x: Int, y: Int) = geography.land[index(x, y)]
    fun downstreamTileIndex(x: Int, y: Int) = geography.downstream[index(x, y)]
    fun upstreamLandTileCount(x: Int, y: Int) = geography.upstream[index(x, y)]
    fun isVirtualSpillLink(x: Int, y: Int) = geography.virtualSpill[index(x, y)]
    fun isLakeTileWet(x: Int, y: Int) = wet[index(x, y)]
    fun lakeIdAt(x: Int, y: Int) = geography.lakeIds[index(x, y)]
    fun isLakeCandidateTile(x: Int, y: Int) = lakeIdAt(x, y) >= 0
    fun lakeStorageMmTiles(lake: Int) = storage[lake]
    fun lakeWaterSurfaceElevation(lake: Int) = level[lake]
    fun routedMmTiles(x: Int, y: Int) = throughput[index(x, y)]
    fun dischargeMmTilesPerDay(x: Int, y: Int) = discharge[index(x, y)]
    fun isStreamTile(x: Int, y: Int) = isLandTile(x, y) &&
        upstreamLandTileCount(x, y) >= HydrologySimulation.MIN_STREAM_CONTRIBUTING_AREA_TILES &&
        dischargeMmTilesPerDay(x, y) >= HydrologySimulation.MIN_STREAM_DISCHARGE_MM_TILES_PER_DAY
}
