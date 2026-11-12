package terra.atmosphere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoistAtmosphereTest {
    private val geometry = DryAtmosphereConfig(width = 12, height = 8)
    private val closedPhysics = MoistPhysicsConfig(radiationEnabled = false, surfaceExchangeEnabled = false)

    @Test
    fun climateReconstructionPreservesColumnWaterAndCarrierVolumeWithLocalTemperatures() {
        val model = MoistAtmosphere(geometry, closedPhysics, DryScenario.REST) { _, y ->
            MoistCell(relativeHumidity = 0.95, cloudWaterKgM2 = 0.2,
                surfaceTemperatureKelvin = 270 + 20 * y / geometry.domainHeightMeters)
        }
        val before = model.snapshot()
        val handoff = model.initializeFromClimate(255 * 365 * 86400.0)
        val after = model.snapshot()
        var volumeBefore = 0.0
        var volumeAfter = 0.0
        val pressure = after.dynamics.heightAt(0, 0).let { it * it * after.temperatureKelvinAt(0, 0) }
        for (y in 0 until geometry.height) for (x in 0 until geometry.width) {
            assertEquals(before.vaporMmAt(x, y) + before.cloudWaterMmAt(x, y),
                after.vaporMmAt(x, y) + after.cloudWaterMmAt(x, y), 1e-12)
            assertEquals(before.surfaceTemperatureKelvinAt(x, y) - 6.0,
                after.temperatureKelvinAt(x, y), 1e-12)
            val depth = after.dynamics.heightAt(x, y)
            assertEquals(pressure, depth * depth * after.temperatureKelvinAt(x, y), pressure * 1e-12)
            volumeBefore += before.dynamics.heightAt(x, y)
            volumeAfter += depth
        }
        assertEquals(volumeBefore, volumeAfter, volumeBefore * 1e-12)
        assertTrue(handoff.maximumAirTemperatureKelvin > handoff.minimumAirTemperatureKelvin)
        assertEquals(0L, model.completedSteps)
        assertEquals(handoff.worldSeconds, model.worldSeconds)
        model.step()
        val diagnostics = model.snapshot().diagnostics
        assertTrue(abs(diagnostics.relativeWaterResidual) < 1e-12)
        // Reconstruction energy is deliberately reported, not silently removed from raw residuals.
        val unexplained = diagnostics.unexplainedEnergyResidualJoules - handoff.initializationEnergyChangeJoules
        assertTrue(abs(unexplained / diagnostics.totalModelEnergyJoules) < 1e-10)
    }

    @Test
    fun condensationKeepsCloudAndReleasesLatentHeatWithoutLosingWaterOrEnergy() {
        val model = MoistAtmosphere(geometry, closedPhysics, DryScenario.REST) { _, _ ->
            MoistCell(relativeHumidity = 1.15, surfaceWaterKgM2 = 2.0)
        }
        val initial = model.snapshot()
        model.step()
        val final = model.snapshot()
        assertTrue(final.cloudWaterMmAt(3, 3) > 0)
        assertTrue(final.vaporMmAt(3, 3) < initial.vaporMmAt(3, 3))
        assertTrue(final.temperatureKelvinAt(3, 3) > initial.temperatureKelvinAt(3, 3))
        assertTrue(abs(final.diagnostics.relativeWaterResidual) < 1e-12)
        assertTrue(abs(final.diagnostics.energyResidualJoules / initial.diagnostics.totalModelEnergyJoules) < 1e-12)
    }

    @Test
    fun surfaceEvaporationDoesNotNeedSunlightAndIsPaidForBySurfaceHeat() {
        val physics = MoistPhysicsConfig(radiationEnabled = false)
        val model = MoistAtmosphere(geometry, physics, DryScenario.REST) { _, _ ->
            MoistCell(relativeHumidity = 0.2, surfaceWaterKgM2 = 2.0)
        }
        val initial = model.snapshot()
        model.step()
        val final = model.snapshot()
        assertTrue(final.surfaceWaterMmAt(0, 0) < initial.surfaceWaterMmAt(0, 0))
        assertTrue(final.vaporMmAt(0, 0) > initial.vaporMmAt(0, 0))
        assertTrue(final.surfaceTemperatureKelvinAt(0, 0) < initial.surfaceTemperatureKelvinAt(0, 0))
        assertTrue(abs(final.diagnostics.relativeWaterResidual) < 1e-12)
        assertTrue(abs(final.diagnostics.energyResidualJoules / initial.diagnostics.totalModelEnergyJoules) < 1e-12)
    }

    @Test
    fun rainfallTransfersCloudWaterIntoTheSurfaceReservoir() {
        val model = MoistAtmosphere(geometry, closedPhysics, DryScenario.REST) { _, _ ->
            MoistCell(relativeHumidity = 1.0, cloudWaterKgM2 = 1.0, surfaceWaterKgM2 = 2.0)
        }
        val initial = model.snapshot()
        model.step()
        val final = model.snapshot()
        assertTrue(final.rainMmPerHourAt(3, 3) > 0)
        assertTrue(final.cloudWaterMmAt(3, 3) < initial.cloudWaterMmAt(3, 3))
        assertTrue(final.surfaceWaterMmAt(3, 3) > initial.surfaceWaterMmAt(3, 3))
        assertTrue(abs(final.diagnostics.relativeWaterResidual) < 1e-12)
    }

    @Test
    fun transportedWaterIsConservedAndThermalGradientsAccelerateAir() {
        val model = MoistAtmosphere(geometry, closedPhysics, DryScenario.REST) { x, _ ->
            MoistCell(temperatureKelvin = 280 + 2 * cos(2 * PI * x / geometry.domainWidthMeters),
                relativeHumidity = 0.2, surfaceWaterKgM2 = 2.0)
        }
        repeat(200) { model.step() }
        val final = model.snapshot()
        assertTrue(final.dynamics.eastWindAt(geometry.width / 4, 3) > 0)
        assertTrue(abs(final.diagnostics.relativeWaterResidual) < 1e-11)
        assertTrue(abs(final.diagnostics.unexplainedEnergyResidualJoules / final.diagnostics.totalModelEnergyJoules) < 1e-10)
        for (y in 0 until geometry.height) for (x in 0 until geometry.width) {
            assertTrue(final.vaporMmAt(x, y).isFinite() && final.vaporMmAt(x, y) >= 0)
            assertTrue(final.cloudWaterMmAt(x, y).isFinite() && final.cloudWaterMmAt(x, y) >= 0)
        }
    }
}
