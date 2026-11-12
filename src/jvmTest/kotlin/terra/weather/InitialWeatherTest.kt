package terra.weather

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.PlanetLatitude
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.world.World

class InitialWeatherTest {
    @Test
    fun seededLowsVaryAndHumidityExistsOutsideTheirSupport() {
        val config = DryAtmosphereConfig(width = 24, height = 18)
        val a = SynopticPattern(31742)
        val same = SynopticPattern(31742)
        val other = SynopticPattern(42)
        val weather = InitialWeather(31742, config)
        val repeated = InitialWeather(31742, config)
        var changedLatitude = false
        for (h in 0..1) for (s in 0..1) {
            val range = if (h == 0) 0.15..0.40 else 0.60..0.85
            assertTrue(a.centerNorth(h, s) in range)
            assertTrue(a.centerX(h, s) in (s / 2.0)..((s + 1) / 2.0))
            assertEquals(a.centerNorth(h, s), same.centerNorth(h, s))
            if (a.centerNorth(h, s) != other.centerNorth(h, s)) changedLatitude = true
        }
        assertTrue(changedLatitude)
        var equatorialHumidCells = 0
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val east = (x + 0.5) / config.width
            val north = (y + 0.5) / config.height
            val moisture = weather.moistFraction(east, north, 1.0)
            assertEquals(moisture, repeated.moistFraction(east, north, 1.0))
            assertEquals(moisture, weather.moistFraction(east + 1, north, 1.0), 1e-12)
            assertTrue(weather.relativeHumidity(moisture) in 0.55..1.0)
            assertTrue(weather.cloudWaterMm(moisture) in 0.0..0.25)
            if (abs(PlanetLatitude.radians(north)) < 0.2 && weather.relativeHumidity(moisture) > 0.8)
                equatorialHumidCells++
            val flow = weather.flow(east * config.domainWidthMeters, north * config.domainHeightMeters)
            assertTrue(flow.heightMeters.isFinite() && flow.heightMeters > 0)
        }
        assertTrue(equatorialHumidCells > 0)
        val world = World(31742, 31, 23)
        val hydro = HydrologySimulation(HydrologyTopology(world))
        val model = DynamicalWeather(world, hydro, config)
        assertTrue(model.diagnostics.totalWaterKg.isFinite() && model.diagnostics.totalWaterKg > 0)
        assertTrue(model.diagnostics.totalModelEnergyJoules.isFinite())
        assertEquals(0.0, model.elapsedSeconds)
    }
}
