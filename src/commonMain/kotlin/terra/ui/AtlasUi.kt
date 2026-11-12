package terra.ui

import korlibs.image.color.Colors
import korlibs.image.font.SystemFont
import korlibs.korge.input.mouse
import korlibs.korge.view.*
import korlibs.math.geom.RectCorners
import korlibs.math.geom.Size

/** Shared visual language; constructed once, with state changes kept out of layout code. */
internal object AtlasUi {
    val background = Colors["#0c151b"]
    val panel = Colors["#15242c"]
    val line = Colors["#2b4049"]
    val text = Colors["#e8f0ee"]
    val muted = Colors["#9db2b7"]
    val accent = Colors["#82d1bb"]
    val warm = Colors["#e9c38d"]
}

internal fun Container.atlasCard(width: Double, height: Double) =
    roundRect(Size(width, height), RectCorners(12), fill = AtlasUi.panel,
        stroke = AtlasUi.line, strokeThickness = 1.0)

internal fun Container.atlasText(font: SystemFont, value: String, x: Double, y: Double,
    size: Double = 14.0, muted: Boolean = false) =
    text(value, font = font, textSize = size, color = if (muted) AtlasUi.muted else AtlasUi.text).xy(x, y)

internal class AtlasButton(parent: Container, font: SystemFont, label: String,
    x: Double, y: Double, width: Double, height: Double = 36.0, private val action: () -> Unit
) {
    val view = parent.container { }.xy(x, y)
    private val background = view.roundRect(Size(width, height), RectCorners(7), fill = Colors.WHITE)
    val caption = view.atlasText(font, label, 12.0, (height - 17) / 2, 14.0)
    private var hover = false
    var selected = false
        set(value) { if (field != value) { field = value; refresh() } }
    var enabled = true
        set(value) { if (field != value) { field = value; refresh() } }
    var text: String
        get() = caption.text
        set(value) { caption.text = value }

    init {
        view.mouse {
            over { hover = true; refresh() }
            out { hover = false; refresh() }
            click { if (enabled) action() }
        }
        refresh()
    }

    private fun refresh() {
        background.color = Colors[when {
            selected && hover -> "#3c7166"
            selected -> "#2a554d"
            hover && enabled -> "#304650"
            else -> "#22353e"
        }]
        caption.color = if (selected) AtlasUi.accent else AtlasUi.text
        view.alpha = if (enabled) 1.0 else 0.4
    }
}
