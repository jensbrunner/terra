package terra.weather

import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.AtmosphereTileMapping
import terra.atmosphere.PlanetLatitude
import kotlin.math.*

/** Finite-volume column-water transport on a coarser equal-area climate grid.
 * Upwind advection and symmetric eddy exchange have a common positivity CFL bound.
 * Condensation uses a subgrid lifting temperature, not a geographic rainfall allocation.
 */
internal class ClimateMoistureTransport(private val fine: DryAtmosphereConfig) {
    // Climate transport need not resolve 125-km weather. Restriction/prolongation
    // is conservative on equal-area cells and keeps no independent water stores.
    private val config = fine.copy(width = minOf(fine.width, 48), height = minOf(fine.height, 36))
    private val mapping = AtmosphereTileMapping(fine.width, fine.height, config)
    private val core = ClimateMoistureGrid(config)
    private val q = DoubleArray(config.cellCount)
    private val p = DoubleArray(config.cellCount)
    private val capacity = DoubleArray(config.cellCount)
    private val fineCapacity = DoubleArray(fine.cellCount)
    private fun fineIndex(tile: Int) = (fine.height - 1 - tile / fine.width) * fine.width + tile % fine.width

    fun advance(water: DoubleArray, rain: DoubleArray, seconds: Double, yearFraction: Double,
        upliftMetersPerSecond: (Int, Double, Double) -> Double, saturation: (Int, Double) -> Double,
        temperatureC: DoubleArray, wet: (Int) -> Double) {
        val shift = 0.12 * sin(2 * PI * yearFraction)
        for (c in water.indices) {
            val latitude = PlanetLatitude.atmosphereRowRadians(c / fine.width, fine.height)
            val east = -6.0 * cos(3 * (latitude - shift))
            val north = -1.5 * sin(6 * (latitude - shift))
            val uplift = upliftMetersPerSecond(c, east, north)
            val residence = min(2 * 86400.0, min(fine.dx, fine.dy) / max(1.0, hypot(east, north)))
            val lift = (750 + 500 * wet(c) + residence * uplift).coerceIn(250.0, 3000.0)
            fineCapacity[c] = saturation(c, (temperatureC[c] + 273.15 - 6.0 - 0.006 * lift).coerceAtLeast(193.15))
        }
        q.fill(0.0); p.fill(0.0); capacity.fill(0.0)
        for (c in q.indices) mapping.forCell(c) { tile, fraction ->
            val i = fineIndex(tile)
            val weight = fraction / mapping.tilesPerCell
            q[c] += water[i] * weight
            capacity[c] += fineCapacity[i] * weight
        }
        core.advance(q, p, capacity, seconds, yearFraction)
        // Uniform mixing within a climate cell is the unresolved transport closure.
        // Rain is weighted toward the colder/lifted subcolumns inside that cell.
        water.fill(0.0)
        for (c in q.indices) {
            var weightSum = 0.0
            mapping.forCell(c) { tile, fraction ->
                weightSum += max(0.01, q[c] + p[c] - fineCapacity[fineIndex(tile)]) * fraction
            }
            mapping.forCell(c) { tile, fraction ->
                val i = fineIndex(tile)
                water[i] += q[c] * fraction
                rain[i] += p[c] * mapping.tilesPerCell *
                    max(0.01, q[c] + p[c] - fineCapacity[i]) * fraction / weightSum
            }
        }
    }
}

private class ClimateMoistureGrid(private val config: DryAtmosphereConfig) {
    private val east = DoubleArray(config.height)
    private val north = DoubleArray(config.height + 1)
    private val scratch = DoubleArray(config.cellCount)
    private val eddy = 1.5 // m/s exchange velocity: unresolved transient moisture transport.

    fun advance(water: DoubleArray, rain: DoubleArray, capacity: DoubleArray, seconds: Double, yearFraction: Double) {
        val shift = 0.12 * sin(2 * PI * yearFraction)
        for (y in east.indices) {
            val latitude = PlanetLatitude.atmosphereRowRadians(y, config.height)
            east[y] = -6.0 * cos(3 * (latitude - shift))
        }
        for (y in north.indices) {
            val latitude = PlanetLatitude.radians(y.toDouble() / config.height)
            north[y] = if (y == 0 || y == config.height) 0.0 else -1.5 * sin(6 * (latitude - shift))
        }
        val steps = ceil(seconds * (9.0 / config.dx + 6.0 / config.dy) / 0.8).toInt().coerceAtLeast(1)
        val dt = seconds / steps
        val response = -expm1(-dt / (2 * 86400.0))
        repeat(steps) {
            water.copyInto(scratch)
            for (y in 0 until config.height) for (x in 0 until config.width) {
                val c = y * config.width + x
                val e = y * config.width + (x + 1) % config.width
                val zonal = (max(0.0, east[y]) * water[c] + min(0.0, east[y]) * water[e] +
                    eddy * (water[c] - water[e])) * dt / config.dx
                scratch[c] -= zonal; scratch[e] += zonal
                if (y + 1 < config.height) {
                    val n = c + config.width
                    val meridional = (max(0.0, north[y + 1]) * water[c] + min(0.0, north[y + 1]) * water[n] +
                        eddy * (water[c] - water[n])) * dt / config.dy
                    scratch[c] -= meridional; scratch[n] += meridional
                }
            }
            for (c in water.indices) {
                check(scratch[c] >= -1e-10)
                val condensed = max(0.0, scratch[c] - capacity[c]) * response
                rain[c] += condensed
                water[c] = max(0.0, scratch[c] - condensed)
            }
        }
    }
}
