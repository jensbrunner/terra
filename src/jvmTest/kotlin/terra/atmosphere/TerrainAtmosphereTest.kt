package terra.atmosphere

import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.weather.DynamicalWeather
import terra.world.World
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainAtmosphereTest {
    @Test
    fun polarDisplaySamplesTheAdjacentHemisphereWithoutWrapping() {
        val world = World(31742, 12, 24)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val config = DryAtmosphereConfig(width = 12, height = 8)
        val weather = DynamicalWeather(world, hydrology, config,
            MoistPhysicsConfig(solar = SolarConfig(initialYearFraction = 0.25)))
        assertEquals(0.0, weather.nightOpacity(0, 0))
        assertEquals(0.65, weather.nightOpacity(0, 23))
        assertTrue(weather.solarInputFactor(0, 0) > 0)
        assertEquals(0.0, weather.solarInputFactor(0, 23))
        val initial = DryScenarios.create(config = config, seed = world.seed).snapshot()
        val factor = -86400.0 * world.height / config.domainHeightMeters
        assertEquals(initial.northWindAt(3, 7) * factor, weather.windVTilesPerDay(3, 0), 1e-12)
        assertEquals(initial.northWindAt(3, 0) * factor, weather.windVTilesPerDay(3, 23), 1e-12)
    }

    @Test
    fun nonDivisibleGridsCoverEveryTileAndConserveRainArea() {
        val config = DryAtmosphereConfig(width = 12, height = 8)
        val mapping = AtmosphereTileMapping(31, 23, config)
        val coverage = DoubleArray(31 * 23)
        val rain = DoubleArray(coverage.size)
        var coarseTotal = 0.0
        for (cell in 0 until config.cellCount) {
            val amount = 0.1 + cell * 0.03
            coarseTotal += amount * config.cellArea
            var cellCoverage = 0.0
            mapping.forCell(cell) { tile, fraction ->
                coverage[tile] += fraction
                rain[tile] += amount * fraction
                cellCoverage += fraction
            }
            assertEquals(mapping.tilesPerCell, cellCoverage, 1e-12)
        }
        coverage.forEach { assertEquals(1.0, it, 1e-12) }
        assertTrue(abs(rain.sum() * mapping.tileAreaM2 / coarseTotal - 1) < 1e-12)
        // The first atmospheric row is the northern (top) edge of the game map.
        assertEquals(0, mapping.tiles[mapping.offsets[(config.height - 1) * config.width]])
    }

    @Test
    fun coupledRainSoilLakeAndOutletTransfersCloseTheWaterBudget() {
        val world = World(31742, 31, 23)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val config = DryAtmosphereConfig(width = 12, height = 8)
        val weather = DynamicalWeather(world, hydrology, config)
        val targetSeconds = 3 * 86400.0
        while (weather.elapsedSeconds < targetSeconds) {
            val advanced = weather.advanceBudgeted(min(128 * config.stepSeconds, targetSeconds - weather.elapsedSeconds), 128)
            hydrology.acceptWeatherStep(weather, advanced)
            assertTrue(abs(weather.diagnostics.relativeWaterResidual) < 1e-10)
        }
        assertTrue(weather.diagnostics.totalRainKg > 0)
        // Hydrology routes an entire accepted batch after it crosses the daily cadence.
        assertTrue(hydrology.routedPeriodRevision >= 2)
        val climate = weather.observedClimate()!!
        assertEquals(3.0, climate.sampledDays)
        assertTrue((0 until world.height).any { y -> (0 until world.width).any { x -> climate.precipitationTotalMm(x, y) > 0 } })
    }
}
