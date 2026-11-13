package terra.atmosphere

import kotlin.math.ln

/** One closure for detailed cloud conversion and reduced wet-time/infiltration diagnostics. */
internal object RainAutoconversion {
    const val CESSATION_EXCESS_FRACTION = 0.1

    fun cessationMm(physics: MoistPhysicsConfig): Double = physics.rainCloudThresholdKgM2 +
        CESSATION_EXCESS_FRACTION * (physics.rainOnsetKgM2 - physics.rainCloudThresholdKgM2)

    /** Mean rate of an onset-sized cloud exhausting its excess exponentially to cessation. */
    fun eventMeanMmPerHour(physics: MoistPhysicsConfig): Double =
        (physics.rainOnsetKgM2 - physics.rainCloudThresholdKgM2) * (1 - CESSATION_EXCESS_FRACTION) *
            3600 / (physics.rainoutSeconds * ln(1 / CESSATION_EXCESS_FRACTION))
}
