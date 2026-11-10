package terra.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveFidelityTest {
    @Test
    fun measuredCostHysteresisManualOverrideAndOverloadCoarseningAreBounded() {
        val policy = AdaptiveFidelity(20.0)
        assertEquals(SimulationFidelity.DETAILED, policy.decide(SimulationSpeed.ONE_HOUR, null, 0.0).mode)
        repeat(8) { policy.observe(SimulationFidelity.DETAILED, 0.02, it * 0.05) }
        assertEquals(SimulationFidelity.DETAILED, policy.decide(SimulationSpeed.ONE_HOUR, null, 0.5).mode)
        assertEquals(SimulationFidelity.CLIMATE, policy.decide(SimulationSpeed.ONE_HOUR, null, 0.8).mode)
        assertEquals(SimulationFidelity.CLIMATE, policy.decide(SimulationSpeed.REALTIME, null, 0.9).mode)
        assertEquals(SimulationFidelity.DETAILED, policy.decide(SimulationSpeed.REALTIME, null, 1.0, true).mode)
        assertEquals(SimulationFidelity.DETAILED, policy.decide(SimulationSpeed.TEN_YEARS, SimulationFidelity.DETAILED, 1.1).mode)
        policy.decide(SimulationSpeed.ONE_YEAR, null, 2.0)
        val normal = policy.decide(SimulationSpeed.ONE_YEAR, null, 2.3)
        assertEquals(SimulationFidelity.CLIMATE, normal.mode)
        policy.observe(SimulationFidelity.CLIMATE, 0.8, 2.4)
        val overloaded = policy.decide(SimulationSpeed.ONE_YEAR, null, 2.5)
        assertTrue(overloaded.coarsenedForLoad)
        assertTrue(overloaded.quantumSeconds > normal.quantumSeconds)
        assertTrue(overloaded.quantumSeconds <= 20 * 365 * 86400.0)
        assertTrue(AdaptiveFidelity.baseQuantum(SimulationSpeed.TEN_YEARS) > AdaptiveFidelity.baseQuantum(SimulationSpeed.ONE_YEAR))
    }

    @Test
    fun automaticWorkerReachesYearRateClimateAndReturnsOnExplicitLowSpeed() {
        val worker = SimulationWorker(WorldRequest(generation = 1, width = 15, height = 11, dynamical = true,
            atmosphere = terra.atmosphere.DryAtmosphereConfig(width = 8, height = 6)))
        try {
            worker.updateControls(SimulationSpeed.ONE_YEAR, false)
            await { worker.latestSnapshot()?.let { it.fidelity == SimulationFidelity.CLIMATE && it.progress.completedSeconds >= 86400.0 } == true }
            val shown = worker.latestSnapshot()!!
            assertTrue(shown.progress.automaticFidelity)
            assertTrue(shown.progress.quantumSeconds >= 86400.0)
            val pause = worker.updateControls(SimulationSpeed.ONE_HOUR, true)
            await { worker.latestSnapshot()?.progress?.appliedControlRevision == pause }
            val automatic = worker.useAutomaticFidelity()
            await { worker.latestSnapshot()?.let { it.progress.appliedControlRevision == automatic && it.fidelity == SimulationFidelity.DETAILED } == true }
            assertEquals(1L, worker.latestSnapshot()!!.progress.generation)
            assertEquals(null, worker.failure)
        } finally { worker.close() }
        await { !worker.isRunning }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue(condition(), "Adaptive worker did not reach the bounded requested state")
    }
}
