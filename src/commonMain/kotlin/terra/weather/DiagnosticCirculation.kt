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
 * [east]/[north] are the non-divergent, moisture-weighted lower-tropospheric wind
 * used to advect column water and air heat. [lowEast]/[lowNorth] are the near-surface
 * wind including the frictional, divergent inflow into lows. [divergentEast] and
 * [divergentNorth] are that low-level divergent part alone: moisture lives mostly in
 * the boundary layer while the compensating return flow aloft is dry, so this part
 * carries net water into convergence zones.
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
    val maximumLowSpeed: Double,
    val divergentEast: DoubleArray = DoubleArray(east.size),
    val divergentNorth: DoubleArray = DoubleArray(east.size)
)

/**
 * Diagnosed column circulation from the evolving thermal field, rotation, friction and
 * terrain.
 *
 * The zonal-mean surface pressure is not hydrostatic "warm = low" (that would give
 * easterlies at every latitude). It is set by the angular-momentum-constrained
 * overturning: an equatorial trough under the thermal equator, subtropical highs at the
 * Hadley-cell edges (Held–Hou scaling from rotation and the diagnosed meridional
 * contrast), and subpolar lows under the storm track at the latitude of strongest
 * meridional temperature gradient. Every amplitude scales with the diagnosed thermal
 * contrast, so a uniform planet is calm. Zonal temperature anomalies (land/sea)
 * add a local thermal low or high, which carries monsoons and coastal breezes.
 *
 * Winds solve the linear frictional balance f k×u = −∇p/ρ − r u, which is geostrophic
 * at high latitude, down-gradient at the equator, and turns low-level flow into lows.
 */
internal class DiagnosticCirculation(private val grid: ClimateGrid, private val geography: ClimateGeography) {
    private val config: DryAtmosphereConfig = grid.config
    private val count = grid.count
    private val width = config.width
    private val height = config.height
    private val dx = config.dx
    private val dy = config.dy
    private val poisson = PeriodicChannelPoisson(width, height, dx, dy)
    private val latitude = DoubleArray(height) { PlanetLatitude.atmosphereRowRadians(it, height) }

    private val smoothedAir = DoubleArray(count)
    private val smoothedSurface = DoubleArray(count)
    private val scratch = DoubleArray(count)
    private val pressure = DoubleArray(count)
    private val east = DoubleArray(count)
    private val north = DoubleArray(count)
    private val divergence = DoubleArray(count)
    private val potential = DoubleArray(count)
    private val lowEast = DoubleArray(count)
    private val lowNorth = DoubleArray(count)
    private val divergentEast = DoubleArray(count)
    private val divergentNorth = DoubleArray(count)
    private val convergence = DoubleArray(count)
    private val ascent = DoubleArray(count)
    private val descent = DoubleArray(count)
    private val zonalMean = DoubleArray(height)
    private val zonalPressure = DoubleArray(height)

    private var initialized = false
    private var previousEast = DoubleArray(count)
    private var previousNorth = DoubleArray(count)

    /** Last diagnosed zonal structure, for review output. Degrees. */
    var thermalEquatorDegrees = 0.0; private set
    var hadleyEdgeNorthDegrees = 0.0; private set
    var hadleyEdgeSouthDegrees = 0.0; private set
    var stormTrackNorthDegrees = 0.0; private set
    var stormTrackSouthDegrees = 0.0; private set

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

        diagnoseZonalPressure()
        // Total surface pressure: zonal dynamical structure + zonal thermal anomaly +
        // mountain blocking. A uniform temperature field has no pressure gradient.
        for (cell in 0 until count) {
            val y = cell / width
            val thermal = -(smoothedSurface[cell] - zonalMean[y]) * THERMAL_PRESSURE_PER_KELVIN
            val blocking = geography.elevationMeters[cell] * TERRAIN_PRESSURE_PER_METER
            pressure[cell] = zonalPressure[y] + thermal + blocking
        }

        // Moisture-carrying lower-tropospheric flow: weak friction, nearly balanced.
        balancedWind(COLUMN_FRICTION_PER_SECOND, east, north)
        var maximum = 0.0
        for (cell in 0 until count) {
            val block = 1.0 / (1.0 + geography.elevationMeters[cell] / TERRAIN_BLOCKING_HEIGHT)
            east[cell] *= block; north[cell] *= block
            maximum = max(maximum, sqrt(east[cell] * east[cell] + north[cell] * north[cell]))
        }
        limitSpeed(east, north, MAXIMUM_COLUMN_SPEED)
        // Project onto the non-divergent part. Column water must not converge with
        // the near-surface frictional inflow; that part is carried separately below.
        computeDivergence(east, north, divergence)
        poisson.solve(divergence, potential)
        for (cell in 0 until count) {
            east[cell] -= gradientEast(potential, cell)
            north[cell] -= gradientNorth(potential, cell)
        }

