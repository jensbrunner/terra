package terra.atmosphere

import kotlin.math.abs

/** Three mass-weighted scalars: h*T, h*q_v, h*q_c. Uses the dynamics' actual face mass flux. */
internal class MoistColumnTransport(count: Int, val physics: MoistPhysicsConfig) {
    var values = Array(3) { DoubleArray(count) }
        private set
    val base = Array(3) { DoubleArray(count) }
    val stage = Array(3) { DoubleArray(count) }
    private var next = Array(3) { DoubleArray(count) }
    private val rhs = Array(3) { DoubleArray(count) }
    private val outgoing = DoubleArray(count)

    fun temperatureAt(i: Int, depth: Double): Double = values[0][i] / depth

    fun beginStep() {
        validate(values)
        for (field in 0..2) values[field].copyInto(base[field])
    }

    fun prepareFluxes(source: Array<DoubleArray>) {
        validate(source)
        for (field in rhs) field.fill(0.0)
        outgoing.fill(0.0)
    }

    fun faceFlux(left: Int, right: Int, massRate: Double, depth: DoubleArray, source: Array<DoubleArray>) {
        val donor = if (massRate >= 0) left else right
        outgoing[donor] += abs(massRate)
        for (field in 0..2) {
            val flux = massRate * (source[field][donor] / depth[donor])
            rhs[field][left] -= flux
            rhs[field][right] += flux
        }
    }

    fun checkOutflow(depth: DoubleArray, seconds: Double) {
        for (i in depth.indices) check(seconds * outgoing[i] <= depth[i]) {
            "Moist tracer outflow would exhaust carrier mass at cell $i; reduce the fixed timestep"
        }
    }

    fun makeStage(seconds: Double) {
        for (field in 0..2) for (i in outgoing.indices) stage[field][i] = base[field][i] + seconds * rhs[field][i]
        validate(stage)
    }

    fun finishStep(seconds: Double) {
        for (field in 0..2) for (i in outgoing.indices) {
            next[field][i] = 0.5 * (base[field][i] + stage[field][i] + seconds * rhs[field][i])
        }
        validate(next)
        val old = values; values = next; next = old
    }

    private fun validate(source: Array<DoubleArray>) {
        for (i in outgoing.indices) check(source[0][i].isFinite() && source[0][i] > 0 &&
            source[1][i].isFinite() && source[1][i] >= 0 && source[2][i].isFinite() && source[2][i] >= 0) {
            "Invalid temperature/moisture at cell $i; no tracer clamp was applied"
        }
    }
}
