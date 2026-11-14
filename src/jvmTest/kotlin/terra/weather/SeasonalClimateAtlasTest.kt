package terra.weather

import kotlin.math.abs
import kotlin.test.*
import terra.atmosphere.DryAtmosphereConfig
import terra.world.*

class SeasonalClimateAtlasTest {
    private val config = DryAtmosphereConfig(width = 48, height = 36)
    private fun continent(ridge: Boolean = false): World = World.withElevation(42, 96, 72, TerrainGenerationSettings(), DoubleArray(96 * 72) {
        val x = it % 96
        if (x !in 16..80) 0.1 else if (ridge && x in 46..50) 0.65 else 0.32
    })
    @Test fun rainfallIsMonthlySumAndDownscalingConservesRegionalRain() {
        val world = continent(true)
        val atlas = ClimateAtlasBuilder().build(ClimateGeography(world, config))
        for (i in 0 until 96 * 72) {
            assertEquals((0..11).sumOf { atlas.rainfallMm(it, i) }, atlas.annualRainfallMm(i), 1e-9)
            assertTrue(atlas.annualRainfallMm(i).isFinite() && atlas.annualRainfallMm(i) >= 0, "Invalid rain at tile $i: ${atlas.annualRainfallMm(i)}")
        }
        assertEquals(atlas.regionalAnnualMeanRainMm, atlas.annualRain.average(), 1e-8)
        // Downscaling must not create water, even on isolated ridges.
        assertTrue(atlas.maximumHumidityChangeMm < 0.05, "Fixed sweeps must converge: ${atlas.maximumHumidityChangeMm}")
    }
    @Test fun reversingWindReversesRidgeShadow() {
        val world = continent(true)
        fun contrast(wind: Double): Double {
            val a = ClimateAtlasBuilder(windOverride = wind).build(ClimateGeography(world, config))
            var west = 0.0; var east = 0.0
            for (y in 24..46) {
                for (x in 39..45) west += a.annualRainfallMm(y * 96 + x)
                for (x in 51..57) east += a.annualRainfallMm(y * 96 + x)
            }
            return west / east
        }
        assertTrue(contrast(5.0) > 1.4, "Eastward flow should wet the western slope")
        assertTrue(contrast(-5.0) < 0.72, "Westward flow should wet the eastern slope")
    }
    @Test fun coastsAreMaritimeAndElevationCoolsThePlateau() {
        val flat = ClimateAtlasBuilder().build(ClimateGeography(continent(), config))
        val ridge = ClimateAtlasBuilder().build(ClimateGeography(continent(true), config))
        fun swing(a: SeasonalClimateAtlas, x: Int) = (0..11).maxOf { a.temperatureC(it, 15 * 96 + x) } - (0..11).minOf { a.temperatureC(it, 15 * 96 + x) }
        assertTrue(swing(flat, 48) > swing(flat, 17) + 3)
        assertTrue(ridge.meanTemperature[15 * 96 + 48] < flat.meanTemperature[15 * 96 + 48] - 12)
    }
    @Test fun geometryEditsRebuildAtlasWhileCoverRetainsMemory() {
        val world = continent()
        val weather = DynamicalWeather(world, config)
        val old = weather.atlas
        val tree = world.treeCover(48, 36)
        repeat(5) { weather.advanceClimateInterval(10 * weather.physics.solar.yearSeconds) }
        assertSame(old, weather.atlas)
        val survivingCover = world.treeCover(48, 36)
        for (y in 0 until 72) for (x in 46..50) world.setElevation(x, y, 0.65)
        weather.advanceClimateInterval(weather.physics.solar.yearSeconds / 12)
        assertNotSame(old, weather.atlas)
        assertEquals(world.geometryRevision, weather.atlas.geometryRevision)
        assertEquals(survivingCover, world.treeCover(48, 36), 1e-12)
        assertTrue(tree >= 0)
    }
    @Test fun monthlyEvolutionHasSurfaceWaterClosureAndDoesNotAdvanceDetailedAtmosphere() {
        val weather = DynamicalWeather(continent(), config)
        val atlas = weather.atlas
        repeat(3) {
            val d = weather.advanceClimateInterval(weather.physics.solar.yearSeconds)
            assertTrue(abs(d.relativeWaterResidual) < 1e-10, "Surface water residual ${d.relativeWaterResidual}")
            assertEquals(0L, d.detailedCompletedSteps)
            assertTrue(d.subperiods in 12..13)
        }
        assertSame(atlas, weather.atlas)
        val publication = weather.captureReadOnly()
        val oldSoil = publication.soilWaterMm(30, 30)
        weather.advanceClimateInterval(weather.physics.solar.yearSeconds)
        assertEquals(oldSoil, publication.soilWaterMm(30, 30))
        assertEquals(atlas.annualRainfallMm(30 * 96 + 30), publication.annualizedRainMmPerYear(30, 30))
    }
    @Test fun zeroTiltRemovesThermalSeasons() {
        val world = continent()
        val atlas = ClimateAtlasBuilder(terra.atmosphere.SolarConfig(axialTiltDegrees = 0.0)).build(ClimateGeography(world, config))
        for (i in 0 until 96 * 72 step 17) for (m in 1..11) assertEquals(atlas.temperatureC(0, i), atlas.temperatureC(m, i), 1e-10)
    }
    @Test fun landSeaConversionResetsTerrestrialStoresWithoutReinterpretingSoil() {
        val world = continent()
        val weather = DynamicalWeather(world, config)
        weather.advanceClimateInterval(weather.physics.solar.yearSeconds)
        val survivor = weather.soilWaterMm(40, 35)
        world.setElevation(30, 35, 0.1)
        weather.ensureAtlas()
        assertEquals(0.0, weather.soilWaterMm(30, 35))
        assertEquals(0.0, world.treeCover(30, 35))
        assertEquals(survivor, weather.soilWaterMm(40, 35))
        world.setElevation(30, 35, 0.32)
        weather.ensureAtlas()
        assertEquals(0.0, weather.soilWaterMm(30, 35))
        assertEquals(0.0, world.treeCover(30, 35))
        val d = weather.advanceClimateInterval(weather.physics.solar.yearSeconds / 12)
        assertTrue(abs(d.relativeWaterResidual) < 1e-10)
    }

