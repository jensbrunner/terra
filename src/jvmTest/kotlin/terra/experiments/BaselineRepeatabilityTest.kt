package terra.experiments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaselineRepeatabilityTest {
    @Test
    fun publicStateRepeatsAcrossMixedOuterCallsAndHydrologyRouting() {
        val first = BaselineWorld(width = 16, height = 12)
        val second = BaselineWorld(width = 16, height = 12)
        first.bootstrap(); second.bootstrap()
        assertEquals(first.observableHash(), second.observableHash())
        val initialRevision = first.hydrology.routedPeriodRevision
        repeat(6) {
            for (duration in listOf(0.05, 180.0, 10_800.0, 129_600.0)) {
                first.advance(duration)
                // Reading diagnostics between steps must not affect a subsequent transition.
                first.observableHash()
                second.advance(duration)
                assertEquals(first.observableHash(), second.observableHash())
            }
        }
        assertTrue(first.hydrology.routedPeriodRevision > initialRevision)
    }

    @Test
    fun rainObservationsAndScheduleReplayLeaveTheSamePublicState() {
        val observed = BaselineWorld(width = 16, height = 12)
        val direct = BaselineWorld(width = 16, height = 12)
        val schedule = durationSchedule(5.0 * SECONDS_PER_DAY, 129_600.0)
        val rain = accumulateRain(observed, schedule)
        schedule.forEach(direct::advance)
        assertEquals(5.0 * SECONDS_PER_DAY, schedule.sum())
        assertTrue(rain.all { it.isFinite() && it >= 0 })
        assertEquals(direct.observableHash(), observed.observableHash())
    }

    @Test
    fun timingSamplesKeepTheTerminalStallAndExposeOverflow() {
        val frames = FrameSamples(capacity = 3)
        frames.add(0.016, 0.016)
        frames.add(0.017, 0.017)
        frames.add(2.5, 0.1) // A capped renderer delta must not conceal a 2.5-second stall.
        assertEquals(2.5, frames.percentile(0.95))
        assertEquals(0.017, frames.percentile(0.5))
        frames.add(0.016, 0.016)
        assertEquals(1, frames.dropped)
        assertTrue(frames.percentile(0.95).isNaN())
    }
}
