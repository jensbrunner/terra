package terra.weather

import terra.atmosphere.MoistAtmosphere
import terra.atmosphere.ColumnThermodynamics
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Explicitly approximate energy; water always belongs to existing atmosphere and soil/snow; boundary exchange is explicit. */
data class ClimateIntervalDiagnostics(
    val startSeconds: Double, val durationSeconds: Double, val subperiods: Int,
    val waterBeforeKg: Double, val waterAfterKg: Double, val relativeWaterResidual: Double,
    val evaporationKg: Double, val rainfallKg: Double, val detailedCompletedSteps: Long,
    val boundaryExportKg: Double = 0.0, val energyApproximate: Boolean = true,
    val ecologyEligibleSeconds: Double = 0.0,
    val absorbedShortwaveJoules: Double = 0.0,
    val netSurfaceHeatLossJoules: Double = 0.0,
    val latentHeatJoules: Double = 0.0,
    val snowMeltEnergyJoules: Double = 0.0,
    val convectiveHeatExportJoules: Double = 0.0,
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
        diagnostics.startSeconds, diagnostics.durationSeconds, energyApproximate = true)
    fun field(field: Int, tile: Int) = fields[field][tile]
    fun rainEvents(tile: Int) = RainEventSummary(fields[4][tile] / diagnostics.durationSeconds, fields[5][tile])
    fun cloudMm(cell: Int) = representativeCloud[cell]
    fun relativeHumidity(cell: Int) = representativeHumidity[cell].coerceIn(0.0, 1.0)
    fun windUTilesPerDay(cell: Int) = fields[6][cell]
    fun windVTilesPerDay(cell: Int) = fields[7][cell]
    fun airTemperatureC(cell: Int) = fields[8][cell]
    fun captureFields(surface: TerrainMoistSurface) = arrayOf(fields[0], fields[1], fields[2], fields[3],
        surface.climateRainRate.copyOf(), surface.soil.copyOf(), fields[6], fields[7])
}

/**
 * Chronological reduced climate coordinator.
 *
 * One phase visits, in order: seasonal energy exchange, diagnosed circulation,
 * conservative moisture transport and precipitation, land-water integration,
 * and periodic ecology. No phase applies accumulated forcing
 * retrospectively, and no worker transaction can skip a seasonal boundary.
 */
