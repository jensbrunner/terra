package terra.hydrology

import kotlin.math.exp
import kotlin.test.*

class SoilWaterBalanceTest {
    @Test fun dryRecessionMatchesAnalyticalFieldCapacityResponse() {
        val balance = SoilWaterBalance()
        balance.advance(180.0, 200.0, 0.0, 3 * 86400.0, 1.0)
        val expected = 96.0 + 84.0 * exp(-3.0 / 5)
        assertEquals(expected, balance.soilMm, 1e-12)
        assertEquals(180.0 - expected, balance.drainageMm, 1e-12)
        assertEquals(0.0, balance.runoffMm)
    }

    @Test fun constantRainIsIndependentOfIntervalSubdivisionIncludingSaturation() {
        for (initial in listOf(0.0, 96.0, 180.0, 200.0)) {
            for (rain in listOf(0.0, 100.0, 1000.0, 10000.0)) {
                val whole = SoilWaterBalance()
                whole.advance(initial, 200.0, rain, 10 * 86400.0, 0.5)
                val split = SoilWaterBalance()
                var soil = initial; var drainage = 0.0; var runoff = 0.0
                repeat(20) {
                    split.advance(soil, 200.0, rain / 20, 0.5 * 86400.0, 0.5)
                    soil = split.soilMm; drainage += split.drainageMm; runoff += split.runoffMm
                }
                assertEquals(whole.soilMm, soil, 1e-9)
                assertEquals(whole.drainageMm, drainage, 1e-9)
                assertEquals(whole.runoffMm, runoff, 1e-9)
                assertEquals(initial + rain, soil + drainage + runoff, 1e-9)
            }
        }
    }

    @Test fun evaporationCannotConsumeInfiltrationExcessAndRootWaterIsAccountedFor() {
        val balance = SoilWaterBalance()
        balance.advance(20.0, 200.0, 100.0, 3600.0, 1.0, wetSeconds = 1800.0,
            potentialEvaporationMm = 80.0, rootSupplyMm = 3.0)
        assertEquals(95.0, balance.runoffMm)
        assertEquals(28.0, balance.evaporationMm)
        assertEquals(0.0, balance.soilMm)
        assertEquals(0.0, balance.drainageMm)
        assertEquals(123.0, balance.soilMm + balance.evaporationMm + balance.runoffMm + balance.drainageMm)
    }
}
