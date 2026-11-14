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
        val owner = WorldSimulation(WorldRequest(generation = 1, width = 15, height = 11, atmosphere = DryAtmosphereConfig(width = 8, height = 6)))
        fun capture() = owner.snapshot(SimulationProgress(1, 1, 0, owner.completedSeconds,
            SimulationSpeed.TEN_YEARS, 0.0, 0.0, 0.0, 1.0, false, 0.0, 0.0, 0))
        owner.advance(20.0, SimulationSpeed.ONE_HOUR)
        val original = capture()
        val year = 365 * 86400.0
        val interval = owner.advanceClimateInterval(20 * year)
        val aggregate = capture()
        assertTrue(interval.subperiods in 730..761)
        assertEquals(1L, interval.detailedCompletedSteps)
        assertEquals(20 * year, interval.ecologyEligibleSeconds)
        assertTrue(abs(interval.relativeWaterResidual) < 1e-12)
        assertEquals(20 + 20 * year, owner.completedSeconds, 1e-6)
        assertTrue(aggregate.world.biomeRevision > original.world.biomeRevision)
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
    }

    @Test
    fun phasePlanAccountsForEverySecondChronologically() {
        val year = 365 * 86400.0
        val span = 20 * year
        val annual = ClimatePhasePlan(20.0, year, year)
        assertTrue(annual.phases.size <= 40)
        assertEquals(year, annual.phases.sumOf { it.seconds }, 1e-6)
        val long = ClimatePhasePlan(20.0, span, year)
        assertTrue(ClimatePhasePlan(7.0, 800.0, 40.0).phases.size in 720..761)
        assertTrue(long.phases.size in 730..761)
        assertEquals(span, long.phases.sumOf { it.seconds }, 1e-6)
    }
}
