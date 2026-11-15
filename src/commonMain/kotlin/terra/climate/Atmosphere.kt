package terra.climate

import terra.world.World
import kotlin.math.*

internal fun saturationWater(kelvin: Double) = (6.0 * exp((kelvin - 273.15) * 0.06)).coerceIn(1.0, 45.0)

/** Rotating shallow-water column: centre scalars, east/south face velocities.
 * Forward-backward gravity waves and upwind mass flux conserve column mass.
 * Heat, vapour and cloud water follow semi-Lagrangian trajectories.
 */
internal class Atmosphere(private val world: World) {
    val count = world.width * world.height
    val h = DoubleArray(count) { COLUMN_DEPTH }
    val u = DoubleArray(count)
    val v = DoubleArray(count)
    val temperature = DoubleArray(count)
    val vapor = DoubleArray(count)
    val cloud = DoubleArray(count)
    val rain = DoubleArray(count)
    private val height = DoubleArray(count)
    private val land = BooleanArray(count)
    private val sunlight = PlanetarySunlight(world.width, world.height)
    private val fields = arrayOf(u, v, temperature, vapor, cloud)
    private val next = Array(fields.size) { DoubleArray(count) }
    private val eastFlux = DoubleArray(count)
    private val southFlux = DoubleArray(count)
    private val evaporation = DoubleArray(count)
    private val coriolis = DoubleArray(world.height) { 2 * ROTATION_RATE * sin(PI * (0.5 - (it + 0.5) / world.height)) }
    private val target = DoubleArray(world.height)
    private val rotationSin = DoubleArray(world.height)
    private val rotationCos = DoubleArray(world.height)

    init {
        for (i in 0 until count) {
            val x = i % world.width; val y = i / world.width
            height[i] = world.elevationMeters(x, y)
            land[i] = world.isLand(x, y)
            temperature[i] = 250.0 + 110.0 * sunlight.dailyMeanInputFactor(y) - height[i] * 0.006
            vapor[i] = min(if (land[i]) 5.0 else 10.0, saturationWater(temperature[i]))
            h[i] += 0.25 * sin(x * 12.9898 + y * 78.233 + world.seed)
        }
    }

    fun advance(seconds: Double, elapsed: Double, surface: LandscapeWater) {
        rain.fill(0.0)
        var left = seconds
        while (left > 1e-9) {
            var maxSpeed = 0.0
            var maxDepth = COLUMN_DEPTH
            for (i in h.indices) {
                maxSpeed = max(maxSpeed, max(abs(u[i]), abs(v[i])))
                maxDepth = max(maxDepth, h[i])
            }
            // Two-dimensional gravity-wave CFL, including advective transport.
            val dt = min(left, 0.45 * TILE_METERS / (sqrt(GRAVITY * maxDepth) + maxSpeed))
            step(dt, elapsed + seconds - left)
            left -= dt
        }
        for (i in 0 until count) evaporation[i] = if (land[i]) max(0.0, temperature[i] - 268.0) * 0.000002 * seconds else 0.0
        val actual = surface.advance(rain, evaporation, temperature, seconds)
        for (i in 0 until count) vapor[i] += actual[i]
    }

