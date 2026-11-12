package terra.weather

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.MoistPhysicsConfig
import terra.atmosphere.SolarConfig
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.world.TerrainGenerationSettings
import terra.world.World

class ClimateNormalsTest {
    private val config = DryAtmosphereConfig(width = 12, height = 8)
    private fun createWeather(world: World, solar: SolarConfig = SolarConfig()): DynamicalWeather =
        DynamicalWeather(world, HydrologySimulation(HydrologyTopology(world)), config, MoistPhysicsConfig(solar = solar))

    @Test
    fun freshWorldHasGeographicSeasonalEstimatesWithoutAdvancingAnyState() {
        val world = World(31742, 31, 23)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val weather = DynamicalWeather(world, hydrology, config)
        val before = hydrology.waterInventory()
        val initialWater = weather.diagnostics.totalWaterKg
        val normal = weather.climateNormals.normals()
        assertEquals(ClimateEstimateSource.PROVISIONAL_PRIOR, normal.source)
        assertEquals(0.0, normal.observedWeight)
        val equator = 4 * config.width
        val north = 7 * config.width
        val south = 0
        fun annualTemperature(cell: Int) = (0..3).sumOf { normal.temperatureC(it, cell) } / 4
        assertTrue(annualTemperature(equator) > annualTemperature(north))
        assertTrue(normal.temperatureC(0, north) > normal.temperatureC(2, north))
        assertTrue(normal.temperatureC(2, south) > normal.temperatureC(0, south))
        val rates = (0..3).flatMap { s -> (0 until config.cellCount).map { normal.precipitationMmPerDay(s, it) } }
        assertTrue(rates.max() - rates.min() > 0.5)
        val estimate = weather.estimateClimate(0.0)
        val frozenTemperature = estimate.temperatureC(4)
        weather.estimateClimate(365 * 86400.0 * 1000)
        assertEquals(frozenTemperature, estimate.temperatureC(4))
        for (cell in 0 until config.cellCount) {
            assertTrue(estimate.temperatureC(cell).isFinite())
            assertTrue(estimate.precipitationMmPerDay(cell).isFinite() && estimate.precipitationMmPerDay(cell) >= 0.0)
        }
        for (y in listOf(0, 11, 22)) {
            assertEquals(estimate.temperatureCAtTile(-1, y, 31, 23), estimate.temperatureCAtTile(30, y, 31, 23))
            assertEquals(estimate.precipitationMmPerDayAtTile(0, y, 31, 23), estimate.precipitationMmPerDayAtTile(31, y, 31, 23))
        }
        assertTrue(estimate.temperatureCAtTile(0, 0, 31, 23) < estimate.temperatureCAtTile(0, 11, 31, 23))
        assertEquals(0.0, weather.elapsedSeconds)
        assertEquals(0.0, weather.climateHistory.elapsedSeconds)
        assertEquals(null, weather.seasonalClimate)
        assertEquals(before, hydrology.waterInventory())
        assertEquals(initialWater, weather.diagnostics.totalWaterKg)

        val flat = TerrainGenerationSettings(continentStrength = 0.0, regionalStrength = 0.0, detailStrength = 0.0)
        val land = createWeather(World(31742, 24, 24, flat.copy(baseElevation = 0.4))).climateNormals.normals()
        val ocean = createWeather(World(31742, 24, 24, flat.copy(baseElevation = 0.1))).climateNormals.normals()
        val midNorth = 6 * config.width
        fun amplitude(n: ClimateNormalsSnapshot) = (0..3).maxOf { n.temperatureC(it, midNorth) } - (0..3).minOf { n.temperatureC(it, midNorth) }
        assertTrue(amplitude(land) > 1.5 * amplitude(ocean))
        assertTrue(ocean.precipitationMmPerDay(0, midNorth) > land.precipitationMmPerDay(0, midNorth))
    }

