package terra.ui

import korlibs.event.Key
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.Colors
import korlibs.image.font.SystemFont
import korlibs.image.format.readBitmap
import korlibs.korge.input.keys
import korlibs.korge.input.mouse
import korlibs.korge.input.onClick
import korlibs.korge.input.onMouseDrag
import korlibs.korge.input.onScroll
import korlibs.korge.scene.Scene
import korlibs.korge.view.*
import korlibs.math.geom.Size
import korlibs.time.seconds
import kotlin.math.pow
import terra.*
import terra.render.TerrainMapView
import terra.simulation.SimulationSpeed
import terra.weather.WeatherLayer
import terra.weather.ClimateBootstrap
import terra.weather.ClimateSnapshot
import terra.weather.WeatherSimulation
import terra.world.Terrain
import terra.world.TerrainGenerationSettings
import terra.world.World

/** Coordinates world generation, simulation, input, and the map UI. */
class AtlasScene : Scene() {
    private var terrainSettings = TerrainGenerationSettings()
    private var world = World(31742, MAP_WIDTH_TILES, MAP_HEIGHT_TILES, terrainSettings)
    private var weather = WeatherSimulation(world)
    private var climateBootstrap = ClimateBootstrap(weather)
    val climateSnapshot: ClimateSnapshot?
        get() = climateBootstrap.snapshot
    private lateinit var mapLayer: Container
    private lateinit var mapViewport: FixedSizeContainer
    private lateinit var mapView: TerrainMapView
    private lateinit var settingsPanel: TerrainSettingsPanel
    private lateinit var uiFont: SystemFont
    private val layerButtons = mutableMapOf<WeatherLayer, Pair<SolidRect, Text>>()
    private val speedButtons = mutableMapOf<SimulationSpeed, Pair<SolidRect, Text>>()
    private lateinit var pauseLabel: Text
    private lateinit var selectionLabel: Text
    private lateinit var frameTimeLabel: Text
    private val activeOverlays = mutableSetOf<WeatherLayer>(WeatherLayer.CLOUDS)
    private var selectedTileX: Int? = null
    private var selectedTileY: Int? = null
    private var paused = false
    private var simulationSpeed = SimulationSpeed.REALTIME
    private var smoothedFrameTimeSeconds = 1.0 / 60.0
    private var frameTimeDisplayElapsed = 0.0
    private var weatherTickElapsedRealSeconds = 0.0
    private var overlayRefreshElapsedRealSeconds = 0.0
    private var pendingWeatherSimulationSeconds = 0.0
    private var zoom = minOf(
        WINDOW_WIDTH / world.width,
        WINDOW_HEIGHT / world.height
    )
    private var panX = 0.0
    private var panY = 0.0
    private var dragStartPanX = 0.0
    private var dragStartPanY = 0.0

    override suspend fun SContainer.sceneMain() {
        uiFont = SystemFont.getDefaultFont()
        solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, Colors["#000000"])
        mapViewport = fixedSizeContainer(Size(WINDOW_WIDTH, WINDOW_HEIGHT), clip = true)
        mapLayer = mapViewport.container { }
        mapView = TerrainMapView(mapLayer, world, loadTerrainTextures())

        drawControls()
        drawSelectionPanel()
        settingsPanel = TerrainSettingsPanel(this, uiFont, terrainSettings, ::updateTerrainSettings)
        settingsPanel.updateBiomeSummary(world.landBiomeSummary())
        drawFrameTimeCounter()
        applyMapTransform()

        mapViewport.keys {
            WeatherLayer.entries.forEach { layer ->
                justDown(Key.valueOf("N${layer.key}")) { toggleLayer(layer) }
            }
            justDown(Key.SPACE) { togglePause() }
        }

        mapViewport.addUpdater { dt ->
            updateFrameTimeCounter(dt.seconds)
            if (!climateBootstrap.isComplete) {
                climateBootstrap.advanceBatch()
                selectionLabel.text = climateBootstrap.status
                if (climateBootstrap.isComplete) {
                    world.applyClimateBiomes(climateSnapshot!!)
                    mapView.updateWorld(world)
                    settingsPanel.updateBiomeSummary(world.landBiomeSummary())
                    mapView.updateOverlays(weather, activeOverlays)
                    updateSelectionInfo()
                }
            } else if (!paused && dt.seconds > 0.0) {
                weatherTickElapsedRealSeconds += dt.seconds
                overlayRefreshElapsedRealSeconds += dt.seconds
                pendingWeatherSimulationSeconds += dt.seconds * simulationSpeed.simulatedSecondsPerRealSecond
                if (weatherTickElapsedRealSeconds >= WEATHER_TICK_SECONDS) {
                    weather.step(pendingWeatherSimulationSeconds)
                    pendingWeatherSimulationSeconds = 0.0
                    weatherTickElapsedRealSeconds %= WEATHER_TICK_SECONDS
                    updateSelectionInfo()
                }
                if (overlayRefreshElapsedRealSeconds >= OVERLAY_REFRESH_SECONDS) {
                    overlayRefreshElapsedRealSeconds %= OVERLAY_REFRESH_SECONDS
                    mapView.updateOverlays(weather, activeOverlays)
                }
            }
        }

