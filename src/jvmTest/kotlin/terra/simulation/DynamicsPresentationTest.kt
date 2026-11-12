package terra.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.weather.WeatherSimulation
import terra.world.World

class DynamicsPresentationTest {
    @Test
    fun dynamicsDisappearAtClimateSelectionAndStayUnavailableUntilResolvedReentry() {
        val owner = WorldSimulation(WorldRequest(dynamical = true, width = 15, height = 11,
            atmosphere = DryAtmosphereConfig(width = 8, height = 6)))
        fun capture() = owner.snapshot(SimulationProgress(0, 1, 0, owner.completedSeconds,
            SimulationSpeed.ONE_HOUR, 0.0, 0.0, 0.0, 86400.0, false, 0.0, 0.0, 0))
        owner.advance(20.0, SimulationSpeed.ONE_HOUR)
        val detailed = capture().weather
        val pressure = detailed.pressureAnomalyPercent(4, 5)
        val curl = detailed.relativeVorticityPerSecond(4, 5)
        assertTrue(detailed.hasResolvedDynamics)
        assertNotNull(pressure); assertNotNull(curl)
        owner.setFidelity(SimulationFidelity.CLIMATE)
        assertFalse(capture().weather.hasResolvedDynamics)
        assertNull(capture().weather.pressureAnomalyPercent(4, 5))
        owner.advanceClimateInterval(86400.0)
        assertNull(capture().weather.relativeVorticityPerSecond(4, 5))
        owner.setFidelity(SimulationFidelity.DETAILED)
        assertTrue(capture().detailedReentryPending)
        assertFalse(capture().weather.hasResolvedDynamics)
        owner.advance(20.0, SimulationSpeed.ONE_HOUR)
        assertTrue(capture().weather.hasResolvedDynamics)
        assertNotNull(capture().weather.pressureAnomalyPercent(4, 5))
        assertEquals(pressure, detailed.pressureAnomalyPercent(4, 5))
        assertEquals(curl, detailed.relativeVorticityPerSecond(4, 5))
        val prescribed = WeatherSimulation(World(42, 8, 6))
        assertFalse(prescribed.hasResolvedDynamics)
        assertNull(prescribed.pressureAnomalyPercent(0, 0))
        assertNull(prescribed.relativeVorticityPerSecond(0, 0))
    }
}
