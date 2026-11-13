package terra.hydrology

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelRoutingTest {
    private val exchange = object : HydrologyExchange {
        override fun updateLakeWaterMask(mask: BooleanArray) {}
        override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double) = error("No evaporation")
    }

    @Test
    fun upstreamPulseArrivesLaterAndAllWaterRemainsInTheSameInventory() {
        val width = 96
        val topology = HydrologyTopology(width, 1,
            DoubleArray(width) { min(it, width - it) * 0.01 }, BooleanArray(width) { it == 0 })
        val hydrology = HydrologySimulation(topology)
        val before = hydrology.storedAndPendingWaterMmTiles
        val outflow = DoubleArray(60)
        repeat(outflow.size) { day ->
            val runoff = DoubleArray(width) { if (day == 0 && it == 40) 100.0 else 0.0 }
            hydrology.acceptFluxBatch(WaterFluxBatch.fromTileDepths(width, 1, 86400.0,
                runoff, DoubleArray(width), DoubleArray(width), exchange))
            outflow[day] = hydrology.outletMmTilesLastPeriod
            assertEquals(before + 100.0, hydrology.storedAndPendingWaterMmTiles, 1e-8)
            assertEquals(hydrology.storedAndPendingWaterMmTiles, hydrology.waterInventory().totalWaterMmTiles, 1e-8)
        }
        val peakDay = outflow.indices.maxBy { outflow[it] }
        assertTrue(peakDay in 5..18, "A forty-reach river should retain and delay its pulse: peak=$peakDay")
        assertTrue(outflow[peakDay] > outflow[0] * 100)
        assertEquals(100.0, outflow.sum(), 1e-8)
        assertTrue(hydrology.channelStorageMmTiles < 1e-8)
        println("CHANNEL_PULSE peak_day=$peakDay first_day=${outflow[0]} peak=${outflow[peakDay]} total=${outflow.sum()}")
    }
}
