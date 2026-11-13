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
        // A ten-day climate transaction and 3-hour detailed steps differ in
        // sub-period drainage timing; total soil and river response stay comparable.
        assertTrue(outletError < 0.05, "outlet relative difference $outletError")
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
    fun coldPrecipitationIsStoredAsSnowAndMeltsIntoTheCatchmentWhenWarm() {
        val width = 8; val height = 6
        val config = DryAtmosphereConfig(width = 4, height = 4)
        val settings = TerrainGenerationSettings(baseElevation = 0.4,
            continentStrength = 0.0, regionalStrength = 0.0, detailStrength = 0.0)
        val elevation = DoubleArray(width * height) { 0.4 }
        elevation[0] = 0.0 // one ocean sink, no lake depressions
        val world = World.withElevation(31742, width, height, settings, elevation)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val surface = TerrainMoistSurface(world, AtmosphereTileMapping(width, height, config), hydrology)
        val initialSoil = surface.soil.sum()
        for (cell in 0 until config.cellCount) surface.setCellTemperature(cell, -10.0)
        surface.beginUpdate()
        for (cell in 0 until config.cellCount) surface.receiveClimateRain(cell, 20.0, 1.0, 1.0)
        surface.finishClimateStep(day)
        hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(day))
        assertTrue(surface.snowWaterMmTiles > 500.0, "cold precipitation must remain as snow")
        assertEquals(initialSoil, surface.soil.sum(), 1e-8)
        val snowBeforeMelt = surface.snowWaterMmTiles
        for (cell in 0 until config.cellCount) surface.setCellTemperature(cell, 10.0)
        surface.beginUpdate()
        surface.finishClimateStep(day)
        hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(day))
        assertTrue(surface.snowWaterMmTiles < snowBeforeMelt, "warm conditions must melt snow")
        assertTrue(surface.soil.sum() > initialSoil, "meltwater must recharge the root zone")
        val outletAfterMelt = hydrology.outletMmTilesTotal
        surface.beginUpdate()
        surface.finishClimateStep(day)
        hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(day))
        assertTrue(hydrology.outletMmTilesTotal > outletAfterMelt,
            "snowmelt must sustain delayed baseflow after the melt event")
        println("SNOW_MELT snow_before=$snowBeforeMelt snow_after=${surface.snowWaterMmTiles} " +
            "soil=${surface.soil.sum()} delayed_outlet=${hydrology.outletMmTilesTotal - outletAfterMelt}")
    }

    @Test
    fun energyLimitedSnowDoesNotAlsoMeltByDegreeDayAndOverlapEnergyHasCellUnits() {
        val world = World(31742, 16, 12, TerrainGenerationSettings(baseElevation = 0.4,
            continentStrength = 0.0, regionalStrength = 0.0, detailStrength = 0.0))
        val config = DryAtmosphereConfig(width = 6, height = 5)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val surface = TerrainMoistSurface(world, AtmosphereTileMapping(world.width, world.height, config), hydrology)
        surface.beginUpdate()
        for (cell in 0 until config.cellCount) {
            surface.setCellTemperature(cell, 10.0)
            surface.receiveClimateRain(cell, 200.0, 1.0, 1.0)
        }
        for (tile in surface.soil.indices) surface.soil[tile] = 250.0
        val water = surface.totalWaterKg
        val snow = surface.snowWaterMmTiles
        val fields = Array(7) { DoubleArray(world.width * world.height) }
        surface.finishClimateStep(day, energyLimitedSnowMelt = true, totals = ClimateFluxTotals(fields))
        assertEquals(snow, surface.snowWaterMmTiles, 1e-8, "No second empirical melt without an energy budget")
        val melted = surface.meltSnowEnergyMm(0, 334_000.0)
        assertEquals(1.0, melted, 1e-12, "334 kJ per square metre melts 1 mm over the cell, including partial tile overlaps")
        assertEquals(snow - surface.mapping.tilesPerCell, surface.snowWaterMmTiles, 1e-8)
        surface.meltSnowEnergyMm(0, 100 * 334_000.0)
        assertTrue(surface.overflow.sum() > 0.0)
        assertEquals(surface.overflow.sum(), fields[3].sum(), 1e-12,
            "Melt after the bucket pass must remain visible in interval diagnostics")
        for (cell in 0 until config.cellCount) surface.setCellTemperature(cell, -5.0)
        surface.setCellTemperature(0, 10.0)
        val coldCellSnow = surface.snowWaterMm(config.cellCount - 1)
        surface.finishClimateStep(day)
        assertEquals(coldCellSnow, surface.snowWaterMm(config.cellCount - 1), 1e-12,
            "A warm member of one response unit must not melt its cold members")
        hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(2 * day))
        assertEquals(water, surface.totalWaterKg, water * 1e-12)
    }

    @Test
    fun responseUnitsPreserveAreaGroundwaterSnowAndWholeSurfaceMass() {
        val width = 16; val height = 12
        val config = DryAtmosphereConfig(width = 6, height = 5)
        val world = World(31742, width, height, TerrainGenerationSettings(baseElevation = 0.4,
            continentStrength = 0.0, regionalStrength = 0.0, detailStrength = 0.0))
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val surface = TerrainMoistSurface(world, AtmosphereTileMapping(width, height, config), hydrology)
        val units = surface.responseUnits
        var landTiles = 0
        for (tile in 0 until width * height) {
            val isLand = hydrology.isLandTile(tile % width, tile / width)
            if (isLand) { landTiles++; assertTrue(units.unitOfTile[tile] >= 0) }
            else assertEquals(-1, units.unitOfTile[tile])
        }
        assertEquals(landTiles, units.tileCountPerUnit.sum())
        assertEquals(150.0 * landTiles, surface.groundwaterMmTiles, 1e-8)
        val initial = surface.totalWaterKg
        for (cell in 0 until config.cellCount) surface.setCellTemperature(cell, -8.0)
        surface.beginUpdate()
        for (cell in 0 until config.cellCount) surface.receiveClimateRain(cell, 12.0, 0.4, 0.8)
        val afterRain = surface.totalWaterKg
        surface.finishClimateStep(day)
        hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(day))
        assertEquals(surface.snowWaterMmTiles, surface.unitSnowWaterMmTiles, 1e-6)
        assertEquals(surface.groundwaterMmTiles, surface.unitGroundwaterMmTiles, 1e-8)
        assertEquals(afterRain, surface.totalWaterKg, afterRain * 1e-12)
        assertTrue(surface.snowWaterMmTiles > 0.0)
        for (cell in 0 until config.cellCount) surface.setCellTemperature(cell, 8.0)
        surface.beginUpdate()
        surface.finishClimateStep(day)
        hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(day))
        assertEquals(surface.snowWaterMmTiles, surface.unitSnowWaterMmTiles, 1e-6)
        assertEquals(afterRain, surface.totalWaterKg, afterRain * 1e-12)
        println("RESPONSE_UNITS units=${units.count} land_tiles=$landTiles " +
            "snow=${surface.snowWaterMmTiles} groundwater=${surface.groundwaterMmTiles}")
    }

    @Test
    fun seasonalRainRechargesGroundwaterAndKeepsBaseflowAfterRainStops() {
        val width = 12; val height = 8
        val config = DryAtmosphereConfig(width = 4, height = 4)
        val settings = TerrainGenerationSettings(baseElevation = 0.4, continentStrength = 0.0,
            regionalStrength = 0.0, detailStrength = 0.0)
        val elevation = DoubleArray(width * height) { 0.4 }
        elevation[0] = 0.0
        val world = World.withElevation(31742, width, height, settings, elevation)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val surface = TerrainMoistSurface(world, AtmosphereTileMapping(width, height, config), hydrology)
        for (cell in 0 until config.cellCount) surface.setCellTemperature(cell, 12.0)
        surface.beginUpdate()
        for (cell in 0 until config.cellCount) surface.receiveClimateRain(cell, 130.0, 0.35)
        surface.finishClimateStep(10 * day)
        hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(10 * day))
        val wetOutlet = hydrology.outletMmTilesTotal
        val afterWetGroundwater = surface.groundwaterMmTiles
        val dryOutlets = DoubleArray(6)
        repeat(dryOutlets.size) { step ->
            val before = hydrology.outletMmTilesTotal
            surface.beginUpdate()
            surface.finishClimateStep(10 * day)
            hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(10 * day))
            dryOutlets[step] = hydrology.outletMmTilesTotal - before
        }
        println("SEASONAL_BASEFLOW wet_outlet=$wetOutlet dry_outlets=${dryOutlets.toList()}")
        assertTrue(wetOutlet > 0.0, "wet season must produce river outflow")
        assertTrue(dryOutlets.first() > 0.0, "groundwater must sustain baseflow after rain stops")
        assertTrue(surface.groundwaterMmTiles < afterWetGroundwater,
            "dry-season baseflow must draw down the groundwater store")
        assertTrue(dryOutlets.last() < dryOutlets.first() * 0.95,
            "baseflow should recede through the dry interval: ${dryOutlets.toList()}")
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
        assertEquals(4003 - capacity, hydrology.outletMmTilesLastPeriod + hydrology.channelStorageMmTiles, 1e-9)
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
