package terra.weather

import kotlin.math.floor
import kotlin.math.min

internal data class ClimatePhase(
    val seconds: Double,
    val forcingStart: Double, val forcingSeconds: Double
)

/** Calendar-aligned ten-day water steps. Never reorder winters from different years.
 * A short final fragment is allowed; numerical cost scales with represented time.
 */
internal class ClimatePhasePlan(start: Double, val seconds: Double, val year: Double) {
    val phases: List<ClimatePhase>
    init {
        val quantum = min(10 * 86400.0, year / 36)
        val result = mutableListOf<ClimatePhase>()
        var elapsed = 0.0
        while (elapsed < seconds) {
            val time = start + elapsed
            val index = floor((time + 1e-7) / quantum)
            val boundary = min((index + 1) * quantum, (floor((time + 1e-7) / year) + 1) * year)
            val span = min(seconds - elapsed, boundary - time)
            check(span > 0.0)
            result += ClimatePhase(span, index * quantum, quantum)
            elapsed += span
        }
        phases = result
    }
}
