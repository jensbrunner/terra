package terra.weather

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import terra.atmosphere.*
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.simulation.SimulationProgress
import terra.simulation.SimulationSpeed
import terra.simulation.WorldRequest
import terra.simulation.WorldSimulation
import terra.world.TerrainGenerationSettings
import terra.world.World

class FastClimateStepperTest {
    private val config = DryAtmosphereConfig(width = 8, height = 6)
    private val day = 86400.0
    private val request = WorldRequest(generation = 1, width = 15, height = 11, dynamical = true, atmosphere = config)

    @Test
    fun ownerAdvancesDayAndWeekWithNoFurtherFluidStepsAndFrozenIntervalPresentation() {
        val owner = WorldSimulation(request)
        fun capture() = owner.snapshot(SimulationProgress(1, 1, 0, owner.completedSeconds,
            SimulationSpeed.ONE_DAY, 0.0, 0.0, 0.0, day, false, 0.0, 0.0, 0))
        owner.advance(20.0, SimulationSpeed.ONE_HOUR)
        val detailed = capture()
        val first = owner.advanceClimateInterval(day)
        val daily = capture()
        val frozenRain = daily.weather.rainMmThisUpdate(3, 3)
        val frozenSoil = daily.weather.soilWaterMm(3, 3)
        val frozenHumidity = daily.weather.relativeHumidity(3, 3)
        val frozenCloud = daily.weather.cloudWaterMm(3, 3)
        assertEquals(20 + day, owner.completedSeconds)
        assertEquals(1L, first.detailedCompletedSteps)
        assertEquals(1, first.subperiods)
        assertEquals(0L, first.routingPasses) // Catchment routing accumulates to a monthly cadence.
        assertEquals(day, first.representedHydrologySeconds)
        assertTrue(abs(first.relativeWaterResidual) < 1e-12)
        assertTrue(first.rainfallKg > 0 && first.evaporationKg > 0)
        assertEquals(detailed.diagnostics!!.totalWaterKg, first.waterBeforeKg, first.waterBeforeKg * 1e-12)
        assertEquals(WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL, daily.weather.fieldSemantics.quality)
        assertEquals(day, daily.weather.fieldSemantics.intervalDurationSeconds)
        assertEquals(null, daily.diagnostics)
        assertEquals(first, daily.climateDiagnostics)
        assertTrue(first.energyApproximate)
        val week = owner.advanceClimateInterval(7 * day)
        val weekly = capture()
        assertEquals(20 + 8 * day, owner.completedSeconds)
        assertEquals(owner.completedSeconds, weekly.weather.elapsedSeconds)
        assertEquals(1L, week.detailedCompletedSteps)
        assertEquals(1, week.subperiods)
        assertEquals(0L, week.routingPasses)
        assertEquals(7 * day, week.representedHydrologySeconds)
        assertTrue(weekly.hydrology.aggregatedClimateInterval)
        assertEquals(7.0, weekly.hydrology.routedPeriodDays)
        assertTrue(abs(week.relativeWaterResidual) < 1e-12)
        assertEquals(first.waterBeforeKg, week.waterAfterKg, week.waterAfterKg * 1e-12)
        assertEquals(frozenRain, daily.weather.rainMmThisUpdate(3, 3))
        assertEquals(frozenSoil, daily.weather.soilWaterMm(3, 3))
        assertEquals(frozenHumidity, daily.weather.relativeHumidity(3, 3))
        assertEquals(frozenCloud, daily.weather.cloudWaterMm(3, 3))
        var hasEventPeak = false
        for (y in 0 until request.height) for (x in 0 until request.width) {
            val weather = weekly.weather
            val events = weather.rainEventSummary(x, y)!!
            assertTrue(events.wetFraction in 0.0..1.0)
            if (weather.rainMmThisUpdate(x, y) > 0) {
                assertTrue(events.wetFraction > 0)
                assertTrue(weather.peakSubstepPrecipitationMmPerHour(x, y) + 1e-12 >=
                    weather.precipitationMmPerHour(x, y) / events.wetFraction)
            }
            assertTrue(events.intenseRainMm >= 0 && events.intenseRainMm <= weather.rainMmThisUpdate(x, y) + 1e-12)
            assertTrue(weather.soilWaterMm(x, y) in 0.0..250.00000001)
            assertTrue(weather.atmosphericWaterMm(x, y) >= 0.0)
            assertTrue(weather.relativeHumidity(x, y) in 0.0..1.0)
            assertTrue(weather.cloudWaterMm(x, y) >= 0.0)
            assertTrue(weather.surfaceTemperatureC(x, y).isFinite())
            assertEquals(weather.rainMmThisUpdate(x, y) * 3600 / (7 * day), weather.precipitationMmPerHour(x, y), 1e-12)
            if (weather.peakSubstepPrecipitationMmPerHour(x, y) > weather.precipitationMmPerHour(x, y) * 1.1) hasEventPeak = true
        }
        val totalTileRain = (0 until request.height).sumOf { y -> (0 until request.width).sumOf { x ->
            weekly.weather.rainMmThisUpdate(x, y)
        } }
        val tileArea = config.domainWidthMeters * config.domainHeightMeters / (request.width * request.height)
        assertEquals(week.rainfallKg, totalTileRain * tileArea, week.rainfallKg * 1e-12,
            "Every tile's published rain must reconcile with the physical atmospheric rain volume")
        assertTrue(hasEventPeak, "Wet-phase intensity must differ from interval mean")
        assertEquals(0.0, owner.advance(20.0, SimulationSpeed.ONE_HOUR)) // Climate waits for its fixed day quantum.
        assertEquals(20 + 8 * day, owner.completedSeconds)
        assertFailsWith<IllegalArgumentException> { owner.advanceClimateInterval(21 * 365 * day) }
        assertEquals(20 + 8 * day, owner.completedSeconds)
    }

