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
    fun subsaturatedCloudPartitionIsAnEquilibriumOfDetailedMicrophysics() {
        val t = 280.0
        val sat = closedPhysics.referenceDryColumnKgM2 * closedPhysics.humidColumnFraction *
            FastSaturation.mixingRatio(t, closedPhysics.referenceDryColumnKgM2 * geometry.gravity * closedPhysics.cloudPressureFraction)
        val water = 0.85 * sat
        val cloud = ColumnThermodynamics.cloudMm(water,sat)
        val model = MoistAtmosphere(geometry,closedPhysics,DryScenario.REST) { _, _ ->
            MoistCell(temperatureKelvin = t,relativeHumidity = (water-cloud)/sat,cloudWaterKgM2 = cloud)
        }
        repeat(180) { model.step() }
        val after = model.snapshot()
        assertTrue(cloud > 0)
        assertEquals(cloud,after.cloudWaterMmAt(3,3),1e-10)
        assertEquals(t,after.temperatureKelvinAt(3,3),1e-10)
        assertEquals(0.0,after.rainMmPerHourAt(3,3))
        assertTrue(abs(after.diagnostics.relativeWaterResidual) < 1e-12)
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
    fun smallCloudsStayDryWhileFundedRainEventsEndAndConserveWaterAndEnergy() {
        fun cloud(amount: Double) = MoistAtmosphere(geometry, closedPhysics, DryScenario.REST) { _, _ ->
            MoistCell(relativeHumidity = 1.0, cloudWaterKgM2 = amount)
        }
        val small = cloud(0.20)
        repeat(90) { small.step() }
        assertEquals(0.0, small.snapshot().rainMmPerHourAt(3, 3))
        assertTrue(small.snapshot().cloudWaterMmAt(3, 3) > 0.15)
        val event = cloud(0.60)
        val before = event.snapshot()
        event.step()
        assertTrue(event.snapshot().rainMmPerHourAt(3, 3) > 2.0)
        repeat(180) { event.step() }
        val after = event.snapshot()
        assertEquals(0.0, after.rainMmPerHourAt(3, 3), "An exhausted event must actually stop")
        assertTrue(after.cloudWaterMmAt(3, 3) > 0.09, "Nonprecipitating cloud remains")
        assertTrue(abs(after.diagnostics.relativeWaterResidual) < 1e-12)
        assertTrue(abs(after.diagnostics.energyResidualJoules / before.diagnostics.totalModelEnergyJoules) < 1e-12)
    }

    @Test
    fun evolvedAirSurvivesReentryAndSaturationAgreesAcrossPressures() {
        val model = MoistAtmosphere(geometry, closedPhysics, DryScenario.REST) { _, _ -> MoistCell() }
        val temperatures = DoubleArray(geometry.cellCount) { 260.0 + it / geometry.width }
        model.initializeAtlasRealization(255 * 365 * 86400.0, temperatures, DoubleArray(geometry.cellCount) { 0.65 },
            DoubleArray(geometry.cellCount), DoubleArray(geometry.cellCount), 25.0)
        val after = model.snapshot()
        for (y in 0 until geometry.height) for (x in 0 until geometry.width)
            assertEquals(temperatures[y * geometry.width + x], after.temperatureKelvinAt(x, y), 1e-12)
        for (temperature in listOf(220.25, 280.25, 310.25, 335.25)) for (pressure in listOf(45_000.0, 60_000.0, 100_000.0)) {
            val exact = WarmCloud.saturationMixingRatio(temperature, pressure)
            assertEquals(exact, FastSaturation.mixingRatio(temperature, pressure), exact * 0.001)
        }
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
