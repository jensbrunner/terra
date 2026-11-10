package terra.experiments

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import terra.MAP_HEIGHT_TILES
import terra.MAP_WIDTH_TILES
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.simulation.SimulationSpeed
import terra.weather.ClimateBootstrap
import terra.weather.WeatherSimulation
import terra.world.World

/** Uses the gameplay constructors and coupling verbatim, including applying sampled biomes. */
internal class BaselineWorld(val seed: Int = 31742, val width: Int = MAP_WIDTH_TILES, val height: Int = MAP_HEIGHT_TILES) {
    val world: World
    val weather: WeatherSimulation
    val topology: HydrologyTopology
    val hydrology: HydrologySimulation
    val worldConstructionSeconds: Double
    val weatherConstructionSeconds: Double
    val topologyConstructionSeconds: Double
    val hydrologyConstructionSeconds: Double

    init {
        var start = System.nanoTime()
        world = World(seed, width, height)
        worldConstructionSeconds = seconds(System.nanoTime() - start)
        start = System.nanoTime()
        weather = WeatherSimulation(world)
        weatherConstructionSeconds = seconds(System.nanoTime() - start)
        start = System.nanoTime()
        topology = HydrologyTopology(world)
        topologyConstructionSeconds = seconds(System.nanoTime() - start)
        start = System.nanoTime()
        hydrology = HydrologySimulation(topology)
        hydrologyConstructionSeconds = seconds(System.nanoTime() - start)
    }

    fun bootstrap() {
        val bootstrap = ClimateBootstrap(weather) { hydrology.acceptWeatherStep(weather, it) }
        while (!bootstrap.isComplete) bootstrap.advanceBatch()
        world.applyClimateBiomes(bootstrap.snapshot!!)
    }

    fun advance(simulatedSeconds: Double) {
        weather.step(simulatedSeconds)
        hydrology.acceptWeatherStep(weather, simulatedSeconds)
    }

    /** Observable-state checksum only: intentionally makes no assertion about private rain/pending state. */
    fun observableHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(8)
        fun number(value: Double) {
            check(value.isFinite()) { "Nonfinite public state" }
            buffer.clear()
            buffer.putDouble(value)
            digest.update(buffer.array())
        }
        number(width.toDouble()); number(height.toDouble())
        for (y in 0 until height) for (x in 0 until width) {
            val vapor = weather.atmosphericWaterMm(x, y)
            val cloud = weather.cloudWaterMm(x, y)
            val soil = weather.soilWaterMm(x, y)
            check(vapor >= 0 && cloud >= 0 && soil >= 0) { "Negative public water store at ($x,$y)" }
            number(world[x, y].terrain.ordinal.toDouble())
            number(vapor); number(cloud); number(soil)
            number(weather.airTemperatureC(x, y)); number(weather.surfaceTemperatureC(x, y))
            number(weather.windUTilesPerDay(x, y)); number(weather.windVTilesPerDay(x, y))
            number(weather.relativeHumidity(x, y)); number(weather.cloudCover(x, y))
            number(weather.solarInputFactor(x, y)); number(weather.rainMmThisUpdate(x, y))
            number(weather.precipitationMmPerHour(x, y)); number(weather.peakSubstepPrecipitationMmPerHour(x, y))
            number(weather.thirtyDayRainMmPerHour(x, y))
            number(weather.drainageMmThisUpdate(x, y)); number(weather.overflowMmThisUpdate(x, y))
            number(weather.lakeDirectRainMmThisUpdate(x, y)); number(weather.lakePotentialEvaporationMmThisUpdate(x, y))
            number(hydrology.routedMmTiles(x, y)); number(hydrology.dischargeMmTilesPerDay(x, y))
            number(if (hydrology.isLakeTileWet(x, y)) 1.0 else 0.0)
        }
        for (lake in topology.lakeCandidates) {
            number(hydrology.lakeStorageMmTiles(lake.id))
            number(hydrology.lakeWaterSurfaceElevation(lake.id))
        }
        number(hydrology.outletMmTilesTotal)
        number(hydrology.routedPeriodRevision.toDouble())
        number(hydrology.lastRoutedPeriodDays)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private data class Options(
    val mode: String, val output: File, val warmup: Double, val duration: Double,
    val trials: Int, val seed: Int, val width: Int, val height: Int,
    val days: Double, val speeds: List<SimulationSpeed>, val schedule: File?
) {
    companion object {
        fun parse(args: Array<String>): Options {
            val valid = setOf("mode", "output", "warmup", "seconds", "trials", "seed", "width", "height", "days", "speeds", "schedule")
            val values = args.associate { arg ->
                require(arg.startsWith("--") && '=' in arg) { "Expected --name=value: $arg" }
                arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
            }
            require(values.keys.all { it in valid }) { "Unknown options: ${values.keys - valid}" }
            val mode = values["mode"] ?: "benchmark"
            require(mode in setOf("benchmark", "sensitivity", "replay"))
            val speeds = values["speeds"]?.split(',')?.map { SimulationSpeed.valueOf(it) } ?: SimulationSpeed.entries
            return Options(
                mode, File(values["output"] ?: "build/experiments/headless"),
                (values["warmup"] ?: "15").toDouble(), (values["seconds"] ?: "30").toDouble(),
                (values["trials"] ?: "3").toInt(), (values["seed"] ?: "31742").toInt(),
                (values["width"] ?: MAP_WIDTH_TILES.toString()).toInt(),
                (values["height"] ?: MAP_HEIGHT_TILES.toString()).toInt(),
                (values["days"] ?: "30").toDouble(), speeds, values["schedule"]?.let(::File)
            ).also {
                require(it.warmup.isFinite() && it.warmup >= 0)
                require(it.duration.isFinite() && it.duration > 0 && it.trials > 0)
                require(it.width > 1 && it.height > 1 && it.days.isFinite() && it.days > 0)
                require(it.speeds.isNotEmpty())
                require(it.mode != "replay" || it.schedule != null) { "Replay requires --schedule=file" }
            }
        }
    }
}

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println("""Existing-model baseline (no window).
--mode=benchmark|sensitivity|replay --output=build/experiments/headless
--warmup=15 --seconds=30 --trials=3 --seed=31742 --width=283 --height=212
--speeds=REALTIME,ONE_HOUR,TWELVE_HOURS,ONE_DAY,ONE_WEEK,ONE_MONTH
--days=30 (sensitivity) --schedule=file (replay; one positive duration per line)
Benchmark replays fixed 50ms outer-call batches at maximum compute throughput.
Its simulated days/s is compute capacity, not live achieved speed or frame rate.""")
        return
    }
    val options = Options.parse(args)
    writeEnvironment(options.output, mapOf(
        "kind" to "headless ${options.mode}", "options" to args.toList(),
        "seed" to options.seed, "world" to "${options.width}x${options.height}",
        "warmup_wall_seconds" to options.warmup, "trial_wall_seconds" to options.duration,
        "trials" to options.trials, "bootstrap_simulated_days" to 210,
        "outer_batch_real_seconds" to 0.05
    ))
    when (options.mode) {
        "benchmark" -> benchmark(options)
        "sensitivity" -> sensitivity(options)
        "replay" -> replay(options)
    }
}

