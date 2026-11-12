package terra.weather

import terra.atmosphere.MoistAtmosphere
import terra.hydrology.HydrologySimulation
import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.max
import kotlin.math.min

/** Explicitly approximate energy; water always belongs to existing atmosphere/surface/hydrology. */
data class ClimateIntervalDiagnostics(
    val startSeconds: Double, val durationSeconds: Double, val subperiods: Int,
    val waterBeforeKg: Double, val waterAfterKg: Double, val relativeWaterResidual: Double,
    val evaporationKg: Double, val rainfallKg: Double, val requestedRainfallKg: Double,
    val surfaceRelaxationEnergyJoules: Double, val detailedCompletedSteps: Long,
    val energyApproximate: Boolean = true, val routingPasses: Long = 0, val representedHydrologySeconds: Double = 0.0, val aggregateYears: Boolean = false, val ecologyEligibleSeconds: Double = 0.0
)

internal class FastClimateResult(
    val diagnostics: ClimateIntervalDiagnostics,
    private val fields: Array<DoubleArray>, private val representativeCloud: DoubleArray,
    private val representativeHumidity: DoubleArray
) {
    val semantics = WeatherFieldSemantics(WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL,
        diagnostics.startSeconds, diagnostics.durationSeconds, energyApproximate = true, aggregateYears = diagnostics.aggregateYears)
    fun field(field: Int, tile: Int) = fields[field][tile]
    fun rainEvents(tile: Int) = RainEventSummary(fields[5][tile] / diagnostics.durationSeconds, fields[6][tile])
    fun cloudMm(cell: Int) = representativeCloud[cell]
    fun relativeHumidity(cell: Int) = representativeHumidity[cell].coerceIn(0.0, 1.0)
    fun captureFields(surface: TerrainMoistSurface) = arrayOf(fields[0], fields[1], fields[2], fields[3], fields[4],
        surface.climateRainRate.copyOf(), surface.soil.copyOf())
}

/** Chronological reduced climate. Surface stores and column water are authoritative in both modes.
 * Energy is a labeled relaxation approximation; water transfers are conservative.
 */
