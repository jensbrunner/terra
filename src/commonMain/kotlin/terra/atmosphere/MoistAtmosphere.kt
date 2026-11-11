package terra.atmosphere

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Warm-cloud, idealized ocean parameters. Water amounts in kg/m² are also liquid-depth mm. */
data class MoistPhysicsConfig(
    val referenceTemperatureKelvin: Double = 280.0,
    val referenceDryColumnKgM2: Double = 8000.0,
    val humidColumnFraction: Double = 0.45,
    val cloudPressureFraction: Double = 0.75,
    val surfaceHeatCapacityJm2K: Double = 41_800_000.0,
    val airHeatCapacityJkgK: Double = 1004.0,
    val latentHeatJkg: Double = 2_500_000.0,
    /** Incoming normal-beam flux before model albedo/cloud reflection, not surface noon irradiance. */
    val solarPeakWm2: Double = 1360.0,
    val solar: SolarConfig = SolarConfig(),
    /** Weak column-integrated drag, a 30-day e-folding time; dissipated kinetic energy becomes heat. */
    val momentumDragPerSecond: Double = 1.0 / (30 * 86400.0),
    val surfaceAlbedo: Double = 0.18,
    val atmosphericEmissivity: Double = 0.8,
    val cloudSolarReflection: Double = 0.45,
    val bulkExchangeCoefficient: Double = 0.001,
    val unresolvedGustMetersPerSecond: Double = 2.0,
    val condensationSeconds: Double = 300.0,
    val cloudEvaporationSeconds: Double = 900.0,
    val rainoutSeconds: Double = 3600.0,
    val rainCloudThresholdKgM2: Double = 0.1,
    /** Diagnostic parcel residence/ascent; no extra atmospheric layer or cloud inventory. */
    val liftResidenceSeconds: Double = 600.0,
    val maximumParcelLiftMeters: Double = 1500.0,
    val convectiveMixingDepthMeters: Double = 1500.0,
    val convectiveVelocityCoefficient: Double = 0.02,
    val maximumConvectiveVelocityMetersPerSecond: Double = 1.0,
    val radiationEnabled: Boolean = true,
    val surfaceExchangeEnabled: Boolean = true
) {
    init {
        require(listOf(referenceTemperatureKelvin, referenceDryColumnKgM2, surfaceHeatCapacityJm2K,
            airHeatCapacityJkgK, latentHeatJkg, condensationSeconds, cloudEvaporationSeconds, rainoutSeconds,
            liftResidenceSeconds, convectiveMixingDepthMeters)
            .all { it.isFinite() && it > 0 })
        require(humidColumnFraction > 0 && humidColumnFraction <= 1 && cloudPressureFraction > 0 && cloudPressureFraction <= 1)
        require(listOf(surfaceAlbedo, atmosphericEmissivity, cloudSolarReflection).all { it.isFinite() && it in 0.0..1.0 })
        require(listOf(solarPeakWm2, momentumDragPerSecond, bulkExchangeCoefficient, unresolvedGustMetersPerSecond, rainCloudThresholdKgM2,
            maximumParcelLiftMeters, convectiveVelocityCoefficient, maximumConvectiveVelocityMetersPerSecond)
            .all { it.isFinite() && it >= 0 })
    }
}

data class MoistCell(
    val temperatureKelvin: Double = 280.0,
    val relativeHumidity: Double = 0.9,
    val cloudWaterKgM2: Double = 0.0,
    val surfaceTemperatureKelvin: Double = 288.0,
    val surfaceWaterKgM2: Double = 10_000.0
)

data class MoistAtmosphereDiagnostics(
    val totalWaterKg: Double,
    val relativeWaterResidual: Double,
    val meanVaporMm: Double,
    val meanCloudMm: Double,
    val meanRainTotalMm: Double,
    val maximumRainMmPerHour: Double,
    val meanAirTemperatureKelvin: Double,
    val totalEvaporationKg: Double,
    val totalRainKg: Double,
    val totalModelEnergyJoules: Double,
    val netRadiationJoules: Double,
    /** Includes numerical transport/mechanical-energy error, not an unreported physical heat source. */
    val energyResidualJoules: Double,
    val exportedWaterKg: Double = 0.0,
    /** Internal kinetic-to-thermal transfer, already included in total energy; not an external source. */
    val frictionHeatingJoules: Double = 0.0,
    /** Measured discrete flow/tracer energy change; it can have either sign. */
    val numericalTransportEnergyJoules: Double = 0.0,
    val includesApproximateHistory: Boolean = false,
    val atmosphereInitializationEnergyJoules: Double = 0.0
) {
    val unexplainedEnergyResidualJoules: Double get() = energyResidualJoules - numericalTransportEnergyJoules
}