        // Near-surface flow: strong boundary-layer friction turns it across isobars.
        balancedWind(SURFACE_FRICTION_PER_SECOND, lowEast, lowNorth)
        for (cell in 0 until count) {
            var u = lowEast[cell]
            var v = lowNorth[cell]
            // A thermally direct breeze toward warmer surface anomalies.
            val gx = (smoothedSurface[eastOf(cell)] - smoothedSurface[west(cell)]) / (2 * dx)
            val gy = (smoothedSurface[northOf(cell)] - smoothedSurface[south(cell)]) / (2 * dy)
            u += gx * THERMAL_BREEZE_PER_KELVIN_METER
            v += gy * THERMAL_BREEZE_PER_KELVIN_METER
            val block = 1.0 / (1.0 + geography.elevationMeters[cell] / TERRAIN_BLOCKING_HEIGHT)
            u *= block; v *= block
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
            limitSpeed(lowEast, lowNorth, MAXIMUM_LOW_SPEED)
            maximumLow = MAXIMUM_LOW_SPEED
        }

        // Convergence: frictional inflow plus a thermal convergence anchored to zonal
        // anomalies (a warm continent draws in boundary-layer air in summer).
        computeDivergence(lowEast, lowNorth, scratch)
        for (cell in 0 until count) {
            val anomaly = smoothedSurface[cell] - zonalMean[cell / width]
            val thermal = (THERMAL_CONVERGENCE_PER_KELVIN * anomaly).coerceIn(-MAXIMUM_THERMAL_CONVERGENCE, MAXIMUM_THERMAL_CONVERGENCE)
            convergence[cell] = (-scratch[cell] + thermal)
                .coerceIn(-MAXIMUM_CONVERGENCE, MAXIMUM_CONVERGENCE)
        }
        subtractMean(convergence)
        // The low-level divergent wind is exactly the potential flow of this convergence.
        for (cell in 0 until count) divergence[cell] = -convergence[cell]
        poisson.solve(divergence, potential)
        for (cell in 0 until count) {
            val ue = gradientEast(potential, cell)
            val vn = gradientNorth(potential, cell)
            divergentEast[cell] = ue; divergentNorth[cell] = vn
        }
        // Replace the low-level divergent part by this consistent potential flow.
        computeDivergence(lowEast, lowNorth, scratch)
        subtractMean(scratch)
        poisson.solve(scratch, pressureScratch)
        for (cell in 0 until count) {
            lowEast[cell] += divergentEast[cell] - gradientEast(pressureScratch, cell)
            lowNorth[cell] += divergentNorth[cell] - gradientNorth(pressureScratch, cell)
        }

        // Vertical motion. Convergence lifts the lower column; terrain lifts moist flow;
        // surface-air buoyancy adds convective ascent over warm lowlands. These are the
        // same lifts used by the moisture closure, not additional moisture sources.
        for (cell in 0 until count) {
            val orographic = geography.upliftMetersPerSecond(cell, lowEast[cell], lowNorth[cell])
            val lee = geography.descentMetersPerSecond(cell, lowEast[cell], lowNorth[cell])
            // Instability relative to near-surface air: the active air level sits a nominal
            // boundary-layer offset below the surface even in a neutral column.
            val surfaceAirExcess = max(0.0, smoothedSurface[cell] - smoothedAir[cell] -
                ClimateEnergyModel.BOUNDARY_LAYER_OFFSET_K - CONVECTIVE_THRESHOLD_K)
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
        maximum = 0.0
        for (cell in 0 until count) {
            val s2 = east[cell] * east[cell] + north[cell] * north[cell]
            sumSpeed2 += s2; maximum = max(maximum, sqrt(s2))
        }
        return CirculationState(east.copyOf(), north.copyOf(), lowEast.copyOf(), lowNorth.copyOf(),
            convergence.copyOf(), ascent.copyOf(), descent.copyOf(), pressure.copyOf(), maximum,
            sqrt(sumSpeed2 / count), maximumLow, divergentEast.copyOf(), divergentNorth.copyOf())
    }

