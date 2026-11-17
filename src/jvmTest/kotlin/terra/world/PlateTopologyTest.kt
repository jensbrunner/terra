package terra.world

import kotlin.test.*

class PlateTopologyTest {
    private fun connected(topology: PlateTopology, labels: IntArray, id: Int) {
        val seed = labels.indexOf(id)
        assertTrue(seed >= 0)
        val seen = BooleanArray(labels.size)
        val queue = ArrayDeque<Int>(); queue.add(seed); seen[seed] = true
        while (queue.isNotEmpty()) topology.neighbors(queue.removeFirst()) { j ->
            if (!seen[j] && labels[j] == id) { seen[j] = true; queue.add(j) }
        }
        assertEquals(labels.count { it == id }, seen.count { it })
    }

    @Test fun detachedPatchesAndHolesAttachToPeriodicCoresDeterministically() {
        val topology = PlateTopology(12, 8)
        val labels = IntArray(96) { if (it % 12 < 6) 0 else 1 }
        labels[3 * 12 + 9] = 0
        labels[4 * 12 + 2] = 1
        labels[0] = -1; labels[95] = -1
        val again = labels.copyOf()
        topology.repair(labels, 2) { _, _ -> 1.0 }
        topology.repair(again, 2) { _, _ -> 1.0 }
        assertContentEquals(labels, again)
        assertEquals(1, labels[3 * 12 + 9]); assertEquals(0, labels[4 * 12 + 2])
        assertTrue(labels.all { it >= 0 })
        connected(topology, labels, 0); connected(topology, labels, 1)
    }

    @Test fun weaknessGuidesJaggedConnectedCutsAcrossTheSeam() {
        val topology = PlateTopology(32, 24)
        // A parent straddling longitude zero; both daughters must retain periodic connectivity.
        val parent = IntArray(32 * 24) { if (it % 32 < 12 || it % 32 >= 20) 0 else 2 }
        fun cut(offset: Int): IntArray = topology.divide(parent, 0, 3, 23 * 32 + 3, 0.0) { _, to ->
            val weakRow = 10 + ((to % 32 + offset) / 3 % 4)
            if (to / 32 == weakRow) 30.0 else 1.0
        }
        val a = cut(0); val b = cut(5)
        assertContentEquals(a, cut(0))
        assertFalse(a.contentEquals(b))
        connected(topology, a, 0); connected(topology, a, 1)
        val interfaceRows = a.indices.filter { a[it] == 1 && a[((it / 32 + 23) % 24) * 32 + it % 32] == 0 }
            .map { it / 32 }.toSet()
        assertTrue(interfaceRows.size > 1)
    }
}
