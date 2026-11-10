package terra.render

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.atmosphere.SolarConfig
import terra.weather.WeatherFieldQuality
import terra.weather.WeatherFieldSemantics

class SeasonalLightingTest {
    private val solar = SolarConfig()
    private fun lighting(start: Double, seconds: Double) = SeasonalLighting.capture(32,
        WeatherFieldSemantics(WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL, start, seconds), solar)

    @Test
    fun astronomicalDayPhaseCannotStrobeClimateLightingButSeasonsRemainVisible() {
        val summer = lighting(solar.yearSeconds / 4, 86400.0)
        val halfDayLater = lighting(solar.yearSeconds / 4 + 43200, 86400.0)
        val winter = lighting(solar.yearSeconds * 0.75, 86400.0)
        for (y in 0 until 32) {
            assertTrue(abs(summer.opacityAtRow(y) - halfDayLater.opacityAtRow(y)) < 0.005)
            assertTrue(summer.opacityAtRow(y) in 0.0..0.38)
        }
        assertTrue(winter.opacityAtRow(0) > summer.opacityAtRow(0) + 0.1)
        assertTrue(summer.opacityAtRow(31) > winter.opacityAtRow(31) + 0.1)
    }

    @Test
    fun WholeYearAndDecadeIllustrationsAreStableAcrossStartingDatesAndFrozen() {
        val annual = lighting(12345.0, solar.yearSeconds)
        val decades = lighting(solar.yearSeconds / 3 + 43200, 20 * solar.yearSeconds)
        val retained = annual.opacityAtRow(0)
        lighting(0.0, 86400.0)
        assertEquals(retained, annual.opacityAtRow(0))
        for (y in 0 until 32) assertEquals(annual.opacityAtRow(y), decades.opacityAtRow(y), 1e-12)
    }
}
