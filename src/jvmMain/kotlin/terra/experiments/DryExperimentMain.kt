package terra.experiments

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import terra.atmosphere.AtmosphereGeometry
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.DryAtmosphereSnapshot
import terra.weather.DynamicalWeather
import terra.world.World
import terra.atmosphere.MoistAtmosphereSnapshot
import terra.atmosphere.DryScenario
import terra.atmosphere.DryScenarios
import terra.atmosphere.MoistAtmosphere

/** Runs the actual solver and exports an animated preview, without starting the game. */
fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println("Atmosphere: --terrain=true|false --geometry=LATITUDE_CHANNEL|PERIODIC_F_PLANE --moist=true|false --scenario=JET|PULSE|VORTEX|REST --days=8 --frames=17 --width=96 --height=72 --step=20 --seed=31742 --output=directory")
        return
    }
    val allowed = setOf("terrain", "geometry", "moist", "scenario", "days", "frames", "width", "height", "step", "seed", "output")
    val values = args.associate { argument ->
        require(argument.startsWith("--") && '=' in argument) { "Expected --name=value: $argument" }
        argument.removePrefix("--").substringBefore('=') to argument.substringAfter('=')
    }
    require(values.keys.all { it in allowed }) { "Unknown options: ${values.keys - allowed}" }
    val scenario = DryScenario.valueOf(values["scenario"] ?: "JET")
    val moist = (values["moist"] ?: System.getProperty("terra.moistExperiment", "false")).toBooleanStrict()
    val terrain = (values["terrain"] ?: "false").toBooleanStrict()
    require(!terrain || moist) { "Terrain coupling requires moist mode" }
    val days = (values["days"] ?: "8").toDouble()
    val frameCount = (values["frames"] ?: "17").toInt()
    require(days.isFinite() && days > 0 && frameCount in 2..240)
    val config = DryAtmosphereConfig(
        geometry = AtmosphereGeometry.valueOf(values["geometry"] ?: "LATITUDE_CHANNEL"),
        width = (values["width"] ?: "96").toInt(), height = (values["height"] ?: "72").toInt(),
        stepSeconds = (values["step"] ?: "20").toDouble()
    )
    val requiredSteps = ceil(days * 86400 / config.stepSeconds)
    require(requiredSteps.isFinite() && requiredSteps < Long.MAX_VALUE.toDouble())
    val steps = requiredSteps.toLong().coerceAtLeast(1)
    val stride = max(1L, steps / (frameCount - 1))
    val seed = (values["seed"] ?: "31742").toInt()
    val output = File(values["output"] ?: "build/experiments/${if (moist) "moist" else "dry"}-atmosphere").apply { mkdirs() }
    require(!terrain || scenario == DryScenario.JET) { "Coupled terrain uses the game JET initializer" }
    val world = if (terrain) World(seed, config.width * 2 - 1, config.height * 2 - 1) else null
    val coupled = world?.let { DynamicalWeather(it, config) }
    val wetModel = if (moist && !terrain) MoistAtmosphere(config, scenario = scenario, seed = seed) else null
    val model = if (moist) null else DryScenarios.create(scenario, config, seed)
    fun elapsedSeconds() = coupled?.elapsedSeconds ?: wetModel?.elapsedSeconds ?: model!!.elapsedSeconds
    fun completedSteps() = coupled?.let { (it.elapsedSeconds / config.stepSeconds).toLong() } ?: wetModel?.completedSteps ?: model!!.completedSteps
    fun wetSnapshot() = coupled?.atmosphereSnapshot ?: wetModel?.snapshot()
    val initialMoist = wetSnapshot()
    val initial = initialMoist?.dynamics ?: model!!.snapshot()
    val initialImage = DryAtmosphereImages.frame(initial, initialMoist)
    ImageIO.write(initialImage, "png", File(output, "initial.png"))
    println("${if (moist) "Moist" else "Dry"} $scenario ${config.width}×${config.height}: $steps fixed ${config.stepSeconds}s steps")
    val budgets = StringBuilder("day,relative_water_residual,energy_residual_J,numerical_transport_energy_J,unexplained_energy_residual_J,boundary_export_kg,soil_mm_tiles,observed_years\n")
    AnimatedGif(File(output, "atmosphere.gif")).use { animation ->
        animation.add(initialImage)
        while (completedSteps() < steps) {
            if (coupled != null) {
                // Small hour-or-shorter handoffs keep lake/routing coupling bounded.
                val batch = minOf(maxOf(1L, (3600 / config.stepSeconds).toLong()),
                    steps - completedSteps(), stride - completedSteps() % stride).toInt()
                coupled.advanceBudgeted(batch * config.stepSeconds, batch)

            } else if (wetModel != null) wetModel.step() else model!!.step()
            if (completedSteps() % stride == 0L || completedSteps() == steps) {
                val wet = wetSnapshot()
                val state = wet?.dynamics ?: model!!.snapshot()
                animation.add(DryAtmosphereImages.frame(state, wet))
                println("Day %.2f; non-zonal RMS wind %.3f m/s".format(elapsedSeconds() / 86400, nonZonalRmsWind(state)))
                if (coupled != null) {
                    val diagnostics = wet!!.diagnostics
                    val soil = (0 until coupled.height).sumOf { y -> (0 until coupled.width).sumOf { x -> coupled.soilWaterMm(x, y) } }
                    budgets.append("${elapsedSeconds() / 86400},${diagnostics.relativeWaterResidual},${diagnostics.energyResidualJoules}," +
                        "${diagnostics.numericalTransportEnergyJoules},${diagnostics.unexplainedEnergyResidualJoules},${diagnostics.exportedWaterKg},$soil,${coupled.observedClimateYears}\n")
                    println("water_residual=${diagnostics.relativeWaterResidual}; energy_residual_J=${diagnostics.energyResidualJoules}; " +
                        "transport_energy_J=${diagnostics.numericalTransportEnergyJoules}; unexplained_energy_J=${diagnostics.unexplainedEnergyResidualJoules}; " +
                        "boundary_export_kg=${diagnostics.exportedWaterKg}; soil_mm_tiles=$soil; " +
                        "observed_years=${coupled.observedClimateYears}; biome_revision=${world.biomeRevision}")
                }
            }
        }
    }
    val finalMoist = wetSnapshot()
    val final = finalMoist?.dynamics ?: model!!.snapshot()
    ImageIO.write(DryAtmosphereImages.frame(final, finalMoist), "png", File(output, "final.png"))
    val initialVolume = initial.diagnostics.volumeCubicMeters
    val text = "scenario=$scenario\ngeometry=${config.geometry}\nseed=$seed\ngrid=${config.width}x${config.height}\n" +
        "requested_days=$days\ncompleted_steps=${completedSteps()}\ncompleted_seconds=${elapsedSeconds()}\n" +
        "step_seconds=${config.stepSeconds}\n" +
        "relative_volume_change=${(final.diagnostics.volumeCubicMeters - initialVolume) / initialVolume}\n" +
        "initial_energy_per_density=${initial.diagnostics.energyPerDensity}\n" +
        "final_energy_per_density=${final.diagnostics.energyPerDensity}\n" +
        "minimum_height_m=${final.diagnostics.minimumHeightMeters}\n" +
        "maximum_wind_m_s=${final.diagnostics.maximumSpeedMetersPerSecond}\n" +
        "final_courant=${final.diagnostics.maximumCourant}\n" +
        "initial_non_zonal_rms_wind_m_s=${nonZonalRmsWind(initial)}\n" +
        "final_non_zonal_rms_wind_m_s=${nonZonalRmsWind(final)}\n" +
        "interpretation=equivalent-fluid height; not pressure in hPa; terrain_coupled=$terrain\n"
    val wetText = finalMoist?.diagnostics?.let { d ->
        "initial_model_energy_J=${initialMoist!!.diagnostics.totalModelEnergyJoules}\ntotal_model_energy_J=${d.totalModelEnergyJoules}\n" +
        "mean_vapor_mm=${d.meanVaporMm}\nmean_cloud_mm=${d.meanCloudMm}\nmean_rain_total_mm=${d.meanRainTotalMm}\n" +
            "maximum_rain_mm_h=${d.maximumRainMmPerHour}\nmean_air_temperature_K=${d.meanAirTemperatureKelvin}\n" +
            "relative_water_residual=${d.relativeWaterResidual}\nnet_radiation_J=${d.netRadiationJoules}\n" +
            "friction_heating_J=${d.frictionHeatingJoules}\n" +
            "solar_day_seconds=${finalMoist.physics.solar.daySeconds}\nsolar_year_seconds=${finalMoist.physics.solar.yearSeconds}\n" +
            "axial_tilt_degrees=${finalMoist.physics.solar.axialTiltDegrees}\n" +
            "mean_tropical_surface_K=${meanBandTemperature(finalMoist, true)}\nmean_polar_surface_K=${meanBandTemperature(finalMoist, false)}\n" +
            "numerical_transport_energy_J=${d.numericalTransportEnergyJoules}\nunexplained_energy_residual_J=${d.unexplainedEnergyResidualJoules}\n" +
            "energy_residual_J=${d.energyResidualJoules}\ntotal_evaporation_kg=${d.totalEvaporationKg}\ntotal_rain_kg=${d.totalRainKg}\n"
    } ?: ""
    val climateText = coupled?.seasonalClimate?.let { climate ->
        "observed_climate_years=${climate.observedYears}\nannual_coverage=${climate.hasAnnualCoverage}\n" +
            "climate_surface_temperature_C=${(0 until config.cellCount).map(climate::meanSurfaceTemperatureC).average()}\n" +
            "climate_annual_rain_mm=${(0 until config.cellCount).map(climate::annualPrecipitationMm).average()}\n" +
            "mean_seasonal_temperature_range_C=${(0 until config.cellCount).map { cell ->
                (0..3).maxOf { climate.seasonMeanSurfaceTemperatureC(it, cell) } -
                    (0..3).minOf { climate.seasonMeanSurfaceTemperatureC(it, cell) }
            }.average()}\n" +
            "biome_revision=${world.biomeRevision}\nboundary_export_kg=${coupled.diagnostics.exportedWaterKg}\n"
    } ?: ""
    File(output, "state.txt").writeText(text + wetText + climateText)
    if (coupled != null) File(output, "budgets.csv").writeText(budgets.toString())
    println(text)
    print(wetText + climateText)
    println("Preview: ${File(output, "atmosphere.gif").absolutePath}")
}

