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
import terra.render.TerrainMapView
import terra.render.weatherLegend
import terra.simulation.SimulationSpeed
import terra.simulation.SimulationFidelity
import terra.simulation.PerformanceProbe
import terra.simulation.SimulationWork
import terra.simulation.SimulationWorker
import terra.simulation.WorldRequest
import terra.simulation.WorldPresentationSnapshot
import terra.weather.WeatherLayer
import terra.weather.ClimateSnapshot
import terra.weather.WeatherModel
import terra.weather.WeatherFieldQuality
import terra.world.Terrain
import terra.world.TerrainGenerationSettings

/** Coordinates world generation, simulation, input, and the map UI. */
class AtlasScene(
    private var dynamicalAtmosphere: Boolean = false,
    private val performanceProbe: PerformanceProbe? = null,
    private val onReviewReady: ((AtlasScene, Container) -> Unit)? = null
) : Scene() {
    init { require(performanceProbe == null || dynamicalAtmosphere) { "Live profiling requires -PweatherModel=dynamical" } }
    private var terrainSettings = TerrainGenerationSettings()
    private var requestedGeneration = 0L
    private val simulation = SimulationWorker(WorldRequest(generation = requestedGeneration,
        terrain = terrainSettings, dynamical = dynamicalAtmosphere))
    private var presentation: WorldPresentationSnapshot? = null
    private val world get() = presentation!!.world
    private val hydrology get() = presentation!!.hydrology
    private val weather: WeatherModel get() = presentation!!.weather
    private val climateReady get() = presentation?.climateReady == true
    val climateSnapshot: ClimateSnapshot? get() = presentation?.climate
    private lateinit var terrainTextures: Map<Terrain, Bitmap32>
    private lateinit var mapLayer: Container
    private lateinit var mapViewport: FixedSizeContainer
    private lateinit var mapView: TerrainMapView
    private lateinit var settingsPanel: TerrainSettingsPanel
    private lateinit var uiFont: SystemFont
    private val layerButtons = mutableMapOf<WeatherLayer, AtlasButton>()
    private lateinit var weatherLayerGroup: Container
    private lateinit var dynamicsLayerGroup: Container
    private lateinit var dynamicsMenuButton: AtlasButton
    private var showingDynamics = false
    private lateinit var speedLabel: Text
    private lateinit var calendarLabel: Text
    private lateinit var footerLabel: Text
    private lateinit var speedMenu: Container
    private val speedChoices = mutableMapOf<SimulationSpeed, AtlasButton>()
    private val fidelityButtons = mutableMapOf<SimulationFidelity?, AtlasButton>()
    private lateinit var slowerButton: AtlasButton
    private lateinit var fasterButton: AtlasButton
    private lateinit var pauseButton: AtlasButton
    private lateinit var inspectorHeader: Text
    private lateinit var inspectorContext: Text
    private lateinit var inspectorClimate: Text
    private val inspectorRows = mutableListOf<Pair<Text, Text>>()
    private val inspectorTabs = mutableListOf<AtlasButton>()
    private var inspectorDetailed = false
    private lateinit var atmosphereLabel: Text
    private lateinit var pauseLabel: Text
    private lateinit var selectionLabel: Text
    private lateinit var frameTimeLabel: Text
    private lateinit var helpPanel: Container
    private lateinit var legendPanel: Container
    private lateinit var modelAction: AtlasButton
    private lateinit var modelSummary: Text
    private var reviewStarted = false
    private val activeOverlays = mutableSetOf<WeatherLayer>(WeatherLayer.CLOUDS)
    private var selectedTileX: Int? = null
    private var selectedTileY: Int? = null
    private var paused = false
    private var manualFidelity = false
    private var requestedFidelity = SimulationFidelity.DETAILED
    private var simulationSpeed = if (dynamicalAtmosphere) SimulationSpeed.ONE_HOUR else SimulationSpeed.REALTIME
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
        drawAtmosphereControls()
        drawSelectionPanel()
        legendPanel = container { }.xy(1112.0, 340.0)
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
            WeatherLayer.entries.forEach { layer ->
                justDown(Key.valueOf(if (layer.key.isDigit()) "N${layer.key}" else layer.key.toString())) {
                    if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) toggleLayer(layer)
                }
            }
            justDown(Key.M) { showHelp() }
            justDown(Key.F) { if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) toggleFidelity() }
            justDown(Key.A) { if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) chooseFidelity(null) }
            justDown(Key.SPACE) { if (!settingsPanel.hasOpenMenu()) togglePause() }
            justDown(Key.HOME) { camera.home(); applyMapTransform() }
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
                    if (settings.clouds) activeOverlays.add(WeatherLayer.CLOUDS) else activeOverlays.remove(WeatherLayer.CLOUDS)
                    if (settings.dayNight) activeOverlays.add(WeatherLayer.DAY_NIGHT) else activeOverlays.remove(WeatherLayer.DAY_NIGHT)
                    refreshLayerButtons()
                    simulation.updateControls(settings.speed, paused, resetPending = true)
                }
            }
            simulation.setCloudsVisible(WeatherLayer.CLOUDS in activeOverlays)
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
                if (previous?.weather?.fieldSemantics?.quality != snapshot.weather.fieldSemantics.quality ||
                    previous?.weather?.hasResolvedDynamics != snapshot.weather.hasResolvedDynamics) {
                    refreshLayerButtons()
                    refreshLegend()
                }
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
                updateAtmosphereStatus()
            }
            performanceProbe?.timings?.end(SimulationWork.FRAME_UPDATE)
            performanceProbe?.afterFrame(if (climateReady && !paused) realSeconds * simulationSpeed.simulatedSecondsPerRealSecond else 0.0)
            if (!reviewStarted && climateReady && onReviewReady != null) {
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

    private fun switchAtmosphere() {
        dynamicalAtmosphere = !dynamicalAtmosphere
        requestedFidelity = SimulationFidelity.DETAILED
        manualFidelity = false
        simulation.useAutomaticFidelity()
        simulationSpeed = if (dynamicalAtmosphere) SimulationSpeed.ONE_HOUR else SimulationSpeed.REALTIME
        updateTerrainSettings(terrainSettings)
        refreshSpeedButtons()
        updateAtmosphereStatus()
    }

    private fun toggleFidelity() {
        if (!dynamicalAtmosphere) return
        if (!manualFidelity) requestedFidelity = presentation?.fidelity ?: SimulationFidelity.DETAILED
        manualFidelity = true
        requestedFidelity = if (requestedFidelity == SimulationFidelity.DETAILED) SimulationFidelity.CLIMATE else SimulationFidelity.DETAILED
        simulation.setFidelity(requestedFidelity)
    }

    private fun SContainer.drawAtmosphereControls() {
        calendarLabel = atlasText(uiFont, "Generating world…", 224.0, 27.0, 18.0)
        atmosphereLabel = atlasText(uiFont, "", 224.0, 53.0, 12.0, muted = true)
        solidRect(WINDOW_WIDTH, 40.0, AtlasUi.panel).xy(0.0, 920.0)
        listOf(null to "Auto", SimulationFidelity.DETAILED to "Weather", SimulationFidelity.CLIMATE to "Climate")
            .forEachIndexed { index, (choice, label) ->
                fidelityButtons[choice] = AtlasButton(this, uiFont, label, 16 + index * 84.0, 926.0, 76.0, 28.0) { chooseFidelity(choice) }
            }
        AtlasButton(this, uiFont, "Model…", 276.0, 926.0, 88.0, 28.0) { showHelp() }
        footerLabel = atlasText(uiFont, "", 384.0, 934.0, 11.0, muted = true)
    }

    private fun chooseFidelity(choice: SimulationFidelity?) {
        if (!dynamicalAtmosphere) return
        manualFidelity = choice != null
        if (choice == null) simulation.useAutomaticFidelity() else {
            requestedFidelity = choice; simulation.setFidelity(choice)
        }
        updateAtmosphereStatus()
    }

    private fun updateAtmosphereStatus() {
        val snapshot = presentation
        fidelityButtons.forEach { (choice, button) ->
            button.enabled = dynamicalAtmosphere
            button.selected = if (choice == null) !manualFidelity else manualFidelity && requestedFidelity == choice
        }
        when {
            simulation.failure != null -> {
                calendarLabel.text = "Simulation stopped"
                atmosphereLabel.text = "Open Settings and rebuild to recover."
                footerLabel.text = simulation.failure.toString().take(100)
            }
            snapshot == null || snapshot.progress.generation != requestedGeneration -> {
                calendarLabel.text = "Building your world…"
                atmosphereLabel.text = "Terrain, water and atmosphere"
                footerLabel.text = "Please wait for generation to finish."
            }
            !snapshot.climateReady -> {
                calendarLabel.text = "Preparing climate…"
                atmosphereLabel.text = snapshot.initializationStatus
                footerLabel.text = "The map will be ready shortly."
            }
            else -> {
                val p = snapshot.progress
                val representative = snapshot.weather.fieldSemantics.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL
                calendarLabel.text = WeatherDisplayText.date(p.completedSeconds, snapshot.yearSeconds)
                val mode = when {
                    !snapshot.dynamical -> "Prescribed weather"
                    snapshot.detailedReentryPending -> "Returning to detailed weather"
                    representative -> "Fast climate · interval estimates"
                    else -> "Detailed weather"
                }
                val state = if (paused && !p.paused) "Pausing…" else if (p.paused) "Paused" else if (p.rateLimited) "Catching up" else "Running"
                atmosphereLabel.text = "$mode  /  $state"
                footerLabel.text = buildList {
                    add(if (p.paused) "Paused" else "Actual ${WeatherDisplayText.rate(p.achievedSecondsPerWallSecond, snapshot.yearSeconds)}")
                    if (representative) add("Cloud motion is illustrative · wind unresolved")
                    else add("Drag to pan · scroll to zoom · click to inspect")
                    if (!p.paused && p.pendingSeconds > p.quantumSeconds) add("Queued ${WeatherDisplayText.duration(p.pendingSeconds, snapshot.yearSeconds)}")
                }.joinToString("   ·   ")
            }
        }
    }

    private suspend fun loadTerrainTextures(): Map<Terrain, Bitmap32> = Terrain.entries.associateWith { terrain ->
        views.currentVfs["art/terrain/64x64/${terrain.name.lowercase()}.png"].readBitmap().toBMP32IfRequired()
    }

    private fun SContainer.drawSelectionPanel() {
        container {
            atlasCard(328.0, 494.0)
            atlasText(uiFont, "TILE INSPECTOR", 16.0, 18.0, 12.0, muted = true)
            inspectorHeader = atlasText(uiFont, "Explore the map", 16.0, 48.0, 23.0)
            inspectorContext = atlasText(uiFont, "Select a tile to see its conditions.", 16.0, 82.0, 12.0, muted = true)
            listOf("Overview", "Details").forEachIndexed { index, name ->
                inspectorTabs += AtlasButton(this, uiFont, name, 16 + index * 152.0, 112.0, 144.0) {
                    inspectorDetailed = index == 1; updateSelectionInfo()
                }
            }
            selectionLabel = atlasText(uiFont, "Click a tile to inspect it.\n\nDrag to pan\nScroll to zoom\nHome to fit the map", 16.0, 170.0, 14.0, muted = true)
            repeat(8) { index ->
                val y = 166 + index * 32.0
                inspectorRows += atlasText(uiFont, "", 16.0, y, 13.0, muted = true) to
                    atlasText(uiFont, "", 166.0, y, 14.0)
            }
            inspectorClimate = atlasText(uiFont, "", 16.0, 438.0, 12.0, muted = true)
            inspectorTabs.first().selected = true
        }.xy(1096.0, 414.0)
    }

    private fun SContainer.drawFrameTimeCounter() {
        frameTimeLabel = atlasText(uiFont, "", 1256.0, 934.0, 11.0, muted = true)
        frameTimeLabel.visible = performanceProbe != null
    }

    private fun refreshLegend() {
        if (!::legendPanel.isInitialized) return
        legendPanel.removeChildren()
        val layer = activeOverlays.firstOrNull { weatherLegend(it) != null }
        val legend = layer?.let(::weatherLegend)
        if (layer?.resolvedDynamicsOnly == true && presentation?.weather?.hasResolvedDynamics != true) {
            legendPanel.atlasText(uiFont, "Dynamics unavailable", 0.0, 0.0, 12.0)
            legendPanel.atlasText(uiFont, "Needs a completed detailed-weather step.", 0.0, 24.0, 11.0, muted = true)
        } else if (legend == null) {
            legendPanel.atlasText(uiFont, "Terrain view", 0.0, 0.0, 12.0)
            legendPanel.atlasText(uiFont, "Click a tile for weather and water.", 0.0, 24.0, 11.0, muted = true)
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

    private fun SContainer.drawHelp() {
        helpPanel = container {
            visible = false
            solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, AtlasUi.background.withA(225))
            container {
                atlasCard(800.0, 550.0)
                atlasText(uiFont, "Controls & weather", 28.0, 28.0, 25.0)
                AtlasButton(this, uiFont, "Close", 688.0, 24.0, 84.0) { helpPanel.visible = false }
                atlasText(uiFont, "NAVIGATE", 28.0, 96.0, 12.0, muted = true)
                atlasText(uiFont, "Drag             Pan the map\nScroll           Zoom at the pointer\nHome            Fit the map\nClick              Inspect a tile\n\nSpace            Pause / resume\nLeft / Right   Change speed\n1–9                Toggle map layers\nEsc                Close panels", 28.0, 130.0, 15.0)
                atlasText(uiFont, "WEATHER & CLIMATE", 420.0, 96.0, 12.0, muted = true)
                atlasText(uiFont, "Auto chooses fidelity for your speed.\nWeather resolves winds and clouds.\nClimate estimates longer intervals.\n\nFast cloud movement is illustrative.\nF switches fidelity · A restores Auto.\n\nThe footer shows actual progress;\nvisual blending never advances time.", 420.0, 130.0, 14.0)
                solidRect(744.0, 1.0, AtlasUi.line).xy(28.0, 362.0)
                modelSummary = atlasText(uiFont, "", 28.0, 384.0, 14.0)
                atlasText(uiFont, "Switching model rebuilds the world and restarts time.\nFidelity changes preserve your world.", 28.0, 414.0, 13.0, muted = true)
                modelAction = AtlasButton(this, uiFont, "Switch model & rebuild", 28.0, 486.0, 340.0, 38.0) {
                    helpPanel.visible = false
                    settingsPanel.closeMenus()
                    switchAtmosphere()
                }
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
        modelSummary.text = "Current model: ${if (dynamicalAtmosphere) "Simulated atmosphere" else "Prescribed weather"}"
        modelAction.text = "Use ${if (dynamicalAtmosphere) "prescribed" else "simulated"} weather & rebuild"
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
        simulation.regenerate(WorldRequest(generation = requestedGeneration, terrain = settings, dynamical = dynamicalAtmosphere))
        simulation.updateControls(simulationSpeed, paused)
        selectedTileX = null; selectedTileY = null
        selectionLabel.text = "Rebuilding world…"
        if (::mapView.isInitialized) mapView.clearSelection()
        settingsPanel.updateBiomeSummary("Rebuilding world…")
        updateSelectionInfo()
        updateAtmosphereStatus()
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
        val ready = climateReady && presentation?.progress?.generation == requestedGeneration
        val x = selectedTileX; val y = selectedTileY
        val selected = ready && x != null && y != null
        selectionLabel.textSize = if (selected && inspectorDetailed) 12.0 else 14.0
        inspectorRows.forEach { (label, value) -> label.visible = selected && !inspectorDetailed; value.visible = label.visible }
        inspectorClimate.visible = selected && !inspectorDetailed
        selectionLabel.visible = !selected || inspectorDetailed
        if (!selected) {
            inspectorHeader.text = if (ready) "Explore the map" else "Building your world…"
            inspectorContext.text = "Select a tile to see its conditions."
            selectionLabel.text = "Click a tile to inspect it.\n\nDrag to pan\nScroll to zoom\nHome to fit the map\n\nChoose a colour layer above\nto explore weather patterns."
            return
        }
        val tx = x; val ty = y
        val period = weather.fieldSemantics
        val estimate = period.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL
        val year = presentation!!.yearSeconds
        val cell = world[tx, ty]
        inspectorHeader.text = cell.terrain.label
        inspectorContext.text = "Tile $tx, $ty · ${if (estimate) "Climate estimates" else if (!dynamicalAtmosphere) "Prescribed conditions" else "Current conditions"}"
        val lake = hydrology.lakeIdAt(tx, ty)
        val rows = listOf(
            "Air${if (estimate) " estimate" else ""}" to "${formatNumber(weather.airTemperatureC(tx, ty), 1)} °C",
            "Surface" to "${formatNumber(weather.surfaceTemperatureC(tx, ty), 1)} °C",
            "Humidity${if (estimate) " est." else ""}" to "${formatNumber(weather.relativeHumidity(tx, ty) * 100, 0)}%",
            "Cloud${if (estimate) " estimate" else " water"}" to "${formatNumber(weather.cloudWaterMm(tx, ty), 2)} mm",
            "Mean rain rate" to "${formatNumber(weather.precipitationMmPerHour(tx, ty), 3)} mm/h",
            "Soil moisture" to "${formatNumber(weather.soilMoisture(tx, ty) * 100, 0)}%",
            "Elevation" to "${formatNumber(cell.elevation * 100, 1)}%",
            "Lake" to if (hydrology.isLakeTileWet(tx, ty)) "Under water" else if (lake >= 0) "Dry basin" else "None"
        )
        rows.forEachIndexed { index, (label, value) -> inspectorRows[index].first.text = label; inspectorRows[index].second.text = value }
        val seasonal = presentation!!.seasonalClimate
        inspectorClimate.text = if (seasonal?.hasAnnualCoverage == true)
            "Annual climate · ${formatNumber(seasonal.annualPrecipitationAtTile(tx, ty, world.width, world.height), 0)} mm/year\n${if (seasonal.containsAggregateEstimates) "Includes climate estimates" else "Observed climate window"}"
            else "Annual climate still collecting\nLand cover is provisional."
        if (inspectorDetailed) {
            val represented = WeatherDisplayText.period(period, year)
            val source = if (dynamicalAtmosphere) WeatherDisplayText.quality(period) else "Prescribed weather"
            selectionLabel.text = "$source\nUpdate covers $represented\n\n" +
                "Rain total   ${formatNumber(weather.rainMmThisUpdate(tx, ty), 3)} mm\n" +
                "${if (estimate) "Wet-phase mean" else "Step peak"}   ${formatNumber(weather.peakSubstepPrecipitationMmPerHour(tx, ty), 3)} mm/h\n" +
                "30-day EMA   ${formatNumber(weather.thirtyDayRainMmPerHour(tx, ty), 3)} mm/h\n" +
                "Rain EMA   ${formatNumber(weather.annualizedRainMmPerYear(tx, ty), 0)} mm/year\n\n" +
                "Air water   ${formatNumber(weather.atmosphericWaterMm(tx, ty), 1)} mm\n" +
                "Soil water   ${formatNumber(weather.soilWaterMm(tx, ty), 1)} mm\n" +
                "Drainage   ${formatNumber(weather.drainageMmThisUpdate(tx, ty), 2)} mm/update\n" +
                "Overflow   ${formatNumber(weather.overflowMmThisUpdate(tx, ty), 2)} mm/update\n\n" +
                "Flow   ${formatNumber(hydrology.dischargeMmTilesPerDay(tx, ty), 2)} mm·tiles/day\n" +
                "Upstream land   ${hydrology.upstreamLandTileCount(tx, ty)} tiles\n" +
                "Flow period   ${WeatherDisplayText.duration(hydrology.routedPeriodDays * 86400, year)}\n" +
                "Water view   ${WeatherDisplayText.date(presentation!!.hydrologyCompletedSeconds, year)}\n" +
                (weather.pressureAnomalyPercent(tx, ty)?.let { "Pressure anomaly   ${formatNumber(it, 2)}%" } ?: "Pressure anomaly   unavailable") + "\n" +
                (weather.relativeVorticityPerSecond(tx, ty)?.let { "Relative curl   ${formatNumber(it / 1e-5, 2)} ×10⁻⁵ s⁻¹" } ?: "Relative curl   unavailable")
        }
    }

    private fun toggleLayer(layer: WeatherLayer) {
        if (layer.resolvedDynamicsOnly) {
            showingDynamics = true
            refreshDynamicsMenu()
            if (presentation?.weather?.hasResolvedDynamics != true) return
        }
        if (layer in activeOverlays) activeOverlays.remove(layer) else {
            // Independent scalar tints cannot share a meaningful colour legend.
            if (weatherLegend(layer) != null) activeOverlays.removeAll { weatherLegend(it) != null }
            activeOverlays.add(layer)
        }
        refreshLayerButtons()
        refreshLegend()
        refreshOverlays()
    }

    private fun refreshOverlays() {
        if (presentation != null && this@AtlasScene::mapView.isInitialized) mapView.updateOverlays(weather, activeOverlays, presentation?.clouds, presentation!!.solar)
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
            atlasCard(328.0, 306.0)
            atlasText(uiFont, "MAP LAYERS", 16.0, 18.0, 12.0, muted = true)
            dynamicsMenuButton = AtlasButton(this, uiFont, "Dynamics", 140.0, 12.0, 100.0, 28.0) {
                showingDynamics = !showingDynamics; refreshDynamicsMenu()
            }
            AtlasButton(this, uiFont, "Clear", 248.0, 12.0, 64.0, 28.0) {
                activeOverlays.removeAll { weatherLegend(it) != null }; refreshLayerButtons(); refreshLegend(); refreshOverlays()
            }
            atlasText(uiFont, "Colour overlay", 16.0, 52.0, 12.0, muted = true)
            weatherLayerGroup = container {
                listOf(WeatherLayer.HUMIDITY to "1  Humidity", WeatherLayer.PRECIPITATION to "3  Rain",
                WeatherLayer.AIR_TEMPERATURE to "5  Air temp", WeatherLayer.SURFACE_TEMPERATURE to "7  Surface temp",
                WeatherLayer.CLIMATE_RAIN to "4  30-day rain", WeatherLayer.ANNUAL_PRECIPITATION to "6  Annual rain EMA")
                .forEachIndexed { index, (layer, label) ->
                    layerButtons[layer] = AtlasButton(this, uiFont, label, 16 + index % 2 * 152.0,
                        76 + index / 2 * 40.0, 144.0) { toggleLayer(layer) }
                }
            }
            dynamicsLayerGroup = container {
                visible = false
                listOf(WeatherLayer.PRESSURE_ANOMALY to "P  Pressure", WeatherLayer.RELATIVE_VORTICITY to "V  Vorticity")
                    .forEachIndexed { index, (layer, label) ->
                        layerButtons[layer] = AtlasButton(this, uiFont, label, 16 + index * 152.0, 76.0, 144.0) { toggleLayer(layer) }
                    }
                atlasText(uiFont, "Resolved atmosphere only.\nUnavailable in fast/prescribed modes.\nP pressure · V relative vorticity", 16.0, 126.0, 12.0, muted = true)
            }
            listOf(WeatherLayer.CLOUDS to "2 Clouds", WeatherLayer.WIND to "9 Wind", WeatherLayer.DAY_NIGHT to "8 Light")
                .forEachIndexed { index, (layer, label) ->
                    layerButtons[layer] = AtlasButton(this, uiFont, label, 16 + index * 102.0, 204.0, 94.0, 32.0) { toggleLayer(layer) }
                }
        }.xy(1096.0, 92.0)
        refreshLayerButtons(); refreshSpeedButtons()
    }

    private fun refreshLayerButtons() {
        layerButtons.forEach { (layer, button) ->
            button.selected = layer in activeOverlays
            if (layer.resolvedDynamicsOnly) button.enabled = presentation?.weather?.hasResolvedDynamics == true
            if (layer == WeatherLayer.DAY_NIGHT) button.text = if (presentation?.weather?.fieldSemantics?.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL) "8 Season" else "8 Light"
        }
    }

    private fun refreshDynamicsMenu() {
        weatherLayerGroup.visible = !showingDynamics
        dynamicsLayerGroup.visible = showingDynamics
        dynamicsMenuButton.text = if (showingDynamics) "Weather" else "Dynamics"
        refreshLayerButtons()
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
    }

    /** Explicit development-preview states; never called during ordinary play. */
    internal fun prepareUiReview(state: String) {
        if (!paused) togglePause()
        helpPanel.visible = false; settingsPanel.closeMenus(); speedMenu.visible = false
        when (state) {
            "pressure", "vorticity" -> {
                activeOverlays.remove(WeatherLayer.CLOUDS)
                toggleLayer(if (state == "pressure") WeatherLayer.PRESSURE_ANOMALY else WeatherLayer.RELATIVE_VORTICITY)
                selectedTileX = world.width * 3 / 4; selectedTileY = world.height / 2
                inspectorDetailed = true; updateSelectionInfo()
            }
            "inspection", "details" -> {
                activeOverlays.remove(WeatherLayer.CLOUDS)
                if (WeatherLayer.HUMIDITY !in activeOverlays) toggleLayer(WeatherLayer.HUMIDITY)
                selectedTileX = world.width * 3 / 4; selectedTileY = world.height / 2
                mapView.selectTile(selectedTileX!!, selectedTileY!!)
                inspectorDetailed = state == "details"
                updateSelectionInfo()
            }
            "speed" -> { speedMenu.visible = true; speedMenu.parent?.sendChildToFront(speedMenu) }
            "settings" -> settingsPanel.prepareReview(0)
            "climate-settings" -> settingsPanel.prepareReview(1)
            "advanced-settings" -> settingsPanel.prepareReview(2)
            "settings-edited" -> settingsPanel.prepareReview(0, edited = true)
            "help" -> showHelp()
        }
        updateAtmosphereStatus()
    }
}
