package terra.weather

import terra.atmosphere.AtmosphereTileMapping
import terra.atmosphere.AtmosphereTerrain
import terra.atmosphere.MoistSurface
import terra.hydrology.HydrologySimulation
import terra.world.World
import terra.world.Terrain
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Fine soil/ocean reservoirs and exchange arrays. Lakes remain owned by hydrology. */
internal class TerrainMoistSurface(
    private val world: World,
    val mapping: AtmosphereTileMapping,
    private val hydrology: HydrologySimulation
) : MoistSurface {
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
    private val terrain = AtmosphereTerrain(mapping, DoubleArray(count) { tile ->
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
    private val stepRain = DoubleArray(count)
    private var transferred = false

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

    override fun receiveRain(cell: Int, amountMm: Double) {
        mapping.forCell(cell) { i, fraction ->
            val amount = amountMm * fraction
            rain[i] += amount
            stepRain[i] += amount
            observedRain[i] += amount
            when {
                wetLake[i] -> lakeRain[i] += amount
                land[i] -> {
                    soil[i] += amount
                    val excess = max(0.0, soil[i] - 250.0)
                    soil[i] -= excess
                    overflow[i] += excess
                }
                else -> hydrology.receiveOceanWaterMmTiles(amount)
            }
        }
    }

    fun beginUpdate() {
        rain.fill(0.0); drainage.fill(0.0); overflow.fill(0.0); lakeRain.fill(0.0); peakRainRate.fill(0.0)
        transferred = false
    }

    fun finishStep(seconds: Double) {
        val drainResponse = 1 - exp(-seconds / (5 * 86400.0))
        val climateResponse = 1 - exp(-seconds / (30 * 86400.0))
        for (i in 0 until count) {
            val drained = max(0.0, soil[i] - 120.0) * drainResponse
            soil[i] -= drained
            drainage[i] += drained
            rainRate[i] = stepRain[i] * 3600 / seconds
            peakRainRate[i] = max(peakRainRate[i], rainRate[i])
            climateRainRate[i] += (rainRate[i] - climateRainRate[i]) * climateResponse
        }
        stepRain.fill(0.0)
    }

    fun updateLakeMask(mask: BooleanArray) { wetLake = mask.copyOf() }

    override fun waterMm(cell: Int): Double {
        var amount = 0.0
        mapping.forCell(cell) { i, f -> amount += (soil[i] + if (land[i]) 0.0 else hydrology.meanOceanDepthMm) * f }
        return amount / mapping.tilesPerCell
    }

    override val totalWaterKg: Double
        get() = (soil.sum() + (if (transferred) 0.0 else drainage.sum() + overflow.sum() + lakeRain.sum()) +
            hydrology.storedAndPendingWaterMmTiles) * mapping.tileAreaM2
    override val exportedWaterKg: Double get() = 0.0 // River outlets are internal ocean returns.

    /** Once hydrology has accepted these arrays it owns their water, including its pending interval. */
    fun transferredToHydrology() { transferred = true }
}
