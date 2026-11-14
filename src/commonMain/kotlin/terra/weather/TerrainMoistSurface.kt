package terra.weather

import terra.atmosphere.*
import terra.hydrology.SurfaceWater
import terra.world.World
import terra.world.Terrain
import kotlin.math.*

/** Shared soil, snow and slow drainage stores. Ocean exchange is a signed boundary
 * ledger; inland water is finite and included in every conservation diagnostic.
 */
internal class TerrainMoistSurface(private val world: World, val mapping: AtmosphereTileMapping) : MoistSurface {
    private val width = world.width
    private val height = world.height
    private val count = width * height
    private val land = BooleanArray(count) { world.isLand(it % width, it / width) }
    internal var surfaceWater = SurfaceWater(world)
        private set
    internal val climateDrought = DoubleArray(count)
    val soil = DoubleArray(count) { if (land[it]) 120.0 else 0.0 }
    private val snow = DoubleArray(count)
    private val queuedRain = DoubleArray(count)
    private val stepRain = DoubleArray(count)
    private val weightedWet = DoubleArray(count)
    private var boundaryMmTiles = 0.0
    private var boundaryRoundoff = 0.0
    private fun export(amount: Double) {
        val corrected = amount - boundaryRoundoff
        val next = boundaryMmTiles + corrected
        boundaryRoundoff = (next - boundaryMmTiles) - corrected
        boundaryMmTiles = next
    }
    internal val seaIceFraction = DoubleArray(mapping.config.cellCount)
    val landFraction = DoubleArray(mapping.config.cellCount) { c ->
        var amount = 0.0
        mapping.forCell(c) { i, f -> if (land[i]) amount += f }
        amount / mapping.tilesPerCell
    }
    var terrain = AtmosphereTerrain(mapping, DoubleArray(count) {
        max(0.0, world[it % width, it / width].elevation - world.seaLevel) * 10_000.0
    })
    private val infiltrationScale = DoubleArray(count) { tile ->
        val x = tile % width; val y = tile / width
        val slope = abs(world[(x + 1) % width, y].elevation - world[(x + width - 1) % width, y].elevation) +
            abs(world[x, min(height - 1, y + 1)].elevation - world[x, max(0, y - 1)].elevation)
        (1 - 0.65 * (slope / 0.05).coerceIn(0.0, 1.0)).coerceIn(0.25, 1.0)
    }
    val rain = DoubleArray(count)
    val drainage = DoubleArray(count)
    val overflow = DoubleArray(count)
    val peakRainRate = DoubleArray(count)
    val climateRainRate = DoubleArray(count)
    private val temperatureC = DoubleArray(mapping.config.cellCount) { 10.0 }
    private val drag = DoubleArray(mapping.config.cellCount)
    private var roughnessRevision = -1L
    val soilWaterMmTiles get() = soil.sum()
    val snowWaterMmTiles get() = snow.sum()
    fun initialWaterAvailability(cell: Int) = (1 - landFraction[cell] + rootZoneMm(cell) / 250.0).coerceIn(0.0, 1.0)
    fun setCellTemperature(cell: Int, celsius: Double) { temperatureC[cell] = celsius }

    /** Atlas forcing is an external water flux. Only soil/snow/runoff are accounted here. */
    internal fun evolveMonth(atlas: SeasonalClimateAtlas, month: Int, fraction: Double): Double {
        var evaporated = 0.0
        val days = fraction * 365 / 12
        val drainageResponse = -expm1(-days / 5)
        for (i in 0 until count) {
            val amount = atlas.rain[month][i] * fraction
            rain[i] += amount
            if (!land[i]) continue
            val temperature = atlas.temperature[month][i]
            val frozen = ((1 - temperature) / 2).coerceIn(0.0, 1.0)
            snow[i] += amount * frozen
            val melt = min(snow[i], max(0.0, temperature) * 3.2 * days)
            snow[i] -= melt
            val input = amount * (1 - frozen) + melt
            val demand = atlas.pet[month][i] * fraction
            if (surfaceWater.isLakeWet(i)) {
                surfaceWater.receiveLakeRain(i, input)
                evaporated += surfaceWater.evaporateLake(i, demand)
                continue
            }
            val available = soil[i] + input
            val actual = min(available, demand)
            evaporated += actual
            val afterEt = available - actual
            val spill = max(0.0, afterEt - 250)
            val drain = max(0.0, min(250.0, afterEt) - 120) * drainageResponse
            soil[i] = afterEt - spill - drain
            drainage[i] += drain; overflow[i] += spill
            surfaceWater.receive(i, drain, spill)
        }
        export(surfaceWater.advance(days * 86400))
        return evaporated * mapping.tileAreaM2
    }

