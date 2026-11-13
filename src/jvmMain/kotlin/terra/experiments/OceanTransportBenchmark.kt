package terra.experiments

import java.io.File
import kotlin.math.abs
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.world.Terrain
import terra.world.World
import terra.weather.DynamicalWeather
import terra.weather.OceanTransportMode

/**
 * Compare the bounded lake/basin-scale heat redistribution against the simpler
 * same-basin lateral mixing on identical worlds. This is a closure comparison, not an
 * Earth-calibration claim.
 */
fun main() {
    val year = 365.0 * 86400.0
    fun run(mode: OceanTransportMode): String {
        val world = World(31742)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val weather = DynamicalWeather(world, hydrology, oceanTransport = mode)
        val detailed = weather.advanceBudgeted(40.0, 1, publishSnapshot = false)
        hydrology.acceptWeatherStep(weather, detailed)
        repeat(20) { weather.advanceClimateInterval(year) }
        var residual = 0.0
        val start = System.nanoTime()
        repeat(10) { residual = maxOf(residual, abs(weather.advanceClimateInterval(year).relativeWaterResidual)) }
        val wall = (System.nanoTime() - start) / 1e9
        weather.refreshSnapshot()
        var global = 0.0; var land = 0; var ocean = 0; var landRain = 0.0; var oceanRain = 0.0
        var coastalX = -1; var coastalY = -1; var bestDistance = Int.MAX_VALUE
        for (y in world.height / 4 until 3 * world.height / 4) for (x in 0 until world.width) {
            val t = weather.surfaceTemperatureC(x, y)
            val rain = weather.seasonalClimate!!.annualPrecipitationAtTile(x, y, world.width, world.height)
            global += t
            if (world[x, y].terrain == Terrain.OCEAN) { ocean++; oceanRain += rain }
            else {
                land++; landRain += rain
                if (world[x + 1, y].terrain == Terrain.OCEAN) {
                    val distance = kotlin.math.abs(y - world.height / 3)
                    if (distance < bestDistance) { bestDistance = distance; coastalX = x; coastalY = y }
                }
            }
        }
        val coastalTemperatures = DoubleArray(4)
        for (season in 0..3) {
            weather.advanceClimateInterval(year / 4)
            weather.refreshSnapshot()
            coastalTemperatures[season] = if (coastalX >= 0) weather.surfaceTemperatureC(coastalX, coastalY) else 0.0
        }
        val coastalRange = coastalTemperatures.max() - coastalTemperatures.min()
        return "mode=${mode.name} ms_per_year=${"%.1f".format(wall * 1000 / 10)} " +
            "residual=$residual global_t=${global / (world.width * world.height)} " +
            "land_rain=${landRain / maxOf(1, land)} ocean_rain=${oceanRain / maxOf(1, ocean)} " +
            "coastal_lat=$coastalY range=$coastalRange seasons=${coastalTemperatures.toList()} " +
            "biomes=${world.snapshot(1).landBiomeSummary().replace("\n", " ")}"
    }
    val lines = StringBuilder()
    lines.appendLine("bounded ocean heat-transport closure comparison")
    lines.appendLine(run(OceanTransportMode.LATERAL_MIXING))
    lines.appendLine(run(OceanTransportMode.BASIN_TRANSPORT))
    val output = File("build/experiments/ocean-transport-benchmark.txt")
    output.parentFile.mkdirs()
    output.writeText(lines.toString())
    print(lines)
    println("OCEAN_TRANSPORT_BENCHMARK ${output.absolutePath}")
}
