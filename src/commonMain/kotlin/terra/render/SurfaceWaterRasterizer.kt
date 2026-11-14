package terra.render

import korlibs.image.color.RGBA
import korlibs.math.geom.Point
import terra.hydrology.SurfaceWaterSnapshot
import kotlin.math.*

/** Curved drainage channels rendered into a transparent, anti-aliased water texture.
 * Geometry stays fixed as discharge changes; only the channel widths respond to flow. */
internal class SurfaceWaterRasterizer(private val width: Int, private val height: Int) {
    private val rasterWidth = width * PIXELS_PER_TILE
    private val rasterHeight = height * PIXELS_PER_TILE
    private val mainTributary = IntArray(width * height)
    private val channelDepth = FloatArray(rasterWidth * rasterHeight)

    fun render(hydrology: SurfaceWaterSnapshot): RasterFrame {
        val canvas = RasterCanvas(rasterWidth, rasterHeight)
        channelDepth.fill(Float.NEGATIVE_INFINITY)
        mainTributary.fill(-1)
        for (i in mainTributary.indices) {
            val x = i % width; val y = i / width
            if (!hydrology.isStreamTile(x, y)) continue
            val next = downstream(hydrology, i)
            if (next < 0) continue
            val previous = mainTributary[next]
            if (previous < 0 || discharge(hydrology, i) > discharge(hydrology, previous)) mainTributary[next] = i
        }
        for (i in mainTributary.indices) {
            val x = i % width; val y = i / width
            if (!hydrology.isStreamTile(x, y)) continue
            val next = downstream(hydrology, i)
            if (next < 0) continue
            val nx = next % width; val ny = next / width
            val wetLake = hydrology.isLakeTileWet(nx, ny)
            val dryLake = hydrology.isLakeCandidateTile(nx, ny) && !wetLake
            if (hydrology.isLandTile(nx, ny) && !hydrology.isStreamTile(nx, ny) && !wetLake && !dryLake) continue

            val start = center(i)
            var end = near(center(next), start)
            // A dry basin is a real interruption, not a license to paint a river across it.
            if (dryLake) end = Point((start.x + end.x) / 2, (start.y + end.y) / 2)
            val length = hypot(end.x - start.x, end.y - start.y)
            val a = tangent(hydrology, i)
            val b = if (dryLake) a else tangent(hydrology, next)
            val controlA = Point(start.x + a.x * length * 0.34, start.y + a.y * length * 0.34)
            val controlB = Point(end.x - b.x * length * 0.34, end.y - b.y * length * 0.34)
            val radius = radius(discharge(hydrology, i))
            val startRadius = if (mainTributary[i] < 0) radius * 0.35 else radius
            val endRadius = if (hydrology.isStreamTile(nx, ny)) radius(discharge(hydrology, next)) else radius
            var previous = start
            val steps = max(4, ceil(length * PIXELS_PER_TILE).toInt())
            for (step in 1..steps) {
                val t = step.toDouble() / steps
                val u = 1 - t
                val point = Point(u * u * u * start.x + 3 * u * u * t * controlA.x + 3 * u * t * t * controlB.x + t * t * t * end.x,
                    u * u * u * start.y + 3 * u * u * t * controlA.y + 3 * u * t * t * controlB.y + t * t * t * end.y)
                stroke(previous, point, startRadius + (endRadius - startRadius) * (step - 1.0) / steps,
                    startRadius + (endRadius - startRadius) * t)
                previous = point
            }
        }
        shade(canvas, hydrology)
        return canvas.finish()
    }

    /** Share a tangent at every junction, with the largest tributary defining the main channel. */
    private fun tangent(hydrology: SurfaceWaterSnapshot, tile: Int): Point {
        val point = center(tile)
        val upstream = mainTributary[tile]
        val next = downstream(hydrology, tile)
        val before = if (upstream >= 0) near(center(upstream), point) else point
        val after = if (next >= 0) near(center(next), point) else point
        val dx = after.x - before.x; val dy = after.y - before.y
        val length = hypot(dx, dy)
        return if (length > 1e-9) Point(dx / length, dy / length) else Point(0.0, 1.0)
    }

    private fun downstream(hydrology: SurfaceWaterSnapshot, tile: Int): Int {
        val x = tile % width; val y = tile / width
        if (hydrology.isVirtualSpillLink(x, y)) return -1
        val next = hydrology.downstreamTileIndex(x, y)
        if (next < 0) return -1
        val dx = abs(next % width - x)
        return if (min(dx, width - dx) <= 1 && abs(next / width - y) <= 1) next else -1
    }

