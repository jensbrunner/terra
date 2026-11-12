package terra.render

import korlibs.image.color.Colors
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.sqrt
import kotlin.math.sign
import kotlin.math.roundToInt
import korlibs.image.color.RGBA
import terra.weather.WeatherLayer
import terra.weather.WeatherModel

private val humidityPalette = listOf("#a34d36", "#d7a84e", "#e9e0a1", "#91c889", "#269d91", "#4dc6df")
private val cloudPalette = listOf("#101c2b", "#34475c", "#798796", "#c4c8ca", "#f6f6ef")
private val precipitationPalette = listOf("#3155b7", "#238fd0", "#24c4c0", "#62d17f", "#e6df4b", "#f39435", "#df3f45", "#a92770")
private val annualPrecipitationPalette =
    listOf("#9d582f", "#d9ae55", "#e9df91", "#8fc57f", "#27a899", "#3279a5", "#806bdd")
private val climateRainPalette = listOf("#9d582f", "#d9ae55", "#e9df91", "#8fc57f", "#27a899", "#3279a5", "#806bdd")
private val temperaturePalette = listOf("#536eeb", "#299bc4", "#a8d4c0", "#f0dfa0", "#f29a45", "#d84a3c", "#a93359")
private val dynamicsPalette = listOf("#285c9d", "#73afd0", "#edf1e8", "#df9b81", "#ad423e")

internal data class WeatherLegend(val title: String, val colors: List<String>, val low: String, val middle: String, val high: String,
    val smooth: Boolean = false) {
    fun colorAt(fraction: Double): RGBA = if (smooth) interpolatePalette(fraction, colors) else
        Colors[colors[(fraction.coerceIn(0.0, 1.0) * (colors.size - 1)).toInt()]]
}

/** Uses the same continuous palette and fixed scale as the map. */
internal fun weatherLegend(layer: WeatherLayer, representative: Boolean = false): WeatherLegend? = when (layer) {
    WeatherLayer.HUMIDITY -> WeatherLegend("Relative humidity · %", humidityPalette, "0", "50", "100", smooth = true)
    WeatherLayer.PRECIPITATION -> WeatherLegend(
        if (representative) "Rain rate · wet-phase peak estimate · mm/h" else "Rain rate · substep peak · mm/h",
        precipitationPalette, "0", "3", "12+", smooth = true
    )
    WeatherLayer.CLIMATE_RAIN -> WeatherLegend("30-day mean rainfall · mm/h", climateRainPalette, "0", "6", "12+", smooth = true)
    WeatherLayer.AIR_TEMPERATURE -> WeatherLegend("Air temperature · °C", temperaturePalette, "−20", "7.5", "35+", smooth = true)
    WeatherLayer.SURFACE_TEMPERATURE -> WeatherLegend("Surface temperature · °C", temperaturePalette, "−20", "7.5", "35+", smooth = true)
    WeatherLayer.ANNUAL_PRECIPITATION -> WeatherLegend("Annual rainfall EMA · mm/year", annualPrecipitationPalette, "0", "1250", "2500+", smooth = true)
    WeatherLayer.PRESSURE_ANOMALY -> WeatherLegend("Model pressure anomaly · %", dynamicsPalette, "−5", "0", "+5", smooth = true)
    WeatherLayer.RELATIVE_VORTICITY -> WeatherLegend("Relative curl · 10⁻⁵ s⁻¹ · log", dynamicsPalette, "−10", "0", "+10", smooth = true)
    else -> null
}

internal fun tileColor(layer: WeatherLayer, weather: WeatherModel, x: Int, y: Int) =
    when (layer) {
        WeatherLayer.HUMIDITY -> ramp(weather.relativeHumidity(x, y), humidityPalette)
        WeatherLayer.CLOUDS -> ramp(weather.cloudCover(x, y), cloudPalette)
        WeatherLayer.PRECIPITATION -> precipitationColor(weather.peakSubstepPrecipitationMmPerHour(x, y))
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

/** Display-only mapping: sqrt makes light rain visible while retaining room for heavy rates. */
internal fun precipitationColor(rainMmPerHour: Double) =
    ramp(sqrt((rainMmPerHour / MAX_RAIN_DISPLAY_MM_PER_HOUR).coerceIn(0.0, 1.0)), precipitationPalette)

internal fun precipitationOpacity(rainMmPerHour: Double): Double =
    if (rainMmPerHour <= 0.0) 0.0 else 0.62 + 0.32 * smoothstep(0.05, 3.0, rainMmPerHour)

internal fun weatherOverlayOpacity(layer: WeatherLayer, weather: WeatherModel, x: Int, y: Int): Double = when (layer) {
    WeatherLayer.PRECIPITATION -> {
        val rain = weather.peakSubstepPrecipitationMmPerHour(x, y)
        precipitationOpacity(rain)
    }
    WeatherLayer.PRESSURE_ANOMALY, WeatherLayer.RELATIVE_VORTICITY -> 0.78
    else -> 0.68
}

private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

private const val MAX_RAIN_DISPLAY_MM_PER_HOUR = 12.0

private fun ramp(value: Double, palette: List<String>) = interpolatePalette(value, palette)
