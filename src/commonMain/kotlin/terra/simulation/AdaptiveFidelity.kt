package terra.simulation

import kotlin.math.max

internal data class FidelityDecision(val mode: SimulationFidelity, val quantumSeconds: Double)

/** Owner-thread cost estimates and hysteresis. Camera/frame timing never chooses model fidelity. */
internal class AdaptiveFidelity(private val detailedStep: Double, private val year: Double = 365 * 86400.0) {
    private var mode = SimulationFidelity.DETAILED
    private var switchedAt = Double.NEGATIVE_INFINITY
    private var overloadedAt: Double? = null
    private var underloadedAt: Double? = null
    private var detailedMean = 0.0
    private var detailedSamples = 0
    private var measuredSince = Double.NaN

    fun observe(mode: SimulationFidelity, wallCost: Double, now: Double) {
        require(wallCost.isFinite() && wallCost >= 0)
        if (mode == SimulationFidelity.DETAILED) {
            if (detailedSamples == 0) measuredSince = now
            detailedMean = if (detailedSamples++ == 0) wallCost else detailedMean * 0.9 + wallCost * 0.1
        }
    }

    fun decide(speed: SimulationSpeed, manual: SimulationFidelity?, now: Double, explicitSpeedChange: Boolean = false): FidelityDecision {
        val measured = detailedSamples >= 8 && now - measuredSince >= 0.25
        val safeRate = if (measured) detailedStep / max(1e-6, detailedMean) * 0.6 else 3600.0
        val rate = speed.simulatedSecondsPerRealSecond
        fun select(next: SimulationFidelity) {
            if (next != mode) {
                mode = next; switchedAt = now; overloadedAt = null; underloadedAt = null
                if (next == SimulationFidelity.DETAILED) {
                    // Old startup/decades-old costs must not veto trying the evolved detailed atmosphere.
                    detailedSamples = 0; detailedMean = 0.0; measuredSince = Double.NaN
                }
            }
        }
        if (manual != null) select(manual)
        else if (explicitSpeedChange && rate <= 3600) select(SimulationFidelity.DETAILED)
        else {
            val expensive = rate > 3600 || measured && rate > safeRate * 0.9
            val cheap = rate <= 3600 && rate < safeRate * 0.65
            if (expensive) {
                underloadedAt = null
                if (overloadedAt == null) overloadedAt = now
                if (mode == SimulationFidelity.DETAILED && now - overloadedAt!! >= 0.25 && now - switchedAt >= 0.5) select(SimulationFidelity.CLIMATE)
            } else overloadedAt = null
            if (cheap) {
                if (underloadedAt == null) underloadedAt = now
                if (mode == SimulationFidelity.CLIMATE && now - underloadedAt!! >= 1.5 && now - switchedAt >= 1.0) select(SimulationFidelity.DETAILED)
            } else underloadedAt = null
        }
        // Climate cost scales with represented time: growing a batch in response
        // to its total cost creates positive feedback, not more throughput. Keep
        // calendar-sized transactions and expose any real backlog to the user.
        return FidelityDecision(mode, if (mode == SimulationFidelity.DETAILED) detailedStep else baseQuantum(speed, year))
    }

    companion object {
        fun baseQuantum(speed: SimulationSpeed, year: Double = 365 * 86400.0): Double = when (speed) {
            SimulationSpeed.REALTIME, SimulationSpeed.ONE_HOUR -> 3600.0
            SimulationSpeed.TWELVE_HOURS -> 3 * 3600.0
            SimulationSpeed.ONE_DAY -> 6 * 3600.0
            SimulationSpeed.ONE_WEEK -> 2 * 86400.0
            SimulationSpeed.ONE_MONTH -> 7 * 86400.0
            SimulationSpeed.ONE_YEAR -> year / 12
            SimulationSpeed.TEN_YEARS -> year
        }
    }
}
