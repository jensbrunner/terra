package terra.world

enum class Terrain(val label: String) {
    PLAINS("Plains"),
    MOUNTAINS("Mountains"),
    WOODS("Woods"),
    DESERT("Desert"),
    STEPPE("Steppe"),
    OCEAN("Ocean"),
    TUNDRA("Tundra")
}

data class Cell(
    val elevation: Double,
    val meanTemperatureC: Double,
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
    val mountainRelief: Double = 0.32,
    val erosionStrength: Double = 0.45,

    val equatorTemperatureC: Double = 30.0,
    val poleTemperatureDropC: Double = 45.0,
    val elevationCoolingCOPerUnit: Double = 30.0
)
