package terra.experiments

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.korge.Korge
import korlibs.korge.annotations.KorgeExperimental
import korlibs.korge.view.*
import korlibs.math.geom.Size
import terra.render.RasterBlendImage
import terra.render.LayerFade
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/** Explicit tiny native-GPU regression; never part of game startup or the default test task. */
@OptIn(KorgeExperimental::class)
suspend fun main() {
    Korge(windowSize = Size(64, 64)) {
        val background = RGBA(30, 65, 90, 255)
        val old = Bitmap32(8, 8)
        val next = Bitmap32(8, 8)
        val fixture = container { solidRect(8, 8, background) }
        val cloud = RasterBlendImage(old, next).addTo(fixture)
        val fade = LayerFade(old).also { it.image = cloud }
        views.onBeforeRender.once { ctx ->
            try {
                val cases = listOf(
                    RGBA(240, 245, 250, 128) to RGBA(240, 245, 250, 128),
                    RGBA(220, 230, 240, 192) to RGBA(221, 231, 241, 192),
                    RGBA(240, 245, 250, 0) to RGBA(240, 245, 250, 204)
                )
                var samples = 0
                for ((a, b) in cases) {
                    fade.reset()
                    fade.begin(next)
                    next.lock { next.ints.fill(a.value) }
                    fade.complete(next); fade.advance(0.18)
                    fade.begin(next)
                    next.lock { next.ints.fill(b.value) }
                    fade.complete(next)
                    for (fraction in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                        cloud.fraction = fraction
                        val pixel = fixture.unsafeRenderToBitmapSync(ctx)[4, 4]
                        val alpha = (1 - fraction) * a.a / 255 + fraction * b.a / 255
                        fun expected(ca: Int, cb: Int, bg: Int) =
                            ((1 - fraction) * ca * a.a / 255 + fraction * cb * b.a / 255 + bg * (1 - alpha)).roundToInt()
                        val expected = intArrayOf(expected(a.r, b.r, background.r), expected(a.g, b.g, background.g), expected(a.b, b.b, background.b), 255)
                        val actual = intArrayOf(pixel.r, pixel.g, pixel.b, pixel.a)
                        check(actual.indices.all { abs(actual[it] - expected[it]) <= 2 }) {
                            "Raster blend at $fraction: actual=${actual.toList()}, expected=${expected.toList()}"
                        }
                        samples++
                    }
                }
                // Interrupt an actual half-finished fade. Its next source must
                // be the displayed mixture, so a new publication cannot jump.
                fade.reset()
                fade.begin(next)
                next.lock { next.ints.fill(RGBA(120, 160, 200, 80).value) }
                fade.complete(next); fade.advance(0.18)
                fade.begin(next)
                next.lock { next.ints.fill(RGBA(240, 245, 250, 220).value) }
                fade.complete(next); fade.advance(0.09)
                val before = fixture.unsafeRenderToBitmapSync(ctx)[4, 4]
                fade.begin(next)
                next.lock { next.ints.fill(RGBA(210, 230, 240, 140).value) }
                fade.complete(next)
                val after = fixture.unsafeRenderToBitmapSync(ctx)[4, 4]
                check(abs(before.r - after.r) <= 2 && abs(before.g - after.g) <= 2 && abs(before.b - after.b) <= 2) {
                    "Interrupted fade jumped: $before -> $after"
                }
                samples++
                println("RASTER_BLEND passed $samples GPU samples: stable brightness, transparent edges, interrupted-fade continuity")
                exitProcess(0)
            } catch (failure: Throwable) {
                failure.printStackTrace()
                exitProcess(1)
            }
        }
    }
}
