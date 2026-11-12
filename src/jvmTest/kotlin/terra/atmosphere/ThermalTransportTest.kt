package terra.atmosphere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThermalTransportTest {
    @Test
    fun stationaryThermalContactDoesNotGenerateGravityWaveDiffusion() {
        val config = DryAtmosphereConfig(width = 24, height = 12,
            geometry = AtmosphereGeometry.PERIODIC_F_PLANE, coriolisPerSecond = 0.0)
        fun depth(x: Double) = config.meanHeightMeters * if (x < config.domainWidthMeters / 2) 0.96 else 1.04
        val physics = MoistPhysicsConfig(radiationEnabled = false, surfaceExchangeEnabled = false)
        val model = MoistAtmosphere(config, physics, initializeFlow = { x, _ -> DryCell(depth(x), 0.0, 0.0) },
            initializeColumns = { x, _ -> MoistCell(temperatureKelvin = 280 * (config.meanHeightMeters / depth(x)).let { it * it },
                relativeHumidity = 0.0, surfaceWaterKgM2 = 0.0) })
        val initial = model.snapshot()
        repeat(100) { model.step() }
        val final = model.snapshot()
        assertTrue(final.dynamics.diagnostics.maximumSpeedMetersPerSecond < 1e-8)
        for (x in 0 until config.width) {
            assertEquals(initial.dynamics.heightAt(x, 4), final.dynamics.heightAt(x, 4), 1e-8)
            assertEquals(initial.temperatureKelvinAt(x, 4), final.temperatureKelvinAt(x, 4), 1e-8)
        }
    }

    @Test
    fun limitedTracersRetainAFrontAndConservePositiveWaterDuringOneCircuit() {
        val config = DryAtmosphereConfig(width = 40, height = 12, domainWidthMeters = 1e6,
            domainHeightMeters = 300000.0, gravity = 0.01, stepSeconds = 100.0,
            geometry = AtmosphereGeometry.PERIODIC_F_PLANE, coriolisPerSecond = 0.0)
        val columns = MoistColumnTransport(config, MoistPhysicsConfig())
        val flow = DryAtmosphere(config, columns) { _, _ -> DryCell(config.meanHeightMeters, 20.0, 0.0) }
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val i = y * config.width + x
            columns.values[0][i] = config.meanHeightMeters * 280
            columns.values[1][i] = config.meanHeightMeters * 0.005 * (1 + 0.8 * sin(2 * PI * (x + 0.5) / config.width))
            columns.values[2][i] = if (x in 12..20) config.meanHeightMeters * 0.002 else 0.0
        }
        val vapor = columns.values[1].sum(); val cloud = columns.values[2].sum()
        fun amplitude() = (0 until config.width).sumOf { x ->
            columns.values[1][x] / config.meanHeightMeters * sin(2 * PI * (x + 0.5) / config.width)
        } * 2 / config.width
        val before = amplitude()
        repeat(500) { flow.step() }
        val ratio = amplitude() / before
        println("TRACER_TRANSPORT one_circuit_amplitude_retention=$ratio first_order_spatial_approx=0.61")
        assertTrue(ratio > 0.85, "A smooth moisture front should survive transport around the periodic domain")
        assertEquals(vapor, columns.values[1].sum(), vapor * 1e-12)
        assertEquals(cloud, columns.values[2].sum(), cloud * 1e-12)
        assertTrue(columns.values[1].all { it >= 0 && it.isFinite() })
        assertTrue(columns.values[2].all { it >= 0 && it.isFinite() })
        assertTrue(abs(flow.snapshot().diagnostics.volumeCubicMeters / (config.meanHeightMeters * config.cellArea * config.cellCount) - 1) < 1e-12)
    }
}