    @Test fun uniformTemperatureHasNoWindAndWeakRotationRemainsBounded() {
        val g = ClimateGeography(continent(), config.copy(rotationRateRadiansPerSecond = 0.0))
        val east = DoubleArray(g.count); val south = DoubleArray(g.count)
        val t = DoubleArray(g.count) { 15.0 }
        val builder = ClimateAtlasBuilder()
        builder.diagnoseWinds(g, t, east, south)
        assertTrue(east.all { it == 0.0 } && south.all { it == 0.0 })
        for (i in t.indices) t[i] = 15 + 10 * kotlin.math.sin(2 * Math.PI * (i % g.width) / g.width)
        builder.diagnoseWinds(g, t, east, south)
        assertTrue(east.any { abs(it) > 0.1 })
        for (i in t.indices) {
            assertTrue(east[i].isFinite() && south[i].isFinite())
            assertTrue(kotlin.math.hypot(east[i], south[i]) <= 12)
            assertEquals(0.0, south[i], 1e-12)
        }
    }
    @Test fun coastlinesAndMountainsChangeCirculationAndRainWithoutWindOverrides() {
        val flat = ClimateAtlasBuilder().build(ClimateGeography(continent(), config))
        val ridge = ClimateAtlasBuilder().build(ClimateGeography(continent(true), config))
        var windDifference = 0.0; var rainDifference = 0.0
        for (y in 20..50) for (x in 36..60) {
            val i = y * 96 + x
            windDifference += abs(ridge.windEastMetersPerSecond(3, i) - flat.windEastMetersPerSecond(3, i)) +
                abs(ridge.windSouthMetersPerSecond(3, i) - flat.windSouthMetersPerSecond(3, i))
            rainDifference += abs(ridge.annualRainfallMm(i) - flat.annualRainfallMm(i))
        }
        assertTrue(windDifference / (31 * 25) > 0.1)
        assertTrue(rainDifference / (31 * 25) > 25)
        var zonalVariation = 0.0
        for (y in 15..28) for (x in 17..79) zonalVariation += abs(flat.windEastMetersPerSecond(3, y * 96 + x) - flat.windEastMetersPerSecond(3, y * 96 + 5))
        assertTrue(zonalVariation / (14 * 63) > 0.1, "A coastline must change winds at the same latitude")
    }

    @Test fun highObliquityCanMakePolesWarmerThanTheEquator() {
        val ocean = World.withElevation(1, 48, 36, TerrainGenerationSettings(), DoubleArray(48 * 36) { 0.1 })
        val lowTilt = ClimateAtlasBuilder(terra.atmosphere.SolarConfig(axialTiltDegrees = 0.0)).build(ClimateGeography(ocean, config))
        val highTilt = ClimateAtlasBuilder(terra.atmosphere.SolarConfig(axialTiltDegrees = 90.0)).build(ClimateGeography(ocean, config))
        assertTrue(lowTilt.meanTemperature[18 * 48] > lowTilt.meanTemperature[0] + 10)
        assertTrue(highTilt.meanTemperature[0] > highTilt.meanTemperature[18 * 48] + 5,
            "Annual temperature must follow actual sunlight rather than a fixed pole-to-equator curve")
    }
    @Test fun reversingRotationReversesWindDeflection() {
        val world = continent()
        val north = ClimateGeography(world, config)
        val reversed = ClimateGeography(world, config.copy(rotationRateRadiansPerSecond = -config.rotationRateRadiansPerSecond))
        val temperature = DoubleArray(north.count) { 15 + 10 * kotlin.math.sin(2 * Math.PI * (it % north.width) / north.width) }
        val east = DoubleArray(north.count); val south = DoubleArray(north.count)
        val otherEast = DoubleArray(north.count); val otherSouth = DoubleArray(north.count)
        val builder = ClimateAtlasBuilder()
        builder.diagnoseWinds(north, temperature, east, south)
        builder.diagnoseWinds(reversed, temperature, otherEast, otherSouth)
        val sample = 10 * north.width
        assertTrue(south[sample] > 0.1 && otherSouth[sample] < -0.1)
        assertEquals(east[sample], otherEast[sample], 1e-10)
    }

    @Test fun initialOrbitalPhaseSelectsAtlasMonthWithoutShiftingTheAtlasTwice() {
        val g = ClimateGeography(continent(), config)
        val baseline = ClimateAtlasBuilder().build(g)
        val shifted = ClimateAtlasBuilder(terra.atmosphere.SolarConfig(initialYearFraction = 0.25)).build(g)
        assertEquals(3, shifted.monthAt(0.0))
        for (m in 0..11) for (i in 0 until 96 * 72 step 37) {
            assertEquals(baseline.temperatureC(m, i), shifted.temperatureC(m, i), 1e-10)
            assertEquals(baseline.rainfallMm(m, i), shifted.rainfallMm(m, i), 1e-10)
        }
    }

}
