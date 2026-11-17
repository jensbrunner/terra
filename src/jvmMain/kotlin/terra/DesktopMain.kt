package terra

import korlibs.image.color.Colors
import korlibs.korge.KorgeDisplayMode
import korlibs.korge.Korge
import korlibs.korge.scene.sceneContainer
import korlibs.math.geom.Size
import korlibs.render.GameWindowCreationConfig
import korlibs.render.awt.AGOpenglAWT
import korlibs.render.awt.BaseAwtGameWindow
import korlibs.render.platform.BaseOpenglContext
import korlibs.render.platform.glContextFromComponent
import terra.ui.AtlasScene
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame

suspend fun main() {
    // Select the OpenGL pipeline before JFrame initializes AWT (macOS otherwise selects Metal).
    System.setProperty("sun.java2d.opengl", "true")
    System.setProperty("sun.java2d.metal", "false")
    lateinit var window: BaseAwtGameWindow
    val frame = object : JFrame("Terra") {
        override fun paintComponents(g: Graphics) = Unit
        override fun paint(g: Graphics) { window.framePaint(g) }
    }.apply {
        ignoreRepaint = true
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
    }
    // The stock AWT window ignores preferredFps. Keep its rendering path with explicit pacing.
    window = object : BaseAwtGameWindow(AGOpenglAWT(GameWindowCreationConfig.DEFAULT)) {
        override val component get() = frame
        override val contentComponent get() = frame.contentPane
        override var ctx: BaseOpenglContext? = null
        override fun ensureContext() {
            if (ctx == null) ctx = glContextFromComponent(frame, this)
        }
        override fun computeDisplayRefreshRate() = 100
        override fun setSize(width: Int, height: Int) {
            contentComponent.preferredSize = Dimension(width, height)
            frame.pack()
            frame.setLocationRelativeTo(null)
        }
        override fun frameDispose() { frame.dispose() }
    }
    frame.addWindowListener(object : WindowAdapter() {
        override fun windowClosed(event: WindowEvent) { window.close() }
    })
    Korge(
        gameWindow = window,
        targetFps = 100.0,
        displayMode = KorgeDisplayMode.TOP_LEFT_NO_CLIP,
        windowSize = Size(WINDOW_WIDTH.toInt(), WINDOW_HEIGHT.toInt()),
        backgroundColor = Colors["#0d1719"]
    ) {
        EventQueue.invokeLater { frame.extendedState = JFrame.MAXIMIZED_BOTH }
        sceneContainer().changeTo { AtlasScene() }
    }
}
