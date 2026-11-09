package terra.weather

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import terra.world.Terrain
import terra.world.World

/** A local water-cycle model with atmospheric and soil water stored as millimeters. */
class WeatherSimulation(private val world: World) {
    val width = world.width
    val height = world.height
    private val count = width * height

    private val atmosphericWaterMm = DoubleArray(count)
    private val transportedAtmosphericWaterMm = DoubleArray(count)
    private val cloudWaterMm = DoubleArray(count)
    private val transportedCloudWaterMm = DoubleArray(count)
    private val transportedAirTemperatureC = DoubleArray(count)
    private val transportedAirTemperatureWeight = DoubleArray(count)
    // Rain state is local to the tile; cloud water itself continues to advect with the air.
    private val rainActive = BooleanArray(count)
    private val precipitationMmPerHour = DoubleArray(count)
    private val outerRainMm = DoubleArray(count)
    private val peakSubstepPrecipitationMmPerHour = DoubleArray(count)
    private val climateRainMmPerHour = DoubleArray(count)
    private val soilWaterMm = DoubleArray(count)
    private val solarInputFactor = DoubleArray(count)
    private val surfaceTemperatureC = DoubleArray(count)
    private val airTemperatureC = DoubleArray(count)
    private val zonalWindTilesPerDay = DoubleArray(height)
    private val windUTilesPerDay = DoubleArray(count)
    private val windVTilesPerDay = DoubleArray(count)
    private val vortexBaseX = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexBaseY = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexRadiusX = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexRadiusY = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexInvRadiusXSquared = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexInvRadiusYSquared = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexPhaseOffset = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexMotionPeriodDays = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexLifetimeDays = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexXDriftTiles = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexYDriftTiles = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexCirculationSign = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexCenterX = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexCenterY = DoubleArray(REGIONAL_VORTEX_COUNT)
    private val vortexStrength = DoubleArray(REGIONAL_VORTEX_COUNT)
    var regionalVorticesEnabled = true
        private set
    private var elapsedSeconds = 0.0

