package terra.weather

import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.atmosphere.AtmosphereTileMapping
import terra.atmosphere.DryAtmosphereConfig
import terra.hydrology.HydrologyExchange
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.hydrology.WaterFluxBatch
import terra.world.TerrainGenerationSettings
import terra.world.World

class CoarseHydrologyTest {
    private val day = 86400.0

    @Test
    fun constantForcingMatchesShortDetailedBucketAndRoutingReference() {
        val coarse = SlopeFixture(120.0)
        val reference = SlopeFixture(120.0)
        val initial = coarse.water()
        coarse.advance(10 * day, 100.0, coarse = true)
        repeat(80) { reference.advance(day / 8, 100.0 / 80, coarse = false) }
        val soilError = abs(coarse.surface.soil[36] - reference.surface.soil[36])
        val outletError = abs(coarse.hydrology.outletMmTilesTotal - reference.hydrology.outletMmTilesTotal) /
            reference.hydrology.outletMmTilesTotal
        assertTrue(soilError < 0.6, "soil difference $soilError mm")
        assertTrue(outletError < 0.02, "outlet relative difference $outletError")
        assertEquals(initial, coarse.water(), 1e-8)
        assertEquals(initial, reference.water(), 1e-8)
        assertEquals(1L, coarse.hydrology.completedRoutingPasses)
        assertEquals(10L, reference.hydrology.completedRoutingPasses)
        assertTrue(coarse.hydrology.isStreamTile(0, 1), "The retained fine channel must carry a visible stream")
        assertEquals(10.0, coarse.hydrology.lastRoutedPeriodDays)
        val snapshot = coarse.hydrology.snapshot()
        assertTrue(snapshot.aggregatedClimateInterval)
        assertEquals(snapshot.routedMmTiles(0, 1) / 10, snapshot.dischargeMmTilesPerDay(0, 1))
        println("COARSE_REFERENCE soil_error_mm=$soilError outlet_relative_error=$outletError routes=1_vs_10")
    }

    @Test
    fun sameRainConcentratedIntoWetDaysProducesMoreOverflowAndDifferentDrySoil() {
        val spread = SlopeFixture(240.0)
        val event = SlopeFixture(240.0)
        val initial = spread.water()
        val distributedOverflow = spread.advance(10 * day, 100.0, coarse = true)
        val eventOverflow = event.advance(2 * day, 100.0, coarse = true)
        event.advance(8 * day, 0.0, coarse = true)
        assertEquals(0.0, distributedOverflow)
        assertTrue(eventOverflow > 0.0)
        assertTrue(event.surface.soil[36] < spread.surface.soil[36])
        assertTrue(event.hydrology.outletMmTilesTotal > spread.hydrology.outletMmTilesTotal)
        assertEquals(initial, spread.water(), 1e-8)
        assertEquals(initial, event.water(), 1e-8)
        assertTrue(event.surface.soil.all { it in 0.0..250.0 })
        println("COARSE_EVENT uniform_soil_mm=${spread.surface.soil[36]} event_soil_mm=${event.surface.soil[36]} event_overflow_mm_tiles=$eventOverflow")
    }

