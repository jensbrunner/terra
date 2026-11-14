package terra.climate

import terra.hydrology.SurfaceWater
import terra.hydrology.SoilWaterBalance
import terra.hydrology.SoilWaterBalance.Companion.DRAINAGE_SECONDS
import terra.hydrology.SoilWaterBalance.Companion.FIELD_CAPACITY_FRACTION
import terra.world.World
import kotlin.math.*

/** Shared soil, snow and slow drainage stores. Ocean exchange is a signed boundary
 * ledger; inland water is finite and included in every conservation diagnostic.
 */
internal class LandscapeWater(private val world: World, val tileAreaM2: Double) {
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
    private var boundaryMmTiles = 0.0
    private var boundaryRoundoff = 0.0
    private fun export(amount: Double) {
        val corrected = amount - boundaryRoundoff
        val next = boundaryMmTiles + corrected
        boundaryRoundoff = (next - boundaryMmTiles) - corrected
        boundaryMmTiles = next
    }
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
        beginUpdate()
    }

    /** Integrate the same traveling precipitation field used by the renderer. */
    internal fun evolveMonth(atlas: SeasonalClimateAtlas, month: Int, fraction: Double,
        startFraction: Double = 0.0): Double {
        var evaporated = 0.0
        val days = fraction * atlas.solar.yearSeconds / (12 * 86400)
        val growthMemory = -expm1(-fraction / 6)
        val droughtMemory = -expm1(-fraction / 24)
        val drainageDecay = exp(-days * 86400 / DRAINAGE_SECONDS)
        for (i in 0 until count) {
            val amount = atlas.rainfall.amountMm(month, i, startFraction, (startFraction + fraction).coerceAtMost(1.0))
            rain[i] += amount
            if (!land[i]) continue
            val temperature = atlas.temperature[month][i]
            val frozen = atlas.rainfall.snowFraction(month, i)
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
            val actual = evolveClimateBucket(i, input, demand, days, drainageDecay)
            evaporated += actual
            if (temperature > 5 && demand > 0) {
                val availability = 0.6 * actual / demand + 0.4 * (soil[i] / (soilCapacityMm[i] * FIELD_CAPACITY_FRACTION)).coerceIn(0.0, 1.0)
                growingWaterAvailability[i] += (availability - growingWaterAvailability[i]) * growthMemory
                climateDrought[i] += (1 - availability - climateDrought[i]) * droughtMemory
            }
        }
        export(surfaceWater.advance(days * 86400))
        return evaporated * tileAreaM2
    }

    internal class ClimateState(val soil: DoubleArray, val snow: DoubleArray, val availability: DoubleArray,
        val drought: DoubleArray, val water: SurfaceWater.State, val boundary: Double, val roundoff: Double)

    internal fun captureClimateState() = ClimateState(soil.copyOf(), snow.copyOf(), growingWaterAvailability.copyOf(),
        climateDrought.copyOf(), surfaceWater.captureState(), boundaryMmTiles, boundaryRoundoff)

    internal fun restoreClimateState(state: ClimateState) {
        state.soil.copyInto(soil); state.snow.copyInto(snow)
        state.availability.copyInto(growingWaterAvailability); state.drought.copyInto(climateDrought)
        surfaceWater.restoreState(state.water)
        boundaryMmTiles = state.boundary; boundaryRoundoff = state.roundoff
    }

    /** Atlas forcing adds potential ET and finite root access to the shared balance. */
    private fun evolveClimateBucket(tile: Int, input: Double, demand: Double, days: Double, drainageDecay: Double): Double {
        val seconds = days * 86400
        val infiltrated = SoilWaterBalance.infiltratedMm(input, seconds, infiltrationScale[tile])
        val rootAccess = surfaceWater.supplyRoots(tile, min(max(0.0, demand - soil[tile] - infiltrated), 0.5 * days))
        soilBalance.advance(soil[tile], soilCapacityMm[tile], input, seconds, infiltrationScale[tile],
            potentialEvaporationMm = demand, rootSupplyMm = rootAccess, drainageDecay = drainageDecay)
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
        for (i in 0 until count) {
            val next = world.isLand(i % width, i / width)
            if (land[i] != next) {
                // Conversion resets terrestrial stores; surviving land retains its inventories.
                if (!next) export(soil[i] + snow[i])
                soil[i] = 0.0; snow[i] = 0.0; climateDrought[i] = 0.0; growingWaterAvailability[i] = 0.0
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
    }

    fun beginUpdate() { rain.fill(0.0); drainage.fill(0.0); overflow.fill(0.0) }
    fun snowWaterMm(tile: Int) = snow[tile]
    val totalWaterKg get() = (soil.sum() + snow.sum() + surfaceWater.totalWaterMmTiles) * tileAreaM2
    val exportedWaterKg get() = boundaryMmTiles * tileAreaM2
    private companion object {
        const val HYDROLOGY_SPINUP_YEARS = 4
    }
}
