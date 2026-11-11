package terra.weather

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Shared seed geometry for initial lows/fronts and the renderer's initial filaments.
 * Coordinates are east/north fractions, periodic in longitude. No clock or water store.
 */
internal class SynopticPattern(seed: Int, private val aspectRatio: Double) {
    private val phase = (seed.toUInt().toLong() % 10007) / 10007.0 * 2 * PI
    fun centerX(hemisphere: Int, system: Int) =
        0.18 + system * 0.5 + hemisphere * 0.11 + phase / (20 * PI)
    fun centerNorth(hemisphere: Int) = 0.25 + hemisphere * 0.5

    fun cloudBands(east: Double, north: Double, coreRadiusSquared: Double = 0.0028): Double {
        var fronts = 0.0
        for (hemisphere in 0..1) for (system in 0..1) {
            val cx = centerX(hemisphere, system)
            val dx = east - cx - floor(east - cx + 0.5)
            val dy = (north - centerNorth(hemisphere)) * aspectRatio
            val r2 = dx * dx + dy * dy
            val angle = atan2(dy, dx)
            val orientation = if (hemisphere == 0) -1 else 1
            val arm = exp(-(1 - cos(angle * orientation - sqrt(r2) * 26 + phase + system)) / 0.25)
            val head = exp(-r2 / coreRadiusSquared)
            val frontal = arm * exp(-r2 / 0.025)
            fronts = max(fronts, 1 - (1 - head) * (1 - frontal))
        }
        return fronts
    }
}
