package terra.render

import korlibs.image.color.Colors
import terra.hydrology.SurfaceWaterSnapshot
import kotlin.math.abs

/** Pure CPU water appearance; completed buffers are uploaded by the render thread. */
internal class SurfaceWaterRasterizer(private val width: Int, private val height: Int) {
    private lateinit var canvas: RasterCanvas
    private val riverBankColor = Colors["#143b50"].withA(238)
    private val riverColor = Colors["#3b9cbd"].withA(232)
    private val lakeBankColor = Colors["#22546a"].withA(225)
    private val lakeColor = Colors["#347f9d"].withA(190)
    fun render(hydrology: SurfaceWaterSnapshot): RasterFrame {
        canvas = RasterCanvas(width * PIXELS_PER_TILE, height * PIXELS_PER_TILE)
        drawLakeTiles(hydrology)
        drawRiverLinks(hydrology)
        return canvas.finish()
    }
    private fun drawLakeTiles(hydrology: SurfaceWaterSnapshot) {
        val scale = PIXELS_PER_TILE
        for (y in 0 until height) for (x in 0 until width) {
            if (!hydrology.isLakeTileWet(x, y)) continue
            val left = x * scale
            val top = y * scale
            for (py in 0 until scale) for (px in 0 until scale) {
                canvas[left + px, top + py] = lakeColor
            }
        }

        for (y in 0 until height) for (x in 0 until width) {
            if (!hydrology.isLakeTileWet(x, y)) continue
            val left = x * scale
            val top = y * scale
            if (y == 0 || !hydrology.isLakeTileWet(x, y - 1)) {
                for (px in 0 until scale) canvas[left + px, top] = lakeBankColor
            }
            if (!hydrology.isLakeTileWet((x + 1) % width, y)) {
                for (py in 0 until scale) canvas[left + scale - 1, top + py] = lakeBankColor
            }
            if (y == height - 1 || !hydrology.isLakeTileWet(x, y + 1)) {
                for (px in 0 until scale) canvas[left + px, top + scale - 1] = lakeBankColor
            }
            if (!hydrology.isLakeTileWet((x + width - 1) % width, y)) {
                for (py in 0 until scale) canvas[left, top + py] = lakeBankColor
            }
        }
    }

    private fun drawRiverLinks(hydrology: SurfaceWaterSnapshot) {
        val scale = PIXELS_PER_TILE
        for (y in 0 until height) for (x in 0 until width) {
            if (!hydrology.isLandTile(x, y) || !hydrology.isStreamTile(x, y)) continue
            val downstream = hydrology.downstreamTileIndex(x, y)
            val centerX = x * scale + scale / 2
            val centerY = y * scale + scale / 2
            val brushWidth = riverPixelWidth(hydrology.upstreamLandTileCount(x, y))

            if (downstream >= 0) {
                if (!hydrology.isVirtualSpillLink(x, y)) {
                    val nextX = downstream % this.width
                    val nextY = downstream / this.width
                    var deltaX = nextX - x
                    if (deltaX > width / 2) deltaX -= width
                    if (deltaX < -width / 2) deltaX += width
                    val drawnNextX = x + deltaX
                    val deltaY = nextY - y
                    if (abs(deltaX) <= 1 && abs(deltaY) <= 1) {
                        val downstreamIsLake = hydrology.isLakeCandidateTile(nextX, nextY)
                        val downstreamIsWetLake = downstreamIsLake && hydrology.isLakeTileWet(nextX, nextY)
                        val downstreamIsVisible = !hydrology.isLandTile(nextX, nextY) ||
                            hydrology.isStreamTile(nextX, nextY) || downstreamIsWetLake
                        if (downstreamIsVisible) {
                            val endX = if (downstreamIsLake && !downstreamIsWetLake) {
                                drawnNextX * scale + if (deltaX < 0) scale - 1 else if (deltaX > 0) 0 else scale / 2
                            } else {
                                drawnNextX * scale + scale / 2
                            }
                            val endY = if (downstreamIsLake && !downstreamIsWetLake) {
                                nextY * scale + if (deltaY < 0) scale - 1 else if (deltaY > 0) 0 else scale / 2
                            } else {
                                nextY * scale + scale / 2
                            }
                            drawRiverSegment(centerX, centerY, endX, endY, brushWidth)
                        } else if (downstreamIsLake) {
                            val edgeX = drawnNextX * scale + if (deltaX < 0) scale - 1 else if (deltaX > 0) 0 else scale / 2
                            val edgeY = nextY * scale + if (deltaY < 0) scale - 1 else if (deltaY > 0) 0 else scale / 2
                            drawRiverSegment(centerX, centerY, edgeX, edgeY, brushWidth)
                        }
                    }
                }
            }
            drawRiverBrush(centerX, centerY, brushWidth + RIVER_BANK_EXTRA_PIXELS, riverBankColor)
            drawRiverBrush(centerX, centerY, brushWidth, riverColor)
        }
    }

    private fun riverPixelWidth(drainageAreaTiles: Int): Int = when {
        drainageAreaTiles <= RIVER_WIDTH_1_MAX_AREA_TILES -> 1
        drainageAreaTiles <= RIVER_WIDTH_2_MAX_AREA_TILES -> 2
        else -> PIXELS_PER_TILE
    }

    private fun drawRiverSegment(startX: Int, startY: Int, endX: Int, endY: Int, width: Int) {
        drawSegmentBrush(startX, startY, endX, endY, width + RIVER_BANK_EXTRA_PIXELS, riverBankColor)
        drawSegmentBrush(startX, startY, endX, endY, width, riverColor)
    }

    private fun drawSegmentBrush(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        width: Int,
        color: korlibs.image.color.RGBA
    ) {
        var x = startX
        var y = startY
        val deltaX = abs(endX - startX)
        val stepX = if (startX < endX) 1 else -1
        val deltaY = -abs(endY - startY)
        val stepY = if (startY < endY) 1 else -1
        var error = deltaX + deltaY
        while (true) {
            drawRiverBrush(x, y, width, color)
            if (x == endX && y == endY) break
            val doubledError = 2 * error
            if (doubledError >= deltaY) {
                error += deltaY
                x += stepX
            }
            if (doubledError <= deltaX) {
                error += deltaX
                y += stepY
            }
        }
    }

    private fun drawRiverBrush(centerX: Int, centerY: Int, width: Int, color: korlibs.image.color.RGBA) {
        val offset = width / 2
        for (dy in 0 until width) for (dx in 0 until width) {
            val x = ((centerX + dx - offset) % canvas.width + canvas.width) % canvas.width
            val y = centerY + dy - offset
            if (y in 0 until canvas.height) {
                canvas[x, y] = color
            }
        }
    }

    companion object {
        const val PIXELS_PER_TILE = 3
        const val RIVER_BANK_EXTRA_PIXELS = 2
        const val RIVER_WIDTH_1_MAX_AREA_TILES = 80
        const val RIVER_WIDTH_2_MAX_AREA_TILES = 500
    }
}
