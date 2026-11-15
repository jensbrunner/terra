package terra.climate

import kotlin.math.*

/** Diagnostic climatology in real monthly depths, never an atmospheric inventory.
 * Builder-owned arrays are frozen on publication; normal evolution only reads them. */
class SeasonalClimateAtlas internal constructor(
    val geography: ClimateGeography, val solar: SolarConfig,
    internal val temperature: Array<DoubleArray>, internal val rain: Array<DoubleArray>,
    internal val pet: Array<DoubleArray>, internal val humidity: Array<DoubleArray>, internal val east: Array<DoubleArray>, internal val south: Array<DoubleArray>,
    val regionalAnnualMeanRainMm: Double, val maximumHumidityChangeMm: Double,
    val moistureSweeps: Int, val maximumMoistureResidualMmPerDay: Double
) {
    val rainfall by lazy { RainfallDistribution(this) }
    val geometryRevision = geography.geometryRevision
    val width = geography.world.width
    val height = geography.world.height
    internal val cloudFlowWidth = minOf(16, width)
    internal val cloudFlowHeight = minOf(12, height)
    /** Small derived wind raster prevents tile-scale shear from tearing cloud formations. */
    internal val cloudFlow by lazy {
        val grid = GridOverlap(width, height, cloudFlowWidth, cloudFlowHeight)
        arrayOf(east, south).map { field -> Array(12) { month ->
            DoubleArray(cloudFlowWidth * cloudFlowHeight).also { grid.restrict(field[month], it) }
        } }
    }
    private val count = width * height
    internal val annualRain = DoubleArray(count) { i -> rain.sumOf { it[i] } }
    internal val annualPet = DoubleArray(count) { i -> pet.sumOf { it[i] } }
    internal val meanTemperature = DoubleArray(count) { i -> temperature.sumOf { it[i] } / 12 }
    internal val growingWarmth = DoubleArray(count) { i -> temperature.sumOf { max(0.0, it[i] - 5) * 365 / 12 } }
    internal val warmest = DoubleArray(count) { i -> temperature.maxOf { it[i] } }
    fun temperatureC(month: Int, tile: Int) = temperature[month][tile]
    fun potentialEvapotranspirationMm(month: Int, tile: Int) = pet[month][tile]
    fun rainfallMm(month: Int, tile: Int) = rain[month][tile]
    fun annualRainfallMm(tile: Int) = annualRain[tile]
    fun aridity(tile: Int) = annualRain[tile] / max(1.0, annualPet[tile])
    fun relativeHumidity(month: Int, tile: Int) = humidity[month][tile]
    fun windEastMetersPerSecond(month: Int, tile: Int) = east[month][tile]
    fun windSouthMetersPerSecond(month: Int, tile: Int) = south[month][tile]
    fun monthAt(seconds: Double) = floor(((seconds / solar.yearSeconds + solar.initialYearFraction) % 1.0) * 12 + 1e-10).toInt() % 12

}

