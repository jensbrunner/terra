package terra.render

import korlibs.image.color.Colors
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.sign
import kotlin.math.roundToInt
import korlibs.image.color.RGBA
import terra.weather.WeatherLayer
import terra.weather.WeatherModel

private val humidityPalette = listOf("#c49b66", "#e4d7a1", "#8ec6a1", "#469ea5", "#194c82")
private val cloudPalette = listOf("#101c2b", "#34475c", "#798796", "#c4c8ca", "#f6f6ef")
private val precipitationPalette = listOf("#182a45", "#2a78b7", "#42c4b2", "#d7d563", "#ee8a3b", "#db3636")
private val annualPrecipitationPalette =
    listOf("#d6c58f", "#c2d286", "#74b78a", "#3e9b9d", "#326a9a", "#594b91")
private val climateRainPalette = listOf("#bb9d67", "#d8cc8b", "#72ae82", "#388f9e", "#294f91")
private val temperaturePalette = listOf("#344f9a", "#73aeb5", "#d8d5a3", "#e6a04f", "#b84b3f")
private val dynamicsPalette = listOf("#285c9d", "#73afd0", "#edf1e8", "#df9b81", "#ad423e")

internal data class WeatherLegend(val title: String, val colors: List<String>, val low: String, val middle: String, val high: String,
    val smooth: Boolean = false) {
    fun colorAt(fraction: Double): RGBA = if (smooth) interpolatePalette(fraction, colors) else
        Colors[colors[(fraction.coerceIn(0.0, 1.0) * (colors.size - 1)).toInt()]]
}

/** Uses exactly the same palette and scale as the map, including rain's square-root scale. */
internal fun weatherLegend(layer: WeatherLayer): WeatherLegend? = when (layer) {
    WeatherLayer.HUMIDITY -> WeatherLegend("Relative humidity", humidityPalette, "0%", "50%", "100%")
    WeatherLayer.PRECIPITATION -> WeatherLegend("Rain · mm/h", precipitationPalette, "0", "0.75", "3+")
    WeatherLayer.CLIMATE_RAIN -> WeatherLegend("30-day rain EMA · mm/h", climateRainPalette, "0", "6", "12+")
    WeatherLayer.AIR_TEMPERATURE -> WeatherLegend("Air temperature · °C", temperaturePalette, "−20", "7.5", "35+")
    WeatherLayer.SURFACE_TEMPERATURE -> WeatherLegend("Surface temperature · °C", temperaturePalette, "−20", "7.5", "35+")
    WeatherLayer.ANNUAL_PRECIPITATION -> WeatherLegend("Rain EMA · mm/year", annualPrecipitationPalette, "0", "1250", "2500+")
    WeatherLayer.PRESSURE_ANOMALY -> WeatherLegend("Model pressure anomaly · %", dynamicsPalette, "−5", "0", "+5", smooth = true)
    WeatherLayer.RELATIVE_VORTICITY -> WeatherLegend("Relative curl · 10⁻⁵ s⁻¹ · log", dynamicsPalette, "−10", "0", "+10", smooth = true)
    else -> null
}

internal fun tileColor(layer: WeatherLayer, weather: WeatherModel, x: Int, y: Int) =
    when (layer) {
        WeatherLayer.HUMIDITY -> ramp(weather.relativeHumidity(x, y), humidityPalette)
        WeatherLayer.CLOUDS -> ramp(weather.cloudCover(x, y), cloudPalette)
        WeatherLayer.PRECIPITATION -> ramp(
            precipitationDisplayValue(weather.precipitationMmPerHour(x, y)),
            precipitationPalette
        )
        WeatherLayer.CLIMATE_RAIN -> ramp(weather.thirtyDayRainMmPerHour(x, y) / 12.0, climateRainPalette)
        WeatherLayer.AIR_TEMPERATURE -> ramp((weather.airTemperatureC(x, y) + 20.0) / 55.0, temperaturePalette)
        WeatherLayer.SURFACE_TEMPERATURE -> ramp((weather.surfaceTemperatureC(x, y) + 20.0) / 55.0, temperaturePalette)
        WeatherLayer.ANNUAL_PRECIPITATION -> ramp(weather.annualizedRainMmPerYear(x, y) / 2_500.0, annualPrecipitationPalette)
        WeatherLayer.DAY_NIGHT -> Colors["#071321"]
        WeatherLayer.WIND -> Colors["#f3f1d4"]
        WeatherLayer.PRESSURE_ANOMALY -> weather.pressureAnomalyPercent(x, y)?.let { signedDynamicsColor(it / 5.0) } ?: Colors.TRANSPARENT
        WeatherLayer.RELATIVE_VORTICITY -> weather.relativeVorticityPerSecond(x, y)?.let {
            // Fixed signed-log sensitivity, not a framewise rescale. Knee 10⁻⁵; saturation 10⁻⁴ s⁻¹.
            signedDynamicsColor(sign(it) * ln1p(abs(it) / 1e-5) / ln1p(10.0))
        } ?: Colors.TRANSPARENT
    }

private fun signedDynamicsColor(value: Double): RGBA = interpolatePalette((value.coerceIn(-1.0, 1.0) + 1) / 2, dynamicsPalette)

private fun interpolatePalette(value: Double, colors: List<String>): RGBA {
    val position = value.coerceIn(0.0, 1.0) * (colors.size - 1)
    val index = position.toInt().coerceAtMost(colors.size - 2)
    val fraction = position - index
    val a = Colors[colors[index]]; val b = Colors[colors[index + 1]]
    return RGBA((a.r + (b.r - a.r) * fraction).roundToInt(),
        (a.g + (b.g - a.g) * fraction).roundToInt(), (a.b + (b.b - a.b) * fraction).roundToInt(), 255)
}

/** Display-only mapping: sqrt makes light rain visible; 3 mm/h fills the color ramp. */
private fun precipitationDisplayValue(rainMmPerHour: Double) =
    sqrt((rainMmPerHour / 3.0).coerceIn(0.0, 1.0))

private fun ramp(value: Double, palette: List<String>) = Colors[
    palette[(value.coerceIn(0.0, 1.0) * (palette.size - 1)).toInt()]
]