    private val pressureScratch = DoubleArray(count)

    /**
     * Zonal-mean dynamical surface pressure from the zonal-mean surface temperature.
     * Latitude enters only through rotation (Coriolis, Held–Hou width) and the
     * evolving thermal structure; there is no prescribed belt position.
     */
    private fun diagnoseZonalPressure() {
        for (y in 0 until height) {
            var sum = 0.0
            for (x in 0 until width) sum += smoothedSurface[y * width + x]
            zonalMean[y] = sum / width
        }
        val warmest = zonalMean.maxOrNull() ?: 0.0
        // Thermal equator: soft maximum of the zonal-mean temperature.
        var weightSum = 0.0
        var latitudeSum = 0.0
        for (y in 0 until height) {
            val w = exp((zonalMean[y] - warmest) / THERMAL_EQUATOR_SOFTNESS_K) * cos(latitude[y])
            weightSum += w; latitudeSum += w * latitude[y]
        }
        val thermalEquator = (latitudeSum / max(1e-12, weightSum)).coerceIn(-MAX_THERMAL_EQUATOR, MAX_THERMAL_EQUATOR)

        // Hemispheric thermal contrast: thermal equator minus the high-latitude mean.
        fun contrast(north: Boolean): Double {
            var sum = 0.0; var n = 0
            for (y in 0 until height) {
                val polar = if (north) latitude[y] > POLAR_LATITUDE else latitude[y] < -POLAR_LATITUDE
                if (polar) { sum += zonalMean[y]; n++ }
            }
            if (n == 0) return 0.0
            return max(0.0, interpolateZonal(thermalEquator) - sum / n)
        }
        val contrastNorth = contrast(true)
        val contrastSouth = contrast(false)

        // Held–Hou angular-momentum-conserving Hadley width for the nominal planet.
        fun hadleyWidth(deltaT: Double): Double {
            val omega = config.rotationRateRadiansPerSecond
            val ratio = 5 * config.gravity * TROPOPAUSE_HEIGHT_METERS * deltaT /
                (3 * omega * omega * PLANET_RADIUS_METERS * PLANET_RADIUS_METERS * REFERENCE_POTENTIAL_TEMPERATURE_K)
            return (HADLEY_WIDTH_SCALE * sqrt(max(0.0, ratio))).coerceIn(MIN_HADLEY_WIDTH, MAX_HADLEY_WIDTH)
        }
        val edgeNorth = (thermalEquator + hadleyWidth(contrastNorth)).coerceAtMost(MAX_EDGE)
        val edgeSouth = (thermalEquator - hadleyWidth(contrastSouth)).coerceAtLeast(-MAX_EDGE)

        // Storm track: gradient-weighted latitude of baroclinicity poleward of each edge.
        fun stormTrack(edge: Double, north: Boolean): Double {
            var w = 0.0; var s = 0.0
            for (y in 1 until height - 1) {
                val phi = latitude[y]
                val inRange = if (north) phi > edge + STORM_TRACK_MIN_OFFSET else phi < edge - STORM_TRACK_MIN_OFFSET
                if (!inRange) continue
                val gradient = abs(zonalMean[y + 1] - zonalMean[y - 1]) / (latitude[y + 1] - latitude[y - 1])
                val weight = gradient * gradient
                w += weight; s += weight * phi
            }
            val fallback = edge + (if (north) 1 else -1) * STORM_TRACK_DEFAULT_OFFSET
            val track = if (w > 1e-12) s / w else fallback
            return if (north) track.coerceIn(edge + STORM_TRACK_MIN_OFFSET, MAX_STORM_TRACK)
                else track.coerceIn(-MAX_STORM_TRACK, edge - STORM_TRACK_MIN_OFFSET)
        }
        val trackNorth = stormTrack(edgeNorth, true)
        val trackSouth = stormTrack(edgeSouth, false)

        val strengthNorth = (contrastNorth / REFERENCE_CONTRAST_K).coerceIn(0.0, MAXIMUM_STRENGTH)
        val strengthSouth = (contrastSouth / REFERENCE_CONTRAST_K).coerceIn(0.0, MAXIMUM_STRENGTH)
        val strengthTrough = 0.5 * (strengthNorth + strengthSouth)
        fun bump(phi: Double, center: Double, width: Double): Double {
            val d = (phi - center) / width
            return exp(-0.5 * d * d)
        }
        for (y in 0 until height) {
            val phi = latitude[y]
            zonalPressure[y] =
                -EQUATORIAL_TROUGH_PASCALS * strengthTrough * bump(phi, thermalEquator, TROUGH_WIDTH) +
                SUBTROPICAL_HIGH_PASCALS * strengthNorth * bump(phi, edgeNorth, HIGH_WIDTH) +
                SUBTROPICAL_HIGH_PASCALS * strengthSouth * bump(phi, edgeSouth, HIGH_WIDTH) -
                SUBPOLAR_LOW_PASCALS * strengthNorth * bump(phi, trackNorth, LOW_WIDTH) -
                SUBPOLAR_LOW_PASCALS * strengthSouth * bump(phi, trackSouth, LOW_WIDTH)
        }
        var mean = 0.0
        for (y in 0 until height) mean += zonalPressure[y]
        mean /= height
        for (y in 0 until height) zonalPressure[y] -= mean

        thermalEquatorDegrees = thermalEquator * 180 / PI
        hadleyEdgeNorthDegrees = edgeNorth * 180 / PI
        hadleyEdgeSouthDegrees = edgeSouth * 180 / PI
        stormTrackNorthDegrees = trackNorth * 180 / PI
        stormTrackSouthDegrees = trackSouth * 180 / PI
    }

