package terra.render

import korlibs.image.color.Colors
import kotlin.math.sqrt
import terra.weather.WeatherLayer
import terra.weather.WeatherModel

private val humidityPalette = listOf("#c49b66", "#e4d7a1", "#8ec6a1", "#469ea5", "#194c82")
private val cloudPalette = listOf("#101c2b", "#34475c", "#798796", "#c4c8ca", "#f6f6ef")
private val precipitationPalette = listOf("#182a45", "#2a78b7", "#42c4b2", "#d7d563", "#ee8a3b", "#db3636")
private val annualPrecipitationPalette =
    listOf("#d6c58f", "#c2d286", "#74b78a", "#3e9b9d", "#326a9a", "#594b91")
private val climateRainPalette = listOf("#bb9d67", "#d8cc8b", "#72ae82", "#388f9e", "#294f91")
private val temperaturePalette = listOf("#344f9a", "#73aeb5", "#d8d5a3", "#e6a04f", "#b84b3f")

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
    }

/** Display-only mapping: sqrt makes light rain visible; 3 mm/h fills the color ramp. */
private fun precipitationDisplayValue(rainMmPerHour: Double) =
    sqrt((rainMmPerHour / 3.0).coerceIn(0.0, 1.0))

private fun ramp(value: Double, palette: List<String>) = Colors[
    palette[(value.coerceIn(0.0, 1.0) * (palette.size - 1)).toInt()]
]
