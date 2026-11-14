package terra.weather

import kotlin.math.*
import kotlin.test.*
import terra.atmosphere.*
import terra.world.World

class AtlasInitializationTest {
    @Test fun startupUsesTheCurrentAtlasMonthAndReentryUsesTheSameMechanism() {
        val config = DryAtmosphereConfig(width = 16, height = 12)
        val physics = MoistPhysicsConfig(solar = SolarConfig(initialYearFraction = 0.25))
        val world = World(42, 48, 36)
        val weather = DynamicalWeather(world, config, physics)
        val initial = weather.atmosphereSnapshot
        val atlas = weather.atlas
        val fields = arrayOf(atlas.temperature[3], atlas.humidity[3], atlas.east[3], atlas.south[3])
        val expected = Array(4) { DoubleArray(config.cellCount).also { target -> atlas.geography.overlap.restrict(fields[it], target) } }
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val j = (config.height - 1 - y) * config.width + x
            assertEquals(expected[0][j] + 273.15, initial.temperatureKelvinAt(x, y), 1e-10)
            assertEquals(expected[1][j], initial.relativeHumidityAt(x, y), 1e-10)
            assertEquals(expected[2][j], initial.dynamics.eastWindAt(x, y), 1e-10)
            assertEquals(-expected[3][j], initial.dynamics.northWindAt(x, y), 1e-10)
        }
        assertEquals(0.0, weather.elapsedSeconds)
        assertEquals(0L, weather.detailedCompletedSteps)
        assertFalse(weather.diagnostics.includesApproximateHistory)
        weather.advanceClimateInterval(physics.solar.yearSeconds)
        val soil = weather.surfaceState.soil.copyOf()
        weather.resumeDetailed()
        val returned = weather.atmosphereSnapshot
        assertTrue(soil.contentEquals(weather.surfaceState.soil))
        for (y in 0 until config.height) for (x in 0 until config.width) {
            assertEquals(initial.temperatureKelvinAt(x, y), returned.temperatureKelvinAt(x, y), 1e-10)
            assertEquals(initial.relativeHumidityAt(x, y), returned.relativeHumidityAt(x, y), 1e-10)
            assertEquals(initial.dynamics.heightAt(x, y), returned.dynamics.heightAt(x, y), 1e-8)
            assertEquals(initial.dynamics.eastWindAt(x, y), returned.dynamics.eastWindAt(x, y), 1e-10)
        }
        weather.advanceBudgeted(3600.0, Int.MAX_VALUE)
        assertTrue(weather.diagnostics.relativeWaterResidual.absoluteValue < 1e-10)
        assertTrue(weather.atmosphereSnapshot.dynamics.diagnostics.maximumSpeedMetersPerSecond < 20)
    }

    @Test fun uniformAtlasPressureTemperatureAndWindRemainAtRestWithoutHeating() {
        val config = DryAtmosphereConfig(width = 12, height = 8)
        val model = MoistAtmosphere(config, MoistPhysicsConfig(radiationEnabled = false, surfaceExchangeEnabled = false))
        model.initializeAtlasRealization(0.0, DoubleArray(config.cellCount) { 285.0 }, DoubleArray(config.cellCount) { 0.5 },
            DoubleArray(config.cellCount), DoubleArray(config.cellCount), ClimateAtlasBuilder.THERMAL_PRESSURE_COUPLING, initial = true)
        repeat(180) { model.step() }
        assertTrue(model.snapshot().dynamics.diagnostics.maximumSpeedMetersPerSecond < 1e-10)
        assertTrue(abs(model.diagnostics().relativeWaterResidual) < 1e-12)
    }
}
