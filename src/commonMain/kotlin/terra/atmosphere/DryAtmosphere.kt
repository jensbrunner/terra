package terra.atmosphere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class AtmosphereGeometry { LATITUDE_CHANNEL, PERIODIC_F_PLANE }

/** Cell-centered pseudo-latitude on an equal-area Cartesian map, with no pole crossing. */
object PlanetLatitude {
    fun radians(northwardFraction: Double): Double = (northwardFraction - 0.5) * 170.0 * PI / 180.0
    fun atmosphereRowRadians(y: Int, height: Int): Double = radians((y + 0.5) / height)
    fun terrainRowRadians(y: Int, height: Int): Double = radians(1.0 - (y + 0.5) / height)
}

/** Periodic X; free-slip polar walls by default. Array Y increases northward. */
data class DryAtmosphereConfig(
    val width: Int = 96,
    val height: Int = 72,
    val domainWidthMeters: Double = 12_000_000.0,
    val domainHeightMeters: Double = 9_000_000.0,
    val meanHeightMeters: Double = 10_000.0,
    val gravity: Double = 9.81,
    /** Used only by PERIODIC_F_PLANE. */
    val coriolisPerSecond: Double = 1e-4,
    val stepSeconds: Double = 20.0,
    val courantLimit: Double = 0.4,
    val geometry: AtmosphereGeometry = AtmosphereGeometry.LATITUDE_CHANNEL,
    val rotationRateRadiansPerSecond: Double = 7.2921159e-5
) {
    init {
        require(width >= 4 && height >= 4 && width.toLong() * height <= Int.MAX_VALUE)
        require(listOf(domainWidthMeters, domainHeightMeters, meanHeightMeters, gravity, stepSeconds)
            .all { it.isFinite() && it > 0 })
        require(coriolisPerSecond.isFinite() && rotationRateRadiansPerSecond.isFinite())
        require(courantLimit.isFinite() && courantLimit > 0 && courantLimit <= 0.4)
    }

    val periodicY: Boolean get() = geometry == AtmosphereGeometry.PERIODIC_F_PLANE
    fun coriolisAtRow(y: Int): Double = if (periodicY) coriolisPerSecond else
        2 * rotationRateRadiansPerSecond * sin(PlanetLatitude.atmosphereRowRadians(y, height))
    internal fun southRow(y: Int): Int = if (y > 0) y - 1 else if (periodicY) height - 1 else 0
    internal fun northRow(y: Int): Int = if (y + 1 < height) y + 1 else if (periodicY) 0 else height - 1
    val cellCount: Int get() = width * height
    val dx: Double get() = domainWidthMeters / width
    val dy: Double get() = domainHeightMeters / height
    val cellArea: Double get() = dx * dy
}

data class DryCell(val heightMeters: Double, val eastMetersPerSecond: Double, val northMetersPerSecond: Double)

data class DryAtmosphereDiagnostics(
    val volumeCubicMeters: Double,
    /** Kinetic + dry height-anomaly potential, or full thermal potential, per unit fluid density. */
    val energyPerDensity: Double,
    val potentialEnstrophy: Double,
    val minimumHeightMeters: Double,
    val maximumSpeedMetersPerSecond: Double,
    val maximumCourant: Double
)

/** Owns copied arrays; renderers cannot mutate the integrator's state. */
class DryAtmosphereSnapshot internal constructor(
    val config: DryAtmosphereConfig,
    val completedSteps: Long,
    val diagnostics: DryAtmosphereDiagnostics,
    private val heights: DoubleArray,
    private val eastMomenta: DoubleArray,
    private val northMomenta: DoubleArray
) {
    val elapsedSeconds: Double get() = completedSteps * config.stepSeconds
    fun heightAt(x: Int, y: Int): Double = heights[y * config.width + x]
    fun eastWindAt(x: Int, y: Int): Double = eastMomenta[y * config.width + x] / heightAt(x, y)
    fun northWindAt(x: Int, y: Int): Double = northMomenta[y * config.width + x] / heightAt(x, y)
    fun vorticityAt(x: Int, y: Int): Double {
        val west = if (x == 0) config.width - 1 else x - 1
        val east = if (x + 1 == config.width) 0 else x + 1
        val south = config.southRow(y)
        val north = config.northRow(y)
        return (northWindAt(east, y) - northWindAt(west, y)) / (2 * config.dx) -
            (eastWindAt(x, north) - eastWindAt(x, south)) / (2 * config.dy)
    }
}

