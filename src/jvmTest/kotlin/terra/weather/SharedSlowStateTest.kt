package terra.weather

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.hydrology.HydrologyExchange
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.hydrology.WaterFluxBatch
import terra.world.World

class SharedSlowStateTest {
    @Test
    fun controlledIntervalUsesExistingStoresAndKeepsPendingWaterThroughRouting() {
        val width = 10
        val height = 8
        val depression = setOf(40, 49, 50, 59)
        val elevation = DoubleArray(width * height) { if (it in depression) 0.2 else 0.8 }
        elevation[5] = 0.0
        val hydrology = HydrologySimulation(HydrologyTopology(width, height, elevation,
            BooleanArray(elevation.size) { it == 5 }))
        // Controlled basin geography; the surface's authoritative land mask comes from hydrology.
        val weather = DynamicalWeather(World(31742, width, height), hydrology,
            DryAtmosphereConfig(width = width, height = height))
        val surface = weather.surfaceState
        fun cell(tile: Int) = (height - tile / width - 1) * width + tile % width
        val tileArea = surface.mapping.tileAreaM2
        val initialWater = surface.totalWaterKg / tileArea
        val thermal = weather.surfaceThermalState
        val oldTemperature = thermal[0]
        thermal[0] = oldTemperature + 1.0
        weather.refreshSnapshot()
        assertEquals(oldTemperature + 1.0, weather.atmosphereSnapshot.surfaceTemperatureKelvinAt(0, 0))
        thermal[0] = oldTemperature
        assertFailsWith<IllegalArgumentException> { thermal[0] = Double.NaN }

        // Move real ocean water into a pre-existing wet lake, without an atmospheric source term.
        assertEquals(80.0, hydrology.evaporateOceanWaterMmTiles(80.0))
        val initialLakeExchange = object : HydrologyExchange {
            override fun updateLakeWaterMask(mask: BooleanArray) = surface.updateLakeMask(mask)
            override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double) { error("No potential evaporation") }
        }
        hydrology.acceptFluxBatch(WaterFluxBatch.fromTileDepths(width, height, 86400.0,
            DoubleArray(80), DoubleArray(80) { if (it in depression) 20.0 else 0.0 }, DoubleArray(80), initialLakeExchange))
        assertEquals(80.0, hydrology.lakeStorageMmTiles(0))

        // Establish six hours of pending runoff before the synthetic twelve-hour interval.
        surface.beginUpdate()
        surface.receiveRain(cell(21), hydrology.evaporateOceanWaterMmTiles(200.0))
        surface.finishStep(21600.0)
        hydrology.acceptFluxBatch(surface.sealFluxBatch(21600.0))
        val pending = hydrology.waterInventory()
        assertTrue(pending.pendingRunoffMmTiles > 0.0)
        assertEquals(21600.0, pending.pendingDurationSeconds)
        assertEquals(initialWater, surface.totalWaterKg / tileArea, 1e-9)

