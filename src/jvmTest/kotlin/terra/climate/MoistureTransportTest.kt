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
            val reference = ClimateAtlasBuilder(sweeps = 4096, moistureToleranceMmPerDay = 1e-4).build(g)
            val relativeError = a.annualRain.indices.sumOf { abs(a.annualRain[it] - reference.annualRain[it]) } / reference.annualRain.sum()
            println("MOISTURE_CONVERGENCE seed=$seed sweeps=${a.moistureSweeps} max_residual_mm_day=${a.maximumMoistureResidualMmPerDay} rain_relative_error=$relativeError max_annual_error=${a.annualRain.indices.maxOf { abs(a.annualRain[it] - reference.annualRain[it]) }} reference_residual=${reference.maximumMoistureResidualMmPerDay} reference_sweeps=${reference.moistureSweeps}")
            assertTrue(relativeError < 0.001, "Insufficient convergence for $seed: $relativeError")
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

    @Test fun variableFaceWindsConserveMoistureAcrossTheSeamAndClosedPolarBoundaries() {
        val w = 24; val h = 16
        val world = World.withElevation(5, w, h, TerrainGenerationSettings(),
            DoubleArray(w * h) { if (it % w < 5) 0.1 else 0.32 })
        val g = ClimateGeography(world)
        val temperature = DoubleArray(g.count) { 20.0 }
        for (direction in listOf(-1.0, 1.0)) {
            val u = DoubleArray(g.count) { direction * (5 + 4 * sin(2 * PI * (it % w) / w)) }
            // Deliberately nonzero boundary-cell winds: the external faces must stay closed.
            val v = DoubleArray(g.count) { 3 * cos(2 * PI * (it / w) / (h - 1)) }
            val rain = DoubleArray(g.count); val humidity = DoubleArray(g.count)
            val solve = ClimateAtlasBuilder(moistureToleranceMmPerDay = 1e-5).diagnoseRainfall(g, temperature, u, v, rain, humidity)
            val q = solve.moisture
            assertTrue(q.all { it.isFinite() && it >= 0 })
            assertTrue(humidity.all { it in 0.0..1.0 })
            assertTrue(solve.residualMmPerDay < ClimateAtlasBuilder.MOISTURE_TOLERANCE_MM_DAY)
            var exchange = 0.0; var evaporated = 0.0; var precipitation = 0.0; var landRain = 0.0
            for (i in q.indices) {
                val p = rain[i] * 12 / 365
                exchange += (1 - g.landFraction[i]) * 1.2 * (0.8 * solve.capacity[i] - q[i])
                evaporated += solve.landEvaporation[i]
                precipitation += p
                landRain += g.landFraction[i] * p
            }
            assertEquals(precipitation, exchange + evaporated, precipitation * 1e-4)
            var netCoastFlux = 0.0
            fun face(i: Int, j: Int, wind: Double, spacing: Double) {
                val flux = wind * (if (wind >= 0) q[i] else q[j]) * 86400 / spacing +
                    ClimateAtlasBuilder.MOISTURE_DIFFUSIVITY_M2_S * 86400 / (spacing * spacing) * (q[i] - q[j])
                netCoastFlux += flux * (g.landFraction[j] - g.landFraction[i])
            }
            for (i in q.indices) {
                val east = i / w * w + (i % w + 1) % w
                face(i, east, (u[i] + u[east]) / 2, g.config.dx)
                if (i / w + 1 < h) face(i, i + w, (v[i] + v[i + w]) / 2, g.config.dy)
            }
            assertEquals(landRain - evaporated, netCoastFlux, landRain * 1e-4)
        }
    }

    @Test fun condensationNeedsMoistureAndLiftingOrSupersaturation() {
        val builder = ClimateAtlasBuilder()
        val capacity = builder.saturationColumnMm(20.0)
        assertEquals(0.0, builder.liftingHeightMeters(0.0, 0.0))
        assertEquals(0.0, builder.condensateMm(capacity * 0.8, capacity, 0.0, 0.25))
        assertEquals(10.0, builder.condensateMm(capacity + 10, capacity, 0.0, 0.25), 1e-12)
        val thermal = builder.liftingCondensationFraction(20.0, builder.liftingHeightMeters(0.0, 250.0))
        val forced = builder.liftingCondensationFraction(20.0, builder.liftingHeightMeters(0.3, 0.0))
        for (fraction in listOf(thermal, forced)) {
            assertTrue(fraction in 0.0..1.0)
            assertEquals(0.0, builder.condensateMm(0.0, capacity, fraction, 0.25))
            assertTrue(builder.condensateMm(capacity * 0.8, capacity, fraction, 0.25) > 0)
            assertTrue(builder.condensateMm(capacity * 0.05, capacity, fraction, 0.25) <
                builder.condensateMm(capacity * 0.8, capacity, fraction, 0.25) / 100)
            var previous = 0.0
            for (step in 0..200) {
                val moisture = capacity * step / 100
                val condensed = builder.condensateMm(moisture, capacity, fraction, 0.25)
                assertTrue(condensed >= previous && condensed <= moisture)
                previous = condensed
            }
            val epsilon = capacity * 1e-6
            val atSaturation = builder.condensateMm(capacity, capacity, fraction, 0.25)
            assertEquals(atSaturation, builder.condensateMm(capacity - epsilon, capacity, fraction, 0.25), epsilon)
            assertEquals(atSaturation, builder.condensateMm(capacity + epsilon, capacity, fraction, 0.25), epsilon * 1.01)
        }
        assertTrue(builder.saturationColumnMm(-40.0) < 2.0)
        assertTrue(builder.saturationColumnMm(40.0) > 125.0)
    }

    @Test fun solarConvectionRainsOverMoistLandAndOceanButNotInUnsaturatedPolarNight() {
        val w = 24; val h = 16
        val g = ClimateGeography(World.withElevation(6, w, h, TerrainGenerationSettings(),
            DoubleArray(w * h) { if (it % w in 9..14) 0.32 else 0.1 }))
        val solar = SolarConfig(axialTiltDegrees = 90.0)
        val builder = ClimateAtlasBuilder(solar, moistureToleranceMmPerDay = 1e-5)
        val rain = DoubleArray(g.count); val humidity = DoubleArray(g.count)
        val t = DoubleArray(g.count) { 20.0 }
        // Calm air isolates thermal lifting; uniform temperature removes advective cooling.
        val solved = builder.diagnoseRainfall(g, t, DoubleArray(g.count), DoubleArray(g.count), rain, humidity, 9)
        val sun = PlanetarySunlight(1, h, solar, northUp = false).also { it.update(9.5 * solar.yearSeconds / 12) }
        var wetLand = false; var wetOcean = false; var moistNight = false
        for (i in rain.indices) {
            if (sun.dailyMeanInputFactor(i / w) == 0.0) {
                assertEquals(0.0, rain[i], 1e-8)
                moistNight = moistNight || solved.moisture[i] > 1.0
            } else if (rain[i] > 1.0) {
                if (g.landFraction[i] > 0.5) wetLand = true else wetOcean = true
            }
        }
        assertTrue(wetLand && wetOcean && moistNight)
    }

    @Test fun landEvaporationIsLimitedByWaterAndEnergyWithoutAnArbitraryRecyclingFraction() {
        val builder = ClimateAtlasBuilder()
        for (rain in listOf(0.0, 0.01, 0.5, 2.0, 10.0, 100.0)) {
            for (demand in listOf(0.0, 0.01, 0.5, 2.0, 8.0)) {
                val actual = builder.actualEvaporationMmPerDay(rain, demand)
                assertTrue(actual >= 0.0 && actual <= min(rain, demand) + 1e-12)
            }
        }
        assertEquals(0.0, builder.actualEvaporationMmPerDay(0.0, 5.0))
        assertTrue(builder.actualEvaporationMmPerDay(0.1, 8.0) > 0.099)
        assertEquals(1.0, builder.actualEvaporationMmPerDay(100.0, 1.0))
    }

}
