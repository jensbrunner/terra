package terra.hydrology

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.atmosphere.AtmosphereTerrain
import terra.atmosphere.AtmosphereTileMapping
import terra.atmosphere.DryAtmosphereConfig
import terra.weather.WeatherModel
import terra.weather.WeatherSimulation
import terra.world.World

class GlobalHydrologyTest {
    @Test
    fun seededTerrainIsSmoothAndPeriodicAndAtmosphericSlopesCrossTheSeam() {
        val world = World(31742, 31, 23)
        val repeated = World(31742, 31, 23)
        for (y in 0 until world.height) for (x in 0 until world.width) {
            assertEquals(world[x, y], repeated[x, y])
            assertEquals(world[x, y], world[x + world.width, y])
        }
        val epsilon = 0.001
        for (y in listOf(0.5, 10.5, 22.5)) {
            assertEquals(world.sampleElevation(0.0, y), world.sampleElevation(31.0, y))
            val left = (world.sampleElevation(0.0, y) - world.sampleElevation(-epsilon, y)) / epsilon
            val right = (world.sampleElevation(epsilon, y) - world.sampleElevation(0.0, y)) / epsilon
            assertTrue(abs(left - right) < 1e-4)
        }
        val grid = DryAtmosphereConfig(width = 8, height = 6)
        val mapping = AtmosphereTileMapping(8, 6, grid)
        val heights = DoubleArray(48) { 1000 * sin(2 * PI * (it % 8) / 8) }
        val terrain = AtmosphereTerrain(mapping, heights)
        assertEquals((heights[1] - heights[7]) / (2 * grid.dx), terrain.slopeEast[0], 1e-15)
        assertTrue(terrain.upliftMetersPerSecond(0, 20.0, 0.0) > 0)
        // One ordinary full-size construction checks compound basins/seam links,
        // without a costly full-size atmospheric run or a seed sweep.
        val planet = HydrologyTopology(World(31742))
        assertOceanTerminalsAndOrder(planet, BooleanArray(planet.width * planet.height) { !planet.isLandTile(it) })
    }

    @Test
    fun drainageCrossesLongitudeAndOceanReturnsAreInternalAndFinite() {
        val width = 8
        val height = 6
        val elevation = DoubleArray(width * height) { 0.9 }
        val oceanTile = 2 * width
        elevation[oceanTile] = 0.0
        elevation[oceanTile + width - 1] = 0.4
        val ocean = BooleanArray(elevation.size) { it == oceanTile }
        val topology = HydrologyTopology(width, height, elevation, ocean)
        assertEquals(oceanTile, topology.downstreamIndex[oceanTile + width - 1])
        assertOceanTerminalsAndOrder(topology, ocean)
        val hydrology = HydrologySimulation(topology)
        val weather = RoutingWeather(width, height) { tile -> if (ocean[tile]) 0.0 else 1.0 }
        val before = hydrology.oceanWaterMmTiles
        hydrology.acceptWeatherStep(weather, 86400.0)
        assertEquals(47.0, hydrology.outletMmTilesTotal, 1e-10)
        assertEquals(before + 47, hydrology.oceanWaterMmTiles, 1e-10)
        assertEquals(before + 47, hydrology.storedAndPendingWaterMmTiles, 1e-10)
        weather.runoff = { 0.0 }
        hydrology.acceptWeatherStep(weather, 0.0)
        hydrology.acceptWeatherStep(weather, 86400.0)
        assertEquals(before + 47, hydrology.oceanWaterMmTiles, 1e-10)
        val available = hydrology.oceanWaterMmTiles
        assertEquals(available, hydrology.evaporateOceanWaterMmTiles(available + 100))
        assertEquals(0.0, hydrology.oceanWaterMmTiles)
        hydrology.receiveOceanWaterMmTiles(3.0)
        assertEquals(3.0, hydrology.storedAndPendingWaterMmTiles)
    }

