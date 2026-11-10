package terra.simulation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.PlanetarySunlight
import terra.weather.WeatherFieldQuality

class FidelityTransitionTest {
    private val request = WorldRequest(generation = 1, width = 15, height = 11, dynamical = true,
        atmosphere = DryAtmosphereConfig(width = 8, height = 6))
    private val day = 86400.0

    @Test
    fun multiYearClimateReturnsToDetailedWithoutResettingSlowStateOrCalendar() {
        val owner = WorldSimulation(request)
        fun capture() = owner.snapshot(SimulationProgress(1, 1, 0, owner.completedSeconds,
            SimulationSpeed.ONE_HOUR, 0.0, 0.0, 0.0, day, false, 0.0, 0.0, 0))
        owner.advance(20.0, SimulationSpeed.ONE_HOUR)
        val initialWater = capture().diagnostics!!.totalWaterKg
        // A single tiny-grid schedule: 3.28 real model years, no detailed spin-up or seed sweep.
        repeat(171) { owner.advanceClimateInterval(7 * day) }
        val coarse = capture()
        assertEquals(20 + 1197 * day, coarse.progress.completedSeconds)
        assertTrue(coarse.seasonalClimate!!.hasAnnualCoverage)
        owner.setFidelity(SimulationFidelity.DETAILED)
        val pending = capture()
        assertEquals(coarse.progress.completedSeconds, pending.progress.completedSeconds)
        assertEquals(SimulationFidelity.DETAILED, pending.fidelity)
        assertTrue(pending.detailedReentryPending)
        assertEquals(WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL, pending.weather.fieldSemantics.quality)
        assertTrue(coarse.world === pending.world)
        assertTrue(coarse.hydrology === pending.hydrology)
        assertTrue(coarse.seasonalClimate === pending.seasonalClimate)
        assertEquals(coarse.observedClimateYears, pending.observedClimateYears)
        val handoff = pending.lastHandoff!!
        assertEquals(1L, handoff.physicalStepsAtHandoff)
        assertEquals(0, handoff.spinInSteps)
        assertEquals(handoff.waterBeforeKg, handoff.waterAfterKg, handoff.waterBeforeKg * 1e-12)
        assertTrue(handoff.initializationEnergyChangeJoules.isFinite())
        for (y in 0 until request.height) for (x in 0 until request.width) {
            assertEquals(coarse.weather.soilWaterMm(x, y), pending.weather.soilWaterMm(x, y))
            assertEquals(coarse.weather.surfaceTemperatureC(x, y), pending.weather.surfaceTemperatureC(x, y))
            assertEquals(coarse.weather.atmosphericWaterMm(x, y), pending.weather.atmosphericWaterMm(x, y), 1e-12)
        }
        repeat(100) { owner.advance(20.0, SimulationSpeed.ONE_HOUR) }
        val resolved = capture()
        assertFalse(resolved.detailedReentryPending)
        assertEquals(WeatherFieldQuality.RESOLVED_NOW, resolved.weather.fieldSemantics.quality)
        assertEquals(coarse.progress.completedSeconds + 2000, resolved.progress.completedSeconds)
        assertEquals(resolved.progress.completedSeconds, resolved.weather.elapsedSeconds)
        assertTrue(resolved.diagnostics!!.includesApproximateHistory)
        assertEquals(handoff.initializationEnergyChangeJoules, resolved.diagnostics.atmosphereInitializationEnergyJoules)
        assertEquals(initialWater, resolved.diagnostics.totalWaterKg, initialWater * 1e-10)
        assertTrue(abs(resolved.diagnostics.relativeWaterResidual) < 1e-10)
        val absolute = PlanetarySunlight(request.width, request.height, northUp = false).also { it.update(owner.completedSeconds) }
        val restarted = PlanetarySunlight(request.width, request.height, northUp = false).also { it.update(2020.0) }
        var dateMatters = false
        for (y in 0 until request.height) for (x in 0 until request.width) {
            assertEquals(absolute.inputFactor(x, y), resolved.weather.solarInputFactor(x, y), 1e-12)
            assertEquals(absolute.nightOpacity(x, y), resolved.weather.nightOpacity(x, y), 1e-12)
            if (abs(absolute.inputFactor(x, y) - restarted.inputFactor(x, y)) > 0.1) dateMatters = true
            assertTrue(resolved.weather.airTemperatureC(x, y).isFinite())
            assertTrue(resolved.weather.atmosphericWaterMm(x, y) >= 0 && resolved.weather.cloudWaterMm(x, y) >= 0)
        }
        assertTrue(dateMatters, "Radiation must use the current season, not the old numerical step clock")
        assertEquals(WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL, coarse.weather.fieldSemantics.quality)
        println("FIDELITY years=3.28 climate_intervals=171 resumed_steps=100 water_relative_residual=${resolved.diagnostics.relativeWaterResidual} spin_in=0")
    }

    @Test
    fun workerAcknowledgesPausedFidelityChangesAndResumesSameGeneration() {
        val worker = SimulationWorker(request)
        try {
            val pause = worker.updateControls(SimulationSpeed.ONE_MONTH, true)
            await { worker.latestSnapshot()?.progress?.appliedControlRevision == pause }
            val initial = worker.latestSnapshot()!!
            val climate = worker.setFidelity(SimulationFidelity.CLIMATE)
            await { worker.latestSnapshot()?.let { it.progress.appliedControlRevision == climate && it.fidelity == SimulationFidelity.CLIMATE } == true }
            assertEquals(initial.progress.completedSeconds, worker.latestSnapshot()!!.progress.completedSeconds)
            worker.updateControls(SimulationSpeed.ONE_MONTH, false)
            await { worker.latestSnapshot()?.weather?.fieldSemantics?.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL }
            val paused = worker.updateControls(SimulationSpeed.ONE_MONTH, true)
            await { worker.latestSnapshot()?.progress?.appliedControlRevision == paused }
            val coarse = worker.latestSnapshot()!!
            worker.setFidelity(SimulationFidelity.DETAILED)
            worker.setFidelity(SimulationFidelity.CLIMATE)
            val last = worker.setFidelity(SimulationFidelity.DETAILED)
            await { worker.latestSnapshot()?.let { it.progress.appliedControlRevision == last && it.fidelity == SimulationFidelity.DETAILED } == true }
            val pending = worker.latestSnapshot()!!
            assertEquals(coarse.progress.generation, pending.progress.generation)
            assertEquals(coarse.progress.completedSeconds, pending.progress.completedSeconds)
            assertTrue(pending.detailedReentryPending)
            assertEquals(WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL, pending.weather.fieldSemantics.quality)
            assertEquals(coarse.world.seed, pending.world.seed)
            assertEquals(coarse.weather.soilWaterMm(3, 3), pending.weather.soilWaterMm(3, 3))
            worker.updateControls(SimulationSpeed.ONE_HOUR, false)
            await { worker.latestSnapshot()?.weather?.fieldSemantics?.quality == WeatherFieldQuality.RESOLVED_NOW }
            val resumed = worker.latestSnapshot()!!
            assertTrue(resumed.progress.completedSeconds > coarse.progress.completedSeconds)
            assertEquals(1L, resumed.progress.generation)
            assertEquals(null, worker.failure)
        } finally { worker.close() }
        await { !worker.isRunning }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue(condition(), "Worker did not acknowledge the bounded fidelity operation")
    }
}
