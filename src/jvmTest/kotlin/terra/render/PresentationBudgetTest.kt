package terra.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.weather.DynamicalWeather
import terra.weather.WeatherModel
import terra.weather.testWeather
import terra.world.World

class PresentationBudgetTest {
    @Test
    fun skippedBiomePublicationRetainsAllDirtyTiles() {
        val world = World(31742, 31, 23)
        val initial = world.snapshot(1)
        DynamicalWeather(world)
        val skipped = world.snapshot(1, initial)
        val latest = world.snapshot(1, skipped)
        val dirty = (0 until latest.changedTerrainCount).map { latest.changedTerrainIndex(it) }.toSet()
        assertTrue(dirty.isNotEmpty())
        for (i in 0 until 31 * 23) if (initial[i % 31, i / 31].terrain != latest[i % 31, i / 31].terrain)
            assertTrue(i in dirty, "Skipped publication lost terrain tile $i")
        assertEquals(skipped.changedTerrainCount, latest.changedTerrainCount)
    }


}
