package terra.weather

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import terra.world.Terrain
import terra.world.World

/** A local water-cycle model with atmospheric and soil water stored as millimeters. */
class WeatherSimulation(private val world: World) : WeatherModel {
    override val width = world.width
    override val height = world.height
    private val count = width * height

    private val atmosphericWaterMm = DoubleArray(count)
    private val transportedAtmosphericWaterMm = DoubleArray(count)
    private val cloudWaterMm = DoubleArray(count)
    private val transportedCloudWaterMm = DoubleArray(count)
    private val transportedAirTemperatureC = DoubleArray(count)
    private val eastFaceWind = DoubleArray(count)
    private val northFaceWind = DoubleArray(width * (height + 1))
    private val transportOutgoingRate = DoubleArray(count)
    // Rain state is local to the tile; cloud water itself continues to advect with the air.
    private val rainActive = BooleanArray(count)
    private val precipitationMmPerHour = DoubleArray(count)
    private val outerRainMm = DoubleArray(count)
    private val lakeDirectRainMm = DoubleArray(count)
    private val lakePotentialEvaporationMm = DoubleArray(count)
    private val lakeWaterMask = BooleanArray(count)
    // Water removed from land soil during the latest outer step(), in mm per tile.
    private val drainageMmThisUpdate = DoubleArray(count)
    private val overflowMmThisUpdate = DoubleArray(count)
    private val peakSubstepPrecipitationMmPerHour = DoubleArray(count)
    private val climateRainMmPerHour = DoubleArray(count)
    private val climatePrecipitationSumMm = DoubleArray(count)
    private val climateTemperatureTimeIntegral = DoubleArray(count)
    private val soilWaterMm = DoubleArray(count)
    private val solarInputFactor = DoubleArray(count)
    private val surfaceTemperatureC = DoubleArray(count)
    private val airTemperatureC = DoubleArray(count)
    private val zonalWindTilesPerDay = DoubleArray(height)
    private val windUTilesPerDay = DoubleArray(count)
    private val windVTilesPerDay = DoubleArray(count)
    override var elapsedSeconds = 0.0
        private set
    private var climateSamplingEnabled = false
    private var climateSampledDays = 0.0

    init {
        for (y in 0 until height) {
            zonalWindTilesPerDay[y] = zonalWindAtLatitude(normalizedLatitude(y) * 90.0)
        }
        updateWindField(0.0)

        for (y in 0 until height) for (x in 0 until width) {
            val i = index(x, y)
            val cell = world[x, y]
            surfaceTemperatureC[i] = cell.meanTemperatureC
            airTemperatureC[i] = cell.meanTemperatureC
            solarInputFactor[i] = solarInputFactorAt(x, y, 0.0)
            atmosphericWaterMm[i] = INITIAL_RELATIVE_HUMIDITY *
                saturationColumnWaterMm(airTemperatureC[i])
            soilWaterMm[i] = if (cell.terrain == Terrain.OCEAN) {
                0.0
            } else {
                INITIAL_SOIL_WATER_FRACTION * SOIL_WATER_CAPACITY_MM
            }

            precipitationMmPerHour[i] = 0.0
        }
    }

