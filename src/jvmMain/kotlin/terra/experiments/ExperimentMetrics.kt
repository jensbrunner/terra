package terra.experiments

import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max

internal const val SECONDS_PER_DAY = 86_400.0
internal fun seconds(nanos: Long) = nanos / 1e9

/** JVM counters have different scopes: CPU is process-wide, allocation is the calling thread only. */
internal data class JvmReading(
    val cpuNanos: Long,
    val allocatedBytes: Long,
    val gcMillis: Long,
    val heapBytes: Long
)

internal class JvmProbe {
    private val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    private val thread = ManagementFactory.getThreadMXBean() as? ThreadMXBean
    private val memory = ManagementFactory.getMemoryMXBean()
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()

    init {
        thread?.let { bean ->
            if (bean.isThreadAllocatedMemorySupported && !bean.isThreadAllocatedMemoryEnabled) {
                runCatching { bean.isThreadAllocatedMemoryEnabled = true }
            }
        }
    }

    fun heapUsed(): Long = memory.heapMemoryUsage.used

    @Suppress("DEPRECATION") // Thread.id also runs on the project's supported JDK 17.
    fun read(): JvmReading = JvmReading(
        os?.processCpuTime ?: -1L,
        if (thread?.isThreadAllocatedMemoryEnabled == true) thread.getThreadAllocatedBytes(Thread.currentThread().id) else -1L,
        collectors.sumOf { max(0L, it.collectionTime) },
        heapUsed()
    )

    // Process creation is outside timed intervals and counter snapshots.
    fun rssBytes(): Long = command("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString())
        .trim().toLongOrNull()?.times(1024) ?: -1L
}

/** Fixed-capacity recording: overflow is visible and invalidates percentiles, never overwrites stalls. */
internal class FrameSamples(private val capacity: Int = 131_072) {
    private val wall = DoubleArray(capacity)
    private val reported = DoubleArray(capacity)
    var size = 0
        private set
    var dropped = 0
        private set

    fun add(wallSeconds: Double, reportedSeconds: Double) {
        if (size == capacity) { dropped++; return }
        wall[size] = wallSeconds
        reported[size] = reportedSeconds
        size++
    }

    fun percentile(fraction: Double): Double {
        require(fraction in 0.0..1.0)
        if (size == 0 || dropped > 0) return Double.NaN
        val sorted = wall.copyOf(size).also { it.sort() }
        return sorted[(ceil(fraction * size).toInt() - 1).coerceIn(0, size - 1)]
    }

    fun write(file: File) = file.bufferedWriter().use { out ->
        out.appendLine("frame,wall_seconds,korge_reported_seconds")
        for (i in 0 until size) out.appendLine("$i,${wall[i]},${reported[i]}")
    }

    fun clear() { size = 0; dropped = 0 }
}

internal data class TrialResult(
    val mode: String,
    val speed: String,
    val trial: Int,
    val wallSeconds: Double,
    val completedSeconds: Double,
    val offeredSeconds: Double,
    val pendingStart: Double,
    val pendingEnd: Double,
    val weatherSeconds: Double,
    val hydrologySeconds: Double,
    val overlaySeconds: Double,
    val surfaceWaterSeconds: Double,
    val cpuSeconds: Double,
    val allocatedBytes: Long,
    val gcMillis: Long,
    val sampledPeakHeapBytes: Long,
    val endpointPeakRssBytes: Long,
    val calls: Int,
    val frameCount: Int = 0,
    val droppedFrames: Int = 0,
    val frameP50: Double = Double.NaN,
    val frameP95: Double = Double.NaN,
    val frameMax: Double = Double.NaN
) {
    val simulatedDaysPerSecond get() = completedSeconds / SECONDS_PER_DAY / wallSeconds
    fun csv() = listOf(
        mode, speed, trial, wallSeconds, completedSeconds, offeredSeconds, pendingStart, pendingEnd,
        weatherSeconds, hydrologySeconds, overlaySeconds, surfaceWaterSeconds, cpuSeconds,
        allocatedBytes, gcMillis, sampledPeakHeapBytes, endpointPeakRssBytes, calls,
        frameCount, droppedFrames, frameP50, frameP95, frameMax, simulatedDaysPerSecond
    ).joinToString(",")

    companion object {
        const val HEADER = "mode,speed,trial,wall_seconds,completed_sim_seconds,offered_sim_seconds,pending_start_seconds,pending_end_seconds,weather_wall_seconds,hydrology_wall_seconds,overlay_wall_seconds,surface_water_wall_seconds,process_cpu_seconds,calling_thread_allocated_bytes,gc_millis,sampled_peak_heap_bytes,endpoint_peak_rss_bytes,weather_calls,frames,dropped_frame_samples,frame_p50_seconds,frame_p95_seconds,frame_max_seconds,simulated_days_per_second"
    }
}

internal fun writeResults(file: File, results: List<TrialResult>) = file.bufferedWriter().use { out ->
    out.appendLine(TrialResult.HEADER)
    results.forEach { out.appendLine(it.csv()) }
}

internal fun counterDelta(end: Long, start: Long) = if (end < 0 || start < 0) -1L else end - start
internal fun cpuDelta(end: JvmReading, start: JvmReading) =
    counterDelta(end.cpuNanos, start.cpuNanos).let { if (it < 0) Double.NaN else seconds(it) }

internal fun command(vararg args: String): String = runCatching {
    val process = ProcessBuilder(*args).redirectErrorStream(true).start()
    val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) result else "unavailable"
}.getOrDefault("unavailable")

internal fun sourceDigest(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val files = File("src").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
        listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml").map(::File)
    for (file in files.sortedBy { it.path }) {
        digest.update(file.path.toByteArray())
        digest.update(0.toByte())
        digest.update(file.readBytes())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun writeEnvironment(directory: File, extra: Map<String, Any>) {
    directory.mkdirs()
    val os = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    val values = linkedMapOf<String, Any>(
        "revision" to command("git", "rev-parse", "HEAD"),
        "working_tree" to command("git", "status", "--short"),
        "source_sha256" to sourceDigest(),
        "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        "cpu" to command("sysctl", "-n", "machdep.cpu.brand_string"),
        "machine" to command("sysctl", "-n", "hw.model"),
        "ram_bytes" to (os?.totalMemorySize ?: -1L),
        "logical_processors" to Runtime.getRuntime().availableProcessors(),
        "power" to command("pmset", "-g", "custom"),
        "java" to "${System.getProperty("java.vendor")} ${System.getProperty("java.runtime.version")}",
        "gradle" to System.getProperty("terra.baseline.gradle", "unavailable"),
        "jvm_resource_arguments" to ManagementFactory.getRuntimeMXBean().inputArguments.filter {
            it.startsWith("-X") || it.startsWith("--enable-native-access")
        },
        "max_heap_bytes" to Runtime.getRuntime().maxMemory(),
        "power_source" to command("pmset", "-g", "batt"),
        "gc" to ManagementFactory.getGarbageCollectorMXBeans().map { it.name },
        "allocation_scope" to "calling thread only; excludes other renderer/GC/compiler threads",
        "memory_scope" to "heap sampled at <=1Hz plus endpoints; RSS sampled only at trial endpoints; neither is an OS peak",
        "units" to "seconds, bytes, mm*tiles; no physical air-mass or energy budget"
    )
    values.putAll(extra)
    File(directory, "environment.txt").writeText(values.entries.joinToString("\n\n") { "${it.key}=${it.value}" } + "\n")
}
