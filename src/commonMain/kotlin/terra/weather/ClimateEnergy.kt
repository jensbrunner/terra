package terra.weather

import terra.atmosphere.MoistAtmosphere
import terra.atmosphere.MoistPhysicsConfig
import terra.atmosphere.PlanetLatitude
import terra.atmosphere.SolarConfig
import terra.atmosphere.SurfaceThermalState
import terra.atmosphere.FastSaturation
import terra.atmosphere.ColumnThermodynamics
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bounded ocean heat-transport closures available to the reduced climate. */
enum class OceanTransportMode { LATERAL_MIXING, BASIN_TRANSPORT }

/**
 * Time-integrated zonal energy budget on the fine atmosphere rows (row 0 is the
 * southern edge). Review diagnostic only; it never feeds back into the model.
 */
internal class ClimateRowBudget(val rows: Int, val airRows: Int = rows) {
    /** Air-layer budget on the coarse climate rows (W/m² × s). */
    val airRadiativeGain = DoubleArray(airRows)
    val airSensibleGain = DoubleArray(airRows)
    val airCondensationGain = DoubleArray(airRows)
    val airRadiativeLoss = DoubleArray(airRows)
    val airTransport = DoubleArray(airRows)
    val airDown = DoubleArray(airRows)
    val airUp = DoubleArray(airRows)
    val airDownFine = DoubleArray(airRows)
    val airFineWeight = DoubleArray(airRows)
    val airWeight = DoubleArray(airRows)
    val shortwave = DoubleArray(rows)
    val atmosphereShortwave = DoubleArray(rows)
    val longwaveUp = DoubleArray(rows)
    val longwaveDown = DoubleArray(rows)
    val outgoingLongwave = DoubleArray(rows)
    val sensible = DoubleArray(rows)
    val latent = DoubleArray(rows)
    val surfaceK = DoubleArray(rows)
    val airK = DoubleArray(rows)
    val cloud = DoubleArray(rows)
    val topIncoming = DoubleArray(rows)
    val oceanShortwave = DoubleArray(rows)
    val oceanNetLongwave = DoubleArray(rows)
    val oceanSensible = DoubleArray(rows)
    val oceanLatent = DoubleArray(rows)
    val oceanSurfaceK = DoubleArray(rows)
    val oceanBoundaryK = DoubleArray(rows)
    val oceanWeight = DoubleArray(rows)
    val humidity = DoubleArray(rows)
    val waterMm = DoubleArray(rows)
    /** Seconds × cells accumulated per row. */
    val weight = DoubleArray(rows)

    fun reset() {
        for (field in listOf(shortwave, atmosphereShortwave, longwaveUp, longwaveDown, outgoingLongwave, sensible,
            latent, surfaceK, airK, cloud, topIncoming, weight, oceanShortwave, oceanNetLongwave, oceanSensible,
            oceanLatent, oceanSurfaceK, oceanBoundaryK, oceanWeight, humidity, waterMm, airRadiativeGain, airSensibleGain,
            airCondensationGain, airRadiativeLoss, airTransport, airWeight, airDown, airUp, airDownFine, airFineWeight)) field.fill(0.0)
    }

    fun mean(field: DoubleArray, row: Int): Double = if (weight[row] > 0) field[row] / weight[row] else 0.0
}

/** One phase's flux accounting. Internal transfers reconcile; external radiation is explicit. */
internal data class EnergyIntervalDiagnostics(
    val absorbedShortwaveJoules: Double,
    val outgoingLongwaveJoules: Double,
    val sensibleHeatJoules: Double,
    val latentHeatJoules: Double,
    val airHorizontalTransportJoules: Double,
    val oceanHorizontalTransportJoules: Double,
    val snowMeltEnergyJoules: Double,
    val condensationHeatingJoules: Double,
    val surfaceInternalResidualJoules: Double,
    val airInternalResidualJoules: Double,
    val potentialEvaporationMm: Double,
    val actualEvaporationMm: Double
) {
    val externalRadiationJoules: Double get() = absorbedShortwaveJoules - outgoingLongwaveJoules
    val namedResidualJoules: Double get() = surfaceInternalResidualJoules + airInternalResidualJoules
}

/**
 * Reduced seasonal surface/atmosphere energy model: a two-layer column.
 *
 * The surface is a prognostic slab (a thin land layer or a finite ocean mixed layer).
 * The representative free air is a prognostic energy layer on the climate mesh with its
 * own heat capacity. Per phase:
 *
 * - shortwave is reflected by diagnosed cloud cover, partly absorbed in the air, and
 *   the rest reaches the surface albedo;
 * - the air absorbs the surface longwave it is opaque to (a humidity- and cloud-
 *   dependent emissivity), emits downward from the boundary layer and to space from a
 *   colder emission level whose depth grows with column water (water-vapour feedback);
 * - bulk sensible and latent exchange use the near-surface air, a standard transfer
 *   coefficient and the low-level wind plus unresolved synoptic gustiness;
 * - condensation heating from the moisture closure warms the air where it rains;
 * - air heat is advected by the column wind and mixed by baroclinic eddies, which is
 *   the dry part of the poleward energy transport. Moisture transport carries the
 *   latent part.
 *
 * Every air update is implicit in its own temperature, so ten-day phases are stable.
 * There is no relaxation to a geographical temperature prior once initialised.
 */
