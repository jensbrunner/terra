package terra.weather

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Four orbital-season bins on the atmospheric grid. First year is spin-up;
 * the latest two complete observed years define climate, never the current weather.
 */
class SeasonalClimate(
    private val width: Int,
    private val height: Int,
    val yearSeconds: Double,
    initialYearFraction: Double = 0.0
) {
    private val count = width * height
    private val boundaries = (listOf(0.0, yearSeconds) + (0..3).map {
        (((it / 4.0 - initialYearFraction) % 1.0 + 1.0) % 1.0) * yearSeconds
    }).distinct().sorted()
    private val seasons = IntArray(boundaries.size - 1) {
        floor((((boundaries[it] + boundaries[it + 1]) * 0.5 / yearSeconds + initialYearFraction) % 1.0) * 4).toInt()
    }
    private val temperature = Array(4) { DoubleArray(count) }
    private val rain = Array(4) { DoubleArray(count) }
    private val minimum = DoubleArray(count) { Double.POSITIVE_INFINITY }
    private val maximum = DoubleArray(count) { Double.NEGATIVE_INFINITY }
    private val peakRain = DoubleArray(count)
    private val durations = DoubleArray(4)
    private var yearElapsed = 0.0
    private var segment = 0
    private var completedYears = 0
    private var latest: ClimateYear? = null
    var snapshot: SeasonalClimateSnapshot? = null
        private set
    val observedYears: Int get() = max(0, completedYears - 1)

    init {
        require(width > 0 && height > 0 && yearSeconds.isFinite() && yearSeconds > 0)
        require(initialYearFraction.isFinite() && initialYearFraction in 0.0..1.0)
    }

    /** Inputs are end-of-physics-step surface °C and actual rain depth, not rain rates.
     * Split duration and rainfall at exact seasonal/year boundaries, including offset years.
     */
    fun observe(seconds: Double, surfaceTemperatureC: (Int) -> Double, rainMm: (Int) -> Double) {
        require(seconds.isFinite() && seconds > 0)
        var remaining = seconds
        while (remaining > 0.0) {
            val span = min(remaining, boundaries[segment + 1] - yearElapsed)
            val season = seasons[segment]
            durations[season] += span
            for (cell in 0 until count) {
                val t = surfaceTemperatureC(cell)
                val r = rainMm(cell)
                check(t.isFinite() && r.isFinite() && r >= 0)
                temperature[season][cell] += t * span
                rain[season][cell] += r * (span / seconds)
                minimum[cell] = min(minimum[cell], t)
                maximum[cell] = max(maximum[cell], t)
                peakRain[cell] = max(peakRain[cell], r * 3600 / seconds)
            }
            remaining -= span
            yearElapsed += span
            if (yearElapsed >= boundaries[segment + 1]) {
                segment++
                if (segment == seasons.size) {
                    completedYears++
                    if (completedYears > 1) {
                        val year = ClimateYear(temperature.map { it.copyOf() }.toTypedArray(),
                            rain.map { it.copyOf() }.toTypedArray(), durations.copyOf(), minimum.copyOf(), maximum.copyOf(), peakRain.copyOf())
                        snapshot = SeasonalClimateSnapshot(width, height, yearSeconds, observedYears, latest, year)
                        latest = year
                    }
                    temperature.forEach { it.fill(0.0) }; rain.forEach { it.fill(0.0) }
                    durations.fill(0.0); minimum.fill(Double.POSITIVE_INFINITY)
                    maximum.fill(Double.NEGATIVE_INFINITY); peakRain.fill(0.0)
                    yearElapsed = 0.0
                    segment = 0
                }
            }
        }
    }
}

internal class ClimateYear(
    val temperatureIntegral: Array<DoubleArray>, val rain: Array<DoubleArray>, val durations: DoubleArray,
    val minimum: DoubleArray, val maximum: DoubleArray, val peakRain: DoubleArray
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
    fun stableAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int): Boolean {
        val old = previous ?: return false
        val temperatureChange = sampleTile(x, y, tileWidth, tileHeight) { latest.temperature(it) - old.temperature(it) }
        val rainChange = sampleTile(x, y, tileWidth, tileHeight) { latest.precipitation(it) - old.precipitation(it) }
        val recentRain = sampleTile(x, y, tileWidth, tileHeight, latest::precipitation)
        val oldRain = sampleTile(x, y, tileWidth, tileHeight, old::precipitation)
        return abs(temperatureChange) <= 2.0 && abs(rainChange) <= max(100.0, 0.35 * max(recentRain, oldRain))
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
