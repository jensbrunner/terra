package terra.atmosphere

/**
 * Small, precomputed saturation mixing-ratio table. Climate work evaluates saturation
 * hundreds of thousands of times per simulated year; a linear table removes that exp
 * cost while remaining within a fraction of a percent of the warm-cloud formula.
 * Pressure is handled with the ideal-gas first-order scaling used by the bulk closures.
 */
internal object FastSaturation {
    private const val MIN_TEMPERATURE = 170.0
    private const val MAX_TEMPERATURE = 350.0
    private const val STEP = 0.5
    private const val COUNT = ((MAX_TEMPERATURE - MIN_TEMPERATURE) / STEP).toInt() + 1
    private val table = DoubleArray(COUNT) { WarmCloud.saturationMixingRatio(MIN_TEMPERATURE + it * STEP, 100_000.0) }

    fun mixingRatio(temperature: Double, pressure: Double): Double {
        require(pressure > 1000.0)
        val position = ((temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE) - MIN_TEMPERATURE) / STEP)
        val index = position.toInt().coerceIn(0, COUNT - 2)
        val fraction = position - index
        val atStandardPressure = table[index] * (1 - fraction) + table[index + 1] * fraction
        return atStandardPressure * 100_000.0 / pressure
    }
}