    @Test
    fun representativeCloudColumnDoesNotGrowWithTheRainAccumulationWindow() {
        fun meanCloud(seconds: Double): Double {
            val world = World(request.seed, request.width, request.height)
            val hydrology = HydrologySimulation(HydrologyTopology(world))
            val weather = DynamicalWeather(world, hydrology, config)
            val result = weather.advanceClimateInterval(seconds)
            assertTrue(abs(result.relativeWaterResidual) < 1e-12)
            return (0 until request.height).sumOf { y ->
                (0 until request.width).sumOf { x -> weather.cloudWaterMm(x, y) }
            } / (request.width * request.height)
        }
        val hour = meanCloud(3600.0)
        val fullDay = meanCloud(day)
        assertTrue(hour > 0.0)
        assertTrue(fullDay / hour in 0.5..2.0,
            "Changing the rain accumulation window must not multiply cloudiness by its duration: $hour -> $fullDay")
        println("CLOUD_PERIOD hour_mean_mm=$hour day_mean_mm=$fullDay ratio=${fullDay / hour}")
    }

    @Test
    fun humidityCloudEstimateNeedsMoistureButNotRain() {
        val humid = FastClimateStepper.humidityProxy(10.0, 10.0)
        val dry = FastClimateStepper.humidityProxy(1.0, 10.0)
        assertTrue(FastClimateStepper.cloudProxyMm(10.0, humid, 0.0) > 0.0)
        assertEquals(0.0, FastClimateStepper.cloudProxyMm(1.0, dry, 0.0))
        assertEquals(0.0, FastClimateStepper.cloudProxyMm(0.0, 0.0, 0.0))
        assertTrue(FastClimateStepper.cloudProxyMm(0.01, 1.0, 0.0) <= 0.01)
        assertEquals(1.0, FastClimateStepper.humidityProxy(20.0, 10.0))
    }

