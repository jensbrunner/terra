package terra.atmosphere

/** Sole coarse surface-temperature store, shared by detailed and reduced climate evolution.
 * Mutations are owner-thread only. A climate energy approximation must account for its own forcing;
 * exposing this state does not turn a prescribed temperature change into conserved energy.
 */
class SurfaceThermalState internal constructor(val cellCount: Int) {
    internal val kelvin = DoubleArray(cellCount)
    operator fun get(cell: Int): Double = kelvin[cell]
    internal operator fun set(cell: Int, temperatureKelvin: Double) {
        require(temperatureKelvin.isFinite() && temperatureKelvin > 0.0)
        kelvin[cell] = temperatureKelvin
    }
}