    internal fun refreshGeometry() {
        val replacement = SurfaceWater(world)
        export(replacement.migrateFrom(surfaceWater))
        surfaceWater = replacement
        terrain = AtmosphereTerrain(mapping, DoubleArray(count) { world.elevationMeters(it % width, it / width) })
        for (i in 0 until count) {
            val next = world.isLand(i % width, i / width)
            if (land[i] != next) {
                // Conversion resets terrestrial stores; surviving land retains its inventories.
                soil[i] = 0.0; snow[i] = 0.0; queuedRain[i] = 0.0; climateDrought[i] = 0.0
                land[i] = next
            }
        }
        for (i in infiltrationScale.indices) {
            val x = i % width; val y = i / width
            val slope = abs(world[x + 1, y].elevation - world[x - 1, y].elevation) +
                abs(world[x, min(height - 1, y + 1)].elevation - world[x, max(0, y - 1)].elevation)
            infiltrationScale[i] = (1 - 0.65 * (slope / 0.05).coerceIn(0.0, 1.0)).coerceIn(0.25, 1.0)
        }
        for (c in landFraction.indices) {
            var sum = 0.0
            mapping.forCell(c) { i, f -> if (land[i]) sum += f }
            landFraction[c] = sum / mapping.tilesPerCell
        }
    }