/**
 * Dry rotating shallow water: h, hu, hv; flat bottom, no mass forcing.
 * MUSCL/MC reconstruction, shear-resolving HLL flux, SSPRK2 and split exact rotation.
 * All step buffers are reused. Invalid/CFL-violating steps fail before committing state.
 */
class DryAtmosphere internal constructor(
    val config: DryAtmosphereConfig,
    private val columns: MoistColumnTransport?,
    initialize: (eastMeters: Double, northMeters: Double) -> DryCell
) {
    constructor(
        config: DryAtmosphereConfig = DryAtmosphereConfig(),
        initialize: (Double, Double) -> DryCell = { _, _ -> DryCell(config.meanHeightMeters, 0.0, 0.0) }
    ) : this(config, null, initialize)

    private val count = config.cellCount
    private var h = DoubleArray(count)
    private var mx = DoubleArray(count)
    private var my = DoubleArray(count)
    private val base = Array(3) { DoubleArray(count) }
    private val stage = Array(3) { DoubleArray(count) }
    private var nextH = DoubleArray(count)
    private var nextMx = DoubleArray(count)
    private var nextMy = DoubleArray(count)
    private val rhs = Array(3) { DoubleArray(count) }
    private val slopeX = Array(3) { DoubleArray(count) }
    private val slopeY = Array(3) { DoubleArray(count) }
    private val maximumXWave = DoubleArray(count)
    private val maximumYWave = DoubleArray(count)
    private val coriolis = DoubleArray(config.height) { config.coriolisAtRow(it) }
    private val rotationCos = DoubleArray(config.height) { cos(coriolis[it] * config.stepSeconds * 0.5) }
    private val rotationSin = DoubleArray(config.height) { sin(coriolis[it] * config.stepSeconds * 0.5) }
    var completedSteps: Long = 0L
        private set
    var maximumCourant: Double = 0.0
        private set
    val elapsedSeconds: Double get() = completedSteps * config.stepSeconds

    init {
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val i = y * config.width + x
            val cell = initialize((x + 0.5) * config.dx, (y + 0.5) * config.dy)
            require(cell.heightMeters.isFinite() && cell.heightMeters > 0 &&
                cell.eastMetersPerSecond.isFinite() && cell.northMetersPerSecond.isFinite()) {
                "Invalid initial dry state at ($x,$y)"
            }
            h[i] = cell.heightMeters
            mx[i] = cell.heightMeters * cell.eastMetersPerSecond
            my[i] = cell.heightMeters * cell.northMetersPerSecond
        }
        validate(h, mx, my)
    }

    /** Reconstruct pressure and compatible circulation from evolved thermal geography. */
    internal fun initializeClimateCirculation(temperature: DoubleArray, weatherPhase: Double? = null): ReconstructedCirculation {
        require(temperature.size == count)
        val reference = columns?.physics?.referenceTemperatureKelvin ?: error("Thermal rest needs moist columns")
        require(temperature.all { it.isFinite() && it > 0 })
        val state = ClimateCirculation.reconstruct(config, reference, temperature, h.sum(), weatherPhase)
        for (i in h.indices) {
            h[i] = state.depth[i]
            mx[i] = h[i] * state.east[i]; my[i] = h[i] * state.north[i]
        }
        maximumCourant = 0.0
        validate(h, mx, my)
        return state
    }

    fun step() {
        val dt = config.stepSeconds
        columns?.beginStep()
        h.copyInto(base[0]); mx.copyInto(base[1]); my.copyInto(base[2])
        rotateHalfStep(base[1], base[2])
        val firstCourant = tendencies(base[0], base[1], base[2], columns?.base)
        for (field in 0..2) for (i in 0 until count) stage[field][i] = base[field][i] + dt * rhs[field][i]
        columns?.makeStage(dt)
        val secondCourant = tendencies(stage[0], stage[1], stage[2], columns?.stage)
        for (i in 0 until count) {
            nextH[i] = 0.5 * (base[0][i] + stage[0][i] + dt * rhs[0][i])
            val east = 0.5 * (base[1][i] + stage[1][i] + dt * rhs[1][i])
            val north = 0.5 * (base[2][i] + stage[2][i] + dt * rhs[2][i])
            nextMx[i] = east
            nextMy[i] = north
        }
        rotateHalfStep(nextMx, nextMy)
        validate(nextH, nextMx, nextMy)
        columns?.finishStep(dt)
        val oldH = h; h = nextH; nextH = oldH
        val oldMx = mx; mx = nextMx; nextMx = oldMx
        val oldMy = my; my = nextMy; nextMy = oldMy
        maximumCourant = max(firstCourant, secondCourant)
        completedSteps++
    }

    /** Exact local rotation; separate from transport so its kinetic-energy invariant is checkable. */
    internal fun rotateHalfStep(east: DoubleArray, north: DoubleArray) {
        for (y in 0 until config.height) {
            val c = rotationCos[y]
            val s = rotationSin[y]
            for (x in 0 until config.width) {
                val i = y * config.width + x
                val u = east[i]
                val v = north[i]
                east[i] = c * u + s * v
                north[i] = -s * u + c * v
            }
        }
    }

    /** Returns the dissipated kinetic energy per unit density and cell area. */
    internal fun dampMomentumAt(i: Int, retention: Double): Double {
        val before = kineticEnergyAt(i)
        mx[i] *= retention
        my[i] *= retention
        return before * (1 - retention * retention)
    }

    // After commit, next buffers hold the preceding state, available until the next step.
    internal fun previousDepthAt(i: Int): Double = nextH[i]
    internal fun previousKineticEnergyAt(i: Int): Double = 0.5 * (nextMx[i] * nextMx[i] + nextMy[i] * nextMy[i]) / nextH[i]
    internal fun eastWindAt(i: Int): Double = mx[i] / h[i]
    internal fun northWindAt(i: Int): Double = my[i] / h[i]
    internal fun depthAt(i: Int): Double = h[i]
    internal fun horizontalDivergenceAt(i: Int): Double {
        val x = i % config.width; val y = i / config.width
        val west = y * config.width + (x + config.width - 1) % config.width
        val east = y * config.width + (x + 1) % config.width
        val south = if (!config.periodicY && y == 0) -northWindAt(i) else northWindAt(config.southRow(y) * config.width + x)
        val north = if (!config.periodicY && y == config.height - 1) -northWindAt(i) else northWindAt(config.northRow(y) * config.width + x)
        return (eastWindAt(east) - eastWindAt(west)) / (2 * config.dx) + (north - south) / (2 * config.dy)
    }
    internal fun speedAt(i: Int): Double = sqrt(mx[i] * mx[i] + my[i] * my[i]) / h[i]
    internal fun kineticEnergyAt(i: Int): Double = 0.5 * (mx[i] * mx[i] + my[i] * my[i]) / h[i]

    fun snapshot(): DryAtmosphereSnapshot = DryAtmosphereSnapshot(
        config, completedSteps, diagnostics(), h.copyOf(), mx.copyOf(), my.copyOf()
    )

    fun diagnostics(): DryAtmosphereDiagnostics {
        val volume = CompensatedSum()
        val energy = CompensatedSum()
        val enstrophy = CompensatedSum()
        var minimum = Double.POSITIVE_INFINITY
        var speed = 0.0
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val i = y * config.width + x
            val west = y * config.width + if (x == 0) config.width - 1 else x - 1
            val east = y * config.width + if (x + 1 == config.width) 0 else x + 1
            val south = config.southRow(y) * config.width + x
            val north = config.northRow(y) * config.width + x
            val u = mx[i] / h[i]
            val v = my[i] / h[i]
            val anomaly = h[i] - config.meanHeightMeters
            val curl = (my[east] / h[east] - my[west] / h[west]) / (2 * config.dx) -
                (mx[north] / h[north] - mx[south] / h[south]) / (2 * config.dy)
            val pv = (curl + coriolis[y]) / h[i]
            volume.add(h[i] * config.cellArea)
            val potential = if (columns == null) 0.5 * config.gravity * anomaly * anomaly else
                0.5 * config.gravity * columns.temperatureAt(i, h[i]) / columns.physics.referenceTemperatureKelvin * h[i] * h[i]
            energy.add((0.5 * h[i] * (u * u + v * v) + potential) * config.cellArea)
            enstrophy.add(0.5 * h[i] * pv * pv * config.cellArea)
            minimum = min(minimum, h[i])
            speed = max(speed, sqrt(u * u + v * v))
        }
        return DryAtmosphereDiagnostics(volume.value, energy.value, enstrophy.value, minimum, speed, maximumCourant)
    }

    private fun validate(depth: DoubleArray, east: DoubleArray, north: DoubleArray) {
        for (i in 0 until count) check(depth[i].isFinite() && depth[i] > 0 && east[i].isFinite() && north[i].isFinite()) {
            "Invalid dry atmosphere at cell $i after $completedSteps steps; no state clamp was applied"
        }
    }

    private fun tendencies(depth: DoubleArray, east: DoubleArray, north: DoubleArray, scalars: Array<DoubleArray>?): Double {
        validate(depth, east, north)
        if (scalars != null) columns!!.prepareFluxes(scalars, depth)
        reconstruct(depth, 0); reconstruct(east, 1); reconstruct(north, 2)
        for (field in rhs) field.fill(0.0)
        maximumXWave.fill(0.0); maximumYWave.fill(0.0)
        val width = config.width
        val height = config.height
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            faceFlux(i, y * width + if (x + 1 == width) 0 else x + 1, depth, east, north, true, scalars)
            if (y + 1 < height || config.periodicY) {
                faceFlux(i, config.northRow(y) * width + x, depth, east, north, false, scalars)
            }
            if (!config.periodicY) {
                if (y == 0) wallFlux(i, -1.0, depth, north, scalars)
                if (y + 1 == height) wallFlux(i, 1.0, depth, north, scalars)
            }
        }
        var courant = 0.0
        for (i in 0 until count) courant = max(courant,
            config.stepSeconds * (maximumXWave[i] / config.dx + maximumYWave[i] / config.dy))
        check(courant.isFinite() && courant <= config.courantLimit) {
            "Dry atmosphere CFL $courant exceeds ${config.courantLimit}; use a smaller fixed timestep (currently ${config.stepSeconds}s)"
        }
        columns?.completeFluxes(depth, config.stepSeconds)
        return courant
    }

    private fun reconstruct(field: DoubleArray, component: Int) {
        val width = config.width
        val height = config.height
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val west = y * width + if (x == 0) width - 1 else x - 1
            val east = y * width + if (x + 1 == width) 0 else x + 1
            val south = config.southRow(y) * width + x
            val north = config.northRow(y) * width + x
            slopeX[component][i] = limitedSlope(field[i] - field[west], field[east] - field[i])
            // Reflect normal momentum, extend depth and tangential momentum evenly.
            val southValue = if (!config.periodicY && y == 0 && component == 2) -field[i] else field[south]
            val northValue = if (!config.periodicY && y + 1 == height && component == 2) -field[i] else field[north]
            slopeY[component][i] = limitedSlope(field[i] - southValue, northValue - field[i])
        }
    }

    /** HLL flux against a reflected ghost: zero mass/tracers/shear, finite normal pressure.
     * outward is -1 at the south wall, +1 at the north wall. The symmetric HLL
     * wave bounds are ±(|v|+c), giving F_hv = p + h*v_out*(v_out+|v_out|+c).
     */
    private fun wallFlux(cell: Int, outward: Double, depth: DoubleArray, north: DoubleArray, scalars: Array<DoubleArray>?) {
        val hWall = depth[cell] + outward * 0.5 * slopeY[0][cell]
        check(hWall > 0) { "Nonpositive reconstructed wall layer; no flux clamp was applied" }
        val v = outward * (north[cell] + outward * 0.5 * slopeY[2][cell]) / hWall
        val gravity = if (scalars == null) config.gravity else
            config.gravity * columns!!.faceTemperature(cell, false, outward) / columns.physics.referenceTemperatureKelvin
        val sound = sqrt(gravity * hWall)
        val wave = abs(v) + sound
        // h*v² + p + (|v|+c)*h*v is the reflected-state HLL momentum flux.
        val pressureFlux = 0.5 * gravity * hWall * hWall + hWall * v * (v + wave)
        rhs[2][cell] -= outward * pressureFlux / config.dy
        maximumYWave[cell] = max(maximumYWave[cell], wave)
        // No call to tracer transport: the wall's mass flux is identically zero.
    }

    private fun faceFlux(left: Int, right: Int, depth: DoubleArray, east: DoubleArray, north: DoubleArray, alongX: Boolean, scalars: Array<DoubleArray>?) {
        val slopes = if (alongX) slopeX else slopeY
        val hLeft = depth[left] + 0.5 * slopes[0][left]
        val hRight = depth[right] - 0.5 * slopes[0][right]
        check(hLeft > 0 && hRight > 0) { "Nonpositive reconstructed dry layer; no flux clamp was applied" }
        val mxLeft = east[left] + 0.5 * slopes[1][left]
        val mxRight = east[right] - 0.5 * slopes[1][right]
        val myLeft = north[left] + 0.5 * slopes[2][left]
        val myRight = north[right] - 0.5 * slopes[2][right]
        val momentumLeft = if (alongX) mxLeft else myLeft
        val momentumRight = if (alongX) mxRight else myRight
        val normalLeft = momentumLeft / hLeft
        val normalRight = momentumRight / hRight
        val gravityLeft = if (scalars == null) config.gravity else
            config.gravity * columns!!.faceTemperature(left, alongX, 1.0) / columns.physics.referenceTemperatureKelvin
        val gravityRight = if (scalars == null) config.gravity else
            config.gravity * columns!!.faceTemperature(right, alongX, -1.0) / columns.physics.referenceTemperatureKelvin
        val soundLeft = sqrt(gravityLeft * hLeft)
        val soundRight = sqrt(gravityRight * hRight)
        val leftSpeed = min(normalLeft - soundLeft, normalRight - soundRight)
        val rightSpeed = max(normalLeft + soundLeft, normalRight + soundRight)
        val pressureLeft = 0.5 * gravityLeft * hLeft * hLeft
        val pressureRight = 0.5 * gravityRight * hRight * hRight
        val normalFluxLeft = momentumLeft * normalLeft + pressureLeft
        val normalFluxRight = momentumRight * normalRight + pressureRight
        val mass: Double
        val normalFlux: Double
        when {
            leftSpeed >= 0 -> { mass = momentumLeft; normalFlux = normalFluxLeft }
            rightSpeed <= 0 -> { mass = momentumRight; normalFlux = normalFluxRight }
            else -> {
                val inverseWaveGap = 1.0 / (rightSpeed - leftSpeed)
                val hllMass = (rightSpeed * momentumLeft - leftSpeed * momentumRight +
                    leftSpeed * rightSpeed * (hRight - hLeft)) * inverseWaveGap
                val hllNormal = (rightSpeed * normalFluxLeft - leftSpeed * normalFluxRight +
                    leftSpeed * rightSpeed * (momentumRight - momentumLeft)) * inverseWaveGap
                // The thermal system has a material contact as well as gravity waves.
                // Restore it: equal pressure/normal velocity must not diffuse h and T at c.
                val denominator = hLeft * (leftSpeed - normalLeft) - hRight * (rightSpeed - normalRight)
                val contact = (pressureRight - pressureLeft + momentumLeft * (leftSpeed - normalLeft) -
                    momentumRight * (rightSpeed - normalRight)) / denominator
                val useLeft = contact >= 0.0
                val h = if (useLeft) hLeft else hRight
                val speed = if (useLeft) leftSpeed else rightSpeed
                val velocity = if (useLeft) normalLeft else normalRight
                val pressure = if (useLeft) pressureLeft else pressureRight
                val starDepth = h * (speed - velocity) / (speed - contact)
                val starPressure = pressure + h * (speed - velocity) * (contact - velocity)
                if (contact.isFinite() && contact > leftSpeed && contact < rightSpeed &&
                    starDepth.isFinite() && starDepth > 0.0 && starPressure.isFinite() && starPressure > 0.0) {
                    mass = starDepth * contact
                    normalFlux = mass * contact + starPressure
                } else {
                    // Robust two-wave fallback only for inadmissible contact states.
                    mass = hllMass; normalFlux = hllNormal
                }
            }
        }
        // The transverse/shear characteristic travels with mass, not the gravity wave.
        // In particular, a stationary shear contact has zero transverse momentum flux.
        val transverseVelocity = if (mass >= 0) {
            (if (alongX) myLeft else mxLeft) / hLeft
        } else {
            (if (alongX) myRight else mxRight) / hRight
        }
        val inverseDistance = 1.0 / if (alongX) config.dx else config.dy
        val massFlux = mass * inverseDistance
        val eastFlux = (if (alongX) normalFlux else mass * transverseVelocity) * inverseDistance
        val northFlux = (if (alongX) mass * transverseVelocity else normalFlux) * inverseDistance
        rhs[0][left] -= massFlux; rhs[0][right] += massFlux
        rhs[1][left] -= eastFlux; rhs[1][right] += eastFlux
        rhs[2][left] -= northFlux; rhs[2][right] += northFlux
        if (scalars != null) columns!!.faceFlux(left, right, massFlux, alongX)
        val waves = if (alongX) maximumXWave else maximumYWave
        val wave = max(abs(leftSpeed), abs(rightSpeed))
        waves[left] = max(waves[left], wave); waves[right] = max(waves[right], wave)
    }

    private fun limitedSlope(backward: Double, forward: Double): Double {
        if (backward * forward <= 0.0) return 0.0
        val magnitude = min(2 * min(abs(backward), abs(forward)), 0.5 * abs(backward + forward))
        return if (backward > 0) magnitude else -magnitude
    }
}

internal class CompensatedSum {
    var value = 0.0
        private set
    private var correction = 0.0
    fun add(term: Double) {
        val adjusted = term - correction
        val next = value + adjusted
        correction = (next - value) - adjusted
        value = next
    }
}
