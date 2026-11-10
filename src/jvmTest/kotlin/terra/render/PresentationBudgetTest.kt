package terra.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.weather.ClimateSnapshot
import terra.weather.WeatherModel
import terra.weather.WeatherSimulation
import terra.world.World

class PresentationBudgetTest {
    @Test
    fun skippedBiomePublicationRetainsAllDirtyTiles() {
        val world = World(31742, 31, 23)
        val initial = world.snapshot(1)
        world.applyClimateBiomes(ClimateSnapshot(31, 23, 365.0,
            DoubleArray(31 * 23), DoubleArray(31 * 23) { 30 * 365.0 }))
        val skipped = world.snapshot(1, initial)
        val latest = world.snapshot(1, skipped)
        val dirty = (0 until latest.changedTerrainCount).map { latest.changedTerrainIndex(it) }.toSet()
        assertTrue(dirty.isNotEmpty())
        for (i in 0 until 31 * 23) if (initial[i % 31, i / 31].terrain != latest[i % 31, i / 31].terrain)
            assertTrue(i in dirty, "Skipped publication lost terrain tile $i")
        assertEquals(skipped.changedTerrainCount, latest.changedTerrainCount)
    }

    @Test
    fun abruptSeamLakeChangePublishesCompleteFrozenPixels() {
        val width = 10
        val height = 8
        val depression = setOf(40, 49, 50, 59)
        val elevation = DoubleArray(width * height) { if (it in depression) 0.2 else 0.8 }
        elevation[5] = 0.0
        val hydrology = HydrologySimulation(HydrologyTopology(width, height, elevation,
            BooleanArray(elevation.size) { it == 5 }))
        val rasterizer = SurfaceWaterRasterizer(width, height)
        val dry = rasterizer.render(hydrology.snapshot())
        val weather = object : WeatherModel by WeatherSimulation(World(31742, width, height)) {
            override fun drainageMmThisUpdate(x: Int, y: Int) = if (y * width + x in depression) 20.0 else 0.0
            override fun overflowMmThisUpdate(x: Int, y: Int) = 0.0
            override fun lakeDirectRainMmThisUpdate(x: Int, y: Int) = 0.0
            override fun lakePotentialEvaporationMmThisUpdate(x: Int, y: Int) = 0.0
        }
        hydrology.acceptWeatherStep(weather, 86400.0)
        val wetSnapshot = hydrology.snapshot()
        val wet = rasterizer.render(wetSnapshot)
        assertTrue(wet[1, 13].a > 0 && wet[28, 13].a > 0, "Both sides of the seam must show the lake")
        val expected = IntArray(wet.width * wet.height).also { wet.copyInto(it) }
        hydrology.evaporateLakeWaterMmTiles(0, 4, hydrology.lakeStorageMmTiles(0))
        rasterizer.render(hydrology.snapshot())
        val retained = IntArray(expected.size).also { wet.copyInto(it) }
        assertTrue(expected.contentEquals(retained))
        for (y in 0 until dry.height) for (x in 0 until dry.width) assertEquals(0, dry[x, y].a)
        assertTrue(wetSnapshot.isLakeTileWet(0, 4))
    }
}
