package terra.weather

/** Tile-space view shared by the prescribed and dynamical atmospheres.
 * Water fluxes belong to one completed update; positive wind V points down the map.
 */
interface WeatherModel {
    val width: Int
    val height: Int
    fun step(seconds: Double)
    fun relativeHumidity(x: Int, y: Int): Double
    fun cloudCover(x: Int, y: Int): Double
    fun precipitationMmPerHour(x: Int, y: Int): Double
    fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int): Double
    fun rainMmThisUpdate(x: Int, y: Int): Double
    fun drainageMmThisUpdate(x: Int, y: Int): Double
    fun overflowMmThisUpdate(x: Int, y: Int): Double
    fun lakeDirectRainMmThisUpdate(x: Int, y: Int): Double
    fun lakePotentialEvaporationMmThisUpdate(x: Int, y: Int): Double
    fun thirtyDayRainMmPerHour(x: Int, y: Int): Double
    fun annualizedRainMmPerYear(x: Int, y: Int): Double
    fun solarInputFactor(x: Int, y: Int): Double
    fun surfaceTemperatureC(x: Int, y: Int): Double
    fun airTemperatureC(x: Int, y: Int): Double
    fun windUTilesPerDay(x: Int, y: Int): Double
    fun windVTilesPerDay(x: Int, y: Int): Double
    fun nightOpacity(x: Int): Double
    fun atmosphericWaterMm(x: Int, y: Int): Double
    fun cloudWaterMm(x: Int, y: Int): Double
    fun soilWaterMm(x: Int, y: Int): Double
    fun soilMoisture(x: Int, y: Int): Double
    fun updateLakeWaterMask(mask: BooleanArray)
    fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double)
    fun hydrologyAccepted() {}
}
