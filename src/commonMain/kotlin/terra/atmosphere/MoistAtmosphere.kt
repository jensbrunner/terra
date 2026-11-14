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
    /** Momentum supplied by unresolved overturning to narrow, potentially unstable jets.
     * No storm positions or perturbations are supplied. Zero disables this closure.
     */
    val overturningWindMetersPerSecond: Double = 60.0,
    val overturningSeconds: Double = 5 * 86400.0,
    val surfaceAlbedo: Double = 0.18,
    val atmosphericEmissivity: Double = 0.8,
    val cloudSolarReflection: Double = 0.45,
    val bulkExchangeCoefficient: Double = 0.001,
    val unresolvedGustMetersPerSecond: Double = 2.0,
    val condensationSeconds: Double = 300.0,
    val cloudEvaporationSeconds: Double = 900.0,
    // Rainfall is episodic: once precipitation begins, condensate rains out much
    // faster than the cloud-autoconversion timescale used by the old drizzle model.
    val rainoutSeconds: Double = 480.0,
    val rainCloudThresholdKgM2: Double = 0.2,
    val rainOnsetKgM2: Double = 0.50,
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
            liftResidenceSeconds, convectiveMixingDepthMeters, overturningSeconds)
            .all { it.isFinite() && it > 0 })
        require(rainOnsetKgM2.isFinite() && rainOnsetKgM2 > rainCloudThresholdKgM2)
        require(humidColumnFraction > 0 && humidColumnFraction <= 1 && cloudPressureFraction > 0 && cloudPressureFraction <= 1)
        require(listOf(surfaceAlbedo, atmosphericEmissivity, cloudSolarReflection).all { it.isFinite() && it in 0.0..1.0 })
        require(listOf(solarPeakWm2, momentumDragPerSecond, bulkExchangeCoefficient, unresolvedGustMetersPerSecond, rainCloudThresholdKgM2,
            overturningWindMetersPerSecond, maximumParcelLiftMeters, convectiveVelocityCoefficient, maximumConvectiveVelocityMetersPerSecond)
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
    val atmosphereInitializationEnergyJoules: Double = 0.0,
    val overturningEnergyJoules: Double = 0.0
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
    /** Computed once from this snapshot; publication eagerly requests it on the owner worker. */
    val horizontalDynamics: AtmosphericDynamicsSnapshot by lazy { AtmosphericDynamicsSnapshot(this) }
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
    val spinInSteps: Int = 0, val vaporRelativeHumidityLimit: Double = 1.0,
    val minimumAirTemperatureKelvin: Double = airTemperatureKelvin,
    val maximumAirTemperatureKelvin: Double = airTemperatureKelvin,
    val maximumWindMetersPerSecond: Double = 0.0,
    val rootMeanSquareWindMetersPerSecond: Double = 0.0,
    val reconstructionPressureExponent: Double = 0.0
)

