package terra.world

/** Four-connected periodic territories. Floods always attach a tile to an existing region. */
internal class PlateTopology(val width: Int, val height: Int) {
    private val count = width * height
    private val component = IntArray(count)
    private val queue = IntArray(count)
    private val proposed = IntArray(count)
    private val distance = IntArray(count)
    var represented = BooleanArray(0)
        private set

    inline fun neighbors(i: Int, action: (Int) -> Unit) {
        val x = i % width; val y = i / width
        action(y * width + (x + 1) % width)
        action(y * width + (x + width - 1) % width)
        action(((y + 1) % height) * width + x)
        action(((y + height - 1) % height) * width + x)
    }

    fun repair(owner: IntArray, plateCount: Int, coverage: (Int, Int) -> Double) {
        component.fill(-1)
        val largest = IntArray(plateCount) { -1 }
        val sizes = IntArray(plateCount)
        var nextComponent = 0
        for (i in 0 until count) {
            val plate = owner[i]
            if (plate < 0 || component[i] >= 0) continue
            var head = 0; var tail = 1
            queue[0] = i; component[i] = nextComponent
            while (head < tail) {
                val tile = queue[head++]
                neighbors(tile) { j ->
                    if (component[j] < 0 && owner[j] == plate) {
                        component[j] = nextComponent; queue[tail++] = j
                    }
                }
            }
            if (tail > sizes[plate]) { largest[plate] = nextComponent; sizes[plate] = tail }
            nextComponent++
        }
        represented = BooleanArray(plateCount) { largest[it] >= 0 }
        var tail = 0
        for (i in 0 until count) {
            if (owner[i] >= 0 && component[i] == largest[owner[i]]) {
                distance[i] = 0; queue[tail++] = i
            } else { owner[i] = -1; distance[i] = -1 }
        }
        var head = 0
        while (head < tail) {
            val end = tail
            while (head < end) {
                val tile = queue[head++]
                val plate = owner[tile]
                neighbors(tile) { j ->
                    if (distance[j] < 0) {
                        distance[j] = distance[tile] + 1; proposed[j] = plate; queue[tail++] = j
                    } else if (owner[j] < 0 && distance[j] == distance[tile] + 1) {
                        val old = proposed[j]
                        val a = coverage(plate, j); val b = coverage(old, j)
                        if (a > b || a == b && plate < old) proposed[j] = plate
                    }
                }
            }
            for (i in end until tail) owner[queue[i]] = proposed[queue[i]]
        }
    }

    /** Two rooted, connected daughter regions, including winding territories across seams. */
    fun divide(owner: IntArray, parent: Int, a: Int, b: Int, bias: Double,
        cost: (Int, Int) -> Double): IntArray {
        val side = IntArray(count) { -1 }
        val heap = PlateFront()
        heap.add(a, 0, 0.0); heap.add(b, 1, bias)
        while (heap.size > 0) {
            val tile = heap.tile; val label = heap.label; val distance = heap.cost
            heap.remove()
            if (side[tile] >= 0 || tile == a && label != 0 || tile == b && label != 1) continue
            side[tile] = label
            neighbors(tile) { j ->
                if (owner[j] == parent && side[j] < 0) heap.add(j, label, distance + cost(tile, j))
            }
        }
        return side
    }
}

/** Immutable queue entries keep competing fronts deterministic without decrease-key bookkeeping. */
private class PlateFront {
    private var tiles = IntArray(256)
    private var labels = IntArray(256)
    private var costs = DoubleArray(256)
    var size = 0
        private set
    val tile get() = tiles[0]
    val label get() = labels[0]
    val cost get() = costs[0]
    private fun before(c: Double, l: Int, t: Int, i: Int) =
        c < costs[i] || c == costs[i] && (l < labels[i] || l == labels[i] && t < tiles[i])
    private fun put(i: Int, t: Int, l: Int, c: Double) { tiles[i] = t; labels[i] = l; costs[i] = c }
    fun add(tile: Int, label: Int, cost: Double) {
        if (size == tiles.size) {
            tiles = tiles.copyOf(size * 2); labels = labels.copyOf(size * 2); costs = costs.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val parent = (i - 1) / 2
            if (!before(cost, label, tile, parent)) break
            put(i, tiles[parent], labels[parent], costs[parent]); i = parent
        }
        put(i, tile, label, cost)
    }
    fun remove() {
        val last = --size
        if (last == 0) return
        val t = tiles[last]; val l = labels[last]; val c = costs[last]
        var i = 0
        while (i * 2 + 1 < size) {
            var child = i * 2 + 1
            if (child + 1 < size && before(costs[child + 1], labels[child + 1], tiles[child + 1], child)) child++
            if (!before(costs[child], labels[child], tiles[child], last)) break
            put(i, tiles[child], labels[child], costs[child]); i = child
        }
        put(i, t, l, c)
    }
}
