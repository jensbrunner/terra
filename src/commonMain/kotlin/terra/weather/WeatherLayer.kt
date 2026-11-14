package terra.weather

enum class WeatherLayer(val key: Char) {
    HUMIDITY('1'),
    CLOUDS('2'),
    PRECIPITATION('3'),
    CLIMATE_RAIN('4'),
    ARIDITY('Q'),
    MEAN_TEMPERATURE('T'),
    AIR_TEMPERATURE('5'),
    ANNUAL_PRECIPITATION('6'),
    SURFACE_TEMPERATURE('7'),
    DAY_NIGHT('8'),
    WIND('9'),
    PRESSURE_ANOMALY('P'),
    RELATIVE_VORTICITY('V'),
    ;

    val resolvedDynamicsOnly get() = this == PRESSURE_ANOMALY || this == RELATIVE_VORTICITY
}
