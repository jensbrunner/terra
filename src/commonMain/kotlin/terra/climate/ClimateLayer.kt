package terra.climate

enum class ClimateLayer(val key: Char) {
    HUMIDITY('1'),
    CLOUDS('2'),
    PRECIPITATION('3'),
    CLIMATE_RAIN('4'),
    ARIDITY('Q'),
    MEAN_TEMPERATURE('T'),
    TEMPERATURE('5'),
    ANNUAL_PRECIPITATION('6'),
    DAY_NIGHT('8'),
    WIND('9'),
    ;

}
