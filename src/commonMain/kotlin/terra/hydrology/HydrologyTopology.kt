package terra.hydrology

import terra.world.Terrain
import terra.world.World
import kotlin.math.max

/** Immutable, bounded D8 drainage geography for one generated world. */
class HydrologyTopology(world: World) {
    val width: Int = world.width
    val height: Int = world.height
    private val count = width * height

    /** Downstream row-major tile index, or -1 for an ocean/perimeter terminal outlet. */
    val downstreamIndex = IntArray(count) { -1 }

    /** Priority-Flood filled elevation used to identify spill levels; world elevations stay unchanged. */
    val filledElevation = DoubleArray(count)

    /** Count of land tiles whose downstream paths pass through each tile, including itself if land. */
    val upstreamLandTileCount = IntArray(count)

    /** Upstream-first order; every linked downstream tile occurs later in this array. */
    val processingOrder: IntArray

    /** Meaningful potential depressions; no water is stored or rendered by this topology. */
    val lakeCandidates: List<LakeCandidate>
    private val oceanByTile = BooleanArray(count)

    init {
        val elevation = DoubleArray(count)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val cell = world[x, y]
            elevation[i] = cell.elevation
            oceanByTile[i] = cell.terrain == Terrain.OCEAN
        }

        val visited = BooleanArray(count)
        var floodOrderSize = 0
        val floodHeap = IntHeap(count) { a, b ->
            val elevationOrder = filledElevation[a].compareTo(filledElevation[b])
            elevationOrder < 0 || (elevationOrder == 0 && a < b)
        }

        // Every ocean tile and every bounded-map edge tile is a terminal sink.
        for (i in 0 until count) {
            val x = i % width
            val y = i / width
            if (oceanByTile[i] || x == 0 || x == width - 1 || y == 0 || y == height - 1) {
                visited[i] = true
                filledElevation[i] = elevation[i]
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
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (visited[next]) continue
                visited[next] = true
                filledElevation[next] = max(elevation[next], filledElevation[current])
                downstreamIndex[next] = current
                floodHeap.add(next)
            }
        }
        check(floodOrderSize == count) { "Bounded map perimeter must reach every tile" }

        lakeCandidates = findLakeCandidates(elevation)
        processingOrder = buildProcessingOrder()
        buildUpstreamLandArea()
        verifyProcessingOrder()
    }

    fun isLandTile(tileIndex: Int): Boolean = !oceanByTile[tileIndex]

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
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
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
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
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
                    // Build a deterministic in-basin tree to the selected lowest escape edge.
                    downstreamIndex[exitTile] = exitDownstream
                    distances.fill(-1)
                    head = 0
                    tail = 0
                    queue[tail++] = exitTile
                    distances[exitTile] = 0
                    while (head < tail) {
                        val current = queue[head++]
                        val x = current % width
                        val y = current / width
                        for (dy in -1..1) for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx !in 0 until width || ny !in 0 until height) continue
                            val next = ny * width + nx
                            if (grouped[next] != group || distances[next] >= 0) continue
                            distances[next] = distances[current] + 1
                            queue[tail++] = next
                        }
                    }

                    for (tile in basinTiles) {
                        if (tile == exitTile) continue
                        val x = tile % width
                        val y = tile / width
                        var parent = -1
                        for (dy in -1..1) for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx !in 0 until width || ny !in 0 until height) continue
                            val next = ny * width + nx
                            if (grouped[next] == group && distances[next] == distances[tile] - 1 &&
                                (parent < 0 || next < parent)
                            ) parent = next
                        }
                        check(parent >= 0) { "Depression tiles must connect to their selected spill exit" }
                        downstreamIndex[tile] = parent
                    }

                    var entryCount = 0
                    for (tile in basinTiles) {
                        val x = tile % width
                        val y = tile / width
                        for (dy in -1..1) for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx !in 0 until width || ny !in 0 until height) continue
                            val upstream = ny * width + nx
                            if (grouped[upstream] != group && downstreamIndex[upstream] == tile) entryCount++
                        }
                    }
                    val entries = IntArray(entryCount)
                    var entryOffset = 0
                    for (tile in basinTiles) {
                        val x = tile % width
                        val y = tile / width
                        for (dy in -1..1) for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx !in 0 until width || ny !in 0 until height) continue
                            val upstream = ny * width + nx
                            if (grouped[upstream] != group && downstreamIndex[upstream] == tile) {
                                entries[entryOffset++] = upstream
                            }
                        }
                    }
                    candidates += LakeCandidate(
                        id = candidates.size,
                        tileIndices = basinTiles,
                        spillElevation = exitSaddle,
                        exitTileIndex = exitTile,
                        downstreamTileIndex = exitDownstream,
                        entryTileIndices = entries
                    )
                }
            }
            group++
        }
        return candidates
    }

    private fun buildProcessingOrder(): IntArray {
        val downstreamIncomingCount = IntArray(count)
        for (tile in 0 until count) {
            val downstream = downstreamIndex[tile]
            if (downstream >= 0) downstreamIncomingCount[downstream]++
        }
        val ready = IntHeap(count) { a, b -> a < b }
        for (tile in 0 until count) if (downstreamIncomingCount[tile] == 0) ready.add(tile)

        val order = IntArray(count)
        var orderSize = 0
        while (ready.isNotEmpty()) {
            val tile = ready.removeMin()
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

    private fun verifyProcessingOrder() {
        val position = IntArray(count)
        for (orderIndex in processingOrder.indices) position[processingOrder[orderIndex]] = orderIndex
        for (tile in 0 until count) {
            val downstream = downstreamIndex[tile]
            check(downstream < 0 || position[tile] < position[downstream]) {
                "Every drainage link must point later in the routing order"
            }
        }
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
    val exitTileIndex: Int,
    val downstreamTileIndex: Int,
    /** Deterministically ordered tiles immediately upstream of this basin. */
    val entryTileIndices: IntArray
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
