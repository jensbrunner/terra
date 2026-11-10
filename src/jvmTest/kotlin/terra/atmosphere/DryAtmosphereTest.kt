package terra.atmosphere

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DryAtmosphereTest {
    @Test
    fun aStationaryShearFlowIsNotDiffusedByGravityWaveSpeed() {
        val config = DryAtmosphereConfig(geometry = AtmosphereGeometry.PERIODIC_F_PLANE, width = 16, height = 12, coriolisPerSecond = 0.0)
        val model = DryAtmosphere(config) { _, y ->
            DryCell(config.meanHeightMeters, 20 * sin(2 * PI * y / config.domainHeightMeters), 0.0)
        }
        val initial = model.snapshot()
        repeat(200) { model.step() }
        val final = model.snapshot()
        for (y in 0 until config.height) {
            assertTrue(abs(initial.eastWindAt(4, y) - final.eastWindAt(4, y)) < 1e-10)
            assertTrue(abs(final.northWindAt(4, y)) < 1e-10)
        }
    }

    @Test
    fun restAndRejectedStepLeaveStateUnchanged() {
        val model = DryAtmosphere(DryAtmosphereConfig(geometry = AtmosphereGeometry.PERIODIC_F_PLANE, width = 8, height = 6))
        repeat(100) { model.step() }
        val state = model.snapshot()
        assertEquals(10_000.0, state.heightAt(3, 2))
        assertEquals(0.0, state.eastWindAt(3, 2))
        assertEquals(0.0, state.northWindAt(3, 2))
        val unstable = DryAtmosphere(DryAtmosphereConfig(geometry = AtmosphereGeometry.PERIODIC_F_PLANE, width = 8, height = 6, stepSeconds = 20_000.0))
        assertFailsWith<IllegalStateException> { unstable.step() }
        assertEquals(0L, unstable.completedSteps)
        assertEquals(10_000.0, unstable.snapshot().heightAt(3, 2))
    }

    @Test
    fun inertialFlowMatchesAnalyticRotationAndReversesWithHemisphere() {
        for (f in listOf(1e-4, -1e-4)) {
            val config = DryAtmosphereConfig(geometry = AtmosphereGeometry.PERIODIC_F_PLANE, width = 8, height = 6, coriolisPerSecond = f)
            val model = DryAtmosphere(config) { _, _ -> DryCell(10_000.0, 10.0, 0.0) }
            repeat(500) { model.step() }
            val state = model.snapshot()
            val angle = f * model.elapsedSeconds
            assertTrue(abs(state.eastWindAt(3, 2) - 10 * cos(angle)) < 1e-10)
            assertTrue(abs(state.northWindAt(3, 2) + 10 * sin(angle)) < 1e-10)
        }
    }

    @Test
    fun pressurePulseAcceleratesAwayFromHighHeight() {
        val model = DryScenarios.create(DryScenario.PULSE,
            DryAtmosphereConfig(geometry = AtmosphereGeometry.PERIODIC_F_PLANE, width = 24, height = 18, coriolisPerSecond = 0.0))
        model.step()
        val state = model.snapshot()
        assertTrue(state.eastWindAt(13, 9) > 0)
        assertTrue(state.eastWindAt(10, 9) < 0)
        assertTrue(state.northWindAt(12, 10) > 0)
        assertTrue(state.northWindAt(12, 7) < 0)
    }

    @Test
    fun periodicTransportConservesVolumeAndSnapshotCadenceDoesNotAlterEvolution() {
        val config = DryAtmosphereConfig(geometry = AtmosphereGeometry.PERIODIC_F_PLANE, width = 24, height = 18)
        val observed = DryScenarios.create(config = config)
        val direct = DryScenarios.create(config = config)
        val initial = observed.snapshot()
        val oldHeight = initial.heightAt(5, 7)
        repeat(600) {
            observed.step(); direct.step()
            if (it % 37 == 0) observed.snapshot()
        }
        val a = observed.snapshot()
        val b = direct.snapshot()
        for (y in 0 until config.height) for (x in 0 until config.width) {
            assertEquals(a.heightAt(x, y), b.heightAt(x, y))
            assertEquals(a.eastWindAt(x, y), b.eastWindAt(x, y))
            assertEquals(a.northWindAt(x, y), b.northWindAt(x, y))
        }
        assertTrue(abs(a.diagnostics.volumeCubicMeters / initial.diagnostics.volumeCubicMeters - 1) < 1e-12)
        assertTrue(a.diagnostics.minimumHeightMeters > 0)
        assertTrue(a.diagnostics.maximumSpeedMetersPerSecond.isFinite())
        assertEquals(oldHeight, initial.heightAt(5, 7))
    }
}