    private fun tileAlbedo(tile: Int, cell: Int): Double {
        if (!land[tile]) return 0.07 * (1 - seaIceFraction[cell]) + 0.40 * seaIceFraction[cell]
        val cover = world.treeCover(tile % width, tile / width)
        val grass = world.grassCover(tile % width, tile / width)
        val bare = (0.25 - 0.075 * cover - 0.04 * grass - 0.055 * (soil[tile] / 250).coerceIn(0.0, 1.0)).coerceIn(0.12, 0.36)
        val ice = (snow[tile] / 25.0).coerceIn(0.0, 1.0)
        return bare * (1 - ice) + 0.45 * ice
    }
    private fun tileCapacity(tile: Int) = if (land[tile]) 3.2e6 else 1.6e8
    override fun heatCapacityJm2K(cell: Int): Double {
        if (landFraction[cell] == 0.0) return 1.6e8
        var sum = 0.0
        mapping.forCell(cell) { i, f -> sum += tileCapacity(i) * f }
        return sum / mapping.tilesPerCell
    }
    override fun albedo(cell: Int): Double {
        if (landFraction[cell] == 0.0) return 0.07 * (1 - seaIceFraction[cell]) + 0.40 * seaIceFraction[cell]
        var sum = 0.0
        mapping.forCell(cell) { i, f -> sum += tileAlbedo(i, cell) * f }
        return sum / mapping.tilesPerCell
    }
    fun fillThermalProperties(albedo: DoubleArray, capacity: DoubleArray) {
        for (c in albedo.indices) {
            if (landFraction[c] == 0.0) {
                albedo[c] = 0.07 * (1 - seaIceFraction[c]) + 0.40 * seaIceFraction[c]
                capacity[c] = 1.6e8
                continue
            }
            var a = 0.0; var heat = 0.0
            mapping.forCell(c) { i, f -> a += tileAlbedo(i, c) * f; heat += tileCapacity(i) * f }
            albedo[c] = a / mapping.tilesPerCell; capacity[c] = heat / mapping.tilesPerCell
        }
    }
    fun snowWaterMm(cell: Int): Double {
        if (landFraction[cell] == 0.0) return 0.0
        var sum = 0.0
        mapping.forCell(cell) { i, f -> sum += snow[i] * f }
        return sum / mapping.tilesPerCell
    }
    fun rootZoneMm(cell: Int): Double {
        if (landFraction[cell] == 0.0) return 0.0
        var sum = 0.0
        mapping.forCell(cell) { i, f -> sum += soil[i] * f }
        return sum / mapping.tilesPerCell
    }
    override fun orographicUpliftMetersPerSecond(cell: Int, eastWind: Double, northWind: Double) =
        terrain.upliftMetersPerSecond(cell, eastWind, northWind)
    override fun momentumDragMultiplier(cell: Int): Double {
        if (roughnessRevision != world.biomeRevision) {
            for (c in drag.indices) {
                var sum = 0.0
                mapping.forCell(c) { i, f ->
                    val x = i % width; val y = i / width
                    val base = if (world[x, y].terrain == Terrain.MOUNTAINS) 5.0 else 1.0
                    sum += base * (1 + 1.4 * world.treeCover(x, y) + 0.7 * world.grassCover(x, y)) * f
                }
                drag[c] = sum / mapping.tilesPerCell
            }
            roughnessRevision = world.biomeRevision
        }
        return drag[cell]
    }
    override fun evaporate(cell: Int, potentialMm: Double): Double {
        require(potentialMm.isFinite() && potentialMm >= 0)
        if (potentialMm == 0.0) return 0.0
        if (landFraction[cell] == 0.0) {
            export(-potentialMm * mapping.tilesPerCell)
            return potentialMm
        }
        var actual = 0.0
        mapping.forCell(cell) { i, f ->
            val demand = potentialMm * f
            if (!land[i]) { actual += demand; export(-demand) }
            else if (surfaceWater.isLakeWet(i)) actual += surfaceWater.evaporateLake(i, demand)
            else {
                val affordable = min(demand, soil[i] * relaxationFraction(demand / 120.0))
                soil[i] -= affordable; actual += affordable
            }
        }
        return actual / mapping.tilesPerCell
    }
    override fun receiveRain(cell: Int, amountMm: Double, snowFraction: Double) = recordRain(cell, amountMm, 1.0, snowFraction)
    private fun recordRain(cell: Int, amountMm: Double, wet: Double, frozen: Double) {
        require(amountMm.isFinite() && amountMm >= 0 && wet > 0 && wet <= 1 && frozen in 0.0..1.0)
        mapping.forCell(cell) { i, f ->
            val amount = amountMm * f
            rain[i] += amount; stepRain[i] += amount; weightedWet[i] += amount * wet
            if (land[i]) {
                snow[i] += amount * frozen
                if (surfaceWater.isLakeWet(i)) surfaceWater.receiveLakeRain(i, amount * (1 - frozen))
                else queuedRain[i] += amount * (1 - frozen)
            }
            else export(amount)
        }
    }
    fun meltSnowEnergyMm(cell: Int, energyJoules: Double): Double {
        var melted = 0.0
        val available = snowWaterMm(cell)
        if (available <= 0 || energyJoules <= 0) return 0.0
        val fraction = min(1.0, energyJoules / (334000.0 * available))
        mapping.forCell(cell) { i, f ->
            val amount = snow[i] * fraction * f
            snow[i] -= amount; queuedRain[i] += amount; melted += amount
        }
        return melted / mapping.tilesPerCell
    }
    fun beginUpdate() {
        rain.fill(0.0); drainage.fill(0.0); overflow.fill(0.0); peakRainRate.fill(0.0)
    }
    fun finishStep(seconds: Double) {
        require(seconds.isFinite() && seconds > 0)
        val response = relaxationFraction(seconds / (30 * 86400.0))
        val decay = exp(-seconds / DRAINAGE_SECONDS)
        for (i in 0 until count) {
            if (snow[i] > 0) {
                val c = mapping.cellOfTile[i]
                val melt = min(snow[i], max(0.0, temperatureC[c]) * 3.2 * seconds / 86400)
                snow[i] -= melt; queuedRain[i] += melt
            }
            val wet = if (stepRain[i] > 0) (weightedWet[i] / stepRain[i]).coerceIn(1e-6, 1.0) else 1.0
            val previousOverflow = overflow[i]
            val drained = if (land[i]) integrateRainBucket(i, queuedRain[i], seconds, wet * seconds, decay) else 0.0
            drainage[i] += drained
            surfaceWater.receive(i, drained, overflow[i] - previousOverflow)
            queuedRain[i] = 0.0
            val rate = stepRain[i] * 3600 / seconds
            val peak = rate / wet
            peakRainRate[i] = max(peakRainRate[i], peak)
            climateRainRate[i] += (rate - climateRainRate[i]) * response

        }
        export(surfaceWater.advance(seconds))
        stepRain.fill(0.0); weightedWet.fill(0.0)
    }

