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
import terra.weather.WeatherLayer
import terra.weather.WeatherModel
import terra.weather.WeatherFieldQuality
import terra.atmosphere.SolarConfig
import terra.world.Terrain
import terra.world.WorldSnapshot
import terra.simulation.SimulationTimings
import terra.simulation.SimulationWork

/** Draws the terrain tile map and its independently toggled weather overlays. */
class TerrainMapView(
    parent: Container,
    initialWorld: WorldSnapshot,
    private val terrainTextures: Map<Terrain, Bitmap32>,
    private val timings: SimulationTimings? = null
) {
    private var world = initialWorld
    private val terrainTileSet = createTerrainTileSet()
    private val terrainTileMapData = TileMapData(world.width, world.height, terrainTileSet)
    private val queuedTerrain = BooleanArray(world.width * world.height)
    private val terrainQueue = ArrayDeque<Int>()
    private var nextOverlay = 0
    private var cloudFrame: RasterFrame? = null
    private val layerWeather = mutableMapOf<WeatherLayer, WeatherModel>()
    private val overlayBitmaps = WeatherLayer.entries.associateWith { layer ->
        if (layer == WeatherLayer.CLOUDS) Bitmap32(world.width * CloudRenderer.PIXELS_PER_TILE, world.height * CloudRenderer.PIXELS_PER_TILE) else Bitmap32(world.width, world.height)
    }
    private val overlayFades = overlayBitmaps.mapValues { (_, bitmap) -> LayerFade(Bitmap32(bitmap.width, bitmap.height)) }
    private var lightingWeather: WeatherModel? = null
    private var seasonalLighting: SeasonalLighting? = null
    private val surfaceWaterBitmap = Bitmap32(
        world.width * SURFACE_WATER_PIXELS_PER_TILE,
        world.height * SURFACE_WATER_PIXELS_PER_TILE
    )
    private val waterFade = LayerFade(Bitmap32(surfaceWaterBitmap.width, surfaceWaterBitmap.height))
    private val nightTint = Colors["#071321"]
    private val transparentPixel = Colors["#000000"].withA(0)
    private val windArrowOutline = Colors["#101820"].withA(230)
    private val windArrowStroke = Colors["#f3f1d4"].withA(245)
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
            waterFade.previousImage = image(waterFade.previous) {
                scaleX = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                scaleY = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                smoothing = false; alpha = 0.0
            }
            waterFade.currentImage = image(surfaceWaterBitmap) {
                scaleX = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                scaleY = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                smoothing = false; alpha = 0.0
            }
        }

        overlayContainers = WeatherLayer.entries.associateWith { layer ->
            parent.container {
                visible = false
                val fade = overlayFades.getValue(layer)
                fade.previousImage = image(fade.previous) {
                    val pixelsPerTile = if (layer == WeatherLayer.CLOUDS) CloudRenderer.PIXELS_PER_TILE else 1
                    scaleX = 1.0 / pixelsPerTile
                    scaleY = 1.0 / pixelsPerTile
                    smoothing = layer == WeatherLayer.DAY_NIGHT || layer == WeatherLayer.CLOUDS
                    alpha = 0.0
                }
                val overlayImage = image(overlayBitmaps.getValue(layer)) {
                    val pixelsPerTile = if (layer == WeatherLayer.CLOUDS) CloudRenderer.PIXELS_PER_TILE else 1
                    scaleX = 1.0 / pixelsPerTile
                    scaleY = 1.0 / pixelsPerTile
                    smoothing = layer == WeatherLayer.DAY_NIGHT || layer == WeatherLayer.CLOUDS
                }
                fade.currentImage = overlayImage
                overlayImage.alpha = 0.0
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

    fun updateWorld(newWorld: WorldSnapshot) {
        require(newWorld.width == world.width && newWorld.height == world.height) {
            "Terrain map dimensions cannot change after the view is created"
        }
        val generationChanged = world.generation != newWorld.generation
        world = newWorld
        if (generationChanged) {
            cloudFrame = null
            layerWeather.clear()
            lightingWeather = null
            for ((layer, bitmap) in overlayBitmaps) { bitmap.fill(transparentPixel); overlayFades.getValue(layer).reset() }
            surfaceWaterBitmap.fill(transparentPixel); waterFade.reset()
            for (i in queuedTerrain.indices) enqueueTerrain(i)
        } else for (i in 0 until world.changedTerrainCount) enqueueTerrain(world.changedTerrainIndex(i))
    }

    /** Only the completed pixel copy/texture invalidation runs on the renderer thread. */
    fun updateSurfaceWater(frame: RasterFrame) {
        waterFade.begin(surfaceWaterBitmap)
        upload(surfaceWaterBitmap, frame)
        waterFade.complete(surfaceWaterBitmap)
    }

    private fun upload(bitmap: Bitmap32, frame: RasterFrame) {
        require(bitmap.width == frame.width && bitmap.height == frame.height)
        bitmap.lock { frame.copyInto(bitmap.ints) }
    }

    fun updateOverlays(weather: WeatherModel, activeLayers: Set<WeatherLayer>, clouds: RasterFrame?, solar: SolarConfig) {
        for (layer in WeatherLayer.entries) overlayContainers.getValue(layer).visible = layer in activeLayers
        // Round robin prevents later layers starving when publications arrive during a refresh.
        var renderedOverlay = false
        val firstOverlay = nextOverlay
        repeat(WeatherLayer.entries.size) { offset ->
            val index = (firstOverlay + offset) % WeatherLayer.entries.size
            val layer = WeatherLayer.entries[index]
            if (layer !in activeLayers) return@repeat
            val bitmap = overlayBitmaps.getValue(layer)
            if (layer == WeatherLayer.CLOUDS) {
                if (clouds != null && clouds !== cloudFrame) {
                    timings?.begin(SimulationWork.CLOUD_BITMAP)
                    overlayFades.getValue(layer).begin(bitmap)
                    upload(bitmap, clouds)
                    overlayFades.getValue(layer).complete(bitmap)
                    cloudFrame = clouds
                    timings?.end(SimulationWork.CLOUD_BITMAP)
                }
                return@repeat
            }
            if (renderedOverlay || layerWeather[layer] === weather) return@repeat
            renderedOverlay = true
            nextOverlay = (index + 1) % WeatherLayer.entries.size
            layerWeather[layer] = weather
            overlayFades.getValue(layer).begin(bitmap)
            val representative = weather.fieldSemantics.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL
            if (layer == WeatherLayer.DAY_NIGHT && representative && lightingWeather !== weather) {
                seasonalLighting = SeasonalLighting.capture(world.height, weather.fieldSemantics, solar)
                lightingWeather = weather
            }
            if (layer == WeatherLayer.DAY_NIGHT) {
                timings?.begin(SimulationWork.OTHER_OVERLAYS)
                bitmap.lock {
                    for (y in 0 until world.height) {
                        if (representative) {
                            val color = nightTint.withA((seasonalLighting!!.opacityAtRow(y) * 255).toInt()).value
                            bitmap.ints.fill(color, y * world.width, (y + 1) * world.width)
                        } else for (x in 0 until world.width) bitmap[x, y] = nightTint.withA((weather.nightOpacity(x, y) * 255).toInt())
                    }
                }
                overlayFades.getValue(layer).complete(bitmap)
                timings?.end(SimulationWork.OTHER_OVERLAYS)
                return@repeat
            }
            if (layer == WeatherLayer.WIND) {
                timings?.begin(SimulationWork.OTHER_OVERLAYS)
                updateWindOverlay(bitmap, weather)
                overlayFades.getValue(layer).complete(bitmap)
                timings?.end(SimulationWork.OTHER_OVERLAYS)
                return@repeat
            }
            timings?.begin(SimulationWork.OTHER_OVERLAYS)
            bitmap.lock {
                for (y in 0 until world.height) {
                    for (x in 0 until world.width) {
                        bitmap[x, y] = tileColor(layer, weather, x, y).withA((0.42 * 255).toInt())
                    }
                }
            }
            overlayFades.getValue(layer).complete(bitmap)
            timings?.end(SimulationWork.OTHER_OVERLAYS)
        }
    }

    private fun updateWindOverlay(bitmap: Bitmap32, weather: WeatherModel) {
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

    /** Crossfade completed rasters; no visual fraction is ever written back to physical state. */
    fun advanceVisuals(realSeconds: Double) {
        val seconds = realSeconds.coerceIn(0.0, 0.25)
        for (fade in overlayFades.values) fade.advance(seconds)
        waterFade.advance(seconds)
        advanceTerrainUpdates()
    }

    private fun enqueueTerrain(index: Int) {
        if (!queuedTerrain[index]) { queuedTerrain[index] = true; terrainQueue.addLast(index) }
    }

    /** Amortize abrupt biome/world changes; selection/physics always use the latest complete view. */
    private fun advanceTerrainUpdates() {
        if (terrainQueue.isEmpty()) return
        timings?.begin(SimulationWork.TERRAIN_TILES)
        repeat(minOf(1024, terrainQueue.size)) {
            val i = terrainQueue.removeFirst()
            queuedTerrain[i] = false
            val x = i % world.width; val y = i / world.width
            terrainTileMapData[x, y] = Tile(world[x, y].terrain.ordinal)
        }
        timings?.end(SimulationWork.TERRAIN_TILES)
    }

    private fun updateTerrainTileMapData() {
        timings?.begin(SimulationWork.TERRAIN_TILES)
        for (y in 0 until world.height) {
            for (x in 0 until world.width) {
                terrainTileMapData[x, y] = Tile(world[x, y].terrain.ordinal)
            }
        }
        timings?.end(SimulationWork.TERRAIN_TILES)
    }

    private companion object {
        const val SURFACE_WATER_PIXELS_PER_TILE = SurfaceWaterRasterizer.PIXELS_PER_TILE
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

/** Render-thread blend buffers. Hidden layers retain data, and generation reset hides obsolete rasters. */
private class LayerFade(val previous: Bitmap32) {
    var previousImage: Image? = null
    var currentImage: Image? = null
    private var age = 0.0
    private var hasData = false
    fun begin(current: Bitmap32) {
        previous.lock { if (hasData) current.ints.copyInto(previous.ints) else previous.ints.fill(0) }
        hasData = true
        age = 0.0
        advance(0.0)
    }
    fun complete(current: Bitmap32) {
        if (previous.ints.contentEquals(current.ints)) { age = 0.18; advance(0.0) }
    }
    fun advance(seconds: Double) {
        age = minOf(0.18, age + seconds)
        val fraction = if (hasData) age / 0.18 else 0.0
        previousImage?.alpha = if (hasData) 1.0 - fraction else 0.0
        currentImage?.alpha = fraction
    }
    fun reset() { hasData = false; advance(0.0) }
}
