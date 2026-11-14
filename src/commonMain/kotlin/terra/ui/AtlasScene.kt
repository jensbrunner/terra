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
import kotlin.math.pow
import kotlin.time.TimeSource
import terra.*
import terra.render.WindGlyph
import terra.render.TerrainMapView
import terra.render.weatherLegend
import terra.simulation.SimulationSpeed
import terra.simulation.PerformanceProbe
import terra.simulation.SimulationWork
import terra.simulation.SimulationWorker
import terra.simulation.WorldRequest
import terra.simulation.WorldPresentationSnapshot
import terra.climate.ClimateLayer
import terra.climate.EnvironmentSnapshot
import terra.world.Terrain
import terra.world.TerrainGenerationSettings

/** Coordinates world generation, simulation, input, and the map UI. */
class AtlasScene(
    private val performanceProbe: PerformanceProbe? = null,
    private val onReviewReady: ((AtlasScene, Container) -> Unit)? = null
) : Scene() {
    private var terrainSettings = TerrainGenerationSettings()
    private var requestedGeneration = 0L
    private val simulation = SimulationWorker(WorldRequest(generation = requestedGeneration,
        terrain = terrainSettings))
    private var presentation: WorldPresentationSnapshot? = null
    private val world get() = presentation!!.world
    private val hydrology get() = presentation!!.drainage
    private val weather: EnvironmentSnapshot get() = presentation!!.weather
    private val worldReady get() = presentation != null
    private lateinit var terrainTextures: Map<Terrain, Bitmap32>
    private lateinit var mapLayer: Container
    private lateinit var mapViewport: FixedSizeContainer
    private lateinit var mapView: TerrainMapView
    private lateinit var settingsPanel: TerrainSettingsPanel
    private lateinit var uiFont: SystemFont
    private val layerButtons = mutableMapOf<ClimateLayer, AtlasButton>()
    private lateinit var climateLayerGroup: Container
    private lateinit var speedLabel: Text
    private lateinit var calendarLabel: Text
    private lateinit var footerLabel: Text
    private lateinit var speedMenu: Container
    private val speedChoices = mutableMapOf<SimulationSpeed, AtlasButton>()
    private lateinit var slowerButton: AtlasButton
    private lateinit var fasterButton: AtlasButton
    private lateinit var pauseButton: AtlasButton
    private lateinit var inspectorHeader: Text
    private lateinit var inspectorContext: Text
    private lateinit var inspectorClimate: Text
    private val inspectorRows = mutableListOf<Pair<Text, Text>>()
    private val inspectorTabs = mutableListOf<AtlasButton>()
    private var inspectorDetailed = false
    private lateinit var simulationLabel: Text
    private lateinit var pauseLabel: Text
    private lateinit var selectionLabel: Text
    private lateinit var frameTimeLabel: Text
    private lateinit var helpPanel: Container
    private lateinit var windLegendPanel: Container
    private lateinit var legendPanel: Container
    private var reviewStarted = false
    private val activeOverlays = mutableSetOf<ClimateLayer>()
    private var selectedTileX: Int? = null
    private var selectedTileY: Int? = null
    private var paused = false
    private var simulationSpeed = SimulationSpeed.ONE_HOUR
    private var smoothedFrameTimeSeconds = 1.0 / 60.0
    private var frameTimeDisplayElapsed = 0.0
    private var statusDisplayElapsed = 0.1
    private var previousFrameTime = TimeSource.Monotonic.markNow()
    private val camera = MapCamera(1064.0, 816.0, MAP_WIDTH_TILES, MAP_HEIGHT_TILES)
    private var dragStartPanX = 0.0
    private var dragStartPanY = 0.0
    private var draggedMap = false

    override suspend fun SContainer.sceneMain() {
        uiFont = SystemFont.getDefaultFont()
        solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, AtlasUi.background)
        mapViewport = fixedSizeContainer(Size(camera.viewportWidth, camera.viewportHeight), clip = true).xy(16.0, 92.0)
        mapLayer = mapViewport.container { }
        terrainTextures = loadTerrainTextures()

        drawControls()
        drawSimulationControls()
        drawSelectionPanel()
        legendPanel = container { }.xy(1112.0, 378.0)
        drawWindLegend()
        refreshLegend()
        refreshOverlays()
        settingsPanel = TerrainSettingsPanel(this, uiFont, terrainSettings, ::updateTerrainSettings) {
            helpPanel.visible = false; speedMenu.visible = false
        }
        settingsPanel.updateBiomeSummary(presentation?.world?.landBiomeSummary() ?: "Generating world…")
        drawFrameTimeCounter()
        drawHelp()
        applyMapTransform()

        mapViewport.keys {
            ClimateLayer.entries.forEach { layer ->
                justDown(Key.valueOf(if (layer.key.isDigit()) "N${layer.key}" else layer.key.toString())) {
                    if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) toggleLayer(layer)
                }
            }
            justDown(Key.M) { showHelp() }
            justDown(Key.SPACE) { if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) togglePause() }
            justDown(Key.HOME) { if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) { camera.home(); applyMapTransform() } }
            justDown(Key.H) { toggleHelp() }
            justDown(Key.ESCAPE) { helpPanel.visible = false; speedMenu.visible = false; settingsPanel.closeMenus() }
            justDown(Key.LEFT) { if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) cycleSpeed(-1) }
            justDown(Key.RIGHT) { if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) cycleSpeed(1) }
        }

        mapViewport.addUpdater { _ ->
            val frameTime = TimeSource.Monotonic.markNow()
            val realSeconds = (frameTime - previousFrameTime).inWholeNanoseconds / 1e9
            previousFrameTime = frameTime
            presentation?.let { shown ->
                performanceProbe?.beforeFrame(shown.progress.completedSeconds, shown.progress.pendingSeconds)?.let { settings ->
                    selectSpeed(settings.speed)
                    if (settings.dayNight) activeOverlays.add(ClimateLayer.DAY_NIGHT) else activeOverlays.remove(ClimateLayer.DAY_NIGHT)
                    if (settings.wind) activeOverlays.add(ClimateLayer.WIND) else activeOverlays.remove(ClimateLayer.WIND)
                    refreshLegend()
                    refreshLayerButtons()
                    simulation.updateControls(settings.speed, paused, resetPending = true)
                }
            }
            performanceProbe?.timings?.begin(SimulationWork.FRAME_UPDATE)
            updateFrameTimeCounter(realSeconds)
            val snapshot = simulation.latestSnapshot()
            if (snapshot != null && snapshot.progress.generation == requestedGeneration && snapshot !== presentation) {
                val previous = presentation
                presentation = snapshot
                performanceProbe?.workerProgress(snapshot.progress)
                val changedWorld = previous?.world !== snapshot.world
                if (!this@AtlasScene::mapView.isInitialized) {
                    mapView = TerrainMapView(mapLayer, snapshot.world, terrainTextures, performanceProbe?.timings)
                    applyMapTransform()
                } else if (changedWorld) mapView.updateWorld(snapshot.world)
                if (changedWorld) settingsPanel.updateBiomeSummary(snapshot.world.landBiomeSummary())
                if (previous?.surfaceWater !== snapshot.surfaceWater) refreshSurfaceWater()
                updateSelectionInfo()

            }
            if (this@AtlasScene::mapView.isInitialized) {
                mapView.advanceVisuals(realSeconds)
                refreshOverlays()
            }
            // Status strings only change with publications/controls, not at the
            // display refresh rate. Keep formatting off the animation hot path.
            statusDisplayElapsed += realSeconds
            if (statusDisplayElapsed >= 0.1) {
                statusDisplayElapsed %= 0.1
                updateSimulationStatus()
            }
            performanceProbe?.timings?.end(SimulationWork.FRAME_UPDATE)
            performanceProbe?.afterFrame(if (worldReady && !paused) realSeconds * simulationSpeed.simulatedSecondsPerRealSecond else 0.0)
            if (!reviewStarted && worldReady && onReviewReady != null) {
                reviewStarted = true
                onReviewReady.invoke(this@AtlasScene, this@sceneMain)
            }
        }

        mapViewport.onClick { event ->
            if (speedMenu.visible) speedMenu.visible = false
            else if (!draggedMap && !helpPanel.visible && !settingsPanel.hasOpenMenu()) selectTileAt(event.currentPosLocal.x, event.currentPosLocal.y)
            draggedMap = false
        }
        mapViewport.onScroll { event ->
            if (helpPanel.visible || settingsPanel.hasOpenMenu()) return@onScroll
            val delta = event.scrollDeltaYLines.toDouble().coerceIn(-64.0, 64.0)
            if (delta != 0.0) zoomBy(1.12.pow(-delta), event.currentPosLocal.x, event.currentPosLocal.y)
        }
        mapViewport.onMouseDrag { drag ->
            if (helpPanel.visible || settingsPanel.hasOpenMenu()) return@onMouseDrag
            if (drag.start) {
                dragStartPanX = camera.x
                dragStartPanY = camera.y
                draggedMap = false
            }
            if (drag.dx * drag.dx + drag.dy * drag.dy > 16) draggedMap = true
            camera.moveTo(dragStartPanX + drag.dx, dragStartPanY + drag.dy)
            applyMapTransform()
        }
    }

    override suspend fun sceneDestroy() {
        simulation.close()
        super.sceneDestroy()
    }

    private fun SContainer.drawSimulationControls() {
        calendarLabel = atlasText(uiFont, "Generating world…", 224.0, 27.0, 18.0)
        simulationLabel = atlasText(uiFont, "", 224.0, 53.0, 12.0, muted = true)
        solidRect(WINDOW_WIDTH, 40.0, AtlasUi.panel).xy(0.0, 920.0)
        footerLabel = atlasText(uiFont, "", 276.0, 934.0, 11.0, muted = true)
    }

    private fun updateSimulationStatus() {
        val snapshot = presentation
        when {
            simulation.failure != null -> {
                calendarLabel.text = "Simulation stopped"
                simulationLabel.text = "Open Settings and rebuild to recover."
                footerLabel.text = simulation.failure.toString().take(100)
            }
            snapshot == null || snapshot.progress.generation != requestedGeneration -> {
                calendarLabel.text = "Building your world…"
                simulationLabel.text = "Terrain, climate and water"
                footerLabel.text = "Please wait for generation to finish."
            }
            else -> {
                val p = snapshot.progress
                calendarLabel.text = SimulationDisplayText.date(p.completedSeconds, snapshot.yearSeconds)
                val mode = "Seasonal climate atlas"
                val state = if (paused && !p.paused) "Pausing…" else if (p.paused) "Paused" else if (p.rateLimited) "Catching up" else "Running"
                simulationLabel.text = "$mode  /  $state"
                footerLabel.text = buildList {
                    add(if (p.paused) "Paused" else "Actual ${SimulationDisplayText.rate(p.achievedSecondsPerWallSecond, snapshot.yearSeconds)}")
                    add("Monthly prevailing winds · climate means")
                    if (!p.paused && p.pendingSeconds > p.quantumSeconds) add("Queued ${SimulationDisplayText.duration(p.pendingSeconds, snapshot.yearSeconds)}")
                }.joinToString("   ·   ")
            }
        }
    }

    private suspend fun loadTerrainTextures(): Map<Terrain, Bitmap32> = Terrain.entries.associateWith { terrain ->
        views.currentVfs["art/terrain/64x64/${terrain.name.lowercase()}.png"].readBitmap().toBMP32IfRequired()
    }

    private fun SContainer.drawSelectionPanel() {
        container {
            atlasCard(328.0, 450.0)
            atlasText(uiFont, "TILE INSPECTOR", 16.0, 18.0, 12.0, muted = true)
            inspectorHeader = atlasText(uiFont, "Explore the map", 16.0, 48.0, 23.0)
            inspectorContext = atlasText(uiFont, "Select a tile to see its conditions.", 16.0, 82.0, 12.0, muted = true)
            listOf("Overview", "Details").forEachIndexed { index, name ->
                inspectorTabs += AtlasButton(this, uiFont, name, 16 + index * 152.0, 100.0, 144.0, 32.0) {
                    inspectorDetailed = index == 1; updateSelectionInfo()
                }
            }
            selectionLabel = atlasText(uiFont, "Click a tile to inspect it.\n\nDrag to pan\nScroll to zoom\nHome to fit the map", 16.0, 152.0, 14.0, muted = true)
            repeat(8) { index ->
                val y = 152 + index * 26.0
                inspectorRows += atlasText(uiFont, "", 16.0, y, 13.0, muted = true) to
                    atlasText(uiFont, "", 166.0, y, 14.0)
            }
            inspectorClimate = atlasText(uiFont, "", 16.0, 372.0, 12.0, muted = true)
            inspectorTabs.first().selected = true
        }.xy(1096.0, 458.0)
    }

    private fun SContainer.drawFrameTimeCounter() {
        frameTimeLabel = atlasText(uiFont, "", 1256.0, 934.0, 11.0, muted = true)
        frameTimeLabel.visible = performanceProbe != null
    }

    private fun refreshLegend() {
        if (!::legendPanel.isInitialized) return
        windLegendPanel.visible = ClimateLayer.WIND in activeOverlays
        legendPanel.removeChildren()
        val layer = activeOverlays.firstOrNull { weatherLegend(it) != null }
        val legend = layer?.let { weatherLegend(it) }
        if (legend == null) {
            when {
                ClimateLayer.WIND in activeOverlays -> {
                    legendPanel.atlasText(uiFont, "Wind speed · m/s", 0.0, 0.0, 12.0)
                    legendPanel.atlasText(uiFont, "Length and colour show speed. Key on the map.", 0.0, 24.0, 11.0, muted = true)
                }
                ClimateLayer.DAY_NIGHT in activeOverlays -> {
                    legendPanel.atlasText(uiFont, "Day and night", 0.0, 0.0, 12.0)
                    legendPanel.atlasText(uiFont, "Dark shading marks night; edges show twilight.", 0.0, 24.0, 11.0, muted = true)
                }
                else -> {
                    legendPanel.atlasText(uiFont, "Terrain view", 0.0, 0.0, 12.0)
                    legendPanel.atlasText(uiFont, "Click a tile for climate and water.", 0.0, 24.0, 11.0, muted = true)
                }
            }
        } else {
            legendPanel.atlasText(uiFont, legend.title, 0.0, 0.0, 12.0)
            for (i in 0..99) {
                legendPanel.solidRect(2.96, 7.0, legend.colorAt(i / 99.0)).xy(i * 2.96, 22.0)
            }
            legendPanel.atlasText(uiFont, legend.low, 0.0, 34.0, 11.0, muted = true)
            legendPanel.atlasText(uiFont, legend.middle, 126.0, 34.0, 11.0, muted = true)
            legendPanel.atlasText(uiFont, legend.high, 256.0, 34.0, 11.0, muted = true)
        }
    }

    private fun SContainer.drawWindLegend() {
        windLegendPanel = container {
            visible = false
            atlasCard(376.0, 76.0)
            atlasText(uiFont, "WIND SPEED · m/s", 14.0, 9.0, 11.0, muted = true)
            val samples = listOf(2.0, 5.0, 10.0, 20.0)
            graphics(renderer = GraphicsRenderer.SYSTEM) {
                samples.forEachIndexed { index, speed ->
                    WindGlyph.draw(this, 48.0 + index * 92, 39.0, 1.0, 0.0, speed, scale = 3.0)
                }
            }
            samples.forEachIndexed { index, speed ->
                atlasText(uiFont, if (speed == 20.0) "20+" else speed.toInt().toString(), 42.0 + index * 92, 55.0, 11.0)
            }
        }.xy(32.0, 816.0)
    }

    private fun SContainer.drawHelp() {
        helpPanel = container {
            visible = false
            solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, AtlasUi.background.withA(225))
            container {
                atlasCard(800.0, 550.0)
                atlasText(uiFont, "Controls & climate", 28.0, 28.0, 25.0)
                AtlasButton(this, uiFont, "Close", 688.0, 24.0, 84.0) { helpPanel.visible = false }
                atlasText(uiFont, "NAVIGATE", 28.0, 96.0, 12.0, muted = true)
                atlasText(uiFont, "Drag             Pan the map\nScroll           Zoom at the pointer\nHome            Fit the map\nClick              Inspect a tile\n\nSpace            Pause / resume\nLeft / Right   Change speed\n1–9                Toggle map layers\nQ / T             Aridity / mean temperature\nEsc                Close panels", 28.0, 130.0, 15.0)
                atlasText(uiFont, "CLIMATE & LANDSCAPE", 420.0, 96.0, 12.0, muted = true)
                atlasText(uiFont, "The seasonal atlas drives all speeds.\nRain, temperature and winds are monthly means.\n\nSoil, snow, rivers and vegetation\ncontinue evolving as time advances.\n\nWind arrows show speed in m/s.", 420.0, 130.0, 14.0)
                solidRect(744.0, 1.0, AtlasUi.line).xy(28.0, 362.0)
                atlasText(uiFont, "Geography and sunlight determine the seasonal climate atlas.\nThe atlas drives persistent water stores and vegetation.\nSpeed changes how quickly the same world advances.", 28.0, 384.0, 13.0, muted = true)
                lateinit var diagnostics: AtlasButton
                diagnostics = AtlasButton(this, uiFont, "Frame time", 558.0, 486.0, 214.0, 38.0) {
                    frameTimeLabel.visible = !frameTimeLabel.visible
                    diagnostics.selected = frameTimeLabel.visible
                }
                diagnostics.selected = frameTimeLabel.visible
            }.xy((WINDOW_WIDTH - 800) / 2, (WINDOW_HEIGHT - 550) / 2)
        }
    }

    private fun toggleHelp() {
        if (helpPanel.visible) helpPanel.visible = false else showHelp()
    }

    private fun showHelp() {
        settingsPanel.closeMenus()
        speedMenu.visible = false
        helpPanel.parent?.sendChildToFront(helpPanel)
        helpPanel.visible = true
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
        requestedGeneration++
        simulation.regenerate(WorldRequest(generation = requestedGeneration, terrain = settings))
        simulation.updateControls(simulationSpeed, paused)
        selectedTileX = null; selectedTileY = null
        selectionLabel.text = "Rebuilding world…"
        if (::mapView.isInitialized) mapView.clearSelection()
        settingsPanel.updateBiomeSummary("Rebuilding world…")
        updateSelectionInfo()
        updateSimulationStatus()
    }

    private fun selectTileAt(screenX: Double, screenY: Double) {
        if (presentation?.progress?.generation != requestedGeneration || !this@AtlasScene::mapView.isInitialized) return
        val (x, y) = camera.tileAt(screenX, screenY) ?: return
        selectedTileX = x
        selectedTileY = y
        mapView.selectTile(x, y)
        updateSelectionInfo()
    }

    private fun updateSelectionInfo() {
        inspectorTabs.forEachIndexed { index, button -> button.selected = (index == 1) == inspectorDetailed }
        val ready = worldReady && presentation?.progress?.generation == requestedGeneration
        val x = selectedTileX; val y = selectedTileY
        val selected = ready && x != null && y != null
        selectionLabel.textSize = if (selected && inspectorDetailed) 12.0 else 14.0
        inspectorRows.forEach { (label, value) -> label.visible = selected && !inspectorDetailed; value.visible = label.visible }
        inspectorClimate.visible = selected && !inspectorDetailed
        selectionLabel.visible = !selected || inspectorDetailed
        if (!selected) {
            inspectorHeader.text = if (ready) "Explore the map" else "Building your world…"
            inspectorContext.text = "Select a tile to see its conditions."
            selectionLabel.text = "Click a tile to inspect it.\n\nDrag to pan\nScroll to zoom\nHome to fit the map\n\nChoose a colour layer above\nto explore climate patterns."
            return
        }
        val tx = x; val ty = y
        val cell = world[tx, ty]
        inspectorHeader.text = cell.terrain.label
        inspectorContext.text = "Tile $tx, $ty · Seasonal climate"
        val lake = hydrology.lakeIdAt(tx, ty)
        val rows = listOf(
            "Temperature" to "${formatNumber(weather.temperatureC(tx, ty), 1)} °C",
            "Snow" to "${formatNumber(weather.snowWaterMm(tx, ty), 1)} mm",
            "Humidity" to "${formatNumber(weather.relativeHumidity(tx, ty) * 100, 0)}%",
            "Wind speed" to "${formatNumber(weather.windSpeedMetersPerSecond(tx, ty), 1)} m/s",
            "Monthly rain" to "${formatNumber(weather.monthlyClimateRainMm(tx, ty), 0)} mm/month",
            "Soil moisture" to "${formatNumber(weather.soilMoisture(tx, ty) * 100, 0)}%",
            "Elevation" to "${formatNumber(cell.elevation * 100, 1)}%",
            "Lake" to if (hydrology.isLakeTileWet(tx, ty)) "Under water" else if (lake >= 0) "Dry basin" else "None"
        )
        rows.forEachIndexed { index, (label, value) -> inspectorRows[index].first.text = label; inspectorRows[index].second.text = value }
        val ecology = "Canopy ${formatNumber(world.treeCover(tx, ty) * 100, 0)}% · grass ${formatNumber(world.grassCover(tx, ty) * 100, 0)}% · bare ${formatNumber(world.bareCover(tx, ty) * 100, 0)}%\n" +
            "Limit: ${world.growthLimitation(tx, ty).name.lowercase()}"
        inspectorClimate.text = "Annual climate · ${formatNumber(weather.annualizedRainMmPerYear(tx, ty), 0)} mm/year\n$ecology\nDiagnostic seasonal atlas"
        if (inspectorDetailed) {
            selectionLabel.text = "Seasonal climate atlas\nUpdate covers ${SimulationDisplayText.duration(weather.updateSeconds, presentation!!.yearSeconds)}\n" +
                "Rain total   ${formatNumber(weather.rainMmThisUpdate(tx, ty), 3)} mm\n" +
                "Monthly mean rate   ${formatNumber(weather.precipitationMmPerHour(tx, ty), 3)} mm/h\n" +
                "Wind speed   ${formatNumber(weather.windSpeedMetersPerSecond(tx, ty), 1)} m/s\n" +
                "Monthly rain   ${formatNumber(weather.monthlyClimateRainMm(tx, ty), 0)} mm/month\n" +
                "Annual rain   ${formatNumber(weather.annualizedRainMmPerYear(tx, ty), 0)} mm/year\n\n" +
                "Aridity   ${formatNumber(weather.climateAridity(tx, ty), 2)} rain/PET\n" +
                "Soil water   ${formatNumber(weather.soilWaterMm(tx, ty), 1)} mm\n" +
                "Drainage   ${formatNumber(weather.drainageMmThisUpdate(tx, ty), 2)} mm/update\n" +
                "Overflow   ${formatNumber(weather.overflowMmThisUpdate(tx, ty), 2)} mm/update\n\n" +
                "Upstream land   ${hydrology.upstreamLandTileCount(tx, ty)} tiles\n" +
                "River flow   ${formatNumber(hydrology.dischargeMmTilesPerDay(tx, ty), 2)} mm·tiles/day\n" +
                "Snow water   ${formatNumber(weather.snowWaterMm(tx, ty), 1)} mm"
        }
    }

    private fun toggleLayer(layer: ClimateLayer) {
        if (layer in activeOverlays) activeOverlays.remove(layer) else {
            // Independent scalar tints cannot share a meaningful colour legend.
            if (weatherLegend(layer) != null) activeOverlays.removeAll { weatherLegend(it) != null }
            activeOverlays.add(layer)
        }
        refreshLayerButtons()
        refreshLegend()
        refreshOverlays()
        updateSelectionInfo()
    }

    private fun refreshOverlays() {
        if (presentation != null && this@AtlasScene::mapView.isInitialized) mapView.updateOverlays(weather, activeOverlays)
    }

    private fun refreshSurfaceWater() {
        performanceProbe?.timings?.begin(SimulationWork.WATER_RASTER)
        if (presentation != null && this@AtlasScene::mapView.isInitialized) mapView.updateSurfaceWater(presentation!!.surfaceWater)
        performanceProbe?.timings?.end(SimulationWork.WATER_RASTER)
    }

    private fun SContainer.drawControls() {
        container {
            atlasCard(1408.0, 64.0)
            atlasText(uiFont, "TERRA", 16.0, 10.0, 23.0).color = AtlasUi.accent
            atlasText(uiFont, "PLANET EXPLORER", 17.0, 40.0, 10.0, muted = true)
        }.xy(16.0, 16.0)
        pauseButton = AtlasButton(this, uiFont, "Pause", 754.0, 28.0, 90.0) { togglePause() }
        pauseLabel = pauseButton.caption
        slowerButton = AtlasButton(this, uiFont, "−", 852.0, 28.0, 36.0) { cycleSpeed(-1) }
        speedLabel = AtlasButton(this, uiFont, simulationSpeed.label + " ▾", 896.0, 28.0, 142.0) {
            speedMenu.visible = !speedMenu.visible
            if (speedMenu.visible) { refreshSpeedButtons(); speedMenu.parent?.sendChildToFront(speedMenu) }
        }.caption
        fasterButton = AtlasButton(this, uiFont, "+", 1046.0, 28.0, 36.0) { cycleSpeed(1) }
        AtlasButton(this, uiFont, "Home", 1096.0, 28.0, 72.0) { camera.home(); applyMapTransform() }
        AtlasButton(this, uiFont, "Settings", 1176.0, 28.0, 128.0) { settingsPanel.toggle() }
        AtlasButton(this, uiFont, "Help", 1312.0, 28.0, 96.0) { toggleHelp() }
        speedMenu = container { visible = false }.xy(896.0, 76.0)
        speedMenu.atlasCard(186.0, 336.0)
        speedMenu.atlasText(uiFont, "TIME PER SECOND", 12.0, 12.0, 11.0, muted = true)
        SimulationSpeed.entries.forEachIndexed { index, speed ->
            speedChoices[speed] = AtlasButton(speedMenu, uiFont, speed.label, 8.0, 36 + index * 36.0, 170.0, 32.0) {
                selectSpeed(speed); speedMenu.visible = false
            }
        }
        container {
            atlasCard(328.0, 350.0)
            atlasText(uiFont, "MAP LAYERS", 16.0, 18.0, 12.0, muted = true)
            AtlasButton(this, uiFont, "Clear all", 232.0, 12.0, 80.0, 28.0) {
                activeOverlays.clear(); refreshLayerButtons(); refreshLegend(); refreshOverlays(); updateSelectionInfo()
            }
            atlasText(uiFont, "Colour overlay", 16.0, 52.0, 12.0, muted = true)
            climateLayerGroup = container {
                listOf(ClimateLayer.HUMIDITY to "1  Humidity", ClimateLayer.PRECIPITATION to "3  Mean rain rate",
                    ClimateLayer.TEMPERATURE to "5  Temperature",
                    ClimateLayer.CLIMATE_RAIN to "4  Monthly rain", ClimateLayer.ANNUAL_PRECIPITATION to "6  Annual rainfall",
                    ClimateLayer.ARIDITY to "Q  Aridity", ClimateLayer.MEAN_TEMPERATURE to "T  Mean temp")
                .forEachIndexed { index, (layer, label) ->
                    layerButtons[layer] = AtlasButton(this, uiFont, label, 16 + index % 2 * 152.0,
                        76 + index / 2 * 40.0, 144.0) { toggleLayer(layer) }
                }
            }
            listOf(ClimateLayer.WIND to "9 Wind", ClimateLayer.DAY_NIGHT to "8 Light")
                .forEachIndexed { index, (layer, label) ->
                    layerButtons[layer] = AtlasButton(this, uiFont, label, 16 + index * 102.0, 236.0, 94.0, 32.0) { toggleLayer(layer) }
                }
        }.xy(1096.0, 92.0)
        refreshLayerButtons(); refreshSpeedButtons()
    }

    private fun refreshLayerButtons() {
        layerButtons.forEach { (layer, button) ->
            button.selected = layer in activeOverlays
        }
    }

    private fun selectSpeed(speed: SimulationSpeed) {
        simulationSpeed = speed
        simulation.updateControls(speed, paused)
        refreshSpeedButtons()
    }

    private fun cycleSpeed(direction: Int) {
        val speeds = SimulationSpeed.entries
        selectSpeed(speeds[(simulationSpeed.ordinal + direction).coerceIn(0, speeds.size - 1)])
    }

    private fun refreshSpeedButtons() {
        if (::speedLabel.isInitialized) speedLabel.text = simulationSpeed.label + " ▾"
        speedChoices.forEach { (speed, button) -> button.selected = speed == simulationSpeed }
        if (::slowerButton.isInitialized) slowerButton.enabled = simulationSpeed != SimulationSpeed.entries.first()
        if (::fasterButton.isInitialized) fasterButton.enabled = simulationSpeed != SimulationSpeed.entries.last()
    }

    private fun togglePause() {
        paused = !paused
        simulation.updateControls(simulationSpeed, paused)
        pauseLabel.text = if (paused) "Resume" else "Pause"
        pauseButton.selected = paused
    }

    private fun zoomBy(factor: Double, anchorX: Double, anchorY: Double) {
        camera.zoomBy(factor, anchorX, anchorY)
        applyMapTransform()
    }

    private fun applyMapTransform() {
        mapLayer.scaleX = camera.scale
        mapLayer.scaleY = camera.scale
        mapLayer.xy(camera.x, camera.y)
        if (::mapView.isInitialized) mapView.viewportChanged()
    }

    /** Explicit development-preview states; never called during ordinary play. */
    internal fun prepareUiReview(state: String) {
        if (!paused) togglePause()
        helpPanel.visible = false; settingsPanel.closeMenus(); speedMenu.visible = false
        val previewLayer = when (state) {
            "humidity" -> ClimateLayer.HUMIDITY
            "rain" -> ClimateLayer.PRECIPITATION
            "30-day-rain", "monthly-rain" -> ClimateLayer.CLIMATE_RAIN
            "aridity" -> ClimateLayer.ARIDITY
            "mean-temperature" -> ClimateLayer.MEAN_TEMPERATURE
            "annual-rain" -> ClimateLayer.ANNUAL_PRECIPITATION
            "air-temperature" -> ClimateLayer.TEMPERATURE
            "light" -> ClimateLayer.DAY_NIGHT
            "wind", "wind-zoom", "wind-rain" -> ClimateLayer.WIND
            else -> null
        }
        if (state == "wind-zoom") { camera.home(); zoomBy(3.0, camera.viewportWidth / 2, camera.viewportHeight / 2) }
        else { camera.home(); applyMapTransform() }
        if (previewLayer != null) {
            activeOverlays.clear()
            activeOverlays.add(previewLayer)
            refreshLayerButtons(); refreshLegend(); refreshOverlays()
        }
        if (state == "wind-rain") toggleLayer(ClimateLayer.ANNUAL_PRECIPITATION)
        when (state) {
            "inspection", "details" -> {
                activeOverlays.clear()
                if (ClimateLayer.HUMIDITY !in activeOverlays) toggleLayer(ClimateLayer.HUMIDITY)
                selectedTileX = world.width * 3 / 4; selectedTileY = world.height / 2
                mapView.selectTile(selectedTileX!!, selectedTileY!!)
                inspectorDetailed = state == "details"
                updateSelectionInfo()
            }
            "speed" -> { speedMenu.visible = true; speedMenu.parent?.sendChildToFront(speedMenu) }
            "settings" -> settingsPanel.prepareReview(0)
            "advanced-settings" -> settingsPanel.prepareReview(1)
            "settings-edited" -> settingsPanel.prepareReview(0, edited = true)
            "help" -> showHelp()
        }
        mapView.advanceVisuals(0.25)
        refreshOverlays()
        mapView.advanceVisuals(0.25)
        updateSimulationStatus()
    }
}
