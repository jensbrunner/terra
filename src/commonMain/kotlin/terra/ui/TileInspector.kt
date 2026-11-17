package terra.ui

import korlibs.image.font.SystemFont
import korlibs.korge.view.Container
import korlibs.korge.view.Text
import korlibs.korge.view.View
import korlibs.korge.view.container
import korlibs.korge.view.xy
import terra.climate.YEAR_SECONDS
import terra.simulation.WorldPresentationSnapshot

/** Presents live terrain, climate, and water information for the selected tile. */
internal class TileInspector(parent: Container, font: SystemFont) {
    private val panel: Container
    private lateinit var background: View
    private val header: Text
    private val context: Text
    private val selectionText: Text
    private val climateText: Text
    private val rows = mutableListOf<Pair<Text, Text>>()
    private val tabs = mutableListOf<AtlasButton>()
    private var detailed = false
    private var presentation: WorldPresentationSnapshot? = null
    private var requestedGeneration = -1L
    private var selectedTile: Pair<Int, Int>? = null

    init {
        lateinit var headerText: Text
        lateinit var contextText: Text
        lateinit var selectionLabel: Text
        lateinit var climateLabel: Text

        panel = parent.container {
            background = atlasCard(328.0, 450.0)
            atlasText(font, "TILE INSPECTOR", 16.0, 18.0, 12.0, muted = true)
            headerText = atlasText(font, "Explore the map", 16.0, 48.0, 23.0)
            contextText = atlasText(font, "Select a tile to see its conditions.", 16.0, 82.0, 12.0, muted = true)
            listOf("Overview", "Details").forEachIndexed { index, name ->
                tabs += AtlasButton(this, font, name, 16 + index * 152.0, 100.0, 144.0, 32.0) {
                    detailed = index == 1
                    refresh()
                }
            }
            selectionLabel = atlasText(font,
                "Click a tile to inspect it.\n\nDrag to pan\nScroll to zoom\nHome to fit the map",
                16.0, 152.0, 14.0, muted = true)
            repeat(8) { index ->
                val y = 152 + index * 26.0
                rows += atlasText(font, "", 16.0, y, 13.0, muted = true) to
                    atlasText(font, "", 166.0, y, 14.0)
            }
            climateLabel = atlasText(font, "", 16.0, 372.0, 12.0, muted = true)
            tabs.first().selected = true
        }.xy(1096.0, 458.0)

        header = headerText
        context = contextText
        selectionText = selectionLabel
        climateText = climateLabel
    }

    fun update(snapshot: WorldPresentationSnapshot?, generation: Long, tile: Pair<Int, Int>?) {
        presentation = snapshot
        requestedGeneration = generation
        selectedTile = tile
        refresh()
    }

    fun resize(width: Double, height: Double) {
        // The panel stays anchored below the layer controls and grows with the window.
        panel.x = width - 344
        background.height = height - 510
    }

    private fun refresh() {
        tabs.forEachIndexed { index, button -> button.selected = (index == 1) == detailed }
        val snapshot = presentation
        val ready = snapshot != null && snapshot.progress.generation == requestedGeneration
        val tile = selectedTile
        val selected = if (ready) tile else null

        selectionText.textSize = if (selected != null && detailed) 12.0 else 14.0
        rows.forEach { (label, value) ->
            label.visible = selected != null && !detailed
            value.visible = label.visible
        }
        climateText.visible = selected != null && !detailed
        selectionText.visible = selected == null || detailed

        if (selected == null || snapshot == null) {
            header.text = if (ready) "Explore the map" else "Building your world…"
            context.text = "Select a tile to see its conditions."
            selectionText.text = "Click a tile to inspect it.\n\nDrag to pan\nScroll to zoom\nHome to fit the map\n\nChoose a colour layer above\nto explore climate patterns."
            return
        }

        val (x, y) = selected
        val world = snapshot.world
        val weather = snapshot.weather
        val drainage = snapshot.drainage
        val cell = world[x, y]
        header.text = cell.terrain.label
        context.text = "Tile $x, $y · Climate & weather"

        val rowsForTile = listOf(
            "Temperature" to "${formatNumber(weather.temperatureC(x, y), 1)} °C",
            "Snow" to "${formatNumber(weather.snowWaterMm(x, y), 1)} mm",
            "Humidity" to "${formatNumber(weather.relativeHumidity(x, y) * 100, 0)}%",
            "Pressure" to "${formatNumber(weather.pressureHpa(x, y), 1)} hPa",
            "Rain rate" to "${formatNumber(weather.precipitationMmPerHour(x, y), 2)} mm/h",
            "Soil moisture" to "${formatNumber(weather.soilMoisture(x, y) * 100, 0)}%",
            "Elevation" to "${formatNumber((cell.elevation - world.seaLevel) * 10_000, 0)} m",
            "Lake" to when {
                drainage.isLakeTileWet(x, y) -> "Under water"
                drainage.lakeIdAt(x, y) >= 0 -> "Dry basin"
                else -> "None"
            }
        )
        rowsForTile.forEachIndexed { index, (label, value) ->
            rows[index].first.text = label
            rows[index].second.text = value
        }

        val ecology = "Canopy ${formatNumber(world.treeCover(x, y) * 100, 0)}% · " +
            "grass ${formatNumber(world.grassCover(x, y) * 100, 0)}% · " +
            "bare ${formatNumber(world.bareCover(x, y) * 100, 0)}%\n" +
            "Limit: ${world.growthLimitation(x, y).name.lowercase()}"
        climateText.text = "Climate rain · ${formatNumber(weather.recentRainMmPerYear(x, y), 0)} mm/year\n" +
            "$ecology\nAnnual climate drives terrain"

        if (detailed) selectionText.text = detailedText(snapshot, x, y)
    }

    private fun detailedText(snapshot: WorldPresentationSnapshot, x: Int, y: Int): String {
        val weather = snapshot.weather
        val drainage = snapshot.drainage
        return "Plate ${snapshot.world.plateId(x, y) + 1} · ${snapshot.world.plateBoundary(x, y).label}\n" +
            "Budget interval ${SimulationDisplayText.duration(weather.updateSeconds, YEAR_SECONDS)}\n" +
            "Annual rain   ${formatNumber(weather.rainMmThisUpdate(x, y), 3)} mm\n" +
            "Current rain rate   ${formatNumber(weather.precipitationMmPerHour(x, y), 3)} mm/h\n" +
            "Wind speed   ${formatNumber(weather.windSpeedMetersPerSecond(x, y), 1)} m/s\n" +
            "Climate rain   ${formatNumber(weather.recentRainMmPerYear(x, y), 0)} mm/year\n\n" +
            "Soil water   ${formatNumber(weather.soilWaterMm(x, y), 1)} mm\n" +
            "Drainage   ${formatNumber(weather.drainageMmThisUpdate(x, y), 2)} mm/year\n" +
            "Overflow   ${formatNumber(weather.overflowMmThisUpdate(x, y), 2)} mm/year\n\n" +
            "Upstream land   ${drainage.upstreamLandTileCount(x, y)} tiles\n" +
            "River flow   ${formatNumber(drainage.dischargeMmTilesPerDay(x, y), 2)} mm·tiles/day\n" +
            "Snow water   ${formatNumber(weather.snowWaterMm(x, y), 1)} mm"
    }
}
