package terra.weather

import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.PlanetLatitude
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One diagnosed, mass-consistent column circulation.
 *
 * The rotational part is balanced against the evolving thermal pressure field.
 * The divergent part is a low-level velocity potential solved from thermal forcing,
 * surface heating and terrain convergence. Column transport only uses the rotational
 * component, so a converging near-surface wind never advects the whole water column
 * while leaving the compensating return flow unexplained.
 */
internal data class CirculationState(
    val east: DoubleArray,
    val north: DoubleArray,
    val lowEast: DoubleArray,
    val lowNorth: DoubleArray,
    /** Positive means low-level mass convergence (ascent), 1/s. */
    val convergence: DoubleArray,
    /** Positive low-level ascent in m/s, already including orographic and convective lift. */
    val ascent: DoubleArray,
    /** Positive descent in m/s (subsidence or lee descent). */
    val descent: DoubleArray,
    val pressureAnomalyPascals: DoubleArray,
    val maximumSpeed: Double,
    val rmsSpeed: Double,
    val maximumLowSpeed: Double
)

internal class DiagnosticCirculation(private val grid: ClimateGrid, private val geography: ClimateGeography) {
    private val config: DryAtmosphereConfig = grid.config
    private val count = grid.count
    private val width = config.width
    private val height = config.height
    private val dx = config.dx
    private val dy = config.dy

    // Reused work arrays. Warm starts keep the sparse balance solve cheap.
    private val smoothedAir = DoubleArray(count)
    private val smoothedSurface = DoubleArray(count)
    private val scratch = DoubleArray(count)
    private val pressure = DoubleArray(count)
    private val east = DoubleArray(count)
    private val north = DoubleArray(count)
    private val divergence = DoubleArray(count)
    private val potential = DoubleArray(count)
    private val previousPotential = DoubleArray(count)
    private val lowEast = DoubleArray(count)
    private val lowNorth = DoubleArray(count)
    private val convergence = DoubleArray(count)
    private val ascent = DoubleArray(count)
    private val descent = DoubleArray(count)
    private val zonalMean = DoubleArray(height)
    private val adjacent = DoubleArray(4)

    private var initialized = false
    private var previousEast = DoubleArray(count)
    private var previousNorth = DoubleArray(count)

    private fun west(cell: Int): Int = cell - cell % width + (cell % width + width - 1) % width
    private fun eastOf(cell: Int): Int = cell - cell % width + (cell % width + 1) % width
    private fun south(cell: Int): Int = maxOf(0, cell / width - 1) * width + cell % width
    private fun northOf(cell: Int): Int = minOf(height - 1, cell / width + 1) * width + cell % width

