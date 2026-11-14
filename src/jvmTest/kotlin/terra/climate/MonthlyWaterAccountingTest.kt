package terra.climate

import kotlin.test.*
import terra.world.*

class MonthlyWaterAccountingTest {
    private val width = 12
    private val height = 8
    private val config = ClimateConfig(width = 6, height = 4)
    private val world = World.withElevation(9, width, height, TerrainGenerationSettings(),
        DoubleArray(width * height) { if (it % width == 0) 0.1 else 0.36 })
    private val geography = ClimateGeography(world, config)
    private val tileAreaM2 = config.domainWidthMeters * config.domainHeightMeters / (width.toDouble() * height)
    private val land = (0 until width * height).filter { world.isLand(it % width, it / width) }

    private fun atlas(rain: Double, pet: Double, temperature: Double = 20.0): SeasonalClimateAtlas {
        fun field(value: Double) = Array(12) { DoubleArray(width * height) { value } }
        return SeasonalClimateAtlas(geography, SolarConfig(), field(temperature), field(rain), field(pet),
            field(0.5), field(0.0), field(0.0), 0.0, 0.0, 0, 0.0)
    }

    private fun advance(surface: LandscapeWater, atlas: SeasonalClimateAtlas, fraction: Double): Double {
        val before = surface.totalWaterKg
        val boundary = surface.exportedWaterKg
        val evaporation = surface.evolveMonth(atlas, 0, fraction)
        val precipitation = land.sumOf { atlas.rainfall.amountMm(0, it, 0.0, fraction) } * tileAreaM2
        assertEquals(before + precipitation,
            surface.totalWaterKg + evaporation + surface.exportedWaterKg - boundary,
            (before + precipitation).coerceAtLeast(1.0) * 1e-11)
        for (i in land) {
            assertTrue(surface.soil[i] >= 0.0, "Negative soil water at $i: ${surface.soil[i]}")
            assertTrue(surface.soil[i] <= surface.soilCapacityMm[i] + 1e-9)
        }
        assertTrue(evaporation >= 0.0)
        return evaporation / tileAreaM2
    }

    @Test fun heavyRainProducesRunoffAndRecharge() {
        val surface = LandscapeWater(world, tileAreaM2)
        for (i in land) surface.soil[i] = surface.soilCapacityMm[i] * 0.48
        val evaporated = advance(surface, atlas(rain = 1200.0, pet = 500.0), 1.0)
        assertTrue(surface.overflow.sum() > 0.0)
        assertTrue(surface.drainage.sum() > 0.0)
        assertEquals(land.size * 500.0, evaporated, 1e-8)
        assertTrue(land.any { surface.surfaceWater.snapshot().dischargeMmTilesPerDay(it % width, it / width) > 0.0 })
    }

    @Test fun smallRainOnDrySoilIsAvailableToEvaporationWithoutArtificialRunoff() {
        val surface = LandscapeWater(world, tileAreaM2)
        assertEquals(land.size * 5.0, advance(surface, atlas(rain = 5.0, pet = 100.0), 1.0), 1e-9)
        assertEquals(0.0, surface.overflow.sum())
        assertEquals(0.0, surface.drainage.sum())
        assertEquals(0.0, surface.soil.sum())
    }

    @Test fun monthlyAndPartialIntervalsConserveWaterAcrossDryWetAndFrozenConditions() {
        for (fraction in listOf(0.125, 0.5, 1.0)) {
            for (rain in listOf(0.0, 5.0, 400.0, 10000.0)) {
                for (pet in listOf(0.0, 500.0)) {
                    for (temperature in listOf(-10.0, 20.0)) {
                        val surface = LandscapeWater(world, tileAreaM2)
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

    @Test fun calendarMonthHydrologyIsIndependentOfTransactionBoundaries() {
        for (temperature in listOf(-10.0, 0.5, 20.0)) for (startFraction in listOf(0.0, 0.05, 0.3)) {
            val forcing = atlas(400.0, 500.0, temperature)
            fun initialized() = LandscapeWater(world, tileAreaM2).also { surface ->
                for (i in land) surface.soil[i] = surface.soilCapacityMm[i] * 0.75
            }
            val monthSeconds = forcing.solar.yearSeconds / 12
            // Independently integrate the visible rate, including a nonzero calendar offset.
            val samples = 4096
            val numerical = (0 until samples).sumOf { sample ->
                forcing.rainfall.rateMmPerHour(1, 0, (startFraction + (1 - startFraction) * (sample + 0.5) / samples) * monthSeconds)
            } * (1 - startFraction) * monthSeconds / (samples * 3600)
            assertEquals(forcing.rainfall.amountMm(0, 1, startFraction, 1.0), numerical, 1e-3)
            val whole = initialized()
            val startSeconds = startFraction * monthSeconds
            val duration = (1 - startFraction) * monthSeconds
            ClimateEvolution(world, whole, startSeconds).advance(forcing, duration)
            assertEquals(forcing.rainfall.amountMm(0, 1, startFraction, 1.0), whole.rain[1], 1e-9)
            for (parts in listOf(listOf(0.5, 0.5), listOf(0.03, 0.04, 0.08, 0.22, 0.13, 0.5))) {
                val split = initialized()
                val evolution = ClimateEvolution(world, split, startSeconds)
                val totals = Array(3) { DoubleArray(width * height) }
                for (part in parts) {
                    val diagnostics = evolution.advance(forcing, part * duration)
                    assertTrue(kotlin.math.abs(diagnostics.relativeWaterResidual) < 1e-10)
                    for ((f, flux) in arrayOf(split.rain, split.drainage, split.overflow).withIndex()) {
                        for (i in flux.indices) totals[f][i] += flux[i]
                    }
                }
                val expected = whole.captureClimateState(); val actual = split.captureClimateState()
                val expectedArrays = arrayOf(expected.soil, expected.snow, expected.availability, expected.drought,
                    expected.water.groundwater, expected.water.channels, expected.water.recharge,
                    expected.water.runoff, expected.water.lakes, expected.water.discharge,
                    whole.rain, whole.drainage, whole.overflow)
                val actualArrays = arrayOf(actual.soil, actual.snow, actual.availability, actual.drought,
                    actual.water.groundwater, actual.water.channels, actual.water.recharge,
                    actual.water.runoff, actual.water.lakes, actual.water.discharge,
                    totals[0], totals[1], totals[2])
                for (f in expectedArrays.indices) for (i in expectedArrays[f].indices) {
                    assertEquals(expectedArrays[f][i], actualArrays[f][i], 1e-8)
                }
                assertEquals(expected.boundary, actual.boundary, 1e-8)
            }
        }
    }
}
