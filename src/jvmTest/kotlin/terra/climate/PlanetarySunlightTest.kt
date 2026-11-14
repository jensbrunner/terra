package terra.climate

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanetarySunlightTest {
    @Test
    fun solarGeometryTracksDaySeasonHemispheresAndTerrainOrientation() {
        val width = 16
        val height = 9
        val config = SolarConfig()
        val sky = PlanetarySunlight(width, height, config)
        val tiles = PlanetarySunlight(width, height, config, northUp = false)
        val summer = config.yearSeconds * 0.25
        sky.update(summer)
        tiles.update(summer)
        assertEquals(23.44 * PI / 180, sky.declinationRadians, 1e-12)
        for (x in 0 until width) {
            // The represented polar row has continuous summer daylight/winter darkness.
            assertTrue(sky.inputFactor(x, 8) > 0)
            assertEquals(0.0, sky.inputFactor(x, 0))
            assertEquals(0.0, sky.nightOpacity(x, 8))
            assertEquals(0.65, sky.nightOpacity(x, 0))
            for (y in 0 until height) {
                assertEquals(sky.inputFactor(x, y), tiles.inputFactor(x, height - 1 - y), 1e-14)
            }
        }
        sky.update(config.yearSeconds * 0.75)
        assertEquals(-23.44 * PI / 180, sky.declinationRadians, 1e-12)
        assertTrue(sky.inputFactor(0, 0) > 0)
        assertEquals(0.0, sky.inputFactor(0, 8))
        sky.update(0.0)
        assertEquals(0.0, sky.declinationRadians)
        for (y in 0 until height) for (x in 0 until width) {
            val expected = cos(PlanetLatitude.northwardRowRadians(y, height)) *
                cos(2 * PI * ((x + 0.5) / width - 0.5))
            assertEquals(expected, sky.cosineZenith(x, y), 1e-14)
        }
        val untilted = PlanetarySunlight(width, height, config.copy(axialTiltDegrees = 0.0))
        untilted.update(config.daySeconds * 0.5)
        assertTrue(untilted.inputFactor(0, 4) > 0.9)
        assertEquals(0.0, untilted.inputFactor(8, 4))
        untilted.update(config.yearSeconds)
        assertEquals(0.0, untilted.declinationRadians)
        assertTrue(untilted.inputFactor(8, 4) > 0.9)
    }

}
