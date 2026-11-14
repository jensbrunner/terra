package terra.weather

import kotlin.test.*
import terra.atmosphere.*
import terra.world.*

class MonthlyWaterAccountingTest {
    private val width = 12
    private val height = 8
    private val config = DryAtmosphereConfig(width = 6, height = 4)
    private val world = World.withElevation(9, width, height, TerrainGenerationSettings(),
        DoubleArray(width * height) { if (it % width == 0) 0.1 else 0.36 })
    private val geography = ClimateGeography(world, config)
    private val mapping = AtmosphereTileMapping(width, height, config)
    private val land = (0 until width * height).filter { world.isLand(it % width, it / width) }

    private fun atlas(rain: Double, pet: Double, temperature: Double = 20.0): SeasonalClimateAtlas {
        fun field(value: Double) = Array(12) { DoubleArray(width * height) { value } }
        return SeasonalClimateAtlas(geography, SolarConfig(), field(temperature), field(rain), field(pet),
            field(0.5), field(0.0), field(0.0), 0.0, 0.0, 0, 0.0)
    }

    private fun advance(surface: TerrainMoistSurface, atlas: SeasonalClimateAtlas, fraction: Double): Double {
        val before = surface.totalWaterKg
        val boundary = surface.exportedWaterKg
        val evaporation = surface.evolveMonth(atlas, 0, fraction)
        val precipitation = land.sumOf { atlas.rain[0][it] * fraction } * mapping.tileAreaM2
        assertEquals(before + precipitation,
            surface.totalWaterKg + evaporation + surface.exportedWaterKg - boundary,
            (before + precipitation).coerceAtLeast(1.0) * 1e-11)
        for (i in land) {
            assertTrue(surface.soil[i] >= 0.0, "Negative soil water at $i: ${surface.soil[i]}")
            assertTrue(surface.soil[i] <= surface.soilCapacityMm[i] + 1e-9)
        }
        assertTrue(evaporation >= 0.0)
        return evaporation / mapping.tileAreaM2
    }

    @Test fun concentratedRainProducesRunoffEvenWhenMonthlyPetExceedsRain() {
        val surface = TerrainMoistSurface(world, mapping)
        for (i in land) surface.soil[i] = surface.soilCapacityMm[i] * 0.48
        val evaporated = advance(surface, atlas(rain = 400.0, pet = 500.0), 1.0)
        assertTrue(surface.overflow.sum() > 0.0)
        assertTrue(surface.drainage.sum() > 0.0)
        assertTrue(evaporated < land.size * 500.0)
        assertTrue(land.any { surface.surfaceWater.snapshot().dischargeMmTilesPerDay(it % width, it / width) > 0.0 })
    }

    @Test fun smallRainOnDrySoilIsAvailableToEvaporationWithoutArtificialRunoff() {
        val surface = TerrainMoistSurface(world, mapping)
        assertEquals(land.size * 5.0, advance(surface, atlas(rain = 5.0, pet = 100.0), 1.0), 1e-9)
        assertEquals(0.0, surface.overflow.sum())
        assertEquals(0.0, surface.drainage.sum())
        assertEquals(0.0, surface.soil.sum())
    }

    @Test fun detailedAndClimateForcingUseTheSameSoilRulesForEquivalentWetAndDryPeriods() {
        val detailed = TerrainMoistSurface(world, mapping)
        val climate = TerrainMoistSurface(world, mapping)
        for (i in land) {
            detailed.soil[i] = detailed.soilCapacityMm[i] * 0.48
            climate.soil[i] = detailed.soil[i]
        }
        val monthSeconds = 365.0 / 12 * 86400
        for (cell in 0 until config.cellCount) detailed.receiveRain(cell, 400.0, 0.0)
        detailed.finishStep(monthSeconds * 0.1)
        detailed.finishStep(monthSeconds * 0.9)
        climate.evolveMonth(atlas(rain = 400.0, pet = 0.0), 0, 1.0)
        for (i in land) {
            assertEquals(detailed.soil[i], climate.soil[i], 1e-9)
            assertEquals(detailed.drainage[i], climate.drainage[i], 1e-9)
            assertEquals(detailed.overflow[i], climate.overflow[i], 1e-9)
        }
    }

    @Test fun monthlyAndPartialIntervalsConserveWaterAcrossDryWetAndFrozenConditions() {
        for (fraction in listOf(0.125, 0.5, 1.0)) {
            for (rain in listOf(0.0, 5.0, 400.0, 10000.0)) {
                for (pet in listOf(0.0, 500.0)) {
                    for (temperature in listOf(-10.0, 20.0)) {
                        val surface = TerrainMoistSurface(world, mapping)
                        for (i in land) surface.soil[i] = surface.soilCapacityMm[i] * (i % 3) / 2
                        val forcing = atlas(rain, pet, temperature)
                        repeat(2) { advance(surface, forcing, fraction) }
                        // Warm subsequent conditions also exercise melting the frozen inventory.
                        advance(surface, atlas(0.0, pet), fraction)
                    }
                }
            }
        }
    }
}
