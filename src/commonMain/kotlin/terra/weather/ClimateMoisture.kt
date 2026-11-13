package terra.weather

import terra.atmosphere.MoistPhysicsConfig
import terra.atmosphere.ColumnThermodynamics
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class MoistureIntervalResult(
    val coarseRainMm: DoubleArray,
    val fineRainMm: DoubleArray,
    val rainfallMm: Double,
    val potentialRainfallMm: Double,
    val supersaturationRainMm: Double,
    val reEvaporatedMm: Double,
    val atmosphericWaterBeforeMm: Double,
    val atmosphericWaterAfterMm: Double,
    val maximumCourant: Double,
    val transportSteps: Int,
    val evaporationMm: Double,
    val wetFractionMean: Double,
    val maximumLiftMetersPerSecond: Double,
    val moistureResidualMm: Double
)

/**
 * Conservative column-water transport on the reduced mesh.
 *
 * Advection uses an upwind finite-volume flux shared by both faces; eddy mixing is a
 * bounded, direction-dependent diffusive flux. Precipitation is one closure combining
 * supersaturation, diagnosed ascent (convergence, orography and convection) and
 * re-evaporation in subsiding dry air. Every removal comes from the local column.
 */
internal class ClimateMoisture(
    private val grid: ClimateGrid,
    private val geography: ClimateGeography,
    private val physics: MoistPhysicsConfig,
    private val regimes: WeatherRegimes
) {
    private val config = grid.config
    private val fineConfig = grid.fine
    private val count = grid.count
    private val fineCount = fineConfig.cellCount
    private val width = config.width
    private val height = config.height
    private val dx = config.dx
    private val dy = config.dy
    private val next = DoubleArray(count)
    private val eddy = DoubleArray(count)
    private val rainPotential = DoubleArray(count)
    private val rainSource = DoubleArray(count)
    private val saturation = DoubleArray(count)
    private val fineWeight = DoubleArray(fineCount)
    private val fineScratch = DoubleArray(count)
    private val regimeWet = DoubleArray(count)
    private val regimeStorm = DoubleArray(count)
    private val transportEast = DoubleArray(count)
    private val transportNorth = DoubleArray(count)

    fun advance(
        water: DoubleArray,
        seconds: Double,
        midSeconds: Double,
        yearSeconds: Double,
        circulation: CirculationState,
        airTemperatureK: DoubleArray,
        surfaceTemperatureC: DoubleArray,
        relativeHumidity: (Int) -> Double,
        fineElevationMeters: DoubleArray
    ): MoistureIntervalResult {
        require(water.size == count && seconds.isFinite() && seconds > 0.0)
        require(airTemperatureK.size == count && surfaceTemperatureC.size == count)
        val before = water.sum()
        var maximumCourant = 0.0
        var steps = 0

        // Coherent weather regimes are sampled once per phase on absolute time.
        var wetSum = 0.0
        var maximumLift = 0.0
        for (cell in 0 until count) {
            regimeWet[cell] = regimes.wetAnomaly(cell, midSeconds, yearSeconds)
            regimeStorm[cell] = regimes.storminess(cell, midSeconds, yearSeconds)
            wetSum += regimes.wetFraction(cell, midSeconds, yearSeconds)
            maximumLift = max(maximumLift, circulation.ascent[cell])
        }

        // Eddy diffusivity follows the local thermal gradient and storminess. It is
        // bounded and measured separately from the numerical advection CFL.
        var maximumSpeed = 0.0
        for (cell in 0 until count) {
            transportEast[cell] = circulation.east[cell] + DIVERGENT_MOISTURE_FRACTION * circulation.divergentEast[cell]
            transportNorth[cell] = circulation.north[cell] + DIVERGENT_MOISTURE_FRACTION * circulation.divergentNorth[cell]
        }
        for (cell in 0 until count) {
            val x = cell % width
            val y = cell / width
            val west = y * width + (x + width - 1) % width
            val east = y * width + (x + 1) % width
            val south = maxOf(0, y - 1) * width + x
            val north = minOf(height - 1, y + 1) * width + x
            val gradient = hypot(surfaceTemperatureC[east] - surfaceTemperatureC[west],
                surfaceTemperatureC[north] - surfaceTemperatureC[south]) / (2 * dx)
            val storm = regimeStorm[cell]
            eddy[cell] = (EDDY_VELOCITY_BASE + EDDY_VELOCITY_GRADIENT * gradient.coerceIn(0.0, 3e-5) * 1e5 *
                storm).coerceIn(0.15, 1.5) * dx
            maximumSpeed = max(maximumSpeed, hypot(transportEast[cell], transportNorth[cell]))
        }

        val advectiveCourant = maximumSpeed * seconds * (1.0 / dx + 1.0 / dy)
        var maximumEddy = 0.0
        for (value in eddy) maximumEddy = max(maximumEddy, value)
        val diffusiveCourant = maximumEddy * seconds * 2 * (1.0 / (dx * dx) + 1.0 / (dy * dy))
        steps = ceil(max(max(advectiveCourant, diffusiveCourant) / TARGET_COURANT, seconds / MAX_RAIN_SUBSTEP_SECONDS))
            .toInt().coerceIn(1, MAX_TRANSPORT_STEPS)
        val dt = seconds / steps
        for (cell in 0 until count) {
            saturation[cell] = max(1e-6, ColumnThermodynamics.saturationMm(airTemperatureK[cell],
                physics.referenceDryColumnKgM2, config.gravity, physics))
        }

        // Transport and precipitation alternate within the phase, so water converging
        // into an ascending column rains as it arrives instead of piling up for ten days.
        var rainfall = 0.0
        var potentialRainfall = 0.0
        var supersaturationRain = 0.0
        var reEvaporated = 0.0
        rainSource.fill(0.0)
        repeat(steps) {
            transportStep(water, dt)
            for (cell in 0 until count) {
                val result = precipitate(cell, water[cell], dt, circulation, airTemperatureK[cell])
                water[cell] -= result.rain
                rainSource[cell] += result.rain
                rainfall += result.rain
                potentialRainfall += result.raw
                supersaturationRain += result.supersaturation
                reEvaporated += result.reEvaporated
            }
        }
        maximumCourant = max(advectiveCourant, diffusiveCourant) / steps

        // Prolong to the physical tile/atmosphere surface, weighting rain toward the
        // colder, higher sub-columns inside a climate cell while conserving the total.
        for (cell in 0 until fineCount) {
            val elevation = max(0.0, fineElevationMeters[cell])
            fineWeight[cell] = 1 + 0.65 * (elevation / 2200.0).coerceIn(0.0, 1.0)
        }
        val fineRain = DoubleArray(fineCount)
        grid.overlap.prolongWeighted(rainSource, fineWeight, fineRain, fineScratch)


        val after = water.sum()
        val residual = (after + rainfall - before) / max(1.0, before)
        return MoistureIntervalResult(rainSource.copyOf(), fineRain, rainfall, potentialRainfall,
            supersaturationRain, reEvaporated, before, after, maximumCourant, steps,
            0.0, wetSum / count, maximumLift, residual)
    }

    private class RainStep(val rain: Double, val raw: Double, val supersaturation: Double, val reEvaporated: Double)

    /**
     * One precipitation closure over a substep. Supersaturation, convergence/orographic
     * ascent and convective ascent are combined multiplicatively so no water is counted
     * twice; falling rain partly re-evaporates in dry or subsiding air.
     */
    private fun precipitate(cell: Int, water: Double, dt: Double, circulation: CirculationState, air: Double): RainStep {
        if (water <= 1e-12) return RainStep(0.0, 0.0, 0.0, 0.0)
        val humidity = (water / saturation[cell]).coerceIn(0.0, 4.0)
        val supersaturation = max(0.0, water - saturation[cell])
        val supersaturationRate = 1 - exp(-dt / SUPERSATURATION_SECONDS)
        val supersaturationFraction = (supersaturation / water).coerceIn(0.0, 1.0) * supersaturationRate
        // Transient eddies (fronts, upper lows) lift air intermittently even where the
        // time-mean flow subsides, so no climate is a rain-free numerical desert.
        val lift = max(0.0, (circulation.ascent[cell] + TRANSIENT_ASCENT_METERS_PER_SECOND) * regimeStorm[cell])
        val scaleHeight = MOIST_SCALE_HEIGHT_METERS + 12 * (air - 273.15).coerceIn(-40.0, 35.0)
        // Lift only condenses air that is already close to saturation. A dry column
        // cannot be wrung out merely because it is ascending.
        // Sub-grid humidity variance: a dry mean column still holds a few moist patches.
        val humidityGate = (((humidity - LIFT_HUMIDITY_THRESHOLD) / (1 - LIFT_HUMIDITY_THRESHOLD))
            .coerceIn(0.0, 1.0)).let { it * it * it }
        val liftFraction = (1 - exp(-lift * dt / max(800.0, scaleHeight))) * humidityGate
        val combined = 1 - (1 - supersaturationFraction) * (1 - liftFraction.coerceIn(0.0, 1.0))
        val raw = water * combined * PRECIPITATION_EFFICIENCY
        val dryFactor = (1 - humidity).coerceIn(0.0, 1.0)
        val reEvaporationFraction = (RE_EVAPORATION_BASE + RE_EVAPORATION_DRY * dryFactor +
            RE_EVAPORATION_DESCENT * min(1.0, circulation.descent[cell] / 0.01)).coerceIn(0.0, MAX_RE_EVAPORATION)
        val reEvaporatedMm = raw * reEvaporationFraction
        val rain = max(0.0, raw - reEvaporatedMm).coerceAtMost(water)
        return RainStep(rain, raw, supersaturation * supersaturationRate, reEvaporatedMm)
    }

    /** Upwind advection plus bounded eddy diffusion, one explicit substep. */
    private fun transportStep(water: DoubleArray, dt: Double) {
        water.copyInto(next)
        // Zonal faces (periodic).
        for (y in 0 until height) {
            for (x in 0 until width) {
                val cell = y * width + x
                val east = y * width + (x + 1) % width
                val u = 0.5 * (transportEast[cell] + transportEast[east])
                val kappa = 0.5 * (eddy[cell] + eddy[east])
                var flux = if (u > 0) u * water[cell] else u * water[east]
                flux -= kappa * (water[east] - water[cell]) / dx
                var amount = flux * dt / dx
                // Positivity limiter: a face can remove at most a quarter of the donor's
                // substep inventory; with at most four faces per cell this is sufficient.
                val donor = if (amount > 0) water[cell] else water[east]
                val limit = 0.25 * donor
                if (abs(amount) > limit) amount = if (amount > 0) limit else -limit
                next[cell] -= amount
                next[east] += amount
            }
        }
        for (y in 0 until height - 1) {
            for (x in 0 until width) {
                val cell = y * width + x
                val northCell = cell + width
                val v = 0.5 * (transportNorth[cell] + transportNorth[northCell])
                val kappa = 0.5 * (eddy[cell] + eddy[northCell])
                var flux = if (v > 0) v * water[cell] else v * water[northCell]
                flux -= kappa * (water[northCell] - water[cell]) / dy
                var amount = flux * dt / dy
                val donor = if (amount > 0) water[cell] else water[northCell]
                val limit = 0.25 * donor
                if (abs(amount) > limit) amount = if (amount > 0) limit else -limit
                next[cell] -= amount
                next[northCell] += amount
            }
        }
        for (cell in 0 until count) water[cell] = max(0.0, next[cell])
    }

    companion object {
        const val EDDY_VELOCITY_BASE = 0.3
        const val EDDY_VELOCITY_GRADIENT = 0.8
        const val TARGET_COURANT = 0.7
        const val MAX_TRANSPORT_STEPS = 400
        const val SUPERSATURATION_SECONDS = 6 * 3600.0
        const val MOIST_SCALE_HEIGHT_METERS = 2600.0
        const val PRECIPITATION_EFFICIENCY = 0.52
        const val RE_EVAPORATION_BASE = 0.06
        const val RE_EVAPORATION_DRY = 0.30
        const val RE_EVAPORATION_DESCENT = 0.18
        const val MAX_RE_EVAPORATION = 0.48
        /** Share of the low-level divergent inflow that carries column water (dry return flow aloft). */
        const val DIVERGENT_MOISTURE_FRACTION = 0.8
        const val LIFT_HUMIDITY_THRESHOLD = 0.3
        const val TRANSIENT_ASCENT_METERS_PER_SECOND = 0.004
        const val MAX_RAIN_SUBSTEP_SECONDS = 86400.0
    }
}
