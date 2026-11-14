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
import kotlinx.coroutines.*
import terra.climate.ProceduralWeather
import terra.climate.SeasonalClimateAtlas
import kotlin.math.abs
import kotlin.math.roundToInt
import terra.climate.ClimateLayer
import terra.climate.EnvironmentSnapshot
import terra.world.Terrain
import terra.world.WorldSnapshot
import terra.simulation.SimulationTimings
import terra.simulation.SimulationWork

/** Draws the terrain tile map and its independently toggled weather overlays. */
class TerrainMapView(
    parent: Container,
    initialWorld: WorldSnapshot,
    private val terrainTextures: Map<Terrain, Bitmap32>,
    private val timings: SimulationTimings?,
    private val renderScope: CoroutineScope
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
    private val weatherWidth = minOf(world.width * 2, 512)
    private val weatherHeight = minOf(world.height * 2, 384)
    private var weatherJob: Deferred<WeatherRaster>? = null
    private var weatherAtlas: SeasonalClimateAtlas? = null
    private var weatherTime = Double.NaN
    private var weatherAge = 1.0
    private val overlayBitmaps = ClimateLayer.entries.filter { it != ClimateLayer.WIND }
        .associateWith { if (it == ClimateLayer.CLOUDS || it == ClimateLayer.PRECIPITATION)
            Bitmap32(weatherWidth, weatherHeight) else Bitmap32(world.width, world.height) }
    private val overlayFades = overlayBitmaps.mapValues { (layer, bitmap) -> LayerFade(Bitmap32(bitmap.width, bitmap.height),
        if (layer == ClimateLayer.CLOUDS || layer == ClimateLayer.PRECIPITATION) 0.3 else 0.18) }
    private val surfaceWaterBitmap = Bitmap32(
        world.width * SURFACE_WATER_PIXELS_PER_TILE,
        world.height * SURFACE_WATER_PIXELS_PER_TILE
    )
    private val waterFade = LayerFade(Bitmap32(surfaceWaterBitmap.width, surfaceWaterBitmap.height))
    private val nightTint = Colors["#071321"]
    private val transparentPixel = Colors["#000000"].withA(0)
    private val windOverlay = WindOverlay()
    private var overlayContainers: Map<ClimateLayer, Container>
    private val surfaceWaterContainer: Container
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

        surfaceWaterContainer = parent.container {
            waterFade.image = RasterBlendImage(waterFade.previous, surfaceWaterBitmap).addTo(this) {
                scaleX = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                scaleY = 1.0 / SURFACE_WATER_PIXELS_PER_TILE
                smoothing = false; alpha = 0.0
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
                    scaleX = world.width.toDouble() / overlayBitmaps.getValue(layer).width
                    scaleY = world.height.toDouble() / overlayBitmaps.getValue(layer).height
                    smoothing = layer == ClimateLayer.CLOUDS || layer == ClimateLayer.PRECIPITATION || layer == ClimateLayer.DAY_NIGHT || layer == ClimateLayer.CLIMATE_RAIN || layer == ClimateLayer.ANNUAL_PRECIPITATION || layer == ClimateLayer.ARIDITY || layer == ClimateLayer.MEAN_TEMPERATURE
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
        val geometryChanged = world.geometryRevision != newWorld.geometryRevision
        world = newWorld
        if (generationChanged) {
            updateRelief()
            updateCanopy()
            layerWeather.clear()
            weatherJob?.cancel(); weatherJob = null
            weatherAtlas = null; weatherTime = Double.NaN
            for ((layer, bitmap) in overlayBitmaps) { bitmap.fill(transparentPixel); overlayFades.getValue(layer).reset() }
            surfaceWaterBitmap.fill(transparentPixel); waterFade.reset()
            for (i in queuedTerrain.indices) enqueueTerrain(i)
        } else {
            if (geometryChanged) updateRelief()
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

    fun updateOverlays(weather: EnvironmentSnapshot, activeLayers: Set<ClimateLayer>, displaySeconds: Double = weather.elapsedSeconds) {
        for (layer in ClimateLayer.entries) {
            overlayContainers.getValue(layer).visible = layer in activeLayers
        }
        updateWeatherRaster(weather.atlas, displaySeconds, activeLayers)
        // Round robin prevents later layers starving when publications arrive during a refresh.
        var renderedOverlay = false
        val firstOverlay = nextOverlay
        repeat(ClimateLayer.entries.size) { offset ->
            val index = (firstOverlay + offset) % ClimateLayer.entries.size
            val layer = ClimateLayer.entries[index]
            if (layer !in activeLayers || layer == ClimateLayer.CLOUDS || layer == ClimateLayer.PRECIPITATION) return@repeat
            if (layer == ClimateLayer.WIND) {
                if (layerWeather[layer] !== weather) {
                    timings?.begin(SimulationWork.OTHER_OVERLAYS)
                    windOverlay.update(weather)
                    layerWeather[layer] = weather
                    timings?.end(SimulationWork.OTHER_OVERLAYS)
                }
                return@repeat
            }
            val bitmap = overlayBitmaps.getValue(layer)
            if (renderedOverlay || layerWeather[layer] === weather) return@repeat
            renderedOverlay = true
            nextOverlay = (index + 1) % ClimateLayer.entries.size
            layerWeather[layer] = weather
            overlayFades.getValue(layer).begin(bitmap)
            if (layer == ClimateLayer.DAY_NIGHT) {
                timings?.begin(SimulationWork.OTHER_OVERLAYS)
                bitmap.lock {
                    for (y in 0 until world.height) {
                        for (x in 0 until world.width) bitmap[x, y] = nightTint.withA((weather.nightOpacity(x, y) * 255).toInt())
                    }
                }
                overlayFades.getValue(layer).complete(bitmap)
                timings?.end(SimulationWork.OTHER_OVERLAYS)
                return@repeat
            }
            timings?.begin(SimulationWork.OTHER_OVERLAYS)
            bitmap.lock {
                for (y in 0 until world.height) {
                    for (x in 0 until world.width) {
                        bitmap[x, y] = tileColor(layer, weather, x, y).withA(
                            (weatherOverlayOpacity(layer, weather, x, y) * 255).toInt()
                        )
                    }
                }
            }
            overlayFades.getValue(layer).complete(bitmap)
            timings?.end(SimulationWork.OTHER_OVERLAYS)
        }
    }

    internal suspend fun awaitWeather() { weatherJob?.await() }

    private data class WeatherRaster(val atlas: SeasonalClimateAtlas, val seconds: Double,
        val clouds: RasterFrame, val rain: RasterFrame)

    /** One bounded job, no queue or catch-up. Only completed pixels cross to the UI thread. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun updateWeatherRaster(atlas: SeasonalClimateAtlas, seconds: Double, active: Set<ClimateLayer>) {
        if (ClimateLayer.CLOUDS !in active && ClimateLayer.PRECIPITATION !in active) return
        if (weatherAtlas !== atlas) {
            weatherJob?.cancel(); weatherJob = null
            weatherAtlas = atlas; weatherTime = Double.NaN
            for (layer in listOf(ClimateLayer.CLOUDS, ClimateLayer.PRECIPITATION)) overlayFades.getValue(layer).reset()
        }
        weatherJob?.takeIf { it.isCompleted }?.let { job ->
            val frame = job.getCompleted()
            weatherJob = null
            if (frame.atlas === atlas) {
                for ((layer, raster) in listOf(ClimateLayer.CLOUDS to frame.clouds, ClimateLayer.PRECIPITATION to frame.rain)) {
                    val bitmap = overlayBitmaps.getValue(layer)
                    val fade = overlayFades.getValue(layer)
                    fade.begin(bitmap); upload(bitmap, raster); fade.complete(bitmap)
                }
                weatherTime = frame.seconds
            }
        }
        if (weatherJob != null || weatherAge < 0.12 || weatherTime == seconds) return
        weatherAge = 0.0
        val rasterWidth = weatherWidth; val rasterHeight = weatherHeight
        weatherJob = renderScope.async(Dispatchers.Default) {
            val field = ProceduralWeather(atlas, seconds)
            val density = FloatArray(rasterWidth * rasterHeight)
            val clouds = RasterCanvas(rasterWidth, rasterHeight)
            val rain = RasterCanvas(rasterWidth, rasterHeight)
            for (y in 0 until rasterHeight) {
                ensureActive()
                for (x in 0 until rasterWidth) {
                    val sample = field.sample((x + 0.5) * atlas.width / rasterWidth, (y + 0.5) * atlas.height / rasterHeight)
                    density[y * rasterWidth + x] = sample.cloud.toFloat()
                    val snow = atlas.rainfall.snowFraction(x * atlas.width / rasterWidth, y * atlas.height / rasterHeight, seconds)
                    rain[x, y] = precipitationColor(sample.precipitation, snow).withA((precipitationOpacity(sample.precipitation) * 255).toInt())
                }
            }
            for (y in 0 until rasterHeight) for (x in 0 until rasterWidth) {
                val cloud = density[y * rasterWidth + x]
                val upwind = density[maxOf(0, y - 1) * rasterWidth + (x + rasterWidth - 1) % rasterWidth]
                val light = (0.88 + (cloud - upwind) * 1.8).coerceIn(0.55, 1.0)
                clouds[x, y] = RGBA((231 * light).toInt(), (240 * light).toInt(), (252 * light).toInt(), (cloud * 232).toInt())
            }
            WeatherRaster(atlas, seconds, clouds.finish(), rain.finish())
        }
    }

    fun selectTile(x: Int, y: Int) {
        selectionBorder.xy(x, y)
        selectionBorder.visible = true
    }

    fun clearSelection() { selectionBorder.visible = false }

    fun viewportChanged() { windOverlay.viewportChanged() }

    /** Static topographic light, independent of seasonal lighting and physical temperatures. */
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
                val north = world[x, (y - 1).coerceAtLeast(0)].elevation
                val south = world[x, (y + 1).coerceAtMost(world.height - 1)].elevation
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
        weatherAge += seconds
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

    }
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
