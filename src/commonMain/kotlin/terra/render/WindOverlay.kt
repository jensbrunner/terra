package terra.render

import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.vector.ShapeBuilder
import korlibs.korge.view.Graphics
import korlibs.korge.view.GraphicsRenderer
import korlibs.math.geom.Point
import korlibs.math.geom.vector.LineCap
import terra.weather.WeatherModel
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Vector glyphs share a fixed physical speed scale with the legend, including across worlds/zoom. */
internal object WindGlyph {
    private val palette = listOf("#b5f0ed", "#83dcb5", "#f3dc84", "#f59b64", "#ee6f70").map { Colors[it] }
    private val halo = Colors["#10252e"].withA(205)
    const val MAX_SPEED = 20.0

    fun color(speedMetersPerSecond: Double): RGBA {
        val position = (speedMetersPerSecond / MAX_SPEED).coerceIn(0.0, 1.0) * (palette.size - 1)
        val index = position.toInt().coerceAtMost(palette.size - 2)
        val f = position - index
        val a = palette[index]; val b = palette[index + 1]
        return RGBA((a.r + (b.r - a.r) * f).roundToInt(), (a.g + (b.g - a.g) * f).roundToInt(),
            (a.b + (b.b - a.b) * f).roundToInt(), 255)
    }

    /** Length encodes m/s linearly; a small minimum keeps weak winds legible. */
    fun draw(builder: ShapeBuilder, x: Double, y: Double, u: Double, v: Double, speed: Double, scale: Double = 1.0) {
        if (!u.isFinite() || !v.isFinite() || !speed.isFinite()) return
        val direction = hypot(u, v)
        val strength = (speed / MAX_SPEED).coerceIn(0.0, 1.0)
        val thickness = (0.34 + 0.22 * strength) * scale
        with(builder) {
            if (speed < 0.2 || direction < 1e-9) {
                // A small round mark distinguishes calm air from missing data.
                stroke(halo, 0.95 * scale, lineCap = LineCap.ROUND) { moveTo(Point(x, y)); lineTo(Point(x + 0.01 * scale, y)) }
                stroke(palette.first(), 0.4 * scale, lineCap = LineCap.ROUND) { moveTo(Point(x, y)); lineTo(Point(x + 0.01 * scale, y)) }
                return
            }
            val dx = u / direction; val dy = v / direction
            val length = (1.5 + 9.0 * strength) * scale
            val head = (0.8 + 1.1 * strength) * scale
            val halfHead = head * 0.58
            val tailX = x - dx * length / 2; val tailY = y - dy * length / 2
            val tipX = x + dx * length / 2; val tipY = y + dy * length / 2
            val neckX = tipX - dx * head; val neckY = tipY - dy * head
            val left = Point(neckX - dy * halfHead, neckY + dx * halfHead)
            val right = Point(neckX + dy * halfHead, neckY - dx * halfHead)
            stroke(halo, thickness + 0.48 * scale, lineCap = LineCap.ROUND) {
                moveTo(Point(tailX, tailY)); lineTo(Point(neckX, neckY))
            }
            stroke(halo, 0.5 * scale, lineCap = LineCap.ROUND) {
                moveTo(left); lineTo(Point(tipX, tipY)); lineTo(right); close()
            }
            val ink = color(speed)
            stroke(ink, thickness, lineCap = LineCap.ROUND) { moveTo(Point(tailX, tailY)); lineTo(Point(neckX, neckY)) }
            fill(ink) { moveTo(left); lineTo(Point(tipX, tipY)); lineTo(right); close() }
        }
    }
}

/** No tile-sized raster: GPU antialiasing keeps arrows smooth when the camera zooms. */
internal class WindOverlay {
    val view = Graphics(renderer = GraphicsRenderer.GPU)
    fun update(weather: WeatherModel) {
        view.updateShape {
            for (y in 6 until weather.height step 12) for (x in 8 until weather.width step 16) {
                WindGlyph.draw(this, x + 0.5, y + 0.5, weather.windUTilesPerDay(x, y), weather.windVTilesPerDay(x, y),
                    weather.windSpeedMetersPerSecond(x, y))
            }
        }
    }
}