internal class FastClimateStepper(
    private val surface: TerrainMoistSurface,
    private val atmosphere: MoistAtmosphere, private val history: SeasonalClimate,
    startSeconds: Double,
    private val evolveEcology: (Double, DoubleArray) -> Unit = { _, _ -> }
) {
    private val fineConfig = surface.mapping.config
    private val fineCount = fineConfig.cellCount
    private val grid = ClimateGrid(fineConfig)
    private val geography = ClimateGeography(surface.worldForClimate, grid)
    private val climate = ReducedClimate(grid, geography, surface, atmosphere)
    private val solar = atmosphere.physics.solar
    private val coarseWater = DoubleArray(grid.count)
    private val fineWaterScratch = DoubleArray(fineCount)
    private val fineTemperatureAfter = DoubleArray(fineCount)
    private val soilExposureScratch = DoubleArray(surface.soil.size)
    private var soilExposureSeconds = 0.0
    private val fineTemperature = DoubleArray(fineCount)
    private val fineOldTemperature = DoubleArray(fineCount)
    private val finePeakRain = DoubleArray(fineCount)
    private val fineHumidity = DoubleArray(fineCount)
    private val fineSaturation = DoubleArray(fineCount)
    private var saturationReady = false
    private val temperatureIntegral = DoubleArray(fineCount)
    private val minimumTemperature = DoubleArray(fineCount)
    private val maximumTemperature = DoubleArray(fineCount)
    private var totalMoistureResidual = 0.0
    val maximumIntervalSeconds get() = 20 * solar.yearSeconds
    var completedSeconds = startSeconds
        private set
    var latest: FastClimateResult? = null
        private set

    fun reentryAirTemperatureKelvin(): DoubleArray = climate.detailedAirTemperatureKelvin()

    init {
        check(history.elapsedSeconds == startSeconds)
        atmosphere.markClimateEvolution()
    }

    fun advance(seconds: Double): FastClimateResult {
        require(seconds.isFinite() && seconds >= MIN_INTERVAL_SECONDS && seconds <= maximumIntervalSeconds)
        require((completedSeconds + seconds).isFinite() && completedSeconds + seconds > completedSeconds)
        check(history.elapsedSeconds == completedSeconds)
        val start = completedSeconds
        val before = atmosphere.atmosphericWaterKg + surface.totalWaterKg
        val boundaryBefore = surface.exportedWaterKg
        val tileCount = surface.mapping.tileWidth * surface.mapping.tileHeight
        val fields = Array(9) { field -> DoubleArray(if (field < 6) tileCount else fineCount) }
        val fluxTotals = ClimateFluxTotals(fields)
        val representativeCloud = DoubleArray(fineCount)
        val representativeHumidity = DoubleArray(fineCount)
        val plan = ClimatePhasePlan(start, seconds, solar.yearSeconds)
        var phaseClock = start
        var evaporated = 0.0
        var rainfall = 0.0
        var moistureResidual = 0.0
        var totalTransportSteps = 0
        var maximumCourant = 0.0
        var maximumWind = 0.0
        var meanWindSum = 0.0
        var windSamples = 0
        climate.resetIntervalTotals()
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

            climate.diagnoseWinds()
            maximumWind = max(maximumWind, climate.maximumSpeed)
            meanWindSum += climate.meanSpeed; windSamples++
            surface.beginUpdate()
            val evaporation = climate.exchange(phaseSeconds, mid)
            saturationReady = false
            atmosAdd(evaporation)
            for (amount in evaporation) evaporated += amount * fineConfig.cellArea
            for (cell in 0 until fineCount) fineWaterScratch[cell] = atmosphere.effectiveMoistureMmAt(cell)
            grid.restrict(fineWaterScratch, coarseWater)
            val rain = climate.transport(coarseWater, phaseSeconds)
            moistureResidual = max(moistureResidual, abs(climate.moistureResidual))
            totalTransportSteps += climate.transportSteps
            maximumCourant = max(maximumCourant, climate.maximumCourant)
            // Commit the transported coarse inventory back to the authoritative fine columns.
            grid.prolongUniform(coarseWater, fineTemperature) // reuse as a fine scratch
            for (cell in 0 until fineCount) atmosphere.setEffectiveMoistureMm(cell, fineTemperature[cell])
            // Distribute fine rain, including snow, to the physical surface.
            finePeakRain.fill(0.0)
            for (cell in 0 until fineCount) {
                val fineCell = cell
                val amount = rain[cell]
                if (amount <= 0.0) continue
                // Unresolved rain events turn over a humid column in six hours.
                // Their rate varies with local moisture capacity; phase rain volume
                // only determines wet duration. It is never multiplied to make storms.
                val saturation = atmosphere.saturatedVaporMmAt(cell, climate.detailedAirTemperatureAt(cell))
                val eventRate = max(0.05, saturation / 6.0)
                val wet = min(0.35, amount * 3600 / (phaseSeconds * eventRate)).coerceAtLeast(1e-9)
                finePeakRain[cell] = amount * 3600 / (phaseSeconds * wet)
                val snow = if (atmosphere.surfaceThermalState[fineCell] - 273.15 < 0.0) 1.0 else 0.0
                surface.receiveClimateRain(cell, amount, wet, snow)
                rainfall += amount * fineConfig.cellArea
            }
            // Integrate the funded rain and drainage once. Energy exchange already
            // paid for snowmelt; there is no additional degree-day source here.
            for (cell in 0 until fineCount)
                surface.setCellTemperature(cell, atmosphere.surfaceThermalState[cell] - 273.15)
            surface.finishClimateStep(phaseSeconds, energyLimitedSnowMelt = true, totals = fluxTotals)
            climate.finishExchange(phaseSeconds)
            saturationReady = false

            // Publish the actual subgrid chronology diagnostics.
            for (cell in 0 until fineCount) fineTemperatureAfter[cell] = atmosphere.surfaceThermalState[cell] - 273.15
            for (cell in 0 until fineCount) {
                temperatureIntegral[cell] = (fineOldTemperature[cell] + fineTemperatureAfter[cell]) / 2 * phaseSeconds
                minimumTemperature[cell] = min(minimumTemperature[cell], fineTemperatureAfter[cell])
                maximumTemperature[cell] = max(maximumTemperature[cell], fineTemperatureAfter[cell])
                val coarse = grid.overlap.targetOfSource[cell]
                // Publish the same flow used for conservative moisture transport.
                fields[6][cell] = climate.east[coarse] * 86400 * surface.mapping.tileWidth / fineConfig.domainWidthMeters
                fields[7][cell] = -climate.north[coarse] * 86400 * surface.mapping.tileHeight / fineConfig.domainHeightMeters
                fields[8][cell] = climate.detailedAirTemperatureAt(cell) - 273.15
            }

            for (tile in soilExposureScratch.indices)
                soilExposureScratch[tile] += (surface.soil[tile] / 120.0).coerceIn(0.0, 1.0) * phaseSeconds
            soilExposureSeconds += phaseSeconds
            history.observePhase(phaseStart, phaseSeconds, temperatureIntegral, rain,
                minimumTemperature, maximumTemperature, finePeakRain)
            if (floor((history.elapsedSeconds + 1e-6) / solar.yearSeconds) > floor((phaseStart + 1e-6) / solar.yearSeconds)) {
                // Ecology sees the year's moisture exposure, not just winter's last bucket.
                if (history.snapshot != null) {
                    for (tile in soilExposureScratch.indices) soilExposureScratch[tile] /= soilExposureSeconds
                    evolveEcology(soilExposureSeconds, soilExposureScratch)
                }
                soilExposureScratch.fill(0.0)
                soilExposureSeconds = 0.0
            }
            // Diagnose humidity AFTER transport, rainout, and temperature evolution;
            // the value computed before the phase is stale at publication time.
            computeFineHumidity()
            // Representative cloud and humidity depend on phase-mean moisture and rain rate.
            for (cell in 0 until fineCount) {
                val humidity = fineHumidity[cell]
                val weight = phaseSeconds / seconds
                representativeHumidity[cell] += humidity * weight
                representativeCloud[cell] += ColumnThermodynamics.cloudMm(atmosphere.effectiveMoistureMmAt(cell), fineSaturation[cell]) * weight
            }
            phaseClock += phaseSeconds
        }

        // The energy interval is one aggregate diagnostic; the approximation is named.
        val after = atmosphere.atmosphericWaterKg + surface.totalWaterKg
        val boundary = surface.exportedWaterKg - boundaryBefore
        val residual = (after - before + boundary) / max(1.0, abs(before))
        check(residual.isFinite() && abs(residual) < 1e-10) { "Climate water transaction did not reconcile: $residual before=$before after=$after" }
        totalMoistureResidual = max(totalMoistureResidual, moistureResidual)
        completedSeconds = history.elapsedSeconds
        val diagnostics = ClimateIntervalDiagnostics(start, seconds, plan.phases.size, before, after, residual,
            evaporated, rainfall, atmosphere.completedSteps,
            boundaryExportKg = boundary, ecologyEligibleSeconds = seconds,
            absorbedShortwaveJoules = climate.shortwaveJoules,
            netSurfaceHeatLossJoules = climate.outgoingJoules,
            latentHeatJoules = climate.latentJoules,
            convectiveHeatExportJoules = rainfall * atmosphere.physics.latentHeatJkg,
            snowMeltEnergyJoules = climate.snowMeltJoules,
            potentialEvaporationMm = climate.potentialMm,
            actualEvaporationMm = climate.actualMm,
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
        // Dry carrier mass is dormant in climate mode. Air changes only after the
        // condensation/mixing stage, so all intra-phase water updates share capacity.
        if (!saturationReady) {
            for (cell in 0 until fineCount)
                fineSaturation[cell] = atmosphere.saturatedVaporMmAt(cell, climate.detailedAirTemperatureAt(cell))
            saturationReady = true
        }
        for (cell in 0 until fineCount)
            fineHumidity[cell] = (atmosphere.effectiveMoistureMmAt(cell) / max(1e-12, fineSaturation[cell])).coerceIn(0.0, 1.0)
    }

    companion object {
        /** Diagnostic rain above 2 mm/h, not an Earth-calibrated infiltration cutoff. */
        const val INTENSE_RAIN_MM_PER_HOUR = 2.0
        const val MIN_INTERVAL_SECONDS = 1.0
        const val DAY_SECONDS = 86400.0
    }
}
