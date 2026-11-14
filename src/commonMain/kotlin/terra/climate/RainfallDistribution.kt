package terra.climate

import kotlin.math.*

/** Traveling fronts and spiral bands with an analytic monthly integral. The seasonal
 * envelope vanishes at month boundaries; all nonconstant harmonics integrate to zero.
 * No storm history, numerical quadrature, or visual noise is needed for water budgets. */
class RainfallDistribution internal constructor(private val atlas: SeasonalClimateAtlas) {
    private val phases = Array(12) { month -> lazy {
        DoubleArray(atlas.width * atlas.height) { phase(month, it % atlas.width + 0.5, it / atlas.width + 0.5) }
    } }
    private val seed = atlas.geography.world.seed.toDouble()
    private val windTilt = DoubleArray(12) { m ->
        val u = atlas.east[m].average(); val v = atlas.south[m].average()
        atan2(v, abs(u) + 0.1)
    }
    private val travelDirection = DoubleArray(12) { m -> if (atlas.east[m].average() >= 0) -1.0 else 1.0 }

    private fun cycles(month: Int) = 4 + (month % 3)

    /** Coordinates are in terrain tiles, including fractional positions for rendering. */
    private fun phase(month: Int, x: Double, y: Double): Double {
        val longitude = x / atlas.width * 2 * PI
        val latitude = y / atlas.height
        val tilt = sin(seed * 0.73 + month * 1.7)
        // A periodic, warped front with a cyclone embedded in each hemisphere.
        val centerX = seed * 0.17 + month * 2.4
        val dx = atan2(sin(longitude - centerX), cos(longitude - centerX))
        val centerY = if (latitude < 0.5) 0.27 else 0.73
        val dy = (latitude - centerY) * 2 * PI
        val radius = hypot(dx, dy)
        val spiral = 2 * atan2(dy, dx) + radius * 5
        val cyclone = exp(-radius * radius * 1.8)
        val front = travelDirection[month] * 3 * longitude + latitude * (9 + tilt * 5 + windTilt[month] * 6) +
            1.8 * sin(longitude * 2 + latitude * 5 + month) + seed
        // Blend complex phases, avoiding a branch-cut seam at the cyclone's angle.
        return atan2((1 - cyclone) * sin(front) + cyclone * sin(spiral),
            (1 - cyclone) * cos(front) + cyclone * cos(spiral))
    }

    fun rateMmPerHour(x: Int, y: Int, seconds: Double): Double =
        rateMmPerHour(x + 0.5, y + 0.5, seconds)

    fun rateMmPerHour(x: Double, y: Double, seconds: Double): Double {
        val position = (seconds / atlas.solar.yearSeconds + atlas.solar.initialYearFraction) * 12
        val whole = floor(position)
        val month = ((whole.toLong() % 12 + 12) % 12).toInt()
        val fraction = position - whole
        val tileX = floor(x).toInt().mod(atlas.width)
        val tileY = floor(y).toInt().coerceIn(0, atlas.height - 1)
        val angle = 2 * PI * cycles(month) * fraction + phase(month, x, y)
        val pulse = 1 + cos(angle)
        val weight = (1 - cos(2 * PI * fraction)) * pulse * pulse * pulse * pulse / MEAN
        return atlas.rain[month][tileY * atlas.width + tileX] * weight * 12 * 3600 / atlas.solar.yearSeconds
    }

    /** The same atlas temperature partitions both the delivered water and its overlay. */
    fun snowFraction(x: Int, y: Int, seconds: Double): Double =
        snowFraction(atlas.monthAt(seconds), y * atlas.width + x)

    internal fun snowFraction(month: Int, tile: Int) = ((1 - atlas.temperature[month][tile]) / 2).coerceIn(0.0, 1.0)

    internal fun amountMm(month: Int, tile: Int, start: Double, end: Double): Double {
        if (start == 0.0 && end == 1.0) return atlas.rain[month][tile]
        val phase = phases[month].value[tile]
        return atlas.rain[month][tile] * (integral(month, phase, end) - integral(month, phase, start)).coerceAtLeast(0.0)
    }

    private fun integral(month: Int, phase: Double, fraction: Double): Double {
        if (fraction <= 0.0) return 0.0
        if (fraction >= 1.0) return 1.0
        val t = 2 * PI * fraction
        var sum = fraction - sin(t) / (2 * PI)
        for (k in 1..4) {
            val n = k * cycles(month)
            val p = k * phase
            fun harmonic(frequency: Int) = (sin(frequency * t + p) - sin(p)) / (2 * PI * frequency)
            sum += COEFFICIENTS[k - 1] / MEAN * (harmonic(n) - 0.5 * harmonic(n - 1) - 0.5 * harmonic(n + 1))
        }
        return sum
    }

    private companion object {
        const val MEAN = 35.0 / 8.0
        val COEFFICIENTS = doubleArrayOf(7.0, 3.5, 1.0, 0.125)
    }
}
