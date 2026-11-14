package terra.weather

/** Immutable display view: coarse atmospheric arrays stay coarse, fine surface arrays stay fine. */
class WeatherPresentation private constructor(
    private val source: WeatherModel, override val elapsedSeconds: Double,
    override val presentationIdentity: Any
) : WeatherModel by source {
    override fun step(seconds: Double): Unit = error("Presentation cannot advance physics")

    companion object {
        fun capture(source: DynamicalWeather, clockSeconds: Double, identity: Any): WeatherPresentation =
            WeatherPresentation(source.captureReadOnly(), clockSeconds, identity)
    }
}
