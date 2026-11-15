package terra.climate

import kotlin.test.*
import terra.world.World

class AtmosphereTest {
    @Test fun physicalScaleAndClosedColumnMassRemainStable() {
        assertEquals(1000.0, TILE_METERS)
        assertEquals(9.80665, GRAVITY)
        assertEquals(1.225, AIR_DENSITY)
        assertEquals(101325.0, COLUMN_DEPTH * AIR_DENSITY * GRAVITY, 1e-9)
        assertEquals(40 * 7.2921159e-5, ROTATION_RATE)
        val world = World(31742, 24, 18)
        val a = Atmosphere(world)
        val surface = LandscapeWater(world)
        val initial = a.h.sum()
        repeat(48) { hour ->
            surface.beginUpdate()
            a.advance(3600.0, hour * 3600.0, surface)
            assertEquals(initial, a.h.sum(), initial * 1e-11)
            for (i in a.h.indices) {
                assertTrue(a.h[i].isFinite() && a.h[i] > 0)
                assertTrue(a.temperature[i] in 150.0..350.0)
                assertTrue(a.u[i].isFinite() && a.v[i].isFinite())
                assertTrue(a.vapor[i] >= 0 && a.cloud[i] >= 0 && a.rain[i] >= 0)
            }
        }
    }

    @Test fun defaultGridPublishesLiveWeatherAndLandscapeRain() {
        val world = World(42)
        assertEquals(283, world.width)
        assertEquals(212, world.height)
        val climate = ClimateSimulation(world)
        climate.advance(600.0)
        val weather = climate.snapshot()
        assertEquals(world.width, weather.width)
        assertEquals(world.height, weather.height)
        for (y in 0 until world.height) for (x in 0 until world.width) {
            assertTrue(weather.pressureHpa(x, y) in 800.0..1200.0)
            assertEquals(weather.rainMmThisUpdate(x, y) * 6, weather.precipitationMmPerHour(x, y), 1e-10)
            assertTrue(weather.relativeHumidity(x, y) in 0.0..1.0)
        }
    }
}
