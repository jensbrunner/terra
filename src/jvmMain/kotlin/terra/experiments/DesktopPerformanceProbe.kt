package terra.experiments

import java.awt.GraphicsEnvironment
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.ceil
import kotlin.system.exitProcess
import terra.simulation.PerformanceProbe
import terra.simulation.ProfileSettings
import terra.simulation.SimulationSpeed
import terra.simulation.SimulationTimings
import terra.simulation.SimulationWork
import terra.simulation.SimulationProgress

/** A short, opt-in live baseline, not a benchmark suite or a gameplay scheduler. */
class DesktopPerformanceProbe private constructor(environment: String) : PerformanceProbe {
    override val timings = SimulationTimings(System::nanoTime)
    private val output = File(System.getProperty("terra.profile.output", "build/experiments/phase-7/baseline")).apply { mkdirs() }
    private val seconds = System.getProperty("terra.profile.seconds", "2.0").toDouble()
    private val warmup = System.getProperty("terra.profile.warmup", "0.5").toDouble()
    private val selectedSpeeds = System.getProperty("terra.profile.speeds", "").takeIf { it.isNotBlank() }
        ?.split(',')?.map { SimulationSpeed.valueOf(it.trim()) } ?: SimulationSpeed.entries
    private val selectedClouds = System.getProperty("terra.profile.clouds", "").takeIf { it.isNotBlank() }
        ?.let { listOf(it.toBooleanStrict()) } ?: listOf(false, true)
    private val cases = selectedSpeeds.flatMap { speed -> selectedClouds.map { ProfileSettings(speed, it, System.getProperty("terra.profile.dayNight", "false").toBooleanStrict()) } }
    private val frames = DoubleArray(10000)
    private var frameCount = 0
    private var caseIndex = -1
    private var caseStarted = 0L
    private var measuredStarted = 0L
    private var previousFrame = 0L
    private var startSim = 0.0
    private var startPending = 0.0
    private var offered = 0.0
    private var latestWorker: SimulationProgress? = null
    private var workerAtStart: SimulationProgress? = null
    override fun workerProgress(progress: SimulationProgress) { latestWorker = progress }
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    private fun gcCount() = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
    private fun gcMillis() = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }
    private fun heapBytes() = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    private var startGcCount = 0L
    private var startGcMillis = 0L
    private var startHeap = 0L
    private val report = File(output, "trials.csv").bufferedWriter()

    init {
        require(seconds.isFinite() && seconds >= 1 && seconds <= 30 && warmup.isFinite() && warmup >= 0 && warmup <= 10)
        val mode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
        File(output, "environment.txt").writeText(
            "os=${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}\n" +
                "java=${System.getProperty("java.runtime.version")}\nprocessors=${Runtime.getRuntime().availableProcessors()}\n" +
                "display=${mode.width}x${mode.height}; refresh_hz=${mode.refreshRate} (0 means unknown)\n$environment\n" +
                "trial_seconds=$seconds; warmup_seconds=$warmup; cases=${cases.size}; day_night_overlay=${cases.first().dayNight}; one trial per case\n" +
                "Frames are monotonic updater-to-updater intervals, including terminal stalls; not a GPU-present counter.\n" +
                "Physics/world state persists across cases; resetting unprocessed requested backlog per case is reported as unserved demand.\n" +
                "No camera interaction. Short exploratory trials; JIT/order/thermal effects are not controlled.\n" +
                "Stage times are render-thread wall time; FRAME_UPDATE includes other render stages. GPU/upload time is not isolated.\nGC uses JVM aggregate counters; heap endpoints are occupancy, not allocation or retained-size measurements.\n" +
                "backend=single simulation worker; worker totals come from immutable publications. Render offered-time column is diagnostic, not worker demand.\n")
        report.write("speed,clouds,frames,wall_seconds,completed_sim_seconds,achieved_days_per_second,requested_days_per_second,offered_sim_seconds,pending_start,pending_end,p50_ms,p95_ms,p99_ms,max_ms")
        for (work in SimulationWork.entries) report.write(",${work.name.lowercase()}_total_ms,${work.name.lowercase()}_calls,${work.name.lowercase()}_max_ms")
        report.write(",worker_advance_ms,worker_publication_ms,worker_transactions,unserved_requested_seconds,rate_limited,automatic_fidelity,quantum_seconds,coarsened_for_load,snapshot_publications,gc_collections,gc_ms,heap_start_bytes,heap_end_bytes")
        report.newLine()
    }

    override fun beforeFrame(completedSeconds: Double, pendingSeconds: Double): ProfileSettings? {
        val now = System.nanoTime()
        if (caseIndex < 0) return nextCase(now)
        if (measuredStarted == 0L) {
            if ((now - caseStarted) / 1e9 >= warmup) {
                measuredStarted = now
                startSim = completedSeconds
                startPending = pendingSeconds
                offered = 0.0
                workerAtStart = latestWorker
                startGcCount = gcCount(); startGcMillis = gcMillis(); startHeap = heapBytes()
                frameCount = 0
                timings.clear()
            }
        } else {
            check(frameCount < frames.size) { "Profile frame capacity exceeded; trial is invalid" }
            frames[frameCount++] = (now - previousFrame) / 1e6
            val elapsed = (now - measuredStarted) / 1e9
            if (elapsed >= seconds) {
                val settings = cases[caseIndex]
                val sorted = frames.copyOf(frameCount).apply { sort() }
                fun percentile(p: Double) = sorted[(ceil(p * frameCount).toInt() - 1).coerceIn(0, frameCount - 1)]
                val completed = completedSeconds - startSim
                report.write("${settings.speed.name},${settings.clouds},$frameCount,$elapsed,$completed,${completed / elapsed / 86400}," +
                    "${settings.speed.simulatedSecondsPerRealSecond / 86400},$offered,$startPending,$pendingSeconds," +
                    "${percentile(0.5)},${percentile(0.95)},${percentile(0.99)},${sorted.last()}")
                for (work in SimulationWork.entries) {
                    val i = work.ordinal
                    report.write(",${timings.totals[i] / 1e6},${timings.counts[i]},${timings.maxima[i] / 1e6}")
                }
                val worker = latestWorker
                val start = workerAtStart
                report.write(",${((worker?.advanceWallSeconds ?: 0.0) - (start?.advanceWallSeconds ?: 0.0)) * 1000}," +
                    "${((worker?.publicationWallSeconds ?: 0.0) - (start?.publicationWallSeconds ?: 0.0)) * 1000}," +
                    "${(worker?.completedTransactions ?: 0) - (start?.completedTransactions ?: 0)}," +
                    "${(worker?.unservedRequestedSeconds ?: 0.0) - (start?.unservedRequestedSeconds ?: 0.0)},${worker?.rateLimited ?: false},${worker?.automaticFidelity ?: false},${worker?.quantumSeconds ?: 0.0},${worker?.coarsenedForLoad ?: false}")
                report.write(",${(worker?.revision ?: 0) - (start?.revision ?: 0)},${gcCount() - startGcCount},${gcMillis() - startGcMillis},$startHeap,${heapBytes()}")
                report.newLine(); report.flush()
                println("PROFILE ${settings.speed.name} clouds=${settings.clouds}: p95=%.2fms, p99=%.2fms, achieved=%.4f days/s".format(
                    percentile(0.95), percentile(0.99), completed / elapsed / 86400))
                return nextCase(now)
            }
        }
        previousFrame = now
        return null
    }

    private fun nextCase(now: Long): ProfileSettings {
        caseIndex++
        if (caseIndex == cases.size) {
            report.close()
            File(output, "COMPLETE.txt").writeText("${cases.size} short live trials completed.\n")
            println("Profile complete: ${output.absolutePath}")
            exitProcess(0) // Only the explicitly launched profiling game JVM.
        }
        caseStarted = now
        measuredStarted = 0L
        previousFrame = now
        return cases[caseIndex]
    }

    override fun afterFrame(offeredSeconds: Double) {
        if (measuredStarted != 0L) offered += offeredSeconds
    }

    companion object {
        fun fromProperties(environment: String): DesktopPerformanceProbe? =
            if (System.getProperty("terra.profile", "false").toBoolean()) DesktopPerformanceProbe(environment) else null
    }
}
