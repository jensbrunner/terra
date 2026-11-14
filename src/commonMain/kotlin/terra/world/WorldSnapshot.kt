package terra.world

/** Published cells are immutable; the backing array is never shared with a mutable World. */
class WorldSnapshot internal constructor(
    val generation: Long, val seed: Int, val width: Int, val height: Int, val biomeRevision: Long,
    private val cells: Array<Cell>, private val summary: String, private val terrainChanges: IntArray,
    private val trees: DoubleArray = DoubleArray(width * height),
    private val grasses: DoubleArray = DoubleArray(width * height),
    private val bare: DoubleArray = DoubleArray(width * height),
    private val evergreen: DoubleArray = DoubleArray(width * height),
    private val limitations: Array<GrowthLimitation> = Array(width * height) { GrowthLimitation.NONE },
    val geometryRevision: Long = 0
) {
    operator fun get(x: Int, y: Int): Cell = cells[y * width + ((x % width) + width) % width]
    fun treeCover(x: Int, y: Int): Double = trees[y * width + ((x % width) + width) % width]
    fun grassCover(x: Int, y: Int): Double = grasses[y * width + ((x % width) + width) % width]
    fun bareCover(x: Int, y: Int): Double = bare[y * width + ((x % width) + width) % width]
    fun evergreenFraction(x: Int, y: Int): Double = evergreen[y * width + ((x % width) + width) % width]
    fun growthLimitation(x: Int, y: Int): GrowthLimitation = limitations[y * width + ((x % width) + width) % width]
    // Cumulative since generation, so dropping intermediate publications cannot lose dirty tiles.
    val changedTerrainCount: Int get() = terrainChanges.size
    fun changedTerrainIndex(index: Int): Int = terrainChanges[index]
    fun landBiomeSummary(): String = summary
}
