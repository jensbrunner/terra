package terra.world

/** Published cells are immutable; the backing array is never shared with a mutable World. */
class WorldSnapshot internal constructor(
    val generation: Long, val seed: Int, val width: Int, val height: Int, val biomeRevision: Long,
    private val cells: Array<Cell>, private val summary: String, private val terrainChanges: IntArray
) {
    operator fun get(x: Int, y: Int): Cell = cells[y * width + ((x % width) + width) % width]
    // Cumulative since generation, so dropping intermediate publications cannot lose dirty tiles.
    val changedTerrainCount: Int get() = terrainChanges.size
    fun changedTerrainIndex(index: Int): Int = terrainChanges[index]
    fun landBiomeSummary(): String = summary
}
