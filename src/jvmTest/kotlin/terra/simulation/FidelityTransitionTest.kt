package terra.simulation

import kotlin.math.abs
import kotlin.test.*
import terra.atmosphere.DryAtmosphereConfig
import terra.weather.WeatherFieldQuality

class FidelityTransitionTest {
    @Test fun atlasReentryPreservesSurfaceAndCalendarAndInitializesIndependentDetailedWater() {
        val owner = WorldSimulation(WorldRequest(width = 31, height = 23, atmosphere = DryAtmosphereConfig(width = 12, height = 8)))
        fun capture() = owner.snapshot(SimulationProgress(0, 1, 0, owner.completedSeconds,
            SimulationSpeed.ONE_HOUR, 0.0, 0.0, 0.0, 86400.0, true, 0.0, 0.0, 0))
        val year = 365 * 86400.0
        owner.advanceClimateInterval(20 * year)
        val climate = capture()
        assertFalse(climate.weather.hasResolvedDynamics)
        assertTrue(climate.seasonalClimate!!.hasAnnualCoverage)
        assertEquals(0, climate.observedClimateYears)
        owner.setFidelity(SimulationFidelity.DETAILED)
        val detailed = capture()
        assertEquals(climate.progress.completedSeconds, detailed.progress.completedSeconds)
        assertEquals(WeatherFieldQuality.RESOLVED_NOW, detailed.weather.fieldSemantics.quality)
        assertTrue(detailed.weather.hasResolvedDynamics)
        assertEquals(0L, detailed.lastHandoff!!.physicalStepsAtHandoff)
        for (y in 0 until 23) for (x in 0 until 31) {
            assertEquals(climate.weather.soilWaterMm(x, y), detailed.weather.soilWaterMm(x, y))
            assertEquals(climate.weather.annualizedRainMmPerYear(x, y), detailed.weather.annualizedRainMmPerYear(x, y))
        }
        repeat(90) { owner.advance(20.0, SimulationSpeed.ONE_HOUR) }
        val evolved = capture()
        assertEquals(20 * year + 1800, owner.completedSeconds, 1e-6)
        assertTrue(abs(evolved.diagnostics!!.relativeWaterResidual) < 1e-10)
        assertTrue(evolved.weather.pressureAnomalyPercent(15, 11)!!.isFinite())
    }
    @Test fun pausedWorkerPublishesPhysicalEditAndNewAtlasAtTheSameClock() {
        val worker = SimulationWorker(WorldRequest(width = 31, height = 23, atmosphere = DryAtmosphereConfig(width = 12, height = 8)))
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
            assertNull(worker.failure)
            assertTrue(condition())
        }
        try {
            val control = worker.updateControls(SimulationSpeed.ONE_HOUR, true)
            await { worker.latestSnapshot()?.progress?.appliedControlRevision == control }
            val old = worker.latestSnapshot()!!
            worker.editTerrain(listOf(TerrainElevationEdit(15, 11, 0.9)))
            await { worker.latestSnapshot()?.world?.geometryRevision == old.world.geometryRevision + 1 }
            val next = worker.latestSnapshot()!!
            assertEquals(old.progress.completedSeconds, next.progress.completedSeconds)
            assertEquals(0.9, next.world[15, 11].elevation)
            assertNotSame(old.seasonalClimate, next.seasonalClimate)
            assertTrue(old.world[15, 11].elevation != next.world[15, 11].elevation)
        } finally { worker.close() }
    }

}