    private fun interpolateZonal(phi: Double): Double {
        var best = 0
        for (y in 0 until height) if (abs(latitude[y] - phi) < abs(latitude[best] - phi)) best = y
        return zonalMean[best]
    }

    /** Linear frictional balance: f k×u = −∇p/ρ − r u, solved exactly per cell. */
    private fun balancedWind(friction: Double, u: DoubleArray, v: DoubleArray) {
        for (y in 0 until height) {
            val f = config.coriolisAtRow(y)
            val denominator = AIR_DENSITY * (f * f + friction * friction)
            for (x in 0 until width) {
                val cell = y * width + x
                val gx = (pressure[y * width + (x + 1) % width] - pressure[y * width + (x + width - 1) % width]) / (2 * dx)
                val gy = (pressure[northOf(cell)] - pressure[south(cell)]) / (2 * dy)
                u[cell] = (-friction * gx - f * gy) / denominator
                v[cell] = (-friction * gy + f * gx) / denominator
            }
        }
    }

    /** Cell-local speed limit: one extreme cell must not weaken the whole circulation. */
    private fun limitSpeed(u: DoubleArray, v: DoubleArray, limit: Double) {
        for (cell in 0 until count) {
            val speed = sqrt(u[cell] * u[cell] + v[cell] * v[cell])
            if (speed > limit) { val s = limit / speed; u[cell] *= s; v[cell] *= s }
        }
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
        const val THERMAL_PRESSURE_PER_KELVIN = 40.0
        const val TERRAIN_PRESSURE_PER_METER = 0.0
        const val AIR_DENSITY = 1.2
        const val TERRAIN_BLOCKING_HEIGHT = 2500.0
        const val MAXIMUM_COLUMN_SPEED = 28.0
        const val MAXIMUM_LOW_SPEED = 18.0
        /** Boundary-layer friction (~1 day) and weak free-tropospheric drag. */
        const val SURFACE_FRICTION_PER_SECOND = 3.0e-5
        const val COLUMN_FRICTION_PER_SECOND = 1.2e-5
        const val THERMAL_BREEZE_PER_KELVIN_METER = 0.055
        const val DEFLECTION_FRACTION = 0.45
        const val THERMAL_CONVERGENCE_PER_KELVIN = 3.0e-7
        const val MAXIMUM_CONVERGENCE = 2.0e-5
        const val MAXIMUM_THERMAL_CONVERGENCE = 4.0e-6
        const val LOWER_COLUMN_DEPTH_METERS = 1500.0
        const val CONVECTIVE_THRESHOLD_K = 2.0
        const val CONVECTIVE_ASCENT_PER_KELVIN = 0.002
        const val MAXIMUM_CONVECTIVE_ASCENT = 0.3
        const val CIRCULATION_MEMORY = 0.35

        // Zonal overturning closure.
        const val PLANET_RADIUS_METERS = 6.371e6
        const val TROPOPAUSE_HEIGHT_METERS = 15_000.0
        const val REFERENCE_POTENTIAL_TEMPERATURE_K = 290.0
        /** Observed Hadley cells are wider than the inviscid Held–Hou estimate. */
        const val HADLEY_WIDTH_SCALE = 1.3
        const val MIN_HADLEY_WIDTH = 12.0 * PI / 180
        const val MAX_HADLEY_WIDTH = 42.0 * PI / 180
        const val MAX_EDGE = 50.0 * PI / 180
        const val MAX_THERMAL_EQUATOR = 15.0 * PI / 180
        const val THERMAL_EQUATOR_SOFTNESS_K = 3.0
        const val POLAR_LATITUDE = 60.0 * PI / 180
        const val STORM_TRACK_MIN_OFFSET = 15.0 * PI / 180
        const val STORM_TRACK_DEFAULT_OFFSET = 28.0 * PI / 180
        const val MAX_STORM_TRACK = 72.0 * PI / 180
        const val REFERENCE_CONTRAST_K = 30.0
        const val MAXIMUM_STRENGTH = 1.3
        const val EQUATORIAL_TROUGH_PASCALS = 300.0
        const val SUBTROPICAL_HIGH_PASCALS = 600.0
        const val SUBPOLAR_LOW_PASCALS = 700.0
        const val TROUGH_WIDTH = 7.0 * PI / 180
        const val HIGH_WIDTH = 10.0 * PI / 180
        const val LOW_WIDTH = 12.0 * PI / 180
    }
}

