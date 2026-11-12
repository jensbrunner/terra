package terra.experiments

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.math.exp
import terra.atmosphere.DryAtmosphereConfig
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.world.World
import terra.weather.DynamicalWeather

/** Explicit bounded trajectory review, kept out of routine tests. No cloud or storm quotas. */
fun main(args: Array<String>) {
    val native = "--native" in args
    val world = if (native) World(31742) else World(31742, 96, 72)
    val config = if (native) DryAtmosphereConfig(stepSeconds = 40.0) else DryAtmosphereConfig(width = 48, height = 36)
    val aw = config.width; val ah = config.height; val scale = if (native) 2 else 3
    val hydrology = HydrologySimulation(HydrologyTopology(world))
    val weather = DynamicalWeather(world, hydrology, config)
    val lastDay = args.firstOrNull { it.startsWith("--days=") }?.substringAfter('=')?.toDouble() ?: 14.0
    require(lastDay in 0.25..14.0)
    val labels = (listOf(0.0, 0.25, 1.0, 3.0, 7.0, 14.0).filter { it <= lastDay } + lastDay).distinct().sorted()
    val image = BufferedImage(aw * 4 * scale, ah * labels.size * scale, BufferedImage.TYPE_INT_RGB)
    val metrics = StringBuilder()
    val output = File("build/experiments/atmosphere-upgrade").apply { mkdirs() }
    val started = System.nanoTime()
    for ((row, day) in labels.withIndex()) {
        while (weather.elapsedSeconds + 1e-6 < day * 86400) {
            val advanced = weather.advanceBudgeted(config.stepSeconds, 1, publishSnapshot = false)
            hydrology.acceptWeatherStep(weather, advanced)
        }
        weather.refreshSnapshot()
        val snapshot = weather.atmosphereSnapshot
        val dry = snapshot.dynamics
        val fields = snapshot.horizontalDynamics
        var kinetic = 0.0; var eddy = 0.0; var mass = 0.0; var curl2 = 0.0; var pressure2 = 0.0
        for (y in 0 until dry.config.height) {
            val meanU = (0 until dry.config.width).sumOf { dry.eastWindAt(it, y) } / dry.config.width
            val meanV = (0 until dry.config.width).sumOf { dry.northWindAt(it, y) } / dry.config.width
            for (x in 0 until dry.config.width) {
                val h = dry.heightAt(x, y); val u = dry.eastWindAt(x, y); val v = dry.northWindAt(x, y)
                mass += h
                kinetic += 0.5 * h * (u * u + v * v)
                eddy += 0.5 * h * ((u - meanU) * (u - meanU) + (v - meanV) * (v - meanV))
                val curl = fields.relativeVorticityPerSecondAt(x, y)
                val pressure = fields.pressureAnomalyPercentAt(x, y)
                curl2 += curl * curl; pressure2 += pressure * pressure
                val values = listOf((pressure / 5).coerceIn(-1.0, 1.0),
                    (curl / 1e-4).coerceIn(-1.0, 1.0),
                    1 - exp(-snapshot.cloudWaterMmAt(x, y) / 0.15), snapshot.relativeHumidityAt(x, y).coerceIn(0.0, 1.0))
                for ((column, value) in values.withIndex()) {
                    val color = if (column < 2) {
                        val a = kotlin.math.abs(value)
                        if (value < 0) Color((235 - 195 * a).toInt(), (240 - 140 * a).toInt(), (235 - 70 * a).toInt())
                        else Color((235 - 50 * a).toInt(), (240 - 170 * a).toInt(), (235 - 175 * a).toInt())
                    } else Color((255 * value).toInt(), (255 * value).toInt(), (255 * value).toInt())
                    for (dy in 0 until scale) for (dx in 0 until scale) image.setRGB((column * aw + x) * scale + dx, (row * ah + y) * scale + dy, color.rgb)
                }
            }
        }
        val cells = dry.config.cellCount.toDouble()
        val line = "day=$day ke_m2_s2=${kinetic / mass} eddy_ke_m2_s2=${eddy / mass} curl_rms_s1=${sqrt(curl2 / cells)} pressure_rms_percent=${sqrt(pressure2 / cells)} max_speed_ms=${dry.diagnostics.maximumSpeedMetersPerSecond} cfl=${dry.diagnostics.maximumCourant} cloud_mm=${snapshot.diagnostics.meanCloudMm} rain_mm=${snapshot.diagnostics.meanRainTotalMm} water_residual=${snapshot.diagnostics.relativeWaterResidual}"
        metrics.appendLine(line); println("ATMOSPHERE_EVOLUTION $line")
        check(kotlin.math.abs(snapshot.diagnostics.relativeWaterResidual) < 1e-10)
        check(dry.diagnostics.maximumCourant <= dry.config.courantLimit)
    }
    metrics.appendLine("experiment_wall_seconds=${(System.nanoTime() - started) / 1e9}")
    File(output, if (native) "evolution-native.txt" else "evolution-current.txt").writeText(metrics.toString())
    ImageIO.write(image, "png", File(output, if (native) "evolution-native.png" else "evolution-current.png"))
    }