    init {
        for (y in 0 until height) {
            zonalWindTilesPerDay[y] = zonalWindAtLatitude(normalizedLatitude(y) * 90.0)
        }
        initializeRegionalVortices()
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

    fun relativeHumidity(x: Int, y: Int) = relativeHumidityAt(index(x, y))
    fun cloudCover(x: Int, y: Int) = cloudCoverAt(index(x, y))
    fun precipitationMmPerHour(x: Int, y: Int) = precipitationMmPerHour[index(x, y)]
    fun peakSubstepPrecipitationMmPerHour(x: Int, y: Int) =
        peakSubstepPrecipitationMmPerHour[index(x, y)]
    fun rainMmThisUpdate(x: Int, y: Int) = outerRainMm[index(x, y)]
    fun thirtyDayRainMmPerHour(x: Int, y: Int) = climateRainMmPerHour[index(x, y)]
    fun annualizedRainMmPerYear(x: Int, y: Int) =
        climateRainMmPerHour[index(x, y)] * HOURS_PER_YEAR
    fun solarInputFactor(x: Int, y: Int) = solarInputFactor[index(x, y)]
    fun surfaceTemperatureC(x: Int, y: Int) = surfaceTemperatureC[index(x, y)]
    fun airTemperatureC(x: Int, y: Int) = airTemperatureC[index(x, y)]
    fun windUTilesPerDay(x: Int, y: Int) = windUTilesPerDay[index(x, y)]
    fun windVTilesPerDay(x: Int, y: Int) = windVTilesPerDay[index(x, y)]
    fun setRegionalVorticesEnabled(enabled: Boolean) {
        if (regionalVorticesEnabled == enabled) return
        regionalVorticesEnabled = enabled
        updateWindField(elapsedSeconds)
    }
    fun nightOpacity(x: Int): Double {
        val sunHeight = cos(hourAngleAt(x, elapsedSeconds))
        return (1.0 - smoothstep(-TERMINATOR_TRANSITION, TERMINATOR_TRANSITION, sunHeight)) *
            MAX_NIGHT_OPACITY
    }
    fun atmosphericWaterMm(x: Int, y: Int) = atmosphericWaterMm[index(x, y)]
    fun cloudWaterMm(x: Int, y: Int) = cloudWaterMm[index(x, y)]
    fun soilWaterMm(x: Int, y: Int) = soilWaterMm[index(x, y)]
    fun soilMoisture(x: Int, y: Int) =
        (soilWaterMm[index(x, y)] / SOIL_WATER_CAPACITY_MM).coerceIn(0.0, 1.0)

    /** Process all elapsed simulated time in bounded internal steps. */
    fun step(seconds: Double) {
        require(seconds >= 0.0) { "Simulation time cannot move backwards" }
        if (seconds == 0.0) return

        outerRainMm.fill(0.0)
        peakSubstepPrecipitationMmPerHour.fill(0.0)
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

    private fun advanceSubstep(seconds: Double) {
        transportAirMass(seconds)
        elapsedSeconds += seconds
        updateThermalState(seconds)
        updateAirTemperature(seconds)

        val condensationResponse = 1.0 - exp(-seconds / CONDENSATION_RESPONSE_SECONDS)
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
            val availableWater = if (cell.terrain == Terrain.OCEAN) 1.0 else landWaterFraction

            val potentialEvaporationMm = MAX_EVAPORATION_MM_PER_DAY *
                solarInputFactor[i] * warmth * availableWater * seconds / SECONDS_PER_DAY
            val evaporatedMm = if (cell.terrain == Terrain.OCEAN) {
                potentialEvaporationMm
            } else {
                potentialEvaporationMm.coerceAtMost(soilWaterMm[i])
            }
            val atmosphericWaterAfterEvaporation = atmosphericWaterMm[i] + evaporatedMm
            val saturationCapacityMm = saturationColumnWaterMm(airTemperature)
            val supersaturatedWaterMm = max(
                0.0,
                atmosphericWaterAfterEvaporation - saturationCapacityMm
            )
            val condensedMm = supersaturatedWaterMm * condensationResponse
            val vaporAfterCondensationMm = atmosphericWaterAfterEvaporation - condensedMm
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

            if (cell.terrain != Terrain.OCEAN) {
                val soilAfterRainMm = (soilWaterMm[i] - evaporatedMm + rainMm)
                    .coerceIn(0.0, SOIL_WATER_CAPACITY_MM)
                val excessAboveFieldCapacityMm =
                    max(0.0, soilAfterRainMm - SOIL_FIELD_CAPACITY_MM)
                val drainedMm = excessAboveFieldCapacityMm * soilDrainageResponse
                soilWaterMm[i] = soilAfterRainMm - drainedMm
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

    /** Advect vapor, cloud water, and air temperature together in the atmospheric wind. */
    private fun transportAirMass(seconds: Double) {
        // Scratch buffers collect destination values before replacing the source fields.
        // Deposited values therefore cannot be transported a second time in this pass.
        transportedAtmosphericWaterMm.fill(0.0)
        transportedCloudWaterMm.fill(0.0)
        transportedAirTemperatureC.fill(0.0)
        transportedAirTemperatureWeight.fill(0.0)
        val elapsedDays = seconds / SECONDS_PER_DAY
        updateWindField(elapsedSeconds)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceIndex = index(x, y)
                val u = windUTilesPerDay[sourceIndex]
                val v = windVTilesPerDay[sourceIndex]
                val targetX = x + u * elapsedDays
                val targetY = (y + v * elapsedDays).coerceIn(0.0, (height - 1).toDouble())
                val x0 = floor(targetX).toInt()
                val y0 = floor(targetY).toInt().coerceIn(0, height - 1)
                val fractionX = targetX - x0
                val fractionY = targetY - y0
                val x1 = x0 + 1
                val y1 = (y0 + 1).coerceAtMost(height - 1)

                val destination0 = index(wrapX(x0), y0)
                val destination1 = index(wrapX(x1), y0)
                val destination2 = index(wrapX(x0), y1)
                val destination3 = index(wrapX(x1), y1)
                val weight0 = (1.0 - fractionX) * (1.0 - fractionY)
                val weight1 = fractionX * (1.0 - fractionY)
                val weight2 = (1.0 - fractionX) * fractionY
                val weight3 = fractionX * fractionY
                val vapor = atmosphericWaterMm[sourceIndex]
                val cloud = cloudWaterMm[sourceIndex]
                val temperature = airTemperatureC[sourceIndex]

                transportedAtmosphericWaterMm[destination0] += vapor * weight0
                transportedAtmosphericWaterMm[destination1] += vapor * weight1
                transportedAtmosphericWaterMm[destination2] += vapor * weight2
                transportedAtmosphericWaterMm[destination3] += vapor * weight3
                transportedCloudWaterMm[destination0] += cloud * weight0
                transportedCloudWaterMm[destination1] += cloud * weight1
                transportedCloudWaterMm[destination2] += cloud * weight2
                transportedCloudWaterMm[destination3] += cloud * weight3
                transportedAirTemperatureC[destination0] += temperature * weight0
                transportedAirTemperatureC[destination1] += temperature * weight1
                transportedAirTemperatureC[destination2] += temperature * weight2
                transportedAirTemperatureC[destination3] += temperature * weight3
                transportedAirTemperatureWeight[destination0] += weight0
                transportedAirTemperatureWeight[destination1] += weight1
                transportedAirTemperatureWeight[destination2] += weight2
                transportedAirTemperatureWeight[destination3] += weight3
            }
        }

        transportedAtmosphericWaterMm.copyInto(atmosphericWaterMm)
        transportedCloudWaterMm.copyInto(cloudWaterMm)
        for (i in 0 until count) {
            val incomingWeight = transportedAirTemperatureWeight[i]
            if (incomingWeight > 0.0) {
                airTemperatureC[i] = transportedAirTemperatureC[i] / incomingWeight
            }
        }
    }

    private fun updateWindField(simulationSeconds: Double) {
        val simulatedDays = simulationSeconds / SECONDS_PER_DAY
        val wave1Number = TWO_PI * WAVE_1_CYCLES / width
        val wave2Number = TWO_PI * WAVE_2_CYCLES / width
        val wave1Amplitude = WAVE_1_MERIDIONAL_SPEED_TILES_PER_DAY / wave1Number
        val wave2Amplitude = WAVE_2_MERIDIONAL_SPEED_TILES_PER_DAY / wave2Number
        val wave1TimePhase = TWO_PI * simulatedDays / WAVE_1_PERIOD_DAYS * WAVE_1_DIRECTION
        val wave2TimePhase = TWO_PI * simulatedDays / WAVE_2_PERIOD_DAYS * WAVE_2_DIRECTION
        updateRegionalVortexState(simulatedDays)

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
                var u = zonalWindTilesPerDay[y] +
                    wave1Amplitude * latitudeDerivative * sin(wave1Phase) +
                    wave2Amplitude * latitudeDerivative * sin(wave2Phase)
                var v = -WAVE_1_MERIDIONAL_SPEED_TILES_PER_DAY *
                    latitudeEnvelope * cos(wave1Phase) -
                    WAVE_2_MERIDIONAL_SPEED_TILES_PER_DAY *
                    latitudeEnvelope * cos(wave2Phase)

                if (regionalVorticesEnabled) {
                    for (vortex in 0 until REGIONAL_VORTEX_COUNT) {
                        val strength = vortexStrength[vortex]
                        if (strength == 0.0) continue
                        var dx = x - vortexCenterX[vortex]
                        if (dx > width / 2.0) dx -= width
                        if (dx < -width / 2.0) dx += width
                        val dy = y - vortexCenterY[vortex]
                        val radiusSquared = dx * dx * vortexInvRadiusXSquared[vortex] +
                            dy * dy * vortexInvRadiusYSquared[vortex]
                        if (radiusSquared >= 1.0) continue

                        val derivative = -6.0 * strength * (1.0 - radiusSquared) *
                            (1.0 - radiusSquared)
                        u += derivative * dy * vortexInvRadiusYSquared[vortex]
                        v -= derivative * dx * vortexInvRadiusXSquared[vortex]
                    }
                }

                windUTilesPerDay[i] = u
                windVTilesPerDay[i] = v
            }
        }
    }

