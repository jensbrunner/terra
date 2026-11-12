package terra.experiments

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import korlibs.korge.Korge
import korlibs.korge.scene.sceneContainer
import korlibs.korge.view.renderToBitmap
import korlibs.math.geom.Rectangle
import korlibs.math.geom.Size
import terra.ui.AtlasScene
import kotlin.system.exitProcess

/** Opt-in export of actual game views for visual review; not run during ordinary play. */
suspend fun main() {
    Korge(windowSize = Size(1440, 960)) {
        val output = File("build/reports/ui").apply { mkdirs() }
        val previewViews = views
        sceneContainer().changeTo {
            AtlasScene(dynamicalAtmosphere = true, onReviewReady = { scene, root ->
                CoroutineScope(previewViews.coroutineContext).launch {
                    try {
                        val states = System.getProperty("terra.uiReviewStates", "overview,inspection,details,speed,settings,climate-settings,advanced-settings,settings-edited,help").split(',')
                        for (state in states) {
                            scene.prepareUiReview(state)
                            val bitmap = root.renderToBitmap(previewViews, Rectangle(0, 0, 1440, 960))
                            val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
                            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                                val pixel = bitmap[x, y]
                                image.setRGB(x, y, (pixel.a shl 24) or (pixel.r shl 16) or (pixel.g shl 8) or pixel.b)
                            }
                            ImageIO.write(image, "png", File(output, "$state.png"))
                        }
                        println("UI_PREVIEW exported ${states.size} actual game views to ${output.absolutePath}")
                        exitProcess(0)
                    } catch (failure: Throwable) { failure.printStackTrace(); exitProcess(1) }
                }
            })
        }
    }
}
