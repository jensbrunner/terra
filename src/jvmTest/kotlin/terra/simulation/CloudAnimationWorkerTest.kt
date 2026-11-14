package terra.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig

class CloudAnimationWorkerTest {
    @Test
    fun detailedRealtimeTexturePublishesBetweenStepsWithoutAdvancingTimeOrMovingWhilePaused() {
        val worker = SimulationWorker(WorldRequest(generation = 1, width = 48, height = 36,
            atmosphere = DryAtmosphereConfig(width = 12, height = 8)))
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue(condition(), "Detailed cloud publication failed: ${worker.failure}")
        }
        try {
            worker.setCloudsVisible(true)
            worker.setFidelity(SimulationFidelity.DETAILED)
            val revision = worker.updateControls(SimulationSpeed.REALTIME, false, resetPending = true)
            await { worker.latestSnapshot()?.progress?.appliedControlRevision == revision }
            val before = worker.latestSnapshot()!!
            await { worker.latestSnapshot()?.clouds !== before.clouds }
            assertEquals(before.progress.completedSeconds, worker.latestSnapshot()!!.progress.completedSeconds)
            val paused = worker.updateControls(SimulationSpeed.REALTIME, true)
            await { worker.latestSnapshot()?.progress?.appliedControlRevision == paused }
            val frozen = worker.latestSnapshot()!!
            Thread.sleep(240)
            assertTrue(frozen.clouds === worker.latestSnapshot()!!.clouds)
            assertEquals(frozen.progress.completedSeconds, worker.latestSnapshot()!!.progress.completedSeconds)
        } finally { worker.close() }
        await { !worker.isRunning }
    }
    @Test
    fun climateCloudsAnimateBetweenCommitsAndFreezeWhenPaused() {
        val worker = SimulationWorker(WorldRequest(generation = 1, width = 48, height = 36,
            atmosphere = DryAtmosphereConfig(width = 12, height = 8)))
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue(condition(), "Worker did not publish: ${worker.failure}")
        }
        try {
            worker.setCloudsVisible(true)
            worker.setFidelity(SimulationFidelity.CLIMATE)
            worker.updateControls(SimulationSpeed.ONE_YEAR, false)
            await { (worker.latestSnapshot()?.progress?.completedSeconds ?: 0.0) > 0.0 }
            val revision = worker.updateControls(SimulationSpeed.REALTIME, false, resetPending = true)
            await { worker.latestSnapshot()?.progress?.appliedControlRevision == revision }
            val before = worker.latestSnapshot()!!
            await { worker.latestSnapshot()?.clouds !== before.clouds }
            val after = worker.latestSnapshot()!!
            assertEquals(before.progress.completedSeconds, after.progress.completedSeconds)
            assertEquals(before.weather.elapsedSeconds, after.weather.elapsedSeconds)
            val paused = worker.updateControls(SimulationSpeed.REALTIME, true)
            await { worker.latestSnapshot()?.progress?.appliedControlRevision == paused }
            val frozen = worker.latestSnapshot()!!
            Thread.sleep(240)
            assertTrue(frozen.clouds === worker.latestSnapshot()!!.clouds)
            assertEquals(frozen.progress.completedSeconds, worker.latestSnapshot()!!.progress.completedSeconds)
            assertEquals(null, worker.failure)
        } finally { worker.close() }
        await { !worker.isRunning }
    }
}