internal class ClimateEnergyModel(
    private val grid: ClimateGrid,
    private val geography: ClimateGeography,
    private val surface: TerrainMoistSurface,
    private val atmosphere: MoistAtmosphere,
    private val physics: MoistPhysicsConfig,
    private val solar: SolarConfig,
    private val oceanTransport: OceanTransportMode = OceanTransportMode.LATERAL_MIXING
) {
    private val config = grid.config
    private val fineConfig = grid.fine
    private val count = grid.count
    private val fineCount = fineConfig.cellCount
    private val fineTemperature: SurfaceThermalState get() = atmosphere.surfaceThermalState

    /** Representative free-air temperature on the climate mesh, kelvin. */
    val airTemperatureK = DoubleArray(count)
    private val airScratch = DoubleArray(count)
    private val surfaceRestricted = DoubleArray(count)
    private val surfacePressure = DoubleArray(fineCount) {
        101_325.0 * exp(-surface.terrain.meanElevationMeters[it] / 8000.0)
    }
    private val seaIceFraction = surface.seaIceFraction
    private val fineAlbedo = DoubleArray(fineCount)
    private val fineHeatCapacity = DoubleArray(fineCount)
    /** Recent rain rate (mm/day) per fine column; organises convective cloud. */
    private val recentRainMmPerDay = DoubleArray(fineCount)
    private val rowSinLatitudeSquared = DoubleArray(fineConfig.height) { row ->
        val s = kotlin.math.sin(PlanetLatitude.atmosphereRowRadians(row, fineConfig.height)); s * s
    }
    var seaIceJoules = 0.0; private set
    var coldFloorJoules = 0.0; private set

    // Per-phase air sources (J/m², coarse cell mean) and implicit radiative coefficients.
    private val airSource = DoubleArray(count)
    private val airEmissivity = DoubleArray(count)
    private val airEmissionDepth = DoubleArray(count)
    private val airSensibleConductance = DoubleArray(count)
    private val airCellWeight = DoubleArray(count)
    private val eddyDiffusivity = DoubleArray(count)
    private var phaseSecondsForAir = 0.0

    private var initialized = false
    private var lastMidSeconds = 0.0
    private var phasesSinceProperties = 0
    private var waterMaskRevision = -1L

    // Accumulated external and internal energy, joules.
    var absorbedShortwaveJoules = 0.0; private set
    var outgoingLongwaveJoules = 0.0; private set
    var sensibleHeatJoules = 0.0; private set
    var latentHeatJoules = 0.0; private set
    var airTransportJoules = 0.0; private set
    var oceanTransportJoules = 0.0; private set
    var snowMeltJoules = 0.0; private set
    var condensationJoules = 0.0; private set
    var surfaceResidualJoules = 0.0; private set
    var airResidualJoules = 0.0; private set
    private var phaseEvaporationMm = 0.0
    private var phasePotentialMm = 0.0
    val rowBudget = ClimateRowBudget(fineConfig.height, config.height)
    private val airRadiativeScratch = DoubleArray(count)
    private val airSensibleScratch = DoubleArray(count)
    private val airCondensationScratch = DoubleArray(count)

    fun initialize(priorSurfaceC: (Int) -> Double) {
        if (initialized) return
        // Keep any existing detailed surface state. The prior is a fallback for cells
        // that have never been integrated, not an override of observed thermal history.
        for (cell in 0 until fineCount) {
            val existing = fineTemperature[cell]
            if (!existing.isFinite() || existing <= 0.0) {
                fineTemperature[cell] = (priorSurfaceC(cell) + 273.15).coerceIn(190.0, 340.0)
            }
        }
        // Representative air temperature starts from the detailed column when available;
        // a prior lapse profile is only the fallback for an uninitialised column.
        val airFine = DoubleArray(fineCount) { atmosphere.temperatureKelvinAt(it) }
        grid.restrict(airFine, airTemperatureK)
        grid.restrict(DoubleArray(fineCount) { fineTemperature[it] - 273.15 }, surfaceRestricted)
        for (cell in 0 until count) {
            if (!airTemperatureK[cell].isFinite() || airTemperatureK[cell] <= 0.0) {
                val elevationLapse = ColumnThermodynamics.LAPSE_K_PER_METER * geography.elevationMeters[cell]
                airTemperatureK[cell] = (surfaceRestricted[cell] + 273.15 + ACTIVE_AIR_OFFSET_K -
                    elevationLapse).coerceIn(180.0, 340.0)
            }
        }
        initialized = true
    }

    fun beginPhase(midSeconds: Double) {
        require(initialized) { "Energy model must be initialized before advancing" }
        lastMidSeconds = midSeconds
        phaseEvaporationMm = 0.0
        phasePotentialMm = 0.0
        // Surface albedo and heat capacity change slowly; refresh every few phases.
        if (phasesSinceProperties % 3 == 0 || waterMaskRevision != surface.waterMaskRevision) {
            waterMaskRevision = surface.waterMaskRevision
            surface.fillThermalProperties(fineAlbedo, fineHeatCapacity)
        }
        phasesSinceProperties++
    }

    /**
     * Exchange heat and water over a phase fragment. The surface owner withdraws the
     * actual evaporation; this routine pays its latent heat and collects the air's
     * radiative and sensible sources for the implicit air update in [mix].
     */
    fun exchange(
        seconds: Double,
        midSeconds: Double,
        circulation: CirculationState,
        relativeHumidityAt: (Int) -> Double,
        meltSnowThisExchange: Boolean = true
    ): DoubleArray {
        require(seconds.isFinite() && seconds > 0.0)
        val evaporation = DoubleArray(fineCount)
        var absorbed = 0.0
        var outgoing = 0.0
        var sensibleTotal = 0.0
        var latentTotal = 0.0
        airSource.fill(0.0); airEmissivity.fill(0.0); airEmissionDepth.fill(0.0)
        airSensibleConductance.fill(0.0); airCellWeight.fill(0.0)
        phaseSecondsForAir = seconds

        val insolationFraction = ((midSeconds / solar.yearSeconds + solar.initialYearFraction) % 1.0)
        val tilt = solar.axialTiltDegrees
        val sun = DoubleArray(fineConfig.height) { row ->
            val latitude = PlanetLatitude.atmosphereRowRadians(row, fineConfig.height)
            ClimateInsolation.dailyMean(insolationFraction, latitude, tilt)
        }

        for (cell in 0 until fineCount) {
            val coarse = grid.overlap.targetOfSource[cell]
            val air = detailedAirTemperatureAt(cell)
            val boundary = air + BOUNDARY_LAYER_OFFSET_K
            val surfaceTemperature = fineTemperature[cell]
            val land = surface.landFraction[cell]
            val ice = seaIceFraction[cell]
            // Open water reflects more at the low sun angles of high latitudes (Briegleb-like).
            val waterAlbedo = OCEAN_ALBEDO + OCEAN_GRAZING_ALBEDO * rowSinLatitudeSquared[cell / fineConfig.width]
            val albedo = if (land < 0.5) waterAlbedo * (1 - ice) + SEA_ICE_ALBEDO * ice else fineAlbedo[cell]
            val relativeHumidity = relativeHumidityAt(cell).coerceIn(0.0, 1.0)
            val columnWaterCm = (atmosphere.effectiveMoistureMmAt(cell) / 10.0).coerceIn(0.0, 8.0)
            val cloud = cloudFraction(relativeHumidity, recentRainMmPerDay[cell])

            // Shortwave: cloud reflection, clear-air absorption, surface albedo.
            val incomingSolar = physics.solarPeakWm2 * sun[cell / fineConfig.width]
            val transmitted = incomingSolar * (1 - CLOUD_ALBEDO * cloud)
            val atmosphereShortwave = transmitted * (AIR_SHORTWAVE_ABSORPTION + CLOUD_SHORTWAVE_ABSORPTION * cloud)
            val shortwave = (transmitted - atmosphereShortwave) * (1 - albedo)

            // Longwave: grey air whose emissivity follows the near-surface vapour pressure
            // through Prata's (1996) precipitable-water proxy, plus cloud.
            val boundaryHumidity = (if (land > 0.5) LAND_BOUNDARY_HUMIDITY_BASE + LAND_BOUNDARY_HUMIDITY_SLOPE * relativeHumidity
                else OCEAN_BOUNDARY_HUMIDITY_BASE + OCEAN_BOUNDARY_HUMIDITY_SLOPE * relativeHumidity).coerceIn(0.0, 0.98)
            val boundaryKelvin = boundary.coerceIn(190.0, 345.0)
            val screenWaterCm = PRATA_WATER_CM_PER_HPA_K * boundaryHumidity *
                FastSaturation.vaporPressure(boundaryKelvin) / 100.0 / boundaryKelvin
            val clear = clearSkyEmissivity(screenWaterCm)
            val emissivity = (clear + (1 - clear) * CLOUD_LONGWAVE_EMISSIVITY * cloud).coerceIn(0.3, 0.995)
            val airEmission = emissivity * STEFAN_BOLTZMANN * boundary * boundary * boundary * boundary
            val surfaceEmission = SURFACE_EMISSIVITY * STEFAN_BOLTZMANN *
                surfaceTemperature * surfaceTemperature * surfaceTemperature * surfaceTemperature
            val emissionDepth = EMISSION_DEPTH_BASE_K +
                EMISSION_DEPTH_PER_LOG_WATER_K * ln(max(1.0, screenWaterCm / EMISSION_DEPTH_REFERENCE_CM)) +
                EMISSION_DEPTH_CLOUD_K * cloud

            val gust = if (land > 0.5) LAND_GUST_METERS_PER_SECOND else OCEAN_GUST_METERS_PER_SECOND
            val windSpeed = sqrt(
                circulation.lowEast[coarse] * circulation.lowEast[coarse] +
                    circulation.lowNorth[coarse] * circulation.lowNorth[coarse] + gust * gust
            )
            val transfer = AIR_DENSITY * TRANSFER_COEFFICIENT * windSpeed *
                (if (land > 0.5) LAND_ROUGHNESS_FACTOR else 1.0)
            val exchangeCoefficient = transfer * 1004.0
            val sensible = exchangeCoefficient * (surfaceTemperature - boundary)

            // Potential evaporation from the near-surface vapour deficit, capped by the
            // radiative energy and a bounded draw on the slab's stored heat.
            val pressure = surfacePressure[cell]
            val vaporSaturation = FastSaturation.mixingRatio(surfaceTemperature.coerceIn(190.0, 345.0), pressure)
            // Marine boundary layers stay near 80 % humidity; land air dries with the column.
            val specificHumidity = boundaryHumidity *
                FastSaturation.mixingRatio(boundary.coerceIn(190.0, 345.0), pressure)
            val bulkPotential = transfer * max(0.0, vaporSaturation - specificHumidity) * seconds
            val netRadiative = shortwave + airEmission - surfaceEmission - sensible
            val energyLimit = max(0.0, netRadiative) * seconds / LATENT_HEAT +
                max(0.0, (surfaceTemperature - 273.15)) * SURFACE_HEAT_CAPACITY_LIMIT_FRACTION *
                    fineHeatCapacity[cell] / LATENT_HEAT
            val potential = min(bulkPotential, energyLimit).coerceIn(0.0, MAXIMUM_EXCHANGE_MM)
            val actual = surface.evaporate(cell, potential)
            val latent = actual * LATENT_HEAT / seconds
            // Solar and atmospheric energy can melt snow even when the surface would
            // otherwise remain below freezing. This prevents a permanent snow-albedo lock.
            val meltLatent = if (meltSnowThisExchange && surface.snowMmInCell(cell) > 0.0) {
                val meltEnergy = max(0.0, shortwave + airEmission - surfaceEmission) * seconds * SNOW_MELT_ENERGY_FRACTION
                val meltMm = surface.meltSnowEnergyMm(cell, meltEnergy)
                snowMeltJoules += meltMm * LATENT_HEAT_FUSION * fineConfig.cellArea
                meltMm * LATENT_HEAT_FUSION / seconds
            } else 0.0

            // The surface relaxes toward its own equilibrium with the current air.
            var surfaceEquilibrium = surfaceTemperature
            repeat(EQUILIBRIUM_ITERATIONS) {
                val residual = SURFACE_EMISSIVITY * STEFAN_BOLTZMANN *
                    surfaceEquilibrium * surfaceEquilibrium * surfaceEquilibrium * surfaceEquilibrium +
                    exchangeCoefficient * (surfaceEquilibrium - boundary) + latent + meltLatent -
                    shortwave - airEmission
                val derivative = 4 * SURFACE_EMISSIVITY * STEFAN_BOLTZMANN *
                    surfaceEquilibrium * surfaceEquilibrium * surfaceEquilibrium + exchangeCoefficient
                surfaceEquilibrium = (surfaceEquilibrium - residual / max(1e-9, derivative))
                    .coerceIn(150.0, 350.0)
            }
            val surfaceFeedback = 4 * SURFACE_EMISSIVITY * STEFAN_BOLTZMANN *
                surfaceEquilibrium * surfaceEquilibrium * surfaceEquilibrium + exchangeCoefficient
            val surfaceResponse = -expm1(-surfaceFeedback * seconds / max(1e-9, fineHeatCapacity[cell]))
            var nextTemperature = surfaceTemperature + (surfaceEquilibrium - surfaceTemperature) * surfaceResponse
            if (land < 0.5) {
                // A simple slab sea-ice buffer: the ocean cannot cool below freezing
                // without first growing ice. The phase-change heat is reported.
                if (nextTemperature < FREEZING_KELVIN) {
                    val energy = (FREEZING_KELVIN - nextTemperature) * fineHeatCapacity[cell]
                    val iceGain = (energy / (LATENT_HEAT_FUSION * SEA_ICE_MASS_PER_M2)).coerceIn(0.0, 1.0 - ice)
                    seaIceFraction[cell] = ice + iceGain
                    val phaseEnergy = iceGain * LATENT_HEAT_FUSION * SEA_ICE_MASS_PER_M2
                    seaIceJoules += phaseEnergy * fineConfig.cellArea
                    nextTemperature += phaseEnergy / fineHeatCapacity[cell]
                } else if (nextTemperature > FREEZING_KELVIN && ice > 0.0) {
                    val energy = (nextTemperature - FREEZING_KELVIN) * fineHeatCapacity[cell]
                    val iceLoss = (energy / (LATENT_HEAT_FUSION * SEA_ICE_MASS_PER_M2)).coerceIn(0.0, ice)
                    seaIceFraction[cell] = ice - iceLoss
                    val phaseEnergy = iceLoss * LATENT_HEAT_FUSION * SEA_ICE_MASS_PER_M2
                    seaIceJoules -= phaseEnergy * fineConfig.cellArea
                    nextTemperature -= phaseEnergy / fineHeatCapacity[cell]
                }
            } else if (nextTemperature < LAND_TEMPERATURE_FLOOR_KELVIN) {
                coldFloorJoules += (LAND_TEMPERATURE_FLOOR_KELVIN - nextTemperature) * fineHeatCapacity[cell] * fineConfig.cellArea
                nextTemperature = LAND_TEMPERATURE_FLOOR_KELVIN
            }
            fineTemperature[cell] = nextTemperature.coerceIn(180.0, 345.0)

            // Air sources from this column, evaluated with the phase-mean surface state.
            val meanSurface = 0.5 * (surfaceTemperature + fineTemperature[cell])
            val meanEmission = SURFACE_EMISSIVITY * STEFAN_BOLTZMANN * meanSurface * meanSurface * meanSurface * meanSurface
            airSource[coarse] += (atmosphereShortwave + emissivity * meanEmission +
                exchangeCoefficient * (meanSurface - BOUNDARY_LAYER_OFFSET_K)) * seconds
            airSensibleConductance[coarse] += exchangeCoefficient
            rowBudget.airDownFine[coarse / config.width] += airEmission * seconds
            rowBudget.airFineWeight[coarse / config.width] += seconds
            airRadiativeScratch[coarse] += (atmosphereShortwave + emissivity * meanEmission) * seconds
            airSensibleScratch[coarse] += exchangeCoefficient * (meanSurface - boundary) * seconds
            airEmissivity[coarse] += emissivity
            airEmissionDepth[coarse] += emissionDepth
            airCellWeight[coarse] += 1.0

            val emissionTemperature = air - emissionDepth
            val topLongwave = (1 - emissivity) * meanEmission + emissivity * STEFAN_BOLTZMANN *
                emissionTemperature * emissionTemperature * emissionTemperature * emissionTemperature
            val meanSensible = exchangeCoefficient * (meanSurface - boundary)

            val row = cell / fineConfig.width
            rowBudget.shortwave[row] += shortwave * seconds
            rowBudget.atmosphereShortwave[row] += atmosphereShortwave * seconds
            rowBudget.longwaveUp[row] += meanEmission * seconds
            rowBudget.longwaveDown[row] += airEmission * seconds
            rowBudget.outgoingLongwave[row] += topLongwave * seconds
            rowBudget.sensible[row] += meanSensible * seconds
            rowBudget.latent[row] += latent * seconds
            rowBudget.surfaceK[row] += surfaceTemperature * seconds
            rowBudget.airK[row] += air * seconds
            rowBudget.cloud[row] += cloud * seconds
            rowBudget.topIncoming[row] += incomingSolar * seconds
            rowBudget.weight[row] += seconds
            rowBudget.humidity[row] += relativeHumidity * seconds
            rowBudget.waterMm[row] += columnWaterCm * 10 * seconds
            if (land < 0.5) {
                rowBudget.oceanShortwave[row] += shortwave * seconds
                rowBudget.oceanNetLongwave[row] += (meanEmission - airEmission) * seconds
                rowBudget.oceanSensible[row] += meanSensible * seconds
                rowBudget.oceanLatent[row] += latent * seconds
                rowBudget.oceanSurfaceK[row] += surfaceTemperature * seconds
                rowBudget.oceanBoundaryK[row] += boundary * seconds
                rowBudget.oceanWeight[row] += seconds
            }
            evaporation[cell] = actual
            phasePotentialMm += potential
            phaseEvaporationMm += actual
            absorbed += (shortwave + atmosphereShortwave) * seconds
            outgoing += topLongwave * seconds
            sensibleTotal += meanSensible * seconds
            latentTotal += latent * seconds
        }
        for (coarse in 0 until count) {
            val n = airCellWeight[coarse]
            if (n <= 0.0) continue
            airSource[coarse] /= n
            airSensibleConductance[coarse] /= n
            airEmissivity[coarse] /= n
            airEmissionDepth[coarse] /= n
        }

        absorbedShortwaveJoules += absorbed * fineConfig.cellArea
        outgoingLongwaveJoules += outgoing * fineConfig.cellArea
        sensibleHeatJoules += sensibleTotal * fineConfig.cellArea
        latentHeatJoules += latentTotal * fineConfig.cellArea
        return evaporation
    }

    /** Condensation heating is released in the air column, not at the surface. */
    fun addCondensationHeat(rainMmFine: DoubleArray, seconds: Double) {
        require(seconds > 0.0)
        val heat = DoubleArray(count)
        val weight = DoubleArray(count)
        for (cell in 0 until rainMmFine.size) {
            val coarse = grid.overlap.targetOfSource[cell]
            heat[coarse] += rainMmFine[cell] * LATENT_HEAT
            weight[coarse] += 1.0
            recentRainMmPerDay[cell] = rainMmFine[cell] * 86400.0 / seconds
        }
        for (coarse in 0 until count) {
            if (weight[coarse] <= 0.0) continue
            val joules = heat[coarse] / weight[coarse]
            airSource[coarse] += joules
            airCondensationScratch[coarse] += joules
            condensationJoules += joules * config.cellArea
        }
    }

    /**
     * Implicit air energy update from the phase's sources, then conservative horizontal
     * transport of air heat and ocean mixed-layer heat.
     */
    fun mix(seconds: Double, circulation: CirculationState) {
        require(seconds > 0.0)
        val dt = if (phaseSecondsForAir > 0.0) phaseSecondsForAir else seconds
        for (cell in 0 until count) {
            if (airCellWeight[cell] <= 0.0) continue
            val t = airTemperatureK[cell]
            val emissivity = airEmissivity[cell]
            val boundary = t + BOUNDARY_LAYER_OFFSET_K
            val top = t - airEmissionDepth[cell]
            // Sinks: downward emission, emission to space and sensible uptake's Ta part.
            val sink = emissivity * STEFAN_BOLTZMANN * (boundary * boundary * boundary * boundary +
                top * top * top * top) + airSensibleConductance[cell] * t
            val derivative = 4 * emissivity * STEFAN_BOLTZMANN * (boundary * boundary * boundary +
                top * top * top) + airSensibleConductance[cell]
            val tendency = airSource[cell] - sink * dt
            airTemperatureK[cell] = (t + tendency / (AIR_HEAT_CAPACITY + derivative * dt)).coerceIn(180.0, 340.0)
            val n = airCellWeight[cell]
            val row = cell / config.width
            val gainRadiative = airRadiativeScratch[cell] / n
            val gainSensible = airSensibleScratch[cell] / n
            val gainCondensation = airCondensationScratch[cell]
            rowBudget.airRadiativeGain[row] += gainRadiative
            rowBudget.airSensibleGain[row] += gainSensible
            rowBudget.airCondensationGain[row] += gainCondensation
            rowBudget.airRadiativeLoss[row] += gainRadiative + gainSensible + gainCondensation -
                AIR_HEAT_CAPACITY * (airTemperatureK[cell] - t)
            rowBudget.airWeight[row] += dt
            rowBudget.airDown[row] += emissivity * STEFAN_BOLTZMANN * boundary * boundary * boundary * boundary * dt
            rowBudget.airUp[row] += emissivity * STEFAN_BOLTZMANN * top * top * top * top * dt
        }
        airRadiativeScratch.fill(0.0); airSensibleScratch.fill(0.0); airCondensationScratch.fill(0.0)
        val beforeTransport = airTemperatureK.copyOf()
        airSource.fill(0.0)
        phaseSecondsForAir = 0.0

        transportAir(seconds, circulation)
        for (cell in 0 until count) rowBudget.airTransport[cell / config.width] +=
            AIR_HEAT_CAPACITY * (airTemperatureK[cell] - beforeTransport[cell])

        // Ocean mixed-layer heat redistribution. Only same-basin neighbours exchange;
        // no global teleportation or prescribed western-coast warming is introduced.
        grid.restrict(DoubleArray(fineCount) { fineTemperature[it] - 273.15 }, surfaceRestricted)
        val targetOcean = DoubleArray(count)
        val fraction = 1 - exp(-seconds / OCEAN_MIXING_SECONDS)
        val basinSum = if (oceanTransport == OceanTransportMode.BASIN_TRANSPORT)
            DoubleArray((geography.oceanBasin.maxOrNull() ?: -1) + 1) else DoubleArray(0)
        val basinCount = IntArray(basinSum.size)
        if (oceanTransport == OceanTransportMode.BASIN_TRANSPORT) {
            for (cell in 0 until count) {
                val basin = geography.oceanBasin[cell]
                if (basin >= 0) { basinSum[basin] += surfaceRestricted[cell]; basinCount[basin]++ }
            }
        }
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val cell = y * config.width + x
            if (!geography.isOcean(cell)) { targetOcean[cell] = surfaceRestricted[cell]; continue }
            val basin = geography.oceanBasin[cell]
            var sum = 0.0
            var weight = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val ny = (y + dy).coerceIn(0, config.height - 1)
                val nx = (x + dx + config.width) % config.width
                val n = ny * config.width + nx
                if (geography.isOcean(n) && geography.oceanBasin[n] == basin) { sum += surfaceRestricted[n]; weight += 1.0 }
            }
            targetOcean[cell] = if (weight > 0) surfaceRestricted[cell] +
                (sum / weight - surfaceRestricted[cell]) * fraction * OCEAN_MIXING_FRACTION else surfaceRestricted[cell]
            if (oceanTransport == OceanTransportMode.BASIN_TRANSPORT && basin >= 0 && basinCount[basin] > 0) {
                // Basin-scale transport redistributes heat along connected water. Smaller
                // basins mix faster; large basins retain more interior/coastal contrast.
                val basinEfficiency = (0.35 / (1.0 + basinCount[basin] / 8000.0)).coerceIn(0.04, 0.25)
                targetOcean[cell] += (basinSum[basin] / basinCount[basin] - targetOcean[cell]) *
                    fraction * basinEfficiency
            }
        }
        for (cell in 0 until fineCount) {
            val coarse = grid.overlap.targetOfSource[cell]
            val delta = targetOcean[coarse] - surfaceRestricted[coarse]
            if (geography.isOcean(coarse)) {
                oceanTransportJoules += delta * fineHeatCapacity[cell] * fineConfig.cellArea
                fineTemperature[cell] = (fineTemperature[cell] + delta).coerceIn(180.0, 345.0)
            }
        }
    }

    /**
     * Flux-form upwind advection of air heat by the column wind plus down-gradient eddy
     * mixing. Eddy mixing is strongest where the meridional temperature gradient and
     * rotation support baroclinic eddies, and weak in the tropics where gradients are
     * flattened instead. Both terms conserve column heat exactly.
     */
    private fun transportAir(seconds: Double, circulation: CirculationState) {
        val width = config.width
        val height = config.height
        val dx = config.dx
        val dy = config.dy
        var maximumSpeed = 0.0
        for (cell in 0 until count) {
            maximumSpeed = max(maximumSpeed, sqrt(circulation.east[cell] * circulation.east[cell] +
                circulation.north[cell] * circulation.north[cell]))
            val y = cell / width
            val f = kotlin.math.abs(config.coriolisAtRow(y))
            val rotation = (f / (f + EDDY_CORIOLIS_HALF)).coerceIn(0.0, 1.0)
            eddyDiffusivity[cell] = EDDY_HEAT_DIFFUSIVITY_TROPICS +
                (EDDY_HEAT_DIFFUSIVITY_EXTRATROPICS - EDDY_HEAT_DIFFUSIVITY_TROPICS) * rotation
        }
        val maximumKappa = eddyDiffusivity.maxOrNull() ?: 0.0
        val courant = maximumSpeed * seconds * (1 / dx + 1 / dy) +
            maximumKappa * seconds * 2 * (1 / (dx * dx) + 1 / (dy * dy))
        val steps = kotlin.math.ceil(courant / 0.8).toInt().coerceIn(1, 600)
        val step = seconds / steps
        var before = 0.0
        for (value in airTemperatureK) before += value
        repeat(steps) {
            airTemperatureK.copyInto(airScratch)
            // Advective (u·∇T) upwind form: the diagnosed column wind is only approximately
            // non-divergent, and a flux form would turn that residual into T·∇·u heating.
            for (y in 0 until height) for (x in 0 until width) {
                val cell = y * width + x
                val u = circulation.east[cell]
                val v = circulation.north[cell]
                val t = airTemperatureK[cell]
                val upwindX = if (u > 0) t - airTemperatureK[y * width + (x + width - 1) % width]
                    else airTemperatureK[y * width + (x + 1) % width] - t
                val upwindY = if (v > 0) (if (y > 0) t - airTemperatureK[cell - width] else 0.0)
                    else (if (y < height - 1) airTemperatureK[cell + width] - t else 0.0)
                airScratch[cell] -= step * (u * upwindX / dx + v * upwindY / dy)
            }
            // Down-gradient eddy mixing in conservative flux form.
            for (y in 0 until height) for (x in 0 until width) {
                val cell = y * width + x
                val east = y * width + (x + 1) % width
                val kappa = 0.5 * (eddyDiffusivity[cell] + eddyDiffusivity[east])
                val flux = -kappa * (airTemperatureK[east] - airTemperatureK[cell]) / dx
                airScratch[cell] -= flux * step / dx
                airScratch[east] += flux * step / dx
            }
            for (y in 0 until height - 1) for (x in 0 until width) {
                val cell = y * width + x
                val north = cell + width
                val kappa = 0.5 * (eddyDiffusivity[cell] + eddyDiffusivity[north])
                val flux = -kappa * (airTemperatureK[north] - airTemperatureK[cell]) / dy
                airScratch[cell] -= flux * step / dy
                airScratch[north] += flux * step / dy
            }
            airScratch.copyInto(airTemperatureK)
        }
        var after = 0.0
        for (cell in 0 until count) {
            airTemperatureK[cell] = airTemperatureK[cell].coerceIn(180.0, 340.0)
            after += airTemperatureK[cell]
        }
        // Flux form: transport is internal; only the bounds clamp can change the total.
        airResidualJoules += (after - before) * AIR_HEAT_CAPACITY * config.cellArea
    }

    fun endPhaseDiagnostics(seconds: Double): EnergyIntervalDiagnostics {
        val beforeSurfaceResidual = surfaceResidualJoules
        val beforeAirResidual = airResidualJoules
        surfaceResidualJoules = 0.0
        airResidualJoules = 0.0
        return EnergyIntervalDiagnostics(
            absorbedShortwaveJoules, outgoingLongwaveJoules, sensibleHeatJoules, latentHeatJoules,
            airTransportJoules, oceanTransportJoules, snowMeltJoules, condensationJoules,
            beforeSurfaceResidual, beforeAirResidual, phasePotentialMm, phaseEvaporationMm
        )
    }

    fun resetIntervalTotals() {
        absorbedShortwaveJoules = 0.0; outgoingLongwaveJoules = 0.0
        sensibleHeatJoules = 0.0; latentHeatJoules = 0.0
        airTransportJoules = 0.0; oceanTransportJoules = 0.0
        snowMeltJoules = 0.0; condensationJoules = 0.0
        phaseEvaporationMm = 0.0; phasePotentialMm = 0.0
    }

    fun airTemperatureAt(cell: Int): Double = airTemperatureK[cell]

    /** Fine-grid air at the same elevation convention used by exchange(). */
    fun detailedAirTemperatureAt(fineCell: Int): Double {
        val coarse = grid.overlap.targetOfSource[fineCell]
        return ColumnThermodynamics.airAtElevation(airTemperatureK[coarse],
            surface.terrain.meanElevationMeters[fineCell], geography.elevationMeters[coarse])
    }

    /** The evolved climate-air field is handed into the resolved physics unchanged. */
    fun detailedAirTemperatureKelvin(): DoubleArray =
        DoubleArray(fineCount) { detailedAirTemperatureAt(it) }

    fun potentialEvaporationMm(): Double = phasePotentialMm
    fun actualEvaporationMm(): Double = phaseEvaporationMm

    companion object {
        const val STEFAN_BOLTZMANN = 5.670374419e-8
        const val SURFACE_EMISSIVITY = 0.97
        const val AIR_DENSITY = 1.2
        /** Bulk transfer coefficient for heat and vapour over open water (Cd≈Ch≈Ce). */
        const val TRANSFER_COEFFICIENT = 1.0e-3
        const val LAND_ROUGHNESS_FACTOR = 1.4
        /** Unresolved synoptic and diurnal wind variance absent from a time-mean flow. */
        const val OCEAN_GUST_METERS_PER_SECOND = 3.0
        const val LAND_GUST_METERS_PER_SECOND = 4.0
        /** Near-surface air relative to the representative free air (the active level). */
        const val BOUNDARY_LAYER_OFFSET_K = 8.0
        const val OCEAN_BOUNDARY_HUMIDITY_BASE = 0.72
        const val OCEAN_BOUNDARY_HUMIDITY_SLOPE = 0.2
        const val LAND_BOUNDARY_HUMIDITY_BASE = 0.45
        const val LAND_BOUNDARY_HUMIDITY_SLOPE = 0.4
        const val CLOUD_ALBEDO = 0.5
        const val AIR_SHORTWAVE_ABSORPTION = 0.21
        const val CLOUD_SHORTWAVE_ABSORPTION = 0.05
        const val CLOUD_LONGWAVE_EMISSIVITY = 0.85
        const val EMISSION_DEPTH_BASE_K = 33.0
        const val EMISSION_DEPTH_PER_LOG_WATER_K = 6.0
        const val EMISSION_DEPTH_REFERENCE_CM = 0.25
        const val PRATA_WATER_CM_PER_HPA_K = 46.5
        const val EMISSION_DEPTH_CLOUD_K = 6.0
        const val FREEZING_KELVIN = 271.15
        const val LATENT_HEAT_FUSION = 334_000.0
        const val SNOW_MELT_ENERGY_FRACTION = 0.35
        const val SEA_ICE_MASS_PER_M2 = 2000.0
        const val SEA_ICE_ALBEDO = 0.55
        const val OCEAN_ALBEDO = 0.06
        const val OCEAN_GRAZING_ALBEDO = 0.2
        const val LAND_TEMPERATURE_FLOOR_KELVIN = 208.15
        const val EQUILIBRIUM_ITERATIONS = 2
        const val LATENT_HEAT = 2_500_000.0
        const val ACTIVE_AIR_OFFSET_K = -7.0
        const val AIR_HEAT_CAPACITY = 1.0e7
        /** Eddy heat diffusivity, m²/s: weak in the tropics, baroclinic in mid-latitudes. */
        const val EDDY_HEAT_DIFFUSIVITY_TROPICS = 0.6e5
        const val EDDY_HEAT_DIFFUSIVITY_EXTRATROPICS = 1.5e5
        const val EDDY_CORIOLIS_HALF = 4.0e-5
        const val OCEAN_MIXING_SECONDS = 200 * 86400.0
        const val OCEAN_MIXING_FRACTION = 0.55
        const val MAXIMUM_EXCHANGE_MM = 80.0
        const val SURFACE_HEAT_CAPACITY_LIMIT_FRACTION = 0.08

        /** Clear-sky effective emissivity from precipitable water in cm (Prata 1996). */
        fun clearSkyEmissivity(waterCm: Double): Double =
            1 - (1 + waterCm) * exp(-sqrt(1.2 + 3 * waterCm))

        /** Cloud cover from column humidity (stratiform) and recent rain (convective). */
        fun cloudFraction(relativeHumidity: Double, rainMmPerDay: Double): Double {
            val stratiform = ((relativeHumidity - CLOUD_HUMIDITY_THRESHOLD) / CLOUD_HUMIDITY_RANGE)
                .coerceIn(0.0, 1.0)
            val convective = 1 - exp(-max(0.0, rainMmPerDay) / CLOUD_RAIN_SCALE_MM_PER_DAY)
            return (1 - (1 - stratiform) * (1 - convective)).coerceIn(0.0, MAXIMUM_CLOUD)
        }

        const val CLOUD_HUMIDITY_THRESHOLD = 0.15
        const val CLOUD_HUMIDITY_RANGE = 0.6
        const val CLOUD_RAIN_SCALE_MM_PER_DAY = 3.0
        const val MAXIMUM_CLOUD = 0.95
    }
}