    /**
     * Exact constant-rate root-zone solution. Rain above the infiltration capacity runs
     * off; the remainder fills the root zone, drains toward field capacity on the
     * drainage timescale and leaves as runoff. Every branch is derived from
     * the mass balance, so no rounding path can create water.
     */
    private fun integrateRainBucket(tile: Int, amount: Double, seconds: Double,
        wetSeconds: Double = seconds, drainageDecay: Double = kotlin.math.exp(-seconds / DRAINAGE_SECONDS)): Double {
        if (!land[tile]) { check(amount == 0.0); return 0.0 }
        if (seconds <= 0.0 || amount < 0.0) return 0.0
        if (amount == 0.0 && soil[tile] <= FIELD_CAPACITY_MM) return 0.0
        val capacity = INFILTRATION_MM_PER_HOUR * wetSeconds.coerceIn(0.0, seconds) / 3600.0 *
            infiltrationScale[tile]
        val infiltrated = min(amount, capacity)
        overflow[tile] += amount - infiltrated
        var store = soil[tile]
        var rate = infiltrated / seconds
        var remainingSeconds = seconds
        if (store < FIELD_CAPACITY_MM) {
            if (rate <= 0.0) return 0.0
            val timeToField = (FIELD_CAPACITY_MM - store) / rate
            if (timeToField >= remainingSeconds) {
                soil[tile] = store + infiltrated
                return 0.0
            }
            val used = rate * timeToField
            store = FIELD_CAPACITY_MM
            remainingSeconds -= timeToField
            rate = (infiltrated - used) / remainingSeconds
        }
        val tau = DRAINAGE_SECONDS
        val equilibrium = rate * tau
        val excess = max(0.0, store - FIELD_CAPACITY_MM)
        val capExcess = SOIL_CAPACITY_MM - FIELD_CAPACITY_MM
        val input = rate * remainingSeconds
        if (equilibrium > capExcess) {
            val ratio = (capExcess - equilibrium) / (excess - equilibrium)
            if (ratio > 0.0 && ratio < 1.0) {
                val timeToSaturation = -tau * kotlin.math.ln(ratio)
                if (timeToSaturation < remainingSeconds) {
                    val before = equilibrium + (excess - equilibrium) *
                        kotlin.math.exp(-timeToSaturation / tau)
                    val storedBefore = FIELD_CAPACITY_MM + min(capExcess, before)
                    val drainageBefore = store + rate * timeToSaturation - storedBefore
                    val afterSeconds = remainingSeconds - timeToSaturation
                    val spillRate = max(0.0, rate - capExcess / tau)
                    val drainRate = capExcess / tau
                    overflow[tile] += spillRate * afterSeconds
                    soil[tile] = SOIL_CAPACITY_MM
                    return max(0.0, drainageBefore) + drainRate * afterSeconds
                }
            }
        }
        val endExcess = equilibrium + (excess - equilibrium) *
            (if (remainingSeconds == seconds) drainageDecay else kotlin.math.exp(-remainingSeconds / tau))
        val endStore = FIELD_CAPACITY_MM + min(capExcess, max(0.0, endExcess))
        val spill = max(0.0, endExcess - capExcess)
        soil[tile] = endStore
        overflow[tile] += spill
        return max(0.0, store + input - endStore - spill)
    }


    override fun waterMm(cell: Int) = rootZoneMm(cell) + snowWaterMm(cell)
    override val totalWaterKg get() = (soil.sum() + snow.sum() + queuedRain.sum() + surfaceWater.totalWaterMmTiles) * mapping.tileAreaM2
    override val exportedWaterKg get() = boundaryMmTiles * mapping.tileAreaM2
    private companion object {
        const val SOIL_CAPACITY_MM = 250.0
        const val FIELD_CAPACITY_MM = 120.0
        const val DRAINAGE_SECONDS = 5 * 86400.0
        const val INFILTRATION_MM_PER_HOUR = 10.0
    }
}

private fun relaxationFraction(x: Double) = -kotlin.math.expm1(-x)
