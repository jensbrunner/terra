package terra.weather

import terra.atmosphere.SolarConfig
import terra.atmosphere.PlanetarySunlight
import terra.atmosphere.PlanetLatitude
import kotlin.math.*

/** Diagnostic climatology in real monthly depths, never an atmospheric inventory.
 * Builder-owned arrays are frozen on publication; normal evolution only reads them. */
class SeasonalClimateAtlas internal constructor(
    val geography: ClimateGeography, val solar: SolarConfig,
    internal val temperature: Array<DoubleArray>, internal val rain: Array<DoubleArray>,
    internal val pet: Array<DoubleArray>, internal val east: Array<DoubleArray>, internal val south: Array<DoubleArray>,
    val regionalAnnualMeanRainMm: Double, val maximumHumidityChangeMm: Double
) {
    val geometryRevision = geography.geometryRevision
    val width = geography.world.width
    val height = geography.world.height
    private val count = width * height
    internal val annualRain = DoubleArray(count) { i -> rain.sumOf { it[i] } }
    internal val annualPet = DoubleArray(count) { i -> pet.sumOf { it[i] } }
    internal val meanTemperature = DoubleArray(count) { i -> temperature.sumOf { it[i] } / 12 }
    internal val growingWarmth = DoubleArray(count) { i -> temperature.sumOf { max(0.0, it[i] - 5) * 365 / 12 } }
    internal val warmest = DoubleArray(count) { i -> temperature.maxOf { it[i] } }
    fun temperatureC(month: Int, tile: Int) = temperature[month][tile]
    fun rainfallMm(month: Int, tile: Int) = rain[month][tile]
    fun potentialEvapotranspirationMm(month: Int, tile: Int) = pet[month][tile]
    fun annualRainfallMm(tile: Int) = annualRain[tile]
    fun aridity(tile: Int) = annualRain[tile] / max(1.0, annualPet[tile])
    fun windEastMetersPerSecond(month: Int, tile: Int) = east[month][tile]
    fun windSouthMetersPerSecond(month: Int, tile: Int) = south[month][tile]
    fun monthAt(seconds: Double) = floor(((seconds / solar.yearSeconds + solar.initialYearFraction) % 1.0) * 12 + 1e-10).toInt() % 12

    /** Compatibility view for existing seasonal inspector; full tile resolution avoids a second rainfall representation. */
    internal fun seasonalSnapshot(): SeasonalClimateSnapshot {
        val temps = Array(4) { DoubleArray(count) }; val rains = Array(4) { DoubleArray(count) }
        val durations = DoubleArray(4) { solar.yearSeconds / 4 }
        val low = DoubleArray(count); val high = DoubleArray(count)
        for (i in 0 until count) {
            val j = (height - 1 - i / width) * width + i % width
            low[j] = temperature.minOf { it[i] }; high[j] = warmest[i]
            for (m in 0..11) { temps[m / 3][j] += temperature[m][i] * solar.yearSeconds / 12; rains[m / 3][j] += rain[m][i] }
        }
        val year = ClimateYear(temps, rains, durations, low, high, DoubleArray(count), true)
        return SeasonalClimateSnapshot(width, height, solar.yearSeconds, 0, year, year)
    }
}