    fun advance(
        airTemperatureK: DoubleArray,
        surfaceTemperatureK: DoubleArray,
        seconds: Double,
        yearFraction: Double,
        windGustMetersPerSecond: Double = 3.0
    ): CirculationState {
        require(airTemperatureK.size == count && surfaceTemperatureK.size == count)
        require(seconds.isFinite() && seconds > 0.0)
        smooth(airTemperatureK, smoothedAir, passes = 2)
        smooth(surfaceTemperatureK, smoothedSurface, passes = 2)

        // Thermal pressure: a warm column is a relative low at the surface; a mountain
        // root adds blocking high pressure. No latitude-dependent wind or rain belt is
        // inserted here; a uniform temperature field has no pressure gradient.
        var meanAir = 0.0
        for (v in smoothedAir) meanAir += v
        meanAir /= count
        for (cell in 0 until count) {
            val thermal = -(smoothedSurface[cell] - meanAir) * THERMAL_PRESSURE_PER_KELVIN
            val blocking = geography.elevationMeters[cell] * TERRAIN_PRESSURE_PER_METER
            pressure[cell] = thermal + blocking
        }

        // Geostrophic balance with a smooth equatorial transition. The inverse Coriolis
        // factor never blows up at the equator.
        var maximum = 0.0
        for (y in 0 until height) {
            val f = config.coriolisAtRow(y)
            val inverse = f / (f * f + EQUATORIAL_CORIOLIS * EQUATORIAL_CORIOLIS)
            for (x in 0 until width) {
                val cell = y * width + x
                val pEast = pressure[y * width + (x + 1) % width]
                val pWest = pressure[y * width + (x + width - 1) % width]
                val pNorth = pressure[northOf(cell)]
                val pSouth = pressure[south(cell)]
                var u = -(pNorth - pSouth) / (2 * dy) * inverse / AIR_DENSITY
                var v = (pEast - pWest) / (2 * dx) * inverse / AIR_DENSITY
                u *= BALANCED_FLOW_SCALE
                v *= BALANCED_FLOW_SCALE
                // Mountains obstruct and deflect the balanced flow.
                val block = 1.0 / (1.0 + geography.elevationMeters[cell] / TERRAIN_BLOCKING_HEIGHT)
                u *= block; v *= block
                east[cell] = u; north[cell] = v
                val speed = sqrt(u * u + v * v)
                if (speed > maximum) maximum = speed
            }
        }
        val scale = if (maximum > MAXIMUM_COLUMN_SPEED) MAXIMUM_COLUMN_SPEED / maximum else 1.0
        if (scale < 1.0) for (cell in 0 until count) { east[cell] *= scale; north[cell] *= scale }

        // Project the balanced flow onto its non-divergent part. Column moisture transport
        // must not converge or diverge with the near-surface thermal circulation.
        computeDivergence(east, north, divergence)
        subtractMean(divergence)
        solvePotential(divergence, potential)
        for (cell in 0 until count) {
            val ex = east[cell] - gradientEast(potential, cell)
            val ny = north[cell] - gradientNorth(potential, cell)
            east[cell] = ex; north[cell] = ny
        }
        // A small residual from the truncated solve is acceptable for display, but the
        // transport scheme itself is positivity-limited and conserves water exactly.

        // Low-level flow: reduced balanced flow plus the thermally direct surface breeze,
        // which points from cool air toward a warm surface anomaly.
        val meanSurface = smoothedSurface.average()
        for (cell in 0 until count) {
            val anomaly = smoothedSurface[cell] - meanSurface
            val gx = (smoothedSurface[eastOf(cell)] - smoothedSurface[west(cell)]) / (2 * dx)
            val gy = (smoothedSurface[northOf(cell)] - smoothedSurface[south(cell)]) / (2 * dy)
            val breezeEast = gx * THERMAL_BREEZE_PER_KELVIN_METER
            val breezeNorth = gy * THERMAL_BREEZE_PER_KELVIN_METER
            var u = east[cell] * LOW_LEVEL_BALANCED_FRACTION + breezeEast
            var v = north[cell] * LOW_LEVEL_BALANCED_FRACTION + breezeNorth
            // Ridge deflection: remove part of the cross-ridge component.
            val sx = geography.slopeEast[cell]
            val sy = geography.slopeNorth[cell]
            val slope = sqrt(sx * sx + sy * sy)
            if (slope > 1e-9) {
                val along = (u * sx + v * sy) / slope
                u -= DEFLECTION_FRACTION * along * sx / slope
                v -= DEFLECTION_FRACTION * along * sy / slope
            }
            lowEast[cell] = u; lowNorth[cell] = v
        }
        var maximumLow = 0.0
        for (cell in 0 until count) {
            maximumLow = max(maximumLow, sqrt(lowEast[cell] * lowEast[cell] + lowNorth[cell] * lowNorth[cell]))
        }
        if (maximumLow > MAXIMUM_LOW_SPEED) {
            val s = MAXIMUM_LOW_SPEED / maximumLow
            for (cell in 0 until count) { lowEast[cell] *= s; lowNorth[cell] *= s }
        }

        // Diagnose convergence and retain a thermal convergence component so a warm
        // continent can anchor a monsoon even when the balanced flow is weak.
        computeDivergence(lowEast, lowNorth, scratch)
        for (cell in 0 until count) {
            val anomaly = smoothedSurface[cell] - meanSurface
            val thermal = THERMAL_CONVERGENCE_PER_KELVIN * anomaly
            convergence[cell] = (-scratch[cell] + thermal).coerceIn(-MAXIMUM_CONVERGENCE, MAXIMUM_CONVERGENCE)
        }
        subtractMean(convergence)
        // Make the low-level divergent component exactly consistent with this convergence.
        for (cell in 0 until count) divergence[cell] = -convergence[cell]
        solvePotential(divergence, previousPotential)
        for (cell in 0 until count) {
            lowEast[cell] += gradientEast(previousPotential, cell)
            lowNorth[cell] += gradientNorth(previousPotential, cell)
            // Recompute the realized convergence from the adjusted flow.
            convergence[cell] = -(
                (lowEast[eastOf(cell)] - lowEast[west(cell)]) / (2 * dx) +
                    (lowNorth[northOf(cell)] - lowNorth[south(cell)]) / (2 * dy)
                ).coerceIn(-MAXIMUM_CONVERGENCE, MAXIMUM_CONVERGENCE)
        }

        // Vertical motion. Convergence lifts the lower column; terrain lifts moist flow;
        // surface-air buoyancy adds convective ascent over warm lowlands. These are the
        // same lifts used by the moisture closure, not additional moisture sources.
        for (cell in 0 until count) {
            val orographic = geography.upliftMetersPerSecond(cell, lowEast[cell], lowNorth[cell])
            val lee = geography.descentMetersPerSecond(cell, lowEast[cell], lowNorth[cell])
            val surfaceAirExcess = max(0.0, smoothedSurface[cell] - smoothedAir[cell] - CONVECTIVE_THRESHOLD_K)
            val convective = min(MAXIMUM_CONVECTIVE_ASCENT, CONVECTIVE_ASCENT_PER_KELVIN * surfaceAirExcess *
                sqrt(max(0.0, surfaceAirExcess)))
            val largeScale = max(0.0, convergence[cell]) * LOWER_COLUMN_DEPTH_METERS
            ascent[cell] = max(0.0, orographic + largeScale + convective)
            descent[cell] = max(0.0, lee + max(0.0, -convergence[cell]) * LOWER_COLUMN_DEPTH_METERS)
        }

        // Slow the circulation response to individual climate phases; seasonal reversal
        // still occurs because the surface energy state, not this filter, carries the season.
        if (!initialized) {
            previousEast = east.copyOf(); previousNorth = north.copyOf(); initialized = true
        } else {
            for (cell in 0 until count) {
                east[cell] = CIRCULATION_MEMORY * previousEast[cell] + (1 - CIRCULATION_MEMORY) * east[cell]
                north[cell] = CIRCULATION_MEMORY * previousNorth[cell] + (1 - CIRCULATION_MEMORY) * north[cell]
            }
            previousEast = east.copyOf(); previousNorth = north.copyOf()
        }

        var sumSpeed2 = 0.0
        for (cell in 0 until count) sumSpeed2 += east[cell] * east[cell] + north[cell] * north[cell]
        return CirculationState(east, north, lowEast, lowNorth, convergence, ascent, descent,
            pressure, maximum, sqrt(sumSpeed2 / count), maximumLow)
    }

