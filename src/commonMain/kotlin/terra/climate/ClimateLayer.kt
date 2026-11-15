package terra.climate

enum class ClimateLayer(val key: Char) {
    HUMIDITY('1'),
    CLOUDS('2'),
    PRECIPITATION('3'),
    PRESSURE('4'),
    TEMPERATURE('5'),
    DAY_NIGHT('8'),
    WIND('9'),
    ;

}
