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
    private val performanceProbe: PerformanceProbe? = null
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
    private val layerButtons = mutableMapOf<WeatherLayer, Pair<SolidRect, Text>>()
    private lateinit var speedLabel: Text
    private lateinit var atmosphereLabel: Text
    private lateinit var pauseLabel: Text
    private lateinit var selectionLabel: Text
    private lateinit var frameTimeLabel: Text
    private lateinit var helpPanel: Container
    private lateinit var legendPanel: Container
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
    private val camera = MapCamera(WINDOW_WIDTH, WINDOW_HEIGHT - 372.0, MAP_WIDTH_TILES, MAP_HEIGHT_TILES)
    private var dragStartPanX = 0.0
    private var dragStartPanY = 0.0
    private var draggedMap = false

    override suspend fun SContainer.sceneMain() {
        uiFont = SystemFont.getDefaultFont()
        solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, Colors["#000000"])
        mapViewport = fixedSizeContainer(Size(camera.viewportWidth, camera.viewportHeight), clip = true).xy(0.0, 138.0)
        mapLayer = mapViewport.container { }
        terrainTextures = loadTerrainTextures()

        drawControls()
        drawAtmosphereControls()
        drawSelectionPanel()
        legendPanel = container { }.xy(1188.0, WINDOW_HEIGHT - 220.0)
        refreshLegend()
        refreshOverlays()
        settingsPanel = TerrainSettingsPanel(this, uiFont, terrainSettings, ::updateTerrainSettings)
        settingsPanel.updateBiomeSummary(presentation?.world?.landBiomeSummary() ?: "Generating world…")
        drawFrameTimeCounter()
        drawHelp()
        applyMapTransform()

        mapViewport.keys {
            WeatherLayer.entries.forEach { layer ->
                justDown(Key.valueOf("N${layer.key}")) { if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) toggleLayer(layer) }
            }
            justDown(Key.M) { if (!helpPanel.visible && !settingsPanel.hasOpenMenu()) switchAtmosphere() }
            justDown(Key.F) { if (!helpPanel.visible) toggleFidelity() }
            justDown(Key.A) { if (!helpPanel.visible) { manualFidelity = false; simulation.useAutomaticFidelity() } }
            justDown(Key.SPACE) { togglePause() }
            justDown(Key.HOME) { camera.home(); applyMapTransform() }
            justDown(Key.H) { toggleHelp() }
            justDown(Key.ESCAPE) { helpPanel.visible = false; settingsPanel.closeMenus() }
            justDown(Key.LEFT) { if (!helpPanel.visible) cycleSpeed(-1) }
            justDown(Key.RIGHT) { if (!helpPanel.visible) cycleSpeed(1) }
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
                if (previous?.weather?.fieldSemantics?.quality != snapshot.weather.fieldSemantics.quality) {
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
        }

        mapViewport.onClick { event ->
            if (!draggedMap && !helpPanel.visible && !settingsPanel.hasOpenMenu()) selectTileAt(event.currentPosLocal.x, event.currentPosLocal.y)
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
        solidRect(1164.0, 54.0, Colors["#101b20"]).xy(12.0, 76.0)
        atmosphereLabel = text("", font = uiFont, textSize = 14.0, color = Colors["#e3e8d7"]).xy(22.0, 84.0)
        updateAtmosphereStatus()
    }

    private fun updateAtmosphereStatus() {
        val snapshot = presentation
        atmosphereLabel.text = when {
            simulation.failure != null -> "Simulation stopped: ${simulation.failure} · change terrain/model to regenerate"
            snapshot == null || snapshot.progress.generation != requestedGeneration -> "Generating world…"
            !snapshot.climateReady -> snapshot.initializationStatus
            else -> {
                val progress = snapshot.progress
                val year = snapshot.yearSeconds
                val period = snapshot.weather.fieldSemantics
                val representative = period.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL
                val state = if (paused && !progress.paused) "pausing" else if (progress.paused) "paused" else if (progress.rateLimited) "rate limited" else "running"
                val mode = if (!snapshot.dynamical) "Prescribed" else if (snapshot.detailedReentryPending) "Detailed pending" else if (snapshot.fidelity == SimulationFidelity.CLIMATE && !representative) "Climate pending" else if (representative) "Approximate climate" else "Detailed weather"
                val guides = buildList {
                    add(WeatherDisplayText.quality(period))
                    if (snapshot.dynamical) add("${if (progress.automaticFidelity) "auto" else "manual"} · step ${WeatherDisplayText.duration(progress.quantumSeconds, year)}")
                    if (WeatherLayer.CLOUDS in activeOverlays && representative) add("cloud drift: illustrative")
                    if (WeatherLayer.DAY_NIGHT in activeOverlays) add(if (representative) "light: day-averaged seasonal" else "light: current date")
                    if (WeatherLayer.WIND in activeOverlays && representative) add("wind: unresolved")
                    if (progress.pendingSeconds > 0) add("queued ${WeatherDisplayText.duration(progress.pendingSeconds, year)}")
                    if (progress.coarsenedForLoad) add("coarser for load")
                }.joinToString(" · ")
                "$mode · ${WeatherDisplayText.date(progress.completedSeconds, year)} · " +
                    "${simulationSpeed.label} requested / ${WeatherDisplayText.rate(progress.achievedSecondsPerWallSecond, year)} actual · " +
                    "$state\n$guides"

            }
        }
    }

    private suspend fun loadTerrainTextures(): Map<Terrain, Bitmap32> = Terrain.entries.associateWith { terrain ->
        views.currentVfs["art/terrain/64x64/${terrain.name.lowercase()}.png"].readBitmap().toBMP32IfRequired()
    }

    private fun SContainer.drawSelectionPanel() {
        val panelHeight = 220.0
        solidRect(1164.0, panelHeight, Colors["#101b20"]).xy(12.0, WINDOW_HEIGHT - panelHeight - 12.0)
        selectionLabel = text(
            "Click a tile to inspect it", font = uiFont, textSize = 15.0, color = Colors["#e3e8d7"]
        ).xy(22.0, WINDOW_HEIGHT - panelHeight + 3.0)
    }

    private fun SContainer.drawFrameTimeCounter() {
        frameTimeLabel = text(
            "Frame 16.7 ms · 60 FPS", font = uiFont, textSize = 12.0, color = Colors["#ffffff"]
        ).xy(WINDOW_WIDTH - 190.0, WINDOW_HEIGHT - 24.0)
    }

    private fun refreshLegend() {
        if (!::legendPanel.isInitialized) return
        legendPanel.removeChildren()
        legendPanel.solidRect(238.0, 180.0, Colors["#101b20"])
        val layer = activeOverlays.firstOrNull { weatherLegend(it) != null }
        val legend = layer?.let(::weatherLegend)
        fun line(value: String, y: Double, size: Double = 12.0) =
            legendPanel.text(value, font = uiFont, textSize = size, color = Colors["#e3e8d7"]).xy(12.0, y)
        if (legend != null) {
            line(legend.title, 12.0, 13.0)
            for (i in 0..99) {
                val color = legend.colors[(i / 99.0 * (legend.colors.size - 1)).toInt()]
                legendPanel.solidRect(2.1, 18.0, Colors[color]).xy(12 + i * 2.1, 38.0)
            }
            line(legend.low, 62.0); line(legend.middle, 62.0).x = 97.0
            line(legend.high, 62.0).x = 182.0
            line("One colour layer at a time.\nClouds and lighting can tint it.", 88.0, 11.0)
            val representative = presentation?.weather?.fieldSemantics?.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL
            line(if (representative) "Interval estimate\nCloud movement is illustrative." else "Current weather\nClick a tile for exact values.", 133.0, 11.0)
        } else {
            line("Map layers · keys 1–9", 12.0, 14.0)
            line("${if (WeatherLayer.CLOUDS in activeOverlays) "Clouds on" else "Clouds off"} · 2 to toggle\n\nColour layers show weather ranges.\nWind arrows point with the flow.\n8 toggles lighting / seasonal shade.\n\nH opens all controls.", 40.0, 11.0)
        }
    }

    private fun SContainer.drawHelp() {
        helpPanel = container {
            visible = false
            solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, Colors["#000000"].withA(180))
            container {
                solidRect(760.0, 510.0, Colors["#101b20"])
                text("Terra · explore the simulation", font = uiFont, textSize = 24.0,
                    color = Colors["#e5bd72"]).xy(24.0, 22.0)
                text("Navigate\n\nDrag to pan · scroll to zoom\nHome fits the map\nClick a tile to inspect it\n\nSpace pauses / resumes\nLeft / Right change speed\n1–9 toggle map layers\nH opens help · Esc closes panels",
                    font = uiFont, textSize = 16.0, color = Colors["#e3e8d7"]).xy(24.0, 78.0)
                text("Weather and climate\n\nF selects detailed / fast climate\nA restores automatic selection\n\nDetailed: physical winds and clouds\nFast: interval climate estimates\nFast cloud movement is illustrative\n\nM switches model and resets the world",
                    font = uiFont, textSize = 16.0, color = Colors["#e3e8d7"]).xy(382.0, 78.0)
                text("Elevation / Climate settings are drafts until Apply & rebuild.\nApplying settings or switching model restarts the world and its clock.",
                    font = uiFont, textSize = 14.0, color = Colors["#e5bd72"]).xy(24.0, 366.0)
                fun button(label: String, x: Double, width: Double, action: () -> Unit) {
                    val background = solidRect(width, 38.0, Colors["#223538"]).xy(x, 448.0)
                    val caption = text(label, font = uiFont, textSize = 15.0, color = Colors["#e3e8d7"]).xy(x + 12, 459.0)
                    background.mouse { click { action() } }; caption.mouse { click { action() } }
                }
                button("Switch weather model & rebuild", 24.0, 320.0) {
                    helpPanel.visible = false
                    settingsPanel.closeMenus()
                    switchAtmosphere()
                }
                button("Close · Esc", 600.0, 136.0) { helpPanel.visible = false }
            }.xy((WINDOW_WIDTH - 760) / 2, (WINDOW_HEIGHT - 510) / 2)
        }
    }

    private fun toggleHelp() {
        if (helpPanel.visible) helpPanel.visible = false else showHelp()
    }

    private fun showHelp() {
        settingsPanel.closeMenus()
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
        if (!climateReady) {
            selectionLabel.text = presentation?.initializationStatus ?: "Generating world…"
            return
        }
        val x = selectedTileX ?: run {
            selectionLabel.text = "Explore your world\n\nClick a tile for weather, soil, rivers and climate.\nDrag to pan · scroll to zoom · Home to fit the map.\n\nH opens controls and model help."
            return
        }
        val y = selectedTileY ?: return
        val cell = world[x, y]
        val representative = weather.fieldSemantics.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL
        val period = weather.fieldSemantics
        val year = presentation!!.yearSeconds
        val representedPeriod = WeatherDisplayText.period(period, year)
        val peakKind = if (representative) "inferred wet-phase mean" else "physical-step peak"
        val cloudKind = if (representative) "Representative cloud proxy" else "Cloud water"
        val events = weather.rainEventSummary(x, y)
        val eventInfo = events?.let { "Wet ${(it.wetFraction * 100).toInt()}% · rain above 2 mm/h ${formatNumber(it.intenseRainMm, 2)} mm · " } ?: ""
        val flowPeriod = if (hydrology.routedPeriodDays > 0) " over ${WeatherDisplayText.duration(hydrology.routedPeriodDays * 86400, year)}" else " (not yet routed)"
        val meanRain = formatNumber(weather.precipitationMmPerHour(x, y), 3)
        val peakRain = formatNumber(weather.peakSubstepPrecipitationMmPerHour(x, y), 3)
        val rainThisUpdate = formatNumber(weather.rainMmThisUpdate(x, y), 3)
        val averageRain = formatNumber(weather.thirtyDayRainMmPerHour(x, y), 3)
        val annualizedRain = formatNumber(weather.annualizedRainMmPerYear(x, y), 1)
        val climate = climateSnapshot
        val observedRain = climate?.let { formatNumber(it.precipitationTotalMm(x, y), 1) } ?: "0"
        val observedDays = climate?.let { formatNumber(it.sampledDays, 2) } ?: "0"
        val seasonal = presentation?.seasonalClimate
        val climateInfo = seasonal?.let {
            "${if (it.containsAggregateEstimates) "Estimated annual" else "Annual"} window ${it.windowYears}y · surface proxy ${formatNumber(it.meanSurfaceTemperatureAtTile(x, y, world.width, world.height), 1)}°C · " +
                "${formatNumber(it.annualPrecipitationAtTile(x, y, world.width, world.height), 0)} mm/year"
        } ?: "Annual climate pending; land cover provisional"
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
        selectionLabel.text = "Tile ($x, $y) · ${cell.terrain.label} · elevation ${(cell.elevation * 100).toInt()}%\n" +
            "${WeatherDisplayText.date(presentation!!.progress.completedSeconds, year)} · ${WeatherDisplayText.quality(period)} · data period $representedPeriod\n" +
            "${if (representative) "Air proxy" else "Air"} ${formatNumber(weather.airTemperatureC(x, y), 1)}°C · " +
            "surface ${formatNumber(weather.surfaceTemperatureC(x, y), 1)}°C · " +
            "${if (representative) "humidity proxy" else "humidity"} ${(weather.relativeHumidity(x, y) * 100).toInt()}% · " +
            "$cloudKind ${formatNumber(weather.cloudWaterMm(x, y), 2)} mm\n" +
            (if (presentation?.dynamical == true) "$climateInfo\n" else
                "Rain estimate $annualizedRain mm/year (EMA) · observed $observedDays-day total $observedRain mm\n") +
            "Rain $rainThisUpdate mm over $representedPeriod · mean $meanRain mm/h · $peakKind $peakRain mm/h\n" +
            "$eventInfo" + "30-day EMA $averageRain mm/h\n" +
            "${if (representative) "Effective atmospheric water" else "Air water"} ${formatNumber(weather.atmosphericWaterMm(x, y), 1)} mm · " +
            "soil ${formatNumber(weather.soilWaterMm(x, y), 0)} mm (${(weather.soilMoisture(x, y) * 100).toInt()}%)\n" +
            "Mean flow $discharge mm·tiles/day$flowPeriod · upstream area $upstreamArea tiles\n" +
            "Soil drainage $soilDrainage mm · overflow $soilOverflow mm over $representedPeriod\n" +
            "Lake $lakeInfo · $lakeWetStatus · water view ${WeatherDisplayText.date(presentation!!.hydrologyCompletedSeconds, year)}"

    }

    private fun toggleLayer(layer: WeatherLayer) {
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
        controlButton("-", x, 30.0) { cycleSpeed(-1) }
        speedLabel = controlButton(simulationSpeed.label, x + 35, 142.0) { cycleSpeed(1) }.second
        controlButton("+", x + 182, 30.0) { cycleSpeed(1) }
        controlButton("Home", 1150.0, 76.0) { camera.home(); applyMapTransform() }
        controlButton("Help H", 1234.0, 82.0) { toggleHelp() }
        controlButton("Model…", 1324.0, 96.0) { showHelp() }
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
            if (layer == WeatherLayer.DAY_NIGHT) button.second.text = if (presentation?.weather?.fieldSemantics?.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL) "Season" else "Day/night"
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
        if (::speedLabel.isInitialized) speedLabel.text = simulationSpeed.label
    }

    private fun togglePause() {
        paused = !paused
        simulation.updateControls(simulationSpeed, paused)
        pauseLabel.text = if (paused) "Play" else "Pause"
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
}
