package terra.render

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import terra.weather.WeatherModel
import terra.weather.WeatherFieldSemantics
import terra.weather.WeatherFieldQuality
import terra.weather.WeatherSimulation
import terra.world.World

class CloudRendererTest {
    @Test
    fun clearAirStaysClearAndRenderingDoesNotAdvanceWeather() {
        val weather = CloudFixture()
        val renderer = CloudRenderer(weather.width, weather.height, 31742)
        assertTrue(renderer.update(weather))
        assertEquals(0.0, weather.elapsedSeconds)
        for (y in 0 until renderer.frame.height) for (x in 2..20) {
            assertEquals(0, renderer.frame[x, y].a)
        }
        writeVisualReview(renderer, weather)
        weather.empty = true
        weather.elapsedSeconds = 20.0
        renderer.update(weather)
        for (y in 0 until renderer.frame.height) for (x in 0 until renderer.frame.width) {
            assertEquals(0, renderer.frame[x, y].a)
        }
    }

    @Test
    fun textureFollowsWindAcrossTheSeamAndStopsOrResetsWithTheClock() {
        val weather = CloudFixture(uniform = true)
        val renderer = CloudRenderer(weather.width, weather.height, 31742)
        renderer.update(weather)
        val frozen = renderer.frame
        val w = renderer.frame.width
        val h = renderer.frame.height
        val before = IntArray(w * h) { renderer.frame[it % w, it / w].a }
        // 40 tiles/day for 1/80 day: exactly one rendered pixel east.
        weather.elapsedSeconds = 86400.0 / 80
        // A new immutable publication must not reset one owner's advected texture.
        val published = object : WeatherModel by weather {}
        renderer.update(published)
        assertTrue(frozen !== renderer.frame)
        for (y in 0 until h) for (x in 0 until w) assertEquals(before[y * w + x], frozen[x, y].a)
        var movedError = 0L
        var stationaryError = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val alpha = renderer.frame[x, y].a
            movedError += abs(alpha - before[y * w + (x + w - 1) % w])
            stationaryError += abs(alpha - before[y * w + x])
        }
        assertTrue(movedError < stationaryError / 2, "Texture must follow wind, including the periodic seam")
        assertFalse(renderer.update(weather))
        weather.elapsedSeconds = 0.0
        assertTrue(renderer.update(weather))
        for (y in 0 until h) for (x in 0 until w) assertEquals(before[y * w + x], renderer.frame[x, y].a)
    }

    @Test
    fun illustrativeCloudMotionUsesVisualIncrementInsteadOfSkippedYears() {
        val a = CloudFixture(uniform = true, representative = true)
        val b = CloudFixture(uniform = true, representative = true)
        val short = CloudRenderer(a.width, a.height, 31742)
        val long = CloudRenderer(b.width, b.height, 31742)
        short.update(a)
        long.update(b)
        val frozen = long.frame
        a.elapsedSeconds = 86400.0
        b.elapsedSeconds = 20 * 365 * 86400.0
        short.update(a, visualElapsedSeconds = 0.2)
        long.update(b, visualElapsedSeconds = 0.2)
        var moved = false
        for (y in 0 until frozen.height) for (x in 0 until frozen.width) {
            assertEquals(short.frame[x, y], long.frame[x, y])
            if (long.frame[x, y] != frozen[x, y]) moved = true
        }
        assertTrue(moved)
        assertFalse(long.update(b)) // A paused caller supplies no visual elapsed time.
        val beforeMotion = long.frame
        assertTrue(long.update(b, visualElapsedSeconds = 0.2)) // Climate animation continues between physical commits.
        assertTrue(beforeMotion !== long.frame)
        assertEquals(20 * 365 * 86400.0, b.elapsedSeconds)
    }

    /** One small before/after artifact for visual review, not a physics experiment. */
    private fun writeVisualReview(renderer: CloudRenderer, weather: CloudFixture) {
        val scale = 3
        val panelWidth = renderer.frame.width * scale
        val panelHeight = renderer.frame.height * scale
        val output = BufferedImage(panelWidth * 2 + 36, panelHeight + 54, BufferedImage.TYPE_INT_RGB)
        val graphics = output.createGraphics()
        graphics.color = Color(17, 28, 37)
        graphics.fillRect(0, 0, output.width, output.height)
        graphics.color = Color(227, 235, 236)
        graphics.drawString("Previous opacity overlay", 12, 23)
        graphics.drawString("Wind-advected cloud detail", panelWidth + 24, 23)
        for (panel in 0..1) {
            val image = BufferedImage(renderer.frame.width, renderer.frame.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val tileX = x / CloudRenderer.PIXELS_PER_TILE
                val tileY = y / CloudRenderer.PIXELS_PER_TILE
                val water = weather.cloudWaterMm(tileX, tileY)
                val color = renderer.frame[x, y]
                val alpha = if (panel == 0) 1 - exp(-water / 0.15) else color.a / 255.0
                val checker = ((tileX / 7 + tileY / 7) % 2) * 9
                val base = intArrayOf(48 + checker, 85 + checker, 73 + checker)
                val cloud = if (panel == 0) intArrayOf(241, 244, 242) else intArrayOf(color.r, color.g, color.b)
                image.setRGB(x, y, Color(
                    (base[0] * (1 - alpha) + cloud[0] * alpha).toInt(),
                    (base[1] * (1 - alpha) + cloud[1] * alpha).toInt(),
                    (base[2] * (1 - alpha) + cloud[2] * alpha).toInt()).rgb)
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(image, 12 + panel * (panelWidth + 12), 36, panelWidth, panelHeight, null)
        }
        graphics.dispose()
        val file = File("build/reports/clouds/review.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}

private class CloudFixture(private val uniform: Boolean = false, private val representative: Boolean = false) : WeatherModel by WeatherSimulation(World(31742, 96, 72)) {
    override val fieldSemantics get() = if (representative)
        WeatherFieldSemantics(WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL, 0.0, 20 * 365 * 86400.0)
        else WeatherFieldSemantics.RESOLVED
    override val width = 96
    override val height = 72
    override var elapsedSeconds = 0.0
    var empty = false
    override fun step(seconds: Double) { error("A renderer must not advance weather") }
    override fun cloudWaterMm(x: Int, y: Int): Double {
        if (empty || (!uniform && x < 12)) return 0.0
        if (uniform) return 0.2
        val dx = x - 55.0
        val dy = y - 36.0
        val head = 0.85 * exp(-(dx * dx / 220 + dy * dy / 160))
        val band = 0.16 * exp(-((y - 22 - 0.25 * x) * (y - 22 - 0.25 * x)) / 20) * exp(-dx * dx / 1100)
        return head + band
    }
    override fun precipitationMmPerHour(x: Int, y: Int) = if (cloudWaterMm(x, y) > 0.4) 2.0 else 0.0
    override fun surfaceTemperatureC(x: Int, y: Int) = 28.0
    override fun airTemperatureC(x: Int, y: Int) = 2.0
    override fun windUTilesPerDay(x: Int, y: Int) = 40.0
    override fun windVTilesPerDay(x: Int, y: Int) = 0.0
}
