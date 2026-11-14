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
            assertTrue(atlas.annualRainfallMm(i).isFinite() && atlas.annualRainfallMm(i) >= 0)
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
    @Test fun zeroTiltRemovesThermalSeasonsAndPolarWindsAreEasterly() {
        val world = continent()
        val atlas = ClimateAtlasBuilder(terra.atmosphere.SolarConfig(axialTiltDegrees = 0.0)).build(ClimateGeography(world, config))
        for (i in 0 until 96 * 72 step 17) for (m in 1..11) assertEquals(atlas.temperatureC(0, i), atlas.temperatureC(m, i), 1e-10)
        assertTrue(atlas.windEastMetersPerSecond(0, 48) < 0)
        assertTrue(atlas.windEastMetersPerSecond(0, 18 * 96 + 48) > 0)
        assertTrue(atlas.windEastMetersPerSecond(0, 36 * 96 + 48) < 0)
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

}
