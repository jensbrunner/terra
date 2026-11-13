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
    private var currentYearEstimated = false
    private var latest: ClimateYear? = null
    var snapshot: SeasonalClimateSnapshot? = null
        private set
    var elapsedSeconds = 0.0
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
        accumulate(seconds, surfaceTemperatureC, rainMm)
    }

    /** Internal fast-path ingestion for a modeled phase; avoids copying five diagnostic arrays. */
    internal fun observePhase(
        startSeconds: Double, seconds: Double, temperatureIntegralC: DoubleArray, rainMm: DoubleArray,
        minimumC: DoubleArray, maximumC: DoubleArray, peakRainMmPerHour: DoubleArray
    ) {
        require(startSeconds == elapsedSeconds) { "Climate phase must follow the last committed observation" }
        require(seconds > 0.0 && temperatureIntegralC.size == count && rainMm.size == count)
        accumulate(seconds, { temperatureIntegralC[it] / seconds }, { rainMm[it] },
            { minimumC[it] }, { maximumC[it] }, { peakRainMmPerHour[it] }, modeled = true)
    }

    /** Bulk ingestion keeps existing spin-up, seasonal splits and two-observed-year eligibility. */
    fun observeInterval(summary: ClimateIntervalSummary, modeled: Boolean = false) {
        require(summary.width == width && summary.height == height)
        require(summary.startSeconds == elapsedSeconds) { "Climate interval must follow the last committed observation" }
        accumulate(summary.durationSeconds, summary::meanTemperatureC, summary::precipitationMm,
            summary::minimumTemperatureC, summary::maximumTemperatureC, summary::peakRainMmPerHour, modeled)
    }

    /** Keep only the boundary fragments and last two annual estimates; skip middle years arithmetically.
     * The representative seasonal rates are estimates, never independent proof of stationarity.
     */
    internal fun observeAggregate(start: Double, seconds: Double, summary: SeasonalAggregate) {
        require(start == elapsedSeconds && seconds.isFinite() && seconds > yearSeconds)
        require(summary.temperatureC.size == 4 && summary.rainMmPerSecond.size == 4)
        for (season in 0..3) {
            require(summary.temperatureC[season].size == count && summary.rainMmPerSecond[season].size == count)
            require(summary.temperatureC[season].all { it.isFinite() } && summary.rainMmPerSecond[season].all { it.isFinite() && it >= 0 })
        }
        val target = start + seconds
        fun consume(duration: Double) {
            var remaining = duration
            while (remaining > 0.0) {
                val span = min(remaining, boundaries[segment + 1] - yearElapsed)
                val season = seasons[segment]
                currentYearEstimated = true
                accumulate(span, { summary.temperatureC[season][it] }, { summary.rainMmPerSecond[season][it] * span },
                    { summary.minimumC[it] }, { summary.maximumC[it] }, { summary.peakMmPerHour[it] })
                remaining -= span
            }
        }
        consume(min(seconds, yearSeconds - yearElapsed))
        val complete = floor((target - elapsedSeconds) / yearSeconds).toInt().coerceAtLeast(0)
        if (complete > 0) {
            val duration = DoubleArray(4) { yearSeconds / 4 }
            val year = ClimateYear(Array(4) { s -> DoubleArray(count) { summary.temperatureC[s][it] * duration[s] } },
                Array(4) { s -> DoubleArray(count) { summary.rainMmPerSecond[s][it] * duration[s] } }, duration,
                summary.minimumC.copyOf(), summary.maximumC.copyOf(), summary.peakMmPerHour.copyOf(), estimated = true)
            val previous = if (complete >= 2) year else latest
            completedYears += complete
            snapshot = SeasonalClimateSnapshot(width, height, yearSeconds, observedYears, previous, year)
            latest = year
            elapsedSeconds += complete * yearSeconds
        }
        if (target > elapsedSeconds) consume(target - elapsedSeconds)
        elapsedSeconds = target
    }

    private fun accumulate(
        seconds: Double, surfaceTemperatureC: (Int) -> Double, rainMm: (Int) -> Double,
        intervalMinimum: ((Int) -> Double)? = null, intervalMaximum: ((Int) -> Double)? = null,
        intervalPeakRain: ((Int) -> Double)? = null, modeled: Boolean = false
    ) {
        require(seconds.isFinite() && seconds > 0)
        var remaining = seconds
        while (remaining > 0.0) {
            currentYearEstimated = currentYearEstimated || modeled
            val span = min(remaining, boundaries[segment + 1] - yearElapsed)
            val season = seasons[segment]
            durations[season] += span
            for (cell in 0 until count) {
                val t = surfaceTemperatureC(cell)
                val r = rainMm(cell)
                check(t.isFinite() && r.isFinite() && r >= 0)
                temperature[season][cell] += t * span
                rain[season][cell] += r * (span / seconds)
                minimum[cell] = min(minimum[cell], intervalMinimum?.invoke(cell) ?: t)
                maximum[cell] = max(maximum[cell], intervalMaximum?.invoke(cell) ?: t)
                peakRain[cell] = max(peakRain[cell], intervalPeakRain?.invoke(cell) ?: (r * 3600 / seconds))
            }
            remaining -= span
            yearElapsed += span
            if (yearElapsed >= boundaries[segment + 1]) {
                segment++
                if (segment == seasons.size) {
                    completedYears++
                    if (completedYears > 1) {
                        val year = ClimateYear(temperature.map { it.copyOf() }.toTypedArray(),
                            rain.map { it.copyOf() }.toTypedArray(), durations.copyOf(), minimum.copyOf(), maximum.copyOf(), peakRain.copyOf(), currentYearEstimated)
                        snapshot = SeasonalClimateSnapshot(width, height, yearSeconds, observedYears, latest, year)
                        latest = year
                    }
                    temperature.forEach { it.fill(0.0) }; rain.forEach { it.fill(0.0) }
                    durations.fill(0.0); minimum.fill(Double.POSITIVE_INFINITY)
                    maximum.fill(Double.NEGATIVE_INFINITY); peakRain.fill(0.0)
                    currentYearEstimated = false
                    yearElapsed = 0.0
                    segment = 0
                }
            }
        }
        elapsedSeconds += seconds
    }
}

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

/** Producer-owned scratch summary, consumed synchronously on the simulation owner. */
internal class SeasonalAggregate(
    val temperatureC: Array<DoubleArray>, val rainMmPerSecond: Array<DoubleArray>,
    val minimumC: DoubleArray, val maximumC: DoubleArray, val peakMmPerHour: DoubleArray
)
