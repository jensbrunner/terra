package terra.simulation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import terra.climate.ClimateConfig
import terra.climate.ClimateSimulation
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

    private val request = WorldRequest(generation = 1, width = 15, height = 11, climate = ClimateConfig(width = 8, height = 6))

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
    fun ownerPublishesImmutableViewsAndMatchesSplitEvolution() {
        val owner = WorldSimulation(request)
        fun capture() = owner.snapshot(SimulationProgress(1, 1, 0, owner.completedSeconds,
            SimulationSpeed.ONE_YEAR, 0.0, 0.0, 0.0, 920.0, false, 0.0, 0.0, 0))
        val initial = capture()
        val initialSoil = initial.weather.soilWaterMm(3, 3)
        val directWorld = World(request.seed, request.width, request.height)
        val direct = ClimateSimulation(directWorld, request.climate)
        val year = request.solar.yearSeconds
        assertTrue(abs(owner.advance(3 * year).relativeWaterResidual) < 1e-10)
        for (speed in SimulationSpeed.entries) {
            val span = year / SimulationSpeed.entries.size
            val batch = (speed.simulatedSecondsPerRealSecond * 0.05).coerceIn(1.0, span)
            // Bound test work at realtime while retaining irregular monthly partitions.
            var remaining = 3 * span
            while (remaining > 1e-6) {
                val seconds = minOf(remaining, maxOf(batch, 12345.0))
                assertTrue(abs(direct.advance(seconds).relativeWaterResidual) < 1e-10)
                remaining -= seconds
            }
        }
        val final = capture()
        val expected = direct.snapshot()
        for (y in 0 until request.height) for (x in 0 until request.width) {
            assertEquals(expected.soilWaterMm(x, y), final.weather.soilWaterMm(x, y), 1e-8)
            assertEquals(expected.snowWaterMm(x, y), final.weather.snowWaterMm(x, y), 1e-8)
            assertEquals(directWorld.treeCover(x, y), final.world.treeCover(x, y), 1e-10)
            assertEquals(direct.surfaceState.surfaceWater.groundwaterMmAt(y * request.width + x),
                final.drainage.groundwaterMm(x, y), 1e-8)
        }
        assertEquals(initialSoil, initial.weather.soilWaterMm(3,3))
        assertEquals(0.0, initial.weather.elapsedSeconds)
        assertTrue(capture().weather === final.weather)
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
