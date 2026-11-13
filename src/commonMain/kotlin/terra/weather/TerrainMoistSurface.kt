package terra.weather

import terra.atmosphere.AtmosphereTileMapping
import terra.atmosphere.AtmosphereTerrain
import terra.atmosphere.MoistSurface
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyExchange
import terra.hydrology.WaterFluxBatch
import terra.world.World
import terra.world.Terrain
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * Fine land reservoirs and surface exchange. Ownership:
 *
 *  - root zone (`soil`) is the fast plant-accessible store;
 *  - `groundwater` is a slow baseflow store released by a linear recession;
 *  - `snow` is frozen water with its own albedo and delayed melt;
 *  - lakes, channels and the finite ocean remain owned by [HydrologySimulation].
 *
 * No climate-cell array duplicates any of these inventories. The reduced atmosphere
 * only ever requests exchange against the authoritative tile stores.
 */
internal class TerrainMoistSurface(
    private val world: World,
    val mapping: AtmosphereTileMapping,
    private val hydrology: HydrologySimulation
) : MoistSurface, HydrologyExchange {
    private val width = world.width
    private val height = world.height
    private val count = width * height
    internal val worldForClimate: World get() = world
    internal val worldSeedForClimate: Int get() = world.seed
    private val land = BooleanArray(count) { hydrology.isLandTile(it % width, it / width) }
    val responseUnits = HydrologicalResponseUnits(world, hydrology)
    val soil = DoubleArray(count) { if (land[it]) INITIAL_ROOT_ZONE_MM else 0.0 }
    private val unitGroundwater = DoubleArray(responseUnits.count)
    private val unitSnow = DoubleArray(responseUnits.count)
    private val unitPercolation = DoubleArray(responseUnits.count)
    private val snow = DoubleArray(count)
    private val cellSnowMm = DoubleArray(mapping.config.cellCount)
    private var wetLake = BooleanArray(count)
    val landFraction = DoubleArray(mapping.config.cellCount) { c ->
        var amount = 0.0
        mapping.forCell(c) { i, f -> if (land[i]) amount += f }
        amount / mapping.tilesPerCell
    }
    // An atmospheric relief scale: 0.3 normalized elevation above sea is 3 km.
    // This does not alter the existing game-scale hydrological basin capacities.
    val terrain = AtmosphereTerrain(mapping, DoubleArray(count) { tile ->
        max(0.0, world[tile % width, tile / width].elevation - world.seaLevel) * 10_000.0
    })
    private val infiltrationScale = DoubleArray(count) { tile ->
        val x = tile % width; val y = tile / width
        val east = world[(x + 1) % width, y].elevation
        val west = world[(x + width - 1) % width, y].elevation
        val north = world[x, maxOf(0, y - 1)].elevation
        val south = world[x, minOf(height - 1, y + 1)].elevation
        val slope = (abs(east - west) + abs(south - north)).coerceAtLeast(0.0)
        (1.0 - 0.65 * (slope / 0.05).coerceIn(0.0, 1.0)).coerceIn(0.25, 1.0)
    }
    private val dragMultiplier = DoubleArray(mapping.config.cellCount)
    private var roughnessRevision = -1L
    val rain = DoubleArray(count)
    val drainage = DoubleArray(count)
    val overflow = DoubleArray(count)
    val lakeRain = DoubleArray(count)
    val rainRate = DoubleArray(count)
    val peakRainRate = DoubleArray(count)
    val climateRainRate = DoubleArray(count)
    val observedRain = DoubleArray(count)
    val rainWetSeconds = DoubleArray(count)
    val snowMelt = DoubleArray(count)
    private val wetFractionWeightedRain = DoubleArray(count)
    private val queuedSoilRain = DoubleArray(count)
    private var climateRainQueued = false
    private val stepRain = DoubleArray(count)
    private val cellTemperatureC = DoubleArray(mapping.config.cellCount) { 10.0 }
    private val activeTiles = IntArray(count)
    private val activeFlag = BooleanArray(count)
    private var activeTileCount = 0
    private fun markActive(tile: Int) {
        if (!activeFlag[tile]) { activeFlag[tile] = true; activeTiles[activeTileCount++] = tile }
    }
    private var transferred = true
    private var updateRevision = 0L
    private var updateDurationSeconds = 0.0
    var pendingFluxBatch: WaterFluxBatch? = null
        private set
    val soilWaterMmTiles: Double get() = soil.sum()
    val groundwaterMmTiles: Double get() = unitGroundwater.sum()
    val unitGroundwaterMmTiles: Double get() = unitGroundwater.sum()
    val snowWaterMmTiles: Double get() = snow.sum()
    val unitSnowWaterMmTiles: Double get() = unitSnow.sum()
    val hasSnow: Boolean get() = unitSnow.any { it > 0.0 }
    fun snowMmInCell(cell: Int): Double = cellSnowMm[cell]
    val unacceptedWaterMmTiles: Double
        get() = if (transferred) 0.0 else drainage.sum() + overflow.sum() + lakeRain.sum() + queuedSoilRain.sum()

    init {
        for (unit in 0 until responseUnits.count) {
            unitGroundwater[unit] = INITIAL_GROUNDWATER_MM * responseUnits.tileCountPerUnit[unit]
        }
    }

    /** Read the initial physical stores without coupling weather initialization to soil constants. */
    fun initialWaterAvailability(cell: Int): Double {
        var available = 1 - landFraction[cell]
        mapping.forCell(cell) { tile, fraction ->
            val unit = responseUnits.unitOfTile[tile]
            val groundwaterShare = if (unit >= 0) unitGroundwater[unit] / responseUnits.tileCountPerUnit[unit] else 0.0
            available += ((soil[tile] + groundwaterShare * GROUNDWATER_ACCESS) /
                (SOIL_CAPACITY_MM + INITIAL_GROUNDWATER_MM)) * fraction / mapping.tilesPerCell
        }
        return available.coerceIn(0.0, 1.0)
    }

    /** Owner-only temperature estimate used for snow accumulation and melt, °C. */
    fun setCellTemperature(cell: Int, celsius: Double) {
        require(cell in cellTemperatureC.indices && celsius.isFinite())
        cellTemperatureC[cell] = celsius
    }

    override fun heatCapacityJm2K(cell: Int): Double {
        var capacity = (1 - landFraction[cell]) * OCEAN_MIXED_LAYER_HEAT_CAPACITY
        mapping.forCell(cell) { tile, fraction ->
            val water = soil[tile] * WATER_HEAT_CAPACITY_PER_MM
            capacity += (LAND_HEAT_CAPACITY + min(water, MAX_SOIL_HEAT_CAPACITY)) * fraction / mapping.tilesPerCell
        }
        return capacity
    }

    override fun albedo(cell: Int): Double {
        var sum = 0.0
        mapping.forCell(cell) { tile, fraction ->
            if (!land[tile]) {
                sum += OCEAN_ALBEDO * fraction
            } else {
                val cover = world.treeCover(tile % width, tile / width)
                val grass = world.grassCover(tile % width, tile / width)
                val wetness = (soil[tile] / SOIL_CAPACITY_MM).coerceIn(0.0, 1.0)
                val snowFraction = (snow[tile] / SNOW_ALBEDO_DEPTH_MM).coerceIn(0.0, 1.0)
                val landAlbedo = (LAND_ALBEDO - 0.075 * cover - 0.04 * grass - 0.055 * wetness)
                    .coerceIn(0.12, 0.36)
                sum += (landAlbedo * (1 - snowFraction) + SNOW_ALBEDO * snowFraction) * fraction
            }
        }
        return (sum / mapping.tilesPerCell).coerceIn(0.05, 0.82)
    }

    /** Snow-covered area fraction in a climate cell, 0..1. */
    fun snowCover(cell: Int): Double {
        var covered = 0.0
        mapping.forCell(cell) { tile, fraction ->
            if (snow[tile] > SNOW_ALBEDO_DEPTH_MM * 0.15) covered += fraction
        }
        return (covered / mapping.tilesPerCell).coerceIn(0.0, 1.0)
    }

    /** Energy-limited snowmelt; returns cell-average melt in mm and conserves snow/soil. */
    fun meltSnowEnergyMm(cell: Int, energyJoules: Double): Double {
        require(energyJoules.isFinite() && energyJoules >= 0.0)
        if (energyJoules == 0.0) return 0.0
        var remaining = energyJoules
        var meltedTiles = 0.0
        mapping.forCell(cell) { tile, fraction ->
            if (snow[tile] > 0.0) {
                val availableMeltMm = remaining / (LATENT_HEAT_FUSION_J_PER_KG * 1.0)
                val melt = min(snow[tile], availableMeltMm)
                snow[tile] -= melt
                val unit = responseUnits.unitOfTile[tile]
                if (unit >= 0) unitSnow[unit] = (unitSnow[unit] - melt).coerceAtLeast(0.0)
                cellSnowMm[mapping.cellOfTile[tile]] -= melt
                remaining -= melt * LATENT_HEAT_FUSION_J_PER_KG
                if (wetLake[tile]) {
                    // Snow that fell on a dry basin and later became wet melts into the
                    // lake inventory rather than an inaccessible soil pocket.
                    lakeRain[tile] += melt
                } else if (land[tile]) {
                    soil[tile] += melt
                    val excess = max(0.0, soil[tile] - SOIL_CAPACITY_MM)
                    soil[tile] -= excess
                    overflow[tile] += excess
                } else {
                    overflow[tile] += melt
                }
                meltedTiles += melt * fraction
            }
        }
        return meltedTiles / mapping.tilesPerCell
    }

    fun snowWaterMm(cell: Int): Double {
        var amount = 0.0
        mapping.forCell(cell) { tile, fraction -> amount += snow[tile] * fraction }
        return amount / mapping.tilesPerCell
    }

    fun groundwaterMm(cell: Int): Double {
        var amount = 0.0
        mapping.forCell(cell) { tile, fraction ->
            val unit = responseUnits.unitOfTile[tile]
            if (unit >= 0) amount += (unitGroundwater[unit] / responseUnits.tileCountPerUnit[unit]) * fraction
        }
        return amount / mapping.tilesPerCell
    }

    fun rootZoneMm(cell: Int): Double {
        var amount = 0.0
        mapping.forCell(cell) { tile, fraction -> amount += soil[tile] * fraction }
        return amount / mapping.tilesPerCell
    }

    override fun orographicUpliftMetersPerSecond(cell: Int, eastWind: Double, northWind: Double): Double =
        terrain.upliftMetersPerSecond(cell, eastWind, northWind)

    override fun momentumDragMultiplier(cell: Int): Double {
        if (roughnessRevision != world.biomeRevision) refreshRoughness()
        return dragMultiplier[cell]
    }

    private fun refreshRoughness() {
        for (cell in dragMultiplier.indices) {
            var roughness = 0.0
            mapping.forCell(cell) { tile, fraction ->
                val cover = world.treeCover(tile % width, tile / width)
                val grass = world.grassCover(tile % width, tile / width)
                val base = when (world[tile % width, tile / width].terrain) {
                    Terrain.OCEAN -> 1.0
                    Terrain.MOUNTAINS -> 5.0
                    else -> 1.0
                }
                val vegetation = 1.0 + 1.4 * cover + 0.7 * grass
                roughness += (base * vegetation) * fraction
            }
            dragMultiplier[cell] = roughness / mapping.tilesPerCell
        }
        roughnessRevision = world.biomeRevision
    }

    override fun evaporate(cell: Int, potentialMm: Double): Double {
        check(!transferred && pendingFluxBatch == null)
        require(potentialMm.isFinite() && potentialMm >= 0.0)
        // Open-ocean cells are pure area averages; skip the tile-overlap loop.
        if (landFraction[cell] <= 0.0) {
            return hydrology.evaporateOceanWaterMmTiles(potentialMm * mapping.tilesPerCell) / mapping.tilesPerCell
        }
        var actualMmTiles = 0.0
        var oceanRequest = 0.0
        mapping.forCell(cell) { i, fraction ->
            val request = potentialMm * fraction
            val snowLimit = 1 - (snow[i] / SNOW_ALBEDO_DEPTH_MM).coerceIn(0.0, 0.95)
            val availableRequest = request * snowLimit
            val actual = when {
                wetLake[i] -> hydrology.evaporateLakeWaterMmTiles(i % width, i / width, availableRequest)
                // Root-zone withdrawal: dry soil restricts actual ET rather than paying
                // full demand until empty. A small deep-root access to groundwater keeps
                // vegetation alive through short dry spells without erasing drought.
                land[i] -> {
                    val shallow = min(availableRequest, soil[i] * decayResponse(availableRequest / FIELD_CAPACITY_MM))
                    soil[i] -= shallow
                    val deficit = availableRequest - shallow
                    val unit = responseUnits.unitOfTile[i]
                    val share = if (unit >= 0) unitGroundwater[unit] / responseUnits.tileCountPerUnit[unit] else 0.0
                    val deep = if (deficit > 0 && share > 0)
                        min(deficit, share * GROUNDWATER_ACCESS * decayResponse(deficit / GROUNDWATER_CAPACITY_MM))
                    else 0.0
                    if (unit >= 0) unitGroundwater[unit] -= deep
                    shallow + deep
                }
                else -> { oceanRequest += availableRequest; 0.0 }
            }
            actualMmTiles += actual
        }
        actualMmTiles += hydrology.evaporateOceanWaterMmTiles(oceanRequest)
        return actualMmTiles / mapping.tilesPerCell
    }

    override fun receiveRain(cell: Int, amountMm: Double, snowFraction: Double) =
        recordRain(cell, amountMm, continuous = false, snowFraction = snowFraction)

    /** Source-funded rainfall held for constant-forcing bucket integration, never duplicated in soil. */
    fun receiveClimateRain(cell: Int, amountMm: Double, wetFraction: Double = 1.0, snowFraction: Double = 0.0) =
        recordRain(cell, amountMm, continuous = true, wetFraction = wetFraction, snowFraction = snowFraction)

    private fun recordRain(cell: Int, amountMm: Double, continuous: Boolean, wetFraction: Double = 1.0,
        snowFraction: Double = 0.0) {
        check(!transferred && pendingFluxBatch == null)
        require(amountMm.isFinite() && amountMm >= 0.0)
        require(wetFraction.isFinite() && wetFraction > 0.0 && wetFraction <= 1.0)
        require(snowFraction.isFinite() && snowFraction in 0.0..1.0)
        check(continuous || !climateRainQueued)
        if (continuous) climateRainQueued = true
        var oceanRain = 0.0
        mapping.forCell(cell) { i, fraction ->
            val amount = amountMm * fraction
            // Snow is a land reservoir. Lake and ocean precipitation stays liquid and
            // enters the hydrology-owned store directly, avoiding a duplicated inventory.
            val asSnow = if (land[i] && !wetLake[i]) amount * snowFraction else 0.0
            val liquid = amount - asSnow
            if (asSnow > 0.0) {
                snow[i] += asSnow
                val unit = responseUnits.unitOfTile[i]
                if (unit >= 0) unitSnow[unit] += asSnow
                cellSnowMm[mapping.cellOfTile[i]] += asSnow
            }
            if (continuous) wetFractionWeightedRain[i] += liquid * wetFraction
            rain[i] += amount
            stepRain[i] += amount
            observedRain[i] += amount
            when {
                wetLake[i] -> { lakeRain[i] += amount; markActive(i) }
                land[i] -> queuedSoilRain[i] += liquid
                else -> oceanRain += amount
            }
        }
        hydrology.receiveOceanWaterMmTiles(oceanRain)
    }

    fun beginUpdate() {
        check(transferred) { "Accept the pending surface water before starting another update" }
        pendingFluxBatch = null
        updateRevision++
        updateDurationSeconds = 0.0
        for (k in 0 until activeTileCount) activeFlag[activeTiles[k]] = false
        activeTileCount = 0
        rainWetSeconds.fill(0.0); wetFractionWeightedRain.fill(0.0); snowMelt.fill(0.0)
        rain.fill(0.0); drainage.fill(0.0); overflow.fill(0.0); lakeRain.fill(0.0); peakRainRate.fill(0.0)
        transferred = false
    }

    fun finishStep(seconds: Double) {
        check(!transferred && pendingFluxBatch == null && !climateRainQueued)
        require(seconds.isFinite() && seconds > 0.0)
        val climateResponse = 1 - exp(-seconds / (30 * 86400.0))
        val drainageDecay = kotlin.math.exp(-seconds / DRAINAGE_SECONDS)
        val groundwaterDecay = kotlin.math.exp(-seconds / GROUNDWATER_RECESSION_SECONDS)
        val groundwaterMidDecay = kotlin.math.exp(-seconds / (2 * GROUNDWATER_RECESSION_SECONDS))
        meltUnitSnow(seconds)
        unitPercolation.fill(0.0)
        for (i in 0 until count) {
            val percolation = integrateRainBucket(i, queuedSoilRain[i], seconds, seconds, drainageDecay)
            queuedSoilRain[i] = 0.0
            val unit = responseUnits.unitOfTile[i]
            if (unit >= 0) unitPercolation[unit] += percolation
            if (drainage[i] + overflow[i] + lakeRain[i] > 0.0) markActive(i)
            rainRate[i] = stepRain[i] * 3600 / seconds
            peakRainRate[i] = max(peakRainRate[i], rainRate[i])
            climateRainRate[i] += (rainRate[i] - climateRainRate[i]) * climateResponse
        }
        for (unit in 0 until responseUnits.count) {
            releaseUnitBaseflow(unit, unitPercolation[unit], unitPercolation[unit] * groundwaterMidDecay, groundwaterDecay)
        }
        stepRain.fill(0.0)
        updateDurationSeconds += seconds
    }

    /** Exact rain-plus-drainage solution for each constant-forcing phase, including saturation time.
     * Fast mode surrounds this rain phase with two moisture-limited half exchanges.
     */
    fun finishClimateStep(seconds: Double) {
        check(!transferred && pendingFluxBatch == null)
        require(seconds.isFinite() && seconds > 0.0)
        val climateResponse = -expm1(-seconds / (30 * 86400.0))
        val drainageDecay = kotlin.math.exp(-seconds / DRAINAGE_SECONDS)
        val groundwaterDecay = kotlin.math.exp(-seconds / GROUNDWATER_RECESSION_SECONDS)
        val groundwaterMidDecay = kotlin.math.exp(-seconds / (2 * GROUNDWATER_RECESSION_SECONDS))
        meltUnitSnow(seconds)
        unitPercolation.fill(0.0)
        for (i in 0 until count) {
            val fraction = if (stepRain[i] > 0.0) (wetFractionWeightedRain[i] / stepRain[i]).coerceIn(1e-6, 1.0) else 1.0
            val wetSeconds = seconds * fraction
            // One exact bucket update; percolation is aggregated by response unit
            // before a single analytic linear-reservoir baseflow release.
            val percolation = integrateRainBucket(i, queuedSoilRain[i], seconds, wetSeconds, drainageDecay)
            queuedSoilRain[i] = 0.0
            val unit = responseUnits.unitOfTile[i]
            if (unit >= 0) unitPercolation[unit] += percolation
            if (drainage[i] + overflow[i] + lakeRain[i] > 0.0) markActive(i)
            rainRate[i] = stepRain[i] * 3600 / seconds
            rainWetSeconds[i] = if (stepRain[i] > 0.0) wetSeconds else 0.0
            peakRainRate[i] = max(peakRainRate[i], stepRain[i] * 3600 / wetSeconds)
            climateRainRate[i] += (rainRate[i] - climateRainRate[i]) * climateResponse
        }
        for (unit in 0 until responseUnits.count) {
            releaseUnitBaseflow(unit, unitPercolation[unit], unitPercolation[unit] * groundwaterMidDecay, groundwaterDecay)
        }
        climateRainQueued = false
        stepRain.fill(0.0)
        updateDurationSeconds += seconds
    }

    /** Degree-day snowmelt aggregated by response unit; melt is distributed back to
     * member tiles proportionally to their snow, preserving snowline and mass. */
    private fun meltUnitSnow(seconds: Double) {
        for (unit in 0 until responseUnits.count) {
            val total = unitSnow[unit]
            if (total <= 0.0) continue
            val representativeCell = mapping.cellOfTile[responseUnits.outletTile[unit]]
            val temperature = cellTemperatureC[representativeCell]
            val melt = min(total, max(0.0, temperature - SNOW_MELT_THRESHOLD_C) *
                SNOW_MELT_MM_PER_DAY_C * seconds / 86400.0 * responseUnits.tileCountPerUnit[unit])
            if (melt <= 0.0) continue
            unitSnow[unit] = total - melt
            val members = responseUnits.tiles[unit]
            var distributed = 0.0
            for (index in members.indices) {
                val tile = members[index]
                val share = if (index == members.lastIndex) (melt - distributed).coerceAtLeast(0.0)
                    else if (total > 1e-12) melt * snow[tile] / total else 0.0
                if (share <= 0.0) continue
                distributed += share
                snow[tile] = (snow[tile] - share).coerceAtLeast(0.0)
                cellSnowMm[mapping.cellOfTile[tile]] -= share
                snowMelt[tile] += share
                when {
                    wetLake[tile] -> lakeRain[tile] += share
                    land[tile] -> {
                        soil[tile] += share
                        val excess = max(0.0, soil[tile] - SOIL_CAPACITY_MM)
                        soil[tile] -= excess
                        overflow[tile] += excess
                    }
                    else -> overflow[tile] += share
                }
            }
        }
    }

    private fun releaseUnitBaseflow(unit: Int, percolation: Double, weightedPercolation: Double,
        groundwaterDecay: Double) {
        val start = unitGroundwater[unit]
        if (start <= 0.0 && percolation <= 0.0) return
        val unitCount = responseUnits.tileCountPerUnit[unit]
        val cap = GROUNDWATER_CAPACITY_MM * unitCount
        val unboundedEnd = start * groundwaterDecay + weightedPercolation
        val end = min(cap, unboundedEnd)
        val released = start + percolation - end
        unitGroundwater[unit] = end
        if (released > 0.0) {
            val outlet = responseUnits.outletTile[unit]
            drainage[outlet] += released
            markActive(outlet)
        }
    }

    /**
     * Exact constant-rate root-zone solution. Rain above the infiltration capacity runs
     * off; the remainder fills the root zone, drains toward field capacity on the
     * drainage timescale and percolates to groundwater. Every branch is derived from
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

    fun sealFluxBatch(seconds: Double): WaterFluxBatch {
        check(!transferred && pendingFluxBatch == null && !climateRainQueued)
        require(seconds.isFinite() && seconds > 0.0 &&
            kotlin.math.abs(seconds - updateDurationSeconds) <= 1e-9 * max(1.0, seconds)) {
            "Flux duration must match the completed surface interval"
        }
        val revision = updateRevision
        val active = if (activeTileCount == 0) EMPTY_TILES else activeTiles.copyOf(activeTileCount)
        return WaterFluxBatch.borrowed(width, world.height, seconds, this,
            { drainage[it] + overflow[it] }, { lakeRain[it] }, { 0.0 },
            { updateRevision == revision && !transferred }, active).also { pendingFluxBatch = it }
    }

    fun updateLakeMask(mask: BooleanArray) { require(mask.size == count); wetLake = mask.copyOf() }
    override fun updateLakeWaterMask(mask: BooleanArray) = updateLakeMask(mask)
    override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double) {
        check(amountMm == 0.0) { "Coupled evaporation must be withdrawn during surface exchange" }
    }
    override fun hydrologyAccepted() = transferredToHydrology()

    override fun waterMm(cell: Int): Double {
        var amount = 0.0
        mapping.forCell(cell) { i, f -> amount += (soil[i] + if (land[i]) 0.0 else hydrology.meanOceanDepthMm) * f }
        return amount / mapping.tilesPerCell
    }

    override val totalWaterKg: Double
        get() = (soilWaterMmTiles + groundwaterMmTiles + snowWaterMmTiles + unacceptedWaterMmTiles +
            hydrology.storedAndPendingWaterMmTiles) * mapping.tileAreaM2

    override val exportedWaterKg: Double get() = 0.0 // River outlets are internal ocean returns.

    /** Once hydrology has accepted these arrays it owns their water, including its pending interval. */
    fun transferredToHydrology() { check(!transferred); transferred = true }

    private companion object {
        // exp is a JVM intrinsic; expm1 is expensive on every fine soil tile.
        // The series retains precision at detailed-weather timestep scales.
        fun decayResponse(x: Double): Double = if (x < 1e-3)
            x * (1 - x * (0.5 - x * (1.0 / 6 - x / 24))) else 1 - exp(-x)
        const val SOIL_CAPACITY_MM = 250.0
        const val FIELD_CAPACITY_MM = 120.0
        const val DRAINAGE_SECONDS = 5 * 86400.0
        const val INITIAL_ROOT_ZONE_MM = 120.0
        const val INITIAL_GROUNDWATER_MM = 150.0
        const val GROUNDWATER_CAPACITY_MM = 600.0
        const val GROUNDWATER_RECESSION_SECONDS = 140 * 86400.0
        const val GROUNDWATER_ACCESS = 0.18
        const val SNOW_ALBEDO_DEPTH_MM = 25.0
        const val SNOW_ALBEDO = 0.45
        const val SNOW_MELT_THRESHOLD_C = -1.0
        const val SNOW_MELT_MM_PER_DAY_C = 3.2
        const val LATENT_HEAT_FUSION_J_PER_KG = 334_000.0
        val EMPTY_TILES = IntArray(0)
        const val INFILTRATION_MM_PER_HOUR = 2.0
        const val OCEAN_ALBEDO = 0.07
        const val LAND_ALBEDO = 0.25
        const val OCEAN_MIXED_LAYER_HEAT_CAPACITY = 1.6e8
        const val LAND_HEAT_CAPACITY = 2.0e6
        const val WATER_HEAT_CAPACITY_PER_MM = 4.186e6
        const val MAX_SOIL_HEAT_CAPACITY = 1.2e6
    }
}
