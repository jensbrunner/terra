package terra.render

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.weather.DynamicalWeather
import terra.world.World

/** One small real-world trajectory; images expose failures hidden by a uniform-cloud fixture. */
class CloudEvolutionTest {
    @Test
    fun inspectInitialDayAndClimateClouds() {
        val world = World(31742, 96, 72)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val weather = DynamicalWeather(world, hydrology, DryAtmosphereConfig())
        val renderer = CloudRenderer(world.width, world.height, world.seed)
        val frames = mutableListOf<Pair<String, RasterFrame>>()
        fun record(label: String) {
            renderer.update(weather, 0.2)
            val frame = renderer.frame
            frames += label to frame
            val alpha = (0 until frame.height).flatMap { y -> (0 until frame.width).map { x -> frame[x, y].a } }
            assertTrue(alpha.count { it > 24 }.toDouble() / alpha.size > 0.02,
                "$label should have visible cloud structures, not a nearly empty sky")
            assertTrue(alpha.count { it < 24 }.toDouble() / alpha.size > 0.15, "$label should retain clear gaps")
            println("CLOUD_EVOLUTION $label mean_alpha=${alpha.average()} clear_fraction=${alpha.count { it < 24 }.toDouble() / alpha.size} opaque_fraction=${alpha.count { it > 210 }.toDouble() / alpha.size}")
        }
        record("Initial")
        for (hour in 1..6) {
            repeat(180) { step ->
                hydrology.acceptWeatherStep(weather, weather.advanceBudgeted(20.0, 1, publishSnapshot = false))
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
        }
        g.dispose()
        val outputFile = File("build/reports/clouds/evolution-${System.getProperty("terra.cloudReview", "current")}.png")
        outputFile.parentFile.mkdirs(); ImageIO.write(output, "png", outputFile)
    }
}
