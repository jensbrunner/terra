package terra.weather

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.simulation.*

class AggregateClimateTest {
    @Test
    fun twentyYearIntervalHasBoundedWorkConservesWaterAndReturnsToDetailed() {
        val owner = WorldSimulation(WorldRequest(generation = 1, width = 15, height = 11, dynamical = true,
            atmosphere = DryAtmosphereConfig(width = 8, height = 6)))
        fun capture() = owner.snapshot(SimulationProgress(1, 1, 0, owner.completedSeconds,
            SimulationSpeed.TEN_YEARS, 0.0, 0.0, 0.0, 1.0, false, 0.0, 0.0, 0))
        owner.advance(20.0, SimulationSpeed.ONE_HOUR)
        val original = capture()
        val year = 365 * 86400.0
        val interval = owner.advanceClimateInterval(20 * year)
        val aggregate = capture()
        assertEquals(8, interval.subperiods)
        assertEquals(8L, interval.routingPasses)
        assertEquals(1L, interval.detailedCompletedSteps)
        assertTrue(interval.aggregateYears)
        assertEquals(0.0, interval.ecologyEligibleSeconds)
        assertTrue(abs(interval.relativeWaterResidual) < 1e-12)
        assertEquals(20 + 20 * year, owner.completedSeconds, 1e-6)
        assertTrue(aggregate.world === original.world) // No final-state biome condition applied to twenty years.
        assertTrue(aggregate.seasonalClimate!!.containsAggregateEstimates)
        assertTrue(aggregate.seasonalClimate.hasAnnualCoverage)
        assertTrue(!aggregate.seasonalClimate.stableAtTile(1, 1, 15, 11))
        for (s in 0..3) assertTrue(aggregate.seasonalClimate.seasonMeanSurfaceTemperatureC(s, 0).isFinite())
        owner.setFidelity(SimulationFidelity.DETAILED)
        owner.advance(20.0, SimulationSpeed.ONE_HOUR)
        val resolved = capture()
        assertEquals(WeatherFieldQuality.RESOLVED_NOW, resolved.weather.fieldSemantics.quality)
        assertEquals(40 + 20 * year, owner.completedSeconds, 1e-6)
        assertTrue(abs(resolved.diagnostics!!.relativeWaterResidual) < 1e-10)
        println("AGGREGATE years=20 phases=${interval.subperiods} routes=${interval.routingPasses} water_residual=${interval.relativeWaterResidual}")
    }

    @Test
    fun seasonalHistorySkipsIntermediateYearsAndAnomalyMeanMatchesSmallQuadrature() {
        val history = SeasonalClimate(1, 1, 40.0, initialYearFraction = 0.125)
        history.observe(7.0, { 99.0 }, { 7.0 })
        history.observeAggregate(7.0, 40000.0, SeasonalAggregate(
            arrayOf(doubleArrayOf(-10.0), doubleArrayOf(10.0), doubleArrayOf(20.0), doubleArrayOf(0.0)),
            arrayOf(doubleArrayOf(0.0), doubleArrayOf(1.0), doubleArrayOf(2.0), doubleArrayOf(1.0)),
            doubleArrayOf(-10.0), doubleArrayOf(20.0), doubleArrayOf(7200.0)))
        assertEquals(40007.0, history.elapsedSeconds)
        assertEquals(999, history.observedYears)
        val summary = history.snapshot!!
        assertEquals(5.0, summary.meanSurfaceTemperatureC(0), 1e-12)
        assertEquals(40.0, summary.annualPrecipitationMm(0), 1e-12)
        assertTrue(summary.containsAggregateEstimates)
        assertTrue(!summary.stableAtTile(0, 0, 1, 1))
        val year = 365 * 86400.0
        val field = ClimateAnomalies(31742, year)
        val span = 20 * year
        val start = 12345.0
        val reference = (0 until 128).sumOf { field.wet(0.2, 0.7, start + (it + 0.5) * span / 128) } / 128
        assertEquals(reference, field.meanWet(0.2, 0.7, start, span), 0.002)
        val annual = ClimatePhasePlan(20.0, year, year, 0.0)
        assertTrue(annual.phases.size <= 12)
        assertEquals(year, annual.phases.sumOf { it.seconds }, 1e-6)
        val long = ClimatePhasePlan(20.0, span, year, 0.0)
        assertEquals(8, ClimatePhasePlan(7.0, 800.0, 40.0, 0.125).phases.size)
        assertEquals(8, long.phases.size)
        assertEquals(span, long.phases.sumOf { it.seconds }, 1e-6)
    }
}
