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
    private var gridWidth = 0
    private var gridHeight = 0
    private var spatialSin = emptyArray<DoubleArray>()
    private var spatialCos = emptyArray<DoubleArray>()

    /** Separate stationary spatial waves from their six monthly temporal coefficients. */
    fun fillGrid(wet: DoubleArray, warm: DoubleArray, width: Int, height: Int,
        start: Double, duration: Double = 0.0) {
        if (gridWidth != width || gridHeight != height) {
            gridWidth = width; gridHeight = height
            spatialSin = Array(6) { DoubleArray(width * height) }
            spatialCos = Array(6) { DoubleArray(width * height) }
            val latitudeWaves = doubleArrayOf(1.3, -2.1, 0.7)
            val weights = doubleArrayOf(0.5, 0.3, 0.2)
            for (i in wet.indices) {
                val x = (i % width + 0.5) / width
                val latitude = ((i / width + 0.5) / height - 0.5) * PI
                for (mode in 0..5) {
                    val wave = mode % 3
                    val angle = 2 * PI * (wave + 1) * x + latitudeWaves[wave] * latitude + phases[mode]
                    val amplitude = cos(latitude) * weights[wave]
                    spatialSin[mode][i] = amplitude * sin(angle)
                    spatialCos[mode][i] = amplitude * cos(angle)
                }
            }
        }
        val periods = doubleArrayOf(17.0, 29.0, 43.0)
        val sinTime = DoubleArray(3)
        val cosTime = DoubleArray(3)
        for (wave in 0..2) {
            val halfSpan = PI * duration / monthSeconds / periods[wave]
            val attenuation = if (halfSpan == 0.0) 1.0 else sin(halfSpan) / halfSpan
            val angle = 2 * PI * (((start + duration / 2) / monthSeconds % periods[wave]) / periods[wave]) *
                (if (wave == 1) -1 else 1)
            sinTime[wave] = sin(angle) * attenuation
            cosTime[wave] = cos(angle) * attenuation
        }
        for (i in wet.indices) {
            wet[i] = 0.0; warm[i] = 0.0
            for (mode in 0..5) {
                val wave = mode % 3
                val value = if (wave == 2) spatialCos[mode][i] * cosTime[wave] - spatialSin[mode][i] * sinTime[wave]
                    else spatialSin[mode][i] * cosTime[wave] + spatialCos[mode][i] * sinTime[wave]
                if (mode < 3) wet[i] += value else warm[i] += value
            }
        }
    }

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
