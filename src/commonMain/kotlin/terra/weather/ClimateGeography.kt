package terra.weather

import terra.atmosphere.AtmosphereTileMapping
import terra.world.Terrain
import terra.world.World
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Static geography seen by the reduced atmosphere. Nothing here is a physical store:
 * every field is reproducible from the immutable terrain and the current land cover.
 *
 * Terrain statistics are deliberately precomputed at two scales. Mean elevation drives
 * the thermal lapse and steering, while directional fine-grid ascent preserves ridges
 * that disappear when a climate cell averages several hundred kilometres.
 */
internal class ClimateGeography(val world: World, val grid: ClimateGrid) {
    val config = grid.config
    val count = grid.count
    private val tileMapping = AtmosphereTileMapping(world.width, world.height, config)
    private val tileWidth = world.width
    private val tileHeight = world.height
    private val tileCount = tileWidth * tileHeight

    val landFraction = DoubleArray(count)
    val elevationMeters = DoubleArray(count)
    val elevationMaximum = DoubleArray(count)
    val elevationMinimum = DoubleArray(count)
    val ridgeHeight = DoubleArray(count)

    /** Smoothed large-scale gradient, used for steering and lee descent. */
    val slopeEast = DoubleArray(count)
    val slopeNorth = DoubleArray(count)

    /** Fine-tile gradient aggregated to the climate cell; retains subgrid rain shadows. */
    private val fineSlopeEast = DoubleArray(tileCount)
    private val fineSlopeNorth = DoubleArray(tileCount)

    /** Cell distance to the nearest ocean in eight directions (0 over ocean). */
    val coastalDistance = DoubleArray(count)
    val coastalExposure = DoubleArray(count)

    /** Connected ocean basin identifier; -1 for land. No inter-basin water teleportation. */
    val oceanBasin = IntArray(count) { -1 }
    val oceanCellCount = IntArray(0)

    /** Fraction of the surrounding land that is covered by lakes; refreshed by hydrology. */
    val lakeFraction = DoubleArray(count)

    /** A broad, static aridity tendency from elevation and distance to ocean, never a biome rule. */
    val continentality = DoubleArray(count)

    private val scratch = DoubleArray(count)

