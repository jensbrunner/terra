package terra.atmosphere

import kotlin.math.max
import kotlin.math.min

/** Equilibrium cloud partition used by detailed microphysics. */
internal object ColumnThermodynamics {
    /** Equilibrium partition of subgrid saturated parcels in a moist mean column. */
    fun cloudMm(totalWaterMm: Double, saturationMm: Double): Double {
        val humidity = totalWaterMm / max(1e-12, saturationMm)
        val subgrid = min(totalWaterMm, 0.20 * ((humidity - 0.60) / 0.40).coerceIn(0.0, 1.0))
        return totalWaterMm - min(max(0.0, totalWaterMm - subgrid), saturationMm)
    }
}
