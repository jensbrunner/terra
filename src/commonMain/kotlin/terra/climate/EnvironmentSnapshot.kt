package terra.climate

import kotlin.math.hypot

/** Immutable view of the one live atmospheric state and its landscape fluxes. */
class EnvironmentSnapshot internal constructor(
    val width: Int, val height: Int,
    val elapsedSeconds: Double, val updateSeconds: Double,
    private val humidity: DoubleArray, private val clouds: DoubleArray, private val rainRate: DoubleArray,
    private val temperature: DoubleArray, private val east: DoubleArray, private val south: DoubleArray,
    private val pressure: DoubleArray,
    private val climateRain: DoubleArray, private val soil: DoubleArray, private val capacity: DoubleArray,
    private val snow: DoubleArray, private val rain: DoubleArray, private val drainage: DoubleArray,
    private val overflow: DoubleArray
) {
    private fun i(x: Int, y: Int) = y * width + x
    fun relativeHumidity(x: Int, y: Int) = humidity[i(x, y)].coerceIn(0.0, 1.0)
    fun cloudCover(x: Int, y: Int) = clouds[i(x, y)].coerceIn(0.0, 1.0)
    fun precipitationMmPerHour(x: Int, y: Int) = rainRate[i(x, y)]
    fun snowFraction(x: Int, y: Int) = ((1.0 - temperatureC(x, y) / 2.0) / 2.0).coerceIn(0.0, 1.0)
    fun pressureHpa(x: Int, y: Int) = pressure[i(x, y)]
    fun rainMmThisUpdate(x: Int, y: Int) = rain[i(x, y)]
    fun drainageMmThisUpdate(x: Int, y: Int) = drainage[i(x, y)]
    fun overflowMmThisUpdate(x: Int, y: Int) = overflow[i(x, y)]
    fun recentRainMmPerYear(x: Int, y: Int) = climateRain[i(x, y)]
    fun temperatureC(x: Int, y: Int) = temperature[i(x, y)] - 273.15
    fun windEastMetersPerSecond(x: Int, y: Int) = east[i(x, y)]
    fun windSouthMetersPerSecond(x: Int, y: Int) = south[i(x, y)]
    fun windSpeedMetersPerSecond(x: Int, y: Int) = hypot(east[i(x, y)], south[i(x, y)])
    private val sunlight = PlanetarySunlight(width, height).apply { update(elapsedSeconds) }
    fun nightOpacity(x: Int, y: Int) = sunlight.nightOpacity(x, y)
    fun snowWaterMm(x: Int, y: Int) = snow[i(x, y)]
    fun soilWaterMm(x: Int, y: Int) = soil[i(x, y)]
    fun soilCapacityMm(x: Int, y: Int) = capacity[i(x, y)]
    fun soilMoisture(x: Int, y: Int) = soilWaterMm(x, y) / soilCapacityMm(x, y)
}