/** Removes zonal means so a surviving uniform jet is not mistaken for weather variability. */
private fun nonZonalRmsWind(state: DryAtmosphereSnapshot): Double {
    var variance = 0.0
    for (y in 0 until state.config.height) {
        var east = 0.0
        var north = 0.0
        for (x in 0 until state.config.width) {
            east += state.eastWindAt(x, y)
            north += state.northWindAt(x, y)
        }
        east /= state.config.width
        north /= state.config.width
        for (x in 0 until state.config.width) {
            val u = state.eastWindAt(x, y) - east
            val v = state.northWindAt(x, y) - north
            variance += u * u + v * v
        }
    }
    return sqrt(variance / state.config.cellCount)
}

private fun meanBandTemperature(state: MoistAtmosphereSnapshot, tropical: Boolean): Double {
    val config = state.dynamics.config
    var total = 0.0
    var cells = 0
    for (y in 0 until config.height) {
        val latitude = abs(terra.atmosphere.PlanetLatitude.atmosphereRowRadians(y, config.height))
        if (if (tropical) latitude < Math.PI / 6 else latitude > Math.PI / 3) {
            for (x in 0 until config.width) {
                total += state.surfaceTemperatureKelvinAt(x, y)
                cells++
            }
        }
    }
    return if (cells > 0) total / cells else Double.NaN
}