internal class FastClimateStepper(
    private val surface: TerrainMoistSurface, private val hydrology: HydrologySimulation,
    private val atmosphere: MoistAtmosphere, private val history: SeasonalClimate,
    private val normals: ClimateNormals, startSeconds: Double,
    private val evolveEcology: (Double, DoubleArray) -> Unit = { _, _ -> }
) {
    private val config = surface.mapping.config
    private val count = config.cellCount
    private val phaseMeanMoisture = DoubleArray(count) // Owner-only scratch; never published.
    private val transport = ClimateMoistureTransport(config)
    // Freeze independent detailed calibration on entry; never learn from our own modeled rain.
    private val calibration = history.snapshot?.takeUnless { it.containsAggregateEstimates }
    private val water = DoubleArray(count)
    private val actualRain = DoubleArray(count)
    private val oldTemperature = DoubleArray(count)
    private val newTemperature = DoubleArray(count)
    private val integral = DoubleArray(count)
    private val potential = DoubleArray(count)
    private val soilExposure = DoubleArray(surface.soil.size)
    private var soilExposureSeconds = 0.0
    val maximumIntervalSeconds get() = 20 * normals.yearSeconds
    var completedSeconds = startSeconds
        private set
    var latest: FastClimateResult? = null
        private set

    init {
        check(surface.pendingFluxBatch?.consumed != false) { "Accept detailed water before entering climate evolution" }
        check(history.elapsedSeconds == startSeconds)
        atmosphere.markClimateEvolution()
    }

    fun advance(seconds: Double): FastClimateResult {
        require(seconds.isFinite() && seconds >= MIN_INTERVAL_SECONDS && seconds <= maximumIntervalSeconds)
        require((completedSeconds + seconds).isFinite() && completedSeconds + seconds > completedSeconds)
        check(surface.pendingFluxBatch?.consumed != false)
        check(history.elapsedSeconds == completedSeconds)
        val start = completedSeconds
        val before = atmosphere.atmosphericWaterKg + surface.totalWaterKg
        val fields = Array(7) { DoubleArray(surface.mapping.tileWidth * surface.mapping.tileHeight) }
        val throughput = DoubleArray(surface.mapping.tileWidth * surface.mapping.tileHeight)
        val lakeInflow = DoubleArray(hydrology.lakeCount)
        var representedHydrologySeconds = 0.0
        var outlet = 0.0
        val routingBefore = hydrology.completedRoutingPasses
        val representativeCloud = DoubleArray(count)
        val representativeHumidity = DoubleArray(count)
        val plan = ClimatePhasePlan(start, seconds, normals.yearSeconds)
        var phaseClock = start
        var evaporated = 0.0
        var rainfall = 0.0
        var requestedRain = 0.0
        var relaxationEnergy = 0.0
        for (phase in plan.phases) {
            val phaseStart = phaseClock
            val phaseSeconds = phase.seconds
            val phaseDays = phaseSeconds / DAY_SECONDS
            // Forcing is sampled on an absolute calendar, independent of worker transaction size.
            val estimate = normals.estimate(phase.forcingStart + phase.forcingSeconds / 2, calibration)
            val event = estimate
            actualRain.fill(0.0)
            surface.beginUpdate()
            for (c in 0 until count) {
                val old = atmosphere.surfaceThermalState[c]
                val target = (estimate.temperatureC(c) + 273.15).coerceIn(193.15, 333.15)
                val tau = (10 * surface.landFraction[c] + 90 * (1 - surface.landFraction[c])) * DAY_SECONDS
                val response = -expm1(-phaseSeconds / tau)
                val next = old + (target - old) * response
                integral[c] = (target - 273.15) * phaseSeconds + (old - target) * tau * response
                atmosphere.surfaceThermalState[c] = next
                oldTemperature[c] = old - 273.15
                newTemperature[c] = next - 273.15
                relaxationEnergy += surface.heatCapacityJm2K(c) * (next - old) * config.cellArea
                // 0–4.5 mm/day is an energy-limited PET proxy (~0–128 W/m² latent heat).
                // Half exchange on each side of rainfall limits ordering error in a ten-day step.
                potential[c] = 3.0 * ((next - 273.15 + 5) / 35).coerceIn(0.0, 1.5) * phaseDays
                val actual = surface.evaporate(c, potential[c] / 2)
                atmosphere.receiveEffectiveMoistureMm(c, actual)
                evaporated += actual * config.cellArea
                water[c] = atmosphere.effectiveMoistureMmAt(c)
                phaseMeanMoisture[c] = water[c]
                requestedRain += estimate.precipitationMmPerDay(c) * phaseDays * config.cellArea
            }
            transport.advance(water, actualRain, phaseSeconds,
                ((phaseStart + phaseSeconds / 2) / normals.yearSeconds + normals.initialYearFraction) % 1.0,
                surface::orographicUpliftMetersPerSecond, atmosphere::saturatedVaporMmAt, newTemperature, estimate::wetAnomaly)
            val wetFractions = DoubleArray(count) { c ->
                (0.25 + 0.15 * event.wetAnomaly(c)).coerceIn(0.1, 0.4)
            }
            for (c in 0 until count) {
                // Commit the transported column inventory, never a separate persistent reservoir.
                atmosphere.withdrawEffectiveMoistureMm(c, atmosphere.effectiveMoistureMmAt(c))
                atmosphere.receiveEffectiveMoistureMm(c, water[c])
                phaseMeanMoisture[c] = (phaseMeanMoisture[c] + water[c]) / 2
                surface.receiveClimateRain(c, actualRain[c], wetFractions[c])
                rainfall += actualRain[c] * config.cellArea
            }
            surface.finishClimateStep(phaseSeconds)
            for (c in 0 until count) {
                val actual = surface.evaporate(c, potential[c] / 2)
                atmosphere.receiveEffectiveMoistureMm(c, actual)
                evaporated += actual * config.cellArea
            }
            soilExposureSeconds += phaseSeconds
            for (tile in fields[0].indices) {
                soilExposure[tile] += (surface.soil[tile] / 120.0).coerceIn(0.0, 1.0) * phaseSeconds
                fields[0][tile] = max(fields[0][tile], surface.peakRainRate[tile])
                fields[1][tile] += surface.rain[tile]
                fields[2][tile] += surface.drainage[tile]
                fields[3][tile] += surface.overflow[tile]
                fields[4][tile] += surface.lakeRain[tile]
                fields[5][tile] += surface.rainWetSeconds[tile]
                fields[6][tile] += max(0.0, surface.rain[tile] - INTENSE_RAIN_MM_PER_HOUR * surface.rainWetSeconds[tile] / 3600)
            }
            representedHydrologySeconds += hydrology.acceptClimateFluxBatch(surface.sealFluxBatch(phaseSeconds))
            for (tile in throughput.indices) throughput[tile] += hydrology.routedMmTiles(tile % hydrology.width, tile / hydrology.width)
            for (lake in lakeInflow.indices) lakeInflow[lake] += hydrology.lakeInflowMmTilesLastPeriod(lake)
            outlet += hydrology.outletMmTilesLastPeriod
            val yearsBefore = history.observedYears
            history.observeInterval(ClimateIntervalSummary(config.width, config.height, phaseStart, phaseSeconds,
                integral, actualRain, DoubleArray(count) { min(oldTemperature[it], newTemperature[it]) },
                DoubleArray(count) { max(oldTemperature[it], newTemperature[it]) },
                DoubleArray(count) { actualRain[it] * 3600 / (phaseSeconds * wetFractions[it]) }), modeled = true)
            // Annual ecology uses the actual intervening years, even in a twenty-year transaction.
            if (history.observedYears > yearsBefore) {
                for (tile in soilExposure.indices) soilExposure[tile] /= soilExposureSeconds
                evolveEcology(min(normals.yearSeconds, soilExposureSeconds), soilExposure)
                soilExposure.fill(0.0)
                soilExposureSeconds = 0.0
            }
            // A representative column depends on rain RATE, not accumulated rain
            // depth: the latter turned longer quanta into near-uniform overcast.
            for (c in 0 until count) {
                val hourlyRain = actualRain[c] * 3600 / phaseSeconds
                val meanAirKelvin = integral[c] / phaseSeconds + 273.15 - MoistAtmosphere.CLIMATE_AIR_SURFACE_OFFSET_K
                val saturation = atmosphere.saturatedVaporMmAt(c, meanAirKelvin)
                val humidity = humidityProxy(phaseMeanMoisture[c], saturation)
                val weight = phaseSeconds / seconds
                representativeHumidity[c] += humidity * weight
                representativeCloud[c] += cloudProxyMm(phaseMeanMoisture[c], humidity, hourlyRain) * weight
            }
            phaseClock += phaseSeconds
        }
        hydrology.publishClimateThroughput(representedHydrologySeconds, throughput, lakeInflow, outlet)
        val after = atmosphere.atmosphericWaterKg + surface.totalWaterKg
        val residual = (after - before) / max(1.0, abs(before))
        check(residual.isFinite() && abs(residual) < 1e-10) { "Climate water transaction did not reconcile: $residual" }
        completedSeconds = history.elapsedSeconds
        return FastClimateResult(ClimateIntervalDiagnostics(start, seconds, plan.phases.size, before, after, residual,
            evaporated, rainfall, requestedRain, relaxationEnergy, atmosphere.completedSteps,
            routingPasses = hydrology.completedRoutingPasses - routingBefore, representedHydrologySeconds = representedHydrologySeconds,
            aggregateYears = false, ecologyEligibleSeconds = seconds), fields, representativeCloud, representativeHumidity)
            .also { latest = it }
    }

    companion object {
        // Appearance estimates only: no mass/heat exchange and no history feedback.
        internal fun humidityProxy(meanMoistureMm: Double, saturationMm: Double): Double =
            (meanMoistureMm / max(1e-12, saturationMm)).coerceIn(0.0, 1.0)

        internal fun cloudProxyMm(meanMoistureMm: Double, humidity: Double, fundedHourlyRain: Double): Double {
            val t = ((humidity - 0.8) / 0.2).coerceIn(0.0, 1.0)
            val humidityCloud = min(meanMoistureMm, 0.25 * t * t * (3 - 2 * t))
            val rainCloud = min(0.6, fundedHourlyRain * 1.5)
            return min(0.6, max(humidityCloud, rainCloud))
        }

        /** Diagnostic rain above 2 mm/h, not an Earth-calibrated infiltration cutoff. */
        const val INTENSE_RAIN_MM_PER_HOUR = 2.0
        const val MIN_INTERVAL_SECONDS = 1.0
        const val DAY_SECONDS = 86400.0
    }
}
