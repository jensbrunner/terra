package terra.climate

import kotlin.math.abs
import kotlin.test.*
import terra.world.*

class HydrologyInitializationTest {
    private val width = 24
    private val height = 16
    private val config = ClimateConfig(width = 12, height = 8)

    // A coastal slope with a four-tile depression and a separate river catchment.
    private fun world() = World.withElevation(7, width, height, TerrainGenerationSettings(),
        DoubleArray(width * height) { i ->
            val x = i % width; val y = i / width
            when {
                x < 2 -> 0.1
                x in 10..11 && y in 7..8 -> 0.34
                else -> 0.36 + x * 0.001
            }
        })

    private fun atlas(world: World, rain: (Int) -> Double, start: Double = 0.0): SeasonalClimateAtlas {
        val n = width * height
        return SeasonalClimateAtlas(ClimateGeography(world, config), SolarConfig(initialYearFraction = start),
            Array(12) { DoubleArray(n) { 20.0 } },
            Array(12) { month -> DoubleArray(n) { rain(month) } },
            Array(12) { DoubleArray(n) { 80.0 } },
            Array(12) { DoubleArray(n) { 0.5 } },
            Array(12) { DoubleArray(n) }, Array(12) { DoubleArray(n) },
            0.0, 0.0, 0, 0.0)
    }

    private fun surface(world: World) = LandscapeWater(world, config.domainWidthMeters * config.domainHeightMeters / (width.toDouble() * height))

    @Test fun dryAndWetClimatesInitializeDifferentSoilsAquifersRiversAndLakes() {
        val world = world()
        val dry = surface(world).also { it.initializeClimate(atlas(world, { 0.0 })) }
        val wetAtlas = atlas(world, { 400.0 })
        val wet = surface(world).also { it.initializeClimate(wetAtlas) }
        val duplicate = surface(world).also { it.initializeClimate(wetAtlas) }
        val dryWater = dry.surfaceWater.snapshot()
        val wetWater = wet.surfaceWater.snapshot()
        val duplicateWater = duplicate.surfaceWater.snapshot()
        var wetLakes = 0; var flowingLandTiles = 0
        for (i in wet.soil.indices) {
            val x = i % width; val y = i / width
            assertEquals(0.0, dry.soil[i])
            assertEquals(0.0, dry.surfaceWater.groundwaterMmAt(i))
            assertEquals(0.0, dryWater.dischargeMmTilesPerDay(x, y))
            assertFalse(dryWater.isLakeTileWet(x, y))
            assertTrue(wet.soil[i] in 0.0..wet.soilCapacityMm[i])
            assertEquals(wet.soil[i], duplicate.soil[i])
            assertEquals(wet.surfaceWater.groundwaterMmAt(i), duplicate.surfaceWater.groundwaterMmAt(i))
            assertEquals(wetWater.dischargeMmTilesPerDay(x, y), duplicateWater.dischargeMmTilesPerDay(x, y))
            assertEquals(wetWater.isLakeTileWet(x, y), duplicateWater.isLakeTileWet(x, y))
            if (wetWater.isLakeTileWet(x, y)) {
                wetLakes++
                val id = wetWater.lakeIdAt(x, y)
                assertTrue(wet.surfaceWater.lakeWaterMmTiles(id) > dry.surfaceWater.lakeWaterMmTiles(id))
                assertEquals(wet.surfaceWater.lakeWaterMmTiles(id), duplicate.surfaceWater.lakeWaterMmTiles(id))
            }
            if (world.isLand(x, y) && wetWater.dischargeMmTilesPerDay(x, y) > 0.01) flowingLandTiles++
        }
        assertTrue(wet.soil.sum() > 0)
        assertTrue(wet.soil.indices.sumOf { wet.surfaceWater.groundwaterMmAt(it) } > 0)
        assertTrue(wetLakes > 0)
        assertTrue(flowingLandTiles > 0)
        assertEquals(0.0, wet.exportedWaterKg)
        assertEquals(0.0, wet.rain.sum() + wet.drainage.sum() + wet.overflow.sum())
        val evolution = ClimateEvolution(world, wet, 0.0)
        assertTrue(abs(evolution.advance(wetAtlas, wetAtlas.solar.yearSeconds).relativeWaterResidual) < 1e-10)
    }

    @Test fun initialSeasonMatchesAdvancingTheSameHydrologyThroughTheAtlas() {
        val world = world()
        val forcing: (Int) -> Double = { if (it < 3) 400.0 else 0.0 }
        val januaryAtlas = atlas(world, forcing)
        val january = surface(world).also { it.initializeClimate(januaryAtlas) }
        // Includes a fractional month, so the start season is not rounded down.
        val start = 3.5 / 12
        val seasonal = surface(world).also { it.initializeClimate(atlas(world, forcing, start)) }
        ClimateEvolution(world, january, 0.0).advance(januaryAtlas, start * januaryAtlas.solar.yearSeconds)
        for (i in january.soil.indices) {
            assertEquals(january.soil[i], seasonal.soil[i], 1e-9)
            assertEquals(january.surfaceWater.groundwaterMmAt(i), seasonal.surfaceWater.groundwaterMmAt(i), 1e-9)
            assertEquals(january.surfaceWater.snapshot().isLakeTileWet(i % width, i / width),
                seasonal.surfaceWater.snapshot().isLakeTileWet(i % width, i / width))
        }
        assertTrue(january.soil.any { it > 0 })
    }
}