/** One conservative finite-volume moisture model. No live cloud, pressure, heat or vapor state. */
// Native terrain cells need a larger iteration budget; converged months still exit early.
class ClimateAtlasBuilder(private val solar: SolarConfig = SolarConfig(), private val sweeps: Int = 1536,
    private val windOverride: Double? = null,
    private val moistureToleranceMmPerDay: Double = MOISTURE_TOLERANCE_MM_DAY) {
    fun build(g: ClimateGeography): SeasonalClimateAtlas {
        require(sweeps > 0 && moistureToleranceMmPerDay > 0)
        val n = g.count; val w = g.width; val h = g.height
        val t = Array(12) { DoubleArray(n) }; val p = Array(12) { DoubleArray(n) }
        val humidity = Array(12) { DoubleArray(n) }
        val e = Array(12) { DoubleArray(n) }; val s = Array(12) { DoubleArray(n) }
        // Atlas months are orbital bins; initialYearFraction belongs to monthAt(), not
        // to the forcing of each bin (otherwise a nonzero start season is counted twice).
        val sunlight = PlanetarySunlight(w, h, solar.copy(initialYearFraction = 0.0), northUp = false)
        calculateTemperatures(g, sunlight, t)
        var residual = 0.0; var moistureResidual = 0.0; var totalSweeps = 0
        for (m in 0..11) {
            diagnoseWinds(g, t[m], e[m], s[m])
            val solve = diagnoseRainfall(g, t[m], e[m], s[m], p[m], humidity[m], m)
            residual = max(residual, solve.changeMm)
            moistureResidual = max(moistureResidual, solve.residualMmPerDay)
            totalSweeps += solve.sweeps
        }
        val overlap = g.overlap
        if (overlap == null) {
            val pet = Array(12) { DoubleArray(n) }
            for (m in 0..11) {
                sunlight.update((m + 0.5) * solar.yearSeconds / 12)
                for (i in 0 until n) {
                    val light = sunlight.dailyMeanInputFactor(i / w)
                    pet[m][i] = potentialEvaporationMmPerDay(t[m][i], light) * 365 / 12
                }
            }
            return SeasonalClimateAtlas(g, solar, t, p, pet, humidity, e, s,
                p.sumOf { it.sum() } / n, residual, totalSweeps, moistureResidual)
        }
        val tileCount = g.tileElevation.size
        val tileT = Array(12) { DoubleArray(tileCount) }; val tileP = Array(12) { DoubleArray(tileCount) }
        val tilePet = Array(12) { DoubleArray(tileCount) }; val tileE = Array(12) { DoubleArray(tileCount) }; val tileS = Array(12) { DoubleArray(tileCount) }

        val tileHumidity = Array(12) { DoubleArray(tileCount) }
        val weight = DoubleArray(tileCount); val scratch = DoubleArray(n)
        for (m in 0..11) {
            sunlight.update((m + 0.5) * solar.yearSeconds / 12)
            overlap.prolongUniform(humidity[m], tileHumidity[m])
            overlap.prolongUniform(e[m], tileE[m]); overlap.prolongUniform(s[m], tileS[m])
            // Bilinear reconstruction within each coarse cell, then conservative overlap normalization.
            // This preserves regional water exactly while blending adjacent-cell boundaries.
            for (i in 0 until tileCount) {
                val x = i % g.world.width; val y = i / g.world.width
                val px = (x + 0.5) * w / g.world.width - 0.5; val py = (y + 0.5) * h / g.world.height - 0.5
                tileT[m][i] = sample(t[m], w, h, px, py) - TEMPERATURE_LAPSE_C_PER_M * (g.tileElevation[i] - sample(g.elevationMeters, w, h, px, py))
                val slopeX = (g.tileElevation[y * g.world.width + (x + 1) % g.world.width] - g.tileElevation[y * g.world.width + (x + g.world.width - 1) % g.world.width]) / (2 * g.config.domainWidthMeters / g.world.width)
                val slopeY = (g.tileElevation[min(g.world.height - 1, y + 1) * g.world.width + x] - g.tileElevation[max(0, y - 1) * g.world.width + x]) / (2 * g.config.domainHeightMeters / g.world.height)
                val parent = overlap.targetOfSource[i]
                weight[i] = (sample(p[m], w, h, px, py) / max(0.001, p[m][parent])).coerceIn(0.5, 2.0) * (1 + 12 * (tileE[m][i] * slopeX + tileS[m][i] * slopeY)).coerceIn(0.5, 1.8)
                val light = sunlight.dailyMeanInputFactor((y.toDouble() * h / g.world.height).toInt().coerceIn(0, h - 1))
                tilePet[m][i] = potentialEvaporationMmPerDay(tileT[m][i], light) * 365 / 12
            }
            overlap.prolongWeighted(p[m], weight, tileP[m], scratch)
        }
        return SeasonalClimateAtlas(g, solar, tileT, tileP, tilePet, tileHumidity, tileE, tileS, p.sumOf { it.sum() } / n, residual, totalSweeps, moistureResidual)
    }
    internal data class MoistureSolve(val sweeps: Int, val changeMm: Double, val residualMmPerDay: Double,
        val moisture: DoubleArray, val capacity: DoubleArray, val landEvaporation: DoubleArray)

    /** Steady advection with weak unresolved mixing. Humidity is a diagnostic availability,
     * not a historical atmospheric reservoir. Every sink removes humidity before it can
     * reach the lee; land evaporation is limited by rainfall and evaporative demand. */
    internal fun diagnoseRainfall(g: ClimateGeography, temperature: DoubleArray, east: DoubleArray,
        south: DoubleArray, rain: DoubleArray, humidity: DoubleArray, month: Int = 0): MoistureSolve {
        val n = g.count; val w = g.width; val h = g.height
        val q = DoubleArray(n)
        val neighbors = IntArray(n * 4); val incomingFlux = DoubleArray(n * 4)
        val oceanSource = DoubleArray(n)
        val capacity = DoubleArray(n); val condensationFraction = DoubleArray(n)
        val liftedFraction = DoubleArray(n); val landEvaporation = DoubleArray(n)
        val diffusionX = MOISTURE_DIFFUSIVITY_M2_S * 86400 / (g.config.dx * g.config.dx)
        val diffusionY = MOISTURE_DIFFUSIVITY_M2_S * 86400 / (g.config.dy * g.config.dy)
        val denominator = DoubleArray(n)
        val hasOcean = g.landFraction.any { it < 1 - 1e-12 }
        val sunlight = PlanetarySunlight(1, h, solar.copy(initialYearFraction = 0.0), northUp = false)
        sunlight.update((month + 0.5) * solar.yearSeconds / 12)
        val dailySolar = DoubleArray(h) { SOLAR_FLUX_W_M2 * sunlight.dailyMeanInputFactor(it) }
        for (i in 0 until n) {
            val x = i % w; val y = i / w
            val fraction = g.landFraction[i].coerceIn(0.0, 1.0)
            val land = when { fraction < 1e-12 -> 0.0; fraction > 1 - 1e-12 -> 1.0; else -> fraction }
            val scale = max(abs(east[i]) / g.config.dx, abs(south[i]) / g.config.dy)
            val px = x - if (scale > 0) east[i] / g.config.dx / scale else 0.0
            val py = (y - if (scale > 0) south[i] / g.config.dy / scale else 0.0).coerceIn(0.0, h - 1.0)
            val ix = floor(px).toInt(); val iy = floor(py).toInt()
            val fx = px - ix; val fy = py - iy
            val left = ((ix % w) + w) % w; val right = (left + 1) % w
            val top = iy.coerceIn(0, h - 1); val bottom = (iy + 1).coerceIn(0, h - 1)
            val offset = i * 4
            neighbors[offset] = y * w + (x + w - 1) % w
            neighbors[offset + 1] = y * w + (x + 1) % w
            neighbors[offset + 2] = max(0, y - 1) * w + x
            neighbors[offset + 3] = min(h - 1, y + 1) * w + x
            val upwindHeight = (g.elevationMeters[top * w + left] * (1 - fx) + g.elevationMeters[top * w + right] * fx) * (1 - fy) +
                (g.elevationMeters[bottom * w + left] * (1 - fx) + g.elevationMeters[bottom * w + right] * fx) * fy
            val terrainAscent = max(0.0, g.elevationMeters[i] - upwindHeight) * scale
            // Conservative transport already concentrates vapor in convergent flow.
            // Lifted condensation here diagnoses terrain and unresolved thermal motion.
            capacity[i] = saturationColumnMm(temperature[i])
            val liftHeight = liftingHeightMeters(terrainAscent, dailySolar[y] * (0.72 - 0.04 * land))
            condensationFraction[i] = liftingCondensationFraction(temperature[i], liftHeight)
            liftedFraction[i] = liftHeight / VAPOR_DEPTH_M
            val oceanExchange = (1 - land) * 1.2
            oceanSource[i] = oceanExchange * 0.8 * capacity[i]
            landEvaporation[i] = potentialEvaporationMmPerDay(temperature[i], dailySolar[y] / SOLAR_FLUX_W_M2)
            // Only an initial guess, not an atmospheric inventory or a moisture source.
            q[i] = if (hasOcean) 0.8 * capacity[i] else 0.0
            denominator[i] = oceanExchange
        }
        // Each face is assembled once. Its upwind advective flux and diffusive flux
        // leave one cell and enter the other, including the periodic longitude seam.
        // No north/south boundary faces are assembled: those boundaries are closed.
        var maximumAdvection = 0.0
        for (i in 0 until n) {
            val eastNeighbor = neighbors[i * 4 + 1]
            val u = (east[i] + east[eastNeighbor]) * 0.5 * 86400 / g.config.dx
            maximumAdvection = max(maximumAdvection, abs(u))
            incomingFlux[i * 4 + 1] = diffusionX + max(0.0, -u)
            incomingFlux[eastNeighbor * 4] = diffusionX + max(0.0, u)
            denominator[i] += diffusionX + max(0.0, u)
            denominator[eastNeighbor] += diffusionX + max(0.0, -u)
            if (i / w + 1 < h) {
                val j = i + w
                val v = (south[i] + south[j]) * 0.5 * 86400 / g.config.dy
                maximumAdvection = max(maximumAdvection, abs(v))
                incomingFlux[i * 4 + 3] = diffusionY + max(0.0, -v)
                incomingFlux[j * 4 + 2] = diffusionY + max(0.0, v)
                denominator[i] += diffusionY + max(0.0, v)
                denominator[j] += diffusionY + max(0.0, -v)
            }
        }
        // Over-relax diffusion-dominated grids; strong upwind transport uses plain sweeps.
        val relaxation = if (diffusionX + diffusionY >= maximumAdvection) 1.5 else 1.0
        fun supply(i: Int): Double {
            var incoming = oceanSource[i]
            val offset = i * 4
            for (j in 0..3) incoming += incomingFlux[offset + j] * q[neighbors[offset + j]]
            return incoming
        }
        fun equationResidual(result: DoubleArray): Double {
            var maximum = 0.0
            for (i in 0 until n) {
                val precipitation = CONDENSATION_PER_DAY * condensateMm(q[i], capacity[i], condensationFraction[i], liftedFraction[i])
                val evaporation = g.landFraction[i] * actualEvaporationMmPerDay(precipitation, landEvaporation[i])
                result[i] = supply(i) - denominator[i] * q[i] - precipitation + evaporation
                maximum = max(maximum, abs(result[i]))
            }
            return maximum
        }
        val anchor = DoubleArray(n); val plain = DoubleArray(n)
        val anchorResidual = DoubleArray(n); val currentResidual = DoubleArray(n)
        var used = 0; var change = Double.POSITIVE_INFINITY; var residual = Double.POSITIVE_INFINITY
        for (sweep in 0 until sweeps) {
            if (sweep % 4 == 0) { q.copyInto(anchor); equationResidual(anchorResidual) }
            change = 0.0
            for (index in 0 until n) {
                val i = if (sweep % 2 == 0) index else n - 1 - index
                val incoming = supply(i)
                val guess = q[i].coerceIn(0.0, incoming / denominator[i])
                val cap = capacity[i]; val fraction = condensationFraction[i]
                val exposed = liftedFraction[i]
                val precipitation = CONDENSATION_PER_DAY * condensateMm(guess, cap, fraction, exposed)
                val demand = landEvaporation[i]
                val evaporation = g.landFraction[i] * actualEvaporationMmPerDay(precipitation, demand)
                val deficit = cap * (1 - fraction) + (2 * fraction - 1) * guess
                val rainDerivative = if (guess >= cap) CONDENSATION_PER_DAY else
                    CONDENSATION_PER_DAY * exposed * fraction * fraction * guess *
                        (2 * cap * (1 - fraction) + (2 * fraction - 1) * guess) / (deficit * deficit)
                val evaporativeResponse = if (precipitation < demand) 1.0 else 0.0
                val derivative = denominator[i] + rainDerivative * (1 - g.landFraction[i] * evaporativeResponse)
                val error = denominator[i] * guess + precipitation - evaporation - incoming
                val root = (guess - error / derivative).coerceIn(0.0, incoming / denominator[i])
                val next = max(0.0, q[i] + relaxation * (root - q[i]))
                change = max(change, abs(next - q[i])); q[i] = next
            }
            used++
            if (used % 4 == 0 || used == sweeps) {
                residual = equationResidual(currentResidual)
                // Residual-minimizing extrapolation accelerates slow sheltered circulation.
                // Accept it only when the actual nonlinear residual improves and q stays positive.
                var dot = 0.0; var norm = 0.0; var plainNorm = 0.0
                var positiveLimit = 8.0
                for (i in 0 until n) {
                    val difference = anchorResidual[i] - currentResidual[i]
                    dot += anchorResidual[i] * difference; norm += difference * difference
                    plainNorm += currentResidual[i] * currentResidual[i]
                    val delta = q[i] - anchor[i]
                    if (delta < 0) positiveLimit = min(positiveLimit, -anchor[i] / delta)
                }
                val factor = if (norm > 1e-20) (dot / norm).coerceIn(1.0, max(1.0, positiveLimit)) else 1.0
                if (factor > 1.01) {
                    q.copyInto(plain)
                    for (i in 0 until n) q[i] = max(0.0, anchor[i] + factor * (plain[i] - anchor[i]))
                    val candidateResidual = equationResidual(currentResidual)
                    var candidateNorm = 0.0
                    for (value in currentResidual) candidateNorm += value * value
                    if (candidateNorm < plainNorm && candidateResidual <= residual) {
                        residual = candidateResidual
                        for (i in 0 until n) change = max(change, abs(q[i] - plain[i]))
                    } else plain.copyInto(q)
                }
                if (change < HUMIDITY_TOLERANCE_MM && residual < moistureToleranceMmPerDay) break
            }
        }
        for (i in 0 until n) {
            rain[i] = CONDENSATION_PER_DAY * condensateMm(q[i], capacity[i], condensationFraction[i], liftedFraction[i]) * 365 / 12
            humidity[i] = (q[i] / capacity[i]).coerceIn(0.0, 1.0)
            landEvaporation[i] = g.landFraction[i] * actualEvaporationMmPerDay(rain[i] * 12 / 365, landEvaporation[i])
        }
        return MoistureSolve(used, change, residual, q, capacity, landEvaporation)
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
                temperature[month][i] = target + (boundaryTemperature - target) * meanMemory - TEMPERATURE_LAPSE_C_PER_M * g.elevationMeters[i]
                boundaryTemperature = target + (boundaryTemperature - target) * decay
            }
        }
    }

    /** Diagnostic warm-low pressure tendency, balanced by rotation and linear drag.
     * Map V points south: r u + f v = ax, -f u + r v = ay. Drag keeps the
     * equatorial limit finite; there is no 1/f division or prescribed circulation belt. */
    internal fun diagnoseWinds(g: ClimateGeography, temperature: DoubleArray, east: DoubleArray, south: DoubleArray) {
        val drag = 1 / (WIND_DRAG_DAYS * 86400)
        // Compare temperatures at a common reference height. Surface lapse cooling
        // must not turn every mountain range into a permanent cold pressure source.
        fun thermal(tile: Int) = temperature[tile] + TEMPERATURE_LAPSE_C_PER_M * g.elevationMeters[tile]
        for (i in 0 until g.count) {
            if (windOverride != null) { east[i] = windOverride; south[i] = 0.0; continue }
            val x = i % g.width; val y = i / g.width
            val ax = THERMAL_PRESSURE_COUPLING * (thermal(y * g.width + (x + 1) % g.width) - thermal(y * g.width + (x + g.width - 1) % g.width)) / (2 * g.config.dx)
            val ay = THERMAL_PRESSURE_COUPLING * (thermal(min(g.height - 1, y + 1) * g.width + x) - thermal(max(0, y - 1) * g.width + x)) / (2 * g.config.dy)
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

    internal fun potentialEvaporationMmPerDay(temperatureC: Double, sunlight: Double): Double =
        (max(0.0, temperatureC + 5) * 0.18 * sunlight / 0.32).coerceIn(0.0, 8.0)

    /** Steady root-zone balance: rainfall meets evaporative demand before draining.
     * This is a surface return flux, not a fraction of locally recycled precipitation. */
    internal fun actualEvaporationMmPerDay(precipitation: Double, demand: Double): Double =
        min(precipitation, demand)

    /** Saturated vapor density integrated over a fixed effective vapor-column depth.
     * Unlike the former exponential clamp, this stays continuous in cold and hot climates. */
    internal fun saturationColumnMm(temperatureC: Double): Double {
        val vaporPressurePa = 611.2 * exp(17.67 * temperatureC / (temperatureC + 243.5))
        return VAPOR_DEPTH_M * vaporPressurePa / (461.5 * (temperatureC + 273.15))
    }

    /** Mean thermal mixing depth follows the available daytime sensible heat against
     * dry-static stability. Terrain ascent uses a parcel's one-hour lifetime. */
    internal fun liftingHeightMeters(ascentMetersPerSecond: Double, absorbedSolarWattsPerM2: Double): Double {
        val heatingEnergy = SENSIBLE_HEAT_FRACTION * absorbedSolarWattsPerM2 * solar.daySeconds
        val thermalDepth = sqrt(2 * heatingEnergy / (1.2 * 1005 * (DRY_LAPSE_C_PER_M - TEMPERATURE_LAPSE_C_PER_M)))
        val ascent = max(thermalDepth, ascentMetersPerSecond * PARCEL_ASCENT_SECONDS)
        return MIXING_HEIGHT_M * -expm1(-ascent / MIXING_HEIGHT_M)
    }

    internal fun liftingCondensationFraction(temperatureC: Double, heightMeters: Double): Double {
        val saturationSlope = 17.67 * 243.5 / (temperatureC + 243.5).pow(2) - 1 / (temperatureC + 273.15)
        val coolingRatio = saturationSlope * DRY_LAPSE_C_PER_M * heightMeters
        return coolingRatio / (1 + coolingRatio)
    }

    /** The mixed-layer share samples lifted parcels; the remaining column condenses
     * only when supersaturated. A rational lifted response matches the exponential-lift
     * saturation limit and has a quadratic inverse. Dry, unlifted air cannot rain. */
    internal fun condensateMm(moisture: Double, capacity: Double, fraction: Double, liftedFraction: Double): Double {
        return when {
            moisture >= capacity -> moisture - capacity * (1 - liftedFraction * fraction)
            fraction > 0 -> liftedFraction * fraction * fraction * moisture * moisture / (capacity * (1 - fraction) + (2 * fraction - 1) * moisture)
            else -> 0.0
        }
    }

    internal companion object {
        const val TEMPERATURE_LAPSE_C_PER_M = 0.006
        private const val DRY_LAPSE_C_PER_M = 0.0098
        private const val VAPOR_DEPTH_M = 4000.0
        private const val MIXING_HEIGHT_M = 1000.0
        private const val PARCEL_ASCENT_SECONDS = 3600.0
        private const val SENSIBLE_HEAT_FRACTION = 0.1
        const val SOLAR_FLUX_W_M2 = 1360.0
        const val OUTGOING_AT_ZERO_W_M2 = 180.0
        const val OUTGOING_SLOPE_W_M2_K = 4.0
        const val LAND_RESPONSE_DAYS = 40.0
        const val OCEAN_RESPONSE_DAYS = 240.0
        // Pressure acceleration per kelvin/m: m² s⁻² K⁻¹, an effective shallow thermal column.
        const val THERMAL_PRESSURE_COUPLING = 25.0
        const val WIND_DRAG_DAYS = 1.0
        const val MAX_WIND_M_S = 12.0
        const val CONDENSATION_PER_DAY = 4.0 // Six-hour bulk cloud adjustment.
        // Effective large-scale eddy mixing, shared across land and ocean (m²/s).
        const val MOISTURE_DIFFUSIVITY_M2_S = 1_000_000.0
        const val HUMIDITY_TOLERANCE_MM = 0.002
        const val MOISTURE_TOLERANCE_MM_DAY = 0.01
    }

    private fun sample(a: DoubleArray, w: Int, h: Int, x: Double, y: Double): Double {
        val ix = floor(x).toInt(); val iy = floor(y).toInt(); val fx = x - ix; val fy = y - iy
        fun at(dx: Int, dy: Int) = a[(iy + dy).coerceIn(0, h - 1) * w + ((ix + dx) % w + w) % w]
        return (at(0, 0) * (1 - fx) + at(1, 0) * fx) * (1 - fy) + (at(0, 1) * (1 - fx) + at(1, 1) * fx) * fy
    }
}
