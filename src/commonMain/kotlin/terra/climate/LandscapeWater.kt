package terra.climate

import terra.hydrology.SurfaceWater
import terra.hydrology.SoilWaterBalance
import terra.hydrology.SoilWaterBalance.Companion.DRAINAGE_SECONDS
import terra.hydrology.SoilWaterBalance.Companion.FIELD_CAPACITY_FRACTION
import terra.world.World
import kotlin.math.*

/** Finite soil, snow and drainage stores fed by the atmospheric columns. */
internal class LandscapeWater(world: World) {
    private val width = world.width
    private val height = world.height
    private val count = width * height
    private val land = BooleanArray(count) { world.isLand(it % width, it / width) }
    internal val surfaceWater = SurfaceWater(world)
    internal val growingWaterAvailability = DoubleArray(count)
    val soil = DoubleArray(count)
    private val soilBalance = SoilWaterBalance()
    private val snow = DoubleArray(count)
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
    internal val actualEvaporationMm = DoubleArray(count)
    init {
        for (i in 0 until count) if (land[i]) {
            soil[i] = 0.3 * soilCapacityMm[i]
            growingWaterAvailability[i] = 0.3
        }
    }
    /** Apply the rain and evaporation produced by the live atmospheric columns. */
    internal fun advance(rainMm: DoubleArray, evaporationMm: DoubleArray, temperatureK: DoubleArray, seconds: Double): DoubleArray {
        val days = seconds / 86400.0
        val response = -expm1(-days / 30.0)
        val drainageDecay = exp(-seconds / DRAINAGE_SECONDS)
        for (i in 0 until count) {
            val amount = rainMm[i]
            rain[i] += amount
            if (!land[i]) continue
            val temperature = temperatureK[i] - 273.15
            val frozen = ((1.0 - temperature / 2.0) / 2.0).coerceIn(0.0, 1.0)
            snow[i] += amount * frozen
            val melt = min(snow[i], max(0.0, temperature) * 3.2 * days)
            snow[i] -= melt
            val input = amount * (1 - frozen) + melt
            val demand = evaporationMm[i]
            if (surfaceWater.isLakeWet(i)) {
                surfaceWater.receiveLakeRain(i, input)
                val actual = surfaceWater.evaporateLake(i, demand)
                actualEvaporationMm[i] = actual
                growingWaterAvailability[i] = (1 - response) * growingWaterAvailability[i] + response * (actual / max(1e-9, demand)).coerceIn(0.0, 1.0)
                continue
            }
            val actual = evolveBucket(i, input, demand, seconds, drainageDecay)
            actualEvaporationMm[i] = actual
            val availability = 0.6 * (actual / max(1e-9, demand)).coerceIn(0.0, 1.0) + 0.4 * (soil[i] / (soilCapacityMm[i] * FIELD_CAPACITY_FRACTION)).coerceIn(0.0, 1.0)
            growingWaterAvailability[i] = (1 - response) * growingWaterAvailability[i] + response * availability
        }
        surfaceWater.advance(seconds)
        return actualEvaporationMm
    }

    private fun evolveBucket(tile: Int, input: Double, demand: Double, seconds: Double, drainageDecay: Double): Double {
        val infiltrated = SoilWaterBalance.infiltratedMm(input, seconds, infiltrationScale[tile])
        val rootAccess = surfaceWater.supplyRoots(tile, min(max(0.0, demand - soil[tile] - infiltrated), 0.5 * seconds / 86400.0))
        soilBalance.advance(soil[tile], soilCapacityMm[tile], input, seconds, infiltrationScale[tile],
            potentialEvaporationMm = demand, rootSupplyMm = rootAccess, drainageDecay = drainageDecay)
        soil[tile] = soilBalance.soilMm
        drainage[tile] += soilBalance.drainageMm
        overflow[tile] += soilBalance.runoffMm
        surfaceWater.receive(tile, soilBalance.drainageMm, soilBalance.runoffMm)
        return soilBalance.evaporationMm
    }

    fun beginUpdate() { rain.fill(0.0); drainage.fill(0.0); overflow.fill(0.0); actualEvaporationMm.fill(0.0) }
    fun snowWaterMm(tile: Int) = snow[tile]
}
