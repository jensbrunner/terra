package terra.atmosphere

/** Immutable model-relative pressure and physical relative curl, on the north-up solver grid. */
class AtmosphericDynamicsSnapshot internal constructor(view: MoistAtmosphereSnapshot) {
    val width = view.dynamics.config.width
    val height = view.dynamics.config.height
    private val pressure = DoubleArray(width * height)
    private val curl = DoubleArray(width * height)

    init {
        val dry = view.dynamics
        for (y in 0 until height) {
            var mean = 0.0
            for (x in 0 until width) {
                val h = dry.heightAt(x, y)
                // g/(2*Tref) cancels in the anomaly, so no invented hPa conversion.
                val p = h * h * view.temperatureKelvinAt(x, y)
                pressure[y * width + x] = p
                mean += p / width
                curl[y * width + x] = dry.vorticityAt(x, y)
            }
            for (x in 0 until width) {
                val i = y * width + x
                pressure[i] = 100 * (pressure[i] - mean) / mean
            }
        }
    }

    fun pressureAnomalyPercentAt(x: Int, y: Int) = pressure[y * width + x]
    fun relativeVorticityPerSecondAt(x: Int, y: Int) = curl[y * width + x]
}
