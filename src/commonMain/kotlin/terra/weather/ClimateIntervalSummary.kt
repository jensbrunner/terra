package terra.weather

/** Integrated statistics for a completed [startSeconds, startSeconds + durationSeconds) interval.
 * This is diagnostic history, never a water donor. Split heterogeneous seasons at the producer;
 * when an interval crosses a bin, integrals/rain are apportioned uniformly in time and its extrema
 * conservatively apply to every overlapped bin. No daily or physical substeps are materialized.
 */
class ClimateIntervalSummary(
    val width: Int, val height: Int, val startSeconds: Double, val durationSeconds: Double,
    surfaceTemperatureIntegralCSeconds: DoubleArray, precipitationMm: DoubleArray,
    minimumSurfaceTemperatureC: DoubleArray, maximumSurfaceTemperatureC: DoubleArray,
    peakRainMmPerHour: DoubleArray
) {
    private val temperature = surfaceTemperatureIntegralCSeconds.copyOf()
    private val rain = precipitationMm.copyOf()
    private val minimum = minimumSurfaceTemperatureC.copyOf()
    private val maximum = maximumSurfaceTemperatureC.copyOf()
    private val peak = peakRainMmPerHour.copyOf()

    init {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
        require(startSeconds.isFinite() && startSeconds >= 0.0 && durationSeconds.isFinite() && durationSeconds > 0.0)
        require((startSeconds + durationSeconds).isFinite() && startSeconds + durationSeconds > startSeconds)
        require(listOf(temperature, rain, minimum, maximum, peak).all { it.size == width * height })
        for (i in temperature.indices) {
            val mean = temperature[i] / durationSeconds
            require(mean.isFinite() && minimum[i].isFinite() && maximum[i].isFinite() && minimum[i] <= maximum[i])
            val tolerance = 1e-10 * maxOf(1.0, kotlin.math.abs(mean))
            require(mean >= minimum[i] - tolerance && mean <= maximum[i] + tolerance)
            require(rain[i].isFinite() && rain[i] >= 0.0 && peak[i].isFinite() && peak[i] >= 0.0 && peak[i] + 1e-10 * maxOf(1.0, peak[i]) >= rain[i] * 3600 / durationSeconds)
        }
    }
    internal fun meanTemperatureC(cell: Int) = temperature[cell] / durationSeconds
    internal fun precipitationMm(cell: Int) = rain[cell]
    internal fun minimumTemperatureC(cell: Int) = minimum[cell]
    internal fun maximumTemperatureC(cell: Int) = maximum[cell]
    internal fun peakRainMmPerHour(cell: Int) = peak[cell]
}
