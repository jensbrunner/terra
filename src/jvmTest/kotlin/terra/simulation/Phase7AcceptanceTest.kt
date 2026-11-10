package terra.simulation

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig

/** Two bounded acceptance cases, not a seed/quantum sweep or an equivalence calibration. */
class Phase7AcceptanceTest {
    private val day = 86400.0
    private val request = WorldRequest(generation = 1, width = 15, height = 11, dynamical = true,
        atmosphere = DryAtmosphereConfig(width = 8, height = 6))
    private fun capture(owner: WorldSimulation) = owner.snapshot(SimulationProgress(
        owner.request.generation, 1, 0, owner.completedSeconds, SimulationSpeed.TEN_YEARS,
        0.0, 0.0, 0.0, day, false, 0.0, 0.0, 0))

    @Test
    fun sameInitialDayComparisonThenTwentyYearsOfMonthlyEvolution() {
        val detailed = WorldSimulation(request)
        val climate = WorldSimulation(request)
        val initial = capture(climate)
        val initialWater = initial.diagnostics!!.totalWaterKg
        var detailedRain = 0.0
        var detailedTemperature = 0.0
        repeat(4320) {
            detailed.advance(20.0, SimulationSpeed.ONE_HOUR)
            val weather = capture(detailed).weather
            for (y in 0 until request.height) for (x in 0 until request.width) {
                detailedRain += weather.rainMmThisUpdate(x, y)
                detailedTemperature += weather.surfaceTemperatureC(x, y)
            }
        }
        val short = climate.advanceClimateInterval(day)
        val coarseDay = capture(climate)
        val detailedDay = capture(detailed)
        val n = request.width * request.height
        var temperatureSquared = 0.0
        var coarseTemperature = 0.0
        var coarseRain = 0.0
        var coarseSoil = 0.0
        var detailedSoil = 0.0
        var coarseRouting = 0.0
        var detailedRouting = 0.0
        for (y in 0 until request.height) for (x in 0 until request.width) {
            val t = coarseDay.weather.surfaceTemperatureC(x, y)
            val dt = detailedDay.weather.surfaceTemperatureC(x, y)
            temperatureSquared += (t - dt) * (t - dt)
            coarseTemperature += t
            coarseRain += coarseDay.weather.rainMmThisUpdate(x, y)
            coarseSoil += coarseDay.weather.soilWaterMm(x, y)
            detailedSoil += detailedDay.weather.soilWaterMm(x, y)
            coarseRouting += coarseDay.hydrology.routedMmTiles(x, y)
            detailedRouting += detailedDay.hydrology.routedMmTiles(x, y)
        }
        assertEquals(detailed.completedSeconds, climate.completedSeconds)
        assertTrue(abs(detailedDay.diagnostics!!.relativeWaterResidual) < 1e-10)
        assertTrue(abs(short.relativeWaterResidual) < 1e-10)
        assertTrue(temperatureSquared.isFinite() && coarseRain.isFinite() && detailedRain.isFinite())
        println("ACCEPTANCE_DAY endpoint_temperature_rmse_c=${sqrt(temperatureSquared / n)} " +
            "endpoint_mean_c_detailed=${(0 until request.height).sumOf { y -> (0 until request.width).sumOf { x -> detailedDay.weather.surfaceTemperatureC(x, y) } } / n} " +
            "endpoint_mean_c_climate=${coarseTemperature / n} time_mean_c_detailed=${detailedTemperature / n / 4320} " +
            "rain_mm_detailed=${detailedRain / n} rain_mm_climate=${coarseRain / n} " +
            "soil_mm_detailed=${detailedSoil / n} soil_mm_climate=${coarseSoil / n} " +
            "summed_link_throughput_mm_tiles_detailed=$detailedRouting summed_link_throughput_mm_tiles_climate=$coarseRouting")

        // Twenty years total, twelve intervals/year, retaining seasonal ecological evidence.
        var maxResidual = abs(short.relativeWaterResidual)
        repeat(240) {
            val seconds = (20 * 365 * day - day) / 240
            val result = climate.advanceClimateInterval(seconds)
            maxResidual = maxOf(maxResidual, abs(result.relativeWaterResidual))
        }
        val evolved = capture(climate)
        assertEquals(20 * 365 * day, climate.completedSeconds, 1e-3)
        assertEquals(initialWater, evolved.climateDiagnostics!!.waterAfterKg, initialWater * 1e-10)
        assertTrue(maxResidual < 1e-10)
        assertTrue(evolved.seasonalClimate!!.hasAnnualCoverage)
        var changed = 0
        for (y in 0 until request.height) for (x in 0 until request.width) {
            if (initial.world[x, y].terrain != evolved.world[x, y].terrain) changed++
            assertTrue(evolved.weather.surfaceTemperatureC(x, y).isFinite())
            assertTrue(evolved.weather.soilWaterMm(x, y) in 0.0..250.00000001)
            assertTrue(evolved.weather.atmosphericWaterMm(x, y) >= 0.0)
        }
        println("ACCEPTANCE_LONG years=20 intervals=241 max_interval_water_residual=$maxResidual " +
            "total_relative_water_drift=${(evolved.climateDiagnostics.waterAfterKg - initialWater) / initialWater} " +
            "observed_years=${evolved.observedClimateYears} biome_revision=${evolved.world.biomeRevision} changed_tiles=$changed " +
            "initial_biomes=${initial.world.landBiomeSummary()} final_biomes=${evolved.world.landBiomeSummary()}")
    }

    @Test
    fun fullSizeFastWorkerAcknowledgesPauseWithCloudPublication() {
        val worker = SimulationWorker(WorldRequest(generation = 1, dynamical = true))
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue(condition(), "Full-size worker did not complete the bounded operation: ${worker.failure}")
        }
        try {
            worker.setCloudsVisible(true)
            worker.updateControls(SimulationSpeed.TEN_YEARS, false)
            await { worker.latestSnapshot()?.let { it.progress.completedSeconds >= 365 * day && it.clouds != null } == true }
            val start = System.nanoTime()
            val revision = worker.updateControls(SimulationSpeed.TEN_YEARS, true)
            await { worker.latestSnapshot()?.progress?.let { it.appliedControlRevision == revision && it.paused } == true }
            val pauseMs = (System.nanoTime() - start) / 1e6
            val paused = worker.latestSnapshot()!!
            Thread.sleep(30)
            assertEquals(paused.progress.completedSeconds, worker.latestSnapshot()!!.progress.completedSeconds)
            assertEquals(null, worker.failure)
            println("ACCEPTANCE_PAUSE full_size=283x212 clouds=true acknowledgement_ms=$pauseMs " +
                "pending_seconds=${paused.progress.pendingSeconds} pending_limit=${paused.progress.pendingLimitSeconds}")
        } finally { worker.close() }
        await { !worker.isRunning }
    }
}