    @Test
    fun rainDemandCannotCreateWaterInAnExhaustedWorld() {
        val settings = TerrainGenerationSettings(baseElevation = 0.1, continentStrength = 0.0, regionalStrength = 0.0, detailStrength = 0.0)
        val world = World(31742, 15, 11, settings)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        hydrology.evaporateOceanWaterMmTiles(hydrology.oceanWaterMmTiles)
        val surface = TerrainMoistSurface(world, AtmosphereTileMapping(world.width, world.height, config), hydrology)
        val atmosphere = MoistAtmosphere(config, surface = surface, initializeColumns = { _, _ ->
            MoistCell(relativeHumidity = 0.0, cloudWaterKgM2 = 0.0, surfaceWaterKgM2 = 0.0)
        })
        val history = SeasonalClimate(config.width, config.height, 365 * day)
        val stepper = FastClimateStepper(surface, hydrology, atmosphere, history,
            ClimateNormals(world, surface, SolarConfig()), 0.0)
        val empty = stepper.advance(day)
        val result = empty.diagnostics
        for (c in 0 until config.cellCount) {
            assertEquals(0.0, empty.cloudMm(c))
            assertEquals(0.0, empty.relativeHumidity(c))
        }
        assertTrue(result.requestedRainfallKg > 0)
        assertEquals(0.0, result.waterBeforeKg)
        assertEquals(0.0, result.waterAfterKg)
        assertEquals(0.0, result.rainfallKg)
        assertEquals(0.0, result.evaporationKg)
        assertEquals(0L, atmosphere.completedSteps)
        assertEquals(day, history.elapsedSeconds)
        assertTrue(surface.rain.all { it == 0.0 })
        assertEquals(0.0, hydrology.waterInventory().totalWaterMmTiles)
    }

    @Test
    fun unsaturatedAtmosphericWaterCannotBeSpentAsRain() {
        val settings = TerrainGenerationSettings(baseElevation = 0.1, continentStrength = 0.0,
            regionalStrength = 0.0, detailStrength = 0.0)
        val world = World(31742, 15, 11, settings)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        hydrology.evaporateOceanWaterMmTiles(hydrology.oceanWaterMmTiles)
        val surface = TerrainMoistSurface(world, AtmosphereTileMapping(world.width, world.height, config), hydrology)
        val atmosphere = MoistAtmosphere(config, scenario = DryScenario.REST, surface = surface,
            initializeColumns = { _, _ -> MoistCell(relativeHumidity = 0.25, surfaceWaterKgM2 = 0.0) })
        val water = atmosphere.atmosphericWaterKg
        val stepper = FastClimateStepper(surface, hydrology, atmosphere,
            SeasonalClimate(config.width, config.height, 365 * day),
            ClimateNormals(world, surface, SolarConfig()), 0.0)
        val result = stepper.advance(3600.0).diagnostics
        assertTrue(water > 0 && result.requestedRainfallKg > 0)
        assertEquals(0.0, result.evaporationKg)
        assertEquals(0.0, result.rainfallKg)
        assertEquals(water, atmosphere.atmosphericWaterKg, water * 1e-12)
        assertEquals(0L, atmosphere.completedSteps)
    }

    @Test
    fun fixedScheduleReproducesAndUnacceptedDetailedWaterCannotBeSkipped() {
        val world = World(31742, 15, 11)
        val hydrology = HydrologySimulation(HydrologyTopology(world))
        val weather = DynamicalWeather(world, hydrology, config)
        weather.advanceBudgeted(20.0, 1)
        assertFailsWith<IllegalStateException> { weather.advanceClimateInterval(day) }
        assertEquals(20.0, weather.elapsedSeconds)
        assertTrue(!weather.isClimateMode)
        hydrology.acceptWeatherStep(weather, 20.0)
        val otherHydrology = HydrologySimulation(HydrologyTopology(World(31742, 15, 11)))
        val other = DynamicalWeather(World(31742, 15, 11), otherHydrology, config)
        other.advanceBudgeted(20.0, 1)
        otherHydrology.acceptWeatherStep(other, 20.0)
        for (seconds in listOf(day, 7 * day)) {
            val a = weather.advanceClimateInterval(seconds)
            val b = other.advanceClimateInterval(seconds)
            assertEquals(a, b)
            assertEquals(weather.elapsedSeconds, weather.climateHistory.elapsedSeconds)
            assertEquals(1L, weather.detailedCompletedSteps)
            for (y in 0 until 11) for (x in 0 until 15) {
                assertEquals(weather.rainMmThisUpdate(x, y), other.rainMmThisUpdate(x, y))
                assertEquals(weather.surfaceTemperatureC(x, y), other.surfaceTemperatureC(x, y))
            }
        }
    }
}
