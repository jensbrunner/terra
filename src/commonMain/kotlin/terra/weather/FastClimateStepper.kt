package terra.weather

import terra.atmosphere.MoistAtmosphere
import terra.hydrology.HydrologySimulation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Explicitly approximate energy; water always belongs to existing atmosphere/surface/hydrology. */
data class ClimateIntervalDiagnostics(
    val startSeconds: Double, val durationSeconds: Double, val subperiods: Int,
    val waterBeforeKg: Double, val waterAfterKg: Double, val relativeWaterResidual: Double,
    val evaporationKg: Double, val rainfallKg: Double, val requestedRainfallKg: Double,
    val surfaceRelaxationEnergyJoules: Double, val detailedCompletedSteps: Long,
    val energyApproximate: Boolean = true, val routingPasses: Long = 0,
    val representedHydrologySeconds: Double = 0.0, val aggregateYears: Boolean = false,
    val ecologyEligibleSeconds: Double = 0.0,
    val absorbedShortwaveJoules: Double = 0.0,
    val outgoingLongwaveJoules: Double = 0.0,
    val sensibleHeatJoules: Double = 0.0,
    val latentHeatJoules: Double = 0.0,
    val airTransportJoules: Double = 0.0,
    val oceanTransportJoules: Double = 0.0,
    val snowMeltEnergyJoules: Double = 0.0,
    val condensationHeatingJoules: Double = 0.0,
    val seaIcePhaseChangeJoules: Double = 0.0,
    val coldFloorAdjustmentJoules: Double = 0.0,
    val namedEnergyResidualJoules: Double = 0.0,
    val potentialEvaporationMm: Double = 0.0,
    val actualEvaporationMm: Double = 0.0,
    val maximumWindMetersPerSecond: Double = 0.0,
    val meanWindMetersPerSecond: Double = 0.0,
    val moistureTransportResidual: Double = 0.0,
    val maximumCourant: Double = 0.0,
    val transportSubsteps: Int = 0
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
    fun windUTilesPerDay(cell: Int) = fields[7][cell]
    fun windVTilesPerDay(cell: Int) = fields[8][cell]
    fun airTemperatureC(cell: Int) = fields[9][cell]
    fun captureFields(surface: TerrainMoistSurface) = arrayOf(fields[0], fields[1], fields[2], fields[3], fields[4],
        surface.climateRainRate.copyOf(), surface.soil.copyOf(), fields[7], fields[8])
}

/**
 * Chronological reduced climate coordinator.
 *
 * One phase visits, in order: seasonal energy exchange, diagnosed circulation,
 * conservative moisture transport and precipitation, land-water integration,
 * hydrology routing, and periodic ecology. No phase applies accumulated forcing
 * retrospectively, and no worker transaction can skip a seasonal boundary.
 */