class MoistAtmosphereSnapshot internal constructor(
    val dynamics: DryAtmosphereSnapshot,
    val physics: MoistPhysicsConfig,
    val diagnostics: MoistAtmosphereDiagnostics,
    private val temperature: DoubleArray,
    private val vapor: DoubleArray,
    private val cloud: DoubleArray,
    private val rain: DoubleArray,
    private val totalRain: DoubleArray,
    private val surfaceTemperature: DoubleArray,
    private val surfaceWater: DoubleArray, val worldSeconds: Double
) {
    private fun index(x: Int, y: Int) = y * dynamics.config.width + x
    fun temperatureKelvinAt(x: Int, y: Int): Double = temperature[index(x, y)]
    fun vaporMmAt(x: Int, y: Int): Double = vapor[index(x, y)]
    fun cloudWaterMmAt(x: Int, y: Int): Double = cloud[index(x, y)]
    fun rainMmPerHourAt(x: Int, y: Int): Double = rain[index(x, y)]
    fun rainTotalMmAt(x: Int, y: Int): Double = totalRain[index(x, y)]
    fun surfaceTemperatureKelvinAt(x: Int, y: Int): Double = surfaceTemperature[index(x, y)]
    fun surfaceWaterMmAt(x: Int, y: Int): Double = surfaceWater[index(x, y)]
    fun relativeHumidityAt(x: Int, y: Int): Double {
        val mass = physics.referenceDryColumnKgM2 * dynamics.heightAt(x, y) / dynamics.config.meanHeightMeters
        val pressure = mass * dynamics.config.gravity * physics.cloudPressureFraction
        return vaporMmAt(x, y) / (mass * physics.humidColumnFraction * WarmCloud.saturationMixingRatio(temperatureKelvinAt(x, y), pressure))
    }
}

/** Deliberate atmosphere reconstruction, not a conservative heat correction or a time step. */
data class AtmosphereHandoff(
    val worldSeconds: Double, val waterBeforeKg: Double, val waterAfterKg: Double,
    val vaporKg: Double, val cloudKg: Double, val airTemperatureKelvin: Double,
    val initializationEnergyChangeJoules: Double, val physicalStepsAtHandoff: Long,
    val spinInSteps: Int = 0, val vaporRelativeHumidityLimit: Double = 0.9
)

/**
 * Thermal shallow water (pressure = g*h²*T/(2*Tref)) plus dilute vapor/cloud tracers.
 * T=Tref recovers the dry pressure law. Phase heating changes pressure forces.
 * A finite ocean exchanges water and heat; only radiation is an external energy flux.
 */
