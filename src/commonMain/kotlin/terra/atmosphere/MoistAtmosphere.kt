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
    val solarPeakWm2: Double = 1000.0,
    val surfaceAlbedo: Double = 0.18,
    val atmosphericEmissivity: Double = 0.8,
    val cloudSolarReflection: Double = 0.45,
    val bulkExchangeCoefficient: Double = 0.001,
    val unresolvedGustMetersPerSecond: Double = 2.0,
    val condensationSeconds: Double = 300.0,
    val cloudEvaporationSeconds: Double = 900.0,
    val rainoutSeconds: Double = 3600.0,
    val rainCloudThresholdKgM2: Double = 0.1,
    val radiationEnabled: Boolean = true,
    val surfaceExchangeEnabled: Boolean = true
) {
    init {
        require(listOf(referenceTemperatureKelvin, referenceDryColumnKgM2, surfaceHeatCapacityJm2K,
            airHeatCapacityJkgK, latentHeatJkg, condensationSeconds, cloudEvaporationSeconds, rainoutSeconds)
            .all { it.isFinite() && it > 0 })
        require(humidColumnFraction > 0 && humidColumnFraction <= 1 && cloudPressureFraction > 0 && cloudPressureFraction <= 1)
        require(listOf(surfaceAlbedo, atmosphericEmissivity, cloudSolarReflection).all { it.isFinite() && it in 0.0..1.0 })
        require(listOf(solarPeakWm2, bulkExchangeCoefficient, unresolvedGustMetersPerSecond, rainCloudThresholdKgM2)
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
    val exportedWaterKg: Double = 0.0
)

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
    private val surfaceWater: DoubleArray
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
    initializeColumns: ((Double, Double) -> MoistCell)? = null
) {
    private val columns = MoistColumnTransport(config.cellCount, physics)
    private val flow = DryAtmosphere(config, columns, DryScenarios.initializer(scenario, config, seed))
    private val density = physics.referenceDryColumnKgM2 / config.meanHeightMeters
    private val surfaceTemperature = DoubleArray(config.cellCount)
    private val surfaceWater = DoubleArray(config.cellCount)
    private val rainRate = DoubleArray(config.cellCount)
    private val rainTotal = DoubleArray(config.cellCount)
    private var initialWater = 0.0
    private var initialEnergy = 0.0
    private var radiationEnergy = 0.0
    private var evaporationTotal = 0.0
    private var precipitationTotal = 0.0
    private val condensationResponse = 1 - exp(-config.stepSeconds / physics.condensationSeconds)
    private val cloudEvaporationResponse = 1 - exp(-config.stepSeconds / physics.cloudEvaporationSeconds)
    private val rainResponse = 1 - exp(-config.stepSeconds / physics.rainoutSeconds)
    val completedSteps: Long get() = flow.completedSteps
    val elapsedSeconds: Double get() = flow.elapsedSeconds
    fun temperatureKelvinAt(cell: Int): Double = columns.temperatureAt(cell, flow.depthAt(cell))

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
        surface?.let { s -> DoubleArray(config.cellCount) { s.waterMm(it) } } ?: surfaceWater.copyOf()
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
            if (initialEnergy > 0) energy.value - initialEnergy - radiationEnergy else 0.0, exported
        )
    }

    /** Includes the temperature-dependent shallow-water potential energy, not just caloric heat. */
    private fun airHeatCapacity(depth: Double): Double = density * depth *
        (physics.airHeatCapacityJkgK + 0.5 * config.gravity * depth / physics.referenceTemperatureKelvin)

    private fun surfaceCapacity(cell: Int): Double = surface?.heatCapacityJm2K(cell) ?: physics.surfaceHeatCapacityJm2K

    private fun exchangeHeatAndWater() {
        val dt = config.stepSeconds
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val i = y * config.width + x
            val surfaceCapacity = surfaceCapacity(i)
            val depth = flow.depthAt(i)
            val mass = density * depth
            val capacity = airHeatCapacity(depth)
            var temperature = columns.temperatureAt(i, depth)
            var surface = surfaceTemperature[i]
            var vapor = density * columns.values[1][i]
            var cloud = density * columns.values[2][i]
            var surfaceHeat = 0.0
            var airHeat = 0.0
            if (physics.radiationEnabled) {
                val sun = max(0.0, cos(2 * PI * (elapsedSeconds / 86400 + (x + 0.5) / config.width - 0.5)))
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
            val transfer = phaseTransfer(vapor, cloud, temperature, pressure, mass, capacity)
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