    private fun discharge(hydrology: SurfaceWaterSnapshot, tile: Int) = hydrology.dischargeMmTilesPerDay(tile % width, tile / width)
    private fun radius(discharge: Double) = 0.09 + 0.15 * (ln1p(discharge) / ln1p(3000.0)).coerceIn(0.0, 1.0)

    /** Stable sub-tile offsets remove the visible lattice without moving water between catchments. */
    private fun center(tile: Int): Point {
        var hash = tile * 0x45d9f3b
        hash = (hash xor (hash ushr 16)) * 0x45d9f3b
        hash = hash xor (hash ushr 16)
        return Point(tile % width + 0.5 + ((hash and 65535) / 65535.0 - 0.5) * 0.28,
            tile / width + 0.5 + ((hash ushr 16) / 65535.0 - 0.5) * 0.28)
    }

    private fun near(point: Point, origin: Point): Point =
        Point(origin.x + (point.x - origin.x + width * 1.5) % width - width / 2.0, point.y)

    /** Union signed channel distances, so tributary banks never cut through the main river. */
    private fun stroke(start: Point, end: Point, startRadius: Double, endRadius: Double) {
        val scale = PIXELS_PER_TILE.toDouble()
        val ax = start.x * scale; val ay = start.y * scale
        val bx = end.x * scale; val by = end.y * scale
        val dx = bx - ax; val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val padding = max(startRadius, endRadius) * scale + BANK_WIDTH * scale + 1
        val left = floor(min(ax, bx) - padding).toInt()
        val right = ceil(max(ax, bx) + padding).toInt()
        val top = max(0, floor(min(ay, by) - padding).toInt())
        val bottom = min(rasterHeight - 1, ceil(max(ay, by) + padding).toInt())
        for (y in top..bottom) for (x in left..right) {
            val t = if (lengthSquared > 0) (((x + 0.5 - ax) * dx + (y + 0.5 - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
            val distance = hypot(x + 0.5 - ax - t * dx, y + 0.5 - ay - t * dy)
            val depth = ((startRadius + (endRadius - startRadius) * t) * scale - distance).toFloat()
            val i = y * rasterWidth + x.mod(rasterWidth)
            if (depth > channelDepth[i]) channelDepth[i] = depth
        }
    }

    private fun shade(canvas: RasterCanvas, hydrology: SurfaceWaterSnapshot) {
        val scale = PIXELS_PER_TILE.toDouble()
        fun wet(x: Int, y: Int) = if (y in 0 until height && hydrology.isLakeTileWet(x.mod(width), y)) 1.0 else 0.0
        for (y in 0 until rasterHeight) for (x in 0 until rasterWidth) {
            val depth = channelDepth[y * rasterWidth + x].toDouble()
            var bank = smooth(-0.65, 0.65, depth + BANK_WIDTH * scale)
            var water = smooth(-0.65, 0.65, depth)
            // Bilinear wet-cell coverage rounds lake shores and unifies their river entrances.
            val gx = (x + 0.5) / scale - 0.5; val gy = (y + 0.5) / scale - 0.5
            val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
            val fx = gx - ix; val fy = gy - iy
            val lake = (wet(ix, iy) * (1 - fx) + wet(ix + 1, iy) * fx) * (1 - fy) +
                (wet(ix, iy + 1) * (1 - fx) + wet(ix + 1, iy + 1) * fx) * fy
            bank = max(bank, smooth(0.34, 0.52, lake))
            water = max(water, smooth(0.43, 0.61, lake))
            if (bank <= 0.0) continue
            val core = if (depth.isFinite()) smooth(0.0, scale * 0.18, depth) * (1 - smooth(0.4, 0.65, lake)) else 0.0
            val light = 1 - core * 0.22
            val red = 24 + (65 * light - 24) * water
            val green = 53 + (154 * light - 53) * water
            val blue = 57 + (184 * light - 57) * water
            canvas[x, y] = RGBA(red.roundToInt(), green.roundToInt(), blue.roundToInt(), (bank * 238).roundToInt())
        }
    }

    private fun smooth(low: Double, high: Double, value: Double): Double {
        val t = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    companion object {
        const val PIXELS_PER_TILE = 6
        private const val BANK_WIDTH = 0.045
    }
}
