package terra.weather

import terra.atmosphere.*
import kotlin.math.*

/** Seasonal slab energy balance and conservative moist flow. All reservoirs belong to
 * the shared surface/atmosphere; these arrays are workspace, never another world.
 * Temperature is the only prognostic thermal variable. Winds solve a damped rotating
 * pressure balance, without prescribed pressure belts or a projection solver.
 */
internal class ReducedClimate(
    private val grid: ClimateGrid,
    private val geography: ClimateGeography,
    private val surface: TerrainMoistSurface,
    private val atmosphere: MoistAtmosphere
) {
    private val config = grid.config
    private val fine = grid.fine
    private val physics = atmosphere.physics
    private val sun = PlanetarySunlight(fine.width, fine.height, physics.solar)
    val airTemperatureK = DoubleArray(grid.count)
    val east = DoubleArray(grid.count)
    val north = DoubleArray(grid.count)
    private val pressure = DoubleArray(grid.count)
    private val next = DoubleArray(grid.count)
    private val capacity = DoubleArray(grid.count)
    private val eastCell = IntArray(grid.count) { it / config.width * config.width + (it % config.width + 1) % config.width }
    private val northCell = IntArray(grid.count) { if (it / config.width + 1 < config.height) it + config.width else -1 }
    private val eastPositive = DoubleArray(grid.count)
    private val eastNegative = DoubleArray(grid.count)
    private val northPositive = DoubleArray(grid.count)
    private val northNegative = DoubleArray(grid.count)
    private val latentResponse = DoubleArray(grid.count)
    private val rain = DoubleArray(grid.count)
    private val fineRain = DoubleArray(fine.cellCount)
    private val fineWeight = DoubleArray(fine.cellCount)
    private val weightScratch = DoubleArray(grid.count)
    private val albedo = DoubleArray(fine.cellCount)
    private val heatCapacity = DoubleArray(fine.cellCount)
    private val inverseHeatCapacity = DoubleArray(fine.cellCount) { i ->
        1 / (surface.heatCapacityJm2K(i) + physics.referenceDryColumnKgM2 * physics.airHeatCapacityJkgK)
    }
    private val dailySunlight = DoubleArray(fine.height)
    private val fineAir = DoubleArray(fine.cellCount)
    private val evaporation = DoubleArray(fine.cellCount)
    private val seaLevelK = DoubleArray(grid.count)
    private val coarseCapacity = DoubleArray(grid.count)
    private val fineHeat = DoubleArray(fine.cellCount)
    private val heatEastCell = IntArray(fine.cellCount) { it / fine.width * fine.width + (it % fine.width + 1) % fine.width }
    private val heatNorthCell = IntArray(fine.cellCount) { if (it / fine.width + 1 < fine.height) it + fine.width else -1 }
    private val heatEastExchange = DoubleArray(fine.cellCount)
    private val heatNorthExchange = DoubleArray(fine.cellCount)
    private var heatExchangeSeconds = Double.NaN
    private val elevation = surface.terrain.meanElevationMeters
    private val slopeEast = DoubleArray(fine.cellCount)
    private val slopeNorth = DoubleArray(fine.cellCount)
    var maximumSpeed = 0.0; private set
    var meanSpeed = 0.0; private set
    var maximumCourant = 0.0; private set
    var transportSteps = 0; private set
    var moistureResidual = 0.0; private set
    var shortwaveJoules = 0.0; private set
    var outgoingJoules = 0.0; private set
    var latentJoules = 0.0; private set
    var snowMeltJoules = 0.0; private set
    var potentialMm = 0.0; private set
    var actualMm = 0.0; private set

    init {
        for (y in 0 until fine.height) for (x in 0 until fine.width) {
            val i = y * fine.width + x
            slopeEast[i] = (elevation[y * fine.width + (x + 1) % fine.width] -
                elevation[y * fine.width + (x + fine.width - 1) % fine.width]) / (2 * fine.dx)
            slopeNorth[i] = (elevation[min(fine.height - 1, y + 1) * fine.width + x] -
                elevation[max(0, y - 1) * fine.width + x]) / (2 * fine.dy)
        }
        refreshAir()
    }

    fun resetIntervalTotals() {
        shortwaveJoules = 0.0; outgoingJoules = 0.0; latentJoules = 0.0
        snowMeltJoules = 0.0; potentialMm = 0.0; actualMm = 0.0
    }

    fun detailedAirTemperatureAt(cell: Int) = fineAir[cell]
    fun detailedAirTemperatureKelvin() = fineAir.copyOf()

    private fun refreshAir() {
        // The humid layer is above screen level; use the same temperature for cloud
        // capacity, radiation diagnostics and detailed reentry.
        for (i in fineAir.indices) fineAir[i] = (atmosphere.surfaceThermalState[i] - 6.0).coerceIn(180.0, 340.0)
        grid.restrict(fineAir, airTemperatureK)
    }

    fun diagnoseWinds() {
        for (i in seaLevelK.indices) seaLevelK[i] = airTemperatureK[i] +
            ColumnThermodynamics.LAPSE_K_PER_METER * geography.elevationMeters[i]
        // Horizontal thermal anomalies steer the unresolved prevailing circulation.
        // Using the entire equator-to-pole gradient here would double count it and
        // impose easterlies everywhere in a one-layer warm-low approximation.
        for (y in 0 until config.height) {
            val mean = (0 until config.width).sumOf { seaLevelK[y * config.width + it] } / config.width
            for (x in 0 until config.width) {
                val i = y * config.width + x
                pressure[i] = -16.0 * (seaLevelK[i] - mean)
            }
        }
        maximumSpeed = 0.0; meanSpeed = 0.0
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val i = y * config.width + x
            val px = (pressure[y * config.width + (x + 1) % config.width] -
                pressure[y * config.width + (x + config.width - 1) % config.width]) / (2 * config.dx)
            val py = (pressure[min(config.height - 1, y + 1) * config.width + x] -
                pressure[max(0, y - 1) * config.width + x]) / (2 * config.dy)
            val f = config.coriolisAtRow(y)
            val r = 5e-5 * (1 + geography.landFraction[i])
            val denominator = 1.2 * (r * r + f * f)
            // Weak prevailing zonal flow represents unresolved vertical overturning.
            val latitude = PlanetLatitude.atmosphereRowRadians(y, config.height)
            var u = -5.0 * cos(3 * latitude) - (r * px + f * py) / denominator
            var v = (f * px - r * py) / denominator
            // Flow loses its component into a ridge; the tangential component survives.
            val sx = geography.slopeEast[i]; val sy = geography.slopeNorth[i]
            val slope2 = sx * sx + sy * sy
            val into = max(0.0, u * sx + v * sy)
            val blocking = slope2 / (slope2 + 0.004 * 0.004)
            if (slope2 > 0.0) { u -= blocking * into * sx / slope2; v -= blocking * into * sy / slope2 }
            val speed = hypot(u, v)
            val limit = min(1.0, 18.0 / max(1e-12, speed))
            east[i] = u * limit
            north[i] = if (y == 0 || y == config.height - 1) 0.0 else v * limit
            val actualSpeed = hypot(east[i], north[i])
            maximumSpeed = max(maximumSpeed, actualSpeed); meanSpeed += actualSpeed / grid.count
        }
    }

    /** Exact integration of C dT/dt = absorbed sunlight - (A+B Tsea) - LE + heat
     * exchange. A=100 W/m², B=2 W/m²/K parameterizes net infrared/sensible loss;
     * 0.75 clear-sky transmission accounts for solar absorption above the surface.
     * Conservative neighbour heat exchange uses sea-level potential temperature,
     * keeping elevation cooling separate from horizontal heat transport.
     */
    fun exchange(seconds: Double, midSeconds: Double): DoubleArray {
        sun.update(midSeconds)
        for (y in dailySunlight.indices) dailySunlight[y] = sun.dailyMeanInputFactor(y)
        surface.fillThermalProperties(albedo, heatCapacity)
        // Include the column's sensible capacity in the effective slab.
        for (i in heatCapacity.indices) heatCapacity[i] += physics.referenceDryColumnKgM2 * physics.airHeatCapacityJkgK
        grid.restrict(heatCapacity, coarseCapacity)
        mixHeat(seconds / 2)
        for (i in evaporation.indices) {
            val old = atmosphere.surfaceThermalState[i]
            val c = grid.overlap.targetOfSource[i]
            val sat = ColumnThermodynamics.saturationMm(fineAir[i], physics.referenceDryColumnKgM2, fine.gravity, physics)
            // Evaporation responds to the water-surface vapor deficit, as in the
            // detailed solver, not relative humidity at the colder cloud level.
            val surfaceSaturation = physics.referenceDryColumnKgM2 * physics.humidColumnFraction *
                FastSaturation.mixingRatio(old, physics.referenceDryColumnKgM2 * fine.gravity)
            val humidity = (min(atmosphere.effectiveMoistureMmAt(i), sat) / max(1e-9, surfaceSaturation)).coerceIn(0.0, 1.0)
            val cloud = ColumnThermodynamics.cloudMm(atmosphere.effectiveMoistureMmAt(i), sat)
            val cover = 1 - exp(-cloud / 0.15)
            val sw = 0.75 * physics.solarPeakWm2 * dailySunlight[i / fine.width] *
                (1 - albedo[i]) * (1 - 0.25 * cover)
            val lapse = ColumnThermodynamics.LAPSE_K_PER_METER * elevation[i]
            val potential = max(0.0, old - 258.15) / 30 * (1 - humidity) *
                (3.0 + 0.45 * hypot(east[c], north[c])) * seconds / 86400.0 *
                (1 - surface.seaIceFraction[i])
            val availableEnergy = max(0.0, sw) * seconds +
                max(0.0, old - 273.15) * heatCapacity[i] * 0.05
            evaporation[i] = surface.evaporate(i, min(potential, availableEnergy / physics.latentHeatJkg))
            val latent = evaporation[i] * physics.latentHeatJkg / seconds
            val forcing = sw - latent - 100.0 - 2.0 * lapse
            val equilibriumC = forcing / 2.0
            var nextC = old - 273.15 + (equilibriumC - (old - 273.15)) *
                relaxationFraction(2.0 * seconds / heatCapacity[i])
            if (nextC > 0) {
                val melt = surface.meltSnowEnergyMm(i, nextC * heatCapacity[i])
                val energy = melt * 334000.0
                nextC -= energy / heatCapacity[i]
                snowMeltJoules += energy * fine.cellArea
            }
            // Sea ice is a finite phase buffer (1 m water equivalent), not a water source.
            if (surface.landFraction[i] < 0.5) {
                val ice = surface.seaIceFraction[i]
                val change = (-nextC * heatCapacity[i] / 334e6).coerceIn(-ice, 1 - ice)
                surface.seaIceFraction[i] += change
                nextC += change * 334e6 / heatCapacity[i]
            }
            atmosphere.surfaceThermalState[i] = (nextC + 273.15).coerceIn(180.0, 340.0)
            shortwaveJoules += sw * seconds * fine.cellArea
            outgoingJoules += (100 + 2.0 * (0.5 * (old + atmosphere.surfaceThermalState[i]) - 273.15 + lapse)) * seconds * fine.cellArea
            latentJoules += latent * seconds * fine.cellArea
            potentialMm += potential; actualMm += evaporation[i]
        }
        refreshAir()
        return evaporation
    }

    /** Condensation heats the free atmosphere, not the ocean/root-zone slab.
     * Its released energy is reported as convective heat export. Adding it here
     * creates a warm-low → rain → warmer-ocean feedback. No extra air layer is
     * needed for this surface energy balance approximation.
     */
    fun finishExchange(seconds: Double) {
        mixHeat(seconds / 2)
        refreshAir()
    }

    /** Horizontal atmospheric eddy heat transport, K=500,000 m²/s. At the native
     * 1,000 km scale heat transport takes ~23 days. Use the same
     * flux on every fine face: no coarse-cell homogenization or subgrid reset.
     * Exact pair exchange preserves sensible heat at land/ocean contacts.
     */
    internal fun mixHeat(seconds: Double) {
        for (i in fineHeat.indices) fineHeat[i] = atmosphere.surfaceThermalState[i] +
            ColumnThermodynamics.LAPSE_K_PER_METER * elevation[i]
        // Capacity depends only on fixed land/ocean fractions. Cache face
        // responses for repeated ten-day phases; vegetation changes only albedo.
        if (seconds != heatExchangeSeconds) {
            val conductivity = physics.referenceDryColumnKgM2 * physics.airHeatCapacityJkgK * 500_000.0
            for (i in fineHeat.indices) {
                fun coefficient(j: Int, distance: Double): Double {
                    val inverseCapacity = inverseHeatCapacity[i] + inverseHeatCapacity[j]
                    return relaxationFraction(conductivity / (distance * distance) * inverseCapacity * seconds / 2) / inverseCapacity
                }
                heatEastExchange[i] = coefficient(heatEastCell[i], fine.dx)
                if (heatNorthCell[i] >= 0) heatNorthExchange[i] = coefficient(heatNorthCell[i],fine.dy)
            }
            heatExchangeSeconds = seconds
        }
        repeat(2) { sweep ->
            for (index in fineHeat.indices) {
                val i = if (sweep == 0) index else fineHeat.lastIndex - index
                fun face(j: Int, coefficient: Double) {
                    val heat = (fineHeat[j] - fineHeat[i]) * coefficient
                    fineHeat[i] += heat * inverseHeatCapacity[i]; fineHeat[j] -= heat * inverseHeatCapacity[j]
                }
                face(heatEastCell[i],heatEastExchange[i])
                if (heatNorthCell[i] >= 0) face(heatNorthCell[i],heatNorthExchange[i])
            }
        }
        for (i in fineHeat.indices) atmosphere.surfaceThermalState[i] = fineHeat[i] -
            ColumnThermodynamics.LAPSE_K_PER_METER * elevation[i]
    }

    /** Upwind face fluxes use the very same wind published to the world. CFL accounts
     * for all four outgoing faces and eddy exchange; no cap silently slows transport.
     * Rain is removed during transport, allowing upwind depletion and rain shadows.
     */
    fun transport(water: DoubleArray, seconds: Double): DoubleArray {
        val before = water.sum()
        val exchangeVelocity = 0.4
        var rate = 0.0
        for (i in water.indices) {
            val x = i % config.width; val y = i / config.width
            val w = y * config.width + (x + config.width - 1) % config.width
            val e = y * config.width + (x + 1) % config.width
            val s = max(0, y - 1) * config.width + x
            val n = min(config.height - 1, y + 1) * config.width + x
            val outgoing = (max(0.0, (east[i] + east[e]) / 2) + max(0.0, -(east[i] + east[w]) / 2) + 2 * exchangeVelocity) / config.dx +
                (max(0.0, (north[i] + north[n]) / 2) + max(0.0, -(north[i] + north[s]) / 2) + 2 * exchangeVelocity) / config.dy
            rate = max(rate, outgoing)
            val u = (east[i] + east[e]) / 2
            val v = if (northCell[i] >= 0) (north[i] + north[n]) / 2 else 0.0
            eastPositive[i] = max(0.0, u); eastNegative[i] = min(0.0, u)
            northPositive[i] = max(0.0, v); northNegative[i] = min(0.0, v)
            latentResponse[i] = physics.latentHeatJkg / coarseCapacity[i]
            val ascent = geography.upliftMetersPerSecond(i, east[i], north[i])
            val residence = min(21600.0, min(config.dx, config.dy) / max(1.0, hypot(east[i], north[i])))
            val convergence = -((east[e] - east[w]) / (2 * config.dx) + (north[n] - north[s]) / (2 * config.dy))
            val convectiveLift = max(0.0, airTemperatureK[i] - 273.15) * 10.0
            val lift = min(2500.0, ascent * residence + max(0.0, convergence) * residence * 1500.0 + convectiveLift)
            capacity[i] = ColumnThermodynamics.saturationMm((airTemperatureK[i] - 0.0065 * lift).coerceAtLeast(180.0),
                physics.referenceDryColumnKgM2, config.gravity, physics)
        }
        transportSteps = max(1, ceil(seconds * max(rate / 0.75, 1 / 86400.0)).toInt())
        val dt = seconds / transportSteps
        maximumCourant = rate * dt
        val response = relaxationFraction(dt / 21600.0)
        rain.fill(0.0)
        repeat(transportSteps) {
            water.copyInto(next)
            val zonalStep = dt / config.dx; val meridionalStep = dt / config.dy
            for (i in water.indices) {
                val e = eastCell[i]
                val zonal = (eastPositive[i] * water[i] + eastNegative[i] * water[e] +
                    exchangeVelocity * (water[i] - water[e])) * zonalStep
                next[i] -= zonal; next[e] += zonal
                val n = northCell[i]
                if (n >= 0) {
                    val meridional = (northPositive[i] * water[i] + northNegative[i] * water[n] +
                        exchangeVelocity * (water[i] - water[n])) * meridionalStep
                    next[i] -= meridional; next[n] += meridional
                }
            }
            for (i in water.indices) {
                check(next[i] >= -1e-10) { "Negative transported water: ${next[i]}" }
                // Latent warming raises the capacity while rain forms, rather than
                // raining against a stale cold column for an entire seasonal phase.
                if (next[i] > capacity[i]) {
                    val feedback = capacity[i] * 0.065 * latentResponse[i]
                    val condensed = (next[i] - capacity[i]) * response / (1 + feedback)
                    capacity[i] *= exp(min(1.0, condensed * latentResponse[i] * 0.065))
                    rain[i] += condensed; water[i] = next[i] - condensed
                } else water[i] = max(0.0, next[i])
            }
        }
        // Directional fine slopes retain windward/lee differences inside a coarse cell.
        for (i in fineWeight.indices) {
            val c = grid.overlap.targetOfSource[i]
            val ascent = east[c] * slopeEast[i] + north[c] * slopeNorth[i]
            fineWeight[i] = exp((ascent * 10).coerceIn(-1.5, 1.5))
        }
        grid.overlap.prolongWeighted(rain, fineWeight, fineRain, weightScratch)
        moistureResidual = (water.sum() + rain.sum() - before) / max(1.0, before)
        return fineRain
    }
}

/** JVM exp is intrinsic; the series avoids cancellation for short exchange intervals. */
internal fun relaxationFraction(x: Double): Double = if (x < 1e-3)
    x * (1 - x * (0.5 - x * (1.0 / 6 - x / 24))) else 1 - exp(-x)
