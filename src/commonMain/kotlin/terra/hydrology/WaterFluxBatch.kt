package terra.hydrology

import terra.weather.WeatherModel

/** Surface side of routing. Lake evaporation is returned only after an actual store withdrawal. */
interface HydrologyExchange {
    fun updateLakeWaterMask(mask: BooleanArray)
    fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double)
    fun hydrologyAccepted() {}
}

/** One completed interval's transfers, not rates or additional reservoirs.
 * Each fine tile has unit area in mm·tiles accounting, so a tile's mm depth is its mm·tiles amount.
 * A batch is single-owner and accepted at most once, including across different hydrology instances.
 */
class WaterFluxBatch private constructor(
    val width: Int, val height: Int, val durationSeconds: Double,
    internal val exchange: HydrologyExchange,
    private val runoff: (Int) -> Double, private val lakeRain: (Int) -> Double,
    private val potentialLakeEvaporation: (Int) -> Double, private val current: () -> Boolean,
    /** Nonzero tiles only; null means scan the full grid (compatibility path). */
    internal val activeTiles: IntArray? = null
) {
    var consumed: Boolean = false
        private set
    internal fun runoffMmAt(tile: Int) = runoff(tile)
    internal fun directLakeRainMmAt(tile: Int) = lakeRain(tile)
    internal fun potentialLakeEvaporationMmAt(tile: Int) = potentialLakeEvaporation(tile)

    internal fun claim() {
        check(!consumed) { "Water flux batch was already accepted" }
        check(current()) { "Water flux batch no longer belongs to the current surface update" }
        consumed = true // A failed routing transaction must never be replayed either.
    }

    companion object {
        /** Copies caller arrays. Runoff/rain must already have been debited from their donors.
         * Potential evaporation is a demand, not water; hydrology confirms actual evaporation.
         */
        fun fromTileDepths(
            width: Int, height: Int, durationSeconds: Double, runoffMmByTile: DoubleArray,
            directLakeRainMmByTile: DoubleArray, potentialLakeEvaporationMmByTile: DoubleArray,
            exchange: HydrologyExchange
        ): WaterFluxBatch {
            dimensions(width, height, durationSeconds)
            val fields = arrayOf(runoffMmByTile, directLakeRainMmByTile, potentialLakeEvaporationMmByTile).map { field ->
                require(field.size == width * height && field.all { it.isFinite() && it >= 0.0 })
                field.copyOf()
            }
            val active = (0 until width * height).filter {
                fields[0][it] > 0.0 || fields[1][it] > 0.0 || fields[2][it] > 0.0
            }.toIntArray()
            return WaterFluxBatch(width, height, durationSeconds, exchange,
                { fields[0][it] }, { fields[1][it] }, { fields[2][it] }, { true }, active)
        }

        /** Owner-thread view avoids full-grid copies on every detailed 20-second step.
         * Source storage cannot be cleared/reused before acceptance, and old epochs are rejected.
         */
        internal fun borrowed(
            width: Int, height: Int, durationSeconds: Double, exchange: HydrologyExchange,
            runoff: (Int) -> Double, lakeRain: (Int) -> Double,
            potentialLakeEvaporation: (Int) -> Double, current: () -> Boolean,
            activeTiles: IntArray? = null
        ): WaterFluxBatch {
            dimensions(width, height, durationSeconds)
            require(activeTiles == null || activeTiles.all { it in 0 until width * height })
            return WaterFluxBatch(width, height, durationSeconds, exchange, runoff, lakeRain,
                potentialLakeEvaporation, current, activeTiles)
        }

        internal fun fromWeather(weather: WeatherModel, seconds: Double, tracked: Boolean = false): WaterFluxBatch {
            var batch: WaterFluxBatch? = null
            batch = borrowed(weather.width, weather.height, seconds, weather,
                { tile -> weather.drainageMmThisUpdate(tile % weather.width, tile / weather.width) +
                    weather.overflowMmThisUpdate(tile % weather.width, tile / weather.width) },
                { tile -> weather.lakeDirectRainMmThisUpdate(tile % weather.width, tile / weather.width) },
                { tile -> weather.lakePotentialEvaporationMmThisUpdate(tile % weather.width, tile / weather.width) },
                { !tracked || weather.pendingWaterFluxBatch === batch })
            return batch
        }

        private fun dimensions(width: Int, height: Int, seconds: Double) {
            require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
            require(seconds.isFinite() && seconds > 0.0)
        }
    }
}

/** Diagnostic amounts read from the sole hydrology stores. Potential evaporation is not water. */
data class HydrologyWaterInventory(
    val oceanMmTiles: Double, val lakesMmTiles: Double, val pendingRunoffMmTiles: Double,
    val pendingLakeRainMmTiles: Double, val pendingPotentialLakeEvaporationMmTiles: Double,
    val pendingDurationSeconds: Double, val channelsMmTiles: Double = 0.0
) {
    val totalWaterMmTiles: Double get() = oceanMmTiles + lakesMmTiles + channelsMmTiles + pendingRunoffMmTiles + pendingLakeRainMmTiles
}
