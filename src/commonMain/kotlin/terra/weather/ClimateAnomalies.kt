package terra.weather

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Bounded travelling regional modes on the climate-month clock, with analytic interval means. */
internal class ClimateAnomalies(seed: Int, yearSeconds: Double) {
    private val monthSeconds = yearSeconds / 12.0
    private val phases = Random(seed xor 0x61c74a).let { random -> DoubleArray(6) { random.nextDouble() * 2 * PI } }
    init { require(yearSeconds.isFinite() && yearSeconds > 0.0) }
    fun wet(longitude: Double, northward: Double, seconds: Double) = field(0, longitude, northward, seconds, 0.0)
    fun warm(longitude: Double, northward: Double, seconds: Double) = field(3, longitude, northward, seconds, 0.0)
    fun meanWet(longitude: Double, northward: Double, start: Double, seconds: Double) = field(0, longitude, northward, start, seconds)
    fun meanWarm(longitude: Double, northward: Double, start: Double, seconds: Double) = field(3, longitude, northward, start, seconds)
    private fun field(offset: Int, longitude: Double, northward: Double, start: Double, duration: Double): Double {
        require(longitude.isFinite() && northward.isFinite() && northward in 0.0..1.0)
        require(start.isFinite() && start >= 0 && duration.isFinite() && duration >= 0 && (start + duration).isFinite())
        val x = ((longitude % 1.0) + 1.0) % 1.0
        val latitude = (northward - 0.5) * PI
        val middle = (start + duration / 2) / monthSeconds
        val span = duration / monthSeconds
        fun mode(wave: Int, period: Double, direction: Double, latitudeWave: Double, phase: Double, cosine: Boolean): Double {
            val angle = 2 * PI * (wave * x + direction * ((middle % period) / period)) + latitudeWave * latitude + phase
            val halfSpan = PI * span / period
            val attenuation = if (halfSpan == 0.0) 1.0 else sin(halfSpan) / halfSpan
            return (if (cosine) cos(angle) else sin(angle)) * attenuation
        }
        return cos(latitude) * (0.5 * mode(1, 17.0, 1.0, 1.3, phases[offset], false) +
            0.3 * mode(2, 29.0, -1.0, -2.1, phases[offset + 1], false) +
            0.2 * mode(3, 43.0, 1.0, 0.7, phases[offset + 2], true))
    }
}