    private fun initializeRegionalVortices() {
        val random = Random(world.seed xor REGIONAL_VORTEX_SEED_SALT)
        val baseLatitudeRange = height * 0.36
        val minBaseY = (height - baseLatitudeRange) * 0.5
        val maxBaseY = minBaseY + baseLatitudeRange
        val minRadiusY = minOf(VORTEX_MIN_RADIUS_Y, height * 0.11)
        val maxRadiusY = minOf(VORTEX_MAX_RADIUS_Y, height * 0.16)

        for (vortex in 0 until REGIONAL_VORTEX_COUNT) {
            vortexBaseX[vortex] = random.nextDouble(0.0, width.toDouble())
            vortexBaseY[vortex] = random.nextDouble(minBaseY, maxBaseY)
            vortexRadiusX[vortex] = random.nextDouble(VORTEX_MIN_RADIUS_X, VORTEX_MAX_RADIUS_X)
            vortexRadiusY[vortex] = random.nextDouble(minRadiusY, maxRadiusY)
            vortexInvRadiusXSquared[vortex] = 1.0 /
                (vortexRadiusX[vortex] * vortexRadiusX[vortex])
            vortexInvRadiusYSquared[vortex] = 1.0 /
                (vortexRadiusY[vortex] * vortexRadiusY[vortex])
            vortexPhaseOffset[vortex] = random.nextDouble(0.0, TWO_PI)
            vortexMotionPeriodDays[vortex] = random.nextDouble(VORTEX_MIN_MOTION_PERIOD_DAYS, VORTEX_MAX_MOTION_PERIOD_DAYS)
            vortexLifetimeDays[vortex] = random.nextDouble(VORTEX_MIN_LIFETIME_DAYS, VORTEX_MAX_LIFETIME_DAYS)
            vortexXDriftTiles[vortex] = random.nextDouble(VORTEX_MIN_X_DRIFT_TILES, VORTEX_MAX_X_DRIFT_TILES)
            vortexYDriftTiles[vortex] = random.nextDouble(
                height * VORTEX_MIN_Y_DRIFT_FRACTION,
                height * VORTEX_MAX_Y_DRIFT_FRACTION
            )
            vortexCirculationSign[vortex] = if (random.nextDouble() < 0.5) -1.0 else 1.0
        }
    }

