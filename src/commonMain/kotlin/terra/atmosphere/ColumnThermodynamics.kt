package terra.atmosphere

import kotlin.math.max
import kotlin.math.min

/** Effective humid-column pressure is distinct from surface pressure in the bulk evaporation law. */
internal object ColumnThermodynamics {
    const val LAPSE_K_PER_METER = 0.0065

    fun cloudPressure(dryMassKgM2: Double, gravity: Double, physics: MoistPhysicsConfig): Double =
        dryMassKgM2 * gravity * physics.cloudPressureFraction

    fun saturationMm(temperatureK: Double, dryMassKgM2: Double, gravity: Double,
        physics: MoistPhysicsConfig): Double = dryMassKgM2 * physics.humidColumnFraction *
        FastSaturation.mixingRatio(temperatureK, cloudPressure(dryMassKgM2, gravity, physics))

    fun airAtElevation(temperatureK: Double, fineElevation: Double, coarseElevation: Double): Double =
        (temperatureK - LAPSE_K_PER_METER * (fineElevation - coarseElevation)).coerceIn(180.0, 340.0)

    /** Subgrid saturated parcels in a moist mean column; diagnostic only until handoff partitions water. */
    fun cloudMm(totalWaterMm: Double, saturationMm: Double): Double {
        val humidity = totalWaterMm / max(1e-12, saturationMm)
        val subgrid = min(totalWaterMm, 0.08 * ((humidity - 0.75) / 0.25).coerceIn(0.0, 1.0))
        return totalWaterMm - min(max(0.0, totalWaterMm - subgrid), saturationMm)
    }
}
