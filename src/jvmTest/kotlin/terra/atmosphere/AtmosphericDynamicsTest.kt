package terra.atmosphere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtmosphericDynamicsTest {
    @Test
    fun pressureUsesThermalFluxRelativeToEachLatitudeMeanAndRemainsFrozen() {
        val config = DryAtmosphereConfig(width = 12, height = 8)
        val model = MoistAtmosphere(config, initializeFlow = { _, y ->
            DryCell(config.meanHeightMeters * (0.9 + 0.2 * y / config.domainHeightMeters), 0.0, 0.0)
        }, initializeColumns = { x, _ -> MoistCell(temperatureKelvin = 280 + 5 * cos(2 * PI * x / config.domainWidthMeters)) })
        val view = model.snapshot()
        val fields = view.horizontalDynamics
        for (y in 0 until config.height) {
            var sum = 0.0
            for (x in 0 until config.width) {
                val expected = 100 * (view.temperatureKelvinAt(x, y) - 280) / 280
                assertEquals(expected, fields.pressureAnomalyPercentAt(x, y), 1e-12)
                sum += fields.pressureAnomalyPercentAt(x, y)
            }
            assertTrue(abs(sum) < 1e-11)
        }
        val frozen = fields.pressureAnomalyPercentAt(0, 3)
        model.step()
        assertEquals(frozen, fields.pressureAnomalyPercentAt(0, 3))
        assertTrue(fields === view.horizontalDynamics)
    }

    @Test
    fun relativeCurlHasPhysicalSignUnitsAndPeriodicLongitudeWithFreeSlipEdges() {
        val config = DryAtmosphereConfig(width = 16, height = 10)
        val kx = 2 * PI / config.domainWidthMeters
        val ky = PI / config.domainHeightMeters
        val model = DryAtmosphere(config) { x, y ->
            DryCell(config.meanHeightMeters, 12 * cos(ky * y), 8 * sin(kx * x))
        }
        val view = model.snapshot()
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val north = config.northRow(y); val south = config.southRow(y)
            val dvDx = 8 * cos(kx * (x + 0.5) * config.dx) * sin(kx * config.dx) / config.dx
            val duDy = 12 * (cos(ky * (north + 0.5) * config.dy) - cos(ky * (south + 0.5) * config.dy)) / (2 * config.dy)
            assertEquals(dvDx - duDy, view.vorticityAt(x, y), 1e-18)
        }
        assertTrue(view.vorticityAt(0, 4) > 0) // Counterclockwise is positive in the north-up physical grid.
    }
}
