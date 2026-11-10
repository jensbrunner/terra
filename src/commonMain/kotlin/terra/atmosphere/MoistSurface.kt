package terra.atmosphere

/** Conservative surface exchange. Amounts are kg/m² of the atmospheric cell's full area.
 * Implementations own the water reservoirs; rain must never also enter the internal ocean.
 */
interface MoistSurface {
    fun heatCapacityJm2K(cell: Int): Double
    fun albedo(cell: Int): Double
    fun evaporate(cell: Int, potentialMm: Double): Double
    fun receiveRain(cell: Int, amountMm: Double)
    fun waterMm(cell: Int): Double
    val totalWaterKg: Double
    val exportedWaterKg: Double
}
