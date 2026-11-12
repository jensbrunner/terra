package terra.weather

import terra.atmosphere.AtmosphereTileMapping
import terra.atmosphere.AtmosphereTerrain
import terra.atmosphere.MoistSurface
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyExchange
import terra.hydrology.WaterFluxBatch
import terra.world.World
import terra.world.Terrain
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/** Fine soil/ocean reservoirs and exchange arrays. Lakes remain owned by hydrology. */
internal class TerrainMoistSurface(
    private val world: World,
    val mapping: AtmosphereTileMapping,
    private val hydrology: HydrologySimulation
) : MoistSurface, HydrologyExchange {
    private val width = world.width
    private val count = width * world.height
    private val land = BooleanArray(count) { hydrology.isLandTile(it % width, it / width) }
    val soil = DoubleArray(count) { if (land[it]) 120.0 else 0.0 }
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
    private val wetFractionWeightedRain = DoubleArray(count)
    private val queuedSoilRain = DoubleArray(count)
    private var climateRainQueued = false
    private val stepRain = DoubleArray(count)
    private var transferred = true
    private var updateRevision = 0L
    private var updateDurationSeconds = 0.0
    var pendingFluxBatch: WaterFluxBatch? = null
        private set
    val soilWaterMmTiles: Double get() = soil.sum()
    val unacceptedWaterMmTiles: Double get() = if (transferred) 0.0 else drainage.sum() + overflow.sum() + lakeRain.sum() + queuedSoilRain.sum()

    /** Read the initial physical stores without coupling weather initialization to soil constants. */
    fun initialWaterAvailability(cell: Int): Double {
        var available = 1 - landFraction[cell]
        mapping.forCell(cell) { tile, fraction ->
            available += (soil[tile] / SOIL_CAPACITY_MM).coerceIn(0.0, 1.0) * fraction / mapping.tilesPerCell
        }
        return available.coerceIn(0.0, 1.0)
    }

    override fun heatCapacityJm2K(cell: Int) = landFraction[cell] * 2_000_000.0 + (1 - landFraction[cell]) * 41_800_000.0
    override fun albedo(cell: Int) = landFraction[cell] * 0.24 + (1 - landFraction[cell]) * 0.08

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
                val multiplier = when (world[tile % width, tile / width].terrain) {
                    Terrain.OCEAN -> 1.0
                    Terrain.DESERT, Terrain.TUNDRA -> 1.5
                    Terrain.STEPPE -> 1.8
                    Terrain.PLAINS -> 2.0
                    Terrain.WOODS -> 4.0
                    Terrain.MOUNTAINS -> 6.0
                }
                roughness += multiplier * fraction
            }
            dragMultiplier[cell] = roughness / mapping.tilesPerCell
        }
        roughnessRevision = world.biomeRevision
    }

    override fun evaporate(cell: Int, potentialMm: Double): Double {
        check(!transferred && pendingFluxBatch == null)
        require(potentialMm.isFinite() && potentialMm >= 0.0)
        var actualMmTiles = 0.0
        mapping.forCell(cell) { i, fraction ->
            val request = potentialMm * fraction
            val actual = when {
                wetLake[i] -> hydrology.evaporateLakeWaterMmTiles(i % width, i / width, request)
                land[i] -> min(soil[i], request).also { soil[i] -= it }
                else -> hydrology.evaporateOceanWaterMmTiles(request)
            }
            actualMmTiles += actual
        }
        return actualMmTiles / mapping.tilesPerCell
    }

    override fun receiveRain(cell: Int, amountMm: Double) = recordRain(cell, amountMm, continuous = false)

    /** Source-funded rainfall held for constant-forcing bucket integration, never duplicated in soil. */
    fun receiveClimateRain(cell: Int, amountMm: Double, wetFraction: Double = 1.0) = recordRain(cell, amountMm, continuous = true, wetFraction = wetFraction)

    private fun recordRain(cell: Int, amountMm: Double, continuous: Boolean, wetFraction: Double = 1.0) {
        check(!transferred && pendingFluxBatch == null)
        require(amountMm.isFinite() && amountMm >= 0.0)
        require(wetFraction.isFinite() && wetFraction > 0.0 && wetFraction <= 1.0)
        check(continuous || !climateRainQueued)
        if (continuous) climateRainQueued = true
        mapping.forCell(cell) { i, fraction ->
            val amount = amountMm * fraction
            if (continuous) wetFractionWeightedRain[i] += amount * wetFraction
            rain[i] += amount
            stepRain[i] += amount
            observedRain[i] += amount
            when {
                wetLake[i] -> lakeRain[i] += amount
                land[i] && continuous -> queuedSoilRain[i] += amount
                land[i] -> {
                    soil[i] += amount
                    val excess = max(0.0, soil[i] - SOIL_CAPACITY_MM)
                    soil[i] -= excess
                    overflow[i] += excess
                }
                else -> hydrology.receiveOceanWaterMmTiles(amount)
            }
        }
    }

    fun beginUpdate() {
        check(transferred) { "Accept the pending surface water before starting another update" }
        pendingFluxBatch = null
        updateRevision++
        updateDurationSeconds = 0.0
        rainWetSeconds.fill(0.0); wetFractionWeightedRain.fill(0.0)
        rain.fill(0.0); drainage.fill(0.0); overflow.fill(0.0); lakeRain.fill(0.0); peakRainRate.fill(0.0)
        transferred = false
    }

    fun finishStep(seconds: Double) {
        check(!transferred && pendingFluxBatch == null && !climateRainQueued)
        require(seconds.isFinite() && seconds > 0.0)
        val drainResponse = 1 - exp(-seconds / DRAINAGE_SECONDS)
        val climateResponse = 1 - exp(-seconds / (30 * 86400.0))
        for (i in 0 until count) {
            val drained = max(0.0, soil[i] - FIELD_CAPACITY_MM) * drainResponse
            soil[i] -= drained
            drainage[i] += drained
            rainRate[i] = stepRain[i] * 3600 / seconds
            peakRainRate[i] = max(peakRainRate[i], rainRate[i])
            climateRainRate[i] += (rainRate[i] - climateRainRate[i]) * climateResponse
        }
        stepRain.fill(0.0)
        updateDurationSeconds += seconds
    }

    /** Exact rain-plus-drainage solution for each constant-forcing phase, including saturation time.
     * Evaporation is an ordered finite withdrawal before this rain phase, not an annual net clamp.
     */
    fun finishClimateStep(seconds: Double) {
        check(!transferred && pendingFluxBatch == null)
        require(seconds.isFinite() && seconds > 0.0)
        val climateResponse = -expm1(-seconds / (30 * 86400.0))
        for (i in 0 until count) {
            val fraction = if (stepRain[i] > 0.0) (wetFractionWeightedRain[i] / stepRain[i]).coerceIn(1e-6, 1.0) else 1.0
            val wetSeconds = seconds * fraction
            integrateRainBucket(i, queuedSoilRain[i], wetSeconds)
            if (fraction < 1.0) integrateRainBucket(i, 0.0, seconds - wetSeconds)
            queuedSoilRain[i] = 0.0
            rainRate[i] = stepRain[i] * 3600 / seconds
            rainWetSeconds[i] = if (stepRain[i] > 0.0) wetSeconds else 0.0
            peakRainRate[i] = max(peakRainRate[i], stepRain[i] * 3600 / wetSeconds)
            climateRainRate[i] += (rainRate[i] - climateRainRate[i]) * climateResponse
        }
        climateRainQueued = false
        stepRain.fill(0.0)
        updateDurationSeconds += seconds
    }

    private fun integrateRainBucket(tile: Int, amount: Double, seconds: Double) {
        if (!land[tile]) { check(amount == 0.0); return }
        val rate = amount / seconds
        var store = soil[tile]
        var activeSeconds = seconds
        if (store < FIELD_CAPACITY_MM) {
            if (amount <= FIELD_CAPACITY_MM - store) { soil[tile] = store + amount; return }
            val belowFieldSeconds = (FIELD_CAPACITY_MM - store) / rate
            activeSeconds -= belowFieldSeconds
            store = FIELD_CAPACITY_MM
        }
        val excess = store - FIELD_CAPACITY_MM
        val capacity = SOIL_CAPACITY_MM - FIELD_CAPACITY_MM
        val equilibrium = rate * DRAINAGE_SECONDS
        val response = -expm1(-activeSeconds / DRAINAGE_SECONDS)
        val endExcess = excess + (equilibrium - excess) * response
        if (equilibrium > capacity && endExcess > capacity) {
            val saturationSeconds = DRAINAGE_SECONDS * ln1p((capacity - excess) / (equilibrium - capacity))
            val saturatedSeconds = max(0.0, activeSeconds - saturationSeconds)
            val spilled = max(0.0, (rate - capacity / DRAINAGE_SECONDS) * saturatedSeconds)
            // Mass remaining after the known final store and surface overflow is drainage.
            drainage[tile] += max(0.0, soil[tile] + amount - SOIL_CAPACITY_MM - spilled)
            overflow[tile] += spilled
            soil[tile] = SOIL_CAPACITY_MM
        } else {
            val x = activeSeconds / DRAINAGE_SECONDS
            // Stable x - (1-exp(-x)) avoids losing small positive drainage at short durations.
            val integratedResponse = if (x < 1e-4) x * x * (0.5 - x / 6 + x * x / 24 - x * x * x / 120)
                else x + expm1(-x)
            val drained = max(0.0, equilibrium * integratedResponse + excess * response)
            soil[tile] = (soil[tile] + amount - drained).coerceIn(0.0, SOIL_CAPACITY_MM)
            drainage[tile] += drained
        }
    }

    fun sealFluxBatch(seconds: Double): WaterFluxBatch {
        check(!transferred && pendingFluxBatch == null && !climateRainQueued)
        require(seconds.isFinite() && seconds > 0.0 &&
            kotlin.math.abs(seconds - updateDurationSeconds) <= 1e-9 * max(1.0, seconds)) {
            "Flux duration must match the completed surface interval"
        }
        val revision = updateRevision
        return WaterFluxBatch.borrowed(width, world.height, seconds, this,
            { drainage[it] + overflow[it] }, { lakeRain[it] }, { 0.0 },
            { updateRevision == revision && !transferred }).also { pendingFluxBatch = it }
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
        get() = (soilWaterMmTiles + unacceptedWaterMmTiles +
            hydrology.storedAndPendingWaterMmTiles) * mapping.tileAreaM2
    override val exportedWaterKg: Double get() = 0.0 // River outlets are internal ocean returns.

    /** Once hydrology has accepted these arrays it owns their water, including its pending interval. */
    fun transferredToHydrology() { check(!transferred); transferred = true }

    private companion object {
        const val SOIL_CAPACITY_MM = 250.0
        const val FIELD_CAPACITY_MM = 120.0
        const val DRAINAGE_SECONDS = 5 * 86400.0
    }
}
