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
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.render.TerrainMapView
import terra.simulation.SimulationSpeed
import terra.weather.WeatherLayer
import terra.weather.ClimateBootstrap
import terra.weather.ClimateSnapshot
import terra.weather.WeatherSimulation
import terra.weather.WeatherModel
import terra.weather.DynamicalWeather
import terra.world.Terrain
import terra.world.TerrainGenerationSettings
import terra.world.World

/** Coordinates world generation, simulation, input, and the map UI. */
class AtlasScene(
    private val experiment: AtlasExperiment? = null,
    private var dynamicalAtmosphere: Boolean = false
) : Scene() {
    private var terrainSettings = TerrainGenerationSettings()
    private var world = World(31742, MAP_WIDTH_TILES, MAP_HEIGHT_TILES, terrainSettings)
    private var hydrologyTopology = HydrologyTopology(world)
    private var hydrology = HydrologySimulation(hydrologyTopology)
    private var weather: WeatherModel = createWeather()
    private var climateBootstrap = createBootstrap()
    private var observedClimate: ClimateSnapshot? = null
    private var nextClimateSampleSeconds = 3600.0
    private var nextBiomeSampleSeconds = 30 * 86400.0
    private val climateReady get() = climateBootstrap?.isComplete != false
    val climateSnapshot: ClimateSnapshot?
        get() = climateBootstrap?.snapshot ?: observedClimate
    private lateinit var mapLayer: Container
    private lateinit var mapViewport: FixedSizeContainer
    private lateinit var mapView: TerrainMapView
    private lateinit var settingsPanel: TerrainSettingsPanel
    private lateinit var uiFont: SystemFont
    private val layerButtons = mutableMapOf<WeatherLayer, Pair<SolidRect, Text>>()
    private val speedButtons = mutableMapOf<SimulationSpeed, Pair<SolidRect, Text>>()
    private lateinit var atmosphereLabel: Text
    private lateinit var pauseLabel: Text
    private lateinit var selectionLabel: Text
    private lateinit var frameTimeLabel: Text
    private val activeOverlays = mutableSetOf<WeatherLayer>(WeatherLayer.CLOUDS)
    private var selectedTileX: Int? = null
    private var selectedTileY: Int? = null
    private var paused = false
    private var simulationSpeed = if (dynamicalAtmosphere) SimulationSpeed.ONE_HOUR else SimulationSpeed.REALTIME
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
        drawAtmosphereControls()
        drawSelectionPanel()
        refreshOverlays()
        settingsPanel = TerrainSettingsPanel(this, uiFont, terrainSettings, ::updateTerrainSettings)
        settingsPanel.updateBiomeSummary(world.landBiomeSummary())
        drawFrameTimeCounter()
        applyMapTransform()

        mapViewport.keys {
            WeatherLayer.entries.forEach { layer ->
                justDown(Key.valueOf("N${layer.key}")) { toggleLayer(layer) }
            }
            justDown(Key.M) { switchAtmosphere() }
            justDown(Key.SPACE) { togglePause() }
        }

        mapViewport.addUpdater { dt ->
            experiment?.beforeFrame(dt.seconds, pendingWeatherSimulationSeconds, !climateReady)?.let { speed ->
                if (speed != simulationSpeed) selectSpeed(speed)
            }
            updateFrameTimeCounter(dt.seconds)
            if (!climateReady) {
                experiment?.beginWork(AtlasWork.WEATHER)
                climateBootstrap!!.advanceBatch()
                experiment?.endWork(AtlasWork.WEATHER)
                selectionLabel.text = climateBootstrap!!.status
                if (climateReady) {
                    world.applyClimateBiomes(climateSnapshot!!)
                    mapView.updateWorld(world)
                    settingsPanel.updateBiomeSummary(world.landBiomeSummary())
                    refreshOverlays()
                    refreshSurfaceWater()
                    updateSelectionInfo()
                }
            } else if (!paused && dt.seconds > 0.0) {
                weatherTickElapsedRealSeconds += dt.seconds
                overlayRefreshElapsedRealSeconds += dt.seconds
                pendingWeatherSimulationSeconds += dt.seconds * simulationSpeed.simulatedSecondsPerRealSecond
                if (dynamicalAtmosphere || weatherTickElapsedRealSeconds >= WEATHER_TICK_SECONDS) {
                    experiment?.beginWork(AtlasWork.WEATHER)
                    val elapsedSimulatedSeconds = (weather as? DynamicalWeather)?.advanceBudgeted(pendingWeatherSimulationSeconds)
                        ?: pendingWeatherSimulationSeconds.also { weather.step(it) }
                    experiment?.endWork(AtlasWork.WEATHER, elapsedSimulatedSeconds)
                    if (elapsedSimulatedSeconds > 0.0) handoffWeatherStep(elapsedSimulatedSeconds)
                    pendingWeatherSimulationSeconds = maxOf(0.0, pendingWeatherSimulationSeconds - elapsedSimulatedSeconds)
                    updateObservedClimate()
                    weatherTickElapsedRealSeconds %= WEATHER_TICK_SECONDS
                    updateSelectionInfo()
                }
                if (overlayRefreshElapsedRealSeconds >= OVERLAY_REFRESH_SECONDS) {
                    overlayRefreshElapsedRealSeconds %= OVERLAY_REFRESH_SECONDS
                    refreshOverlays()
                }
            }
            updateAtmosphereStatus()
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

    private fun createWeather(): WeatherModel = if (dynamicalAtmosphere) DynamicalWeather(world, hydrology) else WeatherSimulation(world)

    private fun createBootstrap() = (weather as? WeatherSimulation)?.let { ClimateBootstrap(it, ::handoffWeatherStep) }

    private fun switchAtmosphere() {
        dynamicalAtmosphere = !dynamicalAtmosphere
        simulationSpeed = if (dynamicalAtmosphere) SimulationSpeed.ONE_HOUR else SimulationSpeed.REALTIME
        updateTerrainSettings(terrainSettings)
        refreshSpeedButtons()
        updateAtmosphereStatus()
    }

    private fun SContainer.drawAtmosphereControls() {
        val background = solidRect(680.0, 34.0, Colors["#101b20"]).xy(12.0, 76.0)
        atmosphereLabel = text("", font = uiFont, textSize = 14.0, color = Colors["#e3e8d7"]).xy(22.0, 84.0)
        background.mouse { click { switchAtmosphere() } }
        atmosphereLabel.mouse { click { switchAtmosphere() } }
        updateAtmosphereStatus()
    }

    private fun updateAtmosphereStatus() {
        val dynamic = weather as? DynamicalWeather
        atmosphereLabel.text = if (dynamic == null) "Weather: prescribed · M / click to switch (restarts world)"
        else "Weather: dynamical 96×72 · ${formatNumber(dynamic.elapsedSeconds / 86400.0, 2)} days · " +
            "pending ${formatNumber(pendingWeatherSimulationSeconds / 3600.0, 1)}h · M / click restarts mode"
    }

    private fun updateObservedClimate() {
        val dynamic = weather as? DynamicalWeather ?: return
        if (dynamic.elapsedSeconds < nextClimateSampleSeconds) return
        observedClimate = dynamic.observedClimate()
        nextClimateSampleSeconds = dynamic.elapsedSeconds + 3600.0
        if (dynamic.elapsedSeconds >= nextBiomeSampleSeconds) {
            world.applyClimateBiomes(observedClimate!!)
            mapView.updateWorld(world)
            refreshSurfaceWater()
            settingsPanel.updateBiomeSummary(world.landBiomeSummary())
            nextBiomeSampleSeconds = Double.POSITIVE_INFINITY
        }
    }

    private suspend fun loadTerrainTextures(): Map<Terrain, Bitmap32> = Terrain.entries.associateWith { terrain ->
        views.currentVfs["art/terrain/64x64/${terrain.name.lowercase()}.png"].readBitmap().toBMP32IfRequired()
    }

    private fun SContainer.drawSelectionPanel() {
        val panelHeight = 164.0
        solidRect(1140.0, panelHeight, Colors["#101b20"]).xy(12.0, WINDOW_HEIGHT - panelHeight - 12.0)
        selectionLabel = text(
            "Click a tile to inspect it", font = uiFont, textSize = 15.0, color = Colors["#e3e8d7"]
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
        hydrologyTopology = HydrologyTopology(world)
        hydrology = HydrologySimulation(hydrologyTopology)
        weather = createWeather()
        climateBootstrap = createBootstrap()
        observedClimate = null
        nextClimateSampleSeconds = 3600.0
        nextBiomeSampleSeconds = 30 * 86400.0
        weatherTickElapsedRealSeconds = 0.0
        overlayRefreshElapsedRealSeconds = 0.0
        pendingWeatherSimulationSeconds = 0.0
        mapView.updateWorld(world)
        refreshOverlays()
        refreshSurfaceWater()
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
        if (!climateReady) {
            selectionLabel.text = climateBootstrap!!.status
            return
        }
        val x = selectedTileX ?: return
        val y = selectedTileY ?: return
        val cell = world[x, y]
        val meanRain = formatNumber(weather.precipitationMmPerHour(x, y), 3)
        val peakRain = formatNumber(weather.peakSubstepPrecipitationMmPerHour(x, y), 3)
        val rainThisUpdate = formatNumber(weather.rainMmThisUpdate(x, y), 3)
        val averageRain = formatNumber(weather.thirtyDayRainMmPerHour(x, y), 3)
        val annualizedRain = formatNumber(weather.annualizedRainMmPerYear(x, y), 1)
        val climate = climateSnapshot
        val observedRain = climate?.let { formatNumber(it.precipitationTotalMm(x, y), 1) } ?: "0"
        val observedDays = climate?.let { formatNumber(it.sampledDays, 2) } ?: "0"
        val upstreamArea = hydrology.upstreamLandTileCount(x, y)
        val discharge = formatNumber(hydrology.dischargeMmTilesPerDay(x, y), 2)
        val soilDrainage = formatNumber(weather.drainageMmThisUpdate(x, y), 2)
        val soilOverflow = formatNumber(weather.overflowMmThisUpdate(x, y), 2)
        val lakeId = hydrology.lakeIdAt(x, y)
        val lakeInfo = if (lakeId >= 0) {
            "candidate $lakeId · storage ${formatNumber(hydrology.lakeStorageMmTiles(lakeId), 1)} mm·tiles · " +
                "surface ${formatNumber(hydrology.lakeWaterSurfaceElevation(lakeId), 3)} normalized"
        } else {
            "no candidate basin"
        }
        val lakeWetStatus = if (hydrology.isLakeTileWet(x, y)) "under lake water" else "dry"
        selectionLabel.text = "Tile ($x, $y) · ${cell.terrain.label} · Elevation ${(cell.elevation * 100).toInt()}%\n" +
            "Air ${formatNumber(weather.airTemperatureC(x, y), 1)}°C · " +
            "Surface ${formatNumber(weather.surfaceTemperatureC(x, y), 1)}°C · " +
            "Rain estimate $annualizedRain mm/year (30-day EMA) · " +
            "Observed $observedDays-day total $observedRain mm\n" +
            "Humidity ${(weather.relativeHumidity(x, y) * 100).toInt()}% · " +
            "Clouds ${(weather.cloudCover(x, y) * 100).toInt()}% · " +
            "Cloud water ${formatNumber(weather.cloudWaterMm(x, y), 2)} mm\n" +
            "Rain update mean $meanRain mm/h · peak physical step $peakRain mm/h · " +
            "total $rainThisUpdate mm\n" +
            "30-day EMA $averageRain mm/h · " +
            "Air water ${formatNumber(weather.atmosphericWaterMm(x, y), 1)} mm · " +
            "Soil ${formatNumber(weather.soilWaterMm(x, y), 0)} mm " +
            "(${(weather.soilMoisture(x, y) * 100).toInt()}%)\n" +
            "Upstream area $upstreamArea land tiles · flow $discharge mm·tiles/day proxy\n" +
            "Last weather update: soil drainage $soilDrainage mm · overflow $soilOverflow mm\n" +
            "Lake $lakeInfo · $lakeWetStatus"
    }

    private fun handoffWeatherStep(elapsedSimulatedSeconds: Double) {
        val previousRevision = hydrology.routedPeriodRevision
        experiment?.beginWork(AtlasWork.HYDROLOGY)
        hydrology.acceptWeatherStep(weather, elapsedSimulatedSeconds)
        experiment?.endWork(AtlasWork.HYDROLOGY)
        if (hydrology.routedPeriodRevision != previousRevision &&
            climateReady && this::mapView.isInitialized
        ) {
            refreshSurfaceWater()
        }
    }

    private fun toggleLayer(layer: WeatherLayer) {
        if (!activeOverlays.add(layer)) activeOverlays.remove(layer)
        refreshLayerButtons()
        refreshOverlays()
    }

    private fun refreshOverlays() {
        experiment?.beginWork(AtlasWork.OVERLAYS)
        mapView.updateOverlays(weather, activeOverlays)
        experiment?.endWork(AtlasWork.OVERLAYS)
    }

    private fun refreshSurfaceWater() {
        experiment?.beginWork(AtlasWork.SURFACE_WATER)
        mapView.updateSurfaceWater(hydrology)
        experiment?.endWork(AtlasWork.SURFACE_WATER)
    }

    private fun SContainer.drawControls() {
        solidRect(WINDOW_WIDTH - 24.0, 56.0, Colors["#101b20"]).xy(12.0, 12.0)
        val labels = mapOf(
            WeatherLayer.HUMIDITY to ("Humidity" to 78.0),
            WeatherLayer.CLOUDS to ("Clouds" to 70.0),
            WeatherLayer.PRECIPITATION to ("Rain" to 62.0),
            WeatherLayer.CLIMATE_RAIN to ("30d EMA mm/h" to 108.0),
            WeatherLayer.AIR_TEMPERATURE to ("Air temp" to 80.0),
            WeatherLayer.ANNUAL_PRECIPITATION to ("EMA rain/year" to 110.0),
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

/** Optional desktop measurement seam; an ordinary AtlasScene has no observer. */
enum class AtlasWork { WEATHER, HYDROLOGY, OVERLAYS, SURFACE_WATER }

interface AtlasExperiment {
    fun beforeFrame(reportedSeconds: Double, pendingSeconds: Double, bootstrapping: Boolean): SimulationSpeed?
    fun beginWork(work: AtlasWork)
    fun endWork(work: AtlasWork, completedSeconds: Double = 0.0)
}
