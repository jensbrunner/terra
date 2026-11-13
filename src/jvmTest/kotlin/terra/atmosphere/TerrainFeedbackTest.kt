package terra.atmosphere

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainFeedbackTest {
    private val config = DryAtmosphereConfig(width = 12, height = 8)
    private val closed = MoistPhysicsConfig(radiationEnabled = false, surfaceExchangeEnabled = false,
        momentumDragPerSecond = 0.0, convectiveVelocityCoefficient = 0.0)

    @Test
    fun resolvedConvergenceFormsCloudWhileDivergenceEvaporatesWithClosedWaterAndHeat() {
        val grid = config.copy(geometry = AtmosphereGeometry.PERIODIC_F_PLANE, coriolisPerSecond = 0.0)
        val model = MoistAtmosphere(grid, closed, initializeFlow = { x, _ ->
            DryCell(grid.meanHeightMeters, -15 * kotlin.math.sin(2 * kotlin.math.PI * x / grid.domainWidthMeters), 0.0)
        }, initializeColumns = { _, _ -> MoistCell(relativeHumidity = 0.998, cloudWaterKgM2 = 0.05) })
        val before = model.snapshot()
        model.step()
        val after = model.snapshot()
        assertTrue(after.cloudWaterMmAt(0, 3) > before.cloudWaterMmAt(0, 3))
        assertTrue(after.cloudWaterMmAt(grid.width / 2, 3) < before.cloudWaterMmAt(grid.width / 2, 3))
        assertTrue(abs(after.diagnostics.relativeWaterResidual) < 1e-12)
        assertTrue(abs(after.diagnostics.unexplainedEnergyResidualJoules / before.diagnostics.totalModelEnergyJoules) < 1e-10)
    }

    @Test
    fun terrainMappingPreservesUnresolvedRidgesWindDirectionAndNorthUpSlopes() {
        val grid = DryAtmosphereConfig(width = 8, height = 6)
        val mapping = AtmosphereTileMapping(32, 24, grid)
        // A ridge wholly within a single atmosphere column has zero coarse central
        // slope there, but both wind directions must still see its windward flank.
        val ridge = AtmosphereTerrain(mapping, DoubleArray(32 * 24) { if (it % 32 == 10) 3000.0 else 0.0 })
        val cell = 2 * grid.width + 2
        assertTrue(ridge.meanElevationMeters[cell] > 0)
        assertEquals(0.0, ridge.slopeEast[cell])
        assertTrue(ridge.upliftMetersPerSecond(cell, 20.0, 0.0) > 0)
        assertTrue(ridge.upliftMetersPerSecond(cell, -20.0, 0.0) > 0)
        assertEquals(0.0, ridge.upliftMetersPerSecond(cell, 0.0, 20.0))
        val northwardRise = AtmosphereTerrain(mapping, DoubleArray(32 * 24) { (23 - it / 32) * 100.0 })
        assertTrue(northwardRise.slopeNorth[cell] > 0)
        assertTrue(northwardRise.upliftMetersPerSecond(cell, 0.0, 20.0) > 0)
        assertEquals(0.0, northwardRise.upliftMetersPerSecond(cell, 0.0, -20.0))
        val hill = AtmosphereTerrain(mapping, DoubleArray(32 * 24) { (16 - abs(it % 32 - 16)) * 200.0 })
        assertTrue(hill.upliftMetersPerSecond(2 * grid.width + 2, 20.0, 0.0) > 0)
        assertEquals(0.0, hill.upliftMetersPerSecond(2 * grid.width + 5, 20.0, 0.0))
        assertTrue(hill.upliftMetersPerSecond(2 * grid.width + 5, -20.0, 0.0) > 0)
    }

    @Test
    fun windwardCondensationProducesRainAndVaporDepletionWithClosedBudgets() {
        val surface = TestSurface(config, slope = { cell -> if (cell % config.width < 6) 0.02 else -0.02 })
        val lifted = MoistAtmosphere(config, closed, surface = surface) { _, _ -> MoistCell(relativeHumidity = 0.98) }
        val control = MoistAtmosphere(config, closed) { _, _ -> MoistCell(relativeHumidity = 0.98) }
        lifted.step()
        control.step()
        var state = lifted.snapshot()
        assertTrue(state.cloudWaterMmAt(2, 2) > state.cloudWaterMmAt(9, 2))
        // Adiabatic parcel cooling is diagnostic; latent release pays for bulk warming.
        assertEquals(1.0, state.diagnostics.totalModelEnergyJoules / control.diagnostics().totalModelEnergyJoules, 1e-12)
        repeat(19) { lifted.step() }
        state = lifted.snapshot()
        assertTrue(state.rainTotalMmAt(2, 2) > state.rainTotalMmAt(9, 2))
        assertTrue(state.vaporMmAt(2, 2) < state.vaporMmAt(9, 2))
        assertTrue(abs(state.diagnostics.relativeWaterResidual) < 1e-12)
        assertEquals(0.0, state.diagnostics.netRadiationJoules)
    }

    @Test
    fun convectionNeedsInstabilityAndMoistureAndDoesNotCreateWaterOrEnergy() {
        fun create(surfaceTemperature: Double, humidity: Double) = MoistAtmosphere(config,
            closed.copy(convectiveVelocityCoefficient = 0.02), DryScenario.REST) { _, _ ->
            MoistCell(relativeHumidity = humidity, surfaceTemperatureKelvin = surfaceTemperature)
        }
        val warm = create(305.0, 0.98)
        val stable = create(280.0, 0.98)
        val dry = create(305.0, 0.0)
        warm.step(); stable.step(); dry.step()
        val w = warm.snapshot()
        assertTrue(w.cloudWaterMmAt(3, 3) > 0)
        assertEquals(0.0, stable.snapshot().cloudWaterMmAt(3, 3))
        assertEquals(0.0, dry.snapshot().cloudWaterMmAt(3, 3))
        assertTrue(w.temperatureKelvinAt(3, 3) > 280.0)
        assertTrue(abs(w.diagnostics.relativeWaterResidual) < 1e-12)
        assertTrue(abs(w.diagnostics.energyResidualJoules / w.diagnostics.totalModelEnergyJoules) < 1e-12)
    }

    @Test
    fun roughSurfaceDampsMoreStronglyAndReturnsTheEnergyAsHeat() {
        val rough = TestSurface(config, drag = { if (it % config.width < 6) 1.0 else 6.0 })
        val physics = closed.copy(momentumDragPerSecond = 1.0 / 86400)
        val model = MoistAtmosphere(config, physics, surface = rough) { _, _ -> MoistCell(relativeHumidity = 0.0) }
        val control = MoistAtmosphere(config, closed) { _, _ -> MoistCell(relativeHumidity = 0.0) }
        model.step(); control.step()
        val a = model.snapshot()
        val b = control.snapshot()
        assertEquals(exp(-config.stepSeconds / 86400), a.dynamics.eastWindAt(2, 2) / b.dynamics.eastWindAt(2, 2), 1e-12)
        assertEquals(exp(-6 * config.stepSeconds / 86400), a.dynamics.eastWindAt(9, 2) / b.dynamics.eastWindAt(9, 2), 1e-12)
        assertEquals(1.0, a.diagnostics.totalModelEnergyJoules / b.diagnostics.totalModelEnergyJoules, 1e-12)
        assertTrue(a.diagnostics.frictionHeatingJoules > 0)
    }
}

private class TestSurface(
    private val config: DryAtmosphereConfig,
    private val slope: (Int) -> Double = { 0.0 },
    private val drag: (Int) -> Double = { 1.0 }
) : MoistSurface {
    private val water = DoubleArray(config.cellCount) { 10_000.0 }
    override fun heatCapacityJm2K(cell: Int) = 41_800_000.0
    override fun albedo(cell: Int) = 0.18
    override fun orographicUpliftMetersPerSecond(cell: Int, eastWind: Double, northWind: Double) = max(0.0, eastWind * slope(cell))
    override fun momentumDragMultiplier(cell: Int) = drag(cell)
    override fun evaporate(cell: Int, potentialMm: Double) = minOf(potentialMm, water[cell]).also { water[cell] -= it }
    override fun receiveRain(cell: Int, amountMm: Double, snowFraction: Double) { water[cell] += amountMm }
    override fun waterMm(cell: Int) = water[cell]
    override val totalWaterKg get() = water.sum() * config.cellArea
    override val exportedWaterKg = 0.0
}