class MoistAtmosphere(
    val config: DryAtmosphereConfig = DryAtmosphereConfig(),
    val physics: MoistPhysicsConfig = MoistPhysicsConfig(),
    scenario: DryScenario = DryScenario.JET,
    seed: Int = 31742,
    private val surface: MoistSurface? = null,
    initializeFlow: ((Double, Double) -> DryCell)? = null,
    initializeColumns: ((Double, Double) -> MoistCell)? = null
) {
    private val columns = MoistColumnTransport(config.cellCount, physics)
    private val flow = DryAtmosphere(config, columns, initializeFlow ?: DryScenarios.initializer(scenario, config, seed))
    private val density = physics.referenceDryColumnKgM2 / config.meanHeightMeters
    val surfaceThermalState = SurfaceThermalState(config.cellCount)
    private val surfaceTemperature = surfaceThermalState.kelvin
    private val surfaceWater = DoubleArray(config.cellCount)
    private val rainRate = DoubleArray(config.cellCount)
    private val rainTotal = DoubleArray(config.cellCount)
    private var worldEpochSeconds = 0.0
    private var approximateHistory = false
    private var initializationEnergy = 0.0
    private var initialWater = 0.0
    private var initialEnergy = 0.0
    private var radiationEnergy = 0.0
    private var frictionHeating = 0.0
    private val transportEnergyChange = CompensatedSum()
    private val sunlight = PlanetarySunlight(config.width, config.height, physics.solar)
    private var evaporationTotal = 0.0
    private var precipitationTotal = 0.0
    private val condensationResponse = 1 - exp(-config.stepSeconds / physics.condensationSeconds)
    private val cloudEvaporationResponse = 1 - exp(-config.stepSeconds / physics.cloudEvaporationSeconds)
    private val rainResponse = 1 - exp(-config.stepSeconds / physics.rainoutSeconds)
    val completedSteps: Long get() = flow.completedSteps
    val elapsedSeconds: Double get() = flow.elapsedSeconds
    val worldSeconds: Double get() = worldEpochSeconds + elapsedSeconds
    fun temperatureKelvinAt(cell: Int): Double = columns.temperatureAt(cell, flow.depthAt(cell))
    fun surfaceTemperatureKelvinAt(cell: Int): Double = surfaceTemperature[cell]
    fun rainMmThisStepAt(cell: Int): Double = rainRate[cell] * config.stepSeconds / 3600.0

    /** The same column water is the effective climate pool while detailed dynamics are dormant. */
    internal fun effectiveMoistureMmAt(cell: Int): Double = density * (columns.values[1][cell] + columns.values[2][cell])
    internal val atmosphericWaterKg: Double get() = (0 until config.cellCount).sumOf { effectiveMoistureMmAt(it) } * config.cellArea
    internal fun receiveEffectiveMoistureMm(cell: Int, amount: Double) {
        require(amount.isFinite() && amount >= 0.0)
        columns.values[1][cell] += amount / density
    }
    internal fun withdrawEffectiveMoistureMm(cell: Int, requested: Double): Double {
        require(requested.isFinite() && requested >= 0.0)
        if (requested == 0.0) return 0.0
        val cloud = density * columns.values[2][cell]
        val fromCloud = min(cloud, requested)
        val vapor = density * columns.values[1][cell]
        val fromVapor = min(vapor, max(0.0, requested - fromCloud))
        columns.values[2][cell] = (cloud - fromCloud) / density
        columns.values[1][cell] = (vapor - fromVapor) / density
        return fromCloud + fromVapor
    }

    internal fun markClimateEvolution() { approximateHistory = true }

    /** Uniform air T + flat height + zero wind is exactly at rest before surface forcing resumes.
     * Retain each column's total water; vapor is capped at 90% saturation and excess is cloud.
     */
    internal fun initializeFromClimate(atWorldSeconds: Double): AtmosphereHandoff {
        require(atWorldSeconds.isFinite() && atWorldSeconds >= 0.0)
        val water = DoubleArray(config.cellCount) { effectiveMoistureMmAt(it) }
        val before = atmosphericWaterKg
        val energyBefore = diagnostics().totalModelEnergyJoules
        val temperature = (surfaceTemperature.average() - 8.0).coerceIn(240.0, 300.0)
        val depth = flow.initializeRestState()
        val mass = density * depth
        val pressure = mass * config.gravity * physics.cloudPressureFraction
        val vaporTarget = 0.9
        val vaporLimit = vaporTarget * mass * physics.humidColumnFraction * WarmCloud.saturationMixingRatio(temperature, pressure)
        var vaporTotal = 0.0
        var cloudTotal = 0.0
        for (i in water.indices) {
            val vapor = min(water[i], vaporLimit)
            val cloud = water[i] - vapor
            columns.values[0][i] = depth * temperature
            columns.values[1][i] = vapor / density
            columns.values[2][i] = cloud / density
            vaporTotal += vapor; cloudTotal += cloud
        }
        worldEpochSeconds = atWorldSeconds - elapsedSeconds
        rainRate.fill(0.0)
        approximateHistory = true
        val energyChange = diagnostics().totalModelEnergyJoules - energyBefore
        initializationEnergy += energyChange
        val after = atmosphericWaterKg
        check(kotlin.math.abs(after - before) <= 1e-12 * max(1.0, before))
        return AtmosphereHandoff(atWorldSeconds, before, after, vaporTotal * config.cellArea,
            cloudTotal * config.cellArea, temperature, energyChange, completedSteps, vaporRelativeHumidityLimit = vaporTarget)
    }

    init {
        val phase = Random(seed + 1).nextDouble() * 2 * PI
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val i = y * config.width + x
            val px = (x + 0.5) * config.dx
            val py = (y + 0.5) * config.dy
            val initial = initializeColumns?.invoke(px, py) ?: MoistCell(
                temperatureKelvin = physics.referenceTemperatureKelvin,
                relativeHumidity = 0.65 + 0.30 * sin(2 * PI * px / config.domainWidthMeters + phase) *
                    cos(2 * PI * py / config.domainHeightMeters)
            )
            require(initial.temperatureKelvin.isFinite() && initial.temperatureKelvin > 0 &&
                initial.surfaceTemperatureKelvin.isFinite() && initial.surfaceTemperatureKelvin > 0)
            require(listOf(initial.relativeHumidity, initial.cloudWaterKgM2, initial.surfaceWaterKgM2).all { it.isFinite() && it >= 0 })
            val depth = flow.depthAt(i)
            val mass = density * depth
            val saturation = physics.humidColumnFraction * WarmCloud.saturationMixingRatio(
                initial.temperatureKelvin, mass * config.gravity * physics.cloudPressureFraction)
            columns.values[0][i] = depth * initial.temperatureKelvin
            columns.values[1][i] = depth * saturation * initial.relativeHumidity
            columns.values[2][i] = initial.cloudWaterKgM2 / density
            surfaceTemperature[i] = initial.surfaceTemperatureKelvin
            surfaceWater[i] = initial.surfaceWaterKgM2
        }
        val initial = diagnostics()
        initialWater = initial.totalWaterKg
        initialEnergy = initial.totalModelEnergyJoules
    }

    fun step() {
        flow.step()
        exchangeHeatAndWater()
    }

    fun snapshot(): MoistAtmosphereSnapshot = MoistAtmosphereSnapshot(
        flow.snapshot(), physics, diagnostics(),
        DoubleArray(config.cellCount) { columns.temperatureAt(it, flow.depthAt(it)) },
        DoubleArray(config.cellCount) { density * columns.values[1][it] },
        DoubleArray(config.cellCount) { density * columns.values[2][it] },
        rainRate.copyOf(), rainTotal.copyOf(), surfaceTemperature.copyOf(),
        surface?.let { s -> DoubleArray(config.cellCount) { s.waterMm(it) } } ?: surfaceWater.copyOf(), worldSeconds
    )

    fun diagnostics(): MoistAtmosphereDiagnostics {
        val water = CompensatedSum()
        val energy = CompensatedSum()
        val vapor = CompensatedSum()
        val cloud = CompensatedSum()
        val temperature = CompensatedSum()
        var peakRain = 0.0
        for (i in 0 until config.cellCount) {
            val depth = flow.depthAt(i)
            val t = columns.temperatureAt(i, depth)
            val v = density * columns.values[1][i]
            val c = density * columns.values[2][i]
            water.add((v + c + if (surface == null) surfaceWater[i] else 0.0) * config.cellArea)
            energy.add((density * flow.kineticEnergyAt(i) + airHeatCapacity(depth) * t +
                physics.latentHeatJkg * v + surfaceCapacity(i) * surfaceTemperature[i]) * config.cellArea)
            vapor.add(v); cloud.add(c); temperature.add(t)
            peakRain = max(peakRain, rainRate[i])
        }
        surface?.let { water.add(it.totalWaterKg) }
        val exported = surface?.exportedWaterKg ?: 0.0
        return MoistAtmosphereDiagnostics(
            water.value, if (initialWater > 0) (water.value + exported - initialWater) / initialWater else 0.0,
            vapor.value / config.cellCount, cloud.value / config.cellCount,
            precipitationTotal / (config.cellCount * config.cellArea), peakRain, temperature.value / config.cellCount,
            evaporationTotal, precipitationTotal, energy.value, radiationEnergy,
            if (initialEnergy > 0) energy.value - initialEnergy - radiationEnergy else 0.0, exported, frictionHeating, transportEnergyChange.value, approximateHistory, initializationEnergy
        )
    }

    /** Includes the temperature-dependent shallow-water potential energy, not just caloric heat. */
    private fun airHeatCapacity(depth: Double): Double = density * depth *
        (physics.airHeatCapacityJkgK + 0.5 * config.gravity * depth / physics.referenceTemperatureKelvin)

    private fun surfaceCapacity(cell: Int): Double = surface?.heatCapacityJm2K(cell) ?: physics.surfaceHeatCapacityJm2K

    private fun exchangeHeatAndWater() {
        val dt = config.stepSeconds
        sunlight.update(worldSeconds - 0.5 * dt)
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val i = y * config.width + x
            val surfaceCapacity = surfaceCapacity(i)
            val depth = flow.depthAt(i)
            val mass = density * depth
            val capacity = airHeatCapacity(depth)
            // Attribute discrete transport error before applying any physical heat/water source.
            val oldDepth = flow.previousDepthAt(i)
            val oldTemperature = columns.base[0][i] / oldDepth
            val transportedEnergy = density * (flow.kineticEnergyAt(i) - flow.previousKineticEnergyAt(i)) +
                airHeatCapacity(depth) * columns.temperatureAt(i, depth) - airHeatCapacity(oldDepth) * oldTemperature +
                physics.latentHeatJkg * density * (columns.values[1][i] - columns.base[1][i])
            transportEnergyChange.add(transportedEnergy * config.cellArea)
            val roughness = this.surface?.momentumDragMultiplier(i) ?: 1.0
            check(roughness.isFinite() && roughness >= 0) { "Invalid surface drag at cell $i" }
            val retention = exp(-physics.momentumDragPerSecond * roughness * dt)
            val dissipated = density * flow.dampMomentumAt(i, retention)
            var temperature = columns.temperatureAt(i, depth) + dissipated / capacity
            frictionHeating += dissipated * config.cellArea
            var surface = surfaceTemperature[i]
            var vapor = density * columns.values[1][i]
            var cloud = density * columns.values[2][i]
            var surfaceHeat = 0.0
            var airHeat = 0.0
            if (physics.radiationEnabled) {
                val sun = sunlight.inputFactor(x, y)
                val cover = 1 - exp(-cloud / 0.15)
                val albedo = this.surface?.albedo(i) ?: physics.surfaceAlbedo
                val solar = physics.solarPeakWm2 * sun * (1 - albedo) * (1 - physics.cloudSolarReflection * cover)
                val surfaceEmission = WarmCloud.emission(surface)
                val airEmission = physics.atmosphericEmissivity * WarmCloud.emission(temperature)
                surfaceHeat = (solar + airEmission - surfaceEmission) * dt
                airHeat = (physics.atmosphericEmissivity * surfaceEmission - 2 * airEmission) * dt
                surface += surfaceHeat / surfaceCapacity
                temperature += airHeat / capacity
            }
            var evaporated = 0.0
            if (physics.surfaceExchangeEnabled) {
                val speed = sqrt(flow.speedAt(i) * flow.speedAt(i) +
                    physics.unresolvedGustMetersPerSecond * physics.unresolvedGustMetersPerSecond)
                val surfacePressure = mass * config.gravity
                val airDensity = surfacePressure / (287.0 * surface)
                val exchange = airDensity * physics.bulkExchangeCoefficient * speed
                // Exact two-reservoir sensible exchange cannot overshoot thermal equilibrium.
                val inverseCapacity = 1 / surfaceCapacity + 1 / capacity
                val sensible = (surface - temperature) / inverseCapacity *
                    (1 - exp(-exchange * physics.airHeatCapacityJkgK * inverseCapacity * dt))
                surface -= sensible / surfaceCapacity
                temperature += sensible / capacity
                val surfaceSaturation = WarmCloud.saturationMixingRatio(surface, surfacePressure)
                val nearSurfaceHumidity = vapor / mass / physics.humidColumnFraction
                val potential = exchange * max(0.0, surfaceSaturation - nearSurfaceHumidity) * dt
                // Liquid-only model: evaporation may use stored heat down to freezing, never free energy.
                val energyAvailable = max(0.0, surface - 273.15) * surfaceCapacity
                val affordable = min(potential, energyAvailable / physics.latentHeatJkg)
                evaporated = this.surface?.evaporate(i, affordable) ?: min(surfaceWater[i], affordable)
                check(evaporated.isFinite() && evaporated >= 0 && evaporated <= affordable + 1e-12)
                surface -= physics.latentHeatJkg * evaporated / surfaceCapacity
                vapor += evaporated
            }
            val pressure = mass * config.gravity * physics.cloudPressureFraction
            val orographic = this.surface?.orographicUpliftMetersPerSecond(i, flow.eastWindAt(i), flow.northWindAt(i)) ?: 0.0
            check(orographic.isFinite() && orographic >= 0) { "Invalid terrain ascent at cell $i" }
            val lapse = config.gravity / physics.airHeatCapacityJkgK
            // Instability relative to a dry-adiabatic mixed column, not simply any warm surface.
            val excess = max(0.0, surface - temperature - lapse * physics.convectiveMixingDepthMeters)
            val convective = min(physics.maximumConvectiveVelocityMetersPerSecond,
                physics.convectiveVelocityCoefficient * sqrt(config.gravity * physics.convectiveMixingDepthMeters * excess / temperature))
            val lift = min(physics.maximumParcelLiftMeters, (orographic + convective) * physics.liftResidenceSeconds)
            val parcelTemperature = temperature - lapse * lift
            val parcelPressure = pressure * exp(-config.gravity * lift / (287.0 * temperature))
            check(parcelTemperature > 0 && parcelPressure > 0) { "Invalid lifted parcel at cell $i" }
            // One parcel state for BOTH condensation and evaporation. Its adiabatic
            // temperature is diagnostic; only actual latent transfer changes bulk heat.
            val transfer = phaseTransfer(vapor, cloud, parcelTemperature, parcelPressure, mass, capacity)
            vapor -= transfer
            cloud += transfer
            temperature += physics.latentHeatJkg * transfer / capacity
            val rain = max(0.0, cloud - physics.rainCloudThresholdKgM2) * rainResponse
            cloud -= rain
            val surfaceAmount = if (this.surface == null) surfaceWater[i] - evaporated + rain else 0.0
            check(temperature.isFinite() && temperature > 0 && surface.isFinite() && surface > 0 &&
                vapor.isFinite() && vapor >= 0 && cloud.isFinite() && cloud >= 0 && surfaceAmount.isFinite() && surfaceAmount >= 0) {
                "Invalid moist column at ($x,$y); no temperature or water clamp was applied"
            }
            columns.values[0][i] = depth * temperature
            columns.values[1][i] = vapor / density
            columns.values[2][i] = cloud / density
            surfaceTemperature[i] = surface
            surfaceWater[i] = surfaceAmount
            this.surface?.receiveRain(i, rain)
            rainRate[i] = rain * 3600 / dt
            rainTotal[i] += rain
            radiationEnergy += (surfaceHeat + airHeat) * config.cellArea
            evaporationTotal += evaporated * config.cellArea
            precipitationTotal += rain * config.cellArea
        }
    }

    /** One phase adjustment at one represented temperature, with latent heating/cooling in the solve. */
    private fun phaseTransfer(vapor: Double, cloud: Double, t: Double, pressure: Double, mass: Double, capacity: Double): Double {
        val saturation = mass * physics.humidColumnFraction * WarmCloud.saturationMixingRatio(t, pressure)
        if (vapor == saturation || (vapor < saturation && cloud == 0.0)) return 0.0
        var transfer = 0.0
        repeat(3) {
            val trialTemperature = t + physics.latentHeatJkg * transfer / capacity
            val e = WarmCloud.vaporPressure(trialTemperature)
            check(e < pressure) { "Warm-cloud saturation pressure exceeds column pressure" }
            val saturationMass = mass * physics.humidColumnFraction * 0.622 * e / (pressure - e)
            val derivative = mass * physics.humidColumnFraction * 0.622 * pressure * e *
                WarmCloud.saturationLatentHeat / (461.5 * trialTemperature * trialTemperature * (pressure - e) * (pressure - e))
            transfer += (vapor - transfer - saturationMass) / (1 + physics.latentHeatJkg * derivative / capacity)
            // Transfer limits are donor availability, not corrections to a negative state.
            transfer = min(vapor, max(-cloud, transfer))
        }
        return transfer * if (vapor > saturation) condensationResponse else cloudEvaporationResponse
    }
}

internal object WarmCloud {
    const val saturationLatentHeat = 2_500_000.0
    fun vaporPressure(temperature: Double): Double = 611.2 * exp(saturationLatentHeat / 461.5 * (1 / 273.15 - 1 / temperature))
    fun saturationMixingRatio(temperature: Double, pressure: Double): Double {
        val e = vaporPressure(temperature)
        check(e.isFinite() && pressure > e) { "Invalid pressure/temperature for warm-cloud saturation" }
        return 0.622 * e / (pressure - e)
    }
    fun emission(temperature: Double): Double {
        val squared = temperature * temperature
        return 5.670374419e-8 * squared * squared
    }
}