private fun prepare(options: Options, label: String): BaselineWorld {
    println("Constructing $label ${options.width}x${options.height}, seed ${options.seed}")
    val state = BaselineWorld(options.seed, options.width, options.height)
    val start = System.nanoTime()
    state.bootstrap()
    val text = "world_seconds=${state.worldConstructionSeconds}\nweather_seconds=${state.weatherConstructionSeconds}\n" +
        "topology_seconds=${state.topologyConstructionSeconds}\nhydrology_seconds=${state.hydrologyConstructionSeconds}\n" +
        "bootstrap_seconds=${seconds(System.nanoTime() - start)}\nobservable_hash=${state.observableHash()}\n"
    File(options.output, "initialization-$label.txt").writeText(text)
    println("$label bootstrap complete")
    return state
}

private fun benchmark(options: Options) {
    val probe = JvmProbe()
    val results = mutableListOf<TrialResult>()
    for (speed in options.speeds) {
        val state = prepare(options, speed.name)
        val step = speed.simulatedSecondsPerRealSecond * 0.05
        val warmupStart = System.nanoTime()
        while (seconds(System.nanoTime() - warmupStart) < options.warmup) state.advance(step)
        for (trial in 1..options.trials) {
            val rssBefore = probe.rssBytes()
            val before = probe.read()
            var peakHeap = before.heapBytes
            var weatherNanos = 0L
            var hydroNanos = 0L
            var calls = 0
            var completed = 0.0
            val start = System.nanoTime()
            var nextHeapSample = start + 1_000_000_000L
            while (seconds(System.nanoTime() - start) < options.duration) {
                var part = System.nanoTime()
                state.weather.step(step)
                weatherNanos += System.nanoTime() - part
                part = System.nanoTime()
                state.hydrology.acceptWeatherStep(state.weather, step)
                hydroNanos += System.nanoTime() - part
                completed += step
                calls++
                val now = System.nanoTime()
                if (now >= nextHeapSample) {
                    peakHeap = max(peakHeap, probe.heapUsed())
                    nextHeapSample = now + 1_000_000_000L
                }
            }
            val elapsed = seconds(System.nanoTime() - start)
            val after = probe.read()
            results += TrialResult(
                "headless-capacity", speed.name, trial, elapsed, completed, completed, 0.0, 0.0,
                seconds(weatherNanos), seconds(hydroNanos), 0.0, 0.0, cpuDelta(after, before),
                counterDelta(after.allocatedBytes, before.allocatedBytes), after.gcMillis - before.gcMillis,
                max(peakHeap, after.heapBytes), max(rssBefore, probe.rssBytes()), calls
            )
            // Hashing, formatting and output are outside measured integration.
            File(options.output, "checkpoint-${speed.name}-$trial.txt").writeText(state.observableHash() + "\n")
            writeResults(File(options.output, "trials.csv"), results)
            println("${speed.name} trial $trial: ${results.last().simulatedDaysPerSecond} simulated days/s compute capacity")
        }
    }
}