    private fun smooth(source: DoubleArray, target: DoubleArray, passes: Int) {
        source.copyInto(scratch)
        repeat(passes) {
            for (y in 0 until height) for (x in 0 until width) {
                val cell = y * width + x
                val west = y * width + (x + width - 1) % width
                val east = y * width + (x + 1) % width
                val south = maxOf(0, y - 1) * width + x
                val north = minOf(height - 1, y + 1) * width + x
                target[cell] = 0.5 * scratch[cell] + 0.125 *
                    (scratch[west] + scratch[east] + scratch[south] + scratch[north])
            }
            target.copyInto(scratch)
        }
        scratch.copyInto(target)
    }

    private fun computeDivergence(u: DoubleArray, v: DoubleArray, target: DoubleArray) {
        for (cell in 0 until count) {
            target[cell] = (u[eastOf(cell)] - u[west(cell)]) / (2 * dx) +
                (v[northOf(cell)] - v[south(cell)]) / (2 * dy)
        }
    }

    /** Solve ∇²φ = source with periodic X and free-slip (Neumann) Y walls. */
    private fun solvePotential(source: DoubleArray, target: DoubleArray) {
        target.copyInto(previousPotential)
        val ratio = (dx / dy) * (dx / dy)
        val denominator = 2 + 2 * ratio
        repeat(POISSON_ITERATIONS) {
            for (y in 0 until height) for (x in 0 until width) {
                val cell = y * width + x
                val west = y * width + (x + width - 1) % width
                val east = y * width + (x + 1) % width
                val south = maxOf(0, y - 1) * width + x
                val north = minOf(height - 1, y + 1) * width + x
                val updated = (previousPotential[west] + previousPotential[east] +
                    ratio * (previousPotential[south] + previousPotential[north]) -
                    source[cell] * dx * dx) / denominator
                target[cell] = updated
            }
            target.copyInto(previousPotential)
        }
        subtractMean(target)
    }

