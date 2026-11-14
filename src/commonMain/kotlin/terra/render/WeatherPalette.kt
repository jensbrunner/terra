package terra.render

import korlibs.image.color.Colors
import kotlin.math.sqrt
import kotlin.math.roundToInt
import korlibs.image.color.RGBA
import terra.climate.ClimateLayer
import terra.climate.EnvironmentSnapshot

private val humidityPalette = listOf("#a34d36", "#d7a84e", "#e9e0a1", "#91c889", "#269d91", "#4dc6df")
private val precipitationPalette = listOf("#3155b7", "#238fd0", "#24c4c0", "#62d17f", "#e6df4b", "#f39435", "#df3f45", "#a92770")
private val annualPrecipitationPalette =
    listOf("#9d582f", "#d9ae55", "#e9df91", "#8fc57f", "#27a899", "#3279a5", "#806bdd")
private val climateRainPalette = listOf("#9d582f", "#d9ae55", "#e9df91", "#8fc57f", "#27a899", "#3279a5", "#806bdd")
private val temperaturePalette = listOf("#536eeb", "#299bc4", "#a8d4c0", "#f0dfa0", "#f29a45", "#d84a3c", "#a93359")

internal data class WeatherLegend(val title: String, val colors: List<String>, val low: String, val middle: String, val high: String,
    val smooth: Boolean = false) {
    fun colorAt(fraction: Double): RGBA = if (smooth) interpolatePalette(fraction, colors) else
        Colors[colors[(fraction.coerceIn(0.0, 1.0) * (colors.size - 1)).toInt()]]
}

/** Uses the same continuous palette and fixed scale as the map. */
internal fun weatherLegend(layer: ClimateLayer): WeatherLegend? = when (layer) {
    ClimateLayer.ARIDITY -> WeatherLegend("Aridity · annual rain / PET", climateRainPalette, "0", "1", "2+", smooth = true)
    ClimateLayer.MEAN_TEMPERATURE -> WeatherLegend("Mean annual temperature · °C", temperaturePalette, "−20", "7.5", "35+", smooth = true)
    ClimateLayer.HUMIDITY -> WeatherLegend("Relative humidity · %", humidityPalette, "0", "50", "100", smooth = true)
    ClimateLayer.PRECIPITATION -> WeatherLegend(
        "Precipitation · mm/h · snow in white",
        precipitationPalette, "0", "3", "12+", smooth = true
    )
    ClimateLayer.CLIMATE_RAIN -> WeatherLegend("Monthly rainfall · mm/month", climateRainPalette, "0", "150", "300+", smooth = true)
    ClimateLayer.TEMPERATURE -> WeatherLegend("Air temperature · °C", temperaturePalette, "−20", "7.5", "35+", smooth = true)
    ClimateLayer.ANNUAL_PRECIPITATION -> WeatherLegend("Annual climate rainfall · mm/year", annualPrecipitationPalette, "0", "1250", "2500+", smooth = true)
    else -> null
}

internal fun tileColor(layer: ClimateLayer, weather: EnvironmentSnapshot, x: Int, y: Int) =
    when (layer) {
        ClimateLayer.ARIDITY -> ramp(weather.climateAridity(x, y) / 2, climateRainPalette)
        ClimateLayer.MEAN_TEMPERATURE -> ramp((weather.meanClimateTemperatureC(x, y) + 20) / 55, temperaturePalette)
        ClimateLayer.CLOUDS -> Colors["#eef3fa"]
        ClimateLayer.HUMIDITY -> ramp(weather.relativeHumidity(x, y), humidityPalette)
        ClimateLayer.PRECIPITATION -> precipitationColor(weather.precipitationMmPerHour(x, y),
            weather.atlas.rainfall.snowFraction(x, y, weather.elapsedSeconds))
        ClimateLayer.CLIMATE_RAIN -> ramp(weather.monthlyClimateRainMm(x, y) / 300.0, climateRainPalette)
        ClimateLayer.TEMPERATURE -> ramp((weather.temperatureC(x, y) + 20.0) / 55.0, temperaturePalette)
        ClimateLayer.ANNUAL_PRECIPITATION -> ramp(weather.annualizedRainMmPerYear(x, y) / 2_500.0, annualPrecipitationPalette)
        ClimateLayer.DAY_NIGHT -> Colors["#071321"]
        ClimateLayer.WIND -> Colors["#f3f1d4"]
    }

private fun interpolatePalette(value: Double, colors: List<String>): RGBA {
    val position = value.coerceIn(0.0, 1.0) * (colors.size - 1)
    val index = position.toInt().coerceAtMost(colors.size - 2)
    val fraction = position - index
    val a = Colors[colors[index]]; val b = Colors[colors[index + 1]]
    return RGBA((a.r + (b.r - a.r) * fraction).roundToInt(),
        (a.g + (b.g - a.g) * fraction).roundToInt(), (a.b + (b.b - a.b) * fraction).roundToInt(), 255)
}

/** Display-only mapping: sqrt makes light rain visible while retaining room for heavy rates. */
internal fun precipitationColor(rainMmPerHour: Double, snowFraction: Double = 0.0): RGBA {
    val rain = ramp(sqrt((rainMmPerHour / MAX_RAIN_DISPLAY_MM_PER_HOUR).coerceIn(0.0, 1.0)), precipitationPalette)
    val snow = snowFraction.coerceIn(0.0, 1.0)
    return RGBA((rain.r + (222 - rain.r) * snow).roundToInt(),
        (rain.g + (239 - rain.g) * snow).roundToInt(), (rain.b + (255 - rain.b) * snow).roundToInt(), 255)
}

internal fun precipitationOpacity(rainMmPerHour: Double): Double =
    smoothstep(0.0, 0.03, rainMmPerHour) * (0.62 + 0.32 * smoothstep(0.05, 3.0, rainMmPerHour))

internal fun weatherOverlayOpacity(layer: ClimateLayer, weather: EnvironmentSnapshot, x: Int, y: Int): Double = when (layer) {
    ClimateLayer.CLOUDS -> weather.cloudCover(x, y) * 0.88
    ClimateLayer.PRECIPITATION -> {
        val rain = weather.precipitationMmPerHour(x, y)
        precipitationOpacity(rain)
    }
    else -> 0.68
}

private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

private const val MAX_RAIN_DISPLAY_MM_PER_HOUR = 12.0

private fun ramp(value: Double, palette: List<String>) = interpolatePalette(value, palette)
