package terra.climate

import kotlin.math.hypot

/** Immutable climate reference plus copied landscape stores and update fluxes. */
class EnvironmentSnapshot internal constructor(
    val atlas: SeasonalClimateAtlas, val elapsedSeconds: Double, val updateSeconds: Double,
    private val soil: DoubleArray, private val capacity: DoubleArray, private val snow: DoubleArray,
    private val rain: DoubleArray, private val drainage: DoubleArray, private val overflow: DoubleArray
) {
    val width = atlas.width
    val height = atlas.height
    private val month = atlas.monthAt(elapsedSeconds)
    private val sunlight = PlanetarySunlight(width, height, atlas.solar, northUp = false).also { it.update(elapsedSeconds) }
    private fun i(x: Int, y: Int) = y * width + x
    fun relativeHumidity(x: Int, y: Int) = atlas.relativeHumidity(month, i(x, y))
    fun precipitationMmPerHour(x: Int, y: Int) = monthlyClimateRainMm(x, y) * 3600 / (atlas.solar.yearSeconds / 12)
    fun rainMmThisUpdate(x: Int, y: Int) = rain[i(x, y)]
    fun drainageMmThisUpdate(x: Int, y: Int) = drainage[i(x, y)]
    fun overflowMmThisUpdate(x: Int, y: Int) = overflow[i(x, y)]
    fun monthlyClimateRainMm(x: Int, y: Int) = atlas.rainfallMm(month, i(x, y))
    fun climateAridity(x: Int, y: Int) = atlas.aridity(i(x, y))
    fun meanClimateTemperatureC(x: Int, y: Int) = atlas.meanTemperature[i(x, y)]
    fun annualizedRainMmPerYear(x: Int, y: Int) = atlas.annualRainfallMm(i(x, y))
    fun temperatureC(x: Int, y: Int) = atlas.temperatureC(month, i(x, y))
    fun windUTilesPerDay(x: Int, y: Int) = atlas.windEastMetersPerSecond(month, i(x, y)) * 86400 * width / atlas.geography.config.domainWidthMeters
    fun windVTilesPerDay(x: Int, y: Int) = atlas.windSouthMetersPerSecond(month, i(x, y)) * 86400 * height / atlas.geography.config.domainHeightMeters
    fun windSpeedMetersPerSecond(x: Int, y: Int) = hypot(atlas.windEastMetersPerSecond(month, i(x, y)), atlas.windSouthMetersPerSecond(month, i(x, y)))
    fun nightOpacity(x: Int, y: Int) = sunlight.nightOpacity(x, y)
    fun snowWaterMm(x: Int, y: Int) = snow[i(x, y)]
    fun soilWaterMm(x: Int, y: Int) = soil[i(x, y)]
    fun soilCapacityMm(x: Int, y: Int) = capacity[i(x, y)]
    fun soilMoisture(x: Int, y: Int) = soilWaterMm(x, y) / soilCapacityMm(x, y)
}
