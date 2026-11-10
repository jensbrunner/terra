package terra.atmosphere

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LatitudeChannelTest {
    @Test
    fun signedLatitudeAndExactRowRotationPreserveKineticEnergy() {
        val config = DryAtmosphereConfig(width = 8, height = 9)
        val model = DryAtmosphere(config)
        val east = DoubleArray(config.cellCount) { 3.0 }
        val north = DoubleArray(config.cellCount) { 4.0 }
        repeat(100) { model.rotateHalfStep(east, north) }
        for (y in 0 until config.height) {
            val latitude = PlanetLatitude.atmosphereRowRadians(y, config.height)
            assertEquals(-latitude, PlanetLatitude.terrainRowRadians(y, config.height), 1e-15)
            assertEquals(-latitude, PlanetLatitude.atmosphereRowRadians(config.height - 1 - y, config.height), 1e-15)
            val expectedF = 2 * config.rotationRateRadiansPerSecond * sin(latitude)
            val angle = expectedF * config.stepSeconds * 50
            val i = y * config.width
            assertEquals(3 * cos(angle) + 4 * sin(angle), east[i], 1e-12)
            assertEquals(-3 * sin(angle) + 4 * cos(angle), north[i], 1e-12)
            assertEquals(25.0, east[i] * east[i] + north[i] * north[i], 1e-12)
        }
        assertEquals(0.0, config.coriolisAtRow(4))
        assertTrue(config.coriolisAtRow(0) < 0 && config.coriolisAtRow(8) > 0)
        // The full integrator must use these rotations in both hemispheres too.
        val flow = DryAtmosphere(config) { _, _ -> DryCell(10_000.0, 10.0, 0.0) }
        flow.step()
        val state = flow.snapshot()
        assertTrue(state.northWindAt(3, 2) > 0)
        assertTrue(state.northWindAt(3, 6) < 0)
        assertEquals(0.0, state.northWindAt(3, 4), 1e-12)
    }

    @Test
    fun wallsPreserveRestAndFreeSlipAndRejectUnstableSteps() {
        val config = DryAtmosphereConfig(width = 8, height = 9)
        val rest = DryAtmosphere(config)
        repeat(50) { rest.step() }
        val still = rest.snapshot()
        for (y in 0 until config.height) {
            assertEquals(10_000.0, still.heightAt(3, y))
            assertEquals(0.0, still.eastWindAt(3, y))
            assertEquals(0.0, still.northWindAt(3, y))
        }
        val noRotation = config.copy(rotationRateRadiansPerSecond = 0.0)
        val shear = DryAtmosphere(noRotation) { _, y -> DryCell(10_000.0, 10 * y / noRotation.dy, 0.0) }
        repeat(50) { shear.step() }
        val state = shear.snapshot()
        for (y in 0 until config.height) {
            assertEquals(10 * (y + 0.5), state.eastWindAt(3, y), 1e-10)
            assertEquals(0.0, state.northWindAt(3, y), 1e-10)
        }
        // Even tangential ghost velocities: derivatives never connect opposite walls.
        assertEquals(-5 / config.dy, state.vorticityAt(3, 0), 1e-14)
        assertEquals(-5 / config.dy, state.vorticityAt(3, 8), 1e-14)
        val unstable = DryAtmosphere(config.copy(stepSeconds = 20_000.0))
        assertFailsWith<IllegalStateException> { unstable.step() }
        assertEquals(0L, unstable.completedSteps)
        assertEquals(10_000.0, unstable.snapshot().heightAt(3, 0))
    }

    @Test
    fun pressurePulseReflectsAtWallWithoutPolarTeleportationAndStillWrapsX() {
        val config = DryAtmosphereConfig(width = 12, height = 12, rotationRateRadiansPerSecond = 0.0)
        // Launch a northward gravity wave near the north wall. Early propagation
        // cannot reach the southern rows, but it crosses the periodic X seam.
        val model = DryAtmosphere(config) { x, y ->
            val row = (y / config.dy).toInt()
            val column = (x / config.dx).toInt()
            DryCell(10_000.0 + if (row == 10 && column == 11) 100.0 else 0.0, 0.0, 0.0)
        }
        val volume = model.diagnostics().volumeCubicMeters
        repeat(2) { model.step() }
        var state = model.snapshot()
        assertEquals(10_000.0, state.heightAt(11, 0))
        assertEquals(0.0, state.northWindAt(11, 0))
        assertTrue(state.heightAt(0, 10) > 10_000.0)
        // Over enough time to hit the wall, the integrated wall pressure must
        // reverse the initial northward impulse rather than simply omit a face.
        val pulse = DryAtmosphere(config) { _, y ->
            val distance = y - config.domainHeightMeters * 0.85
            val anomaly = 50 * exp(-distance * distance / (2 * config.dy * config.dy))
            DryCell(10_000.0 + anomaly, 0.0, sqrt(config.gravity / 10_000.0) * anomaly)
        }
        val initialVolume = pulse.diagnostics().volumeCubicMeters
        repeat(700) { pulse.step() }
        state = pulse.snapshot()
        assertTrue((8..11).any { state.northWindAt(3, it) < -0.01 })
        assertEquals(1.0, state.diagnostics.volumeCubicMeters / initialVolume, 1e-12)
        assertEquals(1.0, model.diagnostics().volumeCubicMeters / volume, 1e-12)
    }

    @Test
    fun channelVorticesRemainFiniteAndTransportClosedMoistureWithoutPolarLeakage() {
        val config = DryAtmosphereConfig(width = 16, height = 12)
        val physics = MoistPhysicsConfig(radiationEnabled = false, surfaceExchangeEnabled = false)
        val model = MoistAtmosphere(config, physics, DryScenario.VORTEX) { _, y ->
            MoistCell(relativeHumidity = if (y > config.domainHeightMeters * 0.75) 0.4 else 0.0,
                surfaceWaterKgM2 = 0.0)
        }
        val initial = model.snapshot()
        // Equivalent pressure lows rotate in opposite directions at initialization.
        assertTrue(initial.dynamics.vorticityAt(8, 3) < 0)
        assertTrue(initial.dynamics.vorticityAt(8, 8) > 0)
        model.step()
        assertEquals(0.0, model.snapshot().vaporMmAt(8, 0))
        repeat(399) { model.step() }
        val final = model.snapshot()
        assertTrue(abs(final.diagnostics.relativeWaterResidual) < 1e-12)
        assertEquals(1.0, final.dynamics.diagnostics.volumeCubicMeters / initial.dynamics.diagnostics.volumeCubicMeters, 1e-12)
        for (y in 0 until config.height) for (x in 0 until config.width) {
            assertTrue(final.vaporMmAt(x, y).isFinite() && final.vaporMmAt(x, y) >= 0)
            assertTrue(final.cloudWaterMmAt(x, y).isFinite() && final.cloudWaterMmAt(x, y) >= 0)
        }
        assertTrue(final.dynamics.diagnostics.maximumCourant <= config.courantLimit)
    }
}
