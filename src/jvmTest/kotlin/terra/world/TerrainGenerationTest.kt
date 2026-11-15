package terra.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainGenerationTest {
    @Test
    fun reproducibleTerrainPreservesFlatControls() {
        val world = World(31742, 45, 33)
        val repeated = World(31742, 45, 33)
        val other = World(42, 45, 33)
        var different = false
        for (y in 0 until world.height) for (x in 0 until world.width) {
            assertEquals(world[x, y], repeated[x, y])
            if (world[x, y] != other[x, y]) different = true
        }
        assertTrue(different)
        val flat = World(42, 9, 7, TerrainGenerationSettings(baseElevation = 0.4,
            continentStrength = 0.0, regionalStrength = 0.0, detailStrength = 0.0))
        for (y in 0 until flat.height) for (x in 0 until flat.width) assertEquals(0.4, flat[x, y].elevation)
    }

    @Test
    fun unErodedGeographyUsesTheSamePlanetCoordinatesAtDifferentResolutions() {
        val settings = TerrainGenerationSettings(erosionStrength = 0.0)
        val low = World(31742, 9, 7, settings)
        val high = World(31742, 27, 21, settings)
        for (y in 0 until low.height) for (x in 0 until low.width)
            assertEquals(low[x, y].elevation, high[x * 3 + 1, y * 3 + 1].elevation, 1e-12)
    }

}
