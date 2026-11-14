package terra.hydrology

import terra.world.World
import kotlin.math.*

/** Fixed drainage graph, three slow stores: groundwater, channels and lakes.
 * Amounts are mm × terrain tiles. Routing is analytical at six-hour or longer
 * intervals; coarse climate phases do not need channel travel-time substeps.
 * Only water reaching an ocean tile is returned as boundary export.
 */
internal class SurfaceWater(world: World) {
    private val topology = HydrologyTopology(world)
    private val count = world.width * world.height
    private val lakeIds = IntArray(count) { -1 }
    private val basins = topology.lakeCandidates.map { Lake(it, topology.terrainElevation) }
    private val groundwater = DoubleArray(count) { if (topology.isLandTile(it)) 30.0 else 0.0 }
    private val channels = DoubleArray(count)
    private val recharge = DoubleArray(count)
    private val runoff = DoubleArray(count)
    private val incoming = DoubleArray(count)
    private val lakeIncoming = DoubleArray(basins.size)
    private val discharge = DoubleArray(count)
    private val geography: SurfaceWaterGeography
    private var pendingSeconds = 0.0
    private var revision = 0L
    private var publishedRevision = -1L
    private var published: SurfaceWaterSnapshot? = null
    private val wet = BooleanArray(count)

    init {
        for (lake in topology.lakeCandidates) for (tile in lake.tileIndices) lakeIds[tile] = lake.id
        geography = SurfaceWaterGeography(world.width, world.height,
            BooleanArray(count) { topology.isLandTile(it) }, topology.downstreamIndex,
            topology.upstreamLandTileCount, BooleanArray(count) { topology.isVirtualSpillLink(it) }, lakeIds)
        // Time-zero baseflow and channel inventories are funded by the initial
        // groundwater store, just as soil/lake inventories are initial conditions.
        for (tile in topology.processingOrder) {
            val flow = incoming[tile] + groundwater[tile] / (60 * 86400.0)
            if (lakeIds[tile] >= 0) continue
            discharge[tile] = flow
            if (!geography.land[tile]) continue
            channels[tile] = flow * 43200.0
            val downstream = topology.downstreamIndex[tile]
            if (downstream >= 0) incoming[downstream] += flow
        }
        incoming.fill(0.0)
        refreshLakes()
    }

    val totalWaterMmTiles get() = groundwater.sum() + channels.sum() + recharge.sum() + runoff.sum() + basins.sumOf { it.water }
    fun groundwaterMmAt(tile: Int) = groundwater[tile] + recharge[tile]
    fun lakeWaterMmTiles(id: Int) = basins[id].water
    fun receive(tile: Int, drainage: Double, overflow: Double) {
        recharge[tile] += drainage; runoff[tile] += overflow
    }
    fun isLakeWet(tile: Int) = wet[tile]
    fun receiveLakeRain(tile: Int, amount: Double) { basins[lakeIds[tile]].water += amount }
    fun evaporateLake(tile: Int, demand: Double): Double {
        val lake = basins[lakeIds[tile]]
        val amount = min(demand, lake.water)
        lake.water -= amount
        return amount
    }

    fun advance(seconds: Double): Double {
        pendingSeconds += seconds
        if (pendingSeconds < 21600.0) return 0.0
        val dt = pendingSeconds
        pendingSeconds = 0.0
        incoming.fill(0.0); lakeIncoming.fill(0.0)
        // Exact dS/dt=I-S/tau. All tiles share these two recession constants;
        // compute their exponential responses once per routing interval.
        val groundX = dt / (60 * 86400.0)
        val channelX = dt / 43200.0
        val groundResponse = -expm1(-groundX)
        val channelResponse = -expm1(-channelX)
        var exported = 0.0
        for (tile in topology.processingOrder) {
            val nextGround = groundwater[tile] * (1 - groundResponse) + recharge[tile] * groundResponse / groundX
            val supply = groundwater[tile] + recharge[tile] - nextGround + runoff[tile] + incoming[tile]
            groundwater[tile] = nextGround
            recharge[tile] = 0.0; runoff[tile] = 0.0
            val lakeId = lakeIds[tile]
            var out: Double
            if (lakeId >= 0) {
                val lake = basins[lakeId]
                lakeIncoming[lakeId] += supply
                if (tile != lake.candidate.exitTileIndex) { discharge[tile] = 0.0; continue }
                lake.water += lakeIncoming[lakeId]
                out = max(0.0, lake.water - lake.capacity)
                lake.water -= out
            } else if (!geography.land[tile]) {
                exported += supply
                discharge[tile] = supply / dt
                continue
            } else {
                val total = channels[tile] + supply
                val next = channels[tile] * (1 - channelResponse) + supply * channelResponse / channelX
                out = max(0.0, total - next)
                channels[tile] = next
            }
            discharge[tile] = out / dt
            val downstream = topology.downstreamIndex[tile]
            if (downstream >= 0) incoming[downstream] += out
            else channels[tile] += out // Explicit closed-world terminal, never an implicit ocean.
        }
        refreshLakes()
        revision++
        return exported
    }

