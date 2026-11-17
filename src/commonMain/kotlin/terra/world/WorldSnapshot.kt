package terra.world

/** Published cells are immutable; the backing array is never shared with a mutable World. */
class WorldSnapshot internal constructor(
    val generation: Long, val width: Int, val height: Int, val biomeRevision: Long,
    val geographyRevision: Long, val seaLevel: Double, private val plateIds: IntArray,
    val plateCount: Int, val movingPlateCount: Int, private val plateBoundaries: ByteArray,
    private val cells: Array<Cell>, private val summary: String, private val terrainChanges: IntArray,
    private val trees: DoubleArray,
    private val grasses: DoubleArray,
    private val limitations: Array<GrowthLimitation>
) {
    operator fun get(x: Int, y: Int): Cell = cells[worldIndex(x, y, width, height)]
    fun plateId(x: Int, y: Int) = plateIds[worldIndex(x, y, width, height)]
    fun plateBoundary(x: Int, y: Int) = PlateBoundary.entries[plateBoundaries[worldIndex(x, y, width, height)].toInt()]
    fun treeCover(x: Int, y: Int): Double = trees[worldIndex(x, y, width, height)]
    fun grassCover(x: Int, y: Int): Double = grasses[worldIndex(x, y, width, height)]
    fun bareCover(x: Int, y: Int): Double = 1 - treeCover(x, y) - grassCover(x, y)
    fun growthLimitation(x: Int, y: Int): GrowthLimitation = limitations[worldIndex(x, y, width, height)]
    // Cumulative since generation, so dropping intermediate publications cannot lose dirty tiles.
    val changedTerrainCount: Int get() = terrainChanges.size
    fun changedTerrainIndex(index: Int): Int = terrainChanges[index]
    fun landBiomeSummary(): String = summary
}
