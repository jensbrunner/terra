package terra.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherPaletteTest {
    @Test
    fun rainViewSeparatesDryLightAndHeavyRainWithoutEarlySaturation() {
        assertEquals(0.0, precipitationOpacity(0.0))
        assertTrue(precipitationOpacity(0.1) > 0.0)
        assertTrue(precipitationOpacity(3.0) > precipitationOpacity(0.1))

        val light = precipitationColor(0.1).value
        val moderate = precipitationColor(3.0).value
        val heavy = precipitationColor(12.0).value
        assertTrue(light != moderate)
        assertTrue(moderate != heavy)
        assertEquals(heavy, precipitationColor(30.0).value)
    }
}
