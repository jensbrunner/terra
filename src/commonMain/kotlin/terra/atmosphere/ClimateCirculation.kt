package terra.atmosphere

import kotlin.math.*

internal data class ReconstructedCirculation(
    val depth: DoubleArray, val east: DoubleArray, val north: DoubleArray,
    val maximumSpeed: Double, val rmsSpeed: Double, val pressureExponent: Double
)

/** Stateless reentry from the evolved thermal geography. Thermally conditioned wave disturbances and compatible balanced velocities.
 * Uses this model's force -(1/h) grad(g h² T / 2Tref), not the dry g grad(h) law.
 */
internal object ClimateCirculation {
    fun reconstruct(config: DryAtmosphereConfig, referenceTemperature: Double,
        temperature: DoubleArray, carrierDepthSum: Double, weatherPhase: Double? = null): ReconstructedCirculation {
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
        if (weatherPhase != null) for (y in 1 until height - 1) {
            // Baroclinic activity follows the thermal gradient. Perturb pressure shape
            // first, then derive balanced winds with the actual thermal pressure law.
            val gradient = abs(mean[minOf(height - 1, y + 1)] - mean[maxOf(0, y - 1)])
            val activity = (gradient / 8).coerceIn(0.0, 1.0) * rotationalWeight[y]
            val envelope = sin(PI * (y + 0.5) / height).pow(2)
            for (x in 0 until width) {
                val longitude = 2 * PI * (x + 0.5) / width
                shape[y * width + x] += 0.035 * activity * envelope *
                    (sin(5 * longitude + weatherPhase + 0.6 * y) +
                        0.5 * cos(3 * longitude - weatherPhase + 0.4 * y))
            }
        }
        val depth = DoubleArray(config.cellCount)
        val pressure = DoubleArray(config.cellCount)
        val east = DoubleArray(config.cellCount)
        val north = DoubleArray(config.cellCount)
        var exponent = 0.7
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
