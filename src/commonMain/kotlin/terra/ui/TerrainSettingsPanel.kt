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
import terra.simulation.GeologySettings

private data class SettingSlider(
    val label: String, val value: Double, val minimum: Double, val maximum: Double,
    val step: Double, val decimals: Int, val update: (Double) -> Unit
)

/** One settings dialog with shared drafts, grouped controls and an explicit rebuild action. */
class TerrainSettingsPanel(
    parent: Container, private val font: SystemFont, initialSettings: TerrainGenerationSettings,
    private val onSettingsChanged: (TerrainGenerationSettings) -> Unit,
    initialGeology: GeologySettings,
    private val onGeologyChanged: (GeologySettings) -> Unit,
    private val onOpen: () -> Unit = {}
) {
    private var settings = initialSettings
    private var geology = initialGeology
    private var applied = initialSettings
    private val dirty get() = settings != applied
    private var tab = 0
    private val tabs = mutableListOf<AtlasButton>()
    private val overlay = parent.container { visible = false }
    private val shade: View
    private val panel: Container
    private val content: Container
    private val pending: Text
    private val summary: Text
    private val apply: AtlasButton
    private val discard: AtlasButton
    private val defaults: AtlasButton

    init {
        shade = overlay.solidRect(WINDOW_WIDTH, WINDOW_HEIGHT, AtlasUi.background.withA(225))
        panel = overlay.container { }.xy(300.0, 120.0)
        panel.atlasCard(960.0, 744.0)
        panel.atlasText(font, "World & simulation settings", 28.0, 24.0, 30.0)
        panel.atlasText(font, "Simulation changes apply live. Landform edits rebuild the world.", 28.0, 68.0, 17.0, muted = true)
        AtlasButton(panel, font, "Close", 836.0, 24.0, 96.0) { closeMenus() }
        listOf("Landforms", "Advanced", "Simulation").forEachIndexed { index, name ->
            tabs += AtlasButton(panel, font, name, 28 + index * 188.0, 112.0, 176.0) { tab = index; renderTab() }
        }
        content = panel.container { }.xy(28.0, 180.0)
        panel.solidRect(904.0, 1.0, AtlasUi.line).xy(28.0, 540.0)
        summary = panel.atlasText(font, "Generating world…", 28.0, 560.0, 16.0, muted = true)
        pending = panel.atlasText(font, "", 28.0, 628.0, 17.0)
        defaults = AtlasButton(panel, font, "Defaults", 28.0, 680.0, 180.0, 44.0) {
            if (tab == 2) { geology = GeologySettings(); onGeologyChanged(geology) }
            else settings = TerrainGenerationSettings()
            renderTab()
        }
        discard = AtlasButton(panel, font, "Discard edits", 220.0, 680.0, 164.0) {
            settings = applied; renderTab(); refreshPendingChanges()
        }
        apply = AtlasButton(panel, font, "Apply & rebuild world", 676.0, 676.0, 256.0, 48.0) {
            applied = settings
            onSettingsChanged(settings)
            refreshPendingChanges()
            closeMenus()
        }
        renderTab()
        refreshPendingChanges()
    }

    fun resize(width: Double, height: Double) {
        shade.width = width; shade.height = height
        panel.xy((width - 960) / 2, (height - 744) / 2)
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

    private fun slider(label: String, value: (TerrainGenerationSettings) -> Double,
        minimum: Double, maximum: Double, step: Double, decimals: Int,
        update: (TerrainGenerationSettings, Double) -> TerrainGenerationSettings
    ) = SettingSlider(label, value(settings), minimum, maximum, step, decimals) { next ->
        settings = update(settings, next); refreshPendingChanges()
    }

    private fun renderTab() {
        tabs.forEachIndexed { index, button -> button.selected = index == tab }
        content.removeChildren()
        refreshPendingChanges()
        if (tab == 2) { renderSimulation(); return }
        val all = elevationSliders()
        val basic = setOf("Base elevation", "Sea level", "Mountain cutoff", "Continent scale",
            "Continent strength", "Regional strength", "Mountain relief", "Erosion strength")
        val selected = if (tab == 0) all.filter { it.label in basic } else all.filter { it.label !in basic }
        selected.forEachIndexed { index, setting ->
            val x = (index % 2) * 452.0
            val y = (index / 2) * 84.0
            content.atlasText(font, setting.label, x, y, 18.0)
            val value = content.atlasText(font, formatNumber(setting.value, setting.decimals), x + 332, y, 18.0)
            val control = content.uiSlider(value = setting.value, min = setting.minimum,
                max = setting.maximum, step = setting.step, decimalPlaces = setting.decimals,
                size = Size(424.0, 32.0)) { showTooltip = null }.xy(x, y + 32)
            control.onChange.add { next ->
                value.text = formatNumber(next, setting.decimals); setting.update(next)
            }
        }

    }

    private fun renderSimulation() {
        content.atlasText(font, "Geology speed", 0.0, 0.0, 22.0)
        val rateLabel = content.atlasText(font, "×${geology.speedMultiplier}", 732.0, 0.0, 22.0)
        content.atlasText(font, "Accelerates plate motion, erosion, cooling and the plate lifecycle together.", 0.0, 36.0, 17.0, muted = true)
        val rate = content.uiSlider(value = geology.speedMultiplier.toDouble(), min = 1000.0, max = 10000.0,
            step = 1000.0, decimalPlaces = 0, size = Size(880.0, 36.0)) { showTooltip = null }.xy(0.0, 76.0)
        content.atlasText(font, "Geology update interval", 0.0, 148.0, 22.0)
        val intervalLabel = content.atlasText(font, "${geology.updateYears} years", 732.0, 148.0, 22.0)
        content.atlasText(font, "Smaller intervals give finer detail and use more CPU. Larger intervals are coarser.", 0.0, 184.0, 17.0, muted = true)
        val interval = content.uiSlider(value = geology.updateYears.toDouble(), min = 10.0, max = 500.0,
            step = 10.0, decimalPlaces = 0, size = Size(880.0, 36.0)) { showTooltip = null }.xy(0.0, 224.0)
        val step = content.atlasText(font, "", 0.0, 296.0, 18.0)
        fun changed() {
            rateLabel.text = "×${geology.speedMultiplier}"
            intervalLabel.text = "${geology.updateYears} years"
            step.text = "Each update advances ${formatNumber(geology.speedMultiplier.toDouble() * geology.updateYears / 1000, 0)} thousand geological years."
            onGeologyChanged(geology)
        }
        rate.onChange.add { geology = geology.copy(speedMultiplier = it.toInt()); changed() }
        interval.onChange.add { geology = geology.copy(updateYears = it.toInt()); changed() }
        step.text = "Each update advances ${formatNumber(geology.speedMultiplier.toDouble() * geology.updateYears / 1000, 0)} thousand geological years."
    }

    private fun refreshPendingChanges() {
        val live = tab == 2
        summary.visible = !live
        apply.view.visible = !live; discard.view.visible = !live
        defaults.caption.text = if (live) "Reset controls" else "Defaults"
        pending.text = if (live) "Live controls · your world and its clock are kept. Calendar speed is unchanged."
            else if (dirty) "Unapplied changes · rebuilding restarts the world and its clock."
            else "No changes · close this panel to keep exploring."
        pending.color = if (dirty) AtlasUi.warm else AtlasUi.muted
        apply.enabled = dirty; apply.selected = dirty
        discard.enabled = dirty
    }
}

internal fun formatNumber(value: Double, decimalPlaces: Int): String {
    if (!value.isFinite()) return "—"
    if (decimalPlaces == 0) return round(value).toLong().toString()
    val scale = 10.0.pow(decimalPlaces)
    val rounded = round(value * scale) / scale
    return (if (rounded == 0.0) 0.0 else rounded).toString()
}
