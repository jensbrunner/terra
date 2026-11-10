package terra.atmosphere

/** Conservative surface exchange. Amounts are kg/m² of the atmospheric cell's full area.
 * Implementations own the water reservoirs; rain must never also enter the internal ocean.
 */
interface MoistSurface {
    fun heatCapacityJm2K(cell: Int): Double
    fun albedo(cell: Int): Double
    /** Area-averaged positive terrain ascent; wind components are north-up, in m/s. */
    fun orographicUpliftMetersPerSecond(cell: Int, eastWind: Double, northWind: Double): Double = 0.0
    /** Relative to the configured bulk drag, with ocean = 1. */
    fun momentumDragMultiplier(cell: Int): Double = 1.0
    fun evaporate(cell: Int, potentialMm: Double): Double
    fun receiveRain(cell: Int, amountMm: Double)
    fun waterMm(cell: Int): Double
    val totalWaterKg: Double
    val exportedWaterKg: Double
}
