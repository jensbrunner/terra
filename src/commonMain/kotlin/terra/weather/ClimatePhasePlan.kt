package terra.weather

import kotlin.math.floor
import kotlin.math.min

internal data class ClimatePhase(
    val seconds: Double, val season: Int?, val wetSlot: Int,
    val forcingStart: Double, val forcingSeconds: Double
)

/** At most ten chronological phases, or eight grouped-season phases for multi-year intervals. */
internal class ClimatePhasePlan(start: Double, val seconds: Double, val year: Double, initialFraction: Double) {
    val aggregate = seconds > year
    val phases: List<ClimatePhase>
    init {
        val quarter = year / 4
        fun phaseAt(time: Double) = ((time / year + initialFraction) % 1.0) * 4
        if (seconds <= min(7 * 86400.0, year / 4)) {
            phases = listOf(ClimatePhase(seconds / 2, null, 0, start, seconds),
                ClimatePhase(seconds / 2, null, 1, start, seconds))
        } else if (!aggregate) {
            val result = mutableListOf<ClimatePhase>()
            var elapsed = 0.0
            while (elapsed < seconds) {
                val phase = phaseAt(start + elapsed)
                val season = floor(phase).toInt().coerceIn(0, 3)
                val shifted = start + elapsed + initialFraction * year
                val toBoundary = (floor(shifted / quarter) + 1) * quarter - shifted
                val span = min(seconds - elapsed, if (toBoundary > 1e-6) toBoundary else quarter)
                result += ClimatePhase(span / 2, season, 0, start + elapsed, span)
                result += ClimatePhase(span / 2, season, 1, start + elapsed, span)
                elapsed += span
            }
            check(result.size <= 12)
            phases = result
        } else {
            // Periodic exposure measure avoids visiting the intervening years.
            fun exposure(time: Double, season: Int): Double {
                val shifted = time + initialFraction * year
                val years = floor(shifted / year)
                val remainder = shifted - years * year
                return years * quarter + (remainder - season * quarter).coerceIn(0.0, quarter)
            }
            val first = (floor(phaseAt(start + seconds)).toInt().coerceIn(0, 3) + 1) % 4
            phases = (0..3).flatMap { offset ->
                val season = (first + offset) % 4
                val span = exposure(start + seconds, season) - exposure(start, season)
                listOf(ClimatePhase(span / 2, season, 0, start, seconds),
                    ClimatePhase(span / 2, season, 1, start, seconds))
            }
        }
    }
}
