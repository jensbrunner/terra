package terra.hydrology

import terra.weather.WeatherModel
import terra.world.World
import kotlin.math.max

/** A drainage visualization diagnosed from recent rain and soil wetness. No water
 * inventory, channel timestep, groundwater, or delayed routing transactions.
 * Static D8 terrain still determines the river paths and lake footprints.
 */
internal class SurfaceWater(world: World) {
    private val topology = HydrologyTopology(world)
    private val count = world.width * world.height
    private val lakes = IntArray(count) { -1 }
    private val geography: SurfaceWaterGeography
    private val flow = DoubleArray(count)
    private val wet = BooleanArray(count)
    private var revision = 0L
    init {
        for (lake in topology.lakeCandidates) for (tile in lake.tileIndices) lakes[tile] = lake.id
        geography = SurfaceWaterGeography(world.width, world.height,
            BooleanArray(count) { topology.isLandTile(it) }, topology.downstreamIndex,
            topology.upstreamLandTileCount, BooleanArray(count) { topology.isVirtualSpillLink(it) }, lakes)
    }
    fun snapshot(weather: WeatherModel): SurfaceWaterSnapshot {
        for (i in flow.indices) {
            val x = i % weather.width; val y = i / weather.width
            flow[i] = if (topology.isLandTile(i)) weather.thirtyDayRainMmPerHour(x, y) * 24 *
                (0.1 + 0.6 * weather.soilMoisture(x, y).coerceIn(0.0, 1.0)) else 0.0
        }
        for (i in topology.processingOrder) {
            val downstream = topology.downstreamIndex[i]
            if (downstream >= 0) flow[downstream] += flow[i]
        }
        wet.fill(false)
        for (lake in topology.lakeCandidates) {
            if (!lake.spillElevation.isFinite()) continue
            val supply = lake.tileIndices.maxOfOrNull { flow[it] } ?: 0.0
            // Evaporative demand competes with catchment supply; this is a wetness
            // diagnosis, not an accumulated lake volume or storage capacity.
            val demand = lake.tileIndices.sumOf { tile ->
                max(0.2, (weather.surfaceTemperatureC(tile % weather.width, tile / weather.width) + 5) / 15)
            }
            if (supply > demand) for (tile in lake.tileIndices)
                wet[tile] = topology.terrainElevation[tile] < lake.spillElevation
        }
        return SurfaceWaterSnapshot(geography, ++revision, flow.copyOf(), wet.copyOf())
    }
}

internal class SurfaceWaterGeography(
    val width: Int, val height: Int, val land: BooleanArray, val downstream: IntArray,
    val upstream: IntArray, val virtualSpill: BooleanArray, val lakeIds: IntArray
)

class SurfaceWaterSnapshot internal constructor(
    private val geography: SurfaceWaterGeography, val revision: Long,
    private val discharge: DoubleArray, private val wet: BooleanArray
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
    fun dischargeMmTilesPerDay(x: Int, y: Int) = discharge[index(x, y)]
    fun isStreamTile(x: Int, y: Int) = isLandTile(x, y) && upstreamLandTileCount(x, y) >= 12 && dischargeMmTilesPerDay(x, y) >= 2
}
