package terra.atmosphere

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Conservative h*T/h*q transport, with limited mixing-ratio faces and donor-budget positivity. */
internal class MoistColumnTransport(private val config: DryAtmosphereConfig, val physics: MoistPhysicsConfig) {
    private val count = config.cellCount
    var values = Array(3) { DoubleArray(count) }
        private set
    val base = Array(3) { DoubleArray(count) }
    val stage = Array(3) { DoubleArray(count) }
    private var next = Array(3) { DoubleArray(count) }
    private val rhs = Array(3) { DoubleArray(count) }
    private val specific = Array(3) { DoubleArray(count) }
    private val slopeX = Array(3) { DoubleArray(count) }
    private val slopeY = Array(3) { DoubleArray(count) }
    private val outgoing = DoubleArray(count)
    private val fluxX = DoubleArray(count)
    private val fluxY = DoubleArray(count)

    fun temperatureAt(i: Int, depth: Double): Double = values[0][i] / depth
    fun faceTemperature(i: Int, alongX: Boolean, side: Double): Double = specific[0][i] +
        side * 0.5 * (if (alongX) slopeX[0][i] else slopeY[0][i])

    fun beginStep() {
        validate(values)
        for (field in 0..2) values[field].copyInto(base[field])
    }

    fun prepareFluxes(source: Array<DoubleArray>, depth: DoubleArray) {
        validate(source)
        for (field in 0..2) {
            rhs[field].fill(0.0)
            val q = specific[field]
            for (i in q.indices) q[i] = source[field][i] / depth[i]
            for (y in 0 until config.height) for (x in 0 until config.width) {
                val i = y * config.width + x
                val west = y * config.width + (x + config.width - 1) % config.width
                val east = y * config.width + (x + 1) % config.width
                slopeX[field][i] = limit(q[i] - q[west], q[east] - q[i])
                slopeY[field][i] = limit(q[i] - q[config.southRow(y) * config.width + x],
                    q[config.northRow(y) * config.width + x] - q[i])
            }
        }
        outgoing.fill(0.0)
    }

    fun faceFlux(left: Int, right: Int, massRate: Double, alongX: Boolean) {
        (if (alongX) fluxX else fluxY)[left] = massRate
        outgoing[if (massRate >= 0) left else right] += abs(massRate)
    }

    /** Limit outgoing face concentration before applying fluxes, rather than clipping negative stores. */
    fun completeFluxes(depth: DoubleArray, seconds: Double) {
        for (i in depth.indices) {
            val exported = seconds * outgoing[i]
            check(exported <= depth[i]) { "Moist carrier outflow exhausted cell $i; reduce timestep" }
            if (exported == 0.0) continue
            for (field in 0..2) {
                val q = specific[field][i]
                val excursion = 0.5 * max(abs(slopeX[field][i]), abs(slopeY[field][i]))
                if (excursion == 0.0) continue
                val headroom = q * (depth[i] / exported - 1)
                val retention = min(1.0, max(0.0, headroom / excursion))
                slopeX[field][i] *= retention; slopeY[field][i] *= retention
            }
        }
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val i = y * config.width + x
            applyFace(i, y * config.width + (x + 1) % config.width, fluxX[i], true)
            if (y + 1 < config.height || config.periodicY)
                applyFace(i, config.northRow(y) * config.width + x, fluxY[i], false)
        }
    }

    private fun applyFace(left: Int, right: Int, massRate: Double, alongX: Boolean) {
        val donor = if (massRate >= 0) left else right
        val side = if (massRate >= 0) 0.5 else -0.5
        val slope = if (alongX) slopeX else slopeY
        for (field in 0..2) {
            val concentration = specific[field][donor] + side * slope[field][donor]
            val flux = massRate * concentration
            rhs[field][left] -= flux; rhs[field][right] += flux
        }
    }

    fun makeStage(seconds: Double) {
        for (field in 0..2) for (i in outgoing.indices) stage[field][i] = base[field][i] + seconds * rhs[field][i]
        validate(stage)
    }

    fun finishStep(seconds: Double) {
        for (field in 0..2) for (i in outgoing.indices)
            next[field][i] = 0.5 * (base[field][i] + stage[field][i] + seconds * rhs[field][i])
        validate(next)
        val old = values; values = next; next = old
    }

    private fun limit(a: Double, b: Double): Double {
        if (a * b <= 0) return 0.0
        val magnitude = min(2 * min(abs(a), abs(b)), 0.5 * abs(a + b))
        return if (a > 0) magnitude else -magnitude
    }

    private fun validate(source: Array<DoubleArray>) {
        for (i in outgoing.indices) check(source[0][i].isFinite() && source[0][i] > 0 &&
            source[1][i].isFinite() && source[1][i] >= 0 && source[2][i].isFinite() && source[2][i] >= 0) {
            "Invalid temperature/moisture at cell $i; no tracer clamp was applied"
        }
    }
}
