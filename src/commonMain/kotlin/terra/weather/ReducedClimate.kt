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
    private val rain = DoubleArray(grid.count)
    private val fineRain = DoubleArray(fine.cellCount)
    private val fineWeight = DoubleArray(fine.cellCount)
    private val weightScratch = DoubleArray(grid.count)
    private val albedo = DoubleArray(fine.cellCount)
    private val heatCapacity = DoubleArray(fine.cellCount)
    private val fineAir = DoubleArray(fine.cellCount)
    private val evaporation = DoubleArray(fine.cellCount)
    private val seaLevelK = DoubleArray(grid.count)
    private val coarseCapacity = DoubleArray(grid.count)
    private val fineHeat = DoubleArray(fine.cellCount)
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
     * exchange. A=203 W/m², B=3 W/m²/K is an effective outgoing-radiation law.
     * Conservative neighbour heat exchange uses sea-level potential temperature,
     * keeping elevation cooling separate from horizontal heat transport.
     */
    fun exchange(seconds: Double, midSeconds: Double): DoubleArray {
        sun.update(midSeconds)
        surface.fillThermalProperties(albedo, heatCapacity)
        // Include the column's sensible capacity in the effective slab.
        for (i in heatCapacity.indices) heatCapacity[i] += physics.referenceDryColumnKgM2 * physics.airHeatCapacityJkgK
        mixHeat(seconds / 2)
        for (i in evaporation.indices) {
            val old = atmosphere.surfaceThermalState[i]
            val c = grid.overlap.targetOfSource[i]
            val sat = ColumnThermodynamics.saturationMm(fineAir[i], physics.referenceDryColumnKgM2, fine.gravity, physics)
            val humidity = (atmosphere.effectiveMoistureMmAt(i) / max(1e-9, sat)).coerceIn(0.0, 1.0)
            val cloud = ColumnThermodynamics.cloudMm(atmosphere.effectiveMoistureMmAt(i), sat)
            val cover = 1 - exp(-cloud / 0.15)
            val sw = physics.solarPeakWm2 * sun.dailyMeanInputFactor(i / fine.width) *
                (1 - albedo[i]) * (1 - 0.25 * cover)
            val lapse = ColumnThermodynamics.LAPSE_K_PER_METER * elevation[i]
            val potential = max(0.0, old - 258.15) / 30 * (1 - humidity) *
                (3.0 + 0.45 * hypot(east[c], north[c])) * seconds / 86400.0 *
                (1 - surface.seaIceFraction[i])
            val availableEnergy = max(0.0, sw) * seconds +
                max(0.0, old - 273.15) * heatCapacity[i] * 0.05
            evaporation[i] = surface.evaporate(i, min(potential, availableEnergy / physics.latentHeatJkg))
            val latent = evaporation[i] * physics.latentHeatJkg / seconds
            val forcing = sw - latent - 203.0 - 3.0 * lapse
            val equilibriumC = forcing / 3.0
            var nextC = old - 273.15 + (equilibriumC - (old - 273.15)) *
                relaxationFraction(3.0 * seconds / heatCapacity[i])
            if (nextC > 0 && surface.snowMmInCell(i) > 0) {
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
            outgoingJoules += (203 + 3.0 * (0.5 * (old + atmosphere.surfaceThermalState[i]) - 273.15 + lapse)) * seconds * fine.cellArea
            latentJoules += latent * seconds * fine.cellArea
            potentialMm += potential; actualMm += evaporation[i]
        }
        refreshAir()
        return evaporation
    }

    fun condense(rainMm: DoubleArray, seconds: Double) {
        grid.restrict(rainMm, rain)
        grid.prolongUniform(rain, fineHeat)
        for (i in rainMm.indices) {
            val heat = fineHeat[i] * physics.latentHeatJkg
            atmosphere.surfaceThermalState[i] = (atmosphere.surfaceThermalState[i] + heat / heatCapacity[i]).coerceAtMost(340.0)
        }
        mixHeat(seconds / 2)
        refreshAir()
    }

    private fun mixHeat(seconds: Double) {
        grid.restrict(heatCapacity, coarseCapacity)
        for (i in fineHeat.indices) fineHeat[i] = (atmosphere.surfaceThermalState[i] +
            ColumnThermodynamics.LAPSE_K_PER_METER * elevation[i]) * heatCapacity[i]
        grid.restrict(fineHeat, seaLevelK)
        for (i in seaLevelK.indices) seaLevelK[i] /= coarseCapacity[i]
        // Exact two-reservoir exchanges are conservative and stable at seasonal steps.
        // Alternate traversal to suppress splitting bias; no global heat pool.
        repeat(4) { sweep ->
            for (index in seaLevelK.indices) {
                val i = if (sweep % 2 == 0) index else seaLevelK.lastIndex - index
                val x = i % config.width; val y = i / config.width
                fun face(j: Int, distance: Double) {
                    val inverseCapacity = 1 / coarseCapacity[i] + 1 / coarseCapacity[j]
                    val response = relaxationFraction(1.5e13 / (distance * distance) * inverseCapacity * seconds / 4)
                    val heat = (seaLevelK[j] - seaLevelK[i]) * response / inverseCapacity
                    seaLevelK[i] += heat / coarseCapacity[i]; seaLevelK[j] -= heat / coarseCapacity[j]
                }
                face(y * config.width + (x + 1) % config.width, config.dx)
                if (y + 1 < config.height) face(i + config.width, config.dy)
            }
        }
        grid.prolongUniform(seaLevelK, fineHeat)
        // Mix unresolved subcolumns as well as neighbouring climate cells. Without
        // this, warm valleys and cold peaks in one cell never exchange any heat.
        val subgridResponse = relaxationFraction(seconds / (2 * 86400.0))
        for (i in fineHeat.indices) {
            val old = atmosphere.surfaceThermalState[i]
            atmosphere.surfaceThermalState[i] = old + subgridResponse *
                (fineHeat[i] - ColumnThermodynamics.LAPSE_K_PER_METER * elevation[i] - old)
        }
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
            val ascent = geography.upliftMetersPerSecond(i, east[i], north[i])
            val residence = min(86400.0, min(config.dx, config.dy) / max(1.0, hypot(east[i], north[i])))
            val convergence = -((east[e] - east[w]) / (2 * config.dx) + (north[n] - north[s]) / (2 * config.dy))
            val convectiveLift = max(0.0, airTemperatureK[i] - 273.15) * 25.0
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
            for (y in 0 until config.height) for (x in 0 until config.width) {
                val i = y * config.width + x
                fun face(j: Int, velocity: Double, distance: Double) {
                    val amount = (max(0.0, velocity) * water[i] + min(0.0, velocity) * water[j] +
                        exchangeVelocity * (water[i] - water[j])) * dt / distance
                    next[i] -= amount; next[j] += amount
                }
                val e = y * config.width + (x + 1) % config.width
                face(e, (east[i] + east[e]) / 2, config.dx)
                if (y + 1 < config.height) face(i + config.width, (north[i] + north[i + config.width]) / 2, config.dy)
            }
            for (i in water.indices) {
                check(next[i] >= -1e-10) { "Negative transported water: ${next[i]}" }
                // Latent warming raises the capacity while rain forms, rather than
                // raining against a stale cold column for an entire seasonal phase.
                val feedback = capacity[i] * 0.065 * physics.latentHeatJkg / coarseCapacity[i]
                val condensed = max(0.0, next[i] - capacity[i]) * response / (1 + feedback)
                capacity[i] *= exp(min(1.0, condensed * physics.latentHeatJkg / coarseCapacity[i] * 0.065))
                rain[i] += condensed; water[i] = max(0.0, next[i] - condensed)
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
