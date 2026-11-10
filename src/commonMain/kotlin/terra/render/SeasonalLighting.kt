package terra.render

import terra.atmosphere.PlanetarySunlight
import terra.atmosphere.SolarConfig
import terra.weather.WeatherFieldSemantics
import kotlin.math.floor

/** Immutable day-averaged seasonal illustration, never an alternative astronomical clock. */
internal class SeasonalLighting private constructor(private val opacity: DoubleArray) {
    fun opacityAtRow(y: Int) = opacity[y]
    companion object {
        fun capture(height: Int, period: WeatherFieldSemantics, solar: SolarConfig): SeasonalLighting {
            require(height > 0 && period.intervalStartSeconds >= 0 && period.intervalDurationSeconds > 0)
            val mean = DoubleArray(height)
            val sunlight = PlanetarySunlight(1, height, solar, northUp = false)
            fun addAverage(start: Double, duration: Double, weight: Double) {
                repeat(8) { sample ->
                    sunlight.update(start + duration * (sample + 0.5) / 8)
                    for (y in 0 until height) mean[y] += sunlight.dailyMeanInputFactor(y) * weight / 8
                }
            }
            val seconds = period.intervalDurationSeconds
            val complete = floor(seconds / solar.yearSeconds)
            val cycleDuration = complete * solar.yearSeconds
            if (complete > 0) addAverage(0.0, solar.yearSeconds, cycleDuration / seconds)
            val remainder = seconds - cycleDuration
            if (remainder > 0) addAverage(period.intervalStartSeconds, remainder, remainder / seconds)
            return SeasonalLighting(DoubleArray(height) { 0.38 * (1 - mean[it] / 0.36).coerceIn(0.0, 1.0) })
        }
    }
}
