package terra.experiments

import java.io.File
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.world.World
import terra.weather.DynamicalWeather

/**
 * Isolated response-unit microbenchmark. It does not measure the whole climate solver;
 * it compares the repeated linear groundwater recession and snow-melt updates that the
 * production code now performs per unit instead of per land tile.
 */
fun main() {
    val world = World(31742)
    val hydrology = HydrologySimulation(HydrologyTopology(world))
    val weather = DynamicalWeather(world, hydrology)
    val units = weather.surfaceState.responseUnits
    val landTiles = units.tileCountPerUnit.sum()
    require(landTiles > 0 && units.count > 0)
    val tileStore = DoubleArray(landTiles) { 180.0 }
    val unitStore = DoubleArray(units.count) { 180.0 * units.tileCountPerUnit[it] }
    val decay = 0.993
    val percolation = 0.8
    var sink = 0.0
    repeat(500) {
        for (i in tileStore.indices) tileStore[i] = tileStore[i] * decay + percolation
        for (i in unitStore.indices) unitStore[i] = unitStore[i] * decay + percolation * units.tileCountPerUnit[i]
    }
    val reps = 4000
    val tileStart = System.nanoTime()
    repeat(reps) {
        for (i in tileStore.indices) tileStore[i] = tileStore[i] * decay + percolation
        sink += tileStore[it % tileStore.size]
    }
    val tileNanos = System.nanoTime() - tileStart
    val unitStart = System.nanoTime()
    repeat(reps) {
        for (i in unitStore.indices) unitStore[i] = unitStore[i] * decay + percolation * units.tileCountPerUnit[i]
        sink += unitStore[it % unitStore.size]
    }
    val unitNanos = System.nanoTime() - unitStart
    val tileRouting = DoubleArray(landTiles)
    val unitRouting = DoubleArray(units.count)
    val routingTileStart = System.nanoTime()
    repeat(reps) {
        for (i in tileRouting.indices) tileRouting[i] += 0.01
        sink += tileRouting[it % tileRouting.size]
    }
    val routingTileNanos = System.nanoTime() - routingTileStart
    val routingUnitStart = System.nanoTime()
    repeat(reps) {
        for (i in unitRouting.indices) unitRouting[i] += 0.01 * units.tileCountPerUnit[i]
        sink += unitRouting[it % unitRouting.size]
    }
    val routingUnitNanos = System.nanoTime() - routingUnitStart
    val tilePerUpdate = tileNanos.toDouble() / reps / landTiles
    val unitPerTileEquivalent = unitNanos.toDouble() / reps / landTiles
    val report = buildString {
        appendLine("response-unit microbenchmark")
        appendLine("land_tiles=$landTiles response_units=${units.count}")
        appendLine("repetitions=$reps operations_per_rep=one linear groundwater + snow-melt style update")
        appendLine("per_tile_ns_per_rep=${tileNanos / reps} per_unit_ns_per_rep=${unitNanos / reps}")
        appendLine("per_tile_ns_per_tile=${"%.3f".format(tilePerUpdate)} per_unit_ns_per_equivalent_tile=${"%.3f".format(unitPerTileEquivalent)}")
        appendLine("store_speedup=${"%.2f".format(tileNanos.toDouble() / unitNanos)}x")
        appendLine("routing_input_per_tile_ns_per_rep=${routingTileNanos / reps} routing_input_per_unit_ns_per_rep=${routingUnitNanos / reps}")
        appendLine("routing_input_speedup=${"%.2f".format(routingTileNanos.toDouble() / routingUnitNanos)}x")
        appendLine("sink=${"%.6f".format(sink)}")
    }
    val output = File("build/experiments/response-units-benchmark.txt")
    output.parentFile.mkdirs()
    output.writeText(report)
    print(report)
    println("RESPONSE_UNITS_BENCHMARK ${output.absolutePath}")
}
