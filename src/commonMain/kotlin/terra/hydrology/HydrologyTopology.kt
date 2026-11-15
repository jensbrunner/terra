package terra.hydrology

import terra.world.Terrain
import terra.world.World
import kotlin.math.max

/** Immutable D8 drainage geography: periodic longitude, impermeable polar land edges. */
class HydrologyTopology internal constructor(
    val width: Int,
    val height: Int,
    elevation: DoubleArray,
    ocean: BooleanArray
) {
    constructor(world: World) : this(world.width, world.height,
        DoubleArray(world.width * world.height) { world[it % world.width, it / world.width].elevation },
        BooleanArray(world.width * world.height) { world[it % world.width, it / world.width].terrain == Terrain.OCEAN })
    private val count = width * height
    /** Original immutable terrain elevation in row-major order. */
    val terrainElevation = elevation.copyOf()

    /** Downstream row-major tile index, or -1 for ocean or the explicit closed-world terminal basin. */
    val downstreamIndex = IntArray(count) { -1 }

    /** Priority-Flood filled elevation used to identify spill levels; world elevations stay unchanged. */
    val filledElevation = DoubleArray(count)

    /** Count of land tiles whose downstream paths pass through each tile, including itself if land. */
    val upstreamLandTileCount = IntArray(count)

    /** Upstream-first order; every linked downstream tile occurs later in this array. */
    val processingOrder: IntArray

    /** Meaningful potential depressions; no water is stored or rendered by this topology. */
    val lakeCandidates: List<LakeCandidate>
    private val oceanByTile = ocean.copyOf()

    init {
        require(width >= 3 && height >= 1 && width.toLong() * height <= Int.MAX_VALUE)
        require(elevation.size == count && ocean.size == count && elevation.all { it.isFinite() })
        val visited = BooleanArray(count)
        var floodOrderSize = 0
        val floodHeap = IntHeap(count) { a, b ->
            val elevationOrder = filledElevation[a].compareTo(filledElevation[b])
            elevationOrder < 0 || (elevationOrder == 0 && a < b)
        }

        // Oceans are legitimate sinks; no map edge exports water.
        val hasOcean = oceanByTile.any { it }
        val closedRoot = if (hasOcean) -1 else terrainElevation.indices.minBy { terrainElevation[it] }
        for (i in 0 until count) {
            if (oceanByTile[i] || i == closedRoot) {
                visited[i] = true
                filledElevation[i] = terrainElevation[i]
                floodHeap.add(i)
            }
        }

        while (floodHeap.isNotEmpty()) {
            val current = floodHeap.removeMin()
            floodOrderSize++
            val x = current % width
            val y = current / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = (x + dx + width) % width
                val ny = y + dy
                if (ny !in 0 until height) continue
                val next = ny * width + nx
                if (visited[next]) continue
                visited[next] = true
                filledElevation[next] = max(terrainElevation[next], filledElevation[current])
                downstreamIndex[next] = current
                floodHeap.add(next)
            }
        }
        check(floodOrderSize == count) { "Ocean or closed basin must reach every tile" }

        lakeCandidates = if (hasOcean) findLakeCandidates(terrainElevation) else listOf(
            // All-land fallback: one nonspilling basin whose wet footprint grows
            // from the global minimum. Its storage is finite; capacity is unbounded.
            LakeCandidate(0, IntArray(count) { it }, Double.POSITIVE_INFINITY, closedRoot)
        )
        processingOrder = buildProcessingOrder()
        buildUpstreamLandArea()
    }

    fun isLandTile(tileIndex: Int): Boolean = !oceanByTile[tileIndex]

    /** True when this routing edge climbs original terrain to follow a depression spill route. */
    fun isVirtualSpillLink(tileIndex: Int): Boolean {
        val downstream = downstreamIndex[tileIndex]
        return downstream >= 0 && terrainElevation[downstream] > terrainElevation[tileIndex]
    }

    private fun buildUpstreamLandArea() {
        for (tile in 0 until count) upstreamLandTileCount[tile] = if (oceanByTile[tile]) 0 else 1
        for (tile in processingOrder) {
            val downstream = downstreamIndex[tile]
            if (downstream >= 0) {
                upstreamLandTileCount[downstream] += upstreamLandTileCount[tile]
            }
        }
    }

    private fun findLakeCandidates(elevation: DoubleArray): List<LakeCandidate> {
        val grouped = IntArray(count) { -1 }
        val queue = IntArray(count)
        val distances = IntArray(count)
        val basinOwner = IntArray(count) { -1 }
        val candidates = ArrayList<LakeCandidate>()
        var group = 0

        for (start in 0 until count) {
            if (filledElevation[start] <= elevation[start] || grouped[start] >= 0) continue
            val spillLevel = filledElevation[start]
            var head = 0
            var tail = 0
            queue[tail++] = start
            grouped[start] = group
            var maximumDepth = 0.0
            while (head < tail) {
                val current = queue[head++]
                maximumDepth = max(maximumDepth, spillLevel - elevation[current])
                val x = current % width
                val y = current / width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = (x + dx + width) % width
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (grouped[next] >= 0 || filledElevation[next] != spillLevel ||
                        filledElevation[next] <= elevation[next]
                    ) continue
                    grouped[next] = group
                    queue[tail++] = next
                }
            }

            if (tail >= MIN_LAKE_BASIN_TILES && maximumDepth >= MIN_LAKE_DEPRESSION_DEPTH) {
                val basinTiles = IntArray(tail) { queue[it] }
                basinTiles.sort()
                var exitTile = -1
                var exitDownstream = -1
                var exitSaddle = Double.POSITIVE_INFINITY
                for (tile in basinTiles) {
                    val x = tile % width
                    val y = tile / width
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = (x + dx + width) % width
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        val next = ny * width + nx
                        if (grouped[next] == group) continue
                        var cursor = next
                        var reachesThisBasin = false
                        var traversed = 0
                        while (cursor >= 0 && traversed < count) {
                            if (grouped[cursor] == group) {
                                reachesThisBasin = true
                                break
                            }
                            cursor = downstreamIndex[cursor]
                            traversed++
                        }
                        if (reachesThisBasin || traversed == count) continue
                        val saddle = max(elevation[tile], elevation[next])
                        if (saddle < exitSaddle ||
                            (saddle == exitSaddle && (exitTile < 0 || tile < exitTile ||
                                (tile == exitTile && next < exitDownstream)))
                        ) {
                            exitTile = tile
                            exitDownstream = next
                            exitSaddle = saddle
                        }
                    }
                }

                if (exitTile >= 0) {
                    buildSubBasins(
                        tiles = basinTiles,
                        grouped = grouped,
                        group = group,
                        elevation = elevation,
                        externalExitTile = exitTile,
                        externalDownstreamTile = exitDownstream,
                        externalSpillElevation = exitSaddle,
                        basinOwner = basinOwner,
                        distances = distances,
                        queue = queue,
                        candidates = candidates
                    )
                }
            }
            group++
        }
        return candidates
    }

    private data class Saddle(val from: Int, val to: Int, val tile: Int, val neighbor: Int, val height: Double)

    /** Keep local basins separate, connecting them by a minimum spanning tree of saddles. */
    private fun buildSubBasins(
        tiles: IntArray, grouped: IntArray, group: Int, elevation: DoubleArray,
        externalExitTile: Int, externalDownstreamTile: Int, externalSpillElevation: Double,
        basinOwner: IntArray, distances: IntArray, queue: IntArray,
        candidates: MutableList<LakeCandidate>
    ) {
        val basins = mutableListOf<MutableList<Int>>()
        for (tile in tiles.sortedWith(compareBy<Int> { elevation[it] }.thenBy { it })) {
            var lower = -1
            neighbors(tile) { next ->
                if (grouped[next] == group && (elevation[next] < elevation[tile] ||
                        elevation[next] == elevation[tile] && next < tile) &&
                    (lower < 0 || elevation[next] < elevation[lower] || elevation[next] == elevation[lower] && next < lower)) lower = next
            }
            if (lower < 0) basins.add(mutableListOf())
            val owner = if (lower < 0) basins.lastIndex else basinOwner[lower]
            basinOwner[tile] = owner
            basins[owner].add(tile)
        }
        val edges = mutableListOf<Saddle>()
        for (tile in tiles) neighbors(tile) { next ->
            if (next > tile && grouped[next] == group && basinOwner[tile] != basinOwner[next])
                edges.add(Saddle(basinOwner[tile], basinOwner[next], tile, next, max(elevation[tile], elevation[next])))
        }
        edges.sortWith(compareBy<Saddle> { it.height }.thenBy { minOf(it.from, it.to) }
            .thenBy { maxOf(it.from, it.to) }.thenBy { it.tile }.thenBy { it.neighbor })
        val sets = IntArray(basins.size) { it }
        fun root(start: Int): Int {
            var i = start
            while (sets[i] != i) { sets[i] = sets[sets[i]]; i = sets[i] }
            return i
        }
        val tree = Array(basins.size) { mutableListOf<Saddle>() }
        for (edge in edges) {
            val a = root(edge.from); val b = root(edge.to)
            if (a == b) continue
            sets[a] = b
            tree[edge.from].add(edge); tree[edge.to].add(edge)
        }
        val rootBasin = basinOwner[externalExitTile]
        val exits = IntArray(basins.size) { -1 }
        val downstream = IntArray(basins.size)
        val spill = DoubleArray(basins.size)
        exits[rootBasin] = externalExitTile
        downstream[rootBasin] = externalDownstreamTile
        spill[rootBasin] = externalSpillElevation
        val ready = ArrayDeque<Int>()
        ready.add(rootBasin)
        while (ready.isNotEmpty()) {
            val basin = ready.removeFirst()
            for (edge in tree[basin]) {
                val child = if (edge.from == basin) edge.to else edge.from
                if (exits[child] >= 0) continue
                exits[child] = if (child == edge.from) edge.tile else edge.neighbor
                downstream[child] = if (child == edge.from) edge.neighbor else edge.tile
                spill[child] = edge.height
                ready.add(child)
            }
        }
        for ((basin, members) in basins.withIndex()) {
            val exit = exits[basin]
            check(exit >= 0) { "Depression catchments must connect by saddle edges" }
            for (tile in members) distances[tile] = -1
            var head = 0; var tail = 0
            queue[tail++] = exit
            distances[exit] = 0
            while (head < tail) {
                val current = queue[head++]
                neighbors(current) { next ->
                    if (grouped[next] == group && basinOwner[next] == basin && distances[next] < 0) {
                        distances[next] = distances[current] + 1
                        downstreamIndex[next] = current
                        queue[tail++] = next
                    }
                }
            }
            downstreamIndex[exit] = downstream[basin]
            if (members.size >= MIN_LAKE_BASIN_TILES && spill[basin] - members.minOf { elevation[it] } >= MIN_LAKE_DEPRESSION_DEPTH)
                candidates.add(LakeCandidate(candidates.size, members.toIntArray(), spill[basin], exit))
        }
    }

    private inline fun neighbors(tile: Int, visit: (Int) -> Unit) {
        val x = tile % width; val y = tile / width
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0 || y + dy !in 0 until height) continue
            visit((y + dy) * width + (x + dx + width) % width)
        }
    }

    private fun buildProcessingOrder(): IntArray {
        val downstreamIncomingCount = IntArray(count)
        for (tile in 0 until count) {
            val downstream = downstreamIndex[tile]
            if (downstream >= 0) downstreamIncomingCount[downstream]++
        }
        val ready = ArrayDeque<Int>()
        for (tile in 0 until count) if (downstreamIncomingCount[tile] == 0) ready.add(tile)

        val order = IntArray(count)
        var orderSize = 0
        while (ready.isNotEmpty()) {
            val tile = ready.removeFirst()
            order[orderSize++] = tile
            val downstream = downstreamIndex[tile]
            if (downstream >= 0) {
                downstreamIncomingCount[downstream]--
                if (downstreamIncomingCount[downstream] == 0) ready.add(downstream)
            }
        }
        check(orderSize == count) { "Drainage graph must be acyclic" }
        return order
    }

    companion object {
        /** Game-scale filtering policy for excluding tiny numerical terrain pits. */
        const val MIN_LAKE_BASIN_TILES = 4
        const val MIN_LAKE_DEPRESSION_DEPTH = 0.015
    }
}

/** Potential inland basin metadata; these tiles are not assumed to contain water. */
data class LakeCandidate(
    val id: Int,
    val tileIndices: IntArray,
    val spillElevation: Double,
    val exitTileIndex: Int
)

/** Small build-time integer heap with caller-defined deterministic ordering. */
private class IntHeap(capacity: Int, private val before: (Int, Int) -> Boolean) {
    private val values = IntArray(capacity)
    private var size = 0

    fun isNotEmpty() = size > 0

    fun add(value: Int) {
        var child = size++
        while (child > 0) {
            val parent = (child - 1) / 2
            val parentValue = values[parent]
            if (!before(value, parentValue)) break
            values[child] = parentValue
            child = parent
        }
        values[child] = value
    }

    fun removeMin(): Int {
        check(size > 0)
        val minimum = values[0]
        val last = values[--size]
        if (size == 0) return minimum
        var parent = 0
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) break
            val right = left + 1
            val child = if (right < size && before(values[right], values[left])) right else left
            if (!before(values[child], last)) break
            values[parent] = values[child]
            parent = child
        }
        values[parent] = last
        return minimum
    }
}
