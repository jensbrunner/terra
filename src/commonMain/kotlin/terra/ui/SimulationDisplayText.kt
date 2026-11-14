package terra.ui

import kotlin.math.floor

/** Units and source labels for completed data. Never interpolates the committed calendar. */
internal object SimulationDisplayText {
    fun duration(seconds: Double, year: Double): String {
        val (value, unit) = when {
            seconds >= year -> seconds / year to "yr"
            seconds >= 86400 -> seconds / 86400 to "d"
            seconds >= 3600 -> seconds / 3600 to "h"
            seconds >= 60 -> seconds / 60 to "min"
            else -> seconds to "s"
        }
        return "${formatNumber(value, if (value == floor(value)) 0 else 2)} $unit"
    }
    fun rate(secondsPerSecond: Double, year: Double) = "${duration(secondsPerSecond, year)}/s"
    fun date(seconds: Double, year: Double): String {
        val hour = floor((seconds % 86400) / 3600).toInt().toString().padStart(2, '0')
        val minute = floor((seconds % 3600) / 60).toInt().toString().padStart(2, '0')
        return "year ${floor(seconds / year).toLong() + 1}, day ${floor((seconds % year) / 86400).toLong() + 1} $hour:$minute"
    }
}