    @Test
    fun longMonotoneLakeSegmentsRouteOncePreservePendingAndClipEvaporationToStorage() {
        val width = 10
        val height = 8
        val depression = setOf(40, 49, 50, 59)
        val elevation = DoubleArray(80) { if (it in depression) 0.2 else 0.8 }
        elevation[5] = 0.0
        val hydrology = HydrologySimulation(HydrologyTopology(width, height, elevation, BooleanArray(80) { it == 5 }))
        var returnedVapor = 0.0
        val exchange = object : HydrologyExchange {
            override fun updateLakeWaterMask(mask: BooleanArray) {}
            override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double) { returnedVapor += amountMm }
        }
        val initial = hydrology.storedAndPendingWaterMmTiles
        val zero = DoubleArray(80)
        // Fund an antecedent routing queue, then fund the long wet segment from the real ocean.
        assertEquals(3.0, hydrology.evaporateOceanWaterMmTiles(3.0))
        hydrology.acceptFluxBatch(WaterFluxBatch.fromTileDepths(width, height, day / 4,
            DoubleArray(80) { if (it == 21) 3.0 else 0.0 }, zero, zero, exchange))
        assertEquals(4000.0, hydrology.evaporateOceanWaterMmTiles(4000.0))
        val represented = hydrology.acceptClimateFluxBatch(WaterFluxBatch.fromTileDepths(width, height, 365 * day,
            zero, DoubleArray(80) { if (it in depression) 1000.0 else 0.0 }, zero, exchange))
        val capacity = hydrology.lakeCapacityMmTiles(0)
        assertEquals(365.25 * day, represented)
        assertEquals(1L, hydrology.completedRoutingPasses)
        assertEquals(capacity, hydrology.lakeStorageMmTiles(0), 1e-9)
        assertEquals(4003 - capacity, hydrology.outletMmTilesLastPeriod, 1e-9)
        assertEquals(initial, hydrology.storedAndPendingWaterMmTiles, 1e-9)
        assertEquals(0.0, hydrology.waterInventory().pendingDurationSeconds)
        val wet = hydrology.snapshot()
        assertTrue(wet.isLakeTileWet(0, 4) && wet.isLakeTileWet(9, 4))
        assertEquals(365.25, wet.routedPeriodDays)
        hydrology.acceptClimateFluxBatch(WaterFluxBatch.fromTileDepths(width, height, 365 * day,
            zero, zero, DoubleArray(80) { if (it in depression) 1000.0 else 0.0 }, exchange))
        assertEquals(capacity, returnedVapor, 1e-9)
        assertEquals(0.0, hydrology.lakeStorageMmTiles(0))
        assertTrue(!hydrology.isLakeTileWet(0, 4) && !hydrology.isLakeTileWet(9, 4))
        assertEquals(initial, hydrology.storedAndPendingWaterMmTiles + returnedVapor, 1e-9)
        assertEquals(2L, hydrology.completedRoutingPasses)
        assertEquals(capacity, wet.lakeStorageMmTiles(0)) // Previously published lake state stays frozen.
    }

    private inner class SlopeFixture(initialSoil: Double) {
        private val width = 8
        private val height = 12
        private val config = DryAtmosphereConfig(width = 4, height = 4)
        private val world = World(31742, width, height, TerrainGenerationSettings(baseElevation = 0.4,
            continentStrength = 0.0, regionalStrength = 0.0, detailStrength = 0.0))
        val hydrology = HydrologySimulation(HydrologyTopology(width, height,
            DoubleArray(width * height) { tile ->
                val x = tile % width; val y = tile / width
                if (x == 0) y * 0.01 else 0.5 + min(x, width - x) * 0.01 + y * 0.0001
            }, BooleanArray(width * height) { it == 0 }))
        val surface = TerrainMoistSurface(world, AtmosphereTileMapping(width, height, config), hydrology)
        init {
            for (tile in surface.soil.indices) if (hydrology.isLandTile(tile % width, tile / width)) surface.soil[tile] = initialSoil
        }
        fun water() = surface.totalWaterKg / surface.mapping.tileAreaM2
        fun advance(seconds: Double, rainMm: Double, coarse: Boolean): Double {
            val before = water()
            surface.beginUpdate()
            assertEquals(rainMm * width * height, hydrology.evaporateOceanWaterMmTiles(rainMm * width * height), 1e-8)
            for (cell in 0 until config.cellCount) {
                if (coarse) surface.receiveClimateRain(cell, rainMm) else surface.receiveRain(cell, rainMm)
            }
            assertEquals(before, water(), 1e-8) // Queued climate rain is already owned, before soil integration.
            if (coarse) surface.finishClimateStep(seconds) else surface.finishStep(seconds)
            val overflow = surface.overflow.sum()
            if (coarse) hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(seconds))
            else hydrology.acceptFluxBatch(surface.sealFluxBatch(seconds))
            return overflow
        }
    }
}
