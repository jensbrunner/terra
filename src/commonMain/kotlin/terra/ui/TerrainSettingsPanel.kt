package terra.ui

import korlibs.korge.ui.uiSlider
import korlibs.korge.view.*
import korlibs.math.geom.Size
import korlibs.image.font.SystemFont
import kotlin.math.round
import kotlin.math.pow
import terra.WINDOW_HEIGHT
import terra.WINDOW_WIDTH
import terra.world.TerrainGenerationSettings

private data class SettingSlider(
    val label: String, val value: Double, val minimum: Double, val maximum: Double,
    val step: Double, val decimals: Int, val update: (Double) -> Unit
)

/** One settings dialog with shared drafts, grouped controls and an explicit rebuild action. */
class TerrainSettingsPanel(
    parent: Container, private val font: SystemFont, initialSettings: TerrainGenerationSettings,
    private val onSettingsChanged: (TerrainGenerationSettings) -> Unit,
    private val onOpen: () -> Unit = {}
) {
    private val draft = TerrainSettingsDraft(initialSettings)
    private var settings: TerrainGenerationSettings
        get() = draft.current
        set(value) { draft.current = value }
    private var tab = 0
    private val tabs = mutableListOf<AtlasButton>()
    private val overlay = parent.container { visible = false }
    private val panel: Container
    private val content: Container
    private val pending: Text
    private val summary: Text
    private val apply: AtlasButton
    private val discard: AtlasButton

    init {
        overlay.solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, AtlasUi.background.withA(225))
        panel = overlay.container { }.xy(300.0, 120.0)
        panel.atlasCard(840.0, 720.0)
        panel.atlasText(font, "World settings", 28.0, 24.0, 25.0)
        panel.atlasText(font, "Edit a draft, then rebuild when you’re ready.", 28.0, 60.0, muted = true)
        AtlasButton(panel, font, "Close", 732.0, 24.0, 80.0) { closeMenus() }
        listOf("Landforms", "Climate", "Advanced").forEachIndexed { index, name ->
            tabs += AtlasButton(panel, font, name, 28 + index * 156.0, 104.0, 146.0) { tab = index; renderTab() }
        }
        content = panel.container { }.xy(28.0, 166.0)
        panel.solidRect(784.0, 1.0, AtlasUi.line).xy(28.0, 546.0)
        summary = panel.atlasText(font, "Generating world…", 28.0, 562.0, 12.0, muted = true)
        pending = panel.atlasText(font, "", 28.0, 620.0, 13.0)
        AtlasButton(panel, font, "Defaults", 28.0, 660.0, 100.0) {
            settings = TerrainGenerationSettings(); renderTab(); refreshPendingChanges()
        }
        discard = AtlasButton(panel, font, "Discard edits", 140.0, 660.0, 140.0) {
            draft.discard(); renderTab(); refreshPendingChanges()
        }
        apply = AtlasButton(panel, font, "Apply & rebuild world", 572.0, 656.0, 240.0, 40.0) {
            draft.commit()?.let(onSettingsChanged)
            refreshPendingChanges()
            closeMenus()
        }
        renderTab()
        refreshPendingChanges()
    }

    fun hasOpenMenu(): Boolean = overlay.visible
    fun closeMenus() { overlay.visible = false }
    fun toggle() {
        if (overlay.visible) closeMenus() else {
            onOpen()
            overlay.parent?.sendChildToFront(overlay)
            overlay.visible = true
        }
    }
    fun updateBiomeSummary(value: String) { summary.text = value }

    internal fun prepareReview(index: Int, edited: Boolean = false) {
        tab = index
        if (edited) settings = settings.copy(erosionStrength = 0.65)
        renderTab(); refreshPendingChanges()
        if (!overlay.visible) toggle()
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
        slider("Detail persistence", { it.noisePersistence }, 0.1, 0.9, 0.01, 2) { s, v -> s.copy(noisePersistence = v) },
        slider("Detail frequency step", { it.noiseLacunarity }, 1.1, 4.0, 0.05, 2) { s, v -> s.copy(noiseLacunarity = v) },
        slider("Mountain relief", { it.mountainRelief }, 0.0, 0.6, 0.01, 2) { s, v -> s.copy(mountainRelief = v) },
        slider("Erosion strength", { it.erosionStrength }, 0.0, 1.0, 0.05, 2) { s, v -> s.copy(erosionStrength = v) }
    )

    private fun climateSliders() = listOf(
        slider("Equator temperature (°C)", { it.equatorTemperatureC }, -10.0, 45.0, 0.5, 1) { s, v -> s.copy(equatorTemperatureC = v) },
        slider("Pole temperature drop (°C)", { it.poleTemperatureDropC }, 0.0, 65.0, 0.5, 1) { s, v -> s.copy(poleTemperatureDropC = v) },
        slider("Elevation cooling (°C/unit)", { it.elevationCoolingCOPerUnit }, 0.0, 80.0, 0.5, 1) { s, v -> s.copy(elevationCoolingCOPerUnit = v) }
    )

    private fun slider(label: String, value: (TerrainGenerationSettings) -> Double,
        minimum: Double, maximum: Double, step: Double, decimals: Int,
        update: (TerrainGenerationSettings, Double) -> TerrainGenerationSettings
    ) = SettingSlider(label, value(settings), minimum, maximum, step, decimals) { next ->
        settings = update(settings, next); refreshPendingChanges()
    }

    private fun renderTab() {
        tabs.forEachIndexed { index, button -> button.selected = index == tab }
        content.removeChildren()
        val all = elevationSliders()
        val basic = setOf("Base elevation", "Sea level", "Mountain cutoff", "Continent scale",
            "Continent strength", "Regional strength", "Mountain relief", "Erosion strength")
        val selected = when (tab) {
            0 -> all.filter { it.label in basic }
            1 -> climateSliders()
            else -> all.filter { it.label !in basic }
        }
        selected.forEachIndexed { index, setting ->
            val x = (index % 2) * 408.0
            val y = (index / 2) * 86.0
            content.atlasText(font, setting.label, x, y, 14.0)
            val value = content.atlasText(font, formatNumber(setting.value, setting.decimals), x + 300, y, 14.0)
            val control = content.uiSlider(value = setting.value, min = setting.minimum,
                max = setting.maximum, step = setting.step, decimalPlaces = setting.decimals,
                size = Size(376.0, 24.0)) { showTooltip = null }.xy(x, y + 28)
            control.onChange.add { next ->
                value.text = formatNumber(next, setting.decimals); setting.update(next)
            }
        }
        if (tab == 1) content.atlasText(font,
            "Sets the geographic temperature baseline.\nWeather and seasons evolve from this starting geography.",
            0.0, 220.0, 14.0, muted = true)
    }

    private fun refreshPendingChanges() {
        pending.text = if (draft.dirty) "Unapplied changes · rebuilding restarts the world and its clock."
            else "No changes · close this panel to keep exploring."
        pending.color = if (draft.dirty) AtlasUi.warm else AtlasUi.muted
        apply.enabled = draft.dirty; apply.selected = draft.dirty
        discard.enabled = draft.dirty
    }
}

internal fun formatNumber(value: Double, decimalPlaces: Int): String {
    if (!value.isFinite()) return "—"
    if (decimalPlaces == 0) return round(value).toLong().toString()
    val scale = 10.0.pow(decimalPlaces)
    val rounded = round(value * scale) / scale
    return (if (rounded == 0.0) 0.0 else rounded).toString()
}
