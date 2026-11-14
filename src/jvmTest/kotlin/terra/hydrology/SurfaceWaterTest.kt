package terra.hydrology

import kotlin.math.exp
import kotlin.test.*
import terra.world.World

class SurfaceWaterTest {
    @Test
    fun groundwaterRecedesAnalyticallyAndRoutingAccountsForEveryDrop() {
        val world = World(31742, 48, 36)
        val topology = HydrologyTopology(world)
        val tile = topology.processingOrder.first { topology.isLandTile(it) }
        val water = SurfaceWater(world)
        val before = water.totalWaterMmTiles
        water.receive(tile, 100.0, 40.0)
        var exported = water.advance(21600.0)
        val initialGround = water.groundwaterMmAt(tile)
        assertTrue(initialGround in 129.0..130.0)
        val first = water.snapshot()
        val firstOriginFlow = first.dischargeMmTilesPerDay(0,0)
        repeat(30) { exported += water.advance(86400.0) }
        assertEquals(initialGround * exp(-0.5), water.groundwaterMmAt(tile), 1e-10)
        assertEquals(before + 140, water.totalWaterMmTiles + exported, (before + 140) * 1e-12)
        assertTrue(water.snapshot().dischargeMmTilesPerDay(tile % world.width,tile / world.width) > 0)
        assertTrue(first !== water.snapshot())
        assertEquals(firstOriginFlow, first.dischargeMmTilesPerDay(0,0))
    }

    @Test
    fun finiteLakesDryAndRefillWithoutChangingDrainageOrCreatingWater() {
        val world = World(31742)
        val water = SurfaceWater(world)
        val initial = water.snapshot()
        val tile = (0 until world.width * world.height).filter { initial.isLakeTileWet(it % world.width,it / world.width) }
            .maxBy { world[it % world.width,it / world.width].elevation }
        val id = initial.lakeIdAt(tile % world.width,tile / world.width)
        val stored = water.lakeWaterMmTiles(id)
        val before = water.totalWaterMmTiles
        assertTrue(stored > 0 && stored.isFinite())
        assertEquals(stored, water.evaporateLake(tile, Double.MAX_VALUE))
        var exported = water.advance(21600.0)
        val dry = water.snapshot()
        assertFalse(dry.isLakeTileWet(tile % world.width,tile / world.width))
        assertTrue(initial.isLakeTileWet(tile % world.width,tile / world.width))
        water.receiveLakeRain(tile, stored)
        exported += water.advance(21600.0)
        val filled = water.snapshot()
        assertTrue(filled.isLakeTileWet(tile % world.width,tile / world.width))
        assertEquals(initial.downstreamTileIndex(tile % world.width,tile / world.width),
            filled.downstreamTileIndex(tile % world.width,tile / world.width))
        assertEquals(before, water.totalWaterMmTiles + exported, before * 1e-12)
        // A storm exceeding finite capacity must spill into downstream stores/export.
        val pulse = stored * 10
        water.receiveLakeRain(tile,pulse)
        exported += water.advance(10 * 86400.0)
        assertTrue(water.lakeWaterMmTiles(id) < stored * 4)
        assertEquals(before + pulse, water.totalWaterMmTiles + exported, (before + pulse) * 1e-12)
    }
}
