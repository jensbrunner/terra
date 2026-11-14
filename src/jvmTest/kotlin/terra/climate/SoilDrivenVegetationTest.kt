package terra.climate

import kotlin.test.*
import terra.world.*

class SoilDrivenVegetationTest {
    private val width = 12
    private val height = 8
    private val config = ClimateConfig(width = 6, height = 4)
    private val count = width * height
    private fun world() = World.withElevation(9, width, height, TerrainGenerationSettings(),
        DoubleArray(count) { if (it % width == 0) 0.1 else 0.36 })

    private fun atlas(world: World, summerRain: Boolean): SeasonalClimateAtlas {
        fun field(value: Double) = Array(12) { DoubleArray(count) { value } }
        return SeasonalClimateAtlas(ClimateGeography(world, config), SolarConfig(),
            Array(12) { m -> DoubleArray(count) { if (m < 6) 2.0 else 20.0 } },
            Array(12) { m -> DoubleArray(count) { if ((m >= 6) == summerRain) 180.0 else 0.0 } },
            Array(12) { m -> DoubleArray(count) { if (m < 6) 10.0 else 100.0 } },
            field(0.5), field(0.0), field(0.0), 0.0, 0.0, 0, 0.0)
    }

    @Test fun dryRootZonesConstrainBothTreesAndGrassDespiteHighAnnualRainfall() {
        val dry = world(); val wet = world()
        val atlas = atlas(dry, true)
        assertTrue(atlas.aridity(1) > 1.0)
        dry.evolveAtlasCover(atlas, 0.0, initialize = true, soilAvailability = DoubleArray(count))
        wet.evolveAtlasCover(atlas, 0.0, initialize = true, soilAvailability = DoubleArray(count) { 1.0 })
        assertEquals(0.0, dry.treeCover(5, 4))
        assertEquals(0.0, dry.grassCover(5, 4))
        assertEquals(GrowthLimitation.WATER, dry.growthLimitation(5, 4))
        assertEquals(Terrain.DESERT, dry[5, 4].terrain)
        assertTrue(wet.treeCover(5, 4) > 0.5)
        assertTrue(wet.grassCover(5, 4) > 0.0)
        assertEquals(GrowthLimitation.NONE, wet.growthLimitation(5, 4))
        // Persistent growth uses the same soil constraint as initial suitability.
        wet.evolveAtlasCover(atlas, 30.0, soilAvailability = DoubleArray(count))
        assertTrue(wet.treeCover(5, 4) < 0.02)
        assertTrue(wet.grassCover(5, 4) < 0.001)
    }

    @Test fun growingSeasonRainSupportsMoreCoverThanEqualRainInTheDormantSeason() {
        val summerWorld = world(); val winterWorld = world()
        val summerAtlas = atlas(summerWorld, true); val winterAtlas = atlas(winterWorld, false)
        val summer = LandscapeWater(summerWorld, config.domainWidthMeters * config.domainHeightMeters / (width.toDouble() * height))
        val winter = LandscapeWater(winterWorld, config.domainWidthMeters * config.domainHeightMeters / (width.toDouble() * height))
        summer.initializeClimate(summerAtlas); winter.initializeClimate(winterAtlas)
        val i = 4 * width + 5
        assertEquals(summerAtlas.annualRainfallMm(i), winterAtlas.annualRainfallMm(i))
        assertEquals(summerAtlas.aridity(i), winterAtlas.aridity(i))
        assertTrue(summer.growingWaterAvailability[i] > winter.growingWaterAvailability[i] + 0.2)
        summerWorld.evolveAtlasCover(summerAtlas, 0.0, initialize = true, soilAvailability = summer.growingWaterAvailability)
        winterWorld.evolveAtlasCover(winterAtlas, 0.0, initialize = true, soilAvailability = winter.growingWaterAvailability)
        assertTrue(summerWorld.treeCover(5, 4) > winterWorld.treeCover(5, 4) + 0.2)
        assertTrue(summerWorld.bareCover(5, 4) < winterWorld.bareCover(5, 4))
    }

    @Test fun annualAridityRemainsAvailableForAtlasOnlyDiagnostics() {
        val world = world()
        world.evolveAtlasCover(atlas(world, true), 0.0, initialize = true)
        assertTrue(world.treeCover(5, 4) > 0.5)
    }
}
