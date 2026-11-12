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
    fun nativeWorldReturnsFromTwoHundredFiftyFiveYearsWithActiveResolvedDynamics() {
        val owner = WorldSimulation(WorldRequest(dynamical = true))
        fun capture() = owner.snapshot(SimulationProgress(0, 1, 0, owner.completedSeconds,
            SimulationSpeed.ONE_HOUR, 0.0, 0.0, 0.0, day, false, 0.0, 0.0, 0))
        owner.advance(owner.stepSeconds, SimulationSpeed.ONE_HOUR)
        val water = capture().diagnostics!!.totalWaterKg
        repeat(12) { owner.advanceClimateInterval(20 * 365 * day) }
        owner.advanceClimateInterval(15 * 365 * day)
        val coarse = capture()
        owner.setFidelity(SimulationFidelity.DETAILED)
        val pending = capture()
        assertEquals(coarse.progress.completedSeconds, pending.progress.completedSeconds)
        assertTrue(coarse.world === pending.world && coarse.hydrology === pending.hydrology)
        assertTrue(coarse.seasonalClimate === pending.seasonalClimate)
        assertTrue(pending.lastHandoff!!.rootMeanSquareWindMetersPerSecond > 1)
        assertTrue(pending.lastHandoff.maximumWindMetersPerSecond <= 35)
        assertEquals(1L, pending.lastHandoff.physicalStepsAtHandoff)
        assertFalse(pending.weather.hasResolvedDynamics)
        for (y in 0 until pending.world.height) for (x in 0 until pending.world.width) {
            assertEquals(coarse.weather.soilWaterMm(x, y), pending.weather.soilWaterMm(x, y))
            assertEquals(coarse.weather.surfaceTemperatureC(x, y), pending.weather.surfaceTemperatureC(x, y))
            assertEquals(coarse.weather.atmosphericWaterMm(x, y), pending.weather.atmosphericWaterMm(x, y), 1e-10)
        }
        repeat(90) { owner.advance(40.0, SimulationSpeed.ONE_HOUR) }
        val resolved = capture()
        assertEquals(coarse.progress.completedSeconds + 3600, owner.completedSeconds)
        assertTrue(resolved.weather.hasResolvedDynamics)
        assertTrue(resolved.weather.pressureAnomalyPercent(160, 100)!!.isFinite())
        assertTrue(resolved.weather.relativeVorticityPerSecond(160, 100)!!.isFinite())
        assertEquals(water, resolved.diagnostics!!.totalWaterKg, water * 1e-10)
        assertTrue(abs(resolved.diagnostics.relativeWaterResidual) < 1e-10)
        println("NATIVE_REENTRY years=255 climate_transactions=13 rms_wind_ms=${pending.lastHandoff.rootMeanSquareWindMetersPerSecond} max_wind_ms=${pending.lastHandoff.maximumWindMetersPerSecond} water_residual=${resolved.diagnostics.relativeWaterResidual}")
    }

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
    fun centuriesOfClimateDoNotLeaveDetailedWeatherCloudlessForTwentyHours() {
        val owner = WorldSimulation(request.copy(width = 48, height = 36,
            atmosphere = DryAtmosphereConfig(width = 24, height = 18)))
        fun capture() = owner.snapshot(SimulationProgress(1, 1, 0, owner.completedSeconds,
            SimulationSpeed.ONE_HOUR, 0.0, 0.0, 0.0, day, false, 0.0, 0.0, 0))
        owner.advance(20.0, SimulationSpeed.ONE_HOUR)
        val initialWater = capture().diagnostics!!.totalWaterKg
        repeat(255) { owner.advanceClimateInterval(365 * day) }
        val coarse = capture()
        val records = StringBuilder()
        val output = java.io.File("build/reports/clouds/reentry-255.jsonl")
        output.parentFile.mkdirs()
        fun record(label: String, snapshot: WorldPresentationSnapshot) {
            val w = snapshot.weather
            val n = w.width * w.height.toDouble()
            fun mean(field: (Int, Int) -> Double) =
                (0 until w.height).sumOf { y -> (0 until w.width).sumOf { x -> field(x, y) } } / n
            // Fresh texture each time: an old representative frame cannot hide missing condensate.
            val renderer = terra.render.CloudRenderer(w.width, w.height, snapshot.world.seed)
            renderer.update(w)
            val frame = renderer.frame
            val image = java.awt.image.BufferedImage(frame.width, frame.height * 2,
                java.awt.image.BufferedImage.TYPE_INT_RGB)
            var visible = 0
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val pixel = frame[x, y]
                val a = pixel.a / 255.0
                image.setRGB(x, y, java.awt.Color((27 * (1 - a) + pixel.r * a).toInt(),
                    (65 * (1 - a) + pixel.g * a).toInt(), (86 * (1 - a) + pixel.b * a).toInt()).rgb)
                val raw = (255 * (1 - kotlin.math.exp(-w.cloudWaterMm(x / 2, y / 2) / 0.15))).toInt().coerceIn(0, 255)
                image.setRGB(x, y + frame.height, java.awt.Color(raw, raw, raw).rgb)
                if (pixel.a > 24) visible++
            }
            javax.imageio.ImageIO.write(image, "png", java.io.File(output.parentFile, "reentry-$label.png"))
            val row = """{"label":"$label","seconds":${owner.completedSeconds},"quality":"${w.fieldSemantics.quality}","water_mm":${mean(w::atmosphericWaterMm)},"cloud_mm":${mean(w::cloudWaterMm)},"rh":${mean(w::relativeHumidity)},"air_c":${mean(w::airTemperatureC)},"surface_c":${mean(w::surfaceTemperatureC)},"abs_wind_tiles_day":${mean { x, y -> abs(w.windUTilesPerDay(x, y)) + abs(w.windVTilesPerDay(x, y)) }},"visible_fraction":${visible.toDouble() / (frame.width * frame.height)}}"""
            records.appendLine(row)
            println("REENTRY $row")
            if (label == "detailed1") assertTrue(visible > 0, "This moist fixture should recover resolved clouds within one hour")
        }
        record("climate255", coarse)
        owner.setFidelity(SimulationFidelity.DETAILED)
        val pending = capture()
        record("handoff", pending)
        println("REENTRY_HANDOFF ${pending.lastHandoff}")
        assertTrue(pending.lastHandoff!!.rootMeanSquareWindMetersPerSecond > 1.0)
        assertTrue(pending.lastHandoff.maximumWindMetersPerSecond <= 35.0)
        for (hour in 1..20) {
            repeat(180) { owner.advance(20.0, SimulationSpeed.ONE_HOUR) }
            if (hour in listOf(1, 6, 20)) record("detailed$hour", capture())
        }
        val resolved = capture()
        assertEquals(coarse.progress.completedSeconds + 20 * 3600, owner.completedSeconds)
        assertEquals(initialWater, resolved.diagnostics!!.totalWaterKg, initialWater * 1e-10)
        assertTrue(abs(resolved.diagnostics.relativeWaterResidual) < 1e-10)
        val renderer = terra.render.CloudRenderer(resolved.world.width, resolved.world.height, resolved.world.seed)
        renderer.update(resolved.weather)
        var visible = 0
        for (y in 0 until renderer.frame.height) for (x in 0 until renderer.frame.width)
            if (renderer.frame[x, y].a > 24) visible++
        output.writeText(records.toString())
        println("REENTRY_WATER relative_residual=${resolved.diagnostics.relativeWaterResidual}")
        println("REENTRY_VISIBLE pixels=$visible")
        assertTrue(visible > 0, "A moist world must recover visible resolved clouds within twenty hours")
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
