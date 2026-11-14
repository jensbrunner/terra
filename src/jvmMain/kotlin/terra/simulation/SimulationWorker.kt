package terra.simulation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import terra.render.CloudRenderer

actual class SimulationWorker actual constructor(request: WorldRequest) {
    private data class Controls(val revision: Long, val speed: SimulationSpeed, val paused: Boolean, val resetPending: Boolean = false, val fidelity: SimulationFidelity = SimulationFidelity.DETAILED, val automatic: Boolean = true)
    private data class WorkerFailure(val generation: Long, val message: String)
    private val running = AtomicBoolean(true)
    private val desiredWorld = AtomicReference(request)
    private val desiredControls = AtomicReference(Controls(0, SimulationSpeed.ONE_HOUR, false))
    private val cloudsVisible = AtomicBoolean(false)
    private val mailbox = AtomicReference<WorldPresentationSnapshot?>()
    private val error = AtomicReference<WorkerFailure?>()
    private val wake = Object()
    private val worker = thread(name = "terra-simulation", isDaemon = true) { runSimulation() }

    actual val failure: String? get() = error.get()?.takeIf { it.generation == desiredWorld.get().generation }?.message
    actual val isRunning: Boolean get() = worker.isAlive
    actual fun latestSnapshot(): WorldPresentationSnapshot? = mailbox.get()
    actual fun updateControls(speed: SimulationSpeed, paused: Boolean, resetPending: Boolean): Long {
        val revision = desiredControls.updateAndGet {
            it.copy(revision = it.revision + 1, speed = speed, paused = paused, resetPending = resetPending)
        }.revision
        signal()
        return revision
    }
    actual fun setFidelity(fidelity: SimulationFidelity): Long {
        val revision = desiredControls.updateAndGet {
            it.copy(revision = it.revision + 1, fidelity = fidelity, resetPending = false, automatic = false)
        }.revision
        signal()
        return revision
    }
    actual fun useAutomaticFidelity(): Long {
        val revision = desiredControls.updateAndGet {
            it.copy(revision = it.revision + 1, automatic = true, resetPending = false)
        }.revision
        signal()
        return revision
    }
    actual fun setCloudsVisible(visible: Boolean) {
        if (cloudsVisible.getAndSet(visible) != visible) signal()
    }
    actual fun regenerate(request: WorldRequest) {
        while (true) {
            val current = desiredWorld.get()
            require(request.generation > current.generation)
            if (desiredWorld.compareAndSet(current, request)) break
        }
        error.set(null)
        signal()
    }
    private fun signal() { synchronized(wake) { wake.notifyAll() } }
    actual fun close() { running.set(false); signal() }
    private fun idle(milliseconds: Long = 5) { synchronized(wake) { if (running.get()) wake.wait(milliseconds) } }

    private fun runSimulation() {
        var owner: WorldSimulation? = null
        var demand = SimulationDemand(20.0)
        var policy = AdaptiveFidelity(20.0)
        var nextClimateAt = 0L
        var active = desiredControls.get()
        var failedGeneration = -1L
        var revision = 0L
        var previousWall = System.nanoTime()
        var publishedAt = 0L
        var publishedClouds = false
        var rateStarted = previousWall
        var rateStartSim = 0.0
        var achieved = 0.0
        var advanceNanos = 0L
        var publicationNanos = 0L
        var transactions = 0L
        while (running.get()) {
            val requested = desiredWorld.get()
            if (requested.generation == failedGeneration) { idle(); continue }
            try {
                var forcePublish = false
                if (owner?.request?.generation != requested.generation) {
                    val created = WorldSimulation(requested)
                    if (!running.get()) break
                    if (desiredWorld.get().generation != requested.generation) continue
                    owner = created
                    demand = SimulationDemand(created.stepSeconds)
                    active = desiredControls.get()
                    policy = AdaptiveFidelity(requested.atmosphere.stepSeconds)
                    nextClimateAt = 0L
                    demand.controls(active.speed, active.paused, stepSeconds = created.stepSeconds)
                    previousWall = System.nanoTime()
                    rateStarted = previousWall; rateStartSim = 0.0; achieved = 0.0
                    publishedAt = 0L; revision = 0L
                    advanceNanos = 0L; publicationNanos = 0L; transactions = 0L
                    forcePublish = true
                }
                val simulation = owner!!
                val now = System.nanoTime()
                val command = desiredControls.get()
                // Account elapsed work under the controls that were actually active.
                demand.request((now - previousWall) / 1e9)
                previousWall = now
                var speedChanged = false
                var resetPending = false
                if (command.revision != active.revision) {
                    speedChanged = command.speed != active.speed || command.automatic && !active.automatic
                    active = command
                    resetPending = active.resetPending
                    rateStarted = now; rateStartSim = simulation.completedSeconds; achieved = 0.0
                    forcePublish = true
                }
                val decision = policy.decide(active.speed,
                    if (active.automatic) null else active.fidelity, now / 1e9, speedChanged)
                if (decision.mode != simulation.fidelity) {
                    simulation.setFidelity(decision.mode)
                    rateStarted = now; rateStartSim = simulation.completedSeconds; achieved = 0.0
                    forcePublish = true
                }
                if (decision.mode == SimulationFidelity.CLIMATE) simulation.setClimateQuantum(decision.quantumSeconds)
                demand.controls(active.speed, active.paused, resetPending, simulation.stepSeconds)
                val deadline = now + 5_000_000L
                var work = 0
                while (running.get() && !active.paused && work < 4 && System.nanoTime() < deadline &&
                    desiredWorld.get().generation == requested.generation && desiredControls.get().revision == active.revision &&
                    (simulation.fidelity != SimulationFidelity.CLIMATE || System.nanoTime() >= nextClimateAt) &&
                    demand.pendingSeconds >= simulation.quantum(active.speed)) {
                    val started = System.nanoTime()
                    val consumed = simulation.advance(demand.pendingSeconds, active.speed)
                    demand.consume(consumed)
                    val finishedWork = System.nanoTime()
                    val cost = finishedWork - started
                    advanceNanos += cost
                    policy.observe(simulation.fidelity, cost / 1e9, finishedWork / 1e9)
                    if (simulation.fidelity == SimulationFidelity.CLIMATE) nextClimateAt = started + 50_000_000L
                    transactions++
                    work++
                }
                val finished = System.nanoTime()
                val showClouds = cloudsVisible.get()
                if (showClouds != publishedClouds) forcePublish = true
                val rateWindow = maxOf(0.5, minOf(20.0, simulation.stepSeconds / active.speed.simulatedSecondsPerRealSecond))
                if ((finished - rateStarted) / 1e9 >= rateWindow) {
                    achieved = if (active.paused) 0.0 else
                        (simulation.completedSeconds - rateStartSim) / ((finished - rateStarted) / 1e9)
                    rateStarted = finished; rateStartSim = simulation.completedSeconds
                }
                val publicationPeriod = if (showClouds && !active.paused)
                    (CloudRenderer.REFRESH_SECONDS * 1e9).toLong() else 200_000_000L
                if (running.get() && (forcePublish || finished - publishedAt >= publicationPeriod) && desiredWorld.get().generation == requested.generation) {
                    val progress = SimulationProgress(requested.generation, ++revision, active.revision,
                        simulation.completedSeconds, active.speed, if (active.paused) 0.0 else achieved,
                        demand.pendingSeconds, demand.unservedSeconds, demand.limitSeconds, active.paused,
                        advanceNanos / 1e9, publicationNanos / 1e9, transactions,
                        active.automatic, simulation.quantum(active.speed))
                    val started = System.nanoTime()
                    val previous = mailbox.get()
                    val snapshot = if (previous?.progress?.generation == requested.generation &&
                        previous.progress.completedSeconds == simulation.completedSeconds && previous.progress.paused == active.paused && previous.fidelityRevision == simulation.fidelityRevision && showClouds == publishedClouds &&
                        !(showClouds && !active.paused))
                        previous.copy(progress = progress)
                    else {
                        simulation.snapshot(progress, cloudsVisible = showClouds,
                            visualElapsedSeconds = if (publishedAt == 0L) 0.0 else ((finished - publishedAt) / 1e9).coerceIn(0.0, 0.25))
                    }
                    publishedClouds = showClouds
                    publicationNanos += System.nanoTime() - started
                    if (running.get() && desiredWorld.get().generation == requested.generation) mailbox.set(snapshot.copy(progress = progress.copy(publicationWallSeconds = publicationNanos / 1e9)))
                    // Count synthesis time in the cadence and visual elapsed time.
                    publishedAt = finished
                }
                idle(if (work == 0 || active.paused) 5 else 1)
            } catch (failure: Throwable) {
                // Never retry a partly failed physical transaction or publish its mutable state.
                error.set(WorkerFailure(requested.generation, failure.message ?: failure.toString()))
                failedGeneration = requested.generation
            }
        }
    }
}
