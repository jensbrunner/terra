package terra.atmosphere

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Exact rectangle intersections on an equal-area plane. Atmosphere Y is north; tile Y is south. */
class AtmosphereTileMapping(val tileWidth: Int, val tileHeight: Int, val config: DryAtmosphereConfig) {
    val offsets: IntArray
    val tiles: IntArray
    /** Fraction of one fine tile occupied by this intersection. */
    val tileFractions: DoubleArray
    /** The atmosphere cell that contains this world tile (largest overlap). */
    val cellOfTile: IntArray = IntArray(tileWidth * tileHeight) { -1 }
    val tilesPerCell = tileWidth.toDouble() * tileHeight / config.cellCount
    val tileAreaM2 = config.cellArea / tilesPerCell

    init {
        require(tileWidth > 0 && tileHeight > 0)
        val indices = ArrayList<Int>()
        val fractions = ArrayList<Double>()
        offsets = IntArray(config.cellCount + 1)
        for (cy in 0 until config.height) for (cx in 0 until config.width) {
            val c = cy * config.width + cx
            offsets[c] = indices.size
            val left = cx.toDouble() * tileWidth / config.width
            val right = (cx + 1.0) * tileWidth / config.width
            val top = (config.height - cy - 1.0) * tileHeight / config.height
            val bottom = (config.height - cy.toDouble()) * tileHeight / config.height
            for (y in floor(top).toInt() until min(tileHeight, ceil(bottom).toInt())) {
                for (x in floor(left).toInt() until min(tileWidth, ceil(right).toInt())) {
                    val area = max(0.0, min(right, x + 1.0) - max(left, x.toDouble())) *
                        max(0.0, min(bottom, y + 1.0) - max(top, y.toDouble()))
                    if (area > 0.0) { indices.add(y * tileWidth + x); fractions.add(area) }
                }
            }
        }
        offsets[config.cellCount] = indices.size
        tiles = indices.toIntArray()
        tileFractions = fractions.toDoubleArray()
        // Inverse map for per-tile owners that need their containing atmosphere cell.
        val best = DoubleArray(tileWidth * tileHeight)
        for (cell in 0 until config.cellCount) {
            for (j in offsets[cell] until offsets[cell + 1]) {
                val tile = tiles[j]
                if (tileFractions[j] > best[tile]) { best[tile] = tileFractions[j]; cellOfTile[tile] = cell }
            }
        }
    }

    inline fun forCell(cell: Int, action: (tile: Int, tileFraction: Double) -> Unit) {
        for (j in offsets[cell] until offsets[cell + 1]) action(tiles[j], tileFractions[j])
    }
}
