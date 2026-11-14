package terra.experiments

import java.awt.Color
import java.awt.RenderingHints
import kotlin.math.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import terra.atmosphere.DryAtmosphereConfig
import terra.weather.*
import terra.world.*

/** Review emergent circulation across seeds and fixed geographic counterfactuals. */
fun main(args: Array<String>) {
    val output = File(args.firstOrNull { it.startsWith("--output=") }?.substringAfter('=') ?: "build/experiments/seasonal-atlas").also { it.mkdirs() }
    val summaries = StringBuilder()
    fun export(name: String, world: World, wind: Double? = null, month: Int = 3) {
        val start = System.nanoTime()
        val atlas = ClimateAtlasBuilder(windOverride = wind).build(ClimateGeography(world))
        world.evolveAtlasCover(atlas, 0.0, initialize = true)
        val millis = (System.nanoTime() - start) / 1e6
        val w = world.width; val h = world.height
        val scale = 3; val label = 26
        val image = BufferedImage(w * scale * 3, (h * scale + label) * 2, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val labels = listOf("Annual rain · mm/year (0–2500)", "Orbital month ${month + 1} rain · mm/month (0–300)", "Annual temperature · °C (−25–35)", "Aridity · rain / PET (0–2)", "Land cover · equilibrium", "Orbital month ${month + 1} wind · m/s (0–12)")
        val colors = listOf(Color(167, 99, 50), Color(229, 212, 122), Color(104, 184, 121), Color(33, 153, 163), Color(105, 89, 176))
        fun ramp(v: Double): Color {
            val p = v.coerceIn(0.0, 1.0) * 4; val a = p.toInt().coerceAtMost(3); val f = p - a
            return Color((colors[a].red * (1 - f) + colors[a + 1].red * f).toInt(), (colors[a].green * (1 - f) + colors[a + 1].green * f).toInt(), (colors[a].blue * (1 - f) + colors[a + 1].blue * f).toInt())
        }
        for (panel in 0..5) {
            val ox = panel % 3 * w * scale; val oy = panel / 3 * (h * scale + label)
            graphics.color = Color(20, 29, 36); graphics.fillRect(ox, oy, w * scale, label)
            graphics.color = Color.WHITE; graphics.drawString(labels[panel], ox + 8, oy + 18)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                graphics.color = when (panel) {
                    0 -> ramp(atlas.annualRainfallMm(i) / 2500)
                    1 -> ramp(atlas.rainfallMm(month, i) / 300)
                    2 -> Color.getHSBColor(((35 - atlas.meanTemperature[i]) / 60 * 0.7).toFloat().coerceIn(0f, 0.7f), 0.6f, 0.9f)
                    3 -> ramp(atlas.aridity(i) / 2)
                    4 -> when (world[x, y].terrain) { Terrain.OCEAN -> Color(37, 76, 105); Terrain.WOODS -> Color(31, 102, 59); Terrain.DESERT -> Color(201, 165, 96); Terrain.STEPPE -> Color(159, 163, 90); Terrain.TUNDRA -> Color(155, 165, 157); Terrain.MOUNTAINS -> Color(130, 128, 121); else -> Color(106, 153, 85) }
                    else -> ramp(hypot(atlas.windSouthMetersPerSecond(month, i), atlas.windEastMetersPerSecond(month, i)) / 12)
                }
                graphics.fillRect(ox + x * scale, oy + label + y * scale, scale, scale)
            }
        }
        // Fixed-length sampling grid; arrow length is proportional to physical speed.
        val windX = 2 * w * scale; val windY = h * scale + label + label
        graphics.stroke = java.awt.BasicStroke(1.2f)
        graphics.color = Color(25, 34, 42)
        for (y in 6 until h step 12) for (x in 8 until w step 16) {
            val i = y * w + x
            val u = atlas.windEastMetersPerSecond(month, i); val v = atlas.windSouthMetersPerSecond(month, i)
            val cx = windX + x * scale; val cy = windY + y * scale
            val dx = u * 1.5; val dy = v * 1.5
            if (hypot(u, v) < 0.1) { graphics.fillOval(cx - 1, cy - 1, 2, 2); continue }
            val tx = cx + dx; val ty = cy + dy
            graphics.draw(java.awt.geom.Line2D.Double(cx - dx, cy - dy, tx, ty))
            val angle = atan2(v, u)
            for (turn in listOf(-0.5, 0.5)) graphics.draw(java.awt.geom.Line2D.Double(tx, ty, tx - 5 * cos(angle + turn), ty - 5 * sin(angle + turn)))
        }
        graphics.dispose(); ImageIO.write(image, "png", File(output, "$name.png"))
        val landRain = (0 until w * h).filter { world.isLand(it % w, it / w) }.map { atlas.annualRainfallMm(it) }.sorted()
        summaries.appendLine("$name build_ms=$millis q_sweep_residual_mm=${atlas.maximumHumidityChangeMm} temperature_range=${atlas.meanTemperature.min()}..${atlas.meanTemperature.max()} max_wind_ms=${(0 until w * h).maxOf { hypot(atlas.windEastMetersPerSecond(month, it), atlas.windSouthMetersPerSecond(month, it)) }} land_rain_p10_p50_p90=${listOf(0.1,0.5,0.9).map { landRain[(it * (landRain.size - 1)).toInt()] }} ${world.landBiomeSummary()}")
    }
    for (seed in listOf(31742, 42, 777)) export("seed-$seed", World(seed))
    val w = 144; val h = 108
    fun continent(ridge: Boolean) = World.withElevation(1, w, h, TerrainGenerationSettings(), DoubleArray(w * h) {
        val x = it % w
        if (x !in 25..118) 0.1 else if (ridge && x in 67..73) 0.65 else 0.32
    })
    export("continent", continent(false))
    export("ridge-east", continent(true), 5.0)
    export("ridge-west", continent(true), -5.0)
    export("ridge-emergent", continent(true))
    export("continent-winter", continent(false), month = 9)
    val island = World.withElevation(1, w, h, TerrainGenerationSettings(), DoubleArray(w * h) {
        if (it % w in 25..118 && it / w in 25..82) 0.32 else 0.1
    })
    export("island-summer", island)
    export("island-winter", island, month = 9)
    File(output, "metrics.txt").writeText(summaries.toString()); print(summaries)
    println("ATLAS_REVIEW ${output.absolutePath}")
}