/**
 * Thermal shallow water (pressure = g*h²*T/(2*Tref)) plus dilute vapor/cloud tracers.
 * T=Tref recovers the dry pressure law. Phase heating changes pressure forces.
 * An attached surface owns soil/snow and tracks open-boundary exchange. Standalone
 * numerical experiments retain their own closed surface-water store.
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
    companion object {
        internal const val CLIMATE_AIR_SURFACE_OFFSET_K = 6.0
    }

    private val columns = MoistColumnTransport(config, physics)
    private val flow = DryAtmosphere(config, columns, initializeFlow ?: DryScenarios.initializer(scenario, config, seed))
    private val reconstructionSeed = seed
    private val density = physics.referenceDryColumnKgM2 / config.meanHeightMeters
    val surfaceThermalState = SurfaceThermalState(config.cellCount)
    private val surfaceTemperature = surfaceThermalState.kelvin
    private val surfaceWater = DoubleArray(config.cellCount)
    private val rainRate = DoubleArray(config.cellCount)
    private val rainActive = BooleanArray(config.cellCount)
    private val rainTotal = DoubleArray(config.cellCount)
    private var worldEpochSeconds = 0.0
    private var approximateHistory = false
    private var initializationEnergy = 0.0
    private var initialWater = 0.0
    private var initialEnergy = 0.0
    private var radiationEnergy = 0.0
    private var frictionHeating = 0.0
    private var overturningEnergy = 0.0
    private val overturningResponse = if (physics.overturningWindMetersPerSecond > 0 && physics.radiationEnabled && physics.surfaceExchangeEnabled && !config.periodicY)
        1 - exp(-config.stepSeconds / physics.overturningSeconds) else 0.0
    private val overturningTarget = DoubleArray(config.height) { y ->
        val latitude = PlanetLatitude.atmosphereRowRadians(y, config.height)
        // Eight-degree shear zones can change the sign of the meridional PV gradient.
        // The solver, not the forcing, decides whether and where eddies form.
        physics.overturningWindMetersPerSecond * (exp(-0.5 * ((latitude - 0.70) / 0.14).let { it * it }) +
            exp(-0.5 * ((latitude + 0.70) / 0.14).let { it * it }))
    }
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

    /** Read-only diagnostic capacity; does not condense or move column water. */
    internal fun saturatedVaporMmAt(cell: Int, temperatureKelvin: Double): Double {
        require(temperatureKelvin.isFinite() && temperatureKelvin > 0)
        val mass = density * flow.depthAt(cell)
        return ColumnThermodynamics.saturationMm(temperatureKelvin, mass, config.gravity, physics)
    }

    /** Physical detailed column water (vapor plus condensate). */
    internal fun effectiveMoistureMmAt(cell: Int): Double = density * (columns.values[1][cell] + columns.values[2][cell])
    internal val atmosphericWaterKg: Double get() = (0 until config.cellCount).sumOf { effectiveMoistureMmAt(it) } * config.cellArea
    /** Explicit climatological realization. Water introduced here is an initialization flux,
     * not conservation of a diagnostic atlas humidity or the dormant detailed atmosphere. */
    internal fun initializeAtlasRealization(atSeconds: Double, temperature: DoubleArray, humidity: DoubleArray,
        east: DoubleArray, north: DoubleArray): AtmosphereHandoff {
        val before = atmosphericWaterKg
        val energyBefore = diagnostics().totalModelEnergyJoules
        flow.initializeClimateCirculation(temperature)
        flow.initializeAtlasWinds(east, north)
        var vapor = 0.0
        for (i in temperature.indices) {
            val amount = saturatedVaporMmAt(i, temperature[i]) * humidity[i]
            columns.values[0][i] = flow.depthAt(i) * temperature[i]
            columns.values[1][i] = amount / density
            columns.values[2][i] = 0.0
            surfaceTemperature[i] = temperature[i]
            vapor += amount
        }
        worldEpochSeconds = atSeconds - elapsedSeconds
        rainRate.fill(0.0); rainActive.fill(false)
        approximateHistory = true
        val energyChange = diagnostics().totalModelEnergyJoules - energyBefore
        initializationEnergy += energyChange
        // Detailed accounting starts from this explicitly initialized realization.
        initialWater = diagnostics().totalWaterKg + (surface?.exportedWaterKg ?: 0.0)
        return AtmosphereHandoff(atSeconds, before, atmosphericWaterKg, vapor * config.cellArea, 0.0,
            temperature.average(), energyChange, completedSteps, minimumAirTemperatureKelvin = temperature.min(),
            maximumAirTemperatureKelvin = temperature.max(),
            maximumWindMetersPerSecond = east.indices.maxOf { sqrt(east[it] * east[it] + north[it] * north[it]) },
            rootMeanSquareWindMetersPerSecond = sqrt(east.indices.sumOf { east[it] * east[it] + north[it] * north[it] } / east.size))
    }

    /** Reconstruct from current climate temperatures, conserving atmospheric water.
     * Vapor/condensate partition at saturation; pressure is approximately balanced
     * without new-world moisture, old storms or hidden physical advancement.
     */
    internal fun initializeFromClimate(atWorldSeconds: Double, evolvedAirTemperatureKelvin: DoubleArray? = null): AtmosphereHandoff {
        require(atWorldSeconds.isFinite() && atWorldSeconds >= 0.0)
        val water = DoubleArray(config.cellCount) { effectiveMoistureMmAt(it) }
        val before = atmosphericWaterKg
        val energyBefore = diagnostics().totalModelEnergyJoules
        val temperature = evolvedAirTemperatureKelvin?.also {
            require(it.size == config.cellCount && it.all { kelvin -> kelvin.isFinite() && kelvin > 0.0 })
        }?.copyOf() ?: DoubleArray(config.cellCount) { surfaceTemperature[it] - CLIMATE_AIR_SURFACE_OFFSET_K }
        val circulation = flow.initializeClimateCirculation(temperature, atWorldSeconds / (7 * 86400.0), reconstructionSeed)
        // Smooth local conservative exchange resolves moist low-pressure sectors.
        // Pairwise relaxation preserves water and positivity without block boundaries
        // or a global moisture funding pool. It is initialization, not elapsed time.
        val pressure = DoubleArray(config.cellCount) { i ->
            val h = flow.depthAt(i)
            h * h * temperature[i]
        }
        val meanPressure = pressure.average()
        val weight = DoubleArray(config.cellCount) { i ->
            exp(((meanPressure - pressure[i]) / (0.015 * meanPressure)).coerceIn(-1.5, 1.5))
        }
        repeat(32) { sweep ->
            for (index in water.indices) {
                val i = if (sweep % 2 == 0) index else water.lastIndex - index
                val x = i % config.width; val y = i / config.width
                fun exchange(j: Int) {
                    val target = (water[i] + water[j]) * weight[i] / (weight[i] + weight[j])
                    val amount = 0.45 * (target - water[i])
                    water[i] += amount; water[j] -= amount
                }
                exchange(y * config.width + (x + 1) % config.width)
                if (y + 1 < config.height) exchange(i + config.width)
                else if (config.periodicY) exchange(x)
            }
        }
        val vaporTarget = 1.0
        var vaporTotal = 0.0
        var cloudTotal = 0.0
        for (i in water.indices) {
            val depth = flow.depthAt(i)
            val vaporLimit = saturatedVaporMmAt(i, temperature[i])
            // A moist mean column contains unresolved saturated parcels. Partition a
            // small, bounded fraction as cloud without creating any column water.
            val cloud = ColumnThermodynamics.cloudMm(water[i], vaporLimit)
            val vapor = water[i] - cloud
            columns.values[0][i] = depth * temperature[i]
            columns.values[1][i] = vapor / density
            columns.values[2][i] = cloud / density
            vaporTotal += vapor; cloudTotal += cloud
        }
        worldEpochSeconds = atWorldSeconds - elapsedSeconds
        rainRate.fill(0.0)
        rainActive.fill(false)
        approximateHistory = true
        val energyChange = diagnostics().totalModelEnergyJoules - energyBefore
        initializationEnergy += energyChange
        val after = atmosphericWaterKg
        check(kotlin.math.abs(after - before) <= 1e-12 * max(1.0, before))
        return AtmosphereHandoff(atWorldSeconds, before, after, vaporTotal * config.cellArea,
            cloudTotal * config.cellArea, temperature.average(), energyChange, completedSteps,
            vaporRelativeHumidityLimit = vaporTarget, minimumAirTemperatureKelvin = temperature.min(),
            maximumAirTemperatureKelvin = temperature.max(), maximumWindMetersPerSecond = circulation.maximumSpeed,
            rootMeanSquareWindMetersPerSecond = circulation.rmsSpeed,
            reconstructionPressureExponent = circulation.pressureExponent)
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
            if (initialEnergy > 0) energy.value - initialEnergy - radiationEnergy - overturningEnergy else 0.0, exported, frictionHeating, transportEnergyChange.value, approximateHistory, initializationEnergy, overturningEnergy
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
            if (overturningResponse > 0) overturningEnergy += density *
                flow.exchangeZonalMomentumAt(i, overturningTarget[y], overturningResponse) * config.cellArea
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
            // Column continuity diagnoses unresolved ascent/subsidence, not another prognostic layer.
            // Divergence warms the representative parcel and permits evaporation in descending air.
            val resolvedAscent = -depth * physics.humidColumnFraction * flow.horizontalDivergenceAt(i)
            val lift = ((orographic + convective + resolvedAscent) * physics.liftResidenceSeconds)
                .coerceIn(-physics.maximumParcelLiftMeters, physics.maximumParcelLiftMeters)
            val parcelTemperature = temperature - lapse * lift
            val parcelPressure = pressure * exp(-config.gravity * lift / (287.0 * temperature))
            check(parcelTemperature > 0 && parcelPressure > 0) { "Invalid lifted parcel at cell $i" }
            // One parcel state for BOTH condensation and evaporation. Its adiabatic
            // temperature is diagnostic; only actual latent transfer changes bulk heat.
            val transfer = phaseTransfer(vapor, cloud, parcelTemperature, parcelPressure, mass, capacity)
            vapor -= transfer
            cloud += transfer
            temperature += physics.latentHeatJkg * transfer / capacity
            // Cloud droplets do not precipitate continuously as soon as a tiny
            // threshold is exceeded. A hysteretic autoconversion trigger makes
            // rain events less frequent, while conserving the actual cloud water.
            val onset = physics.rainOnsetKgM2
            if (!rainActive[i] && cloud >= onset) rainActive[i] = true
            // Stop above the retained-cloud floor: exponential rainout never reaches that floor exactly.
            else if (rainActive[i] && cloud <= RainAutoconversion.cessationMm(physics)) rainActive[i] = false
            val rain = if (rainActive[i])
                max(0.0, cloud - physics.rainCloudThresholdKgM2) * rainResponse else 0.0
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
            this.surface?.receiveRain(i, rain, if (surface < 273.15) 1.0 else 0.0)
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
        val water = vapor + cloud
        if (ColumnThermodynamics.cloudMm(water, saturation) == cloud) return 0.0
        var transfer = 0.0
        repeat(3) {
            val trialTemperature = t + physics.latentHeatJkg * transfer / capacity
            val e = WarmCloud.vaporPressure(trialTemperature)
            check(e < pressure) { "Warm-cloud saturation pressure exceeds column pressure" }
            val saturationMass = mass * physics.humidColumnFraction * 0.622 * e / (pressure - e)
            val derivative = mass * physics.humidColumnFraction * 0.622 * pressure * e *
                WarmCloud.saturationLatentHeat / (461.5 * trialTemperature * trialTemperature * (pressure - e) * (pressure - e))
            // The same unresolved saturated-parcel closure is used by climate,
            // reentry and detailed microphysics. Solve its phase partition with
            // latent heat, so reconstructed subgrid cloud is an equilibrium state.
            val target = ColumnThermodynamics.cloudMm(water, saturationMass)
            val epsilon = max(1e-6, saturationMass * 1e-5)
            val vaporDerivative = (target - ColumnThermodynamics.cloudMm(water, saturationMass + epsilon)) / epsilon
            transfer += (target - cloud - transfer) / (1 + physics.latentHeatJkg * vaporDerivative * derivative / capacity)
            // Transfer limits are donor availability, not corrections to a negative state.
            transfer = min(vapor, max(-cloud, transfer))
        }
        return transfer * if (transfer > 0) condensationResponse else cloudEvaporationResponse
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