    private fun step(dt: Double, time: Double) {
        val w = world.width; val rows = world.height
        sunlight.update(time)
        for (y in 0 until rows) {
            target[y] = sunlight.dailyMeanInputFactor(y)
            rotationSin[y] = sin(coriolis[y] * dt)
            rotationCos[y] = cos(coriolis[y] * dt)
        }
        val landResponse = -expm1(-dt / (2 * 86400.0))
        val oceanResponse = -expm1(-dt / (10 * 86400.0))
        val condensationResponse = -expm1(-dt / 900)
        val rainResponse = -expm1(-dt / 7200)
        val oceanEvaporationResponse = -expm1(-dt / 172800)
        val drag = exp(-dt / 86400.0)
        for (i in 0 until count) {
            val x = i % w; val y = i / w
            val west = y * w + (x + w - 1) % w; val east = y * w + (x + 1) % w
            val north = max(0, y - 1) * w + x; val south = min(rows - 1, y + 1) * w + x
            val uc = (u[west] + u[i]) * 0.5
            val vc = (v[i] + if (y == 0) 0.0 else v[north]) * 0.5
            val bx = x - uc * dt / TILE_METERS
            val by = (y - vc * dt / TILE_METERS).coerceIn(0.0, rows - 1.0)
            // Hydrostatic thermal thickness adds geopotential to the column.
            // The mass-pressure acceleration is -grad(p)/rho = -g grad(h).
            val thermalEast = THERMAL_THICKNESS_PER_KELVIN * (temperature[east] + height[east] * 0.006 - temperature[i] - height[i] * 0.006)
            val thermalSouth = THERMAL_THICKNESS_PER_KELVIN * (temperature[south] + height[south] * 0.006 - temperature[i] - height[i] * 0.006)
            val crossV = (v[i] + v[east] + if (y == 0) 0.0 else v[north] + v[north - x + (x + 1) % w]) * 0.25
            val crossU = (uc + (u[south] + u[south - x + (x + w - 1) % w]) * 0.5) * 0.5
            next[0][i] = (sample(u, x - u[i] * dt / TILE_METERS, y - crossV * dt / TILE_METERS) * rotationCos[y] +
                crossV * rotationSin[y] - dt * GRAVITY * (h[east] - h[i] + thermalEast) / TILE_METERS) * drag
            next[1][i] = if (y == rows - 1) 0.0 else
                (sample(v, x - crossU * dt / TILE_METERS, y - v[i] * dt / TILE_METERS) * (rotationCos[y] + rotationCos[y + 1]) * 0.5 -
                    crossU * (rotationSin[y] + rotationSin[y + 1]) * 0.5 - dt * GRAVITY * (h[south] - h[i] + thermalSouth) / TILE_METERS) * drag
            val advected = sample(temperature, bx, by)
            val equilibrium = 250.0 + 110.0 * target[y] - height[i] * 0.006 + 8 * (sunlight.inputFactor(x, y) - target[y])
            val t = advected + (equilibrium - advected) * (if (land[i]) landResponse else oceanResponse)
            val q = sample(vapor, bx, by)
            val uphill = max(0.0, uc * (height[east] - height[west]) / (2 * TILE_METERS) + vc * (height[south] - height[north]) / (2 * TILE_METERS))
            val condense = min(q, max(0.0, q - saturationWater(t)) * condensationResponse + uphill * 0.0002 * dt)
            val cloudIn = sample(cloud, bx, by) + condense
            val precip = max(0.0, cloudIn - 1.5) * rainResponse
            rain[i] += precip
            next[2][i] = t + condense * CONDENSATION_KELVIN_PER_MM
            next[3][i] = q - condense + if (land[i]) 0.0 else max(0.0, saturationWater(t) - q) * oceanEvaporationResponse
            next[4][i] = cloudIn - precip
        }
        for (k in fields.indices) next[k].copyInto(fields[k])
        for (i in 0 until count) {
            val x = i % w; val y = i / w
            val east = y * w + (x + 1) % w
            eastFlux[i] = u[i] * if (u[i] >= 0) h[i] else h[east]
            southFlux[i] = if (y == rows - 1) 0.0 else v[i] * if (v[i] >= 0) h[i] else h[i + w]
        }
        for (i in 0 until count) {
            val x = i % w; val y = i / w
            h[i] -= dt / TILE_METERS * (eastFlux[i] - eastFlux[y * w + (x + w - 1) % w] +
                southFlux[i] - if (y == 0) 0.0 else southFlux[i - w])
        }
    }

    private fun sample(a: DoubleArray, x: Double, y: Double): Double {
        val w = world.width; val rows = world.height
        val xx = x - floor(x / w) * w; val x0 = floor(xx).toInt(); val x1 = (x0 + 1) % w
        val yy = y.coerceIn(0.0, rows - 1.0); val y0 = floor(yy).toInt(); val y1 = min(rows - 1, y0 + 1)
        val fx = xx - x0; val fy = yy - y0
        return (a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx) * (1 - fy) +
            (a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx) * fy
    }
}
