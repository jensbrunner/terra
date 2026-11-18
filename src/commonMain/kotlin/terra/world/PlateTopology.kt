package terra.world

/** Four-connected periodic territories. Floods always attach a tile to an existing region. */
internal class PlateTopology(val width: Int, val height: Int) {
    private val count = width * height
    private val component = IntArray(count)
    private val queue = IntArray(count)
    private val proposed = IntArray(count)
    private val distance = IntArray(count)

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
}