    private fun gradientEast(field: DoubleArray, cell: Int): Double =
        (field[eastOf(cell)] - field[west(cell)]) / (2 * dx)

    private fun gradientNorth(field: DoubleArray, cell: Int): Double =
        (field[northOf(cell)] - field[south(cell)]) / (2 * dy)

    private fun subtractMean(field: DoubleArray) {
        var mean = 0.0
        for (v in field) mean += v
        mean /= field.size
        if (abs(mean) > 1e-15) for (i in field.indices) field[i] -= mean
    }

    /** Zonal mean for display and for optional diagnostics; not used as physical forcing. */
    fun zonalMeanAt(field: DoubleArray, row: Int): Double {
        var sum = 0.0
        for (x in 0 until width) sum += field[row * width + x]
        return sum / width
    }

    companion object {
        const val THERMAL_PRESSURE_PER_KELVIN = 120.0
        const val TERRAIN_PRESSURE_PER_METER = 0.35
        const val AIR_DENSITY = 1.2
        const val BALANCED_FLOW_SCALE = 0.19
        const val TERRAIN_BLOCKING_HEIGHT = 1400.0
        const val MAXIMUM_COLUMN_SPEED = 28.0
        const val MAXIMUM_LOW_SPEED = 22.0
        const val EQUATORIAL_CORIOLIS = 2.2e-5
        const val LOW_LEVEL_BALANCED_FRACTION = 0.82
        const val THERMAL_BREEZE_PER_KELVIN_METER = 0.055
        const val DEFLECTION_FRACTION = 0.45
        const val THERMAL_CONVERGENCE_PER_KELVIN = 2.2e-6
        const val MAXIMUM_CONVERGENCE = 4.0e-5
        const val LOWER_COLUMN_DEPTH_METERS = 2200.0
        const val CONVECTIVE_THRESHOLD_K = 0.4
        const val CONVECTIVE_ASCENT_PER_KELVIN = 0.008
        const val MAXIMUM_CONVECTIVE_ASCENT = 1.2
        const val CIRCULATION_MEMORY = 0.35
        const val POISSON_ITERATIONS = 6
    }
}

/** Daily-mean top-of-atmosphere input on the climate mesh, with no diurnal marching. */
internal object ClimateInsolation {
    fun dailyMean(fractionOfYear: Double, latitudeRadians: Double, tiltDegrees: Double): Double {
        val sinDeclination = sin(tiltDegrees * PI / 180) * sin(2 * PI * fractionOfYear)
        val cosDeclination = sqrt(max(0.0, 1 - sinDeclination * sinDeclination))
        val a = sin(latitudeRadians) * sinDeclination
        val b = cos(latitudeRadians) * cosDeclination
        if (b <= 1e-15) return max(0.0, a)
        val sunset = kotlin.math.acos((-a / b).coerceIn(-1.0, 1.0))
        return max(0.0, (sunset * a + b * sin(sunset)) / PI)
    }

    fun latitude(config: DryAtmosphereConfig, row: Int): Double =
        PlanetLatitude.atmosphereRowRadians(row, config.height)
}
