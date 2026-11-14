package terra.hydrology

import kotlin.math.*

/** Allocation-free root-zone balance driven by atlas rainfall and PET.
 * Results are overwritten on each advance; callers own the persistent inventories.
 * PET is netted against infiltrated water and existing soil within the interval.
 * Remaining input drains analytically toward field capacity, spilling at saturation.
 */
internal class SoilWaterBalance {
    var soilMm = 0.0
        private set
    var drainageMm = 0.0
        private set
    var runoffMm = 0.0
        private set
    var evaporationMm = 0.0
        private set

    fun advance(initialSoilMm: Double, capacityMm: Double, precipitationMm: Double, seconds: Double,
        infiltrationScale: Double, wetSeconds: Double = seconds, potentialEvaporationMm: Double = 0.0,
        rootSupplyMm: Double = 0.0, drainageDecay: Double = exp(-seconds / DRAINAGE_SECONDS)) {
        val infiltrated = infiltratedMm(precipitationMm, wetSeconds.coerceIn(0.0, seconds), infiltrationScale)
        runoffMm = precipitationMm - infiltrated
        drainageMm = 0.0
        evaporationMm = min(initialSoilMm + infiltrated + rootSupplyMm, potentialEvaporationMm)
        val net = infiltrated + rootSupplyMm - evaporationMm
        soilMm = max(0.0, initialSoilMm + min(0.0, net))
        val inputMm = max(0.0, net)
        val fieldCapacityMm = capacityMm * FIELD_CAPACITY_FRACTION
        if (seconds <= 0.0 || (inputMm == 0.0 && soilMm <= fieldCapacityMm)) return
        var store = soilMm
        var rate = inputMm / seconds
        var remainingSeconds = seconds
        if (store < fieldCapacityMm) {
            if (rate <= 0.0) return
            val timeToField = (fieldCapacityMm - store) / rate
            if (timeToField >= remainingSeconds) {
                soilMm = store + inputMm
                return
            }
            val used = rate * timeToField
            store = fieldCapacityMm
            remainingSeconds -= timeToField
            rate = (inputMm - used) / remainingSeconds
        }
        val equilibrium = rate * DRAINAGE_SECONDS
        val excess = max(0.0, store - fieldCapacityMm)
        val capExcess = capacityMm - fieldCapacityMm
        val input = rate * remainingSeconds
        if (equilibrium > capExcess) {
            val ratio = (capExcess - equilibrium) / (excess - equilibrium)
            if (ratio > 0.0 && ratio <= 1.0) {
                val timeToSaturation = -DRAINAGE_SECONDS * ln(ratio)
                if (timeToSaturation < remainingSeconds) {
                    val before = equilibrium + (excess - equilibrium) * exp(-timeToSaturation / DRAINAGE_SECONDS)
                    val storedBefore = fieldCapacityMm + min(capExcess, before)
                    val drainageBefore = store + rate * timeToSaturation - storedBefore
                    val afterSeconds = remainingSeconds - timeToSaturation
                    runoffMm += max(0.0, rate - capExcess / DRAINAGE_SECONDS) * afterSeconds
                    soilMm = capacityMm
                    drainageMm = max(0.0, drainageBefore) + capExcess / DRAINAGE_SECONDS * afterSeconds
                    return
                }
            }
        }
        val endExcess = equilibrium + (excess - equilibrium) *
            (if (remainingSeconds == seconds) drainageDecay else exp(-remainingSeconds / DRAINAGE_SECONDS))
        soilMm = fieldCapacityMm + min(capExcess, max(0.0, endExcess))
        val spill = max(0.0, endExcess - capExcess)
        runoffMm += spill
        drainageMm = max(0.0, store + input - soilMm - spill)
    }

    companion object {
        const val DRAINAGE_SECONDS = 5 * 86400.0
        const val FIELD_CAPACITY_FRACTION = 0.48
        private const val INFILTRATION_MM_PER_HOUR = 10.0

        fun infiltratedMm(precipitationMm: Double, wetSeconds: Double, infiltrationScale: Double) =
            min(precipitationMm, INFILTRATION_MM_PER_HOUR * wetSeconds / 3600.0 * infiltrationScale)
    }
}