private class AnimatedGif(file: File) : AutoCloseable {
    private val writer = ImageIO.getImageWritersByFormatName("gif").next()
    private val stream = ImageIO.createImageOutputStream(file)
    private var first = true

    init { writer.output = stream; writer.prepareWriteSequence(null) }

    fun add(image: BufferedImage) {
        val parameters = writer.defaultWriteParam
        val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), parameters)
        val format = metadata.nativeMetadataFormatName
        val root = metadata.getAsTree(format) as IIOMetadataNode
        val controls = root.getElementsByTagName("GraphicControlExtension").item(0) as? IIOMetadataNode
            ?: IIOMetadataNode("GraphicControlExtension").also { root.appendChild(it) }
        controls.setAttribute("disposalMethod", "none")
        controls.setAttribute("userInputFlag", "FALSE")
        controls.setAttribute("transparentColorFlag", "FALSE")
        controls.setAttribute("delayTime", "20")
        controls.setAttribute("transparentColorIndex", "0")
        if (first) {
            val extensions = IIOMetadataNode("ApplicationExtensions")
            extensions.appendChild(IIOMetadataNode("ApplicationExtension").apply {
                setAttribute("applicationID", "NETSCAPE")
                setAttribute("authenticationCode", "2.0")
                userObject = byteArrayOf(1, 0, 0)
            })
            root.appendChild(extensions)
            first = false
        }
        metadata.setFromTree(format, root)
        writer.writeToSequence(IIOImage(image, null, metadata), parameters)
    }

    override fun close() {
        try { writer.endWriteSequence() }
        finally { writer.dispose(); stream.close() }
    }
}
