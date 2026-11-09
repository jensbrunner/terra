package terra.render

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.Colors
import korlibs.image.tiles.Tile
import korlibs.image.tiles.TileMapData
import korlibs.image.tiles.TileSet
import korlibs.korge.view.Container
import korlibs.korge.view.*
import korlibs.korge.view.container
import korlibs.korge.view.image
import korlibs.korge.view.solidRect
import korlibs.korge.view.tiles.tileMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import terra.hydrology.HydrologySimulation
import terra.weather.WeatherLayer
import terra.weather.WeatherSimulation
import terra.world.Terrain
import terra.world.World

/** Draws the terrain tile map and its independently toggled weather overlays. */
class TerrainMapView(
    parent: Container,
    initialWorld: World,
    private val terrainTextures: Map<Terrain, Bitmap32>
) {
    private var world = initialWorld
    private val terrainTileSet = createTerrainTileSet()
    private val terrainTileMapData = TileMapData(world.width, world.height, terrainTileSet)
    private val overlayBitmaps = WeatherLayer.entries.associateWith { layer ->
        Bitmap32(world.width, world.height)
    }
    private val surfaceWaterBitmap = Bitmap32(
        world.width * SURFACE_WATER_PIXELS_PER_TILE,
        world.height * SURFACE_WATER_PIXELS_PER_TILE
    )
    private val transparentPixel = Colors["#000000"].withA(0)
    private val windArrowOutline = Colors["#101820"].withA(230)
    private val windArrowStroke = Colors["#f3f1d4"].withA(245)
    private val riverBankColor = Colors["#143b50"].withA(238)
    private val riverColor = Colors["#3b9cbd"].withA(232)
    private val lakeBankColor = Colors["#22546a"].withA(225)
    private val lakeColor = Colors["#347f9d"].withA(190)
    private var overlayContainers: Map<WeatherLayer, Container>
    private val surfaceWaterContainer: Container
    private val selectionBorder: Container

    init {
        updateTerrainTileMapData()

        val terrainContainer = parent.container {
            tileMap(terrainTileMapData, smoothing = false)
        }
        terrainContainer.scaleX = 1.0 / terrainTileSet.width.toDouble()
        terrainContainer.scaleY = 1.0 / terrainTileSet.height.toDouble()

        surfaceWaterContainer = parent.container {
            image(surfaceWaterBitmap) {
                scaleX = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                scaleY = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                smoothing = false
            }
        }

        overlayContainers = WeatherLayer.entries.associateWith { layer ->
            parent.container {
                visible = false
                image(overlayBitmaps.getValue(layer)) {
                    scaleX = 1.0
                    scaleY = 1.0
                    smoothing = layer == WeatherLayer.DAY_NIGHT
                }
            }
        }

        selectionBorder = parent.container {
            visible = false
            val border = 0.08
            val color = Colors["#fff1a8"]
            solidRect(1.0, border, color)
            solidRect(1.0, border, color).y = 1.0 - border
            solidRect(border, 1.0 - 2 * border, color).xy(0.0, border)
            solidRect(border, 1.0 - 2 * border, color).xy(1.0 - border, border)
        }
    }

    fun updateWorld(newWorld: World) {
        require(newWorld.width == world.width && newWorld.height == world.height) {
            "Terrain map dimensions cannot change after the view is created"
        }
        world = newWorld
        updateTerrainTileMapData()
    }

    /** Redraw the water layer after hydrology publishes a new routed period. */
    fun updateSurfaceWater(hydrology: HydrologySimulation) {
        require(hydrology.width == world.width && hydrology.height == world.height) {
            "Surface-water dimensions must match the terrain map"
        }
        surfaceWaterBitmap.lock {
            clearSurfaceWaterPixels()
            drawLakeTiles(hydrology)
            drawRiverLinks(hydrology)
        }
    }

    private fun clearSurfaceWaterPixels() {
        surfaceWaterBitmap.fill(transparentPixel)
    }

    private fun drawLakeTiles(hydrology: HydrologySimulation) {
        val scale = SURFACE_WATER_PIXELS_PER_TILE
        for (y in 0 until world.height) for (x in 0 until world.width) {
            if (!hydrology.isLakeTileWet(x, y)) continue
            val left = x * scale
            val top = y * scale
            for (py in 0 until scale) for (px in 0 until scale) {
                surfaceWaterBitmap[left + px, top + py] = lakeColor
            }
        }

        for (y in 0 until world.height) for (x in 0 until world.width) {
            if (!hydrology.isLakeTileWet(x, y)) continue
            val left = x * scale
            val top = y * scale
            if (y == 0 || !hydrology.isLakeTileWet(x, y - 1)) {
                for (px in 0 until scale) surfaceWaterBitmap[left + px, top] = lakeBankColor
            }
            if (x == world.width - 1 || !hydrology.isLakeTileWet(x + 1, y)) {
                for (py in 0 until scale) surfaceWaterBitmap[left + scale - 1, top + py] = lakeBankColor
            }
            if (y == world.height - 1 || !hydrology.isLakeTileWet(x, y + 1)) {
                for (px in 0 until scale) surfaceWaterBitmap[left + px, top + scale - 1] = lakeBankColor
            }
            if (x == 0 || !hydrology.isLakeTileWet(x - 1, y)) {
                for (py in 0 until scale) surfaceWaterBitmap[left, top + py] = lakeBankColor
            }
        }
    }

    private fun drawRiverLinks(hydrology: HydrologySimulation) {
        val scale = SURFACE_WATER_PIXELS_PER_TILE
        for (y in 0 until world.height) for (x in 0 until world.width) {
            if (!hydrology.isLandTile(x, y) || !hydrology.isStreamTile(x, y)) continue
            val downstream = hydrology.downstreamTileIndex(x, y)
            val centerX = x * scale + scale / 2
            val centerY = y * scale + scale / 2
            val width = riverPixelWidth(hydrology.dischargeMmTilesPerDay(x, y))

            if (downstream >= 0) {
                val nextX = downstream % world.width
                val nextY = downstream / world.width
                val deltaX = nextX - x
                val deltaY = nextY - y
                if (abs(deltaX) <= 1 && abs(deltaY) <= 1) {
                    val downstreamIsLake = hydrology.isLakeCandidateTile(nextX, nextY)
                    val downstreamIsWetLake = downstreamIsLake && hydrology.isLakeTileWet(nextX, nextY)
                    val downstreamIsVisible = !hydrology.isLandTile(nextX, nextY) ||
                        hydrology.isStreamTile(nextX, nextY) || downstreamIsWetLake
                    if (downstreamIsVisible) {
                        val endX = if (downstreamIsLake && !downstreamIsWetLake) {
                            nextX * scale + if (deltaX < 0) scale - 1 else if (deltaX > 0) 0 else scale / 2
                        } else {
                            nextX * scale + scale / 2
                        }
                        val endY = if (downstreamIsLake && !downstreamIsWetLake) {
                            nextY * scale + if (deltaY < 0) scale - 1 else if (deltaY > 0) 0 else scale / 2
                        } else {
                            nextY * scale + scale / 2
                        }
                        drawRiverSegment(centerX, centerY, endX, endY, width)
                    } else if (downstreamIsLake) {
                        val edgeX = nextX * scale + if (deltaX < 0) scale - 1 else if (deltaX > 0) 0 else scale / 2
                        val edgeY = nextY * scale + if (deltaY < 0) scale - 1 else if (deltaY > 0) 0 else scale / 2
                        drawRiverSegment(centerX, centerY, edgeX, edgeY, width)
                    }
                }
            } else {
                val edgeX = when (x) {
                    0 -> 0
                    world.width - 1 -> x * scale + scale - 1
                    else -> centerX
                }
                val edgeY = when (y) {
                    0 -> 0
                    world.height - 1 -> y * scale + scale - 1
                    else -> centerY
                }
                if (edgeX != centerX || edgeY != centerY) {
                    drawRiverSegment(centerX, centerY, edgeX, edgeY, width)
                }
            }
            drawRiverBrush(centerX, centerY, width + RIVER_BANK_EXTRA_PIXELS, riverBankColor)
            drawRiverBrush(centerX, centerY, width, riverColor)
        }
    }

    private fun riverPixelWidth(dischargeMmTilesPerDay: Double): Int = when {
        dischargeMmTilesPerDay <= RIVER_WIDTH_1_MAX_DISCHARGE_MM_TILES_PER_DAY -> 1
        dischargeMmTilesPerDay <= RIVER_WIDTH_2_MAX_DISCHARGE_MM_TILES_PER_DAY -> 2
        else -> SURFACE_WATER_PIXELS_PER_TILE
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
            val x = centerX + dx - offset
            val y = centerY + dy - offset
            if (x in 0 until surfaceWaterBitmap.width && y in 0 until surfaceWaterBitmap.height) {
                surfaceWaterBitmap[x, y] = color
            }
        }
    }

    fun updateOverlays(weather: WeatherSimulation, activeLayers: Set<WeatherLayer>) {
        for (layer in WeatherLayer.entries) {
            val container = overlayContainers.getValue(layer)
            container.visible = layer in activeLayers
            if (!container.visible) continue

            val bitmap = overlayBitmaps.getValue(layer)
            if (layer == WeatherLayer.WIND) {
                updateWindOverlay(bitmap, weather)
                continue
            }
            val nightOpacityByColumn = if (layer == WeatherLayer.DAY_NIGHT) {
                DoubleArray(world.width) { x -> weather.nightOpacity(x) }
            } else {
                null
            }
            bitmap.lock {
                for (y in 0 until world.height) {
                    for (x in 0 until world.width) {
                        val color = when (layer) {
                            WeatherLayer.CLOUDS -> Colors["#f1f4f2"]
                            WeatherLayer.DAY_NIGHT -> Colors["#071321"]
                            WeatherLayer.WIND -> windArrowStroke
                            else -> tileColor(layer, weather, x, y)
                        }
                        val opacity = when (layer) {
                            WeatherLayer.CLOUDS -> weather.cloudCover(x, y)
                            WeatherLayer.DAY_NIGHT -> nightOpacityByColumn!![x]
                            WeatherLayer.WIND -> 0.0
                            else -> 0.42
                        }

                        bitmap[x, y] = color.withA((opacity * 255).toInt())
                    }
                }
            }
        }
    }

    private fun updateWindOverlay(bitmap: Bitmap32, weather: WeatherSimulation) {
        bitmap.lock {
            for (y in 0 until world.height) {
                for (x in 0 until world.width) bitmap[x, y] = transparentPixel
            }

            for (y in WIND_ARROW_SPACING_Y / 2 until world.height step WIND_ARROW_SPACING_Y) {
                for (x in WIND_ARROW_SPACING_X / 2 until world.width step WIND_ARROW_SPACING_X) {
                    drawWindArrow(
                        bitmap,
                        x + 0.5,
                        y + 0.5,
                        weather.windUTilesPerDay(x, y),
                        weather.windVTilesPerDay(x, y)
                    )
                }
            }
        }
    }

    private fun drawWindArrow(bitmap: Bitmap32, x: Double, y: Double, u: Double, v: Double) {
        val speed = sqrt(u * u + v * v)
        if (speed < MIN_VISIBLE_WIND_SPEED) return

        val directionX = u / speed
        val directionY = v / speed
        val length = MIN_WIND_ARROW_LENGTH +
            (MAX_WIND_ARROW_LENGTH - MIN_WIND_ARROW_LENGTH) *
            (speed / WIND_SPEED_AT_MAX_ARROW_LENGTH).coerceIn(0.0, 1.0)
        val tipX = x + directionX * length
        val tipY = y + directionY * length
        val headLength = WIND_ARROW_HEAD_LENGTH
        val headCos = kotlin.math.cos(WIND_ARROW_HEAD_ANGLE)
        val headSin = kotlin.math.sin(WIND_ARROW_HEAD_ANGLE)
        val head1X = tipX - headLength * (directionX * headCos - directionY * headSin)
        val head1Y = tipY - headLength * (directionY * headCos + directionX * headSin)
        val head2X = tipX - headLength * (directionX * headCos + directionY * headSin)
        val head2Y = tipY - headLength * (directionY * headCos - directionX * headSin)

        drawWindLine(bitmap, x, y, tipX, tipY, outline = true)
        drawWindLine(bitmap, tipX, tipY, head1X, head1Y, outline = true)
        drawWindLine(bitmap, tipX, tipY, head2X, head2Y, outline = true)
        drawWindLine(bitmap, x, y, tipX, tipY, outline = false)
        drawWindLine(bitmap, tipX, tipY, head1X, head1Y, outline = false)
        drawWindLine(bitmap, tipX, tipY, head2X, head2Y, outline = false)
    }

    private fun drawWindLine(
        bitmap: Bitmap32,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        outline: Boolean
    ) {
        var x = startX.roundToInt()
        var y = startY.roundToInt()
        val targetX = endX.roundToInt()
        val targetY = endY.roundToInt()
        val deltaX = abs(targetX - x)
        val stepX = if (x < targetX) 1 else -1
        val deltaY = -abs(targetY - y)
        val stepY = if (y < targetY) 1 else -1
        var error = deltaX + deltaY

        while (true) {
            if (outline) {
                for (offsetY in -1..1) for (offsetX in -1..1) {
                    setWindPixel(bitmap, x + offsetX, y + offsetY, windArrowOutline)
                }
            } else {
                setWindPixel(bitmap, x, y, windArrowStroke)
            }
            if (x == targetX && y == targetY) break

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

    private fun setWindPixel(bitmap: Bitmap32, x: Int, y: Int, color: korlibs.image.color.RGBA) {
        if (x in 0 until world.width && y in 0 until world.height) bitmap[x, y] = color
    }

    fun selectTile(x: Int, y: Int) {
        selectionBorder.xy(x, y)
        selectionBorder.visible = true
    }

    private fun createTerrainTileSet(): TileSet {
        val terrainTypes = Terrain.entries
        val tileSize = terrainTextures.getValue(terrainTypes.first()).width
        return TileSet.fromBitmaps(
            tileSize,
            tileSize,
            terrainTypes.map { terrainTextures.getValue(it) },
            border = 16,
            mipmaps = true
        )
    }

    private fun updateTerrainTileMapData() {
        for (y in 0 until world.height) {
            for (x in 0 until world.width) {
                terrainTileMapData[x, y] = Tile(world[x, y].terrain.ordinal)
            }
        }
    }

    private companion object {
        const val SURFACE_WATER_PIXELS_PER_TILE = 3
        const val RIVER_BANK_EXTRA_PIXELS = 2
        const val RIVER_WIDTH_1_MAX_DISCHARGE_MM_TILES_PER_DAY = 10.0
        const val RIVER_WIDTH_2_MAX_DISCHARGE_MM_TILES_PER_DAY = 200.0
        const val WIND_ARROW_SPACING_X = 16
        const val WIND_ARROW_SPACING_Y = 12
        const val MIN_VISIBLE_WIND_SPEED = 0.05
        const val MIN_WIND_ARROW_LENGTH = 2.5
        const val MAX_WIND_ARROW_LENGTH = 5.5
        const val WIND_SPEED_AT_MAX_ARROW_LENGTH = 45.0
        const val WIND_ARROW_HEAD_LENGTH = 1.8
        const val WIND_ARROW_HEAD_ANGLE = 0.65
    }
}
