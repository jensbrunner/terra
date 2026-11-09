package terra.world

import kotlin.math.max

/** Maps sampled long-term climate to the existing land-cover terrain types. */
object ClimateBiomeClassifier {
    /**
     * Classifies ecological cover without changing physical geography. The input terrain supplies
     * immutable ocean and mountain overrides; elevation and the settings thresholds also preserve
     * those overrides when called with another land-cover type.
     */
    fun classifyBiome(
        physicalTerrain: Terrain,
        elevation: Double,
        settings: TerrainGenerationSettings,
        annualPrecipitationMm: Double,
        meanAirTemperatureC: Double
    ): Terrain {
        if (physicalTerrain == Terrain.OCEAN || elevation < settings.seaLevel) return Terrain.OCEAN
        if (physicalTerrain == Terrain.MOUNTAINS || elevation > settings.mountainThreshold) {
            return Terrain.MOUNTAINS
        }

        require(annualPrecipitationMm.isFinite()) { "Annual precipitation must be finite" }
        require(meanAirTemperatureC.isFinite()) { "Mean air temperature must be finite" }

        val potentialEvaporationMmPerYear =
            (BASE_POTENTIAL_EVAPORATION_MM_PER_YEAR +
                EVAPORATION_INCREASE_PER_WARM_DEGREE_MM_PER_YEAR * max(meanAirTemperatureC, 0.0))
                .coerceIn(MIN_POTENTIAL_EVAPORATION_MM_PER_YEAR, MAX_POTENTIAL_EVAPORATION_MM_PER_YEAR)
        val aridityRatio = annualPrecipitationMm.coerceAtLeast(0.0) /
            potentialEvaporationMmPerYear

        val aridity = when {
            aridityRatio < VERY_DRY_ARIDITY_RATIO -> Aridity.VERY_DRY
            aridityRatio < SEMI_ARID_ARIDITY_RATIO -> Aridity.SEMI_ARID
            aridityRatio < HUMID_ARIDITY_RATIO -> Aridity.MODERATE
            else -> Aridity.HUMID
        }
        val thermalZone = when {
            meanAirTemperatureC < VERY_COLD_MAX_TEMPERATURE_C -> ThermalZone.VERY_COLD
            meanAirTemperatureC < COOL_MAX_TEMPERATURE_C -> ThermalZone.COOL
            meanAirTemperatureC < TEMPERATE_MAX_TEMPERATURE_C -> ThermalZone.TEMPERATE
            else -> ThermalZone.WARM
        }

        return when (thermalZone) {
            ThermalZone.VERY_COLD -> Terrain.TUNDRA
            ThermalZone.COOL -> when (aridity) {
                Aridity.VERY_DRY -> Terrain.DESERT
                Aridity.SEMI_ARID -> Terrain.PLAINS
                Aridity.MODERATE, Aridity.HUMID -> Terrain.WOODS
            }
            ThermalZone.TEMPERATE, ThermalZone.WARM -> when (aridity) {
                Aridity.VERY_DRY -> Terrain.DESERT
                Aridity.SEMI_ARID, Aridity.MODERATE -> Terrain.PLAINS
                Aridity.HUMID -> Terrain.WOODS
            }
        }
    }

    private enum class Aridity { VERY_DRY, SEMI_ARID, MODERATE, HUMID }
    private enum class ThermalZone { VERY_COLD, COOL, TEMPERATE, WARM }

    private const val BASE_POTENTIAL_EVAPORATION_MM_PER_YEAR = 350.0
    private const val EVAPORATION_INCREASE_PER_WARM_DEGREE_MM_PER_YEAR = 45.0
    private const val MIN_POTENTIAL_EVAPORATION_MM_PER_YEAR = 250.0
    private const val MAX_POTENTIAL_EVAPORATION_MM_PER_YEAR = 1800.0
    private const val VERY_DRY_ARIDITY_RATIO = 0.20
    private const val SEMI_ARID_ARIDITY_RATIO = 0.55
    private const val HUMID_ARIDITY_RATIO = 1.10
    private const val VERY_COLD_MAX_TEMPERATURE_C = -6.0
    private const val COOL_MAX_TEMPERATURE_C = 6.0
    private const val TEMPERATE_MAX_TEMPERATURE_C = 18.0
}