/** Used by sensitivity runs and tests; observers never change the supplied schedule. */
internal fun accumulateRain(state: BaselineWorld, durations: Iterable<Double>): DoubleArray {
    val rain = DoubleArray(state.width * state.height)
    for (duration in durations) {
        require(duration.isFinite() && duration > 0)
        state.advance(duration)
        for (y in 0 until state.height) for (x in 0 until state.width) {
            rain[y * state.width + x] += state.weather.rainMmThisUpdate(x, y)
        }
    }
    return rain
}

internal fun durationSchedule(totalSeconds: Double, batchSeconds: Double): List<Double> {
    require(totalSeconds.isFinite() && totalSeconds > 0 && batchSeconds.isFinite() && batchSeconds > 0)
    val result = mutableListOf<Double>()
    var remaining = totalSeconds
    while (remaining > 0) {
        val step = min(remaining, batchSeconds)
        result += step
        remaining -= step
    }
    return result
}

private fun sensitivity(options: Options) {
    val short = prepare(options, "three-hour")
    val batched = prepare(options, "month-50ms")
    val total = options.days * SECONDS_PER_DAY
    val rainShort = accumulateRain(short, durationSchedule(total, 10_800.0))
    val rainBatched = accumulateRain(batched, durationSchedule(total, SimulationSpeed.ONE_MONTH.simulatedSecondsPerRealSecond * 0.05))
    val lines = mutableListOf("field,sum_three_hour,sum_month_batch,mean_absolute_tile_difference,max_absolute_tile_difference")
    fun compare(name: String, left: (Int, Int) -> Double, right: (Int, Int) -> Double) {
        var l = 0.0; var r = 0.0; var absolute = 0.0; var largest = 0.0
        for (y in 0 until options.height) for (x in 0 until options.width) {
            val a = left(x, y); val b = right(x, y)
            check(a.isFinite() && b.isFinite())
            l += a; r += b; absolute += abs(a - b); largest = max(largest, abs(a - b))
        }
        lines += "$name,$l,$r,${absolute / (options.width * options.height)},$largest"
    }
    compare("cumulative_rain_mm", { x, y -> rainShort[y * options.width + x] }, { x, y -> rainBatched[y * options.width + x] })
    compare("vapor_mm", short.weather::atmosphericWaterMm, batched.weather::atmosphericWaterMm)
    compare("cloud_mm", short.weather::cloudWaterMm, batched.weather::cloudWaterMm)
    compare("soil_mm", short.weather::soilWaterMm, batched.weather::soilWaterMm)
    compare("air_temperature_c", short.weather::airTemperatureC, batched.weather::airTemperatureC)
    File(options.output, "sensitivity.csv").writeText(lines.joinToString("\n") + "\n")
    File(options.output, "hydrology.txt").writeText(
        "three_hour_lake_storage_mm_tiles=${short.topology.lakeCandidates.sumOf { short.hydrology.lakeStorageMmTiles(it.id) }}\n" +
        "month_batch_lake_storage_mm_tiles=${batched.topology.lakeCandidates.sumOf { batched.hydrology.lakeStorageMmTiles(it.id) }}\n" +
        "three_hour_outlet_mm_tiles=${short.hydrology.outletMmTilesTotal}\nmonth_batch_outlet_mm_tiles=${batched.hydrology.outletMmTilesTotal}\n"
    )
    println(lines.joinToString("\n"))
}

private fun replay(options: Options) {
    val state = prepare(options, "replay")
    val schedule = options.schedule!!.readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { it.toDouble() }
    require(schedule.isNotEmpty() && schedule.all { it.isFinite() && it > 0 })
    val start = System.nanoTime()
    schedule.forEach(state::advance)
    val elapsed = seconds(System.nanoTime() - start)
    File(options.output, "replay.txt").writeText(
        "calls=${schedule.size}\nsimulated_seconds=${schedule.sum()}\nwall_seconds=$elapsed\nobservable_hash=${state.observableHash()}\n"
    )
    println("Replayed ${schedule.size} calls in $elapsed seconds")
}
