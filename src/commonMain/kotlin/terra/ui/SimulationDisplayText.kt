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
    private val months = listOf("January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December")
    private val monthDays = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    fun date(seconds: Double, year: Double): String {
        var day = floor((seconds % year) / year * 365 + 1e-9).toInt().coerceIn(0, 364)
        var month = 0
        while (day >= monthDays[month]) { day -= monthDays[month]; month++ }
        return "${(day + 1).toString().padStart(2, '0')} ${months[month]} · Year ${floor(seconds / year).toLong() + 1}"
    }

    fun clock(seconds: Double): String {
        val hour = floor((seconds % 86400) / 3600).toInt().toString().padStart(2, '0')
        val minute = floor((seconds % 3600) / 60).toInt().toString().padStart(2, '0')
        return "$hour:$minute"
    }
}
