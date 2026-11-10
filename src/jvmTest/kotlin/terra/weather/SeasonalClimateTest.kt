package terra.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import terra.world.Terrain
import terra.world.TerrainGenerationSettings
import terra.world.World

class SeasonalClimateTest {
    @Test
    fun spinUpIsDiscardedAndSeasonsSplitRainAndDurationAcrossOffsetBoundaries() {
        val climate = SeasonalClimate(1, 1, 40.0, initialYearFraction = 0.125)
        climate.observe(40.0, { 99.0 }, { 999.0 })
        assertEquals(null, climate.snapshot)
        climate.observe(80.0, { 10.0 }, { 80.0 })
        val summary = climate.snapshot!!
        assertEquals(2, summary.observedYears)
        assertTrue(summary.hasAnnualCoverage)
        assertEquals(10.0, summary.meanSurfaceTemperatureC(0), 1e-12)
        assertEquals(40.0, summary.annualPrecipitationMm(0), 1e-12)
        assertEquals(10.0, summary.minimumSurfaceTemperatureC(0))
        assertEquals(10.0, summary.maximumSurfaceTemperatureC(0))
        assertEquals(3600.0, summary.peakRainMmPerHour(0))
        for (season in 0..3) {
            assertEquals(10.0, summary.seasonMeanSurfaceTemperatureC(season, 0), 1e-12)
            assertEquals(10.0, summary.seasonPrecipitationMm(season, 0), 1e-12)
        }
        assertEquals(0.25, summary.wettestSeasonFraction(0), 1e-12)
        // Frozen snapshots do not change as another year replaces the oldest.
        climate.observe(40.0, { 30.0 }, { 80.0 })
        assertEquals(10.0, summary.meanSurfaceTemperatureC(0))
        assertEquals(20.0, climate.snapshot!!.meanSurfaceTemperatureC(0))
        assertEquals(60.0, climate.snapshot!!.annualPrecipitationMm(0))
        assertFalse(climate.snapshot!!.stableAtTile(0, 0, 1, 1))
        val batched = SeasonalClimate(1, 1, 40.0, initialYearFraction = 0.125)
        var time = 0.0
        while (time < 120.0) {
            val seconds = minOf(7.0, 120 - time)
            batched.observe(seconds, { 10.0 }, { seconds })
            time += seconds
        }
        assertEquals(summary.annualPrecipitationMm(0), batched.snapshot!!.annualPrecipitationMm(0), 1e-12)
    }

    @Test
    fun integratedIntervalsPreserveCalendarEligibilityAndRecordedExtremes() {
        val bulk = SeasonalClimate(1, 1, 40.0, initialYearFraction = 0.125)
        val fine = SeasonalClimate(1, 1, 40.0, initialYearFraction = 0.125)
        bulk.observe(7.0, { 99.0 }, { 7.0 })
        fine.observe(40.0, { 99.0 }, { 40.0 })
        fun interval(start: Double, seconds: Double, temperature: Double, rain: Double,
            low: Double = temperature, high: Double = temperature, peak: Double = rain * 3600 / seconds) =
            ClimateIntervalSummary(1, 1, start, seconds, doubleArrayOf(temperature * seconds),
                doubleArrayOf(rain), doubleArrayOf(low), doubleArrayOf(high), doubleArrayOf(peak))
        bulk.observeInterval(interval(7.0, 33.0, 99.0, 33.0))
        assertEquals(null, bulk.snapshot) // The whole spin-up year is still discarded.
        val summary = interval(40.0, 80.0, 10.0, 80.0, low = -2.0, high = 25.0, peak = 10000.0)
        bulk.observeInterval(summary)
        repeat(80) { fine.observe(1.0, { 10.0 }, { 1.0 }) }
        val published = bulk.snapshot!!
        assertEquals(120.0, bulk.elapsedSeconds)
        assertEquals(2, published.observedYears)
        assertTrue(published.hasAnnualCoverage)
        for (season in 0..3) {
            assertEquals(fine.snapshot!!.seasonMeanSurfaceTemperatureC(season, 0), published.seasonMeanSurfaceTemperatureC(season, 0), 1e-12)
            assertEquals(fine.snapshot!!.seasonPrecipitationMm(season, 0), published.seasonPrecipitationMm(season, 0), 1e-12)
        }
        assertEquals(-2.0, published.minimumSurfaceTemperatureC(0))
        assertEquals(25.0, published.maximumSurfaceTemperatureC(0))
        assertEquals(10000.0, published.peakRainMmPerHour(0))
        assertFailsWith<IllegalArgumentException> { bulk.observeInterval(summary) }
        assertFailsWith<IllegalArgumentException> { bulk.observeInterval(interval(121.0, 1.0, 10.0, 1.0)) }
        assertEquals(120.0, bulk.elapsedSeconds)
        assertTrue(published === bulk.snapshot)
        assertFailsWith<IllegalArgumentException> { interval(120.0, 10.0, 10.0, 1.0, low = 11.0) }
    }

