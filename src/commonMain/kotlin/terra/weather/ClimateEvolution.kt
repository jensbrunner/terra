package terra.weather

import terra.world.World
import kotlin.math.*

/** Surface-only flux accounting; atlas humidity is diagnostic and excluded. */
data class ClimateIntervalDiagnostics(
    val startSeconds: Double, val durationSeconds: Double, val subperiods: Int,
    val waterBeforeKg: Double, val waterAfterKg: Double, val relativeWaterResidual: Double,
    val evaporationKg: Double, val rainfallKg: Double, val detailedCompletedSteps: Long,
    val boundaryExportKg: Double = 0.0, val energyApproximate: Boolean = true,
    val ecologyEligibleSeconds: Double = 0.0
)

internal class ClimateEvolution(private val world: World, private val surface: TerrainMoistSurface,
    startSeconds: Double) {
    var completedSeconds = startSeconds
        private set
    private val drought = surface.climateDrought
    var diagnostics: ClimateIntervalDiagnostics? = null
        private set
    fun advance(atlas: SeasonalClimateAtlas, seconds: Double, detailedSteps: Long): ClimateIntervalDiagnostics {
        val start = completedSeconds
        val before = surface.totalWaterKg
        val boundaryBefore = surface.exportedWaterKg
        surface.beginUpdate()
        var remaining = seconds; var months = 0; var evaporation = 0.0; var rain = 0.0
        while (remaining > 1e-7) {
            val calendar = completedSeconds / atlas.solar.yearSeconds + atlas.solar.initialYearFraction
            val monthIndex = floor(calendar * 12 + 1e-10).toLong()
            val month = ((monthIndex % 12 + 12) % 12).toInt()
            val untilBoundary = ((monthIndex + 1) / 12.0 - atlas.solar.initialYearFraction) * atlas.solar.yearSeconds - completedSeconds
            val span = min(remaining, max(1e-7, untilBoundary))
            val fraction = span * 12 / atlas.solar.yearSeconds
            evaporation += surface.evolveMonth(atlas, month, fraction)
            val droughtResponse = -expm1(-fraction / 24)
            for (i in drought.indices) if (atlas.geography.tileLand[i]) {
                rain += atlas.rain[month][i] * fraction * surface.mapping.tileAreaM2
                val deficit = (1 - surface.soil[i] / 80).coerceIn(0.0, 1.0)
                drought[i] += (deficit - drought[i]) * droughtResponse
            }
            completedSeconds += span; remaining -= span; months++
            if (span >= untilBoundary - 1e-6 && month == 11) world.evolveAtlasCover(atlas, 1.0, drought = drought)
        }
        val after = surface.totalWaterKg
        val export = surface.exportedWaterKg - boundaryBefore
        // Rain on the ocean was never inserted in the terrestrial stores.
        val residual = (after - before - rain + evaporation + export) / max(1.0, before + rain)
        return ClimateIntervalDiagnostics(start, seconds, months, before, after, residual, evaporation, rain,
            detailedSteps, export, ecologyEligibleSeconds = seconds).also { diagnostics = it }
    }
}

/** Immutable tile-space publication from the same atlas used by surface evolution. */
internal class ClimatePresentation(private val detailed: WeatherModel, private val atlas: SeasonalClimateAtlas,
    private val month: Int, private val soil: DoubleArray, private val rain: DoubleArray,
    private val drainage: DoubleArray, private val overflow: DoubleArray,
    override val elapsedSeconds: Double, private val seconds: Double, private val climateMode: Boolean
) : WeatherModel by detailed {
    private fun i(x: Int, y: Int) = y * width + x
    override val hasResolvedDynamics get() = !climateMode && detailed.hasResolvedDynamics
    override val fieldSemantics get() = if (climateMode) WeatherFieldSemantics(WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL,
        elapsedSeconds - seconds, seconds, true) else detailed.fieldSemantics
    override fun pressureAnomalyPercent(x: Int, y: Int) = if (climateMode) null else detailed.pressureAnomalyPercent(x, y)
    override fun relativeVorticityPerSecond(x: Int, y: Int) = if (climateMode) null else detailed.relativeVorticityPerSecond(x, y)
    override fun monthlyClimateRainMm(x: Int, y: Int) = atlas.rain[month][i(x, y)]
    override fun climateAridity(x: Int, y: Int) = atlas.aridity(i(x, y))
    override fun meanClimateTemperatureC(x: Int, y: Int) = atlas.meanTemperature[i(x, y)]
    override fun annualizedRainMmPerYear(x: Int, y: Int) = atlas.annualRain[i(x, y)]
    override fun thirtyDayRainMmPerHour(x: Int, y: Int) = atlas.rain[month][i(x, y)] / (365.0 / 12 * 24)
    override fun surfaceTemperatureC(x: Int, y: Int) = if (climateMode) atlas.temperature[month][i(x, y)] else detailed.surfaceTemperatureC(x, y)
    override fun airTemperatureC(x: Int, y: Int) = if (climateMode) surfaceTemperatureC(x, y) else detailed.airTemperatureC(x, y)
    override fun windUTilesPerDay(x: Int, y: Int) = if (climateMode) atlas.east[month][i(x, y)] * 86400 * width / atlas.geography.config.domainWidthMeters else detailed.windUTilesPerDay(x, y)
    override fun windVTilesPerDay(x: Int, y: Int) = if (climateMode) atlas.south[month][i(x, y)] * 86400 * height / atlas.geography.config.domainHeightMeters else detailed.windVTilesPerDay(x, y)
    override fun rainEventSummary(x: Int, y: Int): RainEventSummary? = if (climateMode) null else detailed.rainEventSummary(x, y)
    override fun precipitationMmPerHour(x: Int, y: Int) = if (climateMode) thirtyDayRainMmPerHour(x, y) else detailed.precipitationMmPerHour(x, y)
    override fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int) = if (climateMode) precipitationMmPerHour(x, y) else detailed.peakSubstepPrecipitationMmPerHour(x, y)
    override fun rainMmThisUpdate(x: Int, y: Int) = rain[i(x, y)]
    override fun drainageMmThisUpdate(x: Int, y: Int) = drainage[i(x, y)]
    override fun overflowMmThisUpdate(x: Int, y: Int) = overflow[i(x, y)]
    override fun soilWaterMm(x: Int, y: Int) = soil[i(x, y)]
    override fun soilMoisture(x: Int, y: Int) = soil[i(x, y)] / 250
    override fun relativeHumidity(x: Int, y: Int) = if (climateMode) (0.25 + 0.5 * atlas.aridity(i(x, y))).coerceIn(0.2, 0.9) else detailed.relativeHumidity(x, y)
    override fun cloudWaterMm(x: Int, y: Int) = if (climateMode) 0.0 else detailed.cloudWaterMm(x, y)
    override fun cloudCover(x: Int, y: Int) = if (climateMode) 0.0 else detailed.cloudCover(x, y)
    override fun atmosphericWaterMm(x: Int, y: Int) = if (climateMode) 0.0 else detailed.atmosphericWaterMm(x, y)
    override fun step(seconds: Double): Unit = error("Immutable climate presentation")
}
