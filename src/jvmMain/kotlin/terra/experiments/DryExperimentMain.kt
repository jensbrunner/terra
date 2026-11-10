package terra.experiments

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.ceil
import kotlin.math.max
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.DryScenario
import terra.atmosphere.DryScenarios
import terra.atmosphere.MoistAtmosphere

/** Runs the actual solver and exports an animated preview, without starting the game. */
fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println("Atmosphere: --moist=true|false --scenario=JET|PULSE|VORTEX|REST --days=8 --frames=17 --width=96 --height=72 --step=20 --seed=31742 --output=directory")
        return
    }
    val allowed = setOf("moist", "scenario", "days", "frames", "width", "height", "step", "seed", "output")
    val values = args.associate { argument ->
        require(argument.startsWith("--") && '=' in argument) { "Expected --name=value: $argument" }
        argument.removePrefix("--").substringBefore('=') to argument.substringAfter('=')
    }
    require(values.keys.all { it in allowed }) { "Unknown options: ${values.keys - allowed}" }
    val scenario = DryScenario.valueOf(values["scenario"] ?: "JET")
    val moist = (values["moist"] ?: System.getProperty("terra.moistExperiment", "false")).toBooleanStrict()
    val days = (values["days"] ?: "8").toDouble()
    val frameCount = (values["frames"] ?: "17").toInt()
    require(days.isFinite() && days > 0 && frameCount in 2..240)
    val config = DryAtmosphereConfig(
        width = (values["width"] ?: "96").toInt(), height = (values["height"] ?: "72").toInt(),
        stepSeconds = (values["step"] ?: "20").toDouble()
    )
    val requiredSteps = ceil(days * 86400 / config.stepSeconds)
    require(requiredSteps.isFinite() && requiredSteps < Long.MAX_VALUE.toDouble())
    val steps = requiredSteps.toLong().coerceAtLeast(1)
    val stride = max(1L, steps / (frameCount - 1))
    val seed = (values["seed"] ?: "31742").toInt()
    val output = File(values["output"] ?: "build/experiments/${if (moist) "moist" else "dry"}-atmosphere").apply { mkdirs() }
    val wetModel = if (moist) MoistAtmosphere(config, scenario = scenario, seed = seed) else null
    val model = if (moist) null else DryScenarios.create(scenario, config, seed)
    fun completedSteps() = wetModel?.completedSteps ?: model!!.completedSteps
    fun elapsedSeconds() = wetModel?.elapsedSeconds ?: model!!.elapsedSeconds
    val initialMoist = wetModel?.snapshot()
    val initial = initialMoist?.dynamics ?: model!!.snapshot()
    val initialImage = DryAtmosphereImages.frame(initial, initialMoist)
    ImageIO.write(initialImage, "png", File(output, "initial.png"))
    println("${if (moist) "Moist" else "Dry"} $scenario ${config.width}×${config.height}: $steps fixed ${config.stepSeconds}s steps")
    AnimatedGif(File(output, "atmosphere.gif")).use { animation ->
        animation.add(initialImage)
        while (completedSteps() < steps) {
            if (wetModel != null) wetModel.step() else model!!.step()
            if (completedSteps() % stride == 0L || completedSteps() == steps) {
                val wet = wetModel?.snapshot()
                animation.add(DryAtmosphereImages.frame(wet?.dynamics ?: model!!.snapshot(), wet))
                println("Day %.2f".format(elapsedSeconds() / 86400))
            }
        }
    }
    val finalMoist = wetModel?.snapshot()
    val final = finalMoist?.dynamics ?: model!!.snapshot()
    ImageIO.write(DryAtmosphereImages.frame(final, finalMoist), "png", File(output, "final.png"))
    val initialVolume = initial.diagnostics.volumeCubicMeters
    val text = "scenario=$scenario\nseed=$seed\ngrid=${config.width}x${config.height}\n" +
        "requested_days=$days\ncompleted_steps=${completedSteps()}\ncompleted_seconds=${elapsedSeconds()}\n" +
        "step_seconds=${config.stepSeconds}\n" +
        "relative_volume_change=${(final.diagnostics.volumeCubicMeters - initialVolume) / initialVolume}\n" +
        "initial_energy_per_density=${initial.diagnostics.energyPerDensity}\n" +
        "final_energy_per_density=${final.diagnostics.energyPerDensity}\n" +
        "minimum_height_m=${final.diagnostics.minimumHeightMeters}\n" +
        "maximum_wind_m_s=${final.diagnostics.maximumSpeedMetersPerSecond}\n" +
        "final_courant=${final.diagnostics.maximumCourant}\n" +
        "interpretation=equivalent-fluid height; not pressure in hPa; terrain not coupled\n"
    val wetText = finalMoist?.diagnostics?.let { d ->
        "mean_vapor_mm=${d.meanVaporMm}\nmean_cloud_mm=${d.meanCloudMm}\nmean_rain_total_mm=${d.meanRainTotalMm}\n" +
            "maximum_rain_mm_h=${d.maximumRainMmPerHour}\nmean_air_temperature_K=${d.meanAirTemperatureKelvin}\n" +
            "relative_water_residual=${d.relativeWaterResidual}\nnet_radiation_J=${d.netRadiationJoules}\n" +
            "energy_residual_J=${d.energyResidualJoules}\ntotal_evaporation_kg=${d.totalEvaporationKg}\ntotal_rain_kg=${d.totalRainKg}\n"
    } ?: ""
    File(output, "state.txt").writeText(text + wetText)
    println(text)
    print(wetText)
    println("Preview: ${File(output, "atmosphere.gif").absolutePath}")
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
