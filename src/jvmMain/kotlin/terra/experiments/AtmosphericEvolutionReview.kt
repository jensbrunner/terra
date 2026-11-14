package terra.experiments

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.math.exp
import terra.atmosphere.DryAtmosphereConfig
import terra.world.World
import terra.weather.DynamicalWeather

/** Explicit bounded trajectory review, kept out of routine tests. No cloud or storm quotas. */
fun main(args: Array<String>) {
    if ("--unseeded" in args) { reviewUnseeded(args); return }
    val native = "--native" in args
    val world = if (native) World(31742) else World(31742, 96, 72)
    val config = if (native) DryAtmosphereConfig(stepSeconds = 40.0) else DryAtmosphereConfig(width = 48, height = 36)
    val aw = config.width; val ah = config.height; val scale = if (native) 2 else 3

    val weather = DynamicalWeather(world, config)
    val climateYears = args.firstOrNull { it.startsWith("--climate-years=") }?.substringAfter('=')?.toInt() ?: 0
    require(climateYears in 0..1000)
    var remaining = climateYears
    while (remaining > 0) {
        val years = minOf(20, remaining)
        weather.advanceClimateInterval(years * weather.physics.solar.yearSeconds)
        remaining -= years
    }
    if (climateYears > 0) weather.resumeDetailed()
    val epoch = weather.elapsedSeconds
    val lastDay = args.firstOrNull { it.startsWith("--days=") }?.substringAfter('=')?.toDouble() ?: 14.0
    require(lastDay in 0.25..60.0)
    val labels = (listOf(0.0, 0.25, 1.0, 3.0, 7.0, 14.0, 28.0, 56.0).filter { it <= lastDay } + lastDay).distinct().sorted()
    val image = BufferedImage(aw * 4 * scale, ah * labels.size * scale, BufferedImage.TYPE_INT_RGB)
    val metrics = StringBuilder()
    val output = File(if (climateYears > 0) "build/experiments/reentry-$climateYears" else "build/experiments/atmosphere-upgrade").apply { mkdirs() }
    val started = System.nanoTime()
    for ((row, day) in labels.withIndex()) {
        while (weather.elapsedSeconds + 1e-6 < epoch + day * 86400) {
            weather.advanceBudgeted(config.stepSeconds, 1, publishSnapshot = false)
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

            }
        }
        drawFields(snapshot,image,row,scale)
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

/** No initialized lows: tiny broadband momentum perturbation over uniform
 * depth. Compare driven and undriven jets after weeks, not just retained eddies.
 */
private fun reviewUnseeded(args: Array<String>) {
    val days = args.firstOrNull { it.startsWith("--days=") }?.substringAfter('=')?.toInt() ?: 28
    val jet = args.firstOrNull { it.startsWith("--jet=") }?.substringAfter('=')?.toDouble() ?: terra.atmosphere.MoistPhysicsConfig().overturningWindMetersPerSecond
    val tauDays = args.firstOrNull { it.startsWith("--tau=") }?.substringAfter('=')?.toDouble() ?: terra.atmosphere.MoistPhysicsConfig().overturningSeconds / 86400.0
    val grid = if ("--native" in args) DryAtmosphereConfig(stepSeconds = 40.0)
        else DryAtmosphereConfig(width = 48, height = 36, stepSeconds = 80.0)
    val output = File("build/experiments/unseeded-weather").apply { mkdirs() }
    val rows = StringBuilder("driven,day,eddy_ke,max_wind,cloud_mm,water_residual,energy_residual,forcing_j\n")
    for (driven in if ("--driven-only" in args) listOf(true) else listOf(false, true)) {
        val random = kotlin.random.Random(42)
        val model = terra.atmosphere.MoistAtmosphere(grid,
            terra.atmosphere.MoistPhysicsConfig(overturningWindMetersPerSecond = if (driven) jet else 0.0,
                overturningSeconds = tauDays * 86400),
            initializeFlow = { _, _ -> terra.atmosphere.DryCell(grid.meanHeightMeters,
                random.nextDouble(-0.01,0.01), random.nextDouble(-0.01,0.01)) })
        for (day in 1..days) {
            repeat((86400 / grid.stepSeconds).toInt()) { model.step() }
            if (day in listOf(1,7,14,28,56,days)) {
                val s = model.snapshot(); val d = s.dynamics
                var eddy = 0.0
                for (y in 0 until grid.height) {
                    val u = (0 until grid.width).sumOf { d.eastWindAt(it,y) } / grid.width
                    val v = (0 until grid.width).sumOf { d.northWindAt(it,y) } / grid.width
                    for (x in 0 until grid.width) eddy += 0.5 *
                        ((d.eastWindAt(x,y)-u).let { it*it } + (d.northWindAt(x,y)-v).let { it*it }) / grid.cellCount
                }
                if (day >= 14) {
                    val image = BufferedImage(grid.width*8,grid.height*2,BufferedImage.TYPE_INT_RGB)
                    drawFields(s,image,0,2)
                    ImageIO.write(image,"png",File(output,"driven-$driven-day$day.png"))
                }
                if (driven && "--native" in args && day == 28)
                    check(eddy > 5.0) { "Jet forcing must generate growing resolved eddies, not just a mean wind: $eddy" }
                val diag = s.diagnostics
                val energy = diag.unexplainedEnergyResidualJoules / diag.totalModelEnergyJoules
                val line = "$driven,$day,$eddy,${d.diagnostics.maximumSpeedMetersPerSecond},${diag.meanCloudMm},${diag.relativeWaterResidual},$energy,${diag.overturningEnergyJoules}"
                println("UNSEEDED $line"); rows.appendLine(line)
                check(kotlin.math.abs(diag.relativeWaterResidual) < 1e-10)
                check(kotlin.math.abs(energy) < 1e-10)
            }
        }
    }
    File(output,"trajectory.csv").writeText(rows.toString())
}

private fun drawFields(snapshot: terra.atmosphere.MoistAtmosphereSnapshot,
    image: BufferedImage, row: Int, scale: Int) {
    val grid = snapshot.dynamics.config
    val fields = snapshot.horizontalDynamics
    for (y in 0 until grid.height) for (x in 0 until grid.width) {
        val values = listOf((fields.pressureAnomalyPercentAt(x,y) / 5).coerceIn(-1.0,1.0),
            (fields.relativeVorticityPerSecondAt(x,y) / 1e-4).coerceIn(-1.0,1.0),
            1 - exp(-snapshot.cloudWaterMmAt(x,y) / 0.15),snapshot.relativeHumidityAt(x,y).coerceIn(0.0,1.0))
        for ((column,value) in values.withIndex()) {
            val color = if (column < 2) {
                val a = kotlin.math.abs(value)
                if (value < 0) Color((235-195*a).toInt(),(240-140*a).toInt(),(235-70*a).toInt())
                else Color((235-50*a).toInt(),(240-170*a).toInt(),(235-175*a).toInt())
            } else Color((255*value).toInt(),(255*value).toInt(),(255*value).toInt())
            for (dy in 0 until scale) for (dx in 0 until scale)
                image.setRGB((column*grid.width+x)*scale+dx,(row*grid.height+y)*scale+dy,color.rgb)
        }
    }
}
