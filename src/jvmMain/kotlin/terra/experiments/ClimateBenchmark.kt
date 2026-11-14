package terra.experiments

import java.io.File
import terra.world.World
import terra.climate.*

/** Compare atlas generation on identical terrain, after warming both resolutions. */
fun main(args: Array<String>) {
    val samples = args.firstOrNull { it.startsWith("--samples=") }?.substringAfter('=')?.toInt() ?: 3
    require(samples > 0)
    val world = World(31742)
    val configs = listOf(ClimateConfig(width = 96, height = 72), ClimateConfig(width = world.width, height = world.height))
    val runtime = Runtime.getRuntime()
    fun usedBytes(): Long {
        System.gc()
        return runtime.totalMemory() - runtime.freeMemory()
    }
    for (config in configs) ClimateAtlasBuilder().build(ClimateGeography(world, config))
    val report = buildString {
        appendLine("world=${world.width}x${world.height} seed=${world.seed} samples=$samples (warmed JVM; retained heap after GC)")
        for (config in configs) {
            val times = mutableListOf<Double>()
            val retained = mutableListOf<Double>()
            var atlas: SeasonalClimateAtlas? = null
            repeat(samples) {
                atlas = null
                val before = usedBytes()
                val start = System.nanoTime()
                atlas = ClimateAtlasBuilder().build(ClimateGeography(world, config))
                times += (System.nanoTime() - start) / 1e6
                retained += (usedBytes() - before) / (1024.0 * 1024)
            }
            appendLine("atlas_grid=${config.width}x${config.height} median_build_ms=${times.sorted()[samples / 2]} retained_mib=${retained.sorted()[samples / 2]} moisture_residual_mm_per_day=${atlas!!.maximumMoistureResidualMmPerDay} sweeps=${atlas!!.moistureSweeps}")
        }
    }
    File("build/experiments").mkdirs()
    File("build/experiments/climate-benchmark.txt").writeText(report)
    print(report)
}
