package terra.climate

import kotlin.math.*
import kotlin.test.*
import terra.world.*

class LandscapeEvolutionTest {
    @Test fun climateFedSoilsSupportForestsRunoffAndRecoveryOverACentury() {
        val width = 96; val height = 72
        val world = World.withElevation(7, width, height, TerrainGenerationSettings(), DoubleArray(width * height) { i ->
            val x = i % width
            if (x !in 16..80) 0.1 else 0.32 + 0.0015 * cos(2 * PI * (i / width) / 18) + 0.27 * exp(-((x - 40.0) / 5).pow(2))
        })
        val config = ClimateConfig(width = 48, height = 36)
        val atlas = ClimateAtlasBuilder(windOverride = 5.0).build(ClimateGeography(world, config))
        val surface = LandscapeWater(world, config.domainWidthMeters * config.domainHeightMeters / (width.toDouble() * height))
        surface.initializeClimate(atlas)
        world.evolveAtlasCover(atlas, 0.0, initialize = true, soilAvailability = surface.growingWaterAvailability)
        val evolution = ClimateEvolution(world, surface, 0.0)
        repeat(5) {
            val budget = evolution.advance(atlas, 20 * atlas.solar.yearSeconds)
            assertTrue(abs(budget.relativeWaterResidual) < 1e-10)
        }
        fun cover(xs: IntRange): Double = (28..43).sumOf { y -> xs.sumOf { x -> world.treeCover(x, y) } } / (16 * xs.count())
        val forest = cover(31..39)
        assertTrue(forest > 0.4 && cover(48..62) < 0.2)
        assertTrue(surface.drainage.sum() + surface.overflow.sum() > 0)
        for (i in surface.soil.indices) assertTrue(surface.soil[i] >= 0 && surface.soil[i] <= surface.soilCapacityMm[i] + 1e-8)
        assertEquals(Terrain.TUNDRA, world[60, 0].terrain)
        val lowWater = DoubleArray(width * height)
        world.evolveAtlasCover(atlas, 20.0, drought = DoubleArray(width * height) { 1.0 }, soilAvailability = lowWater)
        val depleted = cover(31..39)
        assertTrue(depleted < forest)
        world.evolveAtlasCover(atlas, 60.0, drought = lowWater, soilAvailability = surface.growingWaterAvailability)
        assertTrue(cover(31..39) > depleted)
    }
}
