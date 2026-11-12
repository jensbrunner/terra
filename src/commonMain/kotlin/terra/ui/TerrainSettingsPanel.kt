package terra.ui

import korlibs.korge.input.mouse
import korlibs.korge.input.onMouseDrag
import korlibs.korge.ui.uiSlider
import korlibs.korge.view.*
import korlibs.korge.view.Container
import korlibs.math.geom.Size
import korlibs.image.color.Colors
import korlibs.image.font.SystemFont
import kotlin.math.round
import kotlin.math.pow
import terra.WINDOW_HEIGHT
import terra.WINDOW_WIDTH
import terra.world.TerrainGenerationSettings

private data class SettingSlider(
    val label: String,
    val value: Double,
    val minimum: Double,
    val maximum: Double,
    val step: Double,
    val decimals: Int,
    val update: (Double) -> Unit
)

private enum class SettingsMenuKind {
    ELEVATION,
    CLIMATE
}

/** Builds the draggable terrain settings panels and reports edits to its owner. */
class TerrainSettingsPanel(
    parent: Container,
    private val font: SystemFont,
    initialSettings: TerrainGenerationSettings,
    private val onSettingsChanged: (TerrainGenerationSettings) -> Unit
) {
    private var settings = initialSettings
    private var appliedSettings = initialSettings
    private val menus = mutableMapOf<SettingsMenuKind, Container>()
    private var biomeSummaryLabel: Text? = null
    private val pendingLabels = mutableListOf<Text>()
    private val applyButtons = mutableListOf<SolidRect>()

    init {
        drawMenuButtons(parent)
        drawSettingsMenus(parent)
    }

    fun hasOpenMenu(): Boolean = menus.values.any { it.visible }

    fun closeMenus() { menus.values.forEach { it.visible = false } }

    fun updateBiomeSummary(summary: String) {
        biomeSummaryLabel?.text = summary
    }

    private fun drawMenuButtons(parent: Container) {
        val y = 78.0
        val width = 110.0
        val gap = 8.0
        var x = WINDOW_WIDTH - (width * 2.0 + gap) - 18.0
        listOf(
            SettingsMenuKind.ELEVATION to "Elevation",
            SettingsMenuKind.CLIMATE to "Climate"
        ).forEach { (kind, label) ->
            val background = parent.solidRect(width, 38.0, Colors["#223538"]).xy(x, y)
            val caption = parent.text(label, font = font, textSize = 15.0, color = Colors["#e3e8d7"])
                .xy(x + 10.0, y + 10.0)
            background.mouse { click { toggleMenu(kind) } }
            caption.mouse { click { toggleMenu(kind) } }
            x += width + gap
        }
    }

    private fun drawSettingsMenus(parent: Container) {
        createSettingsMenu(
            parent, SettingsMenuKind.ELEVATION, "Elevation settings", 24.0, 108.0, 430.0, 842.0,
            "Continents, ranges and erosion", elevationSliders(), showBiomeSummary = true
        )
        createSettingsMenu(
            parent, SettingsMenuKind.CLIMATE, "Climate settings", 986.0, 138.0, 430.0, 310.0,
            "Geographic temperature baseline", climateSliders()
        )
    }

    private fun elevationSliders() = listOf(
        slider("Base elevation", { it.baseElevation }, 0.0, 0.8, 0.01, 2) { s, v -> s.copy(baseElevation = v) },
        slider("Sea level", { it.seaLevel }, 0.05, 0.7, 0.005, 3) { s, v -> s.copy(seaLevel = v) },
        slider("Mountain cutoff", { it.mountainThreshold }, 0.2, 0.8, 0.005, 3) { s, v -> s.copy(mountainThreshold = v) },
        slider("Continent scale", { it.continentScale }, 10.0, 200.0, 1.0, 0) { s, v -> s.copy(continentScale = v) },
        slider("Continent octaves", { it.continentOctaves.toDouble() }, 1.0, 8.0, 1.0, 0) { s, v -> s.copy(continentOctaves = v.toInt()) },
        slider("Continent strength", { it.continentStrength }, 0.0, 1.0, 0.01, 2) { s, v -> s.copy(continentStrength = v) },
        slider("Regional scale", { it.regionalScale }, 2.0, 100.0, 1.0, 0) { s, v -> s.copy(regionalScale = v) },
        slider("Regional octaves", { it.regionalOctaves.toDouble() }, 1.0, 8.0, 1.0, 0) { s, v -> s.copy(regionalOctaves = v.toInt()) },
        slider("Regional strength", { it.regionalStrength }, 0.0, 0.5, 0.01, 2) { s, v -> s.copy(regionalStrength = v) },
        slider("Detail scale", { it.detailScale }, 1.0, 50.0, 1.0, 0) { s, v -> s.copy(detailScale = v) },
        slider("Detail octaves", { it.detailOctaves.toDouble() }, 1.0, 8.0, 1.0, 0) { s, v -> s.copy(detailOctaves = v.toInt()) },
        slider("Detail strength", { it.detailStrength }, 0.0, 0.4, 0.01, 2) { s, v -> s.copy(detailStrength = v) },
        slider("FBM persistence", { it.noisePersistence }, 0.1, 0.9, 0.01, 2) { s, v -> s.copy(noisePersistence = v) },
        slider("FBM lacunarity", { it.noiseLacunarity }, 1.1, 4.0, 0.05, 2) { s, v -> s.copy(noiseLacunarity = v) },
        slider("Mountain relief", { it.mountainRelief }, 0.0, 0.6, 0.01, 2) { s, v -> s.copy(mountainRelief = v) },
        slider("Erosion strength", { it.erosionStrength }, 0.0, 1.0, 0.05, 2) { s, v -> s.copy(erosionStrength = v) }
    )

    private fun climateSliders() = listOf(
        slider("Equator temperature (°C)", { it.equatorTemperatureC }, -10.0, 45.0, 0.5, 1) { s, v -> s.copy(equatorTemperatureC = v) },
        slider("Pole temperature drop (°C)", { it.poleTemperatureDropC }, 0.0, 65.0, 0.5, 1) { s, v -> s.copy(poleTemperatureDropC = v) },
        slider("Elevation cooling (°C/unit)", { it.elevationCoolingCOPerUnit }, 0.0, 80.0, 0.5, 1) { s, v -> s.copy(elevationCoolingCOPerUnit = v) }
    )

    private fun slider(
        label: String,
        value: (TerrainGenerationSettings) -> Double,
        minimum: Double,
        maximum: Double,
        step: Double,
        decimals: Int,
        update: (TerrainGenerationSettings, Double) -> TerrainGenerationSettings
    ) = SettingSlider(label, value(settings), minimum, maximum, step, decimals) { newValue ->
        settings = update(settings, newValue)
        refreshPendingChanges()
    }

    private fun createSettingsMenu(
        parent: Container,
        kind: SettingsMenuKind,
        title: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        sectionTitle: String,
        sliders: List<SettingSlider>,
        showBiomeSummary: Boolean = false
    ) {
        lateinit var panel: Container
        lateinit var titleBar: SolidRect
        lateinit var titleLabel: Text
        var dragStartX = 0.0
        var dragStartY = 0.0

        panel = parent.container {
            visible = false
            solidRect(width, height, Colors["#101b20"])
            titleBar = solidRect(width, 44.0, Colors["#1c2a2e"])
            titleLabel = text(title, font = font, textSize = 18.0, color = Colors["#e3e8d7"])
                .xy(14.0, 11.0)
            settingsMenuButton("×", width - 44.0, 5.0, 32.0) { toggleMenu(kind) }

            val sectionY = if (showBiomeSummary) 74.0 else 58.0
            if (showBiomeSummary) {
                biomeSummaryLabel = text(
                    "", font = font, textSize = 13.0, color = Colors["#e5bd72"]
                ).xy(16.0, 48.0)
            }
            settingsColumn(sectionTitle, 16.0, sectionY, width - 32.0, sliders)
            pendingLabels += text("Changes rebuild the world and restart time.", font = font,
                textSize = 12.0, color = Colors["#e5bd72"]).xy(16.0, height - 66.0)
            val apply = solidRect(width - 32.0, 34.0, Colors["#223538"]).xy(16.0, height - 48.0)
            applyButtons += apply
            val caption = text("Apply & rebuild world", font = font, textSize = 14.0,
                color = Colors["#e3e8d7"]).xy(26.0, height - 40.0)
            fun applyChanges() {
                if (settings == appliedSettings) return
                appliedSettings = settings
                onSettingsChanged(settings)
                refreshPendingChanges()
            }
            apply.mouse { click { applyChanges() } }
            caption.mouse { click { applyChanges() } }
        }.xy(x, y)

        val dragHandler: (Double, Double) -> Unit = { dx, dy ->
            panel.xy(
                (dragStartX + dx).coerceIn(0.0, WINDOW_WIDTH - width),
                (dragStartY + dy).coerceIn(0.0, WINDOW_HEIGHT - height)
            )
        }
        titleBar.onMouseDrag { drag ->
            if (drag.start) {
                dragStartX = panel.x
                dragStartY = panel.y
            }
            dragHandler(drag.dx, drag.dy)
        }
        titleLabel.onMouseDrag { drag ->
            if (drag.start) {
                dragStartX = panel.x
                dragStartY = panel.y
            }
            dragHandler(drag.dx, drag.dy)
        }

        menus[kind] = panel
    }

    private fun refreshPendingChanges() {
        val pending = settings != appliedSettings
        pendingLabels.forEach { it.text = if (pending) "Unapplied changes · rebuild restarts time" else "Changes rebuild the world and restart time." }
        applyButtons.forEach { it.color = Colors[if (pending) "#49776b" else "#223538"] }
    }

    private fun Container.settingsColumn(
        title: String,
        x: Double,
        y: Double,
        width: Double,
        sliders: List<SettingSlider>
    ) {
        text(title, font = font, textSize = 15.0, color = Colors["#e5bd72"]).xy(x, y)
        val rowHeight = 41.0
        sliders.forEachIndexed { index, setting ->
            val rowY = y + 25.0 + index * rowHeight
            val valueLabel = text(
                "${setting.label}: ${formatNumber(setting.value, setting.decimals)}",
                font = font,
                textSize = 13.0,
                color = Colors["#e3e8d7"]
            ).xy(x, rowY)
            val slider = uiSlider(
                value = setting.value,
                min = setting.minimum,
                max = setting.maximum,
                step = setting.step,
                decimalPlaces = setting.decimals,
                size = Size(width, 15.0)
            ) {
                showTooltip = true
            }.xy(x, rowY + 17.0)
            slider.onChange.add { newValue ->
                valueLabel.text = "${setting.label}: ${formatNumber(newValue, setting.decimals)}"
                setting.update(newValue)
            }
        }
    }

    private fun Container.settingsMenuButton(
        label: String,
        x: Double,
        y: Double,
        width: Double,
        action: () -> Unit
    ) {
        val background = solidRect(width, 34.0, Colors["#223538"]).xy(x, y)
        val caption = text(label, font = font, textSize = 14.0, color = Colors["#e3e8d7"])
            .xy(x + 10.0, y + 8.0)
        background.mouse { click { action() } }
        caption.mouse { click { action() } }
    }

    private fun toggleMenu(kind: SettingsMenuKind) {
        val menu = menus[kind] ?: return
        val shouldOpen = !menu.visible
        menus.values.forEach { it.visible = false }
        menu.visible = shouldOpen
        if (menu.visible) menu.parent?.sendChildToFront(menu)
    }
}

internal fun formatNumber(value: Double, decimalPlaces: Int): String {
    if (decimalPlaces == 0) return value.toInt().toString()
    val scale = 10.0.pow(decimalPlaces)
    return (round(value * scale) / scale).toString()
}