    init {
        val dx = config.domainWidthMeters / tileWidth
        val dy = config.domainHeightMeters / tileHeight
        for (y in 0 until tileHeight) for (x in 0 until tileWidth) {
            val i = y * tileWidth + x
            val west = (x + tileWidth - 1) % tileWidth
            val east = (x + 1) % tileWidth
            val top = maxOf(0, y - 1)
            val bottom = minOf(tileHeight - 1, y + 1)
            if (tileWidth > 1) fineSlopeEast[i] =
                (world[east, y].elevation - world[west, y].elevation) * ELEVATION_METERS_PER_UNIT / (2 * dx)
            if (bottom > top) fineSlopeNorth[i] =
                (world[x, top].elevation - world[x, bottom].elevation) * ELEVATION_METERS_PER_UNIT / ((bottom - top) * dy)
        }
        for (cell in 0 until count) {
            var land = 0.0
            var sum = 0.0
            var maximum = 0.0
            var minimum = Double.POSITIVE_INFINITY
            tileMapping.forCell(cell) { tile, fraction ->
                val cell0 = world[tile % tileWidth, tile / tileWidth]
                val elevation = max(0.0, (cell0.elevation - world.seaLevel) * ELEVATION_METERS_PER_UNIT)
                if (cell0.terrain != Terrain.OCEAN) land += fraction
                sum += elevation * fraction
                maximum = max(maximum, elevation)
                minimum = min(minimum, elevation)
            }
            landFraction[cell] = land / tileMapping.tilesPerCell
            elevationMeters[cell] = sum / tileMapping.tilesPerCell
            elevationMaximum[cell] = maximum
            elevationMinimum[cell] = if (minimum.isFinite()) minimum else 0.0
            ridgeHeight[cell] = max(0.0, maximum - elevationMeters[cell])
        }
        val coarseDx = config.dx
        val coarseDy = config.dy
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val cell = y * config.width + x
            val west = (x + config.width - 1) % config.width
            val east = (x + 1) % config.width
            val south = maxOf(0, y - 1)
            val north = minOf(config.height - 1, y + 1)
            slopeEast[cell] = (elevationMeters[y * config.width + east] - elevationMeters[y * config.width + west]) /
                (2 * coarseDx)
            slopeNorth[cell] = (elevationMeters[north * config.width + x] - elevationMeters[south * config.width + x]) /
                ((north - south) * coarseDy)
        }
        computeCoastAndBasins()
        for (cell in 0 until count) {
            val maritime = coastalExposure[cell]
            continentality[cell] = ((1 - maritime) * (1 - landFraction[cell] * 0.3) +
                elevationMeters[cell] / 6000.0).coerceIn(0.0, 1.0)
        }
    }

    private fun computeCoastAndBasins() {
        // Two-pass geodesic distance proxy on the coarse mesh.
        val distance = DoubleArray(count) { if (landFraction[it] < 0.5) 0.0 else Double.POSITIVE_INFINITY }
        val maxDistance = max(config.width, config.height).toDouble()
        for (pass in 0 until MAX_DISTANCE_PASSES) {
            var changed = false
            for (y in 0 until config.height) for (x in 0 until config.width) {
                val cell = y * config.width + x
                if (distance[cell] == 0.0) continue
                val west = y * config.width + (x + config.width - 1) % config.width
                val east = y * config.width + (x + 1) % config.width
                val south = maxOf(0, y - 1) * config.width + x
                val north = minOf(config.height - 1, y + 1) * config.width + x
                val best = 1.0 + minOf(distance[west], distance[east], distance[south], distance[north])
                if (best < distance[cell]) { distance[cell] = best; changed = true }
            }
            if (!changed) break
        }
        for (cell in 0 until count) {
            coastalDistance[cell] = if (distance[cell].isFinite()) min(distance[cell], maxDistance) else maxDistance
        }
        // Fraction of ocean within a fixed physical radius (~600 km).
        val radius = COASTAL_RADIUS_CELLS
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val cell = y * config.width + x
            var ocean = 0.0
            var total = 0.0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (dx * dx + dy * dy > radius * radius) continue
                val ny = (y + dy).coerceIn(0, config.height - 1)
                val nx = (x + dx + config.width) % config.width
                val weight = 1.0 - hypot(dx.toDouble(), dy.toDouble()) / (radius + 1.0)
                if (weight <= 0.0) continue
                total += weight
                ocean += (1 - landFraction[ny * config.width + nx]) * weight
            }
            coastalExposure[cell] = if (total > 0) ocean / total else 0.0
        }
        // Connected ocean basins. Cells connected only through periodic longitude.
        var nextBasin = 0
        val queue = IntArray(count)
        for (start in 0 until count) {
            if (landFraction[start] >= 0.5 || oceanBasin[start] >= 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            oceanBasin[start] = nextBasin
            while (head < tail) {
                val cell = queue[head++]
                val x = cell % config.width
                val y = cell / config.width
                val west = y * config.width + (x + config.width - 1) % config.width
                val east = y * config.width + (x + 1) % config.width
                val south = maxOf(0, y - 1) * config.width + x
                val north = minOf(config.height - 1, y + 1) * config.width + x
                for (next in intArrayOf(west, east, south, north)) {
                    if (landFraction[next] < 0.5 && oceanBasin[next] < 0) {
                        oceanBasin[next] = nextBasin
                        queue[tail++] = next
                    }
                }
            }
            nextBasin++
        }
    }

    fun isOcean(cell: Int): Boolean = landFraction[cell] < 0.5

    /**
     * Area-weighted positive terrain ascent for a climate cell, using the fine slopes.
     * Negative (descending) components are excluded here; lee drying is applied through
     * the low-level descent diagnostic.
     */
    fun upliftMetersPerSecond(cell: Int, eastWind: Double, northWind: Double): Double {
        var ascent = 0.0
        tileMapping.forCell(cell) { tile, fraction ->
            ascent += max(0.0, eastWind * fineSlopeEast[tile] + northWind * fineSlopeNorth[tile]) * fraction
        }
        return ascent / tileMapping.tilesPerCell
    }

    /** Positive downslope descent along the flow, in m/s. */
    fun descentMetersPerSecond(cell: Int, eastWind: Double, northWind: Double): Double {
        var descent = 0.0
        tileMapping.forCell(cell) { tile, fraction ->
            descent += max(0.0, -(eastWind * fineSlopeEast[tile] + northWind * fineSlopeNorth[tile])) * fraction
        }
        return descent / tileMapping.tilesPerCell
    }

    /** Ocean fraction encountered within [distanceCells] cells along (east,north). */
    fun oceanFetch(cell: Int, eastWind: Double, northWind: Double, distanceCells: Int = OCEAN_FETCH_CELLS): Double {
        val speed = hypot(eastWind, northWind)
        if (speed < 1e-9) return 1 - landFraction[cell]
        val dx = eastWind / speed
        val dy = northWind / speed
        var x = cell % config.width + 0.5
        var y = cell / config.width + 0.5
        var ocean = 0.0
        var samples = 0
        repeat(distanceCells) {
            x += dx
            y += dy
            val ix = ((x.toInt() % config.width) + config.width) % config.width
            val iy = y.toInt().coerceIn(0, config.height - 1)
            ocean += 1 - landFraction[iy * config.width + ix]
            samples++
        }
        return if (samples > 0) ocean / samples else 0.0
    }

    /** Smoothed land fraction used by the energy and circulation closures. */
    fun smoothedLand(): DoubleArray {
        scratch.fill(0.0)
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val cell = y * config.width + x
            val west = y * config.width + (x + config.width - 1) % config.width
            val east = y * config.width + (x + 1) % config.width
            val south = maxOf(0, y - 1) * config.width + x
            val north = minOf(config.height - 1, y + 1) * config.width + x
            scratch[cell] = 0.5 * landFraction[cell] + 0.125 *
                (landFraction[west] + landFraction[east] + landFraction[south] + landFraction[north])
        }
        return scratch.copyOf()
    }

    /** Ridge orientation for display/diagnostics; unit vector normal to the local ridge. */
    fun ridgeOrientation(cell: Int): Pair<Double, Double> {
        val ex = slopeEast[cell]
        val ey = slopeNorth[cell]
        val length = hypot(ex, ey)
        return if (length < 1e-9) 0.0 to 0.0 else ex / length to ey / length
    }

    fun dominantRidgeAngle(cell: Int): Double {
        val (ex, ey) = ridgeOrientation(cell)
        return atan2(ey, ex)
    }

    companion object {
        const val ELEVATION_METERS_PER_UNIT = 10_000.0
        const val MAX_DISTANCE_PASSES = 400
        const val COASTAL_RADIUS_CELLS = 4
        const val OCEAN_FETCH_CELLS = 6
    }
}
