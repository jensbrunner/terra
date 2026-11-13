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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bounded ocean heat-transport closures available to the reduced climate. */
enum class OceanTransportMode { LATERAL_MIXING, BASIN_TRANSPORT }

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
 * Reduced seasonal surface/atmosphere energy model.
 *
 * The surface is a prognostic slab: a thin land layer or a finite ocean mixed layer.
 * Representative air temperature is prognostic on the climate mesh and exchanges
 * sensible heat and longwave radiation with the surface. Seasonal solar geometry,
 * albedo, cloud shading and a simple atmospheric emissivity close the budgets.
 *
 * There is no relaxation to a geographical temperature prior once initialised; the
 * prior is used only for initial conditions and remains available to the detailed
 * solver as a fallback while it is dormant.
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
    var seaIceJoules = 0.0; private set
    var coldFloorJoules = 0.0; private set

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
     * actual evaporation; this routine pays its latent heat and warms or cools the air.
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

        // Air temperature at each fine cell: coarse free-air state plus local lapse.
        val insolationFraction = ((midSeconds / solar.yearSeconds + solar.initialYearFraction) % 1.0)
        val tilt = solar.axialTiltDegrees
        val sun = DoubleArray(fineConfig.height) { row ->
            val latitude = PlanetLatitude.atmosphereRowRadians(row, fineConfig.height)
            ClimateInsolation.dailyMean(insolationFraction, latitude, tilt)
        }

        for (cell in 0 until fineCount) {
            val coarse = grid.overlap.targetOfSource[cell]
            val air = detailedAirTemperatureAt(cell)
            val surfaceTemperature = fineTemperature[cell]
            val land = surface.landFraction[cell]
            val ice = seaIceFraction[cell]
            val albedo = if (land < 0.5) OCEAN_ALBEDO * (1 - ice) + SEA_ICE_ALBEDO * ice else fineAlbedo[cell]
            // Legacy calibrated radiative proxy. Changing its meaning to condensate
            // requires retuning the approximate air/surface energy closure together.
            val cloud = atmosphere.effectiveMoistureMmAt(cell)
            val relativeHumidity = relativeHumidityAt(cell).coerceIn(0.0, 1.0)

            val incomingSolar = physics.solarPeakWm2 * sun[cell / fineConfig.width] * SOLAR_SCALE
            val cloudReflection = CLOUD_SHORTWAVE_REFLECTION *
                (if (cloud / CLOUD_OPTICAL_MM >= 38.0) 1.0 else 1 - exp(-cloud / CLOUD_OPTICAL_MM))
            val shortwave = incomingSolar * (1 - albedo) * (1 - cloudReflection)
            val airEmissivity = (CLEAR_SKY_EMISSIVITY + HUMIDITY_EMISSIVITY * relativeHumidity)
                .coerceIn(0.4, 0.98)
            val airEmission = airEmissivity * STEFAN_BOLTZMANN * air * air * air * air
            val surfaceEmission = SURFACE_EMISSIVITY * STEFAN_BOLTZMANN *
                surfaceTemperature * surfaceTemperature * surfaceTemperature * surfaceTemperature

            val windSpeed = sqrt(
                circulation.east[coarse] * circulation.east[coarse] +
                    circulation.north[coarse] * circulation.north[coarse] +
                    GUST_METERS_PER_SECOND * GUST_METERS_PER_SECOND
            )
            val exchangeCoefficient = AIR_DENSITY * 1004.0 * DRAG_COEFFICIENT *
                (if (land > 0.5) windSpeed * LAND_EXCHANGE_FACTOR else windSpeed * OCEAN_EXCHANGE_FACTOR)
            val sensible = exchangeCoefficient * (surfaceTemperature - air)

            // Potential evaporation from the surface water vapour deficit, capped by
            // the energy actually available at the surface this fragment.
            val pressure = surfacePressure[cell]
            val vaporSaturation = FastSaturation.mixingRatio(
                surfaceTemperature.coerceIn(190.0, 345.0), pressure)
            val airSaturation = FastSaturation.mixingRatio(air.coerceIn(190.0, 345.0), pressure)
            val specificHumidity = relativeHumidity * airSaturation
            val bulkPotential = AIR_DENSITY * DRAG_COEFFICIENT * windSpeed *
                max(0.0, vaporSaturation - specificHumidity) * seconds
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

            // Representative air temperature is diagnosed from the evolving surface
            // state in mix(); here the surface relaxes toward its own radiative-
            // convective equilibrium. This keeps the energy model stable at ten-day
            // phases while still allowing geography and season to control the surface.
            var surfaceEquilibrium = surfaceTemperature
            repeat(EQUILIBRIUM_ITERATIONS) {
                val residual = SURFACE_EMISSIVITY * STEFAN_BOLTZMANN *
                    surfaceEquilibrium * surfaceEquilibrium * surfaceEquilibrium * surfaceEquilibrium +
                    exchangeCoefficient * (surfaceEquilibrium - air) + latent + meltLatent -
                    shortwave - airEmissivity * STEFAN_BOLTZMANN * air * air * air * air
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
            evaporation[cell] = actual
            phasePotentialMm += potential
            phaseEvaporationMm += actual
            absorbed += shortwave * seconds
            outgoing += surfaceEmission * seconds
            sensibleTotal += sensible * seconds
            latentTotal += latent * seconds


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
        }
        for (coarse in 0 until count) {
            if (weight[coarse] <= 0.0) continue
            val delta = heat[coarse] / weight[coarse] / AIR_HEAT_CAPACITY
            airTemperatureK[coarse] = (airTemperatureK[coarse] + delta).coerceIn(180.0, 340.0)
            condensationJoules += heat[coarse] * fineConfig.cellArea
        }
    }

    /** Update representative air temperature from the evolved surface, then mix horizontally. */
    fun mix(seconds: Double, circulation: CirculationState) {
        require(seconds > 0.0)
        grid.restrict(DoubleArray(fineCount) { fineTemperature[it] - 273.15 }, surfaceRestricted)
        // A light smoothing represents a well-mixed boundary layer without erasing
        // the land/ocean thermal contrast that drives circulation.
        for (pass in 0 until 2) {
            for (y in 0 until config.height) for (x in 0 until config.width) {
                val cell = y * config.width + x
                val west = y * config.width + (x + config.width - 1) % config.width
                val east = y * config.width + (x + 1) % config.width
                val south = maxOf(0, y - 1) * config.width + x
                val north = minOf(config.height - 1, y + 1) * config.width + x
                airScratch[cell] = 0.5 * surfaceRestricted[cell] + 0.125 *
                    (surfaceRestricted[west] + surfaceRestricted[east] + surfaceRestricted[south] + surfaceRestricted[north])
            }
            airScratch.copyInto(surfaceRestricted)
        }
        for (cell in 0 until count) {
            val target = surfaceRestricted[cell] + 273.15 + ACTIVE_AIR_OFFSET_K -
                ColumnThermodynamics.LAPSE_K_PER_METER * geography.elevationMeters[cell]
            airTemperatureK[cell] = (airTemperatureK[cell] + (target - airTemperatureK[cell]) * AIR_SURFACE_COUPLING)
                .coerceIn(180.0, 340.0)
        }

        // Horizontal advection of the representative air temperature by the column
        // circulation. This remains a bounded, conservative-in-spirit diagnostic.
        airScratch.fill(0.0)
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val cell = y * config.width + x
            val departureX = x - circulation.east[cell] * seconds / config.dx
            val departureY = y - circulation.north[cell] * seconds / config.dy
            airScratch[cell] = sampleAir(airTemperatureK, departureX, departureY)
        }
        var transport = 0.0
        for (cell in 0 until count) transport += (airScratch[cell] - airTemperatureK[cell]) * AIR_HEAT_CAPACITY
        for (cell in 0 until count) {
            airTemperatureK[cell] = (airTemperatureK[cell] + (airScratch[cell] - airTemperatureK[cell]) * AIR_ADVECTION_WEIGHT)
                .coerceIn(180.0, 340.0)
        }
        airTransportJoules += transport * AIR_ADVECTION_WEIGHT * config.cellArea

        // Ocean mixed-layer heat redistribution. Only same-basin neighbours exchange;
        // no global teleportation or prescribed western-coast warming is introduced.
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

    fun endPhaseDiagnostics(seconds: Double): EnergyIntervalDiagnostics {
        val beforeSurfaceResidual = surfaceResidualJoules
        val beforeAirResidual = airResidualJoules
        // These residuals are explicitly reported numerical adjustments: semi-Lagrangian
        // advection and the finite phase split are not exact conservation statements.
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

    /** Bilinear sample of the coarse air temperature, wrapping longitude and clamping latitude. */
    private fun sampleAir(field: DoubleArray, x: Double, y: Double): Double {
        val px = ((x % config.width) + config.width) % config.width
        val py = y.coerceIn(0.0, config.height - 1.0)
        val left = floor(px).toInt(); val bottom = floor(py).toInt()
        val fx = px - left; val fy = py - bottom
        val x0 = (left + config.width) % config.width
        val x1 = (x0 + 1) % config.width
        val y0 = bottom.coerceIn(0, config.height - 1)
        val y1 = (bottom + 1).coerceIn(0, config.height - 1)
        return (field[y0 * config.width + x0] * (1 - fx) + field[y0 * config.width + x1] * fx) * (1 - fy) +
            (field[y1 * config.width + x0] * (1 - fx) + field[y1 * config.width + x1] * fx) * fy
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
        const val CLEAR_SKY_EMISSIVITY = 0.72
        const val HUMIDITY_EMISSIVITY = 0.15
        const val CLOUD_SHORTWAVE_REFLECTION = 0.42
        const val CLOUD_OPTICAL_MM = 0.12
        const val AIR_DENSITY = 1.2
        const val DRAG_COEFFICIENT = 0.00045
        const val LAND_EXCHANGE_FACTOR = 1.0
        const val OCEAN_EXCHANGE_FACTOR = 1.35
        const val GUST_METERS_PER_SECOND = 3.0
        const val FREEZING_KELVIN = 271.15
        const val LATENT_HEAT_FUSION = 334_000.0
        const val SNOW_MELT_ENERGY_FRACTION = 0.35
        const val SEA_ICE_MASS_PER_M2 = 2000.0
        const val SEA_ICE_ALBEDO = 0.55
        const val OCEAN_ALBEDO = 0.07
        const val LAND_TEMPERATURE_FLOOR_KELVIN = 208.15
        const val SOLAR_SCALE = 0.85
        const val EQUILIBRIUM_ITERATIONS = 2
        const val LATENT_HEAT = 2_500_000.0
        const val ACTIVE_AIR_OFFSET_K = -7.0
        const val AIR_HEAT_CAPACITY = 1.5e7
        const val AIR_SURFACE_COUPLING = 0.45
        const val AIR_ADVECTION_WEIGHT = 0.12
        const val OCEAN_MIXING_SECONDS = 200 * 86400.0
        const val OCEAN_MIXING_FRACTION = 0.55
        const val MAXIMUM_EXCHANGE_MM = 40.0
        const val SURFACE_HEAT_CAPACITY_LIMIT_FRACTION = 0.01
    }
}