internal class FastClimateStepper(
    private val surface: TerrainMoistSurface, private val hydrology: HydrologySimulation,
    private val atmosphere: MoistAtmosphere, private val history: SeasonalClimate,
    private val normals: ClimateNormals, startSeconds: Double,
    private val evolveEcology: (Double, DoubleArray) -> Unit = { _, _ -> },
    oceanTransport: OceanTransportMode = OceanTransportMode.LATERAL_MIXING
) {
    private val fineConfig = surface.mapping.config
    private val fineCount = fineConfig.cellCount
    private val grid = ClimateGrid(fineConfig)
    private val geography = ClimateGeography(surface.worldForClimate, grid)
    private val regimes = WeatherRegimes(grid, surface.worldSeedForClimate)
    private val energy = ClimateEnergyModel(grid, geography, surface, atmosphere, atmosphere.physics,
        normals.solarConfig(), oceanTransport)
    private val circulation = DiagnosticCirculation(grid, geography)
    private val moisture = ClimateMoisture(grid, geography, atmosphere.physics, regimes)
    private val coarseWater = DoubleArray(grid.count)
    private val coarseSurfaceC = DoubleArray(grid.count)
    private val coarseSurfaceK = DoubleArray(grid.count)
    private val coarseHumidity = DoubleArray(grid.count)
    private val coarseWeight = DoubleArray(grid.count)
    private val coarseWetFraction = DoubleArray(grid.count)
    private val fineWaterScratch = DoubleArray(fineCount)
    private val fineElevation = DoubleArray(fineCount) { surface.terrain.meanElevationMeters[it] }
    private val fineTemperatureAfter = DoubleArray(fineCount)
    private val soilExposureScratch = DoubleArray(surface.soil.size)
    private val fineTemperature = DoubleArray(fineCount)
    private val fineOldTemperature = DoubleArray(fineCount)
    private val fineRain = DoubleArray(fineCount)
    private val fineHumidity = DoubleArray(fineCount)
    private val temperatureIntegral = DoubleArray(fineCount)
    private val minimumTemperature = DoubleArray(fineCount)
    private val maximumTemperature = DoubleArray(fineCount)
    private var totalMoistureResidual = 0.0
    private var lastCirculation: CirculationState? = null
    val maximumIntervalSeconds get() = 20 * normals.yearSeconds
    var completedSeconds = startSeconds
        private set
    var latest: FastClimateResult? = null
        private set

    init {
        check(surface.pendingFluxBatch?.consumed != false) { "Accept detailed water before entering climate evolution" }
        check(history.elapsedSeconds == startSeconds)
        atmosphere.markClimateEvolution()
        val estimate = normals.estimate(startSeconds)
        energy.initialize { cell -> estimate.temperatureC(cell) }
        atmosphere.surfaceThermalState.let { thermal ->
            // Preserve any detailed surface temperature already present; the energy model
            // only overwrites it when it has no prior state. Initialization is explicit.
            for (cell in 0 until fineCount) if (!thermal[cell].isFinite() || thermal[cell] <= 0.0)
                thermal[cell] = estimate.temperatureC(cell) + 273.15
        }
    }

    fun advance(seconds: Double): FastClimateResult {
        require(seconds.isFinite() && seconds >= MIN_INTERVAL_SECONDS && seconds <= maximumIntervalSeconds)
        require((completedSeconds + seconds).isFinite() && completedSeconds + seconds > completedSeconds)
        check(surface.pendingFluxBatch?.consumed != false)
        check(history.elapsedSeconds == completedSeconds)
        val start = completedSeconds
        val before = atmosphere.atmosphericWaterKg + surface.totalWaterKg
        val fields = Array(10) { DoubleArray(surface.mapping.tileWidth * surface.mapping.tileHeight) }
        val throughput = DoubleArray(surface.mapping.tileWidth * surface.mapping.tileHeight)
        val lakeInflow = DoubleArray(hydrology.lakeCount)
        var representedHydrologySeconds = 0.0
        var outlet = 0.0
        val routingBefore = hydrology.completedRoutingPasses
        val representativeCloud = DoubleArray(fineCount)
        val representativeHumidity = DoubleArray(fineCount)
        val plan = ClimatePhasePlan(start, seconds, normals.yearSeconds)
        var phaseClock = start
        var evaporated = 0.0
        var rainfall = 0.0
        var requestedRain = 0.0
        val demandEstimate = normals.estimate(start + seconds / 2, calibration)
        for (cell in 0 until fineCount) {
            requestedRain += demandEstimate.precipitationMmPerDay(cell) * seconds / DAY_SECONDS *
                fineConfig.dx * fineConfig.dy
        }
        var moistureResidual = 0.0
        var totalTransportSteps = 0
        var maximumCourant = 0.0
        var maximumWind = 0.0
        var meanWindSum = 0.0
        var windSamples = 0
        energy.resetIntervalTotals()
        totalMoistureResidual = 0.0
        for (phase in plan.phases) {
            val phaseStart = phaseClock
            val phaseSeconds = phase.seconds
            val mid = phaseStart + phaseSeconds / 2
            for (cell in 0 until fineCount) {
                fineOldTemperature[cell] = atmosphere.surfaceThermalState[cell] - 273.15
                minimumTemperature[cell] = fineOldTemperature[cell]
                maximumTemperature[cell] = fineOldTemperature[cell]
            }

            // Restrict the current surface state and diagnose one mass-consistent circulation.
            atmosphere.surfaceThermalState.let { thermal ->
                for (cell in 0 until fineCount) fineTemperature[cell] = thermal[cell]
            }
            grid.restrict(fineTemperature, coarseSurfaceK)
            for (cell in 0 until grid.count) coarseSurfaceC[cell] = coarseSurfaceK[cell] - 273.15
            val yearFraction = ((mid / normals.yearSeconds + normals.initialYearFraction) % 1.0)

            val state = circulation.advance(energy.airTemperatureK, coarseSurfaceK, phaseSeconds, yearFraction)
            lastCirculation = state
            maximumWind = max(maximumWind, state.maximumSpeed)
            meanWindSum += state.rmsSpeed; windSamples++

            surface.beginUpdate()
            energy.beginPhase(mid)

            // First half exchange: seasonal energy, surface heat, evaporation.

            computeFineHumidity()
            val firstEvaporation = energy.exchange(phaseSeconds, mid, state,
                { cell -> fineHumidity[cell] })
            atmosAdd(firstEvaporation)
            for (cell in 0 until fineCount) {
                if (firstEvaporation[cell] > 0) evaporated += firstEvaporation[cell] * surface.mapping.tileAreaM2
            }

            // Coarse humidity for the moisture closure.
            computeCoarseHumidity()
            for (cell in 0 until fineCount) fineWaterScratch[cell] = atmosphere.effectiveMoistureMmAt(cell)
            grid.restrict(fineWaterScratch, coarseWater)

            val result = moisture.advance(coarseWater, phaseSeconds, mid, normals.yearSeconds, state,
                energy.airTemperatureK, coarseSurfaceC, { coarseHumidity[it] },
                fineElevation)
            moistureResidual = max(moistureResidual, abs(result.moistureResidualMm))
            totalTransportSteps += result.transportSteps
            maximumCourant = max(maximumCourant, result.maximumCourant)
            // Commit the transported coarse inventory back to the authoritative fine columns.
            grid.prolongUniform(coarseWater, fineTemperature) // reuse as a fine scratch
            for (cell in 0 until fineCount) atmosphere.setEffectiveMoistureMm(cell, fineTemperature[cell])
            for (coarse in 0 until grid.count) coarseWetFraction[coarse] = regimes.wetFraction(coarse, mid, normals.yearSeconds)
            // Distribute fine rain, including snow, to the physical surface.
            for (cell in 0 until fineCount) {
                val fineCell = cell
                val amount = result.fineRainMm[cell]
                if (amount <= 0.0) continue
                val coarse = grid.overlap.targetOfSource[cell]
                val wet = coarseWetFraction[coarse]
                val snow = if (atmosphere.surfaceThermalState[fineCell] - 273.15 < 0.0) 1.0 else 0.0
                surface.receiveClimateRain(cell, amount, wet, snow)
                rainfall += amount * surface.mapping.tileAreaM2
            }
            // Ensure every cell receives its current temperature for snowmelt even when dry.
            for (cell in 0 until fineCount) surface.setCellTemperature(cell, atmosphere.surfaceThermalState[cell] - 273.15)

            surface.finishClimateStep(phaseSeconds)

            energy.addCondensationHeat(result.fineRainMm, phaseSeconds)
            energy.mix(phaseSeconds, state)

            // Publish the actual subgrid chronology diagnostics.
            for (cell in 0 until fineCount) fineTemperatureAfter[cell] = atmosphere.surfaceThermalState[cell] - 273.15
            for (cell in 0 until fineCount) {
                temperatureIntegral[cell] = (fineOldTemperature[cell] + fineTemperatureAfter[cell]) / 2 * phaseSeconds
                minimumTemperature[cell] = min(minimumTemperature[cell], fineTemperatureAfter[cell])
                maximumTemperature[cell] = max(maximumTemperature[cell], fineTemperatureAfter[cell])
                fields[0][cell] = max(fields[0][cell], surface.peakRainRate[cell])
                fields[1][cell] += surface.rain[cell]
                fields[2][cell] += surface.drainage[cell]
                fields[3][cell] += surface.overflow[cell]
                fields[4][cell] += surface.lakeRain[cell]
                fields[5][cell] += surface.rainWetSeconds[cell]
                fields[6][cell] += max(0.0, surface.rain[cell] - INTENSE_RAIN_MM_PER_HOUR * surface.rainWetSeconds[cell] / 3600)
                val coarse = grid.overlap.targetOfSource[cell]
                // Display the representative low-level flow, not the vertically integrated
                // column transport used by the moisture budget.
                fields[7][cell] = state.lowEast[coarse] * 86400 * surface.mapping.tileWidth / fineConfig.domainWidthMeters
                fields[8][cell] = -state.lowNorth[coarse] * 86400 * surface.mapping.tileHeight / fineConfig.domainHeightMeters
                fields[9][cell] = energy.airTemperatureK[coarse] - 0.0065 * surface.terrain.meanElevationMeters[cell] - 273.15
            }

            val batch = surface.sealFluxBatch(phaseSeconds)
            val routingBeforePhase = hydrology.completedRoutingPasses
            representedHydrologySeconds += hydrology.acceptClimateFluxBatch(batch, forceRoute = false)
            if (hydrology.completedRoutingPasses > routingBeforePhase) {
                for (tile in throughput.indices) throughput[tile] += hydrology.routedMmTiles(tile % hydrology.width, tile / hydrology.width)
                for (lake in lakeInflow.indices) lakeInflow[lake] += hydrology.lakeInflowMmTilesLastPeriod(lake)
                outlet += hydrology.outletMmTilesLastPeriod
            }
            val yearsBefore = history.observedYears
            history.observePhase(phaseStart, phaseSeconds, temperatureIntegral, result.fineRainMm,
                minimumTemperature, maximumTemperature, surface.peakRainRate)
            if (history.observedYears > yearsBefore) {
                for (tile in soilExposureScratch.indices) {
                    soilExposureScratch[tile] = (surface.soil[tile] / 120.0).coerceIn(0.0, 1.0)
                }
                evolveEcology(normals.yearSeconds, soilExposureScratch)
            }
            // Representative cloud and humidity depend on phase-mean moisture and rain rate.
            for (cell in 0 until fineCount) {
                val hourlyRain = result.fineRainMm[cell] * 3600 / phaseSeconds
                val humidity = fineHumidity[cell]
                val weight = phaseSeconds / seconds
                representativeHumidity[cell] += humidity * weight
                representativeCloud[cell] += cloudProxyMm(atmosphere.effectiveMoistureMmAt(cell), humidity, hourlyRain) * weight
            }
            phaseClock += phaseSeconds
        }

        // The energy interval is one aggregate diagnostic; the approximation is named.
        hydrology.publishClimateThroughput(representedHydrologySeconds, throughput, lakeInflow, outlet)
        val after = atmosphere.atmosphericWaterKg + surface.totalWaterKg
        val residual = (after - before) / max(1.0, abs(before))
        check(residual.isFinite() && abs(residual) < 1e-10) { "Climate water transaction did not reconcile: $residual before=$before after=$after" }
        totalMoistureResidual = max(totalMoistureResidual, moistureResidual)
        completedSeconds = history.elapsedSeconds
        val diagnostics = ClimateIntervalDiagnostics(start, seconds, plan.phases.size, before, after, residual,
            evaporated, rainfall, requestedRain, 0.0, atmosphere.completedSteps,
            routingPasses = hydrology.completedRoutingPasses - routingBefore, representedHydrologySeconds = representedHydrologySeconds,
            aggregateYears = false, ecologyEligibleSeconds = seconds,
            absorbedShortwaveJoules = energy.absorbedShortwaveJoules,
            outgoingLongwaveJoules = energy.outgoingLongwaveJoules,
            sensibleHeatJoules = energy.sensibleHeatJoules,
            latentHeatJoules = energy.latentHeatJoules,
            airTransportJoules = energy.airTransportJoules,
            oceanTransportJoules = energy.oceanTransportJoules,
            snowMeltEnergyJoules = energy.snowMeltJoules,
            condensationHeatingJoules = energy.condensationJoules,
            seaIcePhaseChangeJoules = energy.seaIceJoules,
            coldFloorAdjustmentJoules = energy.coldFloorJoules,
            namedEnergyResidualJoules = energy.surfaceResidualJoules + energy.airResidualJoules,
            potentialEvaporationMm = energy.potentialEvaporationMm(),
            actualEvaporationMm = energy.actualEvaporationMm(),
            maximumWindMetersPerSecond = maximumWind,
            meanWindMetersPerSecond = if (windSamples > 0) meanWindSum / windSamples else 0.0,
            moistureTransportResidual = totalMoistureResidual,
            maximumCourant = maximumCourant,
            transportSubsteps = totalTransportSteps
        )
        return FastClimateResult(diagnostics, fields, representativeCloud, representativeHumidity)
            .also { latest = it }
    }

    private fun atmosAdd(evaporation: DoubleArray) {
        for (cell in 0 until fineCount) {
            val amount = evaporation[cell]
            if (amount > 0.0) atmosphere.receiveEffectiveMoistureMm(cell, amount)
        }
    }

    private fun computeFineHumidity() {
        for (cell in 0 until fineCount) {
            val coarse = grid.overlap.targetOfSource[cell]
            val saturation = atmosphere.saturatedVaporMmAt(cell, energy.airTemperatureK[coarse])
            fineHumidity[cell] = humidityProxy(atmosphere.effectiveMoistureMmAt(cell), saturation)
        }
    }

    private fun computeCoarseHumidity() {
        for (cell in 0 until grid.count) { coarseHumidity[cell] = 0.0; coarseWeight[cell] = 0.0 }
        for (cell in 0 until fineCount) {
            val coarse = grid.overlap.targetOfSource[cell]
            coarseHumidity[coarse] += fineHumidity[cell]
            coarseWeight[coarse] += 1.0
        }
        for (cell in 0 until grid.count) if (coarseWeight[cell] > 0) coarseHumidity[cell] /= coarseWeight[cell]
    }

    private val calibration get() = history.snapshot?.takeUnless { it.containsAggregateEstimates }

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
