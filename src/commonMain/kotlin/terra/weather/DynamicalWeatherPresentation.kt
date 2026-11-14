package terra.weather

import terra.atmosphere.*
import kotlin.math.exp
import kotlin.math.floor

/** Read-only publication. The coarse snapshot and fine arrays are never mutated after construction. */
internal class DynamicalWeatherPresentation(
    override val width: Int, override val height: Int, private val config: DryAtmosphereConfig,
    physics: MoistPhysicsConfig, private val view: MoistAtmosphereSnapshot,
    override val elapsedSeconds: Double, private val updateSeconds: Double,
    private val fields: Array<DoubleArray>,
    override val hasResolvedDynamics: Boolean = true
) : WeatherModel {
    // Eager on the publishing worker; tile rendering only interpolates immutable coarse values.
    private val dynamics = if (hasResolvedDynamics) view.horizontalDynamics else null
    override fun pressureAnomalyPercent(x: Int, y: Int): Double? = dynamics?.let {
        sample(x, y, it::pressureAnomalyPercentAt)
    }
    override fun relativeVorticityPerSecond(x: Int, y: Int): Double? = dynamics?.let {
        sample(x, y, it::relativeVorticityPerSecondAt)
    }
    override val fieldSemantics get() = WeatherFieldSemantics(WeatherFieldQuality.RESOLVED_NOW, elapsedSeconds - updateSeconds, updateSeconds)
    private val sunlight = PlanetarySunlight(width, height, physics.solar, northUp = false)
        .also { it.update(elapsedSeconds) }
    /** State interpolation is for display. Water transfers always use exact rectangle overlaps. */
    private inline fun sample(x: Int, y: Int, field: (Int, Int) -> Double): Double {
        val px = (x + 0.5) * config.width / width - 0.5
        val py = config.height - (y + 0.5) * config.height / height - 0.5
        val left = floor(px).toInt(); val bottom = floor(py).toInt()
        val fx = px - left; val fy = py - bottom
        val x0 = (left + config.width) % config.width; val x1 = (x0 + 1) % config.width
        val y0 = if (config.periodicY) (bottom + config.height) % config.height else bottom.coerceIn(0, config.height - 1)
        val y1 = if (config.periodicY) (y0 + 1) % config.height else (bottom + 1).coerceIn(0, config.height - 1)
        return (field(x0, y0) * (1 - fx) + field(x1, y0) * fx) * (1 - fy) +
            (field(x0, y1) * (1 - fx) + field(x1, y1) * fx) * fy
    }

    private fun index(x: Int, y: Int) = y * width + x
    override fun relativeHumidity(x: Int, y: Int) = sample(x, y, view::relativeHumidityAt)
    override fun cloudWaterMm(x: Int, y: Int) = sample(x, y, view::cloudWaterMmAt)
    override fun cloudCover(x: Int, y: Int) = 1 - exp(-cloudWaterMm(x, y) / 0.15)
    override fun atmosphericWaterMm(x: Int, y: Int) = sample(x, y) { cx, cy -> view.vaporMmAt(cx, cy) + view.cloudWaterMmAt(cx, cy) }
    override fun airTemperatureC(x: Int, y: Int) = sample(x, y, view::temperatureKelvinAt) - 273.15
    override fun surfaceTemperatureC(x: Int, y: Int) = sample(x, y, view::surfaceTemperatureKelvinAt) - 273.15
    override fun windUTilesPerDay(x: Int, y: Int) = sample(x, y, view.dynamics::eastWindAt) * 86400 * width / config.domainWidthMeters
    override fun windVTilesPerDay(x: Int, y: Int) = -sample(x, y, view.dynamics::northWindAt) * 86400 * height / config.domainHeightMeters
    override fun precipitationMmPerHour(x: Int, y: Int) = if (updateSeconds > 0) rainMmThisUpdate(x, y) * 3600 / updateSeconds else 0.0
    override fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int) = fields[0][index(x, y)]
    override fun rainMmThisUpdate(x: Int, y: Int) = fields[1][index(x, y)]
    override fun drainageMmThisUpdate(x: Int, y: Int) = fields[2][index(x, y)]
    override fun overflowMmThisUpdate(x: Int, y: Int) = fields[3][index(x, y)]
    // Lake evaporation is already withdrawn; detailed physics pays latent heat, climate labels its energy approximation.
    override fun thirtyDayRainMmPerHour(x: Int, y: Int) = fields[4][index(x, y)]
    override fun annualizedRainMmPerYear(x: Int, y: Int) = thirtyDayRainMmPerHour(x, y) * 24 * 365
    override fun soilWaterMm(x: Int, y: Int) = fields[5][index(x, y)]
    override fun soilMoisture(x: Int, y: Int) = soilWaterMm(x, y) / 250.0
    override fun solarInputFactor(x: Int, y: Int): Double {
        return sunlight.inputFactor(x, y)
    }
    override fun nightOpacity(x: Int, y: Int): Double {
        return sunlight.nightOpacity(x, y)
    }
    override fun step(seconds: Double): Unit = error("Presentation cannot advance physics")
}