    @Test
    fun annualStatisticsRetainSeasonalityExtremesAndNorthToSouthMapping() {
        val climate = SeasonalClimate(1, 2, 40.0)
        repeat(3) {
            for (season in 0..3) {
                val t = doubleArrayOf(-10.0, 10.0, 20.0, 0.0)[season]
                val rain = doubleArrayOf(0.0, 10.0, 20.0, 10.0)[season]
                climate.observe(10.0, { cell -> t + cell * 10 }, { rain })
            }
        }
        val summary = climate.snapshot!!
        assertEquals(5.0, summary.meanSurfaceTemperatureC(0))
        assertEquals(-10.0, summary.minimumSurfaceTemperatureC(0))
        assertEquals(20.0, summary.maximumSurfaceTemperatureC(0))
        assertEquals(0.5, summary.wettestSeasonFraction(0))
        assertEquals(15.0, summary.meanSurfaceTemperatureAtTile(0, 0, 1, 4))
        assertEquals(5.0, summary.meanSurfaceTemperatureAtTile(0, 3, 1, 4))
        assertTrue(summary.stableAtTile(0, 0, 1, 4))
    }

    @Test
    fun ecologyWaitsForAnnualCoveragePersistsThroughThresholdNoiseAndPreservesGeography() {
        val settings = TerrainGenerationSettings(baseElevation = 0.4, continentStrength = 0.0,
            regionalStrength = 0.0, detailStrength = 0.0)
        fun world(base: Double = 0.4) = World(31742, 8, 6, settings.copy(baseElevation = base))
        val dry = stableClimate(15.0, 0.0)
        val wet = stableClimate(15.0, 2000.0)
        val land = world()
        val immature = SeasonalClimate(1, 1, 40.0)
        immature.observe(80.0, { 15.0 }, { 0.0 })
        assertFalse(land.advanceClimateBiomes(immature.snapshot, 400.0))
        assertFalse(land.advanceClimateBiomes(dry, 40.0))
        assertEquals(Terrain.PLAINS, land[3, 3].terrain)
        assertTrue(land.advanceClimateBiomes(dry, 40.0))
        assertEquals(Terrain.DESERT, land[3, 3].terrain)
        assertFalse(land.advanceClimateBiomes(wet, 40.0))
        assertFalse(land.advanceClimateBiomes(dry, 40.0))
        assertFalse(land.advanceClimateBiomes(wet, 40.0))
        assertEquals(Terrain.DESERT, land[3, 3].terrain)
        assertTrue(land.advanceClimateBiomes(wet, 40.0))
        assertEquals(Terrain.WOODS, land[3, 3].terrain)
        val noisy = world()
        assertFalse(noisy.advanceClimateBiomes(stableClimate(15.0, 1150.0), 400.0))
        assertEquals(Terrain.PLAINS, noisy[3, 3].terrain)
        val steppe = world()
        assertTrue(steppe.advanceClimateBiomes(stableClimate(15.0, 400.0), 80.0))
        assertEquals(Terrain.STEPPE, steppe[3, 3].terrain)
        val cold = world()
        assertTrue(cold.advanceClimateBiomes(stableClimate(-15.0, 300.0), 80.0))
        assertEquals(Terrain.TUNDRA, cold[3, 3].terrain)
        val ocean = world(0.1)
        val mountains = world(0.8)
        assertFalse(ocean.advanceClimateBiomes(dry, 400.0))
        assertFalse(mountains.advanceClimateBiomes(wet, 400.0))
        assertEquals(Terrain.OCEAN, ocean[3, 3].terrain)
        assertEquals(Terrain.MOUNTAINS, mountains[3, 3].terrain)
    }

    private fun stableClimate(temperature: Double, annualRain: Double): SeasonalClimateSnapshot {
        val climate = SeasonalClimate(1, 1, 40.0)
        climate.observe(120.0, { temperature }, { annualRain * 3 })
        return climate.snapshot!!
    }
}
