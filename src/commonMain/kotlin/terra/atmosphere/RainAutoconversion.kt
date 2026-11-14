package terra.atmosphere

/** Hysteresis for detailed cloud conversion. */
internal object RainAutoconversion {
    const val CESSATION_EXCESS_FRACTION = 0.1

    fun cessationMm(physics: MoistPhysicsConfig): Double = physics.rainCloudThresholdKgM2 +
        CESSATION_EXCESS_FRACTION * (physics.rainOnsetKgM2 - physics.rainCloudThresholdKgM2)

}
