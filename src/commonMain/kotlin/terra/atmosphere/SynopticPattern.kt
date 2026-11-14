package terra.atmosphere

import kotlin.random.Random
import kotlin.math.*

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

    /** Four smooth geostrophic lows, shared by startup and climate reconstruction. */
    fun flow(config: DryAtmosphereConfig, x: Double, y: Double): DryCell {
        var height = config.meanHeightMeters; var east = 0.0; var north = 0.0
        val radius = config.domainWidthMeters * 0.065
        for (hemisphere in 0..1) for (system in 0..1) {
            val cx = centerX(hemisphere, system) * config.domainWidthMeters
            val cy = centerNorth(hemisphere, system) * config.domainHeightMeters
            val dx = x - cx - floor((x - cx) / config.domainWidthMeters + 0.5) * config.domainWidthMeters
            val dy = y - cy
            val f = if (config.periodicY) config.coriolisPerSecond else
                2 * config.rotationRateRadiansPerSecond * sin(PlanetLatitude.radians(centerNorth(hemisphere, system)))
            if (abs(f) < 1e-8) continue
            val amplitude = 28 * abs(f) * radius * exp(0.5) / config.gravity
            val bell = exp(-(dx * dx + dy * dy) / (2 * radius * radius))
            height -= amplitude * bell
            val gradient = amplitude * bell / (radius * radius)
            east -= config.gravity / f * gradient * dy
            north += config.gravity / f * gradient * dx
        }
        return DryCell(height, east, north)
    }

    fun centerX(hemisphere: Int, system: Int) = east[hemisphere * 2 + system]
    fun centerNorth(hemisphere: Int, system: Int) = north[hemisphere * 2 + system]
}
