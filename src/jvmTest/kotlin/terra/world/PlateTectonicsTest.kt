package terra.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.hydrology.HydrologyTopology

class PlateTectonicsTest {
    @Test fun aBatchedAdvanceKeepsTimeAndPublishesOneGeographyUpdate() {
        val world = World(31742, 60, 44)
        val repeated = World(31742, 60, 44)
        val model = PlateTectonics(world)
        val other = PlateTectonics(repeated)
        val rain = DoubleArray(60 * 44) { 1000.0 }
        val initial = world.snapshot(0)

        model.advance(2_500.0, rain, speedMultiplier = 10_000.0)
        repeat(25) { other.advance(100.0, rain, speedMultiplier = 10_000.0) }

        assertEquals(25_000_000.0, model.effectiveYears)
        assertEquals(1L, world.geographyRevision)
        val updated = world.snapshot(0)
        val expected = repeated.snapshot(0)
        val changed = (0 until 60 * 44).count {
            val x = it % 60
            val y = it / 60
            assertEquals(expected[x, y], updated[x, y])
            assertEquals(expected.plateId(x, y), updated.plateId(x, y))
            initial[x, y].elevation != updated[x, y].elevation
        }
        assertTrue(changed > 60)
        assertTrue((0 until 60 * 44).any { initial.plateId(it % 60, it / 60) != updated.plateId(it % 60, it / 60) })
        assertEquals(0L, initial.geographyRevision)
        assertEquals(8, updated.plateCount)
    }

    @Test fun agedWorldRetainsLandOceanAndActiveBoundaries() {
        val world = World(31742, 60, 44)
        val model = PlateTectonics(world)
        model.advance(30_000.0, DoubleArray(60 * 44) { 1000.0 }, speedMultiplier = 10_000.0)
        val snapshot = world.snapshot(0)
        val land = (0 until 60 * 44).count { i ->
            val elevation = snapshot[i % 60, i / 60].elevation
            assertTrue(elevation.isFinite())
            elevation >= world.seaLevel
        }
        val boundaries = (0 until 60 * 44).map { snapshot.plateBoundary(it % 60, it / 60) }.toSet()
        assertTrue(land in 132..1_980)
        assertTrue(PlateBoundary.COLLISION in boundaries || PlateBoundary.SUBDUCTION in boundaries)
        assertTrue(PlateBoundary.RIFT in boundaries || PlateBoundary.SPREADING in boundaries)
    }

    @Test fun bothSeamsAndCornersWrapForTerrainNoiseAndDrainage() {
        val world = World(42, 24, 18)
        assertEquals(world[23, 17], world[-1, -1])
        assertEquals(world[0, 0], world.snapshot(0)[24, 18])
        val noise = TerrainNoise(TerrainGenerationSettings())
        assertEquals(noise.periodic(3.0, 7.0, 42, 3, 20.0), noise.periodic(286.0, -205.0, 42, 3, 20.0), 1e-12)
        val heights = DoubleArray(25) { 0.8 }.apply { this[24] = 0.1; this[0] = 0.2 }
        val topology = HydrologyTopology(5, 5, heights, BooleanArray(25) { it == 24 })
        assertEquals(24, topology.downstreamIndex[0])
    }
}
