package terra.experiments

import java.io.File
import kotlin.math.abs
import terra.world.World
import terra.weather.DynamicalWeather

fun main(args: Array<String>) {
    val years = args.firstOrNull { it.startsWith("--years=") }?.substringAfter('=')?.toInt() ?: 100
    val start = System.nanoTime()
    val weather = DynamicalWeather(World(31742))
    val buildMs = (System.nanoTime() - start) / 1e6
    val year = weather.physics.solar.yearSeconds
    repeat(5) { weather.advanceClimateInterval(year) }
    var residual = 0.0
    val measured = System.nanoTime()
    repeat(years) { residual = maxOf(residual, abs(weather.advanceClimateInterval(year).relativeWaterResidual)) }
    val wall = (System.nanoTime() - measured) / 1e9
    val handoff = weather.resumeDetailed()!!
    weather.advanceBudgeted(3600.0, Int.MAX_VALUE)
    val report = "world=283x212 atlas_grid=96x72 startup_ms=$buildMs years=$years years_per_second=${years / wall} ms_per_month=${wall * 1000 / years / 12} surface_water_residual=$residual detailed_reentry_residual=${weather.diagnostics.relativeWaterResidual} initialization_water_change_kg=${handoff.waterAfterKg - handoff.waterBeforeKg}\n"
    File("build/experiments").mkdirs(); File("build/experiments/climate-benchmark.txt").writeText(report)
    print(report)
}