        surface.beginUpdate()
        var heldEvaporationMmTiles = 0.0 // Transient transfer ledger, not another world reservoir.
        for (c in 0 until 80) heldEvaporationMmTiles += surface.evaporate(c, 5.0)
        assertEquals(initialWater, surface.totalWaterKg / tileArea + heldEvaporationMmTiles, 1e-9)
        surface.receiveRain(cell(21), heldEvaporationMmTiles * 0.8)
        surface.receiveRain(cell(40), heldEvaporationMmTiles * 0.1)
        surface.receiveRain(cell(5), heldEvaporationMmTiles * 0.1)
        surface.finishStep(43200.0)
        val runoff = surface.drainage.sum() + surface.overflow.sum()
        val lakeRain = surface.lakeRain.sum()
        assertTrue(runoff > 0 && lakeRain > 0 && surface.unacceptedWaterMmTiles > 0)
        assertEquals(initialWater, surface.totalWaterKg / tileArea, 1e-9)
        assertFailsWith<IllegalArgumentException> { surface.sealFluxBatch(21600.0) }
        val batch = surface.sealFluxBatch(43200.0)
        assertFailsWith<IllegalStateException> { surface.beginUpdate() }
        assertFailsWith<IllegalStateException> { surface.receiveRain(0, 1.0) }
        hydrology.acceptFluxBatch(batch)
        val after = hydrology.waterInventory()
        assertEquals(pending.pendingRunoffMmTiles + runoff, after.pendingRunoffMmTiles, 1e-9)
        assertEquals(pending.pendingLakeRainMmTiles + lakeRain, after.pendingLakeRainMmTiles, 1e-9)
        assertEquals(64800.0, after.pendingDurationSeconds)
        assertTrue(after.lakesMmTiles > 0.0)
        assertEquals(0.0, surface.unacceptedWaterMmTiles)
        assertEquals(initialWater, surface.totalWaterKg / tileArea, 1e-9)
        assertFailsWith<IllegalStateException> { hydrology.acceptFluxBatch(batch) }
        assertEquals(after, hydrology.waterInventory())

        surface.beginUpdate()
        surface.finishStep(21600.0)
        hydrology.acceptFluxBatch(surface.sealFluxBatch(21600.0))
        assertEquals(0.0, hydrology.waterInventory().pendingDurationSeconds)
        assertEquals(initialWater, surface.totalWaterKg / tileArea, 1e-9)
        assertEquals(oldTemperature, thermal[0])
        assertEquals(0.0, weather.elapsedSeconds) // No detailed steps or false calendar advancement.

        // Resume the same detailed owner and verify its compatibility handoff is also single-use.
        weather.advanceBudgeted(20.0, 1)
        assertFailsWith<IllegalStateException> { weather.advanceBudgeted(20.0, 1) }
        assertFailsWith<IllegalArgumentException> { hydrology.acceptWeatherStep(weather, 40.0) }
        hydrology.acceptWeatherStep(weather, 20.0)
        val accepted = hydrology.waterInventory()
        assertFailsWith<IllegalStateException> { hydrology.acceptWeatherStep(weather, 20.0) }
        assertEquals(accepted, hydrology.waterInventory())
        weather.refreshSnapshot()
        assertTrue(abs(weather.diagnostics.relativeWaterResidual) < 1e-12)
    }

    @Test
    fun batchCopiesInputsValidatesAmountsAndCannotBeAcceptedTwice() {
        val topology = HydrologyTopology(3, 1, doubleArrayOf(0.5, 0.4, 0.0), booleanArrayOf(false, false, true))
        val hydrology = HydrologySimulation(topology)
        var acknowledgements = 0
        val exchange = object : HydrologyExchange {
            override fun updateLakeWaterMask(mask: BooleanArray) {}
            override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double) { error("No lake evaporation") }
            override fun hydrologyAccepted() { acknowledgements++ }
        }
        val runoff = doubleArrayOf(3.0, 0.0, 0.0) // Already-debited donor fixture.
        val zero = DoubleArray(3)
        val batch = WaterFluxBatch.fromTileDepths(3, 1, 86400.0, runoff, zero, zero, exchange)
        runoff[0] = 999.0
        val initial = hydrology.oceanWaterMmTiles
        hydrology.acceptFluxBatch(batch)
        assertEquals(initial + 3.0, hydrology.oceanWaterMmTiles)
        assertEquals(1, acknowledgements)
        assertFailsWith<IllegalStateException> { HydrologySimulation(topology).acceptFluxBatch(batch) }
        assertFailsWith<IllegalArgumentException> {
            WaterFluxBatch.fromTileDepths(3, 1, 1.0, doubleArrayOf(Double.NaN, 0.0, 0.0), zero, zero, exchange)
        }
        assertEquals(initial + 3.0, hydrology.oceanWaterMmTiles)
    }
}
