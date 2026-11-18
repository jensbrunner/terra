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
}