    private fun updateRegionalVortexState(simulatedDays: Double) {
        for (vortex in 0 until REGIONAL_VORTEX_COUNT) {
            val phaseOffset = vortexPhaseOffset[vortex]
            val lifePhase = ((simulatedDays / vortexLifetimeDays[vortex] +
                phaseOffset / TWO_PI) % 1.0).let { if (it < 0.0) it + 1.0 else it }
            val fadeIn = smoothstep(0.0, VORTEX_FADE_FRACTION, lifePhase)
            val fadeOut = 1.0 - smoothstep(VORTEX_FADE_OUT_START_FRACTION, 1.0, lifePhase)
            vortexStrength[vortex] = if (regionalVorticesEnabled) {
                vortexCirculationSign[vortex] * VORTEX_STREAMFUNCTION_STRENGTH * fadeIn * fadeOut
            } else {
                0.0
            }

            val motionPhase = TWO_PI * simulatedDays / vortexMotionPeriodDays[vortex] + phaseOffset
            vortexCenterX[vortex] = wrapX(
                vortexBaseX[vortex] + vortexXDriftTiles[vortex] * sin(motionPhase)
            )
            vortexCenterY[vortex] = vortexBaseY[vortex] +
                vortexYDriftTiles[vortex] * cos(motionPhase * VORTEX_Y_MOTION_FREQUENCY)
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
    private fun wrapX(x: Double): Double = ((x % width) + width) % width

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
        const val REGIONAL_VORTEX_COUNT = 3
        const val REGIONAL_VORTEX_SEED_SALT = 0x3EEDBEEF
        const val VORTEX_MIN_RADIUS_X = 32.0
        const val VORTEX_MAX_RADIUS_X = 48.0
        const val VORTEX_MIN_RADIUS_Y = 24.0
        const val VORTEX_MAX_RADIUS_Y = 34.0
        const val VORTEX_MIN_MOTION_PERIOD_DAYS = 38.0
        const val VORTEX_MAX_MOTION_PERIOD_DAYS = 72.0
        const val VORTEX_MIN_LIFETIME_DAYS = 55.0
        const val VORTEX_MAX_LIFETIME_DAYS = 95.0
        const val VORTEX_MIN_X_DRIFT_TILES = 18.0
        const val VORTEX_MAX_X_DRIFT_TILES = 32.0
        const val VORTEX_MIN_Y_DRIFT_FRACTION = 0.03
        const val VORTEX_MAX_Y_DRIFT_FRACTION = 0.07
        const val VORTEX_STREAMFUNCTION_STRENGTH = 40.0
        const val VORTEX_FADE_FRACTION = 0.14
        const val VORTEX_FADE_OUT_START_FRACTION = 0.82
        const val VORTEX_Y_MOTION_FREQUENCY = 0.73
        const val CONDENSATION_RESPONSE_SECONDS = 15.0 * 60.0
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