    private fun refreshLakes() {
        for (lake in basins) {
            val level = lake.level()
            for (tile in lake.candidate.tileIndices) wet[tile] = topology.terrainElevation[tile] < level
        }
    }

    fun snapshot(): SurfaceWaterSnapshot {
        if (published == null || revision != publishedRevision) {
            published = SurfaceWaterSnapshot(geography, wet.copyOf(), discharge.copyOf(), revision)
            publishedRevision = revision
        }
        return published!!
    }

    /** Sorted hypsometry gives a finite volume/level relation in O(log n). */
    private class Lake(val candidate: LakeCandidate, elevation: DoubleArray) {
        private val heights = candidate.tileIndices.map { elevation[it] }.sorted().toDoubleArray()
        private val prefix = DoubleArray(heights.size + 1)
        private val threshold = DoubleArray(heights.size)
        val capacity: Double
        var water: Double
        init {
            for (i in heights.indices) {
                prefix[i + 1] = prefix[i] + heights[i]
                threshold[i] = (heights[i] * i - prefix[i]) * MM_PER_ELEVATION
            }
            capacity = heights.sumOf { max(0.0, candidate.spillElevation - it) } * MM_PER_ELEVATION
            water = if (capacity.isFinite()) capacity * 0.35 else 0.0 // Initial inventory, included in the starting water budget.
        }
        fun level(): Double {
            if (water <= 0) return heights[0]
            var low = 0; var high = heights.size
            while (low + 1 < high) {
                val mid = (low + high) / 2
                if (threshold[mid] <= water) low = mid else high = mid
            }
            val flooded = low + 1
            return min(candidate.spillElevation, (water / MM_PER_ELEVATION + prefix[flooded]) / flooded)
        }
    }
    private companion object { const val MM_PER_ELEVATION = 10_000_000.0 }
}

internal class SurfaceWaterGeography(
    val width: Int, val height: Int, val land: BooleanArray, val downstream: IntArray,
    val upstream: IntArray, val virtualSpill: BooleanArray, val lakeIds: IntArray
)

class SurfaceWaterSnapshot internal constructor(
    private val geography: SurfaceWaterGeography, private val wet: BooleanArray,
    private val discharge: DoubleArray, val revision: Long
) {
    val width get() = geography.width
    val height get() = geography.height
    private fun index(x: Int, y: Int) = y * width + x
    fun isLandTile(x: Int, y: Int) = geography.land[index(x, y)]
    fun downstreamTileIndex(x: Int, y: Int) = geography.downstream[index(x, y)]
    fun upstreamLandTileCount(x: Int, y: Int) = geography.upstream[index(x, y)]
    fun isVirtualSpillLink(x: Int, y: Int) = geography.virtualSpill[index(x, y)]
    fun isLakeTileWet(x: Int, y: Int) = wet[index(x, y)]
    fun lakeIdAt(x: Int, y: Int) = geography.lakeIds[index(x, y)]
    fun isLakeCandidateTile(x: Int, y: Int) = lakeIdAt(x, y) >= 0
    fun dischargeMmTilesPerDay(x: Int, y: Int) = discharge[index(x, y)] * 86400.0
    fun isStreamTile(x: Int, y: Int) = isLandTile(x, y) && upstreamLandTileCount(x, y) >= 12 && dischargeMmTilesPerDay(x, y) > 0.01
}
