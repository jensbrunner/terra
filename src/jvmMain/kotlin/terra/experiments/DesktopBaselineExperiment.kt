package terra.experiments

import java.io.File
import kotlin.math.max
import kotlin.system.exitProcess
import terra.simulation.SimulationSpeed
import terra.ui.AtlasExperiment
import terra.ui.AtlasWork

/** Enabled only by -Pbaseline=true; automation finishes and closes its own JVM after exporting. */
class DesktopBaselineExperiment private constructor(
    private val output: File,
    private val warmupSeconds: Double,
    private val trialSeconds: Double,
    private val trials: Int,
    private val phases: Boolean,
    private val speeds: List<SimulationSpeed>
) : AtlasExperiment {
    private enum class Stage { BOOTSTRAP, WARMUP, TRIAL }
    private var stage = Stage.BOOTSTRAP
    private var speedIndex = 0
    private var trial = 1
    private var phaseStart = 0L
    private var previousFrameStart = 0L
    private var bootstrapStart = 0L
    private var readingStart: JvmReading? = null
    private var pendingStart = 0.0
    private var completed = 0.0
    private var offered = 0.0
    private var calls = 0
    private var peakHeap = 0L
    private var rssStart = -1L
    private var nextHeapSample = 0L
    private val workStart = LongArray(AtlasWork.entries.size)
    private val workNanos = LongArray(AtlasWork.entries.size)
    private val frames = FrameSamples()
    private val probe = JvmProbe()
    private val results = mutableListOf<TrialResult>()
    private val schedule = DoubleArray(131_072)
    private var scheduleSize = 0
    @Volatile private var complete = false
    private val speed get() = speeds[speedIndex]

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            if (!complete) File(output, "INCOMPLETE.txt").writeText(
                "Desktop process exited before automatic completion. trials.csv contains completed trials only.\n"
            )
        }, "baseline-exit-status"))
    }

    override fun beforeFrame(reportedSeconds: Double, pendingSeconds: Double, bootstrapping: Boolean): SimulationSpeed? {
        val now = System.nanoTime()
        var frameOrigin = now
        if (bootstrapStart == 0L) bootstrapStart = now
        if (bootstrapping) return null
        if (stage == Stage.BOOTSTRAP) {
            File(output, "bootstrap.txt").writeText("ui_bootstrap_wall_seconds=${seconds(now - bootstrapStart)}\nexcludes=world construction, scene and asset loading\n")
            startWarmup()
        } else if (stage == Stage.TRIAL) {
            // Observe the next frame start BEFORE closing a trial: the last stalled frame counts.
            frames.add(seconds(now - previousFrameStart), reportedSeconds)
            if (seconds(now - phaseStart) >= trialSeconds) {
                finishTrial(now, pendingSeconds)
                if (trial < trials) trial++ else { trial = 1; speedIndex++ }
                if (speedIndex == speeds.size) {
                    exportSchedule()
                    File(output, "COMPLETE.txt").writeText("Completed ${results.size} trials.\n")
                    complete = true
                    println("Desktop baseline complete: ${output.absolutePath}")
                    exitProcess(0)
                }
                startWarmup()
            }
        }
        if (stage == Stage.WARMUP && seconds(System.nanoTime() - phaseStart) >= warmupSeconds) {
            frames.clear()
            workNanos.fill(0)
            completed = 0.0; offered = 0.0; calls = 0
            pendingStart = pendingSeconds
            rssStart = probe.rssBytes()
            readingStart = probe.read()
            peakHeap = readingStart!!.heapBytes
            phaseStart = System.nanoTime()
            frameOrigin = phaseStart
            nextHeapSample = phaseStart + 1_000_000_000L
            stage = Stage.TRIAL
        }
        previousFrameStart = frameOrigin
        if (stage == Stage.TRIAL) {
            offered += reportedSeconds * speed.simulatedSecondsPerRealSecond
            if (previousFrameStart >= nextHeapSample) {
                peakHeap = max(peakHeap, probe.heapUsed())
                nextHeapSample = previousFrameStart + 1_000_000_000L
            }
        }
        return speed
    }

    override fun beginWork(work: AtlasWork) {
        if (phases && stage == Stage.TRIAL) workStart[work.ordinal] = System.nanoTime()
    }

    override fun endWork(work: AtlasWork, completedSeconds: Double) {
        if (phases && stage == Stage.TRIAL) workNanos[work.ordinal] += System.nanoTime() - workStart[work.ordinal]
        if (work == AtlasWork.WEATHER && completedSeconds > 0.0) {
            check(scheduleSize < schedule.size) { "Schedule recording capacity exceeded; experiment is invalid" }
            schedule[scheduleSize++] = completedSeconds
            if (stage == Stage.TRIAL) { completed += completedSeconds; calls++ }
        }
    }

    private fun startWarmup() {
        stage = Stage.WARMUP
        println("Desktop ${speed.name}, trial $trial/$trials: warming up for $warmupSeconds seconds")
        phaseStart = System.nanoTime()
    }

    private fun finishTrial(now: Long, pending: Double) {
        val after = probe.read()
        val before = readingStart!!
        val result = TrialResult(
            if (phases) "live-phases" else "live-frame-only", speed.name, trial,
            seconds(now - phaseStart), completed, offered, pendingStart, pending,
            seconds(workNanos[AtlasWork.WEATHER.ordinal]), seconds(workNanos[AtlasWork.HYDROLOGY.ordinal]),
            seconds(workNanos[AtlasWork.OVERLAYS.ordinal]), seconds(workNanos[AtlasWork.SURFACE_WATER.ordinal]),
            cpuDelta(after, before), counterDelta(after.allocatedBytes, before.allocatedBytes),
            after.gcMillis - before.gcMillis, max(peakHeap, after.heapBytes), max(rssStart, probe.rssBytes()),
            calls, frames.size, frames.dropped, frames.percentile(0.50), frames.percentile(0.95), frames.percentile(1.0)
        )
        results += result
        // Export after taking counters and stopping the clock. The next warmup excludes this I/O.
        frames.write(File(output, "frames-${speed.name}-$trial.csv"))
        writeResults(File(output, "trials.csv"), results)
        exportSchedule()
        println("${speed.name} trial $trial: ${result.simulatedDaysPerSecond} days/s, p95 ${result.frameP95 * 1000} ms, max ${result.frameMax * 1000} ms")
    }

    private fun exportSchedule() = File(output, "schedule.txt").bufferedWriter().use { out ->
        out.appendLine("# Complete post-bootstrap outer-call schedule, including warmups; simulated seconds per call")
        for (i in 0 until scheduleSize) out.appendLine(schedule[i].toString())
    }

    companion object {
        fun fromProperties(): DesktopBaselineExperiment? {
            if (System.getProperty("terra.baseline") != "true") return null
            val output = File(System.getProperty("terra.baseline.output", "build/experiments/live"))
            require(!File(output, "trials.csv").exists()) { "Use a fresh baselineOutput directory: $output" }
            val warmup = System.getProperty("terra.baseline.warmup", "15").toDouble()
            val duration = System.getProperty("terra.baseline.seconds", "30").toDouble()
            val trials = System.getProperty("terra.baseline.trials", "3").toInt()
            val phases = System.getProperty("terra.baseline.phases", "true").toBooleanStrict()
            val selection = System.getProperty("terra.baseline.speeds", "")
            val speeds = if (selection.isBlank()) SimulationSpeed.entries else selection.split(',').map { SimulationSpeed.valueOf(it) }
            require(warmup.isFinite() && warmup >= 0 && duration.isFinite() && duration > 0 && trials > 0 && speeds.isNotEmpty())
            writeEnvironment(output, mapOf(
                "kind" to "live desktop", "seed" to 31742, "world" to "283x212",
                "viewport" to "1440x960 logical; maximized desktop window", "overlays" to "CLOUDS",
                "warmup_per_trial_seconds" to warmup, "trial_seconds" to duration, "trials" to trials,
                "speeds" to speeds, "phase_timing" to phases,
                "frame_metric" to "consecutive updater starts in monotonic wall time; KorGE dt also recorded",
                "phase_timing_false" to "frame-only comparator still observes frames and completed steps; not the observer-free game"
            ))
            return DesktopBaselineExperiment(output, warmup, duration, trials, phases, speeds)
        }
    }
}
