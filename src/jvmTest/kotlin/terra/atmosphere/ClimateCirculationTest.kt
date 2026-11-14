package terra.atmosphere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClimateCirculationTest {
    @Test
    fun reconstructionIsDeterministicSeasonSensitiveAndDoesNotInventMotionInUniformAir() {
        val config = DryAtmosphereConfig(width = 24, height = 18)
        val volume = config.meanHeightMeters * config.cellCount
        fun temperatures(season: Double) = DoubleArray(config.cellCount) { i ->
            val fraction = (i / config.width + 0.5) / config.height
            255 + 35 * kotlin.math.sin(PI * fraction) + season * (fraction - 0.5) +
                3 * cos(2 * PI * (i % config.width + 0.5) / config.width)
        }
        val a = ClimateCirculation.reconstruct(config, 280.0, temperatures(8.0), volume)
        val b = ClimateCirculation.reconstruct(config, 280.0, temperatures(8.0), volume)
        val winter = ClimateCirculation.reconstruct(config, 280.0, temperatures(-8.0), volume)
        assertTrue(a.depth.contentEquals(b.depth) && a.east.contentEquals(b.east) && a.north.contentEquals(b.north))
        assertTrue(!a.east.contentEquals(winter.east))
        assertEquals(volume, a.depth.sum(), volume * 1e-12)
        assertTrue(a.maximumSpeed <= 35 && a.rmsSpeed > 1)
        // Equator-crossing and polar fields remain finite; both midlatitude thermal jets are eastward.
        assertTrue(a.east.all { it.isFinite() } && a.north.all { it.isFinite() })
        assertTrue(a.east[4 * config.width + 10] > 0 && a.east[13 * config.width + 10] > 0)
        // Thermal background remains in the solver's geostrophic force balance.
        val thermal = temperatures(8.0)
        for (y in 1 until config.height - 1) for (x in 0 until config.width) {
            val f = config.coriolisAtRow(y)
            if (abs(f) < 5e-5) continue
            val i = y * config.width + x
            fun pressure(row: Int): Double {
                val j = row * config.width + x
                return config.gravity * a.depth[j] * a.depth[j] * thermal[j] / (2 * 280.0)
            }
            val force = (pressure(y + 1) - pressure(y - 1)) / (2 * config.dy * a.depth[i])
            assertTrue(abs(force + f * a.east[i]) <= abs(force) * 0.15 + 1e-12)
        }
        val rest = ClimateCirculation.reconstruct(config, 280.0, DoubleArray(config.cellCount) { 280.0 }, volume)
        assertTrue(rest.east.all { abs(it) < 1e-12 } && rest.north.all { abs(it) < 1e-12 })
    }
}
