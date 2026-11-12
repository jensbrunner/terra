package terra.weather

import kotlin.random.Random

/** Temporary initial pressure-low positions, never a rendering or humidity mask. */
internal class SynopticPattern(seed: Int) {
    private val east = DoubleArray(4)
    private val north = DoubleArray(4)

    init {
        val random = Random(seed xor 0x41c735)
        for (hemisphere in 0..1) for (system in 0..1) {
            val i = hemisphere * 2 + system
            east[i] = (system + random.nextDouble()) / 2
            north[i] = if (hemisphere == 0) random.nextDouble(0.15, 0.40)
                else random.nextDouble(0.60, 0.85)
        }
    }

    fun centerX(hemisphere: Int, system: Int) = east[hemisphere * 2 + system]
    fun centerNorth(hemisphere: Int, system: Int) = north[hemisphere * 2 + system]
}
