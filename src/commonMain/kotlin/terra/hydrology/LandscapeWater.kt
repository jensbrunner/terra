package terra.hydrology

import terra.hydrology.SoilWaterBalance.Companion.DRAINAGE_SECONDS
import terra.world.World
import kotlin.math.*

/** Finite soil, snow and drainage stores fed by annual climate budgets. */
internal class LandscapeWater(world: World) {
    private val width = world.width
    private val height = world.height
    private val count = width * height
    private val land = BooleanArray(count) { world.isLand(it % width, it / width) }
    internal var surfaceWater = SurfaceWater(world)
        private set
    val soil = DoubleArray(count)
    private val soilBalance = SoilWaterBalance()
    private val snow = DoubleArray(count)
    private val infiltrationScale = DoubleArray(count) { tile ->
        val x = tile % width; val y = tile / width
        val slope = abs(world[(x + 1) % width, y].elevation - world[(x + width - 1) % width, y].elevation) +
            abs(world[x, y + 1].elevation - world[x, y - 1].elevation)
        (1 - 0.65 * (slope / 0.05).coerceIn(0.0, 1.0)).coerceIn(0.25, 1.0)
    }
    /** Fixed substrate/rooting depth: coherent geology texture and slope, independent of cover. */
    val soilCapacityMm = DoubleArray(count) { i ->
        val texture = 0.5 + 0.5 * sin((i % width) * 10 * PI / width + world.seed) * cos((i / width) * 6 * PI / height - world.seed)
        if (land[i]) (160 + 90 * texture) * (0.6 + 0.4 * infiltrationScale[i]) else 1.0
    }
    val rain = DoubleArray(count)
    val drainage = DoubleArray(count)
    val overflow = DoubleArray(count)
    init {
        for (i in 0 until count) if (land[i]) {
            soil[i] = 0.3 * soilCapacityMm[i]
        }
    }
    fun refreshGeography(world: World) {
        surfaceWater = surfaceWater.rebuild(world)
        for (i in 0 until count) {
            val x = i % width; val y = i / width
            val newLand = world.isLand(x, y)
            if (!newLand || !land[i]) { soil[i] = 0.0; snow[i] = 0.0 }
            land[i] = newLand
            val slope = abs(world[x + 1, y].elevation - world[x - 1, y].elevation) +
                abs(world[x, y + 1].elevation - world[x, y - 1].elevation)
            infiltrationScale[i] = (1 - 0.65 * (slope / 0.05).coerceIn(0.0, 1.0)).coerceIn(0.25, 1.0)
            val texture = 0.5 + 0.5 * sin(x * 10 * PI / width + world.seed) * cos(y * 6 * PI / height - world.seed)
            soilCapacityMm[i] = if (newLand) (160 + 90 * texture) * (0.6 + 0.4 * infiltrationScale[i]) else 1.0
            val excess = max(0.0, soil[i] - soilCapacityMm[i])
            soil[i] -= excess
            if (newLand) surfaceWater.receive(i, 0.0, excess)
        }
    }

    /** Apply one annual rain and evaporation budget without daily substeps. */
    internal fun advance(rainMm: DoubleArray, evaporationMm: DoubleArray, temperatureK: DoubleArray, seconds: Double) {
        val days = seconds / 86400.0
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
                surfaceWater.evaporateLake(i, demand)
                continue
            }
            evolveBucket(i, input, demand, seconds, drainageDecay)
        }
        surfaceWater.advance(seconds)
    }

    private fun evolveBucket(tile: Int, input: Double, demand: Double, seconds: Double, drainageDecay: Double) {
        val infiltrated = SoilWaterBalance.infiltratedMm(input, seconds, infiltrationScale[tile])
        val rootAccess = surfaceWater.supplyRoots(tile, min(max(0.0, demand - soil[tile] - infiltrated), 0.5 * seconds / 86400.0))
        soilBalance.advance(soil[tile], soilCapacityMm[tile], input, seconds, infiltrationScale[tile],
            potentialEvaporationMm = demand, rootSupplyMm = rootAccess, drainageDecay = drainageDecay)
        soil[tile] = soilBalance.soilMm
        drainage[tile] += soilBalance.drainageMm
        overflow[tile] += soilBalance.runoffMm
        surfaceWater.receive(tile, soilBalance.drainageMm, soilBalance.runoffMm)
    }

    fun beginUpdate() { rain.fill(0.0); drainage.fill(0.0); overflow.fill(0.0) }
    fun snowWaterMm(tile: Int) = snow[tile]
}