    @Test
    fun seamSpanningLakeStoresThenSpillsOnceWithoutCycles() {
        val width = 10
        val height = 8
        val elevation = DoubleArray(width * height) { 0.8 }
        val depression = setOf(4 * width, 4 * width + 9, 5 * width, 5 * width + 9)
        for (tile in depression) elevation[tile] = 0.2
        elevation[5] = 0.0
        val ocean = BooleanArray(elevation.size) { it == 5 }
        val topology = HydrologyTopology(width, height, elevation, ocean)
        assertOceanTerminalsAndOrder(topology, ocean)
        val basin = topology.lakeCandidates.single()
        assertEquals(depression, basin.tileIndices.toSet())
        val hydrology = HydrologySimulation(topology)
        val weather = RoutingWeather(width, height) { if (it in depression) 20.0 else 0.0 }
        val oceanBefore = hydrology.oceanWaterMmTiles
        hydrology.acceptWeatherStep(weather, 86400.0)
        assertEquals(80.0, hydrology.lakeStorageMmTiles(0), 1e-9)
        assertEquals(oceanBefore, hydrology.oceanWaterMmTiles)
        assertTrue(hydrology.isLakeTileWet(0, 4) && hydrology.isLakeTileWet(9, 4))
        weather.runoff = { if (it in depression) 1000.0 else 0.0 }
        hydrology.acceptWeatherStep(weather, 86400.0)
        val capacity = hydrology.lakeCapacityMmTiles(0)
        assertEquals(capacity, hydrology.lakeStorageMmTiles(0), 1e-9)
        assertEquals(oceanBefore + 4080 - capacity, hydrology.oceanWaterMmTiles, 1e-9)
        assertEquals(oceanBefore + 4080, hydrology.storedAndPendingWaterMmTiles, 1e-9)
    }

    @Test
    fun anAllLandWorldRetainsRunoffInAnExplicitClosedBasin() {
        val width = 6
        val height = 5
        val elevation = DoubleArray(width * height) { 0.3 + (it / width) * 0.02 + (it % width) * 0.01 }
        val topology = HydrologyTopology(width, height, elevation, BooleanArray(elevation.size))
        assertEquals(1, topology.lakeCandidates.size)
        assertEquals(-1, topology.lakeCandidates[0].downstreamTileIndex)
        val hydrology = HydrologySimulation(topology)
        val weather = RoutingWeather(width, height) { 1.0 }
        hydrology.acceptWeatherStep(weather, 86400.0)
        assertEquals(0.0, hydrology.outletMmTilesTotal)
        assertEquals(30.0, hydrology.storedAndPendingWaterMmTiles)
        assertEquals(30.0, hydrology.lakeStorageMmTiles(0))
        assertTrue(hydrology.isLakeTileWet(0, 0))
        assertEquals(10.0, hydrology.evaporateLakeWaterMmTiles(0, 0, 10.0))
        assertEquals(20.0, hydrology.storedAndPendingWaterMmTiles)
    }

    private fun assertOceanTerminalsAndOrder(topology: HydrologyTopology, ocean: BooleanArray) {
        val positions = IntArray(ocean.size)
        topology.processingOrder.forEachIndexed { position, tile -> positions[tile] = position }
        for (tile in ocean.indices) {
            val downstream = topology.downstreamIndex[tile]
            if (downstream < 0) assertTrue(ocean[tile], "Polar/longitude land cannot export water")
            else assertTrue(positions[tile] < positions[downstream])
        }
    }
}

private class RoutingWeather(width: Int, height: Int, var runoff: (Int) -> Double) :
    WeatherModel by WeatherSimulation(World(31742, width, height)) {
    override val width = width
    override val height = height
    override fun drainageMmThisUpdate(x: Int, y: Int) = runoff(y * width + x)
    override fun overflowMmThisUpdate(x: Int, y: Int) = 0.0
    override fun lakeDirectRainMmThisUpdate(x: Int, y: Int) = 0.0
    override fun lakePotentialEvaporationMmThisUpdate(x: Int, y: Int) = 0.0
}