    @Test
    fun observedSeasonsBlendWithCorrectUnitsCoverageAndOrbitalOffset() {
        val solar = SolarConfig(yearSeconds = 40.0, initialYearFraction = 0.125)
        val weather = createWeather(World(31742, 15, 11), solar)
        val prior = weather.climateNormals.normals()
        val history = weather.climateHistory
        history.observe(40.0, { 99.0 }, { 999.0 })
        assertEquals(null, history.snapshot)
        history.observe(40.0, { 10.0 }, { 40.0 })
        val first = weather.climateNormals.normals(history.snapshot)
        assertEquals(0.4, first.observedWeight)
        history.observe(40.0, { 10.0 }, { 40.0 })
        val blended = weather.climateNormals.normals(history.snapshot)
        assertEquals(ClimateEstimateSource.BLENDED_OBSERVATIONS, blended.source)
        assertEquals(0.8, blended.observedWeight)
        assertTrue(blended === weather.climateNormals.normals(history.snapshot))
        for (s in 0..3) for (cell in 0 until config.cellCount) {
            assertEquals(prior.temperatureC(s, cell) * 0.2 + 8.0, blended.temperatureC(s, cell), 1e-12)
            // 40 mm per forty-second year is one mm/s, not forty mm/day or mm/season.
            assertEquals(prior.precipitationMmPerDay(s, cell) * 0.2 + 86400.0 * 0.8,
                blended.precipitationMmPerDay(s, cell), 1e-9)
        }
        assertEquals(0.4, first.observedWeight) // Previously published normals remain frozen.
        val zeroOffset = createWeather(World(31742, 15, 11), solar.copy(initialYearFraction = 0.0))
        val atFirstCenter = weather.estimateClimate(0.0)
        assertEquals(zeroOffset.climateNormals.normals().temperatureC(0, 10), prior.temperatureC(0, 10))
        val zeroEstimate = zeroOffset.climateNormals.estimate(0.0, history.snapshot)
        // Same seed/time anomalies cancel: the orbital offset selects the first season center.
        assertEquals(blended.temperatureC(0, 10) -
            (blended.temperatureC(3, 10) + blended.temperatureC(0, 10)) / 2,
            atFirstCenter.temperatureC(10) - zeroEstimate.temperatureC(10), 1e-12)
        assertEquals(0.0, atFirstCenter.atSeconds)
        val wrongGrid = SeasonalClimate(1, 1, 40.0).also { it.observe(120.0, { 10.0 }, { 1.0 }) }
        assertFailsWith<IllegalArgumentException> { weather.climateNormals.normals(wrongGrid.snapshot) }
        val wrongYear = SeasonalClimate(config.width, config.height, 80.0).also { it.observe(240.0, { 10.0 }, { 1.0 }) }
        assertFailsWith<IllegalArgumentException> { weather.climateNormals.normals(wrongYear.snapshot) }
    }

    @Test
    fun regionalAnomaliesAreBoundedSmoothPeriodicAndIndependentOfQueryOrder() {
        val year = 365 * 86400.0
        val field = ClimateAnomalies(31742, year)
        val same = ClimateAnomalies(31742, year)
        val other = ClimateAnomalies(42, year)
        val month = year / 12
        val t = 6 * month
        for (time in listOf(0.0, t - 1, t, t + 1, 1000 * year)) {
            val wet = field.wet(0.2, 0.65, time)
            assertTrue(abs(wet) <= 1.0)
            assertTrue(abs(field.warm(0.2, 0.65, time)) <= 1.0)
            assertEquals(wet, same.wet(0.2, 0.65, time))
            assertEquals(field.wet(0.0, 0.65, time), field.wet(1.0, 0.65, time))
        }
        val wetGrid = DoubleArray(96)
        val warmGrid = DoubleArray(96)
        field.fillGrid(wetGrid, warmGrid, 12, 8, t)
        for (i in wetGrid.indices) {
            val x = (i % 12 + 0.5) / 12
            val y = (i / 12 + 0.5) / 8
            assertEquals(field.wet(x, y, t), wetGrid[i], 1e-12)
            assertEquals(field.warm(x, y, t), warmGrid[i], 1e-12)
        }
        val saved = field.wet(0.2, 0.65, t)
        field.wet(0.2, 0.65, 1000 * year)
        assertEquals(saved, field.wet(0.2, 0.65, t))
        assertTrue(abs(saved - other.wet(0.2, 0.65, t)) > 1e-3)
        assertTrue(abs(field.wet(0.2, 0.65, t - 1) - field.wet(0.2, 0.65, t + 1)) < 1e-6)
        assertTrue(abs(field.wet(0.2, 0.65, t + month) - saved) > 1e-3)
        val center = field.wet(0.4, 0.6, t)
        val nearbyDifference = abs(center - field.wet(0.4001, 0.6001, t))
        val distantDifference = abs(center - field.wet(0.7, 0.6, t))
        assertTrue(nearbyDifference < distantDifference / 10)
        assertFailsWith<IllegalArgumentException> { field.wet(0.0, 0.5, -1.0) }
    }
}
