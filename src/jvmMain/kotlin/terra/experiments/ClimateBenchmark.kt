package terra.experiments

import java.io.File
import kotlin.math.abs
import terra.world.World
import terra.climate.ClimateSimulation

fun main(args: Array<String>) {
    val years = args.firstOrNull { it.startsWith("--years=") }?.substringAfter('=')?.toInt() ?: 100
    val start = System.nanoTime()
    val weather = ClimateSimulation(World(31742))
    val buildMs = (System.nanoTime() - start) / 1e6
    val year = weather.solar.yearSeconds
    repeat(5) { weather.advance(year) }
    var residual = 0.0
    val measured = System.nanoTime()
    repeat(years) { residual = maxOf(residual, abs(weather.advance(year).relativeWaterResidual)) }
    val wall = (System.nanoTime() - measured) / 1e9
    val report = "world=283x212 atlas_grid=96x72 startup_ms=$buildMs years=$years years_per_second=${years / wall} ms_per_month=${wall * 1000 / years / 12} surface_water_residual=$residual\n"
    File("build/experiments").mkdirs(); File("build/experiments/climate-benchmark.txt").writeText(report)
    print(report)
}
