package terra.simulation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import terra.climate.YEAR_SECONDS

actual class SimulationWorker actual constructor(request: WorldRequest) {
    private data class Controls(val speed: SimulationSpeed, val paused: Boolean)
    private val running = AtomicBoolean(true)
    private val desiredWorld = AtomicReference(request)
    private val controls = AtomicReference(Controls(SimulationSpeed.ONE_YEAR, false))
    private val mailbox = AtomicReference<WorldPresentationSnapshot?>()
    private val error = AtomicReference<String?>()
    private val wake = Object()

    init { thread(name = "terra-simulation", isDaemon = true) { runSimulation() } }

    actual val failure: String? get() = error.get()
    actual fun latestSnapshot(): WorldPresentationSnapshot? = mailbox.get()
    actual fun updateControls(speed: SimulationSpeed, paused: Boolean) {
        controls.set(Controls(speed, paused)); signal()
    }
    actual fun regenerate(request: WorldRequest) {
        desiredWorld.set(request); error.set(null); signal()
    }
    actual fun close() { running.set(false); signal() }
    private fun signal() { synchronized(wake) { wake.notifyAll() } }

    private fun runSimulation() {
        var owner: WorldSimulation? = null
        var active = controls.get()
        var pending = 0.0
        var previousWall = System.nanoTime()
        var publishedAt = 0L
        var rateStarted = previousWall
        var rateStartSim = 0.0
        var achieved = 0.0
        var failedGeneration = -1L
        while (running.get()) {
            val requested = desiredWorld.get()
            try {
                if (requested.generation != failedGeneration) {
                    var changed = false
                    if (owner?.request != requested) {
                        owner = WorldSimulation(requested)
                        pending = 0.0; achieved = 0.0; rateStartSim = 0.0
                        previousWall = System.nanoTime(); rateStarted = previousWall
                        changed = true
                    }
                    val simulation = owner!!
                    val now = System.nanoTime()
                    val command = controls.get()
                    // Max runs annual steps continuously; fixed speeds follow the wall clock.
                    val unlimited = command.speed == SimulationSpeed.MAX
                    val quantum = if (unlimited) YEAR_SECONDS else
                        (command.speed.simulatedSecondsPerRealSecond * 0.05).coerceIn(1.0, YEAR_SECONDS)
                    val limit = quantum * 20
                    if (!active.paused && active.speed != SimulationSpeed.MAX) pending = minOf(limit, pending + (now - previousWall) / 1e9 * active.speed.simulatedSecondsPerRealSecond)
                    previousWall = now
                    if (command != active) {
                        active = command; pending = 0.0; achieved = 0.0
                        rateStarted = now; rateStartSim = simulation.completedSeconds
                        changed = true
                    }
                    if (!active.paused && (unlimited || pending >= quantum)) {
                        simulation.advance(quantum)
                        if (!unlimited) pending -= quantum
                    }
                    val finished = System.nanoTime()
                    if (finished - rateStarted >= 1_000_000_000L) {
                        achieved = (simulation.completedSeconds - rateStartSim) / ((finished - rateStarted) / 1e9)
                        rateStarted = finished; rateStartSim = simulation.completedSeconds
                    }
                    if ((changed || finished - publishedAt >= 200_000_000L) && desiredWorld.get() == requested && running.get()) {
                        val progress = SimulationProgress(requested.generation, simulation.completedSeconds,
                            if (active.paused) 0.0 else achieved, pending, active.paused, quantum,
                            !active.paused && !unlimited && achieved > 0 && achieved < active.speed.simulatedSecondsPerRealSecond * 0.9)
                        val previous = mailbox.get()
                        mailbox.set(if (!changed && previous?.progress?.completedSeconds == simulation.completedSeconds)
                            previous.copy(progress = progress) else simulation.snapshot(progress))
                        publishedAt = finished
                    }
                }
            } catch (failure: Exception) {
                if (desiredWorld.get() == requested) error.set(failure.message ?: failure.toString())
                failedGeneration = requested.generation
            }
            val command = controls.get()
            if (command.speed != SimulationSpeed.MAX || command.paused || desiredWorld.get().generation == failedGeneration) {
                synchronized(wake) { if (running.get()) wake.wait(5) }
            }
        }
    }
}
