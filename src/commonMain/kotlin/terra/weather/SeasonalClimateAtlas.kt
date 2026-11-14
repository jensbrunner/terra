package terra.weather

import terra.atmosphere.SolarConfig
import terra.atmosphere.PlanetarySunlight
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
        val sunlight = PlanetarySunlight(w, h, solar, northUp = false)
        val q = DoubleArray(n); val previousRain = DoubleArray(n)
        var residual = 0.0
        for (m in 0..11) {
            val phase = (m + 0.5) / 12.0
            val season = sin(2 * PI * phase)
            for (i in 0 until n) {
                val lat = PI * (0.5 - (i / w + 0.5) / h)
                val continental = (g.coastDistanceMeters[i] / 1_500_000).coerceIn(0.0, 1.0) * g.landFraction[i]
                val amplitude = (6 + 23 * continental) * abs(sin(lat)) * solar.axialTiltDegrees / 23.44
                t[m][i] = 30 - 42 * sin(lat).pow(2) + amplitude * sin(2 * PI * (phase - 0.08 * (1 - continental))) * sign(lat) - 0.006 * g.elevationMeters[i]
                val shifted = lat * 180 / PI - 8 * season * solar.axialTiltDegrees / 23.44
                e[m][i] = windOverride ?: 4 * tanh((abs(shifted) - 30) / 8) * tanh((65 - abs(shifted)) / 8)
                s[m][i] = 1.5 * sin(2 * shifted * PI / 180)
            }
            // Warm-starting is only an optimization; contraction to ocean supply removes month-zero bias.
            q.fill(0.0); previousRain.fill(0.0)
            val tau = DoubleArray(n); val lambda = DoubleArray(n); val oceanQ = DoubleArray(n)
            for (i in 0 until n) {
                val u = e[m][i]; val v = s[m][i]
                tau[i] = 1 / max(0.2 / min(g.config.dx, g.config.dy), max(abs(u) / g.config.dx, abs(v) / g.config.dy)) / 86400
                val lat = 90 - (i / w + 0.5) * 180 / h
                val convergence = exp(-((lat - 8 * season) / 16).pow(2))
                val uplift = max(0.0, u * g.slopeEast[i] + v * g.slopeSouth[i])
                lambda[i] = 0.045 + 0.08 * convergence + 1.4 * uplift
                oceanQ[i] = (55 * exp(0.04 * (t[m][i] - 20))).coerceIn(5.0, 100.0)
            }
            repeat(sweeps) { sweep ->
                var change = 0.0
                for (index in 0 until n) {
                    val i = if (sweep % 2 == 0) index else n - 1 - index
                    val u = e[m][i]; val v = s[m][i]
                    val scale = max(0.2 / min(g.config.dx, g.config.dy), max(abs(u) / g.config.dx, abs(v) / g.config.dy))
                    val up = sample(q, w, h, i % w - u / g.config.dx / scale, i / w - v / g.config.dy / scale)
                    val k = (1 - g.landFraction[i]) * 1.2
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
    private fun sample(a: DoubleArray, w: Int, h: Int, x: Double, y: Double): Double {
        val ix = floor(x).toInt(); val iy = floor(y).toInt(); val fx = x - ix; val fy = y - iy
        fun at(dx: Int, dy: Int) = a[(iy + dy).coerceIn(0, h - 1) * w + ((ix + dx) % w + w) % w]
        return (at(0, 0) * (1 - fx) + at(1, 0) * fx) * (1 - fy) + (at(0, 1) * (1 - fx) + at(1, 1) * fx) * fy
    }
}
