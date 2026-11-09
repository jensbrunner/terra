package terra

import korlibs.image.color.Colors
import korlibs.korge.Korge
import korlibs.korge.scene.sceneContainer
import korlibs.math.geom.Size
import korlibs.render.awt.AwtGameWindow
import terra.ui.AtlasScene
import java.awt.EventQueue
import javax.swing.JFrame

suspend fun main() {
    Korge(
        windowSize = Size(WINDOW_WIDTH.toInt(), WINDOW_HEIGHT.toInt()),
        backgroundColor = Colors["#0d1719"]
    ) {
        // Use KorGE's normal window creation path, then maximize its AWT frame
        // after KorGE has configured and shown it.
        (gameWindow as? AwtGameWindow)?.let { awtWindow ->
            EventQueue.invokeLater {
                awtWindow.frame.extendedState = JFrame.MAXIMIZED_BOTH
            }
        }
        sceneContainer().changeTo { AtlasScene() }
    }
}
