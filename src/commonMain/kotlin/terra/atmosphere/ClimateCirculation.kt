package terra.atmosphere

import kotlin.math.*

internal data class ReconstructedCirculation(
    val depth: DoubleArray, val east: DoubleArray, val north: DoubleArray,
    val maximumSpeed: Double, val rmsSpeed: Double, val pressureExponent: Double
)

/** Stateless reentry from evolved thermal geography and seeded synoptic lows.
 * Uses this model's force -(1/h) grad(g h² T / 2Tref), not the dry g grad(h) law.
 */
internal object ClimateCirculation {
    fun reconstruct(config: DryAtmosphereConfig, referenceTemperature: Double,
        temperature: DoubleArray, carrierDepthSum: Double, weatherPhase: Double? = null, seed: Int = 0): ReconstructedCirculation {
        val width = config.width; val height = config.height
        var smooth = temperature.copyOf()
        var scratch = DoubleArray(config.cellCount)
        // Broad thermal features only: avoid projecting sharp coastal contrasts into fast winds.
        repeat(3) {
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                scratch[i] = 0.5 * smooth[i] + 0.125 * (smooth[y * width + (x + width - 1) % width] +
                    smooth[y * width + (x + 1) % width] + smooth[config.southRow(y) * width + x] +
                    smooth[config.northRow(y) * width + x])
            }
            val old = smooth; smooth = scratch; scratch = old
        }
        val mean = DoubleArray(height) { y -> (0 until width).sumOf { smooth[y * width + it] } / width }
        val f0 = 2e-5 // A smooth equatorial transition; no division by f at the equator.
        val rotationalWeight = DoubleArray(height) { y ->
            val f = config.coriolisAtRow(y)
            f * f / (f * f + f0 * f0)
        }
        val zonal = DoubleArray(height)
        for (y in 1 until height) zonal[y] = zonal[y - 1] +
            ln(mean[y] / mean[y - 1]) * 0.5 * (rotationalWeight[y] + rotationalWeight[y - 1])
        val shape = DoubleArray(config.cellCount) { i ->
            val y = i / width
            if (config.periodicY) rotationalWeight[y] * ln(smooth[i] / referenceTemperature) else {
                val wallEnvelope = sin(PI * (y + 0.5) / height).pow(2)
                zonal[y] + rotationalWeight[y] * wallEnvelope * ln(smooth[i] / mean[y])
            }
        }
        if (weatherPhase != null) {
            // A shallow layer cannot convert full surface coastal temperature jumps
            // into atmospheric pressure contrasts: those would demand >100 m/s
            // jets and force the limiter to erase every synoptic low with them.
            for (i in shape.indices) shape[i] *= 0.15
            // Use the same smooth synoptic lows as a new world, rather than weak
            // latitude waves whose amplitude vanishes after thermal equilibration.
            val pattern = SynopticPattern(seed xor (weatherPhase * 997).toInt())
            for (y in 0 until height) for (x in 0 until width) {
                val vortex = pattern.flow(config, (x + 0.5) * config.dx, (y + 0.5) * config.dy)
                shape[y * width + x] += 2 * ln(vortex.heightMeters / config.meanHeightMeters)
            }
        }
        val depth = DoubleArray(config.cellCount)
        val pressure = DoubleArray(config.cellCount)
        val east = DoubleArray(config.cellCount)
        val north = DoubleArray(config.cellCount)
        var exponent = if (weatherPhase == null) 0.7 else 1.0
        repeat(8) {
            var sum = 0.0
            for (i in depth.indices) {
                depth[i] = exp(0.5 * exponent * shape[i]) * sqrt(referenceTemperature / temperature[i])
                sum += depth[i]
            }
            val scale = carrierDepthSum / sum
            for (i in depth.indices) {
                depth[i] *= scale
                pressure[i] = config.gravity * depth[i] * depth[i] * temperature[i] / (2 * referenceTemperature)
            }
            var maximum = 0.0; var sumSpeed2 = 0.0
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                val f = config.coriolisAtRow(y)
                val inverse = f / (f * f + f0 * f0) / depth[i]
                val px = (pressure[y * width + (x + 1) % width] - pressure[y * width + (x + width - 1) % width]) / (2 * config.dx)
                val py = (pressure[config.northRow(y) * width + x] - pressure[config.southRow(y) * width + x]) / (2 * config.dy)
                east[i] = -py * inverse
                north[i] = if (!config.periodicY && (y == 0 || y == height - 1)) 0.0 else px * inverse
                if (weatherPhase != null) {
                    // Cyclonic gradient-wind balance includes centripetal acceleration.
                    // Pure geostrophy overestimates tropical vortex speeds, causing a
                    // single tail near the equator to shrink every pressure system.
                    val acceleration = hypot(px, py) / depth[i]
                    val radius = config.domainWidthMeters * 0.065
                    val balancedSpeed = 2 * acceleration /
                        (abs(f) + sqrt(f * f + 4 * acceleration / radius) + 1e-20)
                    val geostrophicSpeed = hypot(east[i], north[i])
                    val response = min(1.0, balancedSpeed / max(1e-12, geostrophicSpeed))
                    east[i] *= response; north[i] *= response
                }
                val speed2 = east[i] * east[i] + north[i] * north[i]
                maximum = max(maximum, sqrt(speed2)); sumSpeed2 += speed2
            }
            if (maximum <= 35.0) return ReconstructedCirculation(depth, east, north, maximum,
                sqrt(sumSpeed2 / config.cellCount), exponent)
            // Scale the pressure contrast AND its balanced velocities, never clip winds alone.
            exponent *= 0.95 * 35.0 / maximum
        }
        error("Unable to bound reconstructed thermal circulation")
    }
}
