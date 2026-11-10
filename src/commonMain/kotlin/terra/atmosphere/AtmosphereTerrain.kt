package terra.atmosphere

import kotlin.math.max

/** Static elevation and north-up slopes on the existing equal-area overlap mapping.
 * Longitude derivatives wrap; latitude derivatives stop at polar walls. Averaging
 * positive fine-grid ascent preserves ridges that disappear in coarse mean slopes.
 */
class AtmosphereTerrain(private val mapping: AtmosphereTileMapping, elevationMeters: DoubleArray) {
    val meanElevationMeters = DoubleArray(mapping.config.cellCount)
    val slopeEast = DoubleArray(mapping.config.cellCount)
    val slopeNorth = DoubleArray(mapping.config.cellCount)
    private val fineSlopeEast = DoubleArray(elevationMeters.size)
    private val fineSlopeNorth = DoubleArray(elevationMeters.size)

    init {
        require(elevationMeters.size == mapping.tileWidth * mapping.tileHeight && elevationMeters.all { it.isFinite() })
        val dx = mapping.config.domainWidthMeters / mapping.tileWidth
        val dy = mapping.config.domainHeightMeters / mapping.tileHeight
        for (y in 0 until mapping.tileHeight) for (x in 0 until mapping.tileWidth) {
            val i = y * mapping.tileWidth + x
            val west = (x + mapping.tileWidth - 1) % mapping.tileWidth
            val east = (x + 1) % mapping.tileWidth
            val top = maxOf(0, y - 1)
            val bottom = minOf(mapping.tileHeight - 1, y + 1)
            if (mapping.tileWidth > 1) fineSlopeEast[i] =
                (elevationMeters[y * mapping.tileWidth + east] - elevationMeters[y * mapping.tileWidth + west]) / (2 * dx)
            if (bottom > top) fineSlopeNorth[i] =
                (elevationMeters[top * mapping.tileWidth + x] - elevationMeters[bottom * mapping.tileWidth + x]) / ((bottom - top) * dy)
        }
        for (cell in meanElevationMeters.indices) {
            mapping.forCell(cell) { tile, fraction -> meanElevationMeters[cell] += elevationMeters[tile] * fraction / mapping.tilesPerCell }
        }
        val config = mapping.config
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val cell = y * config.width + x
            val west = (x + config.width - 1) % config.width
            val east = (x + 1) % config.width
            val south = maxOf(0, y - 1)
            val north = minOf(config.height - 1, y + 1)
            slopeEast[cell] = (meanElevationMeters[y * config.width + east] - meanElevationMeters[y * config.width + west]) /
                (2 * config.dx)
            slopeNorth[cell] = (meanElevationMeters[north * config.width + x] - meanElevationMeters[south * config.width + x]) /
                ((north - south) * config.dy)
        }
    }

    fun upliftMetersPerSecond(cell: Int, eastWind: Double, northWind: Double): Double {
        var ascent = 0.0
        mapping.forCell(cell) { tile, fraction ->
            ascent += max(0.0, eastWind * fineSlopeEast[tile] + northWind * fineSlopeNorth[tile]) * fraction
        }
        return ascent / mapping.tilesPerCell
    }
}
