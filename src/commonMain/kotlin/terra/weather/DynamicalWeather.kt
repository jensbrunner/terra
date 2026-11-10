package terra.weather

import terra.atmosphere.AtmosphereTileMapping
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.MoistAtmosphere
import terra.atmosphere.MoistAtmosphereSnapshot
import terra.atmosphere.MoistCell
import terra.hydrology.HydrologySimulation
import terra.world.World
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Coarse prognostic atmosphere, conservative fine surface, and a stable tile-space render view.
 * This is still a doubly periodic f-plane; the terrain hydrology retains bounded outlets.
 */
class DynamicalWeather(
    world: World,
    private val hydrology: HydrologySimulation,
    val config: DryAtmosphereConfig = DryAtmosphereConfig()
) : WeatherModel {
    override val width = world.width
    override val height = world.height
    private val mapping = AtmosphereTileMapping(width, height, config)
    private val surface = TerrainMoistSurface(world, mapping, hydrology)
    private val model = MoistAtmosphere(config, seed = world.seed, surface = surface, initializeColumns = { x, y ->
        val c = min(config.height - 1, (y / config.dy).toInt()) * config.width +
            min(config.width - 1, (x / config.dx).toInt())
        MoistCell(temperatureKelvin = 280.0, relativeHumidity = 0.75,
            surfaceTemperatureKelvin = 288.0 - 4.0 * surface.landFraction[c], surfaceWaterKgM2 = 0.0)
    })
    private var view: MoistAtmosphereSnapshot = model.snapshot()
    private val temperatureIntegral = DoubleArray(config.cellCount)
    private var updateSeconds = 0.0
    val elapsedSeconds get() = model.elapsedSeconds
    val diagnostics get() = view.diagnostics

    /** Execute whole physical steps only. The caller keeps the unconsumed requested time. */
    fun advanceBudgeted(requestedSeconds: Double, maximumSteps: Int = 8): Double {
        require(requestedSeconds.isFinite() && requestedSeconds >= 0 && maximumSteps > 0)
        val steps = min(maximumSteps.toDouble(), floor(requestedSeconds / config.stepSeconds)).toInt()
        if (steps == 0) return 0.0
        surface.beginUpdate()
        repeat(steps) {
            model.step()
            surface.finishStep(config.stepSeconds)
            for (c in temperatureIntegral.indices) temperatureIntegral[c] +=
                (model.temperatureKelvinAt(c) - 273.15) * config.stepSeconds / 86400.0
        }
        updateSeconds = steps * config.stepSeconds
        view = model.snapshot()
        return updateSeconds
    }

    override fun step(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0)
        require(seconds / config.stepSeconds == floor(seconds / config.stepSeconds)) { "Use whole atmospheric steps" }
        if (seconds > 0) advanceBudgeted(seconds, Int.MAX_VALUE)
    }

    fun observedClimate(): ClimateSnapshot? {
        if (elapsedSeconds == 0.0) return null
        val temperature = DoubleArray(width * height) { i -> sample(i % width, i / width) { x, y ->
            temperatureIntegral[y * config.width + x]
        } }
        return ClimateSnapshot(width, height, elapsedSeconds / 86400.0, surface.observedRain.copyOf(), temperature)
    }

    /** State interpolation is for display. Water transfers always use exact rectangle overlaps. */
    private inline fun sample(x: Int, y: Int, field: (Int, Int) -> Double): Double {
        val px = (x + 0.5) * config.width / width - 0.5
        val py = config.height - (y + 0.5) * config.height / height - 0.5
        val left = floor(px).toInt(); val bottom = floor(py).toInt()
        val fx = px - left; val fy = py - bottom
        val x0 = (left + config.width) % config.width; val x1 = (x0 + 1) % config.width
        val y0 = (bottom + config.height) % config.height; val y1 = (y0 + 1) % config.height
        return (field(x0, y0) * (1 - fx) + field(x1, y0) * fx) * (1 - fy) +
            (field(x0, y1) * (1 - fx) + field(x1, y1) * fx) * fy
    }

    private fun index(x: Int, y: Int) = y * width + x
    override fun relativeHumidity(x: Int, y: Int) = sample(x, y, view::relativeHumidityAt)
    override fun cloudWaterMm(x: Int, y: Int) = sample(x, y, view::cloudWaterMmAt)
    override fun cloudCover(x: Int, y: Int) = 1 - exp(-cloudWaterMm(x, y) / 0.15)
    override fun atmosphericWaterMm(x: Int, y: Int) = sample(x, y, view::vaporMmAt)
    override fun airTemperatureC(x: Int, y: Int) = sample(x, y, view::temperatureKelvinAt) - 273.15
    override fun surfaceTemperatureC(x: Int, y: Int) = sample(x, y, view::surfaceTemperatureKelvinAt) - 273.15
    override fun windUTilesPerDay(x: Int, y: Int) = sample(x, y, view.dynamics::eastWindAt) * 86400 * width / config.domainWidthMeters
    override fun windVTilesPerDay(x: Int, y: Int) = -sample(x, y, view.dynamics::northWindAt) * 86400 * height / config.domainHeightMeters
    override fun precipitationMmPerHour(x: Int, y: Int) = if (updateSeconds > 0) rainMmThisUpdate(x, y) * 3600 / updateSeconds else 0.0
    override fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int) = surface.peakRainRate[index(x, y)]
    override fun rainMmThisUpdate(x: Int, y: Int) = surface.rain[index(x, y)]
    override fun drainageMmThisUpdate(x: Int, y: Int) = surface.drainage[index(x, y)]
    override fun overflowMmThisUpdate(x: Int, y: Int) = surface.overflow[index(x, y)]
    override fun lakeDirectRainMmThisUpdate(x: Int, y: Int) = surface.lakeRain[index(x, y)]
    // Lake evaporation was already withdrawn and paid for during the physical surface exchange.
    override fun lakePotentialEvaporationMmThisUpdate(x: Int, y: Int) = 0.0
    override fun thirtyDayRainMmPerHour(x: Int, y: Int) = surface.climateRainRate[index(x, y)]
    override fun annualizedRainMmPerYear(x: Int, y: Int) = thirtyDayRainMmPerHour(x, y) * 24 * 365
    override fun soilWaterMm(x: Int, y: Int) = surface.soil[index(x, y)]
    override fun soilMoisture(x: Int, y: Int) = soilWaterMm(x, y) / 250.0
    override fun solarInputFactor(x: Int, y: Int) = max(0.0, cos(2 * PI * (elapsedSeconds / 86400 + (x + 0.5) / width - 0.5)))
    override fun nightOpacity(x: Int) = (1 - solarInputFactor(x, 0)) * 0.65
    override fun updateLakeWaterMask(mask: BooleanArray) = surface.updateLakeMask(mask)
    override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double) {
        check(amountMm == 0.0) { "Dynamical lake evaporation must be withdrawn during surface heat exchange" }
    }
    override fun hydrologyAccepted() = surface.transferredToHydrology()
}