/** One upwind fixed-point moisture model. No live cloud, pressure, heat or vapor state. */
class ClimateAtlasBuilder(private val solar: SolarConfig = SolarConfig(), private val sweeps: Int = 160,
    private val windOverride: Double? = null) {
    fun build(g: ClimateGeography): SeasonalClimateAtlas {
        require(sweeps > 0)
        val n = g.count; val w = g.width; val h = g.height
        val t = Array(12) { DoubleArray(n) }; val p = Array(12) { DoubleArray(n) }
        val e = Array(12) { DoubleArray(n) }; val s = Array(12) { DoubleArray(n) }
        // Atlas months are orbital bins; initialYearFraction belongs to monthAt(), not
        // to the forcing of each bin (otherwise a nonzero start season is counted twice).
        val sunlight = PlanetarySunlight(w, h, solar.copy(initialYearFraction = 0.0), northUp = false)
        calculateTemperatures(g, sunlight, t)
        val q = DoubleArray(n); val previousRain = DoubleArray(n)
        var residual = 0.0
        for (m in 0..11) {
            diagnoseWinds(g, t[m], e[m], s[m])
            // Independent diagnostic solves: ocean supply fixes each month without a persistent vapor store.
            q.fill(0.0); previousRain.fill(0.0)
            val tau = DoubleArray(n); val lambda = DoubleArray(n); val oceanQ = DoubleArray(n)
            for (i in 0 until n) {
                val u = e[m][i]; val v = s[m][i]
                tau[i] = 1 / max(0.2 / min(g.config.dx, g.config.dy), max(abs(u) / g.config.dx, abs(v) / g.config.dy)) / 86400
                val x = i % w; val y = i / w
                val divergence = (e[m][y * w + (x + 1) % w] - e[m][y * w + (x + w - 1) % w]) / (2 * g.config.dx) +
                    (s[m][min(h - 1, y + 1) * w + x] - s[m][max(0, y - 1) * w + x]) / (2 * g.config.dy)
                val convergenceRainout = (CONVERGENCE_EFFICIENCY * max(0.0, -divergence) * 86400).coerceAtMost(0.3)
                val uplift = max(0.0, u * g.slopeEast[i] + v * g.slopeSouth[i])
                lambda[i] = 0.045 + convergenceRainout + 1.4 * uplift
                oceanQ[i] = (55 * exp(0.04 * (t[m][i] - 20))).coerceIn(5.0, 100.0)
            }
            repeat(sweeps) { sweep ->
                var change = 0.0
                for (index in 0 until n) {
                    val i = if (sweep % 2 == 0) index else n - 1 - index
                    val u = e[m][i]; val v = s[m][i]
                    val scale = max(0.2 / min(g.config.dx, g.config.dy), max(abs(u) / g.config.dx, abs(v) / g.config.dy))
                    val up = sample(q, w, h, i % w - u / g.config.dx / scale, i / w - v / g.config.dy / scale)
                    // Overlap sums can exceed one by roundoff; an ocean source must never become a sink.
                    val k = (1 - g.landFraction[i].coerceIn(0.0, 1.0)) * 1.2
                    val recycle = 0.18 * previousRain[i] * g.landFraction[i]
                    val next = (up + tau[i] * (recycle + k * oceanQ[i])) / (1 + tau[i] * (lambda[i] + k))
                    change = max(change, abs(next - q[i])); q[i] = next
                    previousRain[i] = lambda[i] * next
                }
                if (sweep == sweeps - 1) residual = max(residual, change)
            }
            for (i in 0 until n) p[m][i] = previousRain[i] * 365 / 12
        }
        val tileCount = g.tileElevation.size
        val tileT = Array(12) { DoubleArray(tileCount) }; val tileP = Array(12) { DoubleArray(tileCount) }
        val tilePet = Array(12) { DoubleArray(tileCount) }; val tileE = Array(12) { DoubleArray(tileCount) }; val tileS = Array(12) { DoubleArray(tileCount) }

        val weight = DoubleArray(tileCount); val scratch = DoubleArray(n)
        for (m in 0..11) {
            sunlight.update((m + 0.5) * solar.yearSeconds / 12)
            g.overlap.prolongUniform(e[m], tileE[m]); g.overlap.prolongUniform(s[m], tileS[m])
            // Bilinear reconstruction within each coarse cell, then conservative overlap normalization.
            // This preserves regional water exactly while blending adjacent-cell boundaries.
            for (i in 0 until tileCount) {
                val x = i % g.world.width; val y = i / g.world.width
                val px = (x + 0.5) * w / g.world.width - 0.5; val py = (y + 0.5) * h / g.world.height - 0.5
                tileT[m][i] = sample(t[m], w, h, px, py) - 0.006 * (g.tileElevation[i] - sample(g.elevationMeters, w, h, px, py))
                val slopeX = (g.tileElevation[y * g.world.width + (x + 1) % g.world.width] - g.tileElevation[y * g.world.width + (x + g.world.width - 1) % g.world.width]) / (2 * g.config.domainWidthMeters / g.world.width)
                val slopeY = (g.tileElevation[min(g.world.height - 1, y + 1) * g.world.width + x] - g.tileElevation[max(0, y - 1) * g.world.width + x]) / (2 * g.config.domainHeightMeters / g.world.height)
                val parent = g.overlap.targetOfSource[i]
                weight[i] = (sample(p[m], w, h, px, py) / max(0.001, p[m][parent])).coerceIn(0.5, 2.0) * (1 + 12 * (tileE[m][i] * slopeX + tileS[m][i] * slopeY)).coerceIn(0.5, 1.8)
                val light = sunlight.dailyMeanInputFactor((y.toDouble() * h / g.world.height).toInt().coerceIn(0, h - 1))
                tilePet[m][i] = (max(0.0, tileT[m][i] + 5) * 0.18 * light / 0.32).coerceIn(0.0, 8.0) * 365 / 12
            }
            g.overlap.prolongWeighted(p[m], weight, tileP[m], scratch)
        }
        return SeasonalClimateAtlas(g, solar, tileT, tileP, tilePet, tileE, tileS, p.sumOf { it.sum() } / n, residual)
    }
    /** Local linear energy balance: absorbed sunlight = A + B T. The thermal
     * response is solved for a periodic year analytically, with no heat PDE or spin-up.
     * The monthly mean of the exact exponential response supplies the atlas temperature. */
    private fun calculateTemperatures(g: ClimateGeography, sunlight: PlanetarySunlight, temperature: Array<DoubleArray>) {
        val light = Array(12) { DoubleArray(g.height) }
        for (month in 0..11) for (sample in 0 until 4) {
            sunlight.update((month + (sample + 0.5) / 4) * solar.yearSeconds / 12)
            for (y in 0 until g.height) light[month][y] += sunlight.dailyMeanInputFactor(y) / 4
        }
        val monthSeconds = solar.yearSeconds / 12
        for (i in 0 until g.count) {
            val land = g.landFraction[i].coerceIn(0.0, 1.0)
            val continental = (g.coastDistanceMeters[i] / 1_500_000).coerceIn(0.0, 1.0) * land
            // Effective albedo includes fixed unresolved reflection; no live cloud feedback.
            val absorbedFraction = 0.72 - 0.04 * land
            val responseDays = OCEAN_RESPONSE_DAYS + (LAND_RESPONSE_DAYS - OCEAN_RESPONSE_DAYS) * continental
            val responseSeconds = responseDays * 86400
            val monthlyResponse = -expm1(-monthSeconds / responseSeconds)
            val decay = 1 - monthlyResponse
            fun equilibrium(month: Int) = (SOLAR_FLUX_W_M2 * absorbedFraction * light[month][i / g.width] - OUTGOING_AT_ZERO_W_M2) / OUTGOING_SLOPE_W_M2_K
            var affineOffset = 0.0
            for (month in 0..11) affineOffset = affineOffset * decay + equilibrium(month) * monthlyResponse
            var boundaryTemperature = affineOffset / -expm1(-solar.yearSeconds / responseSeconds)
            val meanMemory = monthlyResponse * responseSeconds / monthSeconds
            for (month in 0..11) {
                val target = equilibrium(month)
                temperature[month][i] = target + (boundaryTemperature - target) * meanMemory - 0.006 * g.elevationMeters[i]
                boundaryTemperature = target + (boundaryTemperature - target) * decay
            }
        }
    }

    /** Diagnostic warm-low pressure tendency, balanced by rotation and linear drag.
     * Map V points south: r u + f v = ax, -f u + r v = ay. Drag keeps the
     * equatorial limit finite; there is no 1/f division or prescribed circulation belt. */
    internal fun diagnoseWinds(g: ClimateGeography, temperature: DoubleArray, east: DoubleArray, south: DoubleArray) {
        val drag = 1 / (WIND_DRAG_DAYS * 86400)
        for (i in 0 until g.count) {
            if (windOverride != null) { east[i] = windOverride; south[i] = 0.0; continue }
            val x = i % g.width; val y = i / g.width
            val ax = THERMAL_PRESSURE_COUPLING * (temperature[y * g.width + (x + 1) % g.width] - temperature[y * g.width + (x + g.width - 1) % g.width]) / (2 * g.config.dx)
            val ay = THERMAL_PRESSURE_COUPLING * (temperature[min(g.height - 1, y + 1) * g.width + x] - temperature[max(0, y - 1) * g.width + x]) / (2 * g.config.dy)
            val coriolis = 2 * g.config.rotationRateRadiansPerSecond * sin(PlanetLatitude.terrainRowRadians(y, g.height))
            val denominator = drag * drag + coriolis * coriolis
            val u = (drag * ax - coriolis * ay) / denominator
            val v = (coriolis * ax + drag * ay) / denominator
            // Smooth vector speed limit preserves direction and avoids clipping each component.
            val limiter = MAX_WIND_M_S / hypot(MAX_WIND_M_S, hypot(u, v))
            east[i] = u * limiter
            south[i] = if (y == 0 || y == g.height - 1) 0.0 else v * limiter
        }
    }

    private companion object {
        const val SOLAR_FLUX_W_M2 = 1360.0
        const val OUTGOING_AT_ZERO_W_M2 = 180.0
        const val OUTGOING_SLOPE_W_M2_K = 4.0
        const val LAND_RESPONSE_DAYS = 40.0
        const val OCEAN_RESPONSE_DAYS = 240.0
        // Pressure acceleration per kelvin/m: m² s⁻² K⁻¹, an effective shallow thermal column.
        const val THERMAL_PRESSURE_COUPLING = 25.0
        const val WIND_DRAG_DAYS = 1.0
        const val MAX_WIND_M_S = 12.0
        const val CONVERGENCE_EFFICIENCY = 0.1
    }

    private fun sample(a: DoubleArray, w: Int, h: Int, x: Double, y: Double): Double {
        val ix = floor(x).toInt(); val iy = floor(y).toInt(); val fx = x - ix; val fy = y - iy
        fun at(dx: Int, dy: Int) = a[(iy + dy).coerceIn(0, h - 1) * w + ((ix + dx) % w + w) % w]
        return (at(0, 0) * (1 - fx) + at(1, 0) * fx) * (1 - fy) + (at(0, 1) * (1 - fx) + at(1, 1) * fx) * fy
    }
}