/**
 * Exact solver for ∇²φ = s on the climate mesh with periodic longitude and zero-flux
 * polar walls, using the 5-point Laplacian: a discrete Fourier transform in x and a
 * tridiagonal solve in y per wavenumber. The source's mean is removed (solvability).
 */
internal class PeriodicChannelPoisson(private val width: Int, private val height: Int,
    private val dx: Double, private val dy: Double) {
    private val cosTable = Array(width) { k -> DoubleArray(width) { x -> cos(2 * PI * k * x / width) } }
    private val sinTable = Array(width) { k -> DoubleArray(width) { x -> sin(2 * PI * k * x / width) } }
    private val re = Array(width) { DoubleArray(height) }
    private val im = Array(width) { DoubleArray(height) }
    private val lower = DoubleArray(height)
    private val diagonal = DoubleArray(height)
    private val upper = DoubleArray(height)
    private val work = DoubleArray(height)

    fun solve(source: DoubleArray, target: DoubleArray) {
        require(source.size == width * height && target.size == width * height)
        var mean = 0.0
        for (v in source) mean += v
        mean /= source.size
        // Forward real DFT along x.
        for (k in 0 until width) for (y in 0 until height) {
            var a = 0.0; var b = 0.0
            val row = y * width
            val c = cosTable[k]; val s = sinTable[k]
            for (x in 0 until width) { val value = source[row + x] - mean; a += value * c[x]; b -= value * s[x] }
            re[k][y] = a; im[k][y] = b
        }
        val inverseDy2 = 1.0 / (dy * dy)
        for (k in 0 until width) {
            val lambda = (2 * cos(2 * PI * k / width) - 2) / (dx * dx)
            for (y in 0 until height) {
                lower[y] = if (y > 0) inverseDy2 else 0.0
                upper[y] = if (y < height - 1) inverseDy2 else 0.0
                // Neumann walls: the missing neighbour mirrors the boundary cell.
                diagonal[y] = lambda - lower[y] - upper[y]
            }
            if (k == 0) {
                // Singular mode: pin the first row to zero.
                diagonal[0] = 1.0; upper[0] = 0.0
                re[0][0] = 0.0; im[0][0] = 0.0
            }
            thomas(re[k]); thomas(im[k])
        }
        // Inverse DFT.
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0.0
            for (k in 0 until width) value += re[k][y] * cosTable[k][x] - im[k][y] * sinTable[k][x]
            target[y * width + x] = value / width
        }
        var targetMean = 0.0
        for (v in target) targetMean += v
        targetMean /= target.size
        for (i in target.indices) target[i] -= targetMean
    }

    private fun thomas(rhs: DoubleArray) {
        val n = height
        work[0] = upper[0] / diagonal[0]
        rhs[0] = rhs[0] / diagonal[0]
        for (i in 1 until n) {
            val m = diagonal[i] - lower[i] * work[i - 1]
            work[i] = if (i < n - 1) upper[i] / m else 0.0
            rhs[i] = (rhs[i] - lower[i] * rhs[i - 1]) / m
        }
        for (i in n - 2 downTo 0) rhs[i] -= work[i] * rhs[i + 1]
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
