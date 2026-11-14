package terra.weather

import terra.atmosphere.*
import terra.hydrology.SurfaceWater
import terra.hydrology.SoilWaterBalance
import terra.hydrology.SoilWaterBalance.Companion.DRAINAGE_SECONDS
import terra.hydrology.SoilWaterBalance.Companion.FIELD_CAPACITY_FRACTION
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
    internal val growingWaterAvailability = DoubleArray(count)
    internal val climateDrought = DoubleArray(count)
    val soil = DoubleArray(count)
    private val soilBalance = SoilWaterBalance()
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
    /** Fixed substrate/rooting depth: coherent geology texture and slope, independent of cover. */
    val soilCapacityMm = DoubleArray(count) { i ->
        val texture = 0.5 + 0.5 * sin((i % width) * 0.11 + world.seed) * cos((i / width) * 0.09 - world.seed)
        if (land[i]) (160 + 90 * texture) * (0.6 + 0.4 * infiltrationScale[i]) else 1.0
    }
    val rain = DoubleArray(count)
    val drainage = DoubleArray(count)
    val overflow = DoubleArray(count)
    val peakRainRate = DoubleArray(count)
    val climateRainRate = DoubleArray(count)
    private val temperatureC = DoubleArray(mapping.config.cellCount) { 10.0 }
    private val drag = DoubleArray(mapping.config.cellCount)
    private var roughnessRevision = -1L
    fun setCellTemperature(cell: Int, celsius: Double) { temperatureC[cell] = celsius }

    /** Four deterministic atlas years charge soil, snow, groundwater, channels and lakes
     * from climate forcing, starting dry. Then advance to the requested orbital season.
     * These warmup fluxes define time-zero inventories, not elapsed world time; clearing
     * their boundary ledger and update totals keeps them out of live diagnostics. */
    internal fun initializeClimate(atlas: SeasonalClimateAtlas) {
        soil.fill(0.0)
        repeat(HYDROLOGY_SPINUP_YEARS) { for (month in 0..11) evolveMonth(atlas, month, 1.0) }
        val orbitalMonth = (atlas.solar.initialYearFraction % 1.0) * 12
        val month = orbitalMonth.toInt()
        for (m in 0 until month) evolveMonth(atlas, m, 1.0)
        if (orbitalMonth > month) evolveMonth(atlas, month, orbitalMonth - month)
        boundaryMmTiles = 0.0; boundaryRoundoff = 0.0
        beginUpdate(); climateRainRate.fill(0.0)
    }

    /** Atlas forcing is an external water flux. Rain is concentrated into a deterministic
     * wet tenth of the interval; melt and PET span both wet and dry periods. This lets
     * saturation/infiltration excess escape before later dry-period evaporation. */
    internal fun evolveMonth(atlas: SeasonalClimateAtlas, month: Int, fraction: Double): Double {
        var evaporated = 0.0
        val days = fraction * 365 / 12
        val growthMemory = -expm1(-fraction / 6)
        val droughtMemory = -expm1(-fraction / 24)
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
                val actual = surfaceWater.evaporateLake(i, demand)
                evaporated += actual
                if (temperature > 5 && demand > 0) {
                    growingWaterAvailability[i] += (actual / demand - growingWaterAvailability[i]) * growthMemory
                    climateDrought[i] += (1 - actual / demand - climateDrought[i]) * droughtMemory
                }
                continue
            }
            val wet = CLIMATE_WET_FRACTION
            val wetActual = evolveClimateBucket(i, amount * (1 - frozen) + melt * wet, demand * wet, days * wet)
            val actual = wetActual + evolveClimateBucket(i, melt * (1 - wet), demand * (1 - wet), days * (1 - wet))
            evaporated += actual
            if (temperature > 5 && demand > 0) {
                val availability = 0.6 * actual / demand + 0.4 * (soil[i] / (soilCapacityMm[i] * FIELD_CAPACITY_FRACTION)).coerceIn(0.0, 1.0)
                growingWaterAvailability[i] += (availability - growingWaterAvailability[i]) * growthMemory
                climateDrought[i] += (1 - availability - climateDrought[i]) * droughtMemory
            }
        }
        export(surfaceWater.advance(days * 86400))
        return evaporated * mapping.tileAreaM2
    }

    /** Climate mode adds potential ET and finite root access to the shared balance. */
    private fun evolveClimateBucket(tile: Int, input: Double, demand: Double, days: Double): Double {
        val seconds = days * 86400
        val infiltrated = SoilWaterBalance.infiltratedMm(input, seconds, infiltrationScale[tile])
        val rootAccess = surfaceWater.supplyRoots(tile, min(max(0.0, demand - soil[tile] - infiltrated), 0.5 * days))
        return advanceSoil(tile, input, seconds, potentialEvaporationMm = demand, rootSupplyMm = rootAccess)
    }

    /** Both forcing modes publish identical soil, drainage and runoff accounting. */
    private fun advanceSoil(tile: Int, input: Double, seconds: Double, wetSeconds: Double = seconds,
        potentialEvaporationMm: Double = 0.0, rootSupplyMm: Double = 0.0,
        drainageDecay: Double = exp(-seconds / DRAINAGE_SECONDS)): Double {
        soilBalance.advance(soil[tile], soilCapacityMm[tile], input, seconds, infiltrationScale[tile],
            wetSeconds, potentialEvaporationMm, rootSupplyMm, drainageDecay)
        soil[tile] = soilBalance.soilMm
        drainage[tile] += soilBalance.drainageMm
        overflow[tile] += soilBalance.runoffMm
        surfaceWater.receive(tile, soilBalance.drainageMm, soilBalance.runoffMm)
        return soilBalance.evaporationMm
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
                soil[i] = 0.0; snow[i] = 0.0; queuedRain[i] = 0.0; climateDrought[i] = 0.0; growingWaterAvailability[i] = 0.0
                land[i] = next
            }
        }
        for (i in infiltrationScale.indices) {
            val x = i % width; val y = i / width
            val slope = abs(world[x + 1, y].elevation - world[x - 1, y].elevation) +
                abs(world[x, min(height - 1, y + 1)].elevation - world[x, max(0, y - 1)].elevation)
            infiltrationScale[i] = (1 - 0.65 * (slope / 0.05).coerceIn(0.0, 1.0)).coerceIn(0.25, 1.0)
            val texture = 0.5 + 0.5 * sin(x * 0.11 + world.seed) * cos(y * 0.09 - world.seed)
            soilCapacityMm[i] = if (land[i]) (160 + 90 * texture) * (0.6 + 0.4 * infiltrationScale[i]) else 1.0
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
        val bare = (0.25 - 0.075 * cover - 0.04 * grass - 0.055 * (soil[tile] / soilCapacityMm[tile]).coerceIn(0.0, 1.0)).coerceIn(0.12, 0.36)
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
                val affordable = min(demand, soil[i] * relaxationFraction(demand / (soilCapacityMm[i] * FIELD_CAPACITY_FRACTION)))
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
    fun beginUpdate() {
        rain.fill(0.0); drainage.fill(0.0); overflow.fill(0.0); peakRainRate.fill(0.0)
    }
    fun finishStep(seconds: Double) {
        require(seconds.isFinite() && seconds > 0)
        val response = relaxationFraction(seconds / (30 * 86400.0))
        val decay = exp(-seconds / DRAINAGE_SECONDS)
        val growthMemory = -expm1(-seconds / (180 * 86400.0))
        val droughtMemory = -expm1(-seconds / (2 * 365 * 86400.0))
        for (i in 0 until count) {
            if (snow[i] > 0) {
                val c = mapping.cellOfTile[i]
                val melt = min(snow[i], max(0.0, temperatureC[c]) * 3.2 * seconds / 86400)
                snow[i] -= melt; queuedRain[i] += melt
            }
            val wet = if (stepRain[i] > 0) (weightedWet[i] / stepRain[i]).coerceIn(1e-6, 1.0) else 1.0
            if (land[i]) advanceSoil(i, queuedRain[i], seconds, wet * seconds, drainageDecay = decay)
            queuedRain[i] = 0.0
            val rate = stepRain[i] * 3600 / seconds
            val peak = rate / wet
            peakRainRate[i] = max(peakRainRate[i], peak)
            climateRainRate[i] += (rate - climateRainRate[i]) * response
            if (land[i] && temperatureC[mapping.cellOfTile[i]] > 5) {
                val availability = (soil[i] / (soilCapacityMm[i] * FIELD_CAPACITY_FRACTION)).coerceIn(0.0, 1.0)
                growingWaterAvailability[i] += (availability - growingWaterAvailability[i]) * growthMemory
                climateDrought[i] += (1 - availability - climateDrought[i]) * droughtMemory
            }

        }
        export(surfaceWater.advance(seconds))
        stepRain.fill(0.0); weightedWet.fill(0.0)
    }

    override fun waterMm(cell: Int) = rootZoneMm(cell) + snowWaterMm(cell)
    override val totalWaterKg get() = (soil.sum() + snow.sum() + queuedRain.sum() + surfaceWater.totalWaterMmTiles) * mapping.tileAreaM2
    override val exportedWaterKg get() = boundaryMmTiles * mapping.tileAreaM2
    private companion object {
        const val HYDROLOGY_SPINUP_YEARS = 4
        const val CLIMATE_WET_FRACTION = 0.1
    }
}

private fun relaxationFraction(x: Double) = -kotlin.math.expm1(-x)
