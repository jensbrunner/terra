package terra.simulation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.weather.DynamicalWeather
import terra.weather.WeatherModel
import terra.world.World

class SimulationWorkerTest {
    @Test
    fun climateWorkLongerThanQuarterSecondStillRequestsAllElapsedTime() {
        val year = 365 * 86400.0
        val demand = SimulationDemand(year)
        demand.controls(SimulationSpeed.TEN_YEARS, false)
        demand.request(0.3)
        assertEquals(3 * year, demand.pendingSeconds, 1e-6)
        assertEquals(0.0, demand.unservedSeconds)
    }

    private val request = WorldRequest(generation = 1, width = 15, height = 11, atmosphere = DryAtmosphereConfig(width = 8, height = 6))

    @Test
    fun demandIsBoundedAccountedAndPausedWithoutAdvancingPhysicalTime() {
        val demand = SimulationDemand(20.0)
        demand.controls(SimulationSpeed.REALTIME, false)
        repeat(80) { demand.request(0.25) }
        assertEquals(20.0, demand.pendingSeconds)
        demand.consume(20.0)
        demand.controls(SimulationSpeed.ONE_MONTH, false)
        demand.request(60.0)
        assertTrue(demand.pendingSeconds <= demand.limitSeconds)
        assertEquals(60 * SimulationSpeed.ONE_MONTH.simulatedSecondsPerRealSecond,
            demand.pendingSeconds + demand.unservedSeconds, 1e-6)
        demand.controls(SimulationSpeed.REALTIME, true)
        val pending = demand.pendingSeconds
        val unserved = demand.unservedSeconds
        demand.request(60.0)
        assertEquals(pending, demand.pendingSeconds)
        assertEquals(unserved, demand.unservedSeconds)
        assertTrue(pending <= 40.0)
    }

    @Test
    fun ownerCommitsIdenticalDetailedTransfersAndPublishesImmutableViews() {
        val owner = WorldSimulation(request)
        fun capture(refreshWater: Boolean = true) = owner.snapshot(SimulationProgress(1, 1, 0, owner.completedSeconds,
            SimulationSpeed.ONE_HOUR, 0.0, 0.0, 0.0, 920.0, false, 0.0, 0.0, 0), refreshDrainage = refreshWater)
        val initial = capture()
        val initialVapor = initial.weather.atmosphericWaterMm(3, 3)
        val directWorld = World(request.seed, request.width, request.height)

        val direct = DynamicalWeather(directWorld, request.atmosphere)
        repeat(4320) {
            assertEquals(20.0, owner.advance(20.0, SimulationSpeed.ONE_HOUR))
            val advanced = direct.advanceBudgeted(20.0, 1)

        }
        val heldWater = capture(refreshWater = false)
        assertTrue(heldWater.world === initial.world)
        assertTrue(heldWater.drainage === initial.drainage)
        assertTrue(heldWater.surfaceWater === initial.surfaceWater)
        assertEquals(0.0, heldWater.drainageCompletedSeconds)
        val final = capture()
        assertTrue(final.weather === heldWater.weather)
        assertTrue(final.surfaceWater !== initial.surfaceWater)
        assertEquals(final.progress.completedSeconds, final.drainageCompletedSeconds)
        assertTrue(capture().surfaceWater === final.surfaceWater)
        assertEquals(86400.0, final.progress.completedSeconds)
        assertTrue(final.drainage.revision > initial.drainage.revision)
        assertEquals(1L, initial.drainage.revision)
        assertEquals(final.progress.completedSeconds, final.weather.elapsedSeconds)
        fun fields(model: WeatherModel): List<(Int, Int) -> Double> = listOf(
            model::relativeHumidity, model::cloudCover, model::precipitationMmPerHour,
            model::peakSubstepPrecipitationMmPerHour, model::rainMmThisUpdate, model::drainageMmThisUpdate,
            model::overflowMmThisUpdate,
            model::thirtyDayRainMmPerHour, model::annualizedRainMmPerYear, model::solarInputFactor,
            model::surfaceTemperatureC, model::airTemperatureC, model::windUTilesPerDay, model::windVTilesPerDay,
            model::nightOpacity, model::atmosphericWaterMm, model::cloudWaterMm, model::soilWaterMm, model::soilMoisture)
        val expected = fields(direct)
        val published = fields(final.weather)
        for (field in expected.indices) for (y in 0 until request.height) for (x in 0 until request.width)
            assertEquals(expected[field](x, y), published[field](x, y), "Field $field at $x,$y")
        assertEquals(initialVapor, initial.weather.atmosphericWaterMm(3, 3))
        assertEquals(0.0, initial.weather.elapsedSeconds)
        assertTrue(initial.weather.presentationIdentity === final.weather.presentationIdentity)
        assertTrue(abs(final.diagnostics!!.relativeWaterResidual) < 1e-10)
        assertTrue(abs(final.diagnostics.unexplainedEnergyResidualJoules / final.diagnostics.totalModelEnergyJoules) < 1e-10)
        assertFailsWith<IllegalStateException> { final.weather.step(20.0) }

    }

    @Test
    fun workerAcknowledgesPauseCoalescesRegenerationAndStopsSafely() {
        val worker = SimulationWorker(request)
        try {
            val pause = worker.updateControls(SimulationSpeed.ONE_DAY, true)
            await { worker.latestSnapshot()?.progress?.let { it.paused && it.appliedControlRevision >= pause } == true }
            val frozen = worker.latestSnapshot()!!
            Thread.sleep(30)
            assertEquals(frozen.progress.completedSeconds, worker.latestSnapshot()!!.progress.completedSeconds)
            worker.setCloudsVisible(true)
            await { worker.latestSnapshot()?.clouds != null }
            assertEquals(frozen.progress.completedSeconds, worker.latestSnapshot()!!.progress.completedSeconds)
            val pausedClouds = worker.latestSnapshot()!!.clouds
            worker.setCloudsVisible(false)
            await { worker.latestSnapshot()?.clouds == null }
            assertTrue(pausedClouds != null)
            worker.updateControls(SimulationSpeed.ONE_DAY, false)
            await { (worker.latestSnapshot()?.progress?.completedSeconds ?: 0.0) > frozen.progress.completedSeconds }
            val paused = worker.updateControls(SimulationSpeed.ONE_HOUR, true)
            await { worker.latestSnapshot()?.progress?.appliedControlRevision == paused }
            worker.regenerate(request.copy(generation = 2, seed = 42))
            worker.regenerate(request.copy(generation = 3, seed = 43))
            await { worker.latestSnapshot()?.progress?.generation == 3L }
            val regenerated = worker.latestSnapshot()!!
            assertEquals(43, regenerated.world.seed)
            assertEquals(0.0, regenerated.progress.completedSeconds)
            assertTrue(regenerated.progress.paused)
            Thread.sleep(30)
            assertEquals(3L, worker.latestSnapshot()!!.progress.generation)
            assertEquals(request.seed, frozen.world.seed)
            assertEquals(frozen.progress.completedSeconds, frozen.weather.elapsedSeconds)
            assertEquals(null, worker.failure)
        } finally { worker.close() }
        await { !worker.isRunning }
        assertFalse(worker.isRunning)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue(condition(), "Worker did not complete the bounded operation")
    }
}
