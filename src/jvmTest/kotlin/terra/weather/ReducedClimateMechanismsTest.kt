package terra.weather

import terra.atmosphere.*
import terra.world.*
import kotlin.math.*
import kotlin.test.*

class ReducedClimateMechanismsTest {
    private val config = DryAtmosphereConfig(width = 24, height = 12)
    private val settings = TerrainGenerationSettings(baseElevation = 0.4, continentStrength = 0.0,
        regionalStrength = 0.0, detailStrength = 0.0)

    @Test
    fun nativeClimateUsesTheFinerConservativeMesh() {
        val grid = ClimateGrid(DryAtmosphereConfig())
        assertEquals(48, grid.config.width)
        assertEquals(36, grid.config.height)
        val fine = DoubleArray(grid.fine.cellCount) { 1.0 + (it % 17) }
        val coarse = DoubleArray(grid.count)
        grid.restrict(fine, coarse)
        assertEquals(fine.average(), coarse.average(), 1e-12)
        val restored = DoubleArray(fine.size)
        grid.prolongUniform(coarse, restored)
        assertEquals(fine.average(), restored.average(), 1e-12)
    }

    @Test
    fun reversingWindReversesTheMountainRainShadowAndTransportConservesWater() {
        fun run(ridge: Boolean, direction: Double): Pair<DoubleArray, DoubleArray> {
            val z = DoubleArray(config.cellCount) { i ->
                0.35 + if (ridge) 0.25 * exp(-((i % 24 - 12.0) / 1.5).pow(2)) else 0.0
            }
            val world = World.withElevation(42, 24, 12, settings, z)
            val surface = TerrainMoistSurface(world, AtmosphereTileMapping(24, 12, config))
            val atmosphere = MoistAtmosphere(config, surface = surface, initializeColumns = { _, _ ->
                MoistCell(surfaceTemperatureKelvin = 288.15, relativeHumidity = 0.0)
            })
            val grid = ClimateGrid(config)
            val model = ReducedClimate(grid, ClimateGeography(world, grid), surface, atmosphere)
            surface.beginUpdate(); model.exchange(1.0, 0.0)
            model.east.fill(6.0 * direction); model.north.fill(0.0)
            val q = DoubleArray(config.cellCount) { 40.0 }
            val rain = DoubleArray(config.cellCount)
            repeat(40) {
                for (y in 0 until 12) q[y * 24 + if (direction > 0) 3 else 21] += 12.0
                val deposited = model.transport(q, 86400.0)
                for (i in rain.indices) rain[i] += deposited[i]
                assertTrue(model.maximumCourant <= 0.75 + 1e-12)
                assertTrue(abs(model.moistureResidual) < 1e-12)
            }
            assertEquals(40.0 * config.cellCount + 40.0 * 12 * 12, q.sum() + rain.sum(), 1e-8)
            return q to rain
        }
        val flat = run(false, 1.0)
        val east = run(true, 1.0); val west = run(true, -1.0)
        fun amount(field: DoubleArray, xs: IntRange) = (0 until 12).sumOf { y -> xs.sumOf { field[y * 24 + it] } }
        assertTrue(amount(east.second, 9..12) > amount(flat.second, 9..12))
        assertTrue(amount(east.first, 14..18) < amount(flat.first, 14..18))
        assertTrue(amount(east.second, 9..11) > amount(east.second, 13..15))
        assertTrue(amount(west.second, 13..15) > amount(west.second, 9..11))
        println("OROGRAPHY east_windward=${amount(east.second, 9..11)} east_lee=${amount(east.second, 13..15)} west_windward=${amount(west.second, 13..15)} west_lee=${amount(west.second, 9..11)}")
    }

    @Test
    fun soilSnowAndRunoffReconcileAndRainIntermittencyChangesInfiltration() {
        fun run(wet: Double): Triple<Double, Double, Double> {
            val world = World(42, 24, 12, settings)
            val surface = TerrainMoistSurface(world, AtmosphereTileMapping(24, 12, config))
            val before = surface.totalWaterKg
            surface.beginUpdate()
            for (i in 0 until config.cellCount) surface.receiveClimateRain(i, 100.0, wet, 0.0)
            surface.finishClimateStep(86400.0)
            val rainKg = 100.0 * config.cellCount * config.cellArea
            assertEquals(before + rainKg, surface.totalWaterKg + surface.exportedWaterKg, rainKg * 1e-12)
            val actual = surface.evaporate(0, 1.0)
            assertTrue(actual in 0.0..1.0)
            assertEquals(before + rainKg - actual * config.cellArea,
                surface.totalWaterKg + surface.exportedWaterKg, rainKg * 1e-12)
            return Triple(surface.soil.sum(), surface.overflow.sum(), surface.exportedWaterKg)
        }
        val drizzle = run(1.0); val storm = run(0.05)
        assertTrue(storm.second > drizzle.second)
        assertTrue(storm.first < drizzle.first)
        val world = World(42, 24, 12, settings)
        val surface = TerrainMoistSurface(world, AtmosphereTileMapping(24, 12, config))
        surface.beginUpdate(); surface.receiveClimateRain(0, 20.0, 0.1, 1.0)
        surface.finishClimateStep(86400.0)
        val inventory = surface.totalWaterKg + surface.exportedWaterKg
        val melted = surface.meltSnowEnergyMm(0, 334000.0 * 5)
        assertEquals(5.0, melted, 1e-12)
        surface.beginUpdate(); surface.finishClimateStep(86400.0)
        assertEquals(inventory, surface.totalWaterKg + surface.exportedWaterKg, inventory * 1e-12)
    }
}
