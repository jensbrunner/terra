package terra.render

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
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
import terra.climate.EnvironmentSnapshot
import terra.world.PlateBoundary
import terra.world.Terrain
import terra.world.WorldSnapshot

/** Draws the terrain tile map and its independently toggled weather overlays. */
class TerrainMapView(
    parent: Container,
    initialWorld: WorldSnapshot,
    private val terrainTextures: Map<Terrain, Bitmap32>
) {
    private var world = initialWorld
    private val terrainTileSet = createTerrainTileSet()
    private val terrainTileMapData = TileMapData(world.width, world.height, terrainTileSet)
    private val queuedTerrain = BooleanArray(world.width * world.height)
    private val terrainQueue = ArrayDeque<Int>()
    private val reliefBitmap = Bitmap32(world.width, world.height)
    private val canopyBitmap = Bitmap32(world.width, world.height)
    private var canopyRevision = -1L
    private var nextOverlay = 0
    private val layerWeather = mutableMapOf<ClimateLayer, EnvironmentSnapshot>()
    private val overlayBitmaps = ClimateLayer.entries.filter { it != ClimateLayer.WIND }
        .associateWith { Bitmap32(world.width, world.height) }
    private val overlayFades = overlayBitmaps.mapValues { (layer, bitmap) -> LayerFade(Bitmap32(bitmap.width, bitmap.height),
        if (layer == ClimateLayer.CLOUDS || layer == ClimateLayer.PRECIPITATION) 0.3 else 0.18) }
    private val surfaceWaterBitmap = Bitmap32(
        world.width * SURFACE_WATER_PIXELS_PER_TILE,
        world.height * SURFACE_WATER_PIXELS_PER_TILE
    )
    private val waterFade = LayerFade(Bitmap32(surfaceWaterBitmap.width, surfaceWaterBitmap.height))
    private val plateColors = listOf("#67b7c7", "#e5b66a", "#9ac77f", "#bd8acb", "#e18b87", "#81a2e2", "#b8c868")
        .map { Colors[it] }
    private val boundaryColors = listOf("#fff1d0", "#f39d76", "#cf8ad8", "#5ed4d0", "#e5ca6e", "#8eafe8").map { Colors[it] }
    private val nightTint = Colors["#071321"]
    private val transparentPixel = Colors["#000000"].withA(0)
    private val windOverlay = WindOverlay()
    private val overlayContainers: Map<ClimateLayer, Container>
    private val selectionBorder: Container

    init {
        updateTerrainTileMapData()

        val terrainContainer = parent.container {
            tileMap(terrainTileMapData, smoothing = false)
        }
        terrainContainer.scaleX = 1.0 / terrainTileSet.width.toDouble()
        terrainContainer.scaleY = 1.0 / terrainTileSet.height.toDouble()

        updateRelief()
        updateCanopy()
        parent.image(canopyBitmap) { smoothing = false }
        parent.image(reliefBitmap) { smoothing = true }

        parent.container {
            waterFade.image = RasterBlendImage(waterFade.previous, surfaceWaterBitmap).addTo(this) {
                scaleX = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                scaleY = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                smoothing = true; alpha = 0.0
            }
        }

        overlayContainers = ClimateLayer.entries.associateWith { layer ->
            parent.container {
                visible = false
                if (layer == ClimateLayer.WIND) {
                    windOverlay.view.addTo(this)
                    return@container
                }
                val fade = overlayFades.getValue(layer)
                fade.image = RasterBlendImage(fade.previous, overlayBitmaps.getValue(layer)).addTo(this) {
                    smoothing = layer == ClimateLayer.PRECIPITATION || layer == ClimateLayer.DAY_NIGHT
                    alpha = 0.0
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

    fun updateWorld(newWorld: WorldSnapshot) {
        require(newWorld.width == world.width && newWorld.height == world.height) {
            "Terrain map dimensions cannot change after the view is created"
        }
        val generationChanged = world.generation != newWorld.generation
        val geographyChanged = world.geographyRevision != newWorld.geographyRevision
        world = newWorld
        if (generationChanged) {
            updateRelief()
            updateCanopy()
            layerWeather.clear()
            for ((layer, bitmap) in overlayBitmaps) { bitmap.fill(transparentPixel); overlayFades.getValue(layer).reset() }
            surfaceWaterBitmap.fill(transparentPixel); waterFade.reset()
            for (i in queuedTerrain.indices) enqueueTerrain(i)
        } else {
            if (geographyChanged) updateRelief()
            for (i in 0 until world.changedTerrainCount) enqueueTerrain(world.changedTerrainIndex(i))
            if (canopyRevision != world.biomeRevision) updateCanopy()
        }
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

    fun updateOverlays(weather: EnvironmentSnapshot, activeLayers: Set<ClimateLayer>) {
        for (layer in ClimateLayer.entries) overlayContainers.getValue(layer).visible = layer in activeLayers

        // Round robin prevents later layers starving when publications arrive during a refresh.
        var renderedOverlay = false
        val firstOverlay = nextOverlay
        repeat(ClimateLayer.entries.size) { offset ->
            val index = (firstOverlay + offset) % ClimateLayer.entries.size
            val layer = ClimateLayer.entries[index]
            if (layer !in activeLayers) return@repeat
            if (layer == ClimateLayer.WIND) {
                if (layerWeather[layer] !== weather) {
                    windOverlay.update(weather)
                    layerWeather[layer] = weather
                }
                return@repeat
            }
            val bitmap = overlayBitmaps.getValue(layer)
            if (renderedOverlay || layerWeather[layer] === weather) return@repeat
            renderedOverlay = true
            nextOverlay = (index + 1) % ClimateLayer.entries.size
            renderOverlay(layer, weather, bitmap)
        }
    }

    private fun renderOverlay(layer: ClimateLayer, weather: EnvironmentSnapshot, bitmap: Bitmap32) {
        val fade = overlayFades.getValue(layer)
        layerWeather[layer] = weather
        fade.begin(bitmap)
        bitmap.lock {
            for (y in 0 until world.height) for (x in 0 until world.width) {
                bitmap[x, y] = overlayColor(layer, weather, x, y)
            }
        }
        fade.complete(bitmap)
    }

    private fun overlayColor(layer: ClimateLayer, weather: EnvironmentSnapshot, x: Int, y: Int): RGBA = when (layer) {
        ClimateLayer.DAY_NIGHT -> nightTint.withA((weather.nightOpacity(x, y) * 255).toInt())
        ClimateLayer.PLATES -> {
            val id = world.plateId(x, y)
            val edge = id != world.plateId((x + world.width - 1) % world.width, y) ||
                id != world.plateId(x, y - 1)
            val kind = world.plateBoundary(x, y)
            if (edge) boundaryColors[kind.ordinal].withA(235)
            else plateColors[id % plateColors.size].withA(115)
        }
        ClimateLayer.CLOUDS -> {
            val cloud = weather.cloudCover(x, y)
            val west = (x + world.width - 1) % world.width
            val upwind = weather.cloudCover(west, (y + world.height - 1) % world.height)
            val light = (0.88 + (cloud - upwind) * 1.8).coerceIn(0.55, 1.0)
            RGBA((231 * light).toInt(), (240 * light).toInt(), (252 * light).toInt(), (cloud * 232).toInt())
        }
        ClimateLayer.PRECIPITATION -> {
            val rain = weather.precipitationMmPerHour(x, y)
            precipitationColor(rain, weather.snowFraction(x, y))
                .withA((precipitationOpacity(rain) * 255).toInt())
        }
        else -> tileColor(layer, weather, x, y).withA(173)
    }

    fun selectTile(x: Int, y: Int) {
        selectionBorder.xy(x, y)
        selectionBorder.visible = true
    }

    fun clearSelection() { selectionBorder.visible = false }

    fun viewportChanged() { windOverlay.viewportChanged() }

    /** Continuous canopy, grass and bare-ground fractions instead of a categorical switch. */
    private fun updateCanopy() {
        canopyRevision = world.biomeRevision
        canopyBitmap.lock {
            for (y in 0 until world.height) for (x in 0 until world.width) {
                val cell = world[x, y]
                if (cell.terrain == Terrain.OCEAN) { canopyBitmap[x, y] = Colors["#000000"].withA(0); continue }
                val tree = world.treeCover(x, y).coerceIn(0.0, 1.0)
                val grass = world.grassCover(x, y).coerceIn(0.0, 1.0)
                val bare = world.bareCover(x, y).coerceIn(0.0, 1.0)
                val red = (28 * tree + 122 * grass + 188 * bare).coerceIn(0.0, 255.0).toInt()
                val green = (108 * tree + 168 * grass + 150 * bare).coerceIn(0.0, 255.0).toInt()
                val blue = (42 * tree + 74 * grass + 108 * bare).coerceIn(0.0, 255.0).toInt()
                val alpha = ((0.70 * tree + 0.38 * grass) * 255).toInt().coerceIn(0, 255)
                canopyBitmap[x, y] = RGBA(red, green, blue, alpha)
            }
        }
    }

    private fun updateRelief() {
        reliefBitmap.lock {
            for (y in 0 until world.height) for (x in 0 until world.width) {
                val west = world[x - 1, y].elevation
                val east = world[x + 1, y].elevation
                val north = world[x, y - 1].elevation
                val south = world[x, y + 1].elevation
                val shade = (0.88 + 6 * (west - east) + 4 * (south - north)).coerceIn(0.55, 1.12)
                val oceanFactor = if (world[x, y].terrain == Terrain.OCEAN) 0.35 else 1.0
                val alpha = (abs(1 - shade) * oceanFactor * 255).toInt()
                reliefBitmap[x, y] = Colors[if (shade < 1) "#10151b" else "#fff1d0"].withA(alpha)
            }
        }
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
        repeat(minOf(1024, terrainQueue.size)) {
            val i = terrainQueue.removeFirst()
            queuedTerrain[i] = false
            val x = i % world.width; val y = i / world.width
            terrainTileMapData[x, y] = Tile(world[x, y].terrain.ordinal)
        }
    }

    private fun updateTerrainTileMapData() {
        for (y in 0 until world.height) {
            for (x in 0 until world.width) {
                terrainTileMapData[x, y] = Tile(world[x, y].terrain.ordinal)
            }
        }
    }

    private companion object { const val SURFACE_WATER_PIXELS_PER_TILE = SurfaceWaterRasterizer.PIXELS_PER_TILE }
}

/** Render-thread blend buffers. Hidden layers retain data, and generation reset hides obsolete rasters. */
internal class LayerFade(val previous: Bitmap32, private val duration: Double = 0.18) {
    init { require(duration.isFinite() && duration > 0) }
    var image: RasterBlendImage? = null
    private var age = 0.0
    private var hasData = false
    fun begin(current: Bitmap32) {
        previous.lock {
            when {
                !hasData -> previous.ints.fill(0)
                age >= duration -> current.ints.copyInto(previous.ints)
                else -> {
                    // A command/early publication can interrupt the fade. Start
                    // from the image on screen, not the old unfinished target.
                    val fraction = age / duration
                    for (i in previous.ints.indices) {
                        val a = previous.ints[i]
                        val b = current.ints[i]
                        var result = 0
                        for (shift in 0..24 step 8) {
                            val first = (a ushr shift) and 255
                            val second = (b ushr shift) and 255
                            val channel = (first + (second - first) * fraction).roundToInt()
                            result = result or (channel shl shift)
                        }
                        previous.ints[i] = result
                    }
                }
            }
        }
        hasData = true
        age = 0.0
        advance(0.0)
    }
    fun complete(current: Bitmap32) {
        // Producers write straight RGBA. Convert once per publication, before GPU
        // filtering/blending; opacity interpolation must never see straight RGB.
        current.lock {
            current.premultiplied = false
            current.premultiplyInplaceIfRequired()
        }
        if (previous.ints.contentEquals(current.ints)) { age = duration; advance(0.0) }
    }
    fun advance(seconds: Double) {
        age = minOf(duration, age + seconds)
        val fraction = if (hasData) age / duration else 0.0
        image?.fraction = fraction
        image?.alpha = if (hasData) 1.0 else 0.0
    }
    fun reset() { hasData = false; advance(0.0) }
}
