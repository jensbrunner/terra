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
import terra.render.ClimateLayer
import terra.render.WindGlyph
import terra.render.TerrainMapView
import terra.render.weatherLegend
import terra.simulation.GeologySettings
import terra.simulation.SimulationSpeed
import terra.simulation.SimulationWorker
import terra.simulation.WorldRequest
import terra.simulation.WorldPresentationSnapshot
import terra.climate.YEAR_SECONDS
import terra.climate.EnvironmentSnapshot
import terra.world.Terrain
import terra.world.TerrainGenerationSettings

/** Coordinates world generation, simulation, input, and the map UI. */
class AtlasScene : Scene() {
    private var terrainSettings = TerrainGenerationSettings()
    private var requestedGeneration = 0L
    private val simulation = SimulationWorker(WorldRequest(generation = requestedGeneration,
        terrain = terrainSettings))
    private var presentation: WorldPresentationSnapshot? = null
    private val world get() = presentation!!.world
    private val hydrology get() = presentation!!.drainage
    private val weather: EnvironmentSnapshot get() = presentation!!.weather
    private lateinit var terrainTextures: Map<Terrain, Bitmap32>
    private lateinit var background: View
    private lateinit var headerBackground: View
    private lateinit var headerActions: Container
    private lateinit var footerBackground: View
    private lateinit var layerPanel: Container
    private lateinit var helpShade: View
    private lateinit var helpContent: Container
    private var layoutWidth = 0.0
    private var layoutHeight = 0.0
    private lateinit var mapLayer: Container
    private lateinit var mapViewport: FixedSizeContainer
    private lateinit var mapView: TerrainMapView
    private lateinit var settingsPanel: TerrainSettingsPanel
    private lateinit var uiFont: SystemFont
    private val layerButtons = mutableMapOf<ClimateLayer, AtlasButton>()
    private lateinit var layerContent: Container
    private lateinit var layerHints: Text
    private lateinit var inspectorHost: Container
    private lateinit var sidebarBackground: View
    private val sidebarTabs = mutableListOf<AtlasButton>()
    private var geologySettings = GeologySettings()
    private lateinit var speedLabel: Text
    private lateinit var calendarLabel: Text
    private lateinit var footerLabel: Text
    private lateinit var speedMenu: Container
    private val speedChoices = mutableMapOf<SimulationSpeed, AtlasButton>()
    private lateinit var pauseButton: AtlasButton
    private lateinit var tileInspector: TileInspector
    private lateinit var simulationLabel: Text
    private lateinit var pauseLabel: Text
    private lateinit var frameTimeLabel: Text
    private lateinit var helpPanel: Container
    private lateinit var windLegendPanel: Container
    private lateinit var legendPanel: Container
    private val activeOverlays = mutableSetOf<ClimateLayer>()
    private var selectedTile: Pair<Int, Int>? = null
    private var paused = false
    private var simulationSpeed = SimulationSpeed.ONE_YEAR
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
        background = solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, AtlasUi.background)
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
        settingsPanel = TerrainSettingsPanel(this, uiFont, terrainSettings, ::updateTerrainSettings, geologySettings, ::updateGeologySettings) {
            helpPanel.visible = false; speedMenu.visible = false
        }
        settingsPanel.updateBiomeSummary(presentation?.world?.landBiomeSummary() ?: "Generating world…")
        drawFrameTimeCounter()
        drawHelp()
        layoutWindow()
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
            layoutWindow()
            val frameTime = TimeSource.Monotonic.markNow()
            val realSeconds = (frameTime - previousFrameTime).inWholeNanoseconds / 1e9
            previousFrameTime = frameTime
            updateFrameTimeCounter(realSeconds)
            val snapshot = simulation.latestSnapshot()
            if (snapshot != null && snapshot.progress.generation == requestedGeneration && snapshot !== presentation) {
                val previous = presentation
                presentation = snapshot
                val changedWorld = previous?.world !== snapshot.world
                if (!this@AtlasScene::mapView.isInitialized) {
                    mapView = TerrainMapView(mapLayer, snapshot.world, terrainTextures)
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

    private fun layoutWindow() {
        val bounds = views.bp.actualVirtualBounds
        val width = bounds.width
        val height = bounds.height
        if (width == layoutWidth && height == layoutHeight) return
        layoutWidth = width; layoutHeight = height
        background.width = width; background.height = height
        val narrow = width < 1320
        val headerHeight = if (narrow) 164.0 else 104.0
        val top = headerHeight + 24
        val bodyHeight = (height - top - 64).coerceAtLeast(360.0)
        headerBackground.width = width - 40; headerBackground.height = headerHeight - 20
        headerActions.xy(width - WINDOW_WIDTH, if (narrow) 60.0 else 0.0)
        layerPanel.xy(width - 420, top)
        sidebarBackground.height = bodyHeight
        layerHints.visible = bodyHeight >= 600
        legendPanel.xy(width - 400, top + if (bodyHeight < 600) 412 else 452)
        footerBackground.width = width; footerBackground.y = height - 44
        footerLabel.xy(24.0, height - 32)
        frameTimeLabel.xy(width - 264, height - 32)
        windLegendPanel.y = height - 174
        mapViewport.xy(20.0, top)
        mapViewport.unscaledSize = Size(width - 460, bodyHeight)
        mapViewport.invalidateRender()
        camera.resize(width - 460, bodyHeight)
        helpShade.width = width; helpShade.height = height
        helpContent.xy((width - 960) / 2, (height - 720) / 2)
        settingsPanel.resize(width, height)
        applyMapTransform()
    }

    override suspend fun sceneDestroy() {
        simulation.close()
        super.sceneDestroy()
    }

    private fun SContainer.drawSimulationControls() {
        calendarLabel = atlasText(uiFont, "Generating world…", 240.0, 30.0, 24.0)
        simulationLabel = atlasText(uiFont, "", 240.0, 66.0, 17.0, muted = true)
        footerBackground = solidRect(WINDOW_WIDTH, 44.0, AtlasUi.panel).xy(0.0, 920.0)
        footerLabel = atlasText(uiFont, "", 24.0, 928.0, 16.0, muted = true)
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
                calendarLabel.text = SimulationDisplayText.date(p.completedSeconds, YEAR_SECONDS)
                val state = if (paused && !p.paused) "Pausing…" else if (p.paused) "Paused" else if (p.rateLimited) "Limited by simulation" else "Running"
                simulationLabel.text = "${SimulationDisplayText.clock(p.completedSeconds)}   ·   $state"
                footerLabel.text = buildList {
                    add(if (p.paused) "Paused" else "Actual ${SimulationDisplayText.rate(p.achievedSecondsPerWallSecond, YEAR_SECONDS)}")
                    val geology = snapshot.geology
                    add("Geology ${formatNumber(geology.effectiveYears / 1_000_000, 1)} Myr")
                    add("×${geology.settings.speedMultiplier} · step ${geology.settings.updateYears} yr")
                    add("${world.movingPlateCount}/${world.plateCount} moving plates")
                    if (!p.paused && p.pendingSeconds > p.quantumSeconds) add("Queued ${SimulationDisplayText.duration(p.pendingSeconds, YEAR_SECONDS)}")
                }.joinToString("   ·   ")
            }
        }
    }

    private suspend fun loadTerrainTextures(): Map<Terrain, Bitmap32> = Terrain.entries.associateWith { terrain ->
        views.currentVfs["art/terrain/64x64/${terrain.name.lowercase()}.png"].readBitmap().toBMP32IfRequired()
    }

    private fun SContainer.drawSelectionPanel() {
        tileInspector = TileInspector(inspectorHost, uiFont)
    }

    private fun SContainer.drawFrameTimeCounter() {
        frameTimeLabel = atlasText(uiFont, "", 1176.0, 928.0, 16.0, muted = true)
        frameTimeLabel.visible = false
    }

    private fun refreshLegend() {
        if (!::legendPanel.isInitialized) return
        windLegendPanel.visible = ClimateLayer.WIND in activeOverlays
        legendPanel.removeChildren()
        val layer = activeOverlays.firstOrNull { weatherLegend(it) != null }
        val legend = layer?.let { weatherLegend(it) }
        if (legend == null) {
            when {
                ClimateLayer.PLATES in activeOverlays -> {
                    legendPanel.atlasText(uiFont, "Plate boundaries", 0.0, 0.0, 18.0)
                    listOf("Plate border" to "#fff1d0", "Collision" to "#f39d76", "Subduction" to "#cf8ad8",
                        "Spreading" to "#5ed4d0", "Rift" to "#e5ca6e", "Transform" to "#8eafe8")
                        .forEachIndexed { i, (name, color) ->
                            val x = (i % 2) * 184.0; val y = 36 + (i / 2) * 30.0
                            legendPanel.solidRect(12.0, 12.0, Colors[color]).xy(x, y + 2)
                            legendPanel.atlasText(uiFont, name, x + 22, y, 16.0)
                        }
                }
                ClimateLayer.WIND in activeOverlays -> {
                    legendPanel.atlasText(uiFont, "Wind speed · m/s", 0.0, 0.0, 18.0)
                    legendPanel.atlasText(uiFont, "Arrow size and colour show speed.\nSee the key on the map.", 0.0, 34.0, 16.0, muted = true)
                }
                ClimateLayer.CLOUDS in activeOverlays -> {
                    legendPanel.atlasText(uiFont, "Cloud cover", 0.0, 0.0, 18.0)
                    legendPanel.atlasText(uiFont, "White shading shows cloud density.", 0.0, 34.0, 16.0, muted = true)
                }
                ClimateLayer.DAY_NIGHT in activeOverlays -> {
                    legendPanel.atlasText(uiFont, "Day and night", 0.0, 0.0, 18.0)
                    legendPanel.atlasText(uiFont, "Dark shading marks night; edges show twilight.", 0.0, 34.0, 16.0, muted = true)
                }
                else -> {
                    legendPanel.atlasText(uiFont, "Terrain view", 0.0, 0.0, 18.0)
                    legendPanel.atlasText(uiFont, "Click a tile for climate and water.", 0.0, 34.0, 16.0, muted = true)
                }
            }
        } else {
            legendPanel.atlasText(uiFont, legend.title, 0.0, 0.0, 18.0)
            for (i in 0..99) {
                legendPanel.solidRect(3.6, 10.0, legend.colorAt(i / 99.0)).xy(i * 3.6, 36.0)
            }
            legendPanel.atlasText(uiFont, legend.low, 0.0, 56.0, 16.0, muted = true)
            legendPanel.atlasText(uiFont, legend.middle, 152.0, 56.0, 16.0, muted = true)
            legendPanel.atlasText(uiFont, legend.high, 296.0, 56.0, 16.0, muted = true)
        }
    }

    private fun SContainer.drawWindLegend() {
        windLegendPanel = container {
            visible = false
            atlasCard(400.0, 96.0)
            atlasText(uiFont, "WIND SPEED · m/s", 16.0, 12.0, 16.0, muted = true)
            val samples = listOf(2.0, 5.0, 10.0, 20.0)
            graphics(renderer = GraphicsRenderer.SYSTEM) {
                samples.forEachIndexed { index, speed ->
                    WindGlyph.draw(this, 48.0 + index * 92, 50.0, 1.0, 0.0, speed, scale = 3.0)
                }
            }
            samples.forEachIndexed { index, speed ->
                atlasText(uiFont, if (speed == 20.0) "20+" else speed.toInt().toString(), 42.0 + index * 92, 69.0, 16.0)
            }
        }.xy(32.0, 816.0)
    }

    private fun SContainer.drawHelp() {
        helpPanel = container {
            visible = false
            helpShade = solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, AtlasUi.background.withA(235))
            helpContent = container {
                atlasCard(960.0, 720.0)
                atlasText(uiFont, "Explore your world", 28.0, 28.0, 30.0)
                AtlasButton(this, uiFont, "Close", 836.0, 24.0, 96.0) { helpPanel.visible = false }
                atlasText(uiFont, "NAVIGATE", 28.0, 106.0, 17.0, muted = true)
                atlasText(uiFont, "Drag                Pan the map\nScroll              Zoom at the pointer\nHome              Fit the map\nClick                Inspect a tile\n\nSpace              Pause / resume\nLeft / Right     Change calendar speed\n1–9                  Toggle map layers\nEsc                  Close panels", 28.0, 148.0, 19.0)
                atlasText(uiFont, "CLIMATE & LANDSCAPE", 492.0, 106.0, 17.0, muted = true)
                atlasText(uiFont, "Latitude, oceans and elevation shape climate.\nMountains create rain shadows.\n\nAnnual rain feeds soil, rivers and lakes.\nPlains become woods, steppe or desert.\n\nVegetation establishes over decades.\nPlates, uplift and erosion reshape the world.", 492.0, 148.0, 18.0)
                solidRect(904.0, 1.0, AtlasUi.line).xy(28.0, 492.0)
                atlasText(uiFont, "Use Max to watch the landscape evolve as fast as your machine allows.\nAdjust geology speed and its update interval in Settings → Simulation.\nThese live controls keep your world; landform edits rebuild it.", 28.0, 520.0, 18.0, muted = true)
                lateinit var diagnostics: AtlasButton
                diagnostics = AtlasButton(this, uiFont, "Frame time", 692.0, 648.0, 240.0, 44.0) {
                    frameTimeLabel.visible = !frameTimeLabel.visible
                    diagnostics.selected = frameTimeLabel.visible
                }
                diagnostics.selected = frameTimeLabel.visible
            }
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
        selectedTile = null
        if (::mapView.isInitialized) mapView.clearSelection()
        settingsPanel.updateBiomeSummary("Rebuilding world…")
        updateSelectionInfo()
        updateSimulationStatus()
    }

    private fun selectTileAt(screenX: Double, screenY: Double) {
        if (presentation?.progress?.generation != requestedGeneration || !this@AtlasScene::mapView.isInitialized) return
        val (x, y) = camera.tileAt(screenX, screenY) ?: return
        selectedTile = x to y
        showSidebar(true)
        mapView.selectTile(x, y)
        updateSelectionInfo()
    }

    private fun updateSelectionInfo() {
        tileInspector.update(presentation, requestedGeneration, selectedTile)
    }

    private fun toggleLayer(layer: ClimateLayer) {
        if (layer in activeOverlays) activeOverlays.remove(layer) else {
            // Independent scalar tints cannot share a meaningful colour legend.
            if (layer == ClimateLayer.PLATES || weatherLegend(layer) != null)
                activeOverlays.removeAll { it == ClimateLayer.PLATES || weatherLegend(it) != null }
            activeOverlays.add(layer)
        }
        refreshLayerButtons()
        refreshLegend()
        refreshOverlays()
        updateSelectionInfo()
    }

    private fun refreshOverlays() {
        if (presentation != null && ::mapView.isInitialized) mapView.updateOverlays(weather, activeOverlays)
    }

    private fun refreshSurfaceWater() {
        if (presentation != null && this@AtlasScene::mapView.isInitialized) mapView.updateSurfaceWater(presentation!!.surfaceWater)
    }

    private fun SContainer.drawControls() {
        container {
            headerBackground = atlasCard(1400.0, 84.0)
            atlasText(uiFont, "TERRA", 16.0, 10.0, 30.0).color = AtlasUi.accent
            atlasText(uiFont, "PLANET EXPLORER", 17.0, 50.0, 14.0, muted = true)
        }.xy(20.0, 20.0)
        headerActions = container {
            pauseButton = AtlasButton(this, uiFont, "Pause", 700.0, 38.0, 112.0) { togglePause() }
            pauseLabel = pauseButton.caption
            speedLabel = AtlasButton(this, uiFont, simulationSpeed.label + " ▾", 824.0, 38.0, 184.0) {
                speedMenu.visible = !speedMenu.visible
                if (speedMenu.visible) {
                    refreshSpeedButtons(); headerActions.parent?.sendChildToFront(headerActions)
                    speedMenu.parent?.sendChildToFront(speedMenu)
                }
            }.caption
            AtlasButton(this, uiFont, "Home", 1020.0, 38.0, 96.0) { camera.home(); applyMapTransform() }
            AtlasButton(this, uiFont, "Settings", 1128.0, 38.0, 144.0) { settingsPanel.toggle() }
            AtlasButton(this, uiFont, "Help", 1284.0, 38.0, 112.0) { toggleHelp() }
            speedMenu = container { visible = false }.xy(824.0, 90.0)
            speedMenu.atlasCard(232.0, 52.0 + SimulationSpeed.entries.size * 48)
            speedMenu.atlasText(uiFont, "CALENDAR SPEED", 16.0, 16.0, 16.0, muted = true)
            SimulationSpeed.entries.forEachIndexed { index, speed ->
                speedChoices[speed] = AtlasButton(speedMenu, uiFont, speed.label, 8.0, 44 + index * 48.0, 216.0, 44.0) {
                    selectSpeed(speed); speedMenu.visible = false
                }
            }
        }
        layerPanel = container {
            sidebarBackground = atlasCard(400.0, 768.0)
            listOf("Map layers", "Tile details").forEachIndexed { index, name ->
                sidebarTabs += AtlasButton(this, uiFont, name, 16 + index * 184.0, 16.0, 176.0) { showSidebar(index == 1) }
            }
            layerContent = container {
                atlasText(uiFont, "Colour overlay", 20.0, 12.0, 18.0)
                AtlasButton(this, uiFont, "Clear all", 268.0, 0.0, 112.0, 40.0) {
                    activeOverlays.clear(); refreshLayerButtons(); refreshLegend(); refreshOverlays(); updateSelectionInfo()
                }
                listOf(ClimateLayer.HUMIDITY to "1  Humidity", ClimateLayer.PRECIPITATION to "3  Rain rate",
                    ClimateLayer.PRESSURE to "4  Pressure", ClimateLayer.TEMPERATURE to "5  Temperature",
                    ClimateLayer.PLATES to "6  Plate boundaries").forEachIndexed { index, (layer, label) ->
                    layerButtons[layer] = AtlasButton(this, uiFont, label, 20 + index % 2 * 184.0,
                        60 + index / 2 * 54.0, if (index == 4) 360.0 else 176.0) { toggleLayer(layer) }
                }
                atlasText(uiFont, "Additional layers", 20.0, 240.0, 17.0, muted = true)
                listOf(ClimateLayer.CLOUDS to "2 Clouds", ClimateLayer.WIND to "9 Wind", ClimateLayer.DAY_NIGHT to "8 Light")
                    .forEachIndexed { index, (layer, label) ->
                        layerButtons[layer] = AtlasButton(this, uiFont, label, 20 + index * 124.0, 276.0, 112.0) { toggleLayer(layer) }
                    }
                layerHints = atlasText(uiFont, "Keys 1–9 toggle layers", 20.0, 344.0, 16.0, muted = true)
            }.xy(0.0, 80.0)
            inspectorHost = container { visible = false }.xy(0.0, 80.0)
        }
        showSidebar(false)
        refreshLayerButtons(); refreshSpeedButtons()
    }

    private fun showSidebar(inspect: Boolean) {
        layerContent.visible = !inspect; inspectorHost.visible = inspect
        if (::legendPanel.isInitialized) legendPanel.visible = !inspect
        sidebarTabs.forEachIndexed { index, button -> button.selected = (index == 1) == inspect }
    }

    private fun updateGeologySettings(settings: GeologySettings) {
        geologySettings = settings
        simulation.updateGeology(settings)
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

}