        mapViewport.onClick { event ->
            if (!settingsPanel.hasOpenMenu()) selectTileAt(event.currentPosLocal.x, event.currentPosLocal.y)
        }
        mapViewport.onScroll { event ->
            if (settingsPanel.hasOpenMenu()) return@onScroll
            val delta = event.scrollDeltaYLines.toDouble().coerceIn(-64.0, 64.0)
            if (delta != 0.0) zoomBy(1.12.pow(-delta), event.currentPosLocal.x, event.currentPosLocal.y)
        }
        mapViewport.onMouseDrag { drag ->
            if (settingsPanel.hasOpenMenu()) return@onMouseDrag
            if (drag.start) {
                dragStartPanX = panX
                dragStartPanY = panY
            }
            panX = dragStartPanX + drag.dx
            panY = dragStartPanY + drag.dy
            applyMapTransform()
        }
    }

    private suspend fun loadTerrainTextures(): Map<Terrain, Bitmap32> = Terrain.entries.associateWith { terrain ->
        views.currentVfs["art/terrain/64x64/${terrain.name.lowercase()}.png"].readBitmap().toBMP32IfRequired()
    }

    private fun SContainer.drawSelectionPanel() {
        val panelHeight = 120.0
        solidRect(760.0, panelHeight, Colors["#101b20"]).xy(12.0, WINDOW_HEIGHT - panelHeight - 12.0)
        selectionLabel = text(
            "Click a tile to inspect it", font = uiFont, textSize = 16.0, color = Colors["#e3e8d7"]
        ).xy(22.0, WINDOW_HEIGHT - panelHeight + 3.0)
    }

    private fun SContainer.drawFrameTimeCounter() {
        frameTimeLabel = text(
            "Frame 16.7 ms · 60 FPS", font = uiFont, textSize = 12.0, color = Colors["#ffffff"]
        ).xy(WINDOW_WIDTH - 190.0, WINDOW_HEIGHT - 24.0)
    }

    private fun updateFrameTimeCounter(frameDeltaSeconds: Double) {
        if (frameDeltaSeconds <= 0.0) return
        smoothedFrameTimeSeconds = smoothedFrameTimeSeconds * 0.9 + frameDeltaSeconds * 0.1
        frameTimeDisplayElapsed += frameDeltaSeconds
        if (frameTimeDisplayElapsed < 0.25) return
        frameTimeDisplayElapsed %= 0.25
        val milliseconds = formatNumber(smoothedFrameTimeSeconds * 1_000.0, 1)
        frameTimeLabel.text = "Frame $milliseconds ms · ${(1.0 / smoothedFrameTimeSeconds).toInt()} FPS"
    }

    private fun updateTerrainSettings(settings: TerrainGenerationSettings) {
        terrainSettings = settings
        world = World(world.seed, world.width, world.height, settings)
        weather = WeatherSimulation(world)
        climateBootstrap = ClimateBootstrap(weather)
        weatherTickElapsedRealSeconds = 0.0
        overlayRefreshElapsedRealSeconds = 0.0
        pendingWeatherSimulationSeconds = 0.0
        mapView.updateWorld(world)
        mapView.updateOverlays(weather, activeOverlays)
        updateSelectionInfo()
        settingsPanel.updateBiomeSummary(world.landBiomeSummary())
    }

    private fun selectTileAt(screenX: Double, screenY: Double) {
        val mapX = (screenX - mapLayer.x) / zoom
        val mapY = (screenY - mapLayer.y) / zoom
        val x = mapX.toInt()
        val y = mapY.toInt()
        if (x !in 0 until world.width || y !in 0 until world.height) return
        selectedTileX = x
        selectedTileY = y
        mapView.selectTile(x, y)
        updateSelectionInfo()
    }

    private fun updateSelectionInfo() {
        if (!climateBootstrap.isComplete) {
            selectionLabel.text = climateBootstrap.status
            return
        }
        val x = selectedTileX ?: return
        val y = selectedTileY ?: return
        val cell = world[x, y]
        val meanRain = formatNumber(weather.precipitationMmPerHour(x, y), 1)
        val peakRain = formatNumber(weather.peakSubstepPrecipitationMmPerHour(x, y), 1)
        val rainThisUpdate = formatNumber(weather.rainMmThisUpdate(x, y), 1)
        val averageRain = formatNumber(weather.thirtyDayRainMmPerHour(x, y), 1)
        val annualizedRain = weather.annualizedRainMmPerYear(x, y).toInt()
        selectionLabel.text = "Tile ($x, $y) · ${cell.terrain.label} · Elevation ${(cell.elevation * 100).toInt()}%\n" +
            "Air ${formatNumber(weather.airTemperatureC(x, y), 1)}°C · " +
            "Surface ${formatNumber(weather.surfaceTemperatureC(x, y), 1)}°C · " +
            "Annualized rain $annualizedRain mm/year\n" +
            "Humidity ${(weather.relativeHumidity(x, y) * 100).toInt()}% · " +
            "Clouds ${(weather.cloudCover(x, y) * 100).toInt()}% · " +
            "Cloud water ${formatNumber(weather.cloudWaterMm(x, y), 2)} mm\n" +
            "Rain update mean $meanRain mm/h · peak substep (≤3h) $peakRain mm/h · " +
            "total $rainThisUpdate mm\n" +
            "30-day avg $averageRain mm/h · " +
            "Air water ${formatNumber(weather.atmosphericWaterMm(x, y), 1)} mm · " +
            "Soil ${formatNumber(weather.soilWaterMm(x, y), 0)} mm " +
            "(${(weather.soilMoisture(x, y) * 100).toInt()}%)"
    }

    private fun toggleLayer(layer: WeatherLayer) {
        if (!activeOverlays.add(layer)) activeOverlays.remove(layer)
        refreshLayerButtons()
        mapView.updateOverlays(weather, activeOverlays)
    }

    private fun SContainer.drawControls() {
        solidRect(WINDOW_WIDTH - 24.0, 56.0, Colors["#101b20"]).xy(12.0, 12.0)
        val labels = mapOf(
            WeatherLayer.HUMIDITY to ("Humidity" to 78.0),
            WeatherLayer.CLOUDS to ("Clouds" to 70.0),
            WeatherLayer.PRECIPITATION to ("Rain" to 62.0),
            WeatherLayer.CLIMATE_RAIN to ("Rain 30d" to 82.0),
            WeatherLayer.AIR_TEMPERATURE to ("Air temp" to 80.0),
            WeatherLayer.ANNUAL_PRECIPITATION to ("Annual rain" to 98.0),
            WeatherLayer.SURFACE_TEMPERATURE to ("Surface temp" to 112.0),
            WeatherLayer.DAY_NIGHT to ("Day/night" to 88.0),
            WeatherLayer.WIND to ("Wind" to 54.0)
        )
        var x = 20.0
        labels.forEach { (layer, labelAndWidth) ->
            val (label, width) = labelAndWidth
            layerButtons[layer] = controlButton(label, x, width) { toggleLayer(layer) }
            x += width + 5.0
        }
        x += 5.0
        solidRect(1, 30, Colors["#52666a"]).xy(x, 25)
        x += 12.0
        pauseLabel = controlButton("Pause", x, 72.0) { togglePause() }.second
        x += 82.0
        SimulationSpeed.entries.forEach { speed ->
            val width = speed.label.length * 8.0 + 20.0
            speedButtons[speed] = controlButton(speed.label, x, width) { selectSpeed(speed) }
            x += width + 5.0
        }
        refreshLayerButtons()
        refreshSpeedButtons()
    }

    private fun SContainer.controlButton(label: String, x: Double, width: Double, action: () -> Unit) =
        (solidRect(width, 36.0, Colors["#223538"]).xy(x, 22.0) to
            text(label, font = uiFont, textSize = 16.0, color = Colors["#e3e8d7"]).xy(x + 7.0, 30.0)).also { (background, caption) ->
            background.mouse { click { action() } }
            caption.mouse { click { action() } }
        }

    private fun refreshLayerButtons() {
        layerButtons.forEach { (layer, button) ->
            val selected = layer in activeOverlays
            button.first.color = Colors[if (selected) "#e5bd72" else "#223538"]
            button.second.color = Colors[if (selected) "#111a1d" else "#e3e8d7"]
        }
    }

    private fun selectSpeed(speed: SimulationSpeed) {
        simulationSpeed = speed
        refreshSpeedButtons()
    }

    private fun refreshSpeedButtons() {
        speedButtons.forEach { (speed, button) ->
            val selected = speed == simulationSpeed
            button.first.color = Colors[if (selected) "#e5bd72" else "#223538"]
            button.second.color = Colors[if (selected) "#111a1d" else "#e3e8d7"]
        }
    }

    private fun togglePause() {
        paused = !paused
        pauseLabel.text = if (paused) "Play" else "Pause"
    }

    private fun zoomBy(factor: Double, anchorX: Double, anchorY: Double) {
        val worldX = (anchorX - mapLayer.x) / zoom
        val worldY = (anchorY - mapLayer.y) / zoom
        zoom = (zoom * factor).coerceAtLeast(0.1)
        panX = anchorX - worldX * zoom - (WINDOW_WIDTH - world.width * zoom) / 2.0
        panY = anchorY - worldY * zoom - (WINDOW_HEIGHT - world.height * zoom) / 2.0
        applyMapTransform()
    }

    private fun applyMapTransform() {
        val mapWidth = world.width * zoom
        val mapHeight = world.height * zoom
        mapLayer.scaleX = zoom
        mapLayer.scaleY = zoom
        mapLayer.x = (WINDOW_WIDTH - mapWidth) / 2.0 + panX
        mapLayer.y = (WINDOW_HEIGHT - mapHeight) / 2.0 + panY
    }
}

private const val WEATHER_TICK_SECONDS = 0.05
private const val OVERLAY_REFRESH_SECONDS = 0.05
