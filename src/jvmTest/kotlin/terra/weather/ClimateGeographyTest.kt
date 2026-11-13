package terra.weather

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.MoistPhysicsConfig
import terra.world.TerrainGenerationSettings
import terra.world.World

/**
 * Small causal counterfactuals for the reduced atmosphere. These are deliberately
 * cheap synthetic worlds, not a broad acceptance suite: a uniform control, two ridge
 * positions, and a reversed land/ocean thermal contrast.
 */
class ClimateGeographyTest {
    private val oceanSettings = TerrainGenerationSettings(baseElevation = 0.1, continentStrength = 0.0,
        regionalStrength = 0.0, detailStrength = 0.0)

    @Test
    fun thermallyUniformOceanHasNoInjectedConvergenceOrRainBelt() {
        val width = 36; val height = 24
        val world = World(7, width, height, oceanSettings)
        val fine = DryAtmosphereConfig(width = width, height = height)
        val grid = ClimateGrid(fine)
        val geography = ClimateGeography(world, grid)
        val circulation = DiagnosticCirculation(grid, geography)
        val uniform = DoubleArray(grid.count) { 285.0 }
        val state = circulation.advance(uniform, uniform, 10 * 86400.0, 0.25)
        val maximumWind = state.east.maxOf { abs(it) } + state.north.maxOf { abs(it) }
        val maximumConvergence = state.convergence.maxOf { abs(it) }
        val maximumAscent = state.ascent.maxOf { abs(it) }
        assertTrue(maximumWind < 1e-6, "uniform control wind $maximumWind")
        assertTrue(maximumConvergence < 1e-10, "uniform control convergence $maximumConvergence")
        assertTrue(maximumAscent < 1e-8, "uniform control ascent $maximumAscent")
        println("UNIFORM_CONTROL max_wind=$maximumWind max_convergence=$maximumConvergence max_ascent=$maximumAscent")
    }

    @Test
    fun movingARidgeMovesTheUpstreamRainoutAndLeewardDrying() {
        fun run(ridgeX: Int?): Pair<DoubleArray, DoubleArray> {
            val width = 36; val height = 20
            val elevation = DoubleArray(width * height) { index ->
                val x = index % width
                when {
                    x < 3 || x >= 26 -> 0.30
                    x == ridgeX -> 0.95
                    else -> 0.30
                }
            }
            val settings = TerrainGenerationSettings(baseElevation = 0.4, continentStrength = 0.0,
                regionalStrength = 0.0, detailStrength = 0.0)
            val world = World.withElevation(11, width, height, settings, elevation)
            val fine = DryAtmosphereConfig(width = width, height = height)
            val grid = ClimateGrid(fine)
            val geography = ClimateGeography(world, grid)
            val count = grid.count
            val east = DoubleArray(count) { 14.0 }
            val zero = DoubleArray(count)
            val ascent = DoubleArray(count) { geography.upliftMetersPerSecond(it, 14.0, 0.0) }
            val circulation = CirculationState(east, zero, east, zero, zero, ascent, zero, zero, 14.0, 14.0, 14.0)
            val moisture = ClimateMoisture(grid, geography, MoistPhysicsConfig(),
                WeatherRegimes(grid, 3))
            // Specify moisture by RH under the shared saturation convention.
            val capacity = terra.atmosphere.ColumnThermodynamics.saturationMm(265.0, 8000.0,
                fine.gravity, MoistPhysicsConfig())
            val water = DoubleArray(count) { capacity * 0.90 }
            val surface = DoubleArray(count) { -3.0 }
            val air = DoubleArray(count) { 265.0 }
            val fineElevation = DoubleArray(fine.cellCount) {
                maxOf(0.0, (elevation[it] - settings.seaLevel) * 10_000.0)
            }
            val rain = DoubleArray(fine.cellCount)
            repeat(10) {
                val result = moisture.advance(water, 86400.0, 100.0 * 86400.0, 365 * 86400.0,
                    circulation, air, surface, { 0.70 }, fineElevation)
                for (i in rain.indices) rain[i] += result.fineRainMm[i]
            }
            return rain to water
        }
        val flat = run(null)
        val ridge = run(8)
        val width = 36
        fun rowSum(values: DoubleArray, x0: Int, x1: Int) = (0 until 20).sumOf { y ->
            (x0..x1).sumOf { x -> values[y * width + x] }
        }
        val flatWindward = rowSum(flat.first, 7, 7)
        val ridgeWindward = rowSum(ridge.first, 7, 7)
        val flatLeeward = rowSum(flat.first, 10, 14)
        val ridgeLeeward = rowSum(ridge.first, 10, 14)
        assertTrue(ridgeWindward > flatWindward,
            "a ridge must increase windward rainout: flat=$flatWindward ridge=$ridgeWindward")
        val flatWater = rowSum(flat.second, 12, 14)
        val ridgeWater = rowSum(ridge.second, 12, 14)
        assertTrue(ridgeWater < flatWater, "ridge rainout must deplete downstream water: flat=$flatWater ridge=$ridgeWater")
        println("RIDGE_SHADOW flat_windward=$flatWindward ridge_windward=$ridgeWindward " +
            "flat_leeward=$flatLeeward ridge_leeward=$ridgeLeeward")
    }

