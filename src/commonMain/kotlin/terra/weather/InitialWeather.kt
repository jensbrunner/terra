package terra.weather

import kotlin.math.floor
import terra.atmosphere.SynopticPattern
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.DryCell
import terra.atmosphere.DryScenarios
import terra.atmosphere.DryScenario

/** Seeded, longitude-periodic moist/dry air masses and circulation at world creation.
 * No spin-up, time advancement or continuing humidity forcing is hidden here.
 */
internal class InitialWeather(private val seed: Int, private val config: DryAtmosphereConfig) {
    private val pattern = SynopticPattern(seed)
    private val jet = DryScenarios.initializer(DryScenario.JET, config, seed)

    /** Approximate geostrophic lows in both hemispheres, over a weak balanced jet.
     * The solver evolves this initial condition; it is never reapplied as forcing.
     */
    fun flow(x: Double, y: Double): DryCell {
        val base = jet(x, y)
        val vortex = pattern.flow(config, x, y)
        return DryCell(vortex.heightMeters + (base.heightMeters - config.meanHeightMeters) * 0.18,
            vortex.eastMetersPerSecond + base.eastMetersPerSecond * 0.18,
            vortex.northMetersPerSecond + base.northMetersPerSecond * 0.18)
    }
    // Independent of the low positions: any longitude/latitude can start moist.
    fun moistFraction(east: Double, north: Double, waterAvailability: Double): Double {
        require(waterAvailability.isFinite() && waterAvailability in 0.0..1.0)
        val background = 0.7 * noise(east, north, 4, 5, seed xor 0x36e91) +
            0.3 * noise(east, north, 9, 9, seed xor 0x51a3)
        return smoothstep(0.25, 0.65, 0.8 * background + 0.2 * waterAvailability)
    }

    fun relativeHumidity(moisture: Double) = 0.55 + 0.45 * moisture
    fun cloudWaterMm(moisture: Double) = 0.25 * smoothstep(0.98, 1.0, moisture)

    private fun smoothstep(low: Double, high: Double, value: Double): Double {
        val t = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun noise(x: Double, y: Double, columns: Int, rows: Int, salt: Int): Double {
        val px = x * columns; val py = y * rows
        val ix = floor(px).toInt(); val iy = floor(py).toInt()
        fun smooth(t: Double) = t * t * (3 - 2 * t)
        val fx = smooth(px - ix); val fy = smooth(py - iy)
        fun lattice(cx: Int, cy: Int): Double {
            var hash = ((cx % columns + columns) % columns) * 374761393 + cy * 668265263 + salt
            hash = (hash xor (hash ushr 13)) * 1274126177
            return (hash xor (hash ushr 16)).toUInt().toLong() / 4294967295.0
        }
        return (lattice(ix, iy) * (1 - fx) + lattice(ix + 1, iy) * fx) * (1 - fy) +
            (lattice(ix, iy + 1) * (1 - fx) + lattice(ix + 1, iy + 1) * fx) * fy
    }
}
