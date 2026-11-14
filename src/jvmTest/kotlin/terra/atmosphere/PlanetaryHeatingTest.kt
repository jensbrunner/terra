package terra.atmosphere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanetaryHeatingTest {
    @Test
    fun unresolvedOverturningSuppliesMomentumWithAnExplicitSignedEnergyBudget() {
        val grid = DryAtmosphereConfig(width = 12,height = 9)
        val model = MoistAtmosphere(grid,scenario = DryScenario.REST) { _, _ ->
            MoistCell(relativeHumidity = 0.0)
        }
        repeat(180) { model.step() }
        val s = model.snapshot()
        assertTrue(s.diagnostics.overturningEnergyJoules > 0)
        assertTrue(s.dynamics.eastWindAt(4,6) > 0)
        assertTrue(abs(s.diagnostics.unexplainedEnergyResidualJoules /
            s.diagnostics.totalModelEnergyJoules) < 1e-11)
        assertTrue(abs(s.diagnostics.relativeWaterResidual) < 1e-12)
    }

    @Test
    fun solarGeometryTracksDaySeasonHemispheresAndTerrainOrientation() {
        val width = 16
        val height = 9
        val config = SolarConfig()
        val sky = PlanetarySunlight(width, height, config)
        val tiles = PlanetarySunlight(width, height, config, northUp = false)
        val summer = config.yearSeconds * 0.25
        sky.update(summer)
        tiles.update(summer)
        assertEquals(23.44 * PI / 180, sky.declinationRadians, 1e-12)
        for (x in 0 until width) {
            // The represented polar row has continuous summer daylight/winter darkness.
            assertTrue(sky.inputFactor(x, 8) > 0)
            assertEquals(0.0, sky.inputFactor(x, 0))
            assertEquals(0.0, sky.nightOpacity(x, 8))
            assertEquals(0.65, sky.nightOpacity(x, 0))
            for (y in 0 until height) {
                assertEquals(sky.inputFactor(x, y), tiles.inputFactor(x, height - 1 - y), 1e-14)
            }
        }
        sky.update(config.yearSeconds * 0.75)
        assertEquals(-23.44 * PI / 180, sky.declinationRadians, 1e-12)
        assertTrue(sky.inputFactor(0, 0) > 0)
        assertEquals(0.0, sky.inputFactor(0, 8))
        sky.update(0.0)
        assertEquals(0.0, sky.declinationRadians)
        for (y in 0 until height) for (x in 0 until width) {
            val expected = cos(PlanetLatitude.atmosphereRowRadians(y, height)) *
                cos(2 * PI * ((x + 0.5) / width - 0.5))
            assertEquals(expected, sky.cosineZenith(x, y), 1e-14)
        }
        val untilted = PlanetarySunlight(width, height, config.copy(axialTiltDegrees = 0.0))
        untilted.update(config.daySeconds * 0.5)
        assertTrue(untilted.inputFactor(0, 4) > 0.9)
        assertEquals(0.0, untilted.inputFactor(8, 4))
        untilted.update(config.yearSeconds)
        assertEquals(0.0, untilted.declinationRadians)
        assertTrue(untilted.inputFactor(8, 4) > 0.9)
    }

    @Test
    fun radiationHeatsTropicsAndRetainsSurfaceThermalInertiaWithAnExplicitBudget() {
        val geometry = DryAtmosphereConfig(width = 8, height = 9)
        val physics = MoistPhysicsConfig(surfaceExchangeEnabled = false, momentumDragPerSecond = 0.0)
        fun create(capacity: Double) = MoistAtmosphere(geometry,
            physics.copy(surfaceHeatCapacityJm2K = capacity), DryScenario.REST) { _, _ ->
            MoistCell(relativeHumidity = 0.0, surfaceTemperatureKelvin = 280.0, surfaceWaterKgM2 = 2.0)
        }
        val land = create(2_000_000.0)
        val ocean = create(41_800_000.0)
        land.step()
        ocean.step()
        val l = land.snapshot()
        val o = ocean.snapshot()
        assertTrue(l.surfaceTemperatureKelvinAt(3, 4) > l.surfaceTemperatureKelvinAt(3, 8))
        assertTrue(l.surfaceTemperatureKelvinAt(3, 4) > o.surfaceTemperatureKelvinAt(3, 4))
        val landHeat = (l.surfaceTemperatureKelvinAt(3, 4) - 280) * 2_000_000.0
        val oceanHeat = (o.surfaceTemperatureKelvinAt(3, 4) - 280) * 41_800_000.0
        assertEquals(landHeat, oceanHeat, 2e-6)
        for (state in listOf(l, o)) {
            assertTrue(state.diagnostics.netRadiationJoules != 0.0)
            assertTrue(abs(state.diagnostics.energyResidualJoules / state.diagnostics.totalModelEnergyJoules) < 1e-12)
            assertTrue(abs(state.diagnostics.relativeWaterResidual) < 1e-12)
        }
    }

    @Test
    fun momentumDampingReturnsLostKineticEnergyAsHeat() {
        val geometry = DryAtmosphereConfig(width = 12, height = 8)
        val physics = MoistPhysicsConfig(radiationEnabled = false, surfaceExchangeEnabled = false, momentumDragPerSecond = 0.0)
        fun create(drag: Double) = MoistAtmosphere(geometry, physics.copy(momentumDragPerSecond = drag)) { _, _ ->
            MoistCell(relativeHumidity = 0.0)
        }
        val control = create(0.0)
        val damped = create(1.0 / 3600)
        control.step()
        damped.step()
        val c = control.snapshot()
        val d = damped.snapshot()
        val retained = exp(-geometry.stepSeconds / 3600)
        assertEquals(c.dynamics.eastWindAt(3, 2) * retained, d.dynamics.eastWindAt(3, 2), 1e-12)
        assertEquals(c.dynamics.northWindAt(3, 2) * retained, d.dynamics.northWindAt(3, 2), 1e-12)
        assertTrue(d.temperatureKelvinAt(3, 2) > c.temperatureKelvinAt(3, 2))
        assertTrue(d.diagnostics.frictionHeatingJoules > 0)
        assertEquals(0.0, d.diagnostics.netRadiationJoules)
        assertTrue(abs(d.diagnostics.totalModelEnergyJoules / c.diagnostics.totalModelEnergyJoules - 1) < 1e-12)
        assertTrue(abs(d.diagnostics.relativeWaterResidual) < 1e-12)
    }
}
