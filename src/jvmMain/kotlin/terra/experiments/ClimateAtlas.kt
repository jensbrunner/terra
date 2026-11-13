package terra.experiments

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.world.Terrain
import terra.world.World
import terra.weather.DynamicalWeather

/**
 * Compact physical review atlas: terrain, annual rain, surface temperature, root-zone
 * water and canopy cover, with explicit units and regional statistics. It is a review
 * aid, not a test suite or a zero-noise visual acceptance claim.
 */
fun main(args: Array<String>) {
    val years = args.firstOrNull { it.startsWith("--years=") }?.substringAfter('=')?.toInt() ?: 100
    require(years in 1..1000)
    val year = 365.0 * 86400.0
    val world = World(31742)
    val hydrology = HydrologySimulation(HydrologyTopology(world))
    val weather = DynamicalWeather(world, hydrology)
    val detailed = weather.advanceBudgeted(40.0, 1, publishSnapshot = false)
    hydrology.acceptWeatherStep(weather, detailed)
    var residual = 0.0
    val started = System.nanoTime()
    var remaining = years
    while (remaining > 0) {
        val chunk = min(20, remaining)
        residual = max(residual, abs(weather.advanceClimateInterval(chunk * year).relativeWaterResidual))
        remaining -= chunk
    }
    val wall = (System.nanoTime() - started) / 1e9
    weather.refreshSnapshot()
    val climate = weather.seasonalClimate!!
    val width = world.width
    val height = world.height
    val panels = 5
    val image = BufferedImage(width * panels, height, BufferedImage.TYPE_INT_RGB)
    var land = 0; var landRain = 0.0; var oceanRain = 0.0; var ocean = 0
    var landTemperature = 0.0; var soilTotal = 0.0; var treeTotal = 0.0
    var minRain = Double.POSITIVE_INFINITY; var maxRain = 0.0
    var minTemperature = Double.POSITIVE_INFINITY; var maxTemperature = Double.NEGATIVE_INFINITY
    for (y in 0 until height) for (x in 0 until width) {
        val cell = world[x, y]
        val rain = climate.annualPrecipitationAtTile(x, y, width, height)
        val temperature = weather.surfaceTemperatureC(x, y)
        val soil = weather.soilWaterMm(x, y)
        val tree = world.treeCover(x, y)
        minTemperature = min(minTemperature, temperature); maxTemperature = max(maxTemperature, temperature)
        val grass = world.grassCover(x, y)
        if (cell.terrain != Terrain.OCEAN) {
            land++; landRain += rain; landTemperature += temperature; soilTotal += soil; treeTotal += tree
            minRain = min(minRain, rain); maxRain = max(maxRain, rain)
        } else oceanRain += rain; ocean++
        val terrainColor = when (cell.terrain) {
            Terrain.OCEAN -> Color(25, 65, 105)
            Terrain.WOODS -> Color(25, 95, 45)
            Terrain.PLAINS -> Color(125, 160, 65)
            Terrain.STEPPE -> Color(175, 160, 80)
            Terrain.DESERT -> Color(215, 190, 125)
            Terrain.TUNDRA -> Color(165, 175, 165)
            Terrain.MOUNTAINS -> Color(110, 100, 95)
        }
        image.setRGB(x, y, terrainColor.rgb)
        image.setRGB(x + width, y, Color.getHSBColor((0.65 - 0.65 * (rain / 1600.0).coerceIn(0.0, 1.0)).toFloat(),
            0.8f, 0.92f).rgb)
        image.setRGB(x + 2 * width, y, Color.getHSBColor(
            ((temperature + 30) / 80.0).coerceIn(0.0, 1.0).toFloat() * 0.75f, 0.75f, 0.9f).rgb)
        image.setRGB(x + 3 * width, y, Color(0, (soil / 250 * 255).toInt().coerceIn(0, 255), 80).rgb)
        image.setRGB(x + 4 * width, y, Color(
            (30 + 40 * grass).toInt().coerceIn(0, 255),
            (40 + 190 * tree).toInt().coerceIn(0, 255),
            (30 + 30 * grass).toInt().coerceIn(0, 255)).rgb)
    }
    val output = File("build/experiments").apply { mkdirs() }
    ImageIO.write(image, "png", File(output, "climate-atlas.png"))
    val text = buildString {
        appendLine("Terra climate review atlas")
        appendLine("world=${width}x${height} years=$years seed=${world.seed}")
        appendLine("panels: terrain | annual rain (mm/year, 0 blue → 1600 brown) | surface temperature (−30 → 50 °C) | root-zone soil (0–250 mm) | canopy/grass (green)")
        appendLine("wall_seconds=${"%.2f".format(wall)} years_per_second=${"%.2f".format(years / wall)} max_water_residual=$residual")
        appendLine("land_fraction=${land.toDouble() / (land + ocean)} land_rain_mean=${landRain / max(1, land)} mm/year ocean_rain_mean=${oceanRain / max(1, ocean)} mm/year")
        appendLine("rain_range=$minRain..$maxRain mm/year temperature_range=$minTemperature..$maxTemperature °C land_temperature_mean=${landTemperature / max(1, land)} °C")
        appendLine("soil_mean=${soilTotal / max(1, land)} mm canopy_mean=${treeTotal / max(1, land)}")
        appendLine("biomes=${world.snapshot(1).landBiomeSummary()}")
        appendLine("fire_disturbances=${world.fireCount}")
        val units = weather.surfaceState.responseUnits
        appendLine("response_units=${units.count} land_tiles=${units.tileCountPerUnit.sum()}")
        appendLine("observed_years=${climate.observedYears} aggregate=${climate.containsAggregateEstimates}")
        appendLine("zonal_band land_fraction land_rain_mm_y ocean_rain_mm_y land_temperature_c")
        for (band in 0..7) {
            var bandLand = 0; var bandOcean = 0; var bandLandRain = 0.0; var bandOceanRain = 0.0; var bandT = 0.0
            for (y in band * height / 8 until (band + 1) * height / 8) for (x in 0 until width) {
                val rain = climate.annualPrecipitationAtTile(x, y, width, height)
                if (world[x, y].terrain == Terrain.OCEAN) { bandOcean++; bandOceanRain += rain }
                else { bandLand++; bandLandRain += rain; bandT += weather.surfaceTemperatureC(x, y) }
            }
            appendLine("band_$band ${"%.2f".format(bandLand.toDouble() / max(1, bandLand + bandOcean))} " +
                "${"%.0f".format(bandLandRain / max(1, bandLand))} ${"%.0f".format(bandOceanRain / max(1, bandOcean))} " +
                "${"%.1f".format(bandT / max(1, bandLand))}")
        }
    }
    File(output, "climate-atlas.txt").writeText(text)
    print(text)
    println("CLIMATE_ATLAS ${File(output, "climate-atlas.png").absolutePath}")
}
