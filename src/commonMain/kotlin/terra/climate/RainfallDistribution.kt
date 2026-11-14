package terra.climate

import kotlin.math.floor

/** Shared atlas precipitation, spread evenly through each month. */
class RainfallDistribution internal constructor(private val atlas: SeasonalClimateAtlas) {
    fun rateMmPerHour(x: Int, y: Int, seconds: Double): Double =
        atlas.rain[atlas.monthAt(seconds)][y * atlas.width + x] * 12 * 3600 / atlas.solar.yearSeconds

    fun rateMmPerHour(x: Double, y: Double, seconds: Double): Double =
        rateMmPerHour(floor(x).toInt().mod(atlas.width), floor(y).toInt().coerceIn(0, atlas.height - 1), seconds)

    /** The same atlas temperature partitions both the delivered water and its overlay. */
    fun snowFraction(x: Int, y: Int, seconds: Double): Double =
        snowFraction(atlas.monthAt(seconds), y * atlas.width + x)

    internal fun snowFraction(month: Int, tile: Int) = ((1 - atlas.temperature[month][tile]) / 2).coerceIn(0.0, 1.0)

    internal fun amountMm(month: Int, tile: Int, start: Double, end: Double): Double =
        atlas.rain[month][tile] * (end - start).coerceAtLeast(0.0)
}
