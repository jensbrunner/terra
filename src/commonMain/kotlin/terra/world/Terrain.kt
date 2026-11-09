package terra.world

enum class Terrain(val label: String) {
    PLAINS("Plains"),
    MOUNTAINS("Mountains"),
    WOODS("Woods"),
    DESERT("Desert"),
    OCEAN("Ocean")
}

data class Cell(
    val elevation: Double,
    val meanTemperatureC: Double,
    val moistureAvailability: Double,
    val terrain: Terrain
)

data class TerrainGenerationSettings(
    val baseElevation: Double = 0.29,
    val continentScale: Double = 85.0,
    val continentOctaves: Int = 4,
    val continentStrength: Double = 0.56,
    val regionalScale: Double = 13.0,
    val regionalOctaves: Int = 4,
    val regionalStrength: Double = 0.08,
    val detailScale: Double = 6.0,
    val detailOctaves: Int = 3,
    val detailStrength: Double = 0.02,
    val noisePersistence: Double = 0.5,
    val noiseLacunarity: Double = 2.0,
    val seaLevel: Double = 0.305,
    val mountainThreshold: Double = 0.6,

    val baselineAnnualPrecipitationMm: Double = 1000.0,

    val basePotentialEvaporationMm: Double = 250.0,
    val potentialEvaporationPerDegreeMm: Double = 35.0,
    val desertMoistureThreshold: Double = 0.46,
    val woodsMoistureThreshold: Double = 0.60,
    val woodsMinTemperatureC: Double = -5.0,
    val woodsMaxTemperatureC: Double = 31.0,
    val equatorTemperatureC: Double = 30.0,
    val poleTemperatureDropC: Double = 45.0,
    val elevationCoolingCOPerUnit: Double = 30.0
)