    @Test
    fun rotatingARidgeRotatesItsOrographicRainShadow() {
        fun run(vertical: Boolean): Pair<DoubleArray, DoubleArray> {
            val width = 24; val height = 24
            val elevation = DoubleArray(width * height) { index ->
                val x = index % width; val y = index / width
                if (vertical && x == 12) 0.90 else if (!vertical && y == 12) 0.90 else 0.40
            }
            val settings = TerrainGenerationSettings(baseElevation = 0.4, continentStrength = 0.0,
                regionalStrength = 0.0, detailStrength = 0.0)
            val world = World.withElevation(21, width, height, settings, elevation)
            val fine = DryAtmosphereConfig(width = width, height = height)
            val grid = ClimateGrid(fine)
            val geography = ClimateGeography(world, grid)
            val east = DoubleArray(grid.count) { if (vertical) 14.0 else 0.0 }
            val north = DoubleArray(grid.count) { if (vertical) 0.0 else 14.0 }
            val zero = DoubleArray(grid.count)
            val ascent = DoubleArray(grid.count) { geography.upliftMetersPerSecond(it, east[it], north[it]) }
            val circulation = CirculationState(east, north, east, north, zero, ascent, zero, zero, 14.0, 14.0, 14.0)
            val moisture = ClimateMoisture(grid, geography, MoistPhysicsConfig(), WeatherRegimes(grid, 9))
            val capacity = terra.atmosphere.ColumnThermodynamics.saturationMm(265.0, 8000.0,
                fine.gravity, MoistPhysicsConfig())
            val water = DoubleArray(grid.count) { capacity * 0.90 }
            val air = DoubleArray(grid.count) { 265.0 }
            val surface = DoubleArray(grid.count) { -3.0 }
            val fineElevation = DoubleArray(fine.cellCount) { maxOf(0.0, (elevation[it] - settings.seaLevel) * 10_000.0) }
            val rain = DoubleArray(fine.cellCount)
            repeat(10) {
                val result = moisture.advance(water, 86400.0, 100.0 * 86400.0, 365 * 86400.0,
                    circulation, air, surface, { 0.70 }, fineElevation)
                for (i in rain.indices) rain[i] += result.fineRainMm[i]
            }
            return rain to water
        }
        val vertical = run(true)
        val horizontal = run(false)
        val width = 24
        fun at(values: DoubleArray, x: Int, y: Int) = values[y * width + x]
        assertTrue(at(vertical.first, 11, 12) > 0.0, "vertical ridge must rain on its upwind slope")
        assertTrue(at(horizontal.first, 12, 10) > 0.0, "horizontal ridge must rain on its upwind slope")
        println("RIDGE_ROTATION vertical_windward=${at(vertical.first,11,12)} " +
            "horizontal_windward=${at(horizontal.first,12,10)}")
    }

    @Test
    fun reversingLandOceanThermalContrastReversesTheLowLevelBreeze() {
        val width = 24; val height = 12
        val world = World.withElevation(5, width, height, TerrainGenerationSettings(
            baseElevation = 0.4, continentStrength = 0.0, regionalStrength = 0.0, detailStrength = 0.0),
            DoubleArray(width * height) { if (it % width >= width / 2) 0.45 else 0.1 })
        val fine = DryAtmosphereConfig(width = width, height = height)
        val grid = ClimateGrid(fine)
        val geography = ClimateGeography(world, grid)
        val land = geography.landFraction
        fun breeze(landAnomaly: Double): Double {
            val surface = DoubleArray(grid.count) { 285.0 + landAnomaly * land[it] }
            val air = DoubleArray(grid.count) { 280.0 }
            val state = DiagnosticCirculation(grid, geography)
                .advance(air, surface, 10 * 86400.0, 0.25)
            // Near the coast, the thermally direct low-level flow points onshore.
            val x = width / 2 - 2
            return state.lowEast[(height / 2) * width + x]
        }
        val warmLand = breeze(12.0)
        val coolLand = breeze(-12.0)
        assertTrue(warmLand > 0.0, "warm land should draw low-level inflow from the ocean, got $warmLand")
        assertTrue(coolLand < 0.0, "cool land should reverse the coastal low-level flow, got $coolLand")
        println("SEASONAL_BREEZE warm_land=$warmLand cool_land=$coolLand")
    }
}
