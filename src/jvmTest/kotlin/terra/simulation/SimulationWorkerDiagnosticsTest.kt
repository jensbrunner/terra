package terra.simulation

import java.io.File
import kotlin.test.*
import terra.climate.YEAR_SECONDS

/** Opt-in short throughput windows also exercise pause, live settings, regeneration and snapshots. */
class SimulationWorkerDiagnosticsTest {
    private fun awaitState(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "Worker did not acknowledge its controls")
            Thread.sleep(10)
        }
    }

    @Test fun workerThroughputAndControls() {
        val output = System.getenv("TERRA_WORKER_DIAGNOSTICS") ?: return
        val file = File(output)
        file.writeText("default seed=31742 size=283x212 multiplier=10000 interval=100; three 3-second Max windows\n")
        repeat(3) { trial ->
            val worker = SimulationWorker(WorldRequest(generation = trial.toLong()))
            try {
                worker.updateControls(SimulationSpeed.MAX, false)
                awaitState { worker.latestSnapshot() != null || worker.failure != null }
                assertNull(worker.failure)
                val retained = worker.latestSnapshot()!!
                val cell = retained.world[0, 0]; val owner = retained.world.plateId(0, 0)
                val begin = System.nanoTime()
                val initial = retained.progress.completedSeconds
                Thread.sleep(3_000)
                worker.updateControls(SimulationSpeed.MAX, true)
                awaitState { worker.latestSnapshot()?.progress?.paused == true || worker.failure != null }
                assertNull(worker.failure)
                val frozen = worker.latestSnapshot()!!
                val seconds = (System.nanoTime() - begin) / 1e9
                val rate = (frozen.progress.completedSeconds - initial) / YEAR_SECONDS / seconds
                file.appendText("trial=$trial calendarYearsPerSecond=$rate effectiveMyr=${frozen.geology.effectiveYears / 1e6} windowSeconds=$seconds\n")
                val settings = GeologySettings(6_000, 50)
                worker.updateGeology(settings)
                awaitState { worker.latestSnapshot()?.geology?.settings == settings }
                Thread.sleep(50)
                assertEquals(frozen.progress.completedSeconds, worker.latestSnapshot()!!.progress.completedSeconds)
                val request = WorldRequest(generation = 100L + trial, width = 36, height = 26)
                worker.regenerate(request)
                awaitState { worker.latestSnapshot()?.progress?.generation == request.generation }
                assertEquals(settings, worker.latestSnapshot()!!.geology.settings)
                assertEquals(0.0, worker.latestSnapshot()!!.progress.completedSeconds)
                worker.updateControls(SimulationSpeed.MAX, false)
                awaitState { (worker.latestSnapshot()?.geology?.effectiveYears ?: 0.0) > 0 || worker.failure != null }
                assertNull(worker.failure)
                assertEquals(cell, retained.world[0, 0]); assertEquals(owner, retained.world.plateId(0, 0))
            } finally {
                worker.close()
                awaitState { Thread.getAllStackTraces().keys.none { it.name == "terra-simulation" && it.isAlive } }
            }
        }
    }
}
