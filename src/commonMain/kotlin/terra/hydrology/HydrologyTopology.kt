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
            LakeCandidate(0, IntArray(count) { it }, Double.POSITIVE_INFINITY, closedRoot, -1, IntArray(0))
        )
        processingOrder = buildProcessingOrder()
        buildUpstreamLandArea()
        verifyProcessingOrder()
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
        val nextTileInBasin = IntArray(count) { -1 }
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
                        nextTileInBasin = nextTileInBasin,
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

    /** Split one compound depression into downhill catchments joined at explicit saddle edges. */
    private fun buildSubBasins(
        tiles: IntArray,
        grouped: IntArray,
        group: Int,
        elevation: DoubleArray,
        externalExitTile: Int,
        externalDownstreamTile: Int,
        externalSpillElevation: Double,
        basinOwner: IntArray,
        nextTileInBasin: IntArray,
        distances: IntArray,
        queue: IntArray,
        candidates: MutableList<LakeCandidate>
    ) {
        val elevationOrder = tiles.copyOf()
        sortTilesByElevation(elevationOrder, elevation)
        var basinCount = 0

        // Strictly descend by elevation, breaking flats toward the lower row-major index.
        for (tile in elevationOrder) {
            val x = tile % width
            val y = tile / width
            var lowerNeighbor = -1
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = (x + dx + width) % width
                val ny = y + dy
                if (ny !in 0 until height) continue
                val neighbor = ny * width + nx
                if (grouped[neighbor] != group) continue
                val lower = elevation[neighbor] < elevation[tile] ||
                    (elevation[neighbor] == elevation[tile] && neighbor < tile)
                if (!lower) continue
                if (lowerNeighbor < 0 || elevation[neighbor] < elevation[lowerNeighbor] ||
                    (elevation[neighbor] == elevation[lowerNeighbor] && neighbor < lowerNeighbor)
                ) lowerNeighbor = neighbor
            }
            if (lowerNeighbor >= 0) {
                basinOwner[tile] = basinOwner[lowerNeighbor]
            } else {
                basinOwner[tile] = basinCount
                basinCount++
            }
        }
        check(basinCount > 0) { "Every depression must contain a local minimum" }

        val basinSizes = IntArray(basinCount)
        val basinMinimumElevation = DoubleArray(basinCount) { Double.POSITIVE_INFINITY }
        val basinHeads = IntArray(basinCount) { -1 }
        for (tile in tiles) {
            val basin = basinOwner[tile]
            basinSizes[basin]++
            basinMinimumElevation[basin] = minOf(basinMinimumElevation[basin], elevation[tile])
            nextTileInBasin[tile] = basinHeads[basin]
            basinHeads[basin] = tile
        }

        val edgeCapacity = tiles.size * 4
        val edgeFromBasin = IntArray(edgeCapacity)
        val edgeToBasin = IntArray(edgeCapacity)
        val edgeFromTile = IntArray(edgeCapacity)
        val edgeToTile = IntArray(edgeCapacity)
        val edgeSaddle = DoubleArray(edgeCapacity)
        var edgeCount = 0
        for (tile in tiles) {
            val x = tile % width
            val y = tile / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dy < 0 || (dy == 0 && dx <= 0)) continue
                if (dx == 0 && dy == 0) continue
                val nx = (x + dx + width) % width
                val ny = y + dy
                if (ny !in 0 until height) continue
                val neighbor = ny * width + nx
                if (grouped[neighbor] != group) continue
                val from = basinOwner[tile]
                val to = basinOwner[neighbor]
                if (from == to) continue
                edgeFromBasin[edgeCount] = from
                edgeToBasin[edgeCount] = to
                edgeFromTile[edgeCount] = tile
                edgeToTile[edgeCount] = neighbor
                edgeSaddle[edgeCount] = max(elevation[tile], elevation[neighbor])
                edgeCount++
            }
        }

        val rootBasin = basinOwner[externalExitTile]
        val parentSet = IntArray(basinCount) { it }
        val setRank = IntArray(basinCount)
        val edgeOrder = IntArray(edgeCount) { it }
        sortSaddleEdges(edgeOrder, edgeSaddle, edgeFromBasin, edgeToBasin, edgeFromTile, edgeToTile)

        val mstHead = IntArray(basinCount) { -1 }
        val mstTo = IntArray(max(0, 2 * (basinCount - 1)))
        val mstEdge = IntArray(mstTo.size)
        val mstNext = IntArray(mstTo.size) { -1 }
        var mstArcCount = 0
        var acceptedEdges = 0
        for (edge in edgeOrder) {
            val leftRoot = findSet(edgeFromBasin[edge], parentSet)
            val rightRoot = findSet(edgeToBasin[edge], parentSet)
            if (leftRoot == rightRoot) continue
            unionSets(leftRoot, rightRoot, parentSet, setRank)
            mstTo[mstArcCount] = edgeToBasin[edge]
            mstEdge[mstArcCount] = edge
            mstNext[mstArcCount] = mstHead[edgeFromBasin[edge]]
            mstHead[edgeFromBasin[edge]] = mstArcCount++
            mstTo[mstArcCount] = edgeFromBasin[edge]
            mstEdge[mstArcCount] = edge
            mstNext[mstArcCount] = mstHead[edgeToBasin[edge]]
            mstHead[edgeToBasin[edge]] = mstArcCount++
            acceptedEdges++
            if (acceptedEdges == basinCount - 1) break
        }
        check(acceptedEdges == basinCount - 1) { "Depression catchments must connect by saddle edges" }

        val basinParent = IntArray(basinCount) { -2 }
        val parentEdge = IntArray(basinCount) { -1 }
        var queueHead = 0
        var queueTail = 0
        basinParent[rootBasin] = -1
        queue[queueTail++] = rootBasin
        while (queueHead < queueTail) {
            val basin = queue[queueHead++]
            var arc = mstHead[basin]
            while (arc >= 0) {
                val child = mstTo[arc]
                if (basinParent[child] == -2) {
                    basinParent[child] = basin
                    parentEdge[child] = mstEdge[arc]
                    queue[queueTail++] = child
                }
                arc = mstNext[arc]
            }
        }

        val basinExitTile = IntArray(basinCount)
        val basinDownstreamTile = IntArray(basinCount)
        val basinSpillElevation = DoubleArray(basinCount)
        basinExitTile[rootBasin] = externalExitTile
        basinDownstreamTile[rootBasin] = externalDownstreamTile
        basinSpillElevation[rootBasin] = externalSpillElevation
        for (basin in 0 until basinCount) {
            if (basin == rootBasin) continue
            val edge = parentEdge[basin]
            val from = edgeFromBasin[edge]
            if (from == basin) {
                basinExitTile[basin] = edgeFromTile[edge]
                basinDownstreamTile[basin] = edgeToTile[edge]
            } else {
                basinExitTile[basin] = edgeToTile[edge]
                basinDownstreamTile[basin] = edgeFromTile[edge]
            }
            basinSpillElevation[basin] = edgeSaddle[edge]
        }

        val hasReservoir = BooleanArray(basinCount)
        var reservoirCount = 0
        for (basin in 0 until basinCount) {
            hasReservoir[basin] = basinSizes[basin] >= MIN_LAKE_BASIN_TILES &&
                basinSpillElevation[basin] - basinMinimumElevation[basin] >= MIN_LAKE_DEPRESSION_DEPTH
            if (hasReservoir[basin]) reservoirCount++
        }

        // Route every catchment toward its parent saddle; only qualifying catchments store water.
        for (basin in 0 until basinCount) {
            val exitTile = basinExitTile[basin]
            var tile = basinHeads[basin]
            while (tile >= 0) {
                distances[tile] = -1
                tile = nextTileInBasin[tile]
            }
            queueHead = 0
            queueTail = 0
            queue[queueTail++] = exitTile
            distances[exitTile] = 0
            while (queueHead < queueTail) {
                val current = queue[queueHead++]
                val x = current % width
                val y = current / width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = (x + dx + width) % width
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (grouped[next] != group || basinOwner[next] != basin || distances[next] >= 0) continue
                    distances[next] = distances[current] + 1
                    queue[queueTail++] = next
                }
            }
            tile = basinHeads[basin]
            while (tile >= 0) {
                if (tile != exitTile) {
                    val x = tile % width
                    val y = tile / width
                    var parent = -1
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = (x + dx + width) % width
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        val next = ny * width + nx
                        if (grouped[next] == group && basinOwner[next] == basin &&
                            distances[next] == distances[tile] - 1 && (parent < 0 || next < parent)
                        ) parent = next
                    }
                    check(parent >= 0) { "Catchment tiles must connect to their selected saddle" }
                    downstreamIndex[tile] = parent
                }
                tile = nextTileInBasin[tile]
            }
            downstreamIndex[exitTile] = basinDownstreamTile[basin]
        }

        if (reservoirCount == 0) return
        for (basin in 0 until basinCount) {
            if (!hasReservoir[basin]) continue
            val basinTiles = IntArray(basinSizes[basin])
            var tileOffset = 0
            var tile = basinHeads[basin]
            while (tile >= 0) {
                basinTiles[tileOffset++] = tile
                tile = nextTileInBasin[tile]
            }
            var entryCount = 0
            for (tile in basinTiles) entryCount += countSubBasinEntries(
                tile, group, basin, grouped, basinOwner
            )
            val entries = IntArray(entryCount)
            var entryOffset = 0
            for (tile in basinTiles) {
                val x = tile % width
                val y = tile / width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = (x + dx + width) % width
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    val upstream = ny * width + nx
                    if (downstreamIndex[upstream] == tile &&
                        (grouped[upstream] != group || basinOwner[upstream] != basin)
                    ) entries[entryOffset++] = upstream
                }
            }
            candidates += LakeCandidate(
                id = candidates.size,
                tileIndices = basinTiles,
                spillElevation = basinSpillElevation[basin],
                exitTileIndex = basinExitTile[basin],
                downstreamTileIndex = basinDownstreamTile[basin],
                entryTileIndices = entries
            )
        }
    }

    private fun countSubBasinEntries(
        tile: Int,
        group: Int,
        basin: Int,
        grouped: IntArray,
        basinOwner: IntArray
    ): Int {
        val x = tile % width
        val y = tile / width
        var result = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = (x + dx + width) % width
            val ny = y + dy
            if (ny !in 0 until height) continue
            val upstream = ny * width + nx
            if (downstreamIndex[upstream] == tile &&
                (grouped[upstream] != group || basinOwner[upstream] != basin)
            ) result++
        }
        return result
    }

    private fun findSet(value: Int, parent: IntArray): Int {
        var root = value
        while (parent[root] != root) root = parent[root]
        var current = value
        while (parent[current] != current) {
            val next = parent[current]
            parent[current] = root
            current = next
        }
        return root
    }

    private fun unionSets(left: Int, right: Int, parent: IntArray, rank: IntArray) {
        var leftRoot = left
        var rightRoot = right
        if (rank[leftRoot] < rank[rightRoot]) {
            val swap = leftRoot
            leftRoot = rightRoot
            rightRoot = swap
        }
        parent[rightRoot] = leftRoot
        if (rank[leftRoot] == rank[rightRoot]) rank[leftRoot]++
    }

    private fun sortTilesByElevation(tiles: IntArray, elevation: DoubleArray) {
        fun before(left: Int, right: Int): Boolean = elevation[left] < elevation[right] ||
            (elevation[left] == elevation[right] && left < right)
        fun siftDown(rootStart: Int, end: Int) {
            var root = rootStart
            while (root * 2 + 1 <= end) {
                val left = root * 2 + 1
                val right = left + 1
                var child = left
                if (right <= end && before(tiles[left], tiles[right])) child = right
                if (!before(tiles[root], tiles[child])) return
                val swap = tiles[root]
                tiles[root] = tiles[child]
                tiles[child] = swap
                root = child
            }
        }
        for (start in tiles.size / 2 - 1 downTo 0) siftDown(start, tiles.lastIndex)
        for (end in tiles.lastIndex downTo 1) {
            val swap = tiles[0]
            tiles[0] = tiles[end]
            tiles[end] = swap
            siftDown(0, end - 1)
        }
    }

    private fun sortSaddleEdges(
        order: IntArray,
        saddle: DoubleArray,
        fromBasin: IntArray,
        toBasin: IntArray,
        fromTile: IntArray,
        toTile: IntArray
    ) {
        fun before(a: Int, b: Int): Boolean {
            if (saddle[a] != saddle[b]) return saddle[a] < saddle[b]
            val aLow = minOf(fromBasin[a], toBasin[a])
            val bLow = minOf(fromBasin[b], toBasin[b])
            if (aLow != bLow) return aLow < bLow
            val aHigh = max(fromBasin[a], toBasin[a])
            val bHigh = max(fromBasin[b], toBasin[b])
            if (aHigh != bHigh) return aHigh < bHigh
            if (fromTile[a] != fromTile[b]) return fromTile[a] < fromTile[b]
            return toTile[a] < toTile[b]
        }
        fun siftDown(rootStart: Int, end: Int) {
            var root = rootStart
            while (root * 2 + 1 <= end) {
                val left = root * 2 + 1
                val right = left + 1
                var child = left
                if (right <= end && before(order[left], order[right])) child = right
                if (!before(order[root], order[child])) return
                val swap = order[root]
                order[root] = order[child]
                order[child] = swap
                root = child
            }
        }
        for (start in order.size / 2 - 1 downTo 0) siftDown(start, order.lastIndex)
        for (end in order.lastIndex downTo 1) {
            val swap = order[0]
            order[0] = order[end]
            order[end] = swap
            siftDown(0, end - 1)
        }
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