    override fun relativeHumidity(x: Int, y: Int) = relativeHumidityAt(index(x, y))
    override fun cloudCover(x: Int, y: Int) = cloudCoverAt(index(x, y))
    override fun precipitationMmPerHour(x: Int, y: Int) = precipitationMmPerHour[index(x, y)]
    override fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int) =
        peakSubstepPrecipitationMmPerHour[index(x, y)]
    override fun rainMmThisUpdate(x: Int, y: Int) = outerRainMm[index(x, y)]
    /** Soil drainage removed during the latest outer step(), in mm per tile (not mm/h). */
    override fun drainageMmThisUpdate(x: Int, y: Int) = drainageMmThisUpdate[index(x, y)]
    /** Soil-capacity overflow removed during the latest outer step(), in mm per tile (not mm/h). */
    override fun overflowMmThisUpdate(x: Int, y: Int) = overflowMmThisUpdate[index(x, y)]
    /** Rain transferred directly to a wet lake during the latest outer step(), in mm per tile. */
    override fun lakeDirectRainMmThisUpdate(x: Int, y: Int) = lakeDirectRainMm[index(x, y)]
    /** Potential open-water evaporation during the latest outer step(), in mm per tile. */
    override fun lakePotentialEvaporationMmThisUpdate(x: Int, y: Int) = lakePotentialEvaporationMm[index(x, y)]
    /** Exponentially smoothed rain rate with a 30-day response time, in mm/h. */
    override fun thirtyDayRainMmPerHour(x: Int, y: Int) = climateRainMmPerHour[index(x, y)]
    /** Annualized estimate derived from the 30-day rain-rate EMA, not an observed annual total. */
    override fun annualizedRainMmPerYear(x: Int, y: Int) =
        climateRainMmPerHour[index(x, y)] * HOURS_PER_YEAR
    /** Clear previous observations and start collecting climate from subsequent weather substeps. */
    fun beginClimateSampling() {
        climatePrecipitationSumMm.fill(0.0)
        climateTemperatureTimeIntegral.fill(0.0)
        climateSampledDays = 0.0
        climateSamplingEnabled = true
    }

    /** Stop collecting and return a compact, immutable snapshot of the observed climate. */
    fun endClimateSampling(): ClimateSnapshot {
        check(climateSamplingEnabled) { "Climate sampling is not active" }
        check(climateSampledDays > 0.0) { "Climate sampling must include at least one weather substep" }
        climateSamplingEnabled = false
        return ClimateSnapshot(
            width = width,
            height = height,
            sampledDays = climateSampledDays,
            precipitationSumMm = climatePrecipitationSumMm.copyOf(),
            temperatureTimeIntegral = climateTemperatureTimeIntegral.copyOf()
        )
    }
    override fun solarInputFactor(x: Int, y: Int) = solarInputFactor[index(x, y)]
    override fun surfaceTemperatureC(x: Int, y: Int) = surfaceTemperatureC[index(x, y)]
    override fun airTemperatureC(x: Int, y: Int) = airTemperatureC[index(x, y)]
    override fun windUTilesPerDay(x: Int, y: Int) = windUTilesPerDay[index(x, y)]
    override fun windVTilesPerDay(x: Int, y: Int) = windVTilesPerDay[index(x, y)]
    override fun nightOpacity(x: Int, y: Int): Double {
        val sunHeight = cos(hourAngleAt(x, elapsedSeconds))
        return (1.0 - smoothstep(-TERMINATOR_TRANSITION, TERMINATOR_TRANSITION, sunHeight)) *
            MAX_NIGHT_OPACITY
    }
    override fun atmosphericWaterMm(x: Int, y: Int) = atmosphericWaterMm[index(x, y)]
    override fun cloudWaterMm(x: Int, y: Int) = cloudWaterMm[index(x, y)]
    override fun soilWaterMm(x: Int, y: Int) = soilWaterMm[index(x, y)]
    override fun soilMoisture(x: Int, y: Int) =
        (soilWaterMm[index(x, y)] / SOIL_WATER_CAPACITY_MM).coerceIn(0.0, 1.0)

    /** Process all elapsed simulated time in bounded internal steps. */
    override fun step(seconds: Double) {
        require(seconds >= 0.0) { "Simulation time cannot move backwards" }
        outerRainMm.fill(0.0)
        drainageMmThisUpdate.fill(0.0)
        overflowMmThisUpdate.fill(0.0)
        lakeDirectRainMm.fill(0.0)
        lakePotentialEvaporationMm.fill(0.0)
        peakSubstepPrecipitationMmPerHour.fill(0.0)
        if (seconds == 0.0) return

        val substepCount = ceil(seconds / MAX_WEATHER_STEP_SECONDS).toLong().coerceAtLeast(1L)
        val substepSeconds = seconds / substepCount
        var substepIndex = 0L
        while (substepIndex < substepCount) {
            advanceSubstep(substepSeconds)
            substepIndex++
        }

        val elapsedHours = seconds / SECONDS_PER_HOUR
        for (i in 0 until count) {
            precipitationMmPerHour[i] = outerRainMm[i] / elapsedHours
        }
    }

    /** Replace the wet-lake surface mask consumed by subsequent weather substeps. */
    override fun updateLakeWaterMask(mask: BooleanArray) {
        require(mask.size == count) { "Lake-water mask dimensions must match the weather grid" }
        for (i in 0 until count) lakeWaterMask[i] = mask[i]
    }

    /** Return confirmed lake evaporation to atmospheric vapor exactly once. */
    override fun returnLakeEvaporationMm(x: Int, y: Int, amountMm: Double) {
        require(amountMm >= 0.0 && amountMm.isFinite()) { "Lake evaporation must be finite and non-negative" }
        atmosphericWaterMm[index(x, y)] += amountMm
    }

    /** Advance exactly one existing maximum-size weather substep and return its simulated duration. */
    fun advanceOneInternalSubstep(): Double {
        step(MAX_WEATHER_STEP_SECONDS)
        return MAX_WEATHER_STEP_SECONDS
    }

    private fun advanceSubstep(seconds: Double) {
        transportAirMass(seconds)
        elapsedSeconds += seconds
        updateThermalState(seconds)
        updateAirTemperature(seconds)

        val condensationResponse = 1.0 - exp(-seconds / CONDENSATION_RESPONSE_SECONDS)
        val convectiveCondensationResponse =
            1.0 - exp(-seconds / CONVECTIVE_CONDENSATION_RESPONSE_SECONDS)
        val cloudEvaporationResponse = 1.0 - exp(-seconds / CLOUD_EVAPORATION_RESPONSE_SECONDS)
        val climateResponse = 1.0 - exp(-seconds / CLIMATE_RESPONSE_SECONDS)
        val elapsedDays = seconds / SECONDS_PER_DAY
        val soilDrainageResponse = 1.0 - exp(-elapsedDays / SOIL_DRAINAGE_RESPONSE_DAYS)

        for (y in 0 until height) for (x in 0 until width) {
            val i = index(x, y)
            val cell = world[x, y]
            val temperature = surfaceTemperatureC[i]
            val airTemperature = airTemperatureC[i]
            val warmth = ((temperature + 5.0) / 35.0).coerceIn(0.0, 1.0)
            val landWaterFraction =
                (soilWaterMm[i] / SOIL_WATER_CAPACITY_MM).coerceIn(0.0, 1.0)
            val isLakeWater = cell.terrain != Terrain.OCEAN && lakeWaterMask[i]
            val availableWater = if (cell.terrain == Terrain.OCEAN || isLakeWater) 1.0 else landWaterFraction

            val potentialEvaporationMm = MAX_EVAPORATION_MM_PER_DAY *
                solarInputFactor[i] * warmth * availableWater * seconds / SECONDS_PER_DAY
            val evaporatedMm = if (cell.terrain == Terrain.OCEAN) {
                potentialEvaporationMm
            } else if (isLakeWater) {
                0.0
            } else {
                potentialEvaporationMm.coerceAtMost(soilWaterMm[i])
            }
            if (isLakeWater) lakePotentialEvaporationMm[i] += potentialEvaporationMm
            val atmosphericWaterAfterEvaporation = atmosphericWaterMm[i] + evaporatedMm
            val saturationCapacityMm = saturationColumnWaterMm(airTemperature)
            val supersaturatedWaterMm = max(
                0.0,
                atmosphericWaterAfterEvaporation - saturationCapacityMm
            )
            val ordinaryCondensedMm = supersaturatedWaterMm * condensationResponse
            val vaporAfterOrdinaryCondensationMm =
                atmosphericWaterAfterEvaporation - ordinaryCondensedMm
            val surfaceAirContrastC = temperature - airTemperature
            val liftFraction = smoothstep(
                CONVECTIVE_LIFT_START_CONTRAST_C,
                CONVECTIVE_LIFT_FULL_CONTRAST_C,
                surfaceAirContrastC
            )
            val relativeHumidityAfterEvaporation = relativeHumidity(
                atmosphericWaterAfterEvaporation,
                airTemperature
            )
            val humidLiftFraction = smoothstep(
                CONVECTIVE_HUMIDITY_START,
                CONVECTIVE_HUMIDITY_FULL,
                relativeHumidityAfterEvaporation
            )
            val windwardRise = upwindTerrainRise(x, y, i)
            val orographicLiftFraction = smoothstep(
                OROGRAPHIC_RISE_START,
                OROGRAPHIC_RISE_FULL,
                windwardRise
            )
            val convectiveCoolingC = CONVECTIVE_MAX_LIFT_COOLING_C * liftFraction
            val orographicCoolingC = OROGRAPHIC_MAX_LIFT_COOLING_C * orographicLiftFraction
            val combinedLiftCoolingC = minOf(
                MAX_COMBINED_LIFT_COOLING_C,
                convectiveCoolingC + orographicCoolingC
            )
            val effectiveLiftedTemperatureC = airTemperature - combinedLiftCoolingC
            val liftedSaturationCapacityMm = saturationColumnWaterMm(effectiveLiftedTemperatureC)
            val convectivelyCondensableMm = max(
                0.0,
                vaporAfterOrdinaryCondensationMm - liftedSaturationCapacityMm
            )
            val convectivelyCondensedMm = convectivelyCondensableMm *
                humidLiftFraction * convectiveCondensationResponse
            val vaporAfterCondensationMm =
                vaporAfterOrdinaryCondensationMm - convectivelyCondensedMm
            val condensedMm = ordinaryCondensedMm + convectivelyCondensedMm
            val cloudAfterCondensationMm = cloudWaterMm[i] + condensedMm

            val saturationDeficitMm = max(0.0, saturationCapacityMm - vaporAfterCondensationMm)
            val cloudEvaporatedMm = minOf(
                cloudAfterCondensationMm,
                saturationDeficitMm * cloudEvaporationResponse
            )
            val vaporAfterCloudEvaporationMm = vaporAfterCondensationMm + cloudEvaporatedMm
            val cloudBeforeRainMm = cloudAfterCondensationMm - cloudEvaporatedMm

            if (!rainActive[i] && cloudBeforeRainMm >= RAIN_START_CLOUD_WATER_MM) {
                rainActive[i] = true
            } else if (rainActive[i] && cloudBeforeRainMm <= RAIN_STOP_CLOUD_WATER_MM) {
                rainActive[i] = false
            }

            val rainoutResponse = if (rainActive[i]) {
                1.0 - exp(-seconds / ACTIVE_RAINOUT_RESPONSE_SECONDS)
            } else {
                0.0
            }
            val rainMm = cloudBeforeRainMm * rainoutResponse
            val cloudAfterRainMm = cloudBeforeRainMm - rainMm

            if (climateSamplingEnabled) {
                climatePrecipitationSumMm[i] += rainMm
                climateTemperatureTimeIntegral[i] += airTemperature * elapsedDays
            }

            if (cell.terrain != Terrain.OCEAN) {
                if (isLakeWater) {
                    lakeDirectRainMm[i] += rainMm
                } else {
                    val rawSoilMm = soilWaterMm[i] - evaporatedMm + rainMm
                    val soilCapacityOverflowMm = max(0.0, rawSoilMm - SOIL_WATER_CAPACITY_MM)
                    val soilAfterRainMm = rawSoilMm.coerceIn(0.0, SOIL_WATER_CAPACITY_MM)
                    val excessAboveFieldCapacityMm =
                        max(0.0, soilAfterRainMm - SOIL_FIELD_CAPACITY_MM)
                    val drainedMm = excessAboveFieldCapacityMm * soilDrainageResponse
                    soilWaterMm[i] = soilAfterRainMm - drainedMm
                    overflowMmThisUpdate[i] += soilCapacityOverflowMm
                    drainageMmThisUpdate[i] += drainedMm
                }
            }

            atmosphericWaterMm[i] = vaporAfterCloudEvaporationMm
            cloudWaterMm[i] = cloudAfterRainMm
            outerRainMm[i] += rainMm
            val substepRainMmPerHour = rainMm * SECONDS_PER_HOUR / seconds
            peakSubstepPrecipitationMmPerHour[i] = max(
                peakSubstepPrecipitationMmPerHour[i],
                substepRainMmPerHour
            )
            climateRainMmPerHour[i] +=
                (substepRainMmPerHour - climateRainMmPerHour[i]) * climateResponse
        }
        if (climateSamplingEnabled) climateSampledDays += elapsedDays
    }

    private fun relativeHumidityAt(index: Int) = relativeHumidity(
        atmosphericWaterMm[index], airTemperatureC[index]
    )

    private fun cloudCoverAt(index: Int) = smoothstep(
        CLOUD_VISIBLE_WATER_MM,
        CLOUD_FULL_COVER_WATER_MM,
        cloudWaterMm[index]
    )

    private fun relativeHumidity(atmosphericWaterMm: Double, temperatureC: Double) =
        (atmosphericWaterMm / saturationColumnWaterMm(temperatureC)).coerceIn(0.0, 1.0)

    /** Simplified water-column capacity used to turn stored vapor into relative humidity. */
    private fun saturationColumnWaterMm(temperatureC: Double) =
        (SATURATION_COLUMN_WATER_MM_AT_20C *
            exp(SATURATION_CAPACITY_GROWTH_PER_C * (temperatureC - 20.0)))
            .coerceIn(MIN_SATURATION_COLUMN_WATER_MM, MAX_SATURATION_COLUMN_WATER_MM)

    /** Positive elevation gain into the current wind, sampling one tile upstream. */
    private fun upwindTerrainRise(x: Int, y: Int, i: Int): Double {
        val u = windUTilesPerDay[i]
        val v = windVTilesPerDay[i]
        val speed = sqrt(u * u + v * v)
        if (speed <= MIN_OROGRAPHIC_WIND_SPEED_TILES_PER_DAY) return 0.0
        val upwindElevation = elevationAt(
            x - u / speed,
            y - v / speed
        )
        return max(0.0, world[x, y].elevation - upwindElevation)
    }

    /** Bilinear terrain sample with periodic X and clamped Y boundaries. */
    private fun elevationAt(x: Double, y: Double): Double {
        val wrappedX = ((x % width) + width) % width
        val clampedY = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = floor(wrappedX).toInt()
        val y0 = floor(clampedY).toInt()
        val x1 = (x0 + 1) % width
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fractionX = wrappedX - x0
        val fractionY = clampedY - y0
        val northWest = world[x0, y0].elevation
        val northEast = world[x1, y0].elevation
        val southWest = world[x0, y1].elevation
        val southEast = world[x1, y1].elevation
        val north = northWest + (northEast - northWest) * fractionX
        val south = southWest + (southEast - southWest) * fractionX
        return north + (south - north) * fractionY
    }

    private fun updateThermalState(seconds: Double) {
        for (y in 0 until height) for (x in 0 until width) {
            val i = index(x, y)
            val latitudeSolarFactor = latitudeSolarFactorAt(y)
            val solarInput = solarInputFactorAt(x, y, elapsedSeconds)
            val cloudAttenuation = cloudCoverAt(i) * CLOUD_SOLAR_ATTENUATION
            val absorbedSolarInput = solarInput * (1.0 - cloudAttenuation)
            val meanAbsorbedSolarInput = latitudeSolarFactor / PI *
                (1.0 - REFERENCE_CLOUD_COVER * CLOUD_SOLAR_ATTENUATION)
            val solarTemperatureAnomaly =
                (absorbedSolarInput - meanAbsorbedSolarInput) * SOLAR_TEMPERATURE_SWING_C
            val targetTemperature = world[x, y].meanTemperatureC + solarTemperatureAnomaly
            val thermalResponseSeconds = if (world[x, y].terrain == Terrain.OCEAN) {
                OCEAN_THERMAL_RESPONSE_SECONDS
            } else {
                LAND_THERMAL_RESPONSE_SECONDS
            }
            val response = 1.0 - exp(-seconds / thermalResponseSeconds)

            solarInputFactor[i] = solarInput
            surfaceTemperatureC[i] += (targetTemperature - surfaceTemperatureC[i]) * response
        }
    }

    private fun updateAirTemperature(seconds: Double) {
        val response = 1.0 - exp(-seconds / AIR_TEMPERATURE_RESPONSE_SECONDS)
        for (i in 0 until count) {
            airTemperatureC[i] += (surfaceTemperatureC[i] - airTemperatureC[i]) * response
        }
    }

    private fun solarInputFactorAt(x: Int, y: Int, simulationSeconds: Double): Double {
        val hourAngle = hourAngleAt(x, simulationSeconds)
        val daylight = max(0.0, cos(hourAngle))
        return (latitudeSolarFactorAt(y) * daylight).coerceIn(0.0, 1.0)
    }

    private fun hourAngleAt(x: Int, simulationSeconds: Double): Double {
        val localDayPhase = (simulationSeconds / SECONDS_PER_DAY + x.toDouble() / width) % 1.0
        return 2.0 * PI * (localDayPhase - 0.5)
    }

    private fun latitudeSolarFactorAt(y: Int): Double {
        val latitude = normalizedLatitude(y)
        return (0.15 + 0.85 * cos(latitude * PI / 2.0)).coerceIn(0.0, 1.0)
    }

    /** Advect vapor, cloud water, and air temperature with conservative face fluxes. */
    private fun transportAirMass(seconds: Double) {
        updateWindField(elapsedSeconds)
        buildFaceWindField()
        transportOutgoingRate.fill(0.0)
        for (y in 0 until height) for (x in 0 until width) {
            val i = index(x, y)
            val speed = eastFaceWind[i]
            val donor = if (speed >= 0.0) i else index(wrapX(x + 1), y)
            transportOutgoingRate[donor] += abs(speed)
        }
        for (faceY in 1 until height) for (x in 0 until width) {
            val speed = northFaceWind[faceY * width + x]
            val donor = if (speed >= 0.0) index(x, faceY - 1) else index(x, faceY)
            transportOutgoingRate[donor] += abs(speed)
        }
        var maxOutgoingRate = 0.0
        for (rate in transportOutgoingRate) maxOutgoingRate = max(maxOutgoingRate, rate)
        val elapsedDays = seconds / SECONDS_PER_DAY
        val substeps = ceil(elapsedDays * maxOutgoingRate / TRANSPORT_CFL_LIMIT)
            .toInt().coerceAtLeast(1)
        val transportDays = elapsedDays / substeps
        repeat(substeps) {
            transportTracer(atmosphericWaterMm, transportedAtmosphericWaterMm, transportDays)
            transportTracer(cloudWaterMm, transportedCloudWaterMm, transportDays)
            transportTracer(airTemperatureC, transportedAirTemperatureC, transportDays, nonNegative = false)
        }
    }

    /** Build a staggered field whose discrete cell divergence cancels exactly. */
    private fun buildFaceWindField() {
        for (y in 0 until height) for (x in 0 until width) {
            val eastX = wrapX(x + 1)
            eastFaceWind[index(x, y)] = zonalWindTilesPerDay[y] +
                streamfunction(eastX, y + 1) - streamfunction(eastX, y)
        }
        northFaceWind.fill(0.0)
        for (faceY in 1 until height) for (x in 0 until width) {
            northFaceWind[faceY * width + x] =
                -(streamfunction(wrapX(x + 1), faceY) - streamfunction(x, faceY))
        }
    }

    /** Wave streamfunction at periodic X and bounded Y grid vertices. */
    private fun streamfunction(x: Int, vertexY: Int): Double {
        val latitudePhase = PI * vertexY / height
        val sine = sin(latitudePhase)
        val envelope = sine * sine
        val wave1Number = TWO_PI * WAVE_1_CYCLES / width
        val wave2Number = TWO_PI * WAVE_2_CYCLES / width
        val wave1Amplitude = WAVE_1_MERIDIONAL_SPEED_TILES_PER_DAY / wave1Number
        val wave2Amplitude = WAVE_2_MERIDIONAL_SPEED_TILES_PER_DAY / wave2Number
        val simulatedDays = elapsedSeconds / SECONDS_PER_DAY
        val wave1Phase = wave1Number * x + TWO_PI * simulatedDays / WAVE_1_PERIOD_DAYS *
            WAVE_1_DIRECTION + WAVE_1_PHASE_OFFSET
        val wave2Phase = wave2Number * x + TWO_PI * simulatedDays / WAVE_2_PERIOD_DAYS *
            WAVE_2_DIRECTION + WAVE_2_PHASE_OFFSET
        return envelope * (wave1Amplitude * sin(wave1Phase) + wave2Amplitude * sin(wave2Phase))
    }

    /** Apply each face flux once as an equal and opposite donor/receiver update. */
    private fun transportTracer(
        source: DoubleArray,
        destination: DoubleArray,
        elapsedDays: Double,
        nonNegative: Boolean = true
    ) {
        source.copyInto(destination)
        for (y in 0 until height) for (x in 0 until width) {
            val i = index(x, y)
            val east = index(wrapX(x + 1), y)
            val speed = eastFaceWind[i]
            val donor = if (speed >= 0.0) i else east
            val donorValue = if (nonNegative) max(0.0, source[donor]) else source[donor]
            val amount = donorValue * abs(speed) * elapsedDays
            val receiver = if (speed >= 0.0) east else i
            destination[donor] -= amount
            destination[receiver] += amount
        }
        for (faceY in 1 until height) for (x in 0 until width) {
            val south = index(x, faceY)
            val north = index(x, faceY - 1)
            val speed = northFaceWind[faceY * width + x]
            val donor = if (speed >= 0.0) north else south
            val donorValue = if (nonNegative) max(0.0, source[donor]) else source[donor]
            val amount = donorValue * abs(speed) * elapsedDays
            destination[donor] -= amount
            destination[if (speed >= 0.0) south else north] += amount
        }
        for (i in 0 until count) {
            val value = if (!nonNegative) destination[i] else if (
                destination[i] < 0.0 && destination[i] > -1e-12
            ) 0.0 else max(0.0, destination[i])
            destination[i] = value
        }
        destination.copyInto(source)
    }

    private fun updateWindField(simulationSeconds: Double) {
        val simulatedDays = simulationSeconds / SECONDS_PER_DAY
        val wave1Number = TWO_PI * WAVE_1_CYCLES / width
        val wave2Number = TWO_PI * WAVE_2_CYCLES / width
        val wave1Amplitude = WAVE_1_MERIDIONAL_SPEED_TILES_PER_DAY / wave1Number
        val wave2Amplitude = WAVE_2_MERIDIONAL_SPEED_TILES_PER_DAY / wave2Number
        val wave1TimePhase = TWO_PI * simulatedDays / WAVE_1_PERIOD_DAYS * WAVE_1_DIRECTION
        val wave2TimePhase = TWO_PI * simulatedDays / WAVE_2_PERIOD_DAYS * WAVE_2_DIRECTION
        for (y in 0 until height) {
            val latitudePhase = if (height <= 1) 0.0 else PI * y / (height - 1)
            val latitudeSine = sin(latitudePhase)
            val latitudeEnvelope = latitudeSine * latitudeSine
            val latitudeDerivative = if (height <= 1) {
                0.0
            } else {
                PI / (height - 1) * sin(2.0 * latitudePhase)
            }

            for (x in 0 until width) {
                val wave1Phase = wave1Number * x + wave1TimePhase + WAVE_1_PHASE_OFFSET
                val wave2Phase = wave2Number * x + wave2TimePhase + WAVE_2_PHASE_OFFSET
                val i = index(x, y)
                val u = zonalWindTilesPerDay[y] +
                    wave1Amplitude * latitudeDerivative * sin(wave1Phase) +
                    wave2Amplitude * latitudeDerivative * sin(wave2Phase)
                val v = -WAVE_1_MERIDIONAL_SPEED_TILES_PER_DAY *
                    latitudeEnvelope * cos(wave1Phase) -
                    WAVE_2_MERIDIONAL_SPEED_TILES_PER_DAY *
                    latitudeEnvelope * cos(wave2Phase)
                windUTilesPerDay[i] = u
                windVTilesPerDay[i] = v
            }
        }
    }

    private fun normalizedLatitude(y: Int) = if (height <= 1) {
        0.0
    } else {
        abs(2.0 * y / (height - 1) - 1.0)
    }

    private fun zonalWindAtLatitude(latitudeDegrees: Double): Double = when {
        latitudeDegrees < TRADE_WIND_BELT_END_DEGREES -> TRADE_WIND_SPEED_TILES_PER_DAY
        latitudeDegrees < WESTERLY_BELT_START_DEGREES -> {
            val blend = smoothstep(
                TRADE_WIND_BELT_END_DEGREES,
                WESTERLY_BELT_START_DEGREES,
                latitudeDegrees
            )
            TRADE_WIND_SPEED_TILES_PER_DAY +
                (WESTERLY_WIND_SPEED_TILES_PER_DAY - TRADE_WIND_SPEED_TILES_PER_DAY) * blend
        }
        latitudeDegrees < SUBPOLAR_BELT_START_DEGREES -> WESTERLY_WIND_SPEED_TILES_PER_DAY
        latitudeDegrees < POLAR_BELT_START_DEGREES -> {
            val blend = smoothstep(
                SUBPOLAR_BELT_START_DEGREES,
                POLAR_BELT_START_DEGREES,
                latitudeDegrees
            )
            WESTERLY_WIND_SPEED_TILES_PER_DAY +
                (POLAR_WIND_SPEED_TILES_PER_DAY - WESTERLY_WIND_SPEED_TILES_PER_DAY) * blend
        }
        else -> POLAR_WIND_SPEED_TILES_PER_DAY
    }

    private fun wrapX(x: Int): Int = ((x % width) + width) % width

    private fun index(x: Int, y: Int) = y * width + x

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private companion object {
        const val CLIMATE_RESPONSE_SECONDS = 30.0 * 24.0 * 60.0 * 60.0
        const val HOURS_PER_YEAR = 365.0 * 24.0
        const val SECONDS_PER_HOUR = 60.0 * 60.0
        const val SECONDS_PER_DAY = 24.0 * SECONDS_PER_HOUR
        const val MAX_WEATHER_STEP_SECONDS = 3.0 * SECONDS_PER_HOUR
        const val TRANSPORT_CFL_LIMIT = 0.8

        const val INITIAL_RELATIVE_HUMIDITY = 0.45
        const val INITIAL_SOIL_WATER_FRACTION = 0.5
        const val SOIL_WATER_CAPACITY_MM = 250.0
        const val SOIL_FIELD_CAPACITY_MM = 120.0
        const val SOIL_DRAINAGE_RESPONSE_DAYS = 5.0
        const val MAX_EVAPORATION_MM_PER_DAY = 6.0
        const val SATURATION_COLUMN_WATER_MM_AT_20C = 20.0
        const val SATURATION_CAPACITY_GROWTH_PER_C = 0.06
        const val MIN_SATURATION_COLUMN_WATER_MM = 1.0
        const val MAX_SATURATION_COLUMN_WATER_MM = 80.0
        const val TRADE_WIND_BELT_END_DEGREES = 23.0
        const val WESTERLY_BELT_START_DEGREES = 37.0
        const val SUBPOLAR_BELT_START_DEGREES = 55.0
        const val POLAR_BELT_START_DEGREES = 72.0
        const val TRADE_WIND_SPEED_TILES_PER_DAY = -24.0
        const val WESTERLY_WIND_SPEED_TILES_PER_DAY = 36.0
        const val POLAR_WIND_SPEED_TILES_PER_DAY = -12.0
        const val TWO_PI = 2.0 * PI
        const val WAVE_1_CYCLES = 1.0
        const val WAVE_2_CYCLES = 3.0
        const val WAVE_1_MERIDIONAL_SPEED_TILES_PER_DAY = 9.0
        const val WAVE_2_MERIDIONAL_SPEED_TILES_PER_DAY = 5.0
        const val WAVE_1_PERIOD_DAYS = 19.0
        const val WAVE_2_PERIOD_DAYS = 43.0
        const val WAVE_1_DIRECTION = 1.0
        const val WAVE_2_DIRECTION = -1.0
        const val WAVE_1_PHASE_OFFSET = 0.0
        const val WAVE_2_PHASE_OFFSET = PI / 3.0
        const val CONDENSATION_RESPONSE_SECONDS = 15.0 * 60.0
        const val CONVECTIVE_CONDENSATION_RESPONSE_SECONDS = 6.0 * 60.0 * 60.0
        const val CONVECTIVE_MAX_LIFT_COOLING_C = 5.0
        const val CONVECTIVE_LIFT_START_CONTRAST_C = 2.0
        const val CONVECTIVE_LIFT_FULL_CONTRAST_C = 12.0
        const val CONVECTIVE_HUMIDITY_START = 0.65
        const val CONVECTIVE_HUMIDITY_FULL = 0.95
        const val OROGRAPHIC_RISE_START = 0.001
        const val OROGRAPHIC_RISE_FULL = 0.02
        const val OROGRAPHIC_MAX_LIFT_COOLING_C = 3.0
        const val MAX_COMBINED_LIFT_COOLING_C = 7.0
        const val MIN_OROGRAPHIC_WIND_SPEED_TILES_PER_DAY = 0.1
        const val CLOUD_EVAPORATION_RESPONSE_SECONDS = 3.0 * 60.0 * 60.0
        const val TERMINATOR_TRANSITION = 0.12
        const val MAX_NIGHT_OPACITY = 0.55
        const val CLOUD_VISIBLE_WATER_MM = 0.02
        const val CLOUD_FULL_COVER_WATER_MM = 0.8
        const val RAIN_START_CLOUD_WATER_MM = 2.0
        const val RAIN_STOP_CLOUD_WATER_MM = 0.5
        const val ACTIVE_RAINOUT_RESPONSE_SECONDS = 2.0 * 60.0 * 60.0
        const val LAND_THERMAL_RESPONSE_SECONDS = 6.0 * 60.0 * 60.0
        const val OCEAN_THERMAL_RESPONSE_SECONDS = 3.0 * SECONDS_PER_DAY
        const val CLOUD_SOLAR_ATTENUATION = 0.55
        const val REFERENCE_CLOUD_COVER = 0.35
        const val SOLAR_TEMPERATURE_SWING_C = 24.0
        const val AIR_TEMPERATURE_RESPONSE_SECONDS = SECONDS_PER_DAY
    }
}

/** Time-averaged climate captured during an explicit sampling interval. */
class ClimateSnapshot internal constructor(
    val width: Int,
    val height: Int,
    val sampledDays: Double,
    private val precipitationSumMm: DoubleArray,
    private val temperatureTimeIntegral: DoubleArray
) {
    /** Actual precipitation depth observed during this snapshot's sampling interval, in mm. */
    fun precipitationTotalMm(x: Int, y: Int): Double = precipitationSumMm[index(x, y)]

    /** Annualized precipitation depth rate, in mm/year. */
    fun annualPrecipitationMmPerYear(x: Int, y: Int): Double =
        precipitationSumMm[index(x, y)] * DAYS_PER_YEAR / sampledDays

    /** Mean simulated air temperature over the sampling interval, in °C. */
    fun meanAirTemperatureC(x: Int, y: Int): Double =
        temperatureTimeIntegral[index(x, y)] / sampledDays

    private fun index(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "Tile coordinates are outside the snapshot" }
        return y * width + x
    }

    private companion object {
        const val DAYS_PER_YEAR = 365.0
    }
}
