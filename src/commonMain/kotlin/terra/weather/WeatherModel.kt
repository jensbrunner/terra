package terra.weather

/** Quality of representative fields, independently of integrated rainfall totals. */
enum class WeatherFieldQuality { RESOLVED_NOW, REPRESENTATIVE_FOR_INTERVAL }

data class WeatherFieldSemantics(
    val quality: WeatherFieldQuality, val intervalStartSeconds: Double = 0.0,
    val intervalDurationSeconds: Double = 0.0
) {
    companion object { val RESOLVED = WeatherFieldSemantics(WeatherFieldQuality.RESOLVED_NOW) }
}

/** Tile-space view; water fluxes belong to one completed update, and wind V points down-map. */
interface WeatherModel {
    /** Seasonal climate exposes monthly means rather than resolved dynamics. */
    val hasResolvedDynamics: Boolean get() = false
    fun pressureAnomalyPercent(x: Int, y: Int): Double? = null
    fun relativeVorticityPerSecond(x: Int, y: Int): Double? = null
    /** Rain totals are integrated over the update; representative peaks describe subperiods. */
    val fieldSemantics: WeatherFieldSemantics get() = WeatherFieldSemantics.RESOLVED
    val width: Int
    val height: Int
    /** Completed simulation time; presentation never advances the model clock. */
    val elapsedSeconds: Double
    /** Stable across immutable publications of one weather owner; changes on regeneration. */
    val presentationIdentity: Any get() = this
    fun step(seconds: Double)
    fun relativeHumidity(x: Int, y: Int): Double
    fun cloudCover(x: Int, y: Int): Double
    fun precipitationMmPerHour(x: Int, y: Int): Double
    fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int): Double
    fun rainMmThisUpdate(x: Int, y: Int): Double
    fun drainageMmThisUpdate(x: Int, y: Int): Double
    fun overflowMmThisUpdate(x: Int, y: Int): Double
    fun thirtyDayRainMmPerHour(x: Int, y: Int): Double
    fun monthlyClimateRainMm(x: Int, y: Int): Double = thirtyDayRainMmPerHour(x, y) * 24 * 365 / 12
    fun climateAridity(x: Int, y: Int): Double = 0.0
    fun meanClimateTemperatureC(x: Int, y: Int): Double = airTemperatureC(x, y)
    fun annualizedRainMmPerYear(x: Int, y: Int): Double
    fun solarInputFactor(x: Int, y: Int): Double
    fun surfaceTemperatureC(x: Int, y: Int): Double
    fun airTemperatureC(x: Int, y: Int): Double
    fun windUTilesPerDay(x: Int, y: Int): Double
    fun windVTilesPerDay(x: Int, y: Int): Double
    fun windSpeedMetersPerSecond(x: Int, y: Int): Double
    fun nightOpacity(x: Int, y: Int): Double
    fun atmosphericWaterMm(x: Int, y: Int): Double
    fun cloudWaterMm(x: Int, y: Int): Double
    fun soilWaterMm(x: Int, y: Int): Double
    fun soilCapacityMm(x: Int, y: Int): Double = 250.0
    fun soilMoisture(x: Int, y: Int): Double
}
