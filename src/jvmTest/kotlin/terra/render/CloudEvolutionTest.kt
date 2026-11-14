package terra.render

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.weather.DynamicalWeather
import terra.world.World

/** One small real-world trajectory; images expose failures hidden by a uniform-cloud fixture. */
class CloudEvolutionTest {
    @Test
    fun inspectInitialDayAndClimateClouds() {
        val world = World(31742, 96, 72)

        val weather = DynamicalWeather(world, DryAtmosphereConfig())
        val renderer = CloudRenderer(world.width, world.height, world.seed)
        val frames = mutableListOf<Pair<String, RasterFrame>>()
        val rawFrames = mutableListOf<BufferedImage>()
        val diagnostics = StringBuilder()
        fun record(label: String) {
            renderer.update(weather, 0.2)
            val frame = renderer.frame
            frames += label to frame
            val alpha = (0 until frame.height).flatMap { y -> (0 until frame.width).map { x -> frame[x, y].a } }
            val raw = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until world.height) for (x in 0 until world.width) {
                val water = weather.cloudWaterMm(x, y)
                assertTrue(water.isFinite() && water >= 0)
                val humidity = weather.relativeHumidity(x, y)
                assertTrue(humidity.isFinite() && humidity >= 0) // Resolved columns can transiently supersaturate.
                if (weather.fieldSemantics.quality == terra.weather.WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL)
                    assertTrue(humidity <= 1.00000001)
                val shade = (255 * (1 - exp(-water / 0.15))).toInt().coerceIn(0, 255)
                raw.setRGB(x, y, Color(shade, shade, shade).rgb)
            }
            rawFrames += raw
            val rows = world.height / 6
            for ((name, center) in listOf("north" to 0.25, "equator" to 0.5, "south" to 0.75)) {
                val start = (world.height * center).toInt() - rows / 2
                var water = 0.0; var cover = 0.0; var rh = 0.0; var rain = 0.0; var soil = 0.0
                var opacity = 0.0; var visible = 0
                for (y in start until start + rows) for (x in 0 until world.width) {
                    water += weather.cloudWaterMm(x, y); cover += weather.cloudCover(x, y)
                    rh += weather.relativeHumidity(x, y); rain += weather.rainMmThisUpdate(x, y)
                    soil += weather.soilWaterMm(x, y)
                    for (dy in 0..1) for (dx in 0..1) {
                        val a = frame[x * 2 + dx, y * 2 + dy].a
                        opacity += a / 255.0; if (a > 24) visible++
                    }
                }
                val n = rows * world.width.toDouble()
                diagnostics.appendLine("""{"label":"$label","band":"$name","quality":"${weather.fieldSemantics.quality}","completed_seconds":${weather.elapsedSeconds},"rain_period_seconds":${weather.fieldSemantics.intervalDurationSeconds},"cloud_mm":${water / n},"cloud_cover":${cover / n},"relative_humidity":${rh / n},"funded_rain_mm_over_update":${rain / n},"soil_mm":${soil / n},"opacity":${opacity / (4 * n)},"visible_fraction":${visible / (4 * n)}}""")
            }
            println("CLOUD_EVOLUTION $label mean_alpha=${alpha.average()} clear_fraction=${alpha.count { it < 24 }.toDouble() / alpha.size} opaque_fraction=${alpha.count { it > 210 }.toDouble() / alpha.size}")
        }
        record("Initial")
        for (hour in 1..6) {
            repeat(180) { step ->
                weather.advanceBudgeted(20.0, 1, publishSnapshot = false)
                if (step % 36 == 35) { weather.refreshSnapshot(); renderer.update(weather, 0.2) }
            }
            if (hour in listOf(1, 6)) { weather.refreshSnapshot(); record("Hour $hour") }
        }
        weather.advanceClimateInterval(86400.0)
        repeat(15) { renderer.update(weather, 0.2) }; record("Climate day")
        weather.advanceClimateInterval(30 * 86400.0)
        repeat(15) { renderer.update(weather, 0.2) }; record("Climate month")
        repeat(30) { renderer.update(weather, 0.2) }; record("Month + 6 visual seconds")
        val panelW = renderer.frame.width * 2
        val panelH = renderer.frame.height * 2
        val output = BufferedImage(panelW * 3, (panelH + 24) * 2, BufferedImage.TYPE_INT_RGB)
        val g = output.createGraphics()
        val rawOutput = BufferedImage(output.width, output.height, BufferedImage.TYPE_INT_RGB)
        val rg = rawOutput.createGraphics()
        for ((index, entry) in frames.withIndex()) {
            val (label, frame) = entry
            val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val tile = world[x / 2, y / 2]
                val base = if (tile.elevation < world.seaLevel) intArrayOf(27, 65, 86) else intArrayOf(81, 112, 69)
                val pixel = frame[x, y]
                val a = pixel.a / 255.0
                image.setRGB(x, y, Color((base[0] * (1 - a) + pixel.r * a).toInt(),
                    (base[1] * (1 - a) + pixel.g * a).toInt(), (base[2] * (1 - a) + pixel.b * a).toInt()).rgb)
            }
            val left = index % 3 * panelW
            val top = index / 3 * (panelH + 24)
            g.color = Color.WHITE; g.drawString(label, left + 8, top + 17)
            g.drawImage(image, left, top + 24, panelW, panelH, null)
            rg.color = Color.WHITE; rg.drawString("$label: cloud water (0.15 mm optical scale)", left + 8, top + 17)
            rg.drawImage(rawFrames[index], left, top + 24, panelW, panelH, null)
        }
        g.dispose(); rg.dispose()
        val outputFile = File("build/reports/clouds/evolution-${System.getProperty("terra.cloudReview", "current")}.png")
        outputFile.parentFile.mkdirs(); ImageIO.write(output, "png", outputFile)
        ImageIO.write(rawOutput, "png", File(outputFile.parentFile, "evolution-raw.png"))
        File(outputFile.parentFile, "latitude-diagnostics.jsonl").writeText(diagnostics.toString())
    }
}
