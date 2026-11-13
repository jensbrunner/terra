package terra.weather

import terra.hydrology.HydrologySimulation
import terra.world.Terrain
import terra.world.World
import kotlin.math.abs

/**
 * Hydrological response units: ownership-preserving groups of fine tiles sharing a
 * catchment, elevation band, land-cover class and local-slope class.
 *
 * The units own aggregated slow stores (groundwater and snow), while the fine tiles
 * retain root-zone thresholds and the distribution needed for snowline/albedo decisions.
 * A unit update distributes its release or melt back to member tiles proportionally, so
 * splitting a unit cannot create or destroy water.
 */
internal class HydrologicalResponseUnits(world: World, hydrology: HydrologySimulation) {
    private val width = world.width
    private val height = world.height
    private val tileCount = width * height

    val unitOfTile = IntArray(tileCount) { -1 }
    val count: Int
    val tiles: Array<IntArray>
    val outletTile: IntArray
    val tileCountPerUnit: IntArray
    val meanElevation: DoubleArray

    init {
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        for (tile in 0 until tileCount) {
            if (!hydrology.isLandTile(tile % width, tile / width)) continue
            val x = tile % width
            val y = tile / width
            val cell = world[x, y]
            if (cell.terrain == Terrain.OCEAN) continue
            val catchment = hydrology.catchmentIdAt(tile)
            val elevationBand = ((cell.elevation - world.seaLevel) * 4.0).toInt().coerceIn(0, 3)
            val cover = world.treeCover(x, y) + world.grassCover(x, y)
            val coverClass = when {
                cover < 0.20 -> 0
                world.treeCover(x, y) < 0.25 -> 1
                else -> 2
            }
            val east = world[(x + 1) % width, y].elevation
            val west = world[(x + width - 1) % width, y].elevation
            val north = world[x, maxOf(0, y - 1)].elevation
            val south = world[x, minOf(height - 1, y + 1)].elevation
            val slopeClass = if (abs(east - west) + abs(south - north) > 0.02) 1 else 0
            val key = (catchment.toLong() + 1) * 1_000_000L + elevationBand * 100L + coverClass * 10L + slopeClass
            groups.getOrPut(key) { ArrayList() }.add(tile)
        }
        count = groups.size
        tiles = Array(count) { IntArray(0) }
        outletTile = IntArray(count)
        tileCountPerUnit = IntArray(count)
        meanElevation = DoubleArray(count)
        var unit = 0
        for ((_, members) in groups) {
            val array = members.toIntArray()
            tiles[unit] = array
            tileCountPerUnit[unit] = array.size
            var best = array[0]
            var bestUpstream = -1
            var elevation = 0.0
            for (tile in array) {
                unitOfTile[tile] = unit
                elevation += world[tile % width, tile / width].elevation
                val upstream = hydrology.upstreamLandTileCount(tile % width, tile / width)
                if (upstream > bestUpstream) { bestUpstream = upstream; best = tile }
            }
            outletTile[unit] = best
            meanElevation[unit] = elevation / array.size
            unit++
        }
    }
}
