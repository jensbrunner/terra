package terra.climate

import kotlin.test.*
import terra.world.Terrain
import terra.world.World

class AtmosphereTest {
    @Test fun centuryFastForwardMatchesAnnualSteps() {
        val whole = World(31742, 45, 33)
        val split = World(31742, 45, 33)
        for (y in 0 until whole.height) for (x in 0 until whole.width)
            assertTrue(whole[x, y].terrain in listOf(Terrain.PLAINS, Terrain.OCEAN))
        val a = ClimateSimulation(whole)
        val b = ClimateSimulation(split)
        a.advance(100 * YEAR_SECONDS)
        repeat(400) { b.advance(YEAR_SECONDS / 4) }
        val wa = whole.snapshot(0)
        val wb = split.snapshot(0)
        assertEquals(a.elapsedSeconds, b.elapsedSeconds)
        for (y in 0 until whole.height) for (x in 0 until whole.width) {
            assertEquals(wa[x, y], wb[x, y])
            assertEquals(wa.treeCover(x, y), wb.treeCover(x, y))
            assertEquals(wa.grassCover(x, y), wb.grassCover(x, y))
        }
        val biomes = (0 until whole.height).flatMap { y -> (0 until whole.width).map { x -> whole[x, y].terrain } }.toSet()
        assertTrue(biomes.containsAll(Terrain.entries))
    }

    @Test fun annualClimateDrivesSuccessionAndWeatherStaysBounded() {
        val world = World(42)
        val climate = ClimateSimulation(world)
        climate.advance(YEAR_SECONDS)
        val young = world.snapshot(0)
        climate.advance(99 * YEAR_SECONDS)
        val mature = world.snapshot(0)
        val weather = climate.snapshot()
        var forestGrew = false
        for (y in 0 until world.height) for (x in 0 until world.width) {
            assertTrue(weather.pressureHpa(x, y) in 800.0..1200.0)
            assertTrue(weather.relativeHumidity(x, y) in 0.0..1.0)
            assertTrue(weather.cloudCover(x, y) in 0.0..1.0)
            assertTrue(weather.precipitationMmPerHour(x, y) >= 0)
            assertEquals(weather.recentRainMmPerYear(x, y), weather.rainMmThisUpdate(x, y))
            assertTrue(weather.soilMoisture(x, y) in 0.0..1.0)
            assertTrue(mature.treeCover(x, y) + mature.grassCover(x, y) <= 1.0 + 1e-12)
            if (mature[x, y].terrain == Terrain.WOODS) {
                assertTrue(mature.treeCover(x, y) > young.treeCover(x, y))
                forestGrew = true
            }
        }
        assertTrue(forestGrew)
    }
}
