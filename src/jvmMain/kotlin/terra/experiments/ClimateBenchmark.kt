package terra.experiments

import java.io.File
import kotlin.math.abs
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.world.World
import terra.weather.DynamicalWeather

/**
 * Honest, warm-started climate work benchmark on the default 283x212 world.
 * It measures sustained one-year climate transactions, a single twenty-year batch,
 * water closure, representative circulation and the detailed-weather handoff.
 */
fun main(args: Array<String>) {
    val warmupYears = args.firstOrNull { it.startsWith("--warmup=") }?.substringAfter('=')?.toInt() ?: 5
    val measuredYears = args.firstOrNull { it.startsWith("--years=") }?.substringAfter('=')?.toInt() ?: 20
    require(warmupYears >= 0 && measuredYears in 1..200)
    val year = 365.0 * 86400.0
    val world = World(31742)
    val hydrology = HydrologySimulation(HydrologyTopology(world))
    val weather = DynamicalWeather(world, hydrology)
    val detailed = weather.advanceBudgeted(40.0, 1, publishSnapshot = false)
    hydrology.acceptWeatherStep(weather, detailed)
    weather.refreshSnapshot()
    val waterStart = weather.diagnostics.totalWaterKg

    repeat(warmupYears) { weather.advanceClimateInterval(year) }

    val lines = StringBuilder()
    lines.appendLine("world=${world.width}x${world.height} warmup_years=$warmupYears measured_years=$measuredYears")
    lines.appendLine("interval_seconds=${year.toLong()} detail_step_seconds=40")

    // Sustained one-year transactions.
    var maximumResidual = 0.0
    var rainKg = 0.0
    var evaporationKg = 0.0
    var maximumWind = 0.0
    var meanWind = 0.0
    val started = System.nanoTime()
    repeat(measuredYears) {
        val diagnostics = weather.advanceClimateInterval(year)
        maximumResidual = maxOf(maximumResidual, abs(diagnostics.relativeWaterResidual))
        rainKg += diagnostics.rainfallKg
        evaporationKg += diagnostics.evaporationKg
        maximumWind = maxOf(maximumWind, diagnostics.maximumWindMetersPerSecond)
        meanWind += diagnostics.meanWindMetersPerSecond
    }
    val oneYearWall = (System.nanoTime() - started) / 1e9
    val msPerYear = oneYearWall * 1000 / measuredYears

    // One batched transaction over the same number of years.
    val batchStart = System.nanoTime()
    val batch = weather.advanceClimateInterval(measuredYears * year)
    val batchWall = (System.nanoTime() - batchStart) / 1e9

    weather.refreshSnapshot()
    val climateWater = weather.diagnostics.totalWaterKg
    val handoff = weather.resumeDetailed()!!
    val handoffWater = handoff.waterAfterKg + weather.surfaceState.totalWaterKg
    val reentryStart = System.nanoTime()
    weather.advanceBudgeted(86400.0, Int.MAX_VALUE, publishSnapshot = false)
    hydrology.acceptWeatherStep(weather, 86400.0)
    weather.refreshSnapshot()
    val reentryWall = (System.nanoTime() - reentryStart) / 1e9
    val detailedResidual = weather.diagnostics.relativeWaterResidual
    val finalWater = weather.diagnostics.totalWaterKg
    val conservation = abs(handoffWater - climateWater) / climateWater
    val wholeRunDrift = abs(finalWater - waterStart) / waterStart
    val climate = weather.seasonalClimate
    val summary = world.snapshot(1).landBiomeSummary()
    val units = weather.surfaceState.responseUnits
    val landTiles = units.tileCountPerUnit.sum()

    lines.appendLine("one_year_wall_seconds=${"%.3f".format(oneYearWall)} ms_per_year=${"%.1f".format(msPerYear)} years_per_second=${"%.2f".format(measuredYears / oneYearWall)}")
    lines.appendLine("batch_wall_seconds=${"%.3f".format(batchWall)} ms_per_year=${"%.1f".format(batchWall * 1000 / measuredYears)}")
    lines.appendLine("one_year_water_residual_max=$maximumResidual batch_residual=${batch.relativeWaterResidual}")
    lines.appendLine("rain_kg=$rainKg evaporation_kg=$evaporationKg maximum_wind_ms=$maximumWind mean_wind_ms=${meanWind / measuredYears}")
    lines.appendLine("reentry_day_seconds=${"%.3f".format(reentryWall)} reentry_water_residual=$detailedResidual handoff_water_relative_error=$conservation whole_run_drift=$wholeRunDrift")
    lines.appendLine("handoff_cloud_kg=${handoff.cloudKg} handoff_vapor_kg=${handoff.vaporKg} reentry_cloud_present=${handoff.cloudKg > 0}")
    lines.appendLine("response_units=${units.count} land_tiles=$landTiles unit_vs_tile_slow_store_updates=${landTiles - units.count}")
    if (climate != null) {
        lines.appendLine("observed_years=${climate.observedYears} aggregate=${climate.containsAggregateEstimates}")
    }
    val output = File("build/experiments/climate-benchmark.txt")
    output.parentFile.mkdirs()
    output.writeText(lines.toString())
    print(lines)
    println("CLIMATE_BENCHMARK ${output.absolutePath}")
}
