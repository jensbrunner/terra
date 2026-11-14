package terra.climate

import kotlin.math.*
import kotlin.test.*
import terra.world.*

class MoistureTransportTest {
    private val width = 96
    private val height = 72
    private val config = ClimateConfig(width = 48, height = 36)
    private fun world(ridge: Boolean, mirror: Boolean = false) = World.withElevation(7, width, height,
        TerrainGenerationSettings(), DoubleArray(width * height) { i ->
            val x = if (mirror) width - 1 - i % width else i % width
            if (x !in 16..80) 0.1 else 0.32 + if (ridge) 0.27 * exp(-((x - 40.0) / 5).pow(2)) else 0.0
        })
    private fun mean(a: SeasonalClimateAtlas, range: IntRange, mirror: Boolean = false): Double {
        var sum = 0.0; var count = 0
        for (y in 28..43) for (x in range) { sum += a.annualRainfallMm(y * width + if (mirror) width - 1 - x else x); count++ }
        return sum / count
    }
    @Test fun oceanRidgeAndInteriorProduceAWetWindwardAndDryLee() {
        val world = world(true)
        val a = ClimateAtlasBuilder(windOverride = 5.0).build(ClimateGeography(world, config))
        val flat = ClimateAtlasBuilder(windOverride = 5.0).build(ClimateGeography(world(false), config))
        val windward = mean(a, 31..39); val lee = mean(a, 48..62)
        println("RAIN_SHADOW windward_mm_year=$windward lee_mm_year=$lee flat_interior_mm_year=${mean(flat, 48..62)}")
        assertTrue(windward > 1500, "Ocean-fed windward slopes should be wet: $windward")
        assertTrue(windward > 3 * lee, "A mountain must deplete downwind moisture: $windward / $lee")
        assertTrue(lee < 500)
        assertTrue(mean(flat, 48..62) > lee * 1.3, "Removing the ridge must restore moisture inland")
        world.evolveAtlasCover(a, 0.0, initialize = true)
        var treesUpwind = 0.0; var treesLee = 0.0
        for (y in 28..43) {
            for (x in 31..39) treesUpwind += world.treeCover(x, y) / (16 * 9)
            for (x in 48..62) treesLee += world.treeCover(x, y) / (16 * 15)
        }
        println("RAIN_SHADOW tree_cover_windward=$treesUpwind tree_cover_lee=$treesLee")
        assertTrue(treesUpwind > 0.4 && treesLee < 0.2)
    }
    @Test fun reversedWindAndMirroredGeographyReverseTheRainShadow() {
        val a = ClimateAtlasBuilder(windOverride = 5.0).build(ClimateGeography(world(true), config))
        val b = ClimateAtlasBuilder(windOverride = -5.0).build(ClimateGeography(world(true, mirror = true), config))
        val west = mean(a, 31..39); val east = mean(b, 31..39, mirror = true)
        assertTrue(east > 3 * mean(b, 48..62, mirror = true))
        assertEquals(west, east, west * 0.01)
    }
    @Test fun boundedSolveAgreesWithAConvergedReferenceAcrossGeneratedWorlds() {
        for (seed in listOf(31742, 42, 777)) {
            val g = ClimateGeography(World(seed))
            val a = ClimateAtlasBuilder().build(g)
            val reference = ClimateAtlasBuilder(sweeps = 512).build(g)
            val relativeError = a.annualRain.indices.sumOf { abs(a.annualRain[it] - reference.annualRain[it]) } / reference.annualRain.sum()
            println("MOISTURE_CONVERGENCE seed=$seed sweeps=${a.moistureSweeps} max_residual_mm_day=${a.maximumMoistureResidualMmPerDay} rain_relative_error=$relativeError max_annual_error=${a.annualRain.indices.maxOf { abs(a.annualRain[it] - reference.annualRain[it]) }} reference_residual=${reference.maximumMoistureResidualMmPerDay} reference_sweeps=${reference.moistureSweeps}")
            assertTrue(relativeError < 0.01, "Insufficient convergence for $seed: $relativeError")
            assertTrue(a.maximumMoistureResidualMmPerDay < 0.02)
        }
    }
    @Test fun mixingAndRecyclingCannotCreateRainWithoutAnOceanSource() {
        val land = World.withElevation(3, width, height, TerrainGenerationSettings(), DoubleArray(width * height) { 0.32 })
        val a = ClimateAtlasBuilder().build(ClimateGeography(land, config))
        assertTrue(a.annualRain.all { it == 0.0 })
        val calm = ClimateAtlasBuilder(windOverride = 0.0).build(ClimateGeography(world(false), config))
        assertTrue(mean(calm, 17..20) > mean(calm, 48..62) * 3)
        assertTrue(calm.annualRain.all { it.isFinite() && it >= 0 })
    }

}
