package terra.weather

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bounded, spatially coherent weather regimes on the reduced mesh.
 *
 * This is a diagnostic estimate with uncertainty, not a second rainfall reservoir.
 * It proposes wet/dry persistence and intensity; the coupled water budget still
 * decides how much water is available. Every coefficient is indexed by absolute
 * physical time, so two worker requests that cover the same interval visit the same
 * sequence of regimes regardless of transaction size.
 */
internal class WeatherRegimes(private val grid: ClimateGrid, seed: Int) {
    private val count = grid.count
    private val width = grid.config.width
    private val height = grid.config.height
    private val phases = DoubleArray(9) { Random(seed xor (0x51ed + it * 7919)).nextDouble() * 2 * PI }

    // Periods in climate months. A drought is a persistent anomaly, not a single event.
    private val periods = doubleArrayOf(1.7, 3.1, 5.9)
    private val weights = doubleArrayOf(0.5, 0.32, 0.18)

    fun wetAnomaly(cell: Int, midSeconds: Double, yearSeconds: Double): Double {
        val months = midSeconds / (yearSeconds / 12.0)
        val x = (cell % width + 0.5) / width
        val latitude = ((cell / width + 0.5) / height - 0.5) * PI
        var value = 0.0
        for (mode in 0..2) {
            val angle = 2 * PI * ((mode + 2) * x + (0.4 + 0.3 * mode) * latitude) +
                2 * PI * (months / periods[mode]) + phases[mode]
            value += weights[mode] * sin(angle) * cos(latitude).coerceAtLeast(0.15)
        }
        return value.coerceIn(-1.0, 1.0)
    }

    fun temperatureAnomaly(cell: Int, midSeconds: Double, yearSeconds: Double): Double {
        val months = midSeconds / (yearSeconds / 12.0)
        val x = (cell % width + 0.5) / width
        val latitude = ((cell / width + 0.5) / height - 0.5) * PI
        var value = 0.0
        for (mode in 0..2) {
            val angle = 2 * PI * ((mode + 1) * x - (0.35 + 0.25 * mode) * latitude) +
                2 * PI * (months / (periods[mode] * 1.6)) + phases[mode + 3]
            value += weights[mode] * cos(angle) * cos(latitude).coerceAtLeast(0.15)
        }
        return value.coerceIn(-1.0, 1.0)
    }

    fun wetFraction(cell: Int, midSeconds: Double, yearSeconds: Double): Double =
        (0.26 + 0.20 * wetAnomaly(cell, midSeconds, yearSeconds)).coerceIn(0.08, 0.62)

    /** Multiplier on convective lift; the total water budget still limits the result. */
    fun storminess(cell: Int, midSeconds: Double, yearSeconds: Double): Double =
        (1 + 0.32 * wetAnomaly(cell, midSeconds, yearSeconds)).coerceIn(0.55, 1.45)
}
