package terra.weather

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal class ClimateYear(
    val temperatureIntegral: Array<DoubleArray>, val rain: Array<DoubleArray>, val durations: DoubleArray,
    val minimum: DoubleArray, val maximum: DoubleArray, val peakRain: DoubleArray, val estimated: Boolean = false
) {
    fun temperature(cell: Int): Double = temperatureIntegral.sumOf { it[cell] } / durations.sum()
    fun precipitation(cell: Int): Double = rain.sumOf { it[cell] }
}

/** Immutable copies of completed years. Surface temperature is an ecological proxy,
 * not a calibrated two-meter air temperature or the cold effective air column.
 */
class SeasonalClimateSnapshot internal constructor(
    val width: Int, val height: Int, val yearSeconds: Double, val observedYears: Int,
    private val previous: ClimateYear?, private val latest: ClimateYear
) {
    val containsAggregateEstimates: Boolean get() = latest.estimated || previous?.estimated == true
    val hasAnnualCoverage: Boolean get() = previous != null
    val windowYears: Int get() = if (previous == null) 1 else 2
    fun meanSurfaceTemperatureC(cell: Int): Double =
        ((previous?.temperature(cell) ?: 0.0) + latest.temperature(cell)) / windowYears
    fun annualPrecipitationMm(cell: Int): Double =
        ((previous?.precipitation(cell) ?: 0.0) + latest.precipitation(cell)) / windowYears
    fun seasonMeanSurfaceTemperatureC(season: Int, cell: Int): Double =
        ((previous?.temperatureIntegral?.get(season)?.get(cell) ?: 0.0) + latest.temperatureIntegral[season][cell]) /
            ((previous?.durations?.get(season) ?: 0.0) + latest.durations[season])
    fun seasonPrecipitationMm(season: Int, cell: Int): Double =
        ((previous?.rain?.get(season)?.get(cell) ?: 0.0) + latest.rain[season][cell]) / windowYears
    /** Actual season depth divided by represented duration, independent of orbital-year length. */
    fun seasonMeanPrecipitationMmPerDay(season: Int, cell: Int): Double =
        ((previous?.rain?.get(season)?.get(cell) ?: 0.0) + latest.rain[season][cell]) * 86400.0 /
            ((previous?.durations?.get(season) ?: 0.0) + latest.durations[season])
    fun minimumSurfaceTemperatureC(cell: Int): Double = min(previous?.minimum?.get(cell) ?: Double.POSITIVE_INFINITY, latest.minimum[cell])
    fun maximumSurfaceTemperatureC(cell: Int): Double = max(previous?.maximum?.get(cell) ?: Double.NEGATIVE_INFINITY, latest.maximum[cell])
    fun peakRainMmPerHour(cell: Int): Double = max(previous?.peakRain?.get(cell) ?: 0.0, latest.peakRain[cell])
    fun wettestSeasonFraction(cell: Int): Double {
        val total = annualPrecipitationMm(cell)
        return if (total > 0) (0..3).maxOf { seasonPrecipitationMm(it, cell) } / total else 0.0
    }

    fun meanSurfaceTemperatureAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int): Double =
        sampleTile(x, y, tileWidth, tileHeight, ::meanSurfaceTemperatureC)
    fun annualPrecipitationAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int): Double =
        sampleTile(x, y, tileWidth, tileHeight, ::annualPrecipitationMm)
    fun seasonMeanSurfaceTemperatureAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int, season: Int): Double =
        sampleTile(x, y, tileWidth, tileHeight) { seasonMeanSurfaceTemperatureC(season, it) }
    fun seasonPrecipitationAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int, season: Int): Double =
        sampleTile(x, y, tileWidth, tileHeight) { seasonPrecipitationMm(season, it) }
    fun frostMinimumAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int): Double =
        sampleTile(x, y, tileWidth, tileHeight, ::minimumSurfaceTemperatureC)

    /** Growing-season warmth in degree-days above 5 °C, not an annual mean. */
    fun growingSeasonWarmthAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int): Double {
        var warmth = 0.0
        for (season in 0..3) {
            val temperature = seasonMeanSurfaceTemperatureAtTile(x, y, tileWidth, tileHeight, season)
            warmth += max(0.0, temperature - 5.0) * SEASON_DAYS
        }
        return warmth
    }

    /** Seasonal water deficit in mm: warm-season demand not met by that season's rain. */
    fun seasonalWaterDeficitAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int): Double {
        var deficit = 0.0
        for (season in 0..3) {
            val temperature = seasonMeanSurfaceTemperatureAtTile(x, y, tileWidth, tileHeight, season)
            val demandPerDay = 3.0 * ((temperature + 5) / 35).coerceIn(0.0, 1.5)
            val days = SEASON_DAYS
            deficit += max(0.0, demandPerDay * days - seasonPrecipitationAtTile(x, y, tileWidth, tileHeight, season))
        }
        return deficit
    }
    fun stableAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int): Boolean {
        if (containsAggregateEstimates) return false
        val old = previous ?: return false
        val temperatureChange = sampleTile(x, y, tileWidth, tileHeight) { latest.temperature(it) - old.temperature(it) }
        val rainChange = sampleTile(x, y, tileWidth, tileHeight) { latest.precipitation(it) - old.precipitation(it) }
        val recentRain = sampleTile(x, y, tileWidth, tileHeight, latest::precipitation)
        val oldRain = sampleTile(x, y, tileWidth, tileHeight, old::precipitation)
        return abs(temperatureChange) <= 2.0 && abs(rainChange) <= max(100.0, 0.35 * max(recentRain, oldRain))
    }

    private companion object {
        /** Nominal growing-season length in days; independent of a compressed test calendar. */
        const val SEASON_DAYS = 365.25 / 4
    }

    private inline fun sampleTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int, field: (Int) -> Double): Double {
        val px = (x + 0.5) * width / tileWidth - 0.5
        val py = height - (y + 0.5) * height / tileHeight - 0.5
        val left = floor(px).toInt(); val bottom = floor(py).toInt()
        val fx = px - left; val fy = py - bottom
        val x0 = (left + width) % width; val x1 = (x0 + 1) % width
        val y0 = bottom.coerceIn(0, height - 1); val y1 = (bottom + 1).coerceIn(0, height - 1)
        return (field(y0 * width + x0) * (1 - fx) + field(y0 * width + x1) * fx) * (1 - fy) +
            (field(y1 * width + x0) * (1 - fx) + field(y1 * width + x1) * fx) * fy
    }
}


