package terra.weather

/** Copy-on-publication tile view. Mutation hooks fail rather than touching worker-owned state. */
private class TileWeatherPresentation private constructor(
    override val width: Int, override val height: Int, override val elapsedSeconds: Double,
    override val presentationIdentity: Any, private val fields: Array<DoubleArray>,
    override val fieldSemantics: WeatherFieldSemantics
) : WeatherModel {
    private fun at(field: Int, x: Int, y: Int) = fields[field][y * width + x]
    override fun relativeHumidity(x: Int, y: Int) = at(0, x, y)
    override fun cloudCover(x: Int, y: Int) = at(1, x, y)
    override fun precipitationMmPerHour(x: Int, y: Int) = at(2, x, y)
    override fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int) = at(3, x, y)
    override fun rainMmThisUpdate(x: Int, y: Int) = at(4, x, y)
    override fun drainageMmThisUpdate(x: Int, y: Int) = at(5, x, y)
    override fun overflowMmThisUpdate(x: Int, y: Int) = at(6, x, y)
    override fun lakeDirectRainMmThisUpdate(x: Int, y: Int) = at(7, x, y)
    override fun lakePotentialEvaporationMmThisUpdate(x: Int, y: Int) = at(8, x, y)
    override fun thirtyDayRainMmPerHour(x: Int, y: Int) = at(9, x, y)
    override fun annualizedRainMmPerYear(x: Int, y: Int) = at(10, x, y)
    override fun solarInputFactor(x: Int, y: Int) = at(11, x, y)
    override fun surfaceTemperatureC(x: Int, y: Int) = at(12, x, y)
    override fun airTemperatureC(x: Int, y: Int) = at(13, x, y)
    override fun windUTilesPerDay(x: Int, y: Int) = at(14, x, y)
    override fun windVTilesPerDay(x: Int, y: Int) = at(15, x, y)
    override fun nightOpacity(x: Int, y: Int) = at(16, x, y)
    override fun atmosphericWaterMm(x: Int, y: Int) = at(17, x, y)
    override fun cloudWaterMm(x: Int, y: Int) = at(18, x, y)
    override fun soilWaterMm(x: Int, y: Int) = at(19, x, y)
    override fun soilMoisture(x: Int, y: Int) = at(20, x, y)
    override fun step(seconds: Double): Unit = error("Presentation cannot advance physics")
    override fun updateLakeWaterMask(mask: BooleanArray): Unit = error("Presentation is immutable")
    override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double): Unit = error("Presentation is immutable")
    override fun hydrologyAccepted(): Unit = error("Presentation cannot accept fluxes")

    companion object {
        fun capture(source: WeatherModel, clockSeconds: Double, identity: Any): TileWeatherPresentation {
            val readers: Array<(Int, Int) -> Double> = arrayOf(source::relativeHumidity, source::cloudCover,
                source::precipitationMmPerHour, source::peakSubstepPrecipitationMmPerHour, source::rainMmThisUpdate,
                source::drainageMmThisUpdate, source::overflowMmThisUpdate, source::lakeDirectRainMmThisUpdate,
                source::lakePotentialEvaporationMmThisUpdate, source::thirtyDayRainMmPerHour, source::annualizedRainMmPerYear,
                source::solarInputFactor, source::surfaceTemperatureC, source::airTemperatureC,
                source::windUTilesPerDay, source::windVTilesPerDay, source::nightOpacity,
                source::atmosphericWaterMm, source::cloudWaterMm, source::soilWaterMm, source::soilMoisture)
            val fields = Array(readers.size) { field -> DoubleArray(source.width * source.height) { tile ->
                readers[field](tile % source.width, tile / source.width)
            } }
            return TileWeatherPresentation(source.width, source.height, clockSeconds, identity, fields, source.fieldSemantics)
        }
    }
}

/** Immutable display view: coarse atmospheric arrays stay coarse, fine surface arrays stay fine. */
class WeatherPresentation private constructor(
    private val source: WeatherModel, override val elapsedSeconds: Double,
    override val presentationIdentity: Any
) : WeatherModel by source {
    override fun step(seconds: Double): Unit = error("Presentation cannot advance physics")
    override fun updateLakeWaterMask(mask: BooleanArray): Unit = error("Presentation is immutable")
    override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double): Unit = error("Presentation is immutable")
    override fun hydrologyAccepted(): Unit = error("Presentation cannot accept fluxes")

    companion object {
        fun capture(source: WeatherModel, clockSeconds: Double, identity: Any): WeatherPresentation =
            WeatherPresentation(if (source is DynamicalWeather) source.captureReadOnly() else
                TileWeatherPresentation.capture(source, clockSeconds, identity), clockSeconds, identity)
    }
}
