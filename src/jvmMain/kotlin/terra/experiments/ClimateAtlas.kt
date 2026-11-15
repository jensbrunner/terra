package terra.experiments

import java.awt.Color
import java.awt.RenderingHints
import kotlin.math.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import terra.climate.*
import terra.world.*

/** Review emergent circulation across seeds and fixed geographic counterfactuals. */
fun main(args: Array<String>) {
    val output = File(args.firstOrNull { it.startsWith("--output=") }?.substringAfter('=') ?: "build/experiments/seasonal-atlas").also { it.mkdirs() }
    File(output, "moisture-budgets.csv").writeText("")
    File(output, "circulation.csv").writeText("case,month,latitude_degrees,east_ms,south_ms,land_rain_mm_year,ocean_rain_mm_year,land_temperature_c,ocean_temperature_c,land_fraction\n")
    File(output, "land-evaporation-check.csv").writeText("case,month,landscape_evaporation_mm_tiles\n")
    val summaries = StringBuilder()
    fun export(name: String, world: World, wind: Double? = null, month: Int = 3): SeasonalClimateAtlas {
        val start = System.nanoTime()
        val atlas = ClimateAtlasBuilder(windOverride = wind).build(ClimateGeography(world))
        val surface = LandscapeWater(world, atlas.geography.config.domainWidthMeters * atlas.geography.config.domainHeightMeters / (world.width.toDouble() * world.height))
        surface.initializeClimate(atlas)
        world.evolveAtlasCover(atlas, 0.0, initialize = true, soilAvailability = surface.growingWaterAvailability)
        val state = surface.captureClimateState()
        val tileArea = atlas.geography.config.domainWidthMeters * atlas.geography.config.domainHeightMeters / (world.width.toDouble() * world.height)
        for (m in 0..11) {
            val actualEvaporation = surface.evolveMonth(atlas, m, 1.0) / tileArea
            File(output, "land-evaporation-check.csv").appendText("$name,${m + 1},$actualEvaporation\n")
        }
        surface.restoreClimateState(state); surface.beginUpdate()
        val millis = (System.nanoTime() - start) / 1e6
        writeMoistureBudget(output, name, atlas)
        val w = world.width; val h = world.height
        val scale = 3; val label = 26
        val image = BufferedImage(w * scale * 3, (h * scale + label) * 2, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val labels = listOf("Annual rain · mm/year (0–2500)", "Orbital month ${month + 1} rain · mm/month (0–300)", "Annual temperature · °C (−25–35)", "Aridity · rain / PET (0–2)", "Land cover · equilibrium", "Orbital month ${month + 1} wind · m/s (0–12)")
        val colors = listOf(Color(167, 99, 50), Color(229, 212, 122), Color(104, 184, 121), Color(33, 153, 163), Color(105, 89, 176))
        fun ramp(v: Double): Color {
            val p = v.coerceIn(0.0, 1.0) * 4; val a = p.toInt().coerceAtMost(3); val f = p - a
            return Color((colors[a].red * (1 - f) + colors[a + 1].red * f).toInt(), (colors[a].green * (1 - f) + colors[a + 1].green * f).toInt(), (colors[a].blue * (1 - f) + colors[a + 1].blue * f).toInt())
        }
        for (panel in 0..5) {
            val ox = panel % 3 * w * scale; val oy = panel / 3 * (h * scale + label)
            graphics.color = Color(20, 29, 36); graphics.fillRect(ox, oy, w * scale, label)
            graphics.color = Color.WHITE; graphics.drawString(labels[panel], ox + 8, oy + 18)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                graphics.color = when (panel) {
                    0 -> ramp(atlas.annualRainfallMm(i) / 2500)
                    1 -> ramp(atlas.rainfallMm(month, i) / 300)
                    2 -> Color.getHSBColor(((35 - atlas.meanTemperature[i]) / 60 * 0.7).toFloat().coerceIn(0f, 0.7f), 0.6f, 0.9f)
                    3 -> ramp(atlas.aridity(i) / 2)
                    4 -> when (world[x, y].terrain) { Terrain.OCEAN -> Color(37, 76, 105); Terrain.WOODS -> Color(31, 102, 59); Terrain.DESERT -> Color(201, 165, 96); Terrain.STEPPE -> Color(159, 163, 90); Terrain.TUNDRA -> Color(155, 165, 157); Terrain.MOUNTAINS -> Color(130, 128, 121); else -> Color(106, 153, 85) }
                    else -> ramp(hypot(atlas.windSouthMetersPerSecond(month, i), atlas.windEastMetersPerSecond(month, i)) / 12)
                }
                graphics.fillRect(ox + x * scale, oy + label + y * scale, scale, scale)
            }
        }
        // Fixed-length sampling grid; arrow length is proportional to physical speed.
        val windX = 2 * w * scale; val windY = h * scale + label + label
        graphics.stroke = java.awt.BasicStroke(1.2f)
        graphics.color = Color(25, 34, 42)
        for (y in 6 until h step 12) for (x in 8 until w step 16) {
            val i = y * w + x
            val u = atlas.windEastMetersPerSecond(month, i); val v = atlas.windSouthMetersPerSecond(month, i)
            val cx = windX + x * scale; val cy = windY + y * scale
            val dx = u * 1.5; val dy = v * 1.5
            if (hypot(u, v) < 0.1) { graphics.fillOval(cx - 1, cy - 1, 2, 2); continue }
            val tx = cx + dx; val ty = cy + dy
            graphics.draw(java.awt.geom.Line2D.Double(cx - dx, cy - dy, tx, ty))
            val angle = atan2(v, u)
            for (turn in listOf(-0.5, 0.5)) graphics.draw(java.awt.geom.Line2D.Double(tx, ty, tx - 5 * cos(angle + turn), ty - 5 * sin(angle + turn)))
        }
        graphics.dispose(); ImageIO.write(image, "png", File(output, "$name.png"))
        val landRain = (0 until w * h).filter { world.isLand(it % w, it / w) }.map { atlas.annualRainfallMm(it) }.sorted()
        summaries.appendLine("$name build_ms=$millis mean_sweeps_per_month=${atlas.moistureSweeps / 12.0} q_change_mm=${atlas.maximumHumidityChangeMm} moisture_residual_mm_day=${atlas.maximumMoistureResidualMmPerDay} temperature_range=${atlas.meanTemperature.min()}..${atlas.meanTemperature.max()} max_wind_ms=${(0 until w * h).maxOf { hypot(atlas.windEastMetersPerSecond(month, it), atlas.windSouthMetersPerSecond(month, it)) }} land_rain_p10_p50_p90=${listOf(0.1,0.5,0.9).map { landRain[(it * (landRain.size - 1)).toInt()] }} ${world.landBiomeSummary()}")
        if (name == "seed-31742" || name == "moisture-coast-ridge-interior") {
            val evolution = ClimateEvolution(world, surface, 0.0)
            writeLandscape(output, "$name-year0", world, surface)
            repeat(5) { evolution.advance(atlas, 20 * atlas.solar.yearSeconds) }
            writeLandscape(output, "$name-year100", world, surface)
        }
        return atlas
    }
    for (seed in listOf(31742, 42, 777)) export("seed-$seed", World(seed))
    val w = 144; val h = 108
    fun continent(ridge: Boolean) = World.withElevation(1, w, h, TerrainGenerationSettings(), DoubleArray(w * h) {
        val x = it % w
        if (x !in 25..118) 0.1 else if (ridge && x in 67..73) 0.65 else 0.32
    })
    export("continent", continent(false))
    export("ridge-east", continent(true), 5.0)
    export("ridge-west", continent(true), -5.0)
    export("ridge-emergent", continent(true))
    export("continent-winter", continent(false), month = 9)
    // Calm counterfactuals isolate the mean circulation from surface supply and rainout.
    export("continent-calm", continent(false), 0.0)
    export("seed-31742-calm", World(31742), 0.0)
    val island = World.withElevation(1, w, h, TerrainGenerationSettings(), DoubleArray(w * h) {
        if (it % w in 25..118 && it / w in 25..82) 0.32 else 0.1
    })
    export("island-summer", island)
    export("island-winter", island, month = 9)
    fun moistureWorld(ridge: Boolean, mirror: Boolean = false) = World.withElevation(7, 96, 72,
        TerrainGenerationSettings(), DoubleArray(96 * 72) { i ->
            val x = if (mirror) 95 - i % 96 else i % 96
            if (x !in 16..80) 0.1 else 0.32 + 0.0015 * cos(2 * PI * (i / 96) / 18) + if (ridge) 0.27 * exp(-((x - 40.0) / 5).pow(2)) else 0.0
        })
    val ridgeWorld = moistureWorld(true)
    val wetDry = export("moisture-coast-ridge-interior", ridgeWorld, 5.0)
    val flat = export("moisture-flat-control", moistureWorld(false), 5.0)
    export("moisture-reversed", moistureWorld(true, true), -5.0)
    writeMoistureTransect(output, ridgeWorld, wetDry, flat)
    File(output, "metrics.txt").writeText(summaries.toString()); print(summaries)
    println("ATLAS_REVIEW ${output.absolutePath}")
}

/** Same equations and ecology on both slopes; numerical wind is held fixed to isolate rainout. */
private fun writeMoistureTransect(output: File, world: World, atlas: SeasonalClimateAtlas, flat: SeasonalClimateAtlas) {
    val rain = DoubleArray(world.width); val control = DoubleArray(world.width)
    val elevation = DoubleArray(world.width); val trees = DoubleArray(world.width)
    for (x in 0 until world.width) {
        for (y in 28..43) {
            rain[x] += atlas.annualRainfallMm(y * world.width + x) / 16
            control[x] += flat.annualRainfallMm(y * world.width + x) / 16
            trees[x] += world.treeCover(x, y) / 16
        }
        elevation[x] = world.elevationMeters(x, 36)
    }
    val csv = StringBuilder("x,elevation_m,rain_mm_year,flat_rain_mm_year,tree_cover\n")
    for (x in rain.indices) csv.appendLine("$x,${elevation[x]},${rain[x]},${control[x]},${trees[x]}")
    File(output, "moisture-transect.csv").writeText(csv.toString())
    val image = BufferedImage(1200, 620, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(18, 29, 37); g.fillRect(0, 0, 1200, 620)
    g.color = Color.WHITE; g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 20)
    g.drawString("Ocean → wet windward mountains → dry continental interior", 55, 38)
    g.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 14)
    g.drawString("Fixed 5 m/s wind · tropical transect · identical rainfall equations and existing vegetation model", 55, 64)
    fun px(x: Int) = 70 + x * 1070.0 / (world.width - 1)
    val top = 110; val baseline = 420; val maximum = max(5000.0, ceil(rain.max() / 1000) * 1000)
    for (mark in 0..5) {
        val y = baseline - mark * (baseline - top) / 5
        g.color = Color(49, 67, 76); g.drawLine(70, y, 1140, y)
        g.color = Color(184, 201, 207); g.drawString("${(mark * maximum / 5).toInt()}", 15, y + 5)
    }
    g.drawString("Rain · mm/year", 70, 96)
    fun line(field: DoubleArray, color: Color, maximumValue: Double, bottom: Int, height: Int) {
        g.color = color; g.stroke = java.awt.BasicStroke(2.5f)
        val path = java.awt.geom.Path2D.Double()
        for (x in field.indices) {
            val y = bottom - field[x] / maximumValue * height
            if (x == 0) path.moveTo(px(x), y) else path.lineTo(px(x), y)
        }
        g.draw(path)
    }
    line(control, Color(164, 174, 180), maximum, baseline, baseline - top)
    line(rain, Color(85, 202, 222), maximum, baseline, baseline - top)
    g.color = Color(85, 202, 222); g.drawString("With mountains", 850, 88)
    g.color = Color(164, 174, 180); g.drawString("Flat control", 1005, 88)
    g.color = Color(184, 201, 207); g.drawString("Elevation / existing tree cover", 70, 466)
    line(elevation, Color(211, 181, 125), 3500.0, 580, 90)
    line(trees, Color(107, 211, 135), 1.0, 580, 90)
    g.drawString("Ocean", 95, 603); g.drawString("Windward", 385, 603); g.drawString("Ridge", 505, 603); g.drawString("Leeward interior", 650, 603)
    g.dispose(); ImageIO.write(image, "png", File(output, "moisture-transect.png"))
    fun mean(field: DoubleArray, xs: IntRange) = xs.sumOf { field[it] } / xs.count()
    val report = "windward_rain_mm_year=${mean(rain, 31..39)} lee_rain_mm_year=${mean(rain, 48..62)} flat_interior_rain_mm_year=${mean(control, 48..62)} windward_tree_cover=${mean(trees, 31..39)} lee_tree_cover=${mean(trees, 48..62)}\n"
    File(output, "moisture-milestone.txt").writeText(report)
    print(report)
}

private fun writeLandscape(output: File, name: String, world: World, surface: LandscapeWater) {
    val image = BufferedImage(world.width * 3 * 3, (world.height * 3 + 26), BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    val water = surface.surfaceWater.snapshot()
    val labels = listOf("Persistent cover + rivers/lakes", "Root-zone water · 0–250 mm", "Growing-season water availability")
    for (panel in 0..2) {
        val ox = panel * world.width * 3
        g.color = Color(18, 29, 37); g.fillRect(ox, 0, world.width * 3, 26)
        g.color = Color.WHITE; g.drawString(labels[panel], ox + 6, 18)
        for (y in 0 until world.height) for (x in 0 until world.width) {
            val i = y * world.width + x
            val tree = world.treeCover(x, y); val grass = world.grassCover(x, y); val bare = world.bareCover(x, y)
            g.color = if (!world.isLand(x, y)) Color(34, 70, 97) else when (panel) {
                0 -> if (world[x, y].terrain == Terrain.TUNDRA || world[x, y].terrain == Terrain.MOUNTAINS) Color(149, 157, 148)
                    else Color((30 * tree + 133 * grass + 195 * bare).toInt().coerceIn(0,255),
                        (103 * tree + 156 * grass + 160 * bare).toInt().coerceIn(0,255), (49 * tree + 77 * grass + 110 * bare).toInt().coerceIn(0,255))
                else -> { val f = (if (panel == 1) surface.soil[i] / 250 else surface.growingWaterAvailability[i]).coerceIn(0.0,1.0)
                    Color((185 - 135 * f).toInt(), (130 + 60 * f).toInt(), (76 + 115 * f).toInt()) }
            }
            if (panel == 0 && water.isLakeTileWet(x, y)) g.color = Color(66, 155, 196)
            g.fillRect(ox + x * 3, 26 + y * 3, 3, 3)
            if (panel == 0 && water.isStreamTile(x, y)) {
                val next = water.downstreamTileIndex(x, y)
                if (next >= 0 && abs(next % world.width - x) < 3 && abs(next / world.width - y) < 3) {
                    g.color = Color(66, 155, 196); g.stroke = java.awt.BasicStroke(0.8f)
                    g.draw(java.awt.geom.Line2D.Double(x * 3 + 1.5, 26 + y * 3 + 1.5,
                        next % world.width * 3 + 1.5, 26 + next / world.width * 3 + 1.5))
                }
            }
        }
    }
    g.dispose(); ImageIO.write(image, "png", File(output, "$name-landscape.png"))
    val wetLakes = (0 until world.width * world.height).count { water.isLakeTileWet(it % world.width, it / world.width) }
    val streams = (0 until world.width * world.height).count { water.isStreamTile(it % world.width, it / world.width) }
    File(output, "$name-landscape.txt").writeText("wet_lake_tiles=$wetLakes stream_tiles=$streams soil_mean_mm=${surface.soil.average()}\n${world.landBiomeSummary()}\n")
}

/** Opt-in re-solve exposes actual vapor even where no precipitation forms.
 * Fluxes are cell-area means in mm/day; rainfall columns are annualized mm/year. */
private fun writeMoistureBudget(output: File, name: String, atlas: SeasonalClimateAtlas) {
    val g = atlas.geography; val w = g.width; val h = g.height; val n = g.count
    val report = File(output, "moisture-budgets.csv")
    if (report.length() == 0L) report.writeText("case,month,build_sweeps,max_residual,evaporation,ocean_uptake,land_evaporation,precipitation,global_balance,land_rain_year,ocean_rain_year,equatorial_rain_year,subtropical_rain_year,inland_rain_year,deep_inland_rain_year,coast_inflow,coast_outflow,coast_diffusion,inland_advection,inland_diffusion,mean_land_wind,mean_humidity,rain_below_surface_saturation_fraction,max_column_saturation\n")
    for (m in 0..11) {
        val t = atlas.temperature[m]; val u = atlas.east[m]; val v = atlas.south[m]
        val p = DoubleArray(n); val humidity = DoubleArray(n)
        val solve = ClimateAtlasBuilder(atlas.solar).diagnoseRainfall(g, t, u, v, p, humidity, m)
        val q = solve.moisture; val capacity = solve.capacity
        var evaporation = 0.0; var oceanUptake = 0.0; var landEvaporation = 0.0
        var precipitation = 0.0; var liftedRain = 0.0; var subtropical = 0.0; var subCount = 0
        var landRain = 0.0; var oceanRain = 0.0; var equatorial = 0.0; var inlandRain = 0.0; var deepRain = 0.0; var landWind = 0.0
        var landCount = 0; var oceanCount = 0; var eqCount = 0; var inlandCount = 0; var deepCount = 0
        val land = BooleanArray(n) { g.landFraction[it] >= 0.5 }
        val inland = BooleanArray(n) { land[it] && g.coastDistanceMeters[it] >= 500_000 }
        for (i in 0 until n) {
            val exchange = (1 - g.landFraction[i]) * 1.2 * (0.8 * capacity[i] - q[i])
            evaporation += max(0.0, exchange); oceanUptake += max(0.0, -exchange)
            val rain = p[i] * 12 / 365
            landEvaporation += solve.landEvaporation[i]
            precipitation += rain
            if (q[i] <= capacity[i]) liftedRain += rain
            val latitude = abs(PlanetLatitude.terrainRowRadians(i / w, h))
            if (latitude >= PI / 9 && latitude <= 2 * PI / 9) { subCount++; subtropical += rain }
            if (land[i]) { landCount++; landRain += rain; landWind += hypot(u[i], v[i]) } else { oceanCount++; oceanRain += rain }
            if (abs(PlanetLatitude.terrainRowRadians(i / w, h)) < PI / 18) { eqCount++; equatorial += rain }
            if (inland[i]) { inlandCount++; inlandRain += rain }
            if (land[i] && g.coastDistanceMeters[i] >= 1_500_000) { deepCount++; deepRain += rain }
        }
        var coastIn = 0.0; var coastOut = 0.0; var coastDiffusion = 0.0
        var inlandAdvection = 0.0; var inlandDiffusion = 0.0
        fun face(i: Int, j: Int, velocity: Double, spacing: Double) {
            val advection = velocity * (if (velocity >= 0) q[i] else q[j]) * 86400 / spacing
            val diffusion = ClimateAtlasBuilder.MOISTURE_DIFFUSIVITY_M2_S * 86400 / (spacing * spacing) * (q[i] - q[j])
            val enteringLand = (if (land[j]) 1 else 0) - (if (land[i]) 1 else 0)
            coastIn += max(0.0, advection * enteringLand); coastOut += max(0.0, -advection * enteringLand)
            coastDiffusion += diffusion * enteringLand
            val enteringInterior = (if (inland[j]) 1 else 0) - (if (inland[i]) 1 else 0)
            inlandAdvection += advection * enteringInterior; inlandDiffusion += diffusion * enteringInterior
        }
        for (i in 0 until n) {
            val east = i / w * w + (i % w + 1) % w
            face(i, east, (u[i] + u[east]) / 2, g.config.dx)
            if (i / w + 1 < h) face(i, i + w, (v[i] + v[i + w]) / 2, g.config.dy)
        }
        val circulation = StringBuilder()
        for (y in 0 until h) {
            var landP = 0.0; var oceanP = 0.0; var lands = 0; var oceans = 0
            var east = 0.0; var south = 0.0; var landT = 0.0; var oceanT = 0.0
            for (x in 0 until w) {
                val i = y * w + x
                east += u[i]; south += v[i]
                if (land[i]) { landP += p[i] * 12; landT += t[i]; lands++ }
                else { oceanP += p[i] * 12; oceanT += t[i]; oceans++ }
            }
            circulation.appendLine(listOf(name, m + 1, PlanetLatitude.terrainRowRadians(y, h) * 180 / PI,
                east / w, south / w, landP / max(1, lands), oceanP / max(1, oceans),
                landT / max(1, lands), oceanT / max(1, oceans), lands.toDouble() / w).joinToString(","))
        }
        File(output, "circulation.csv").appendText(circulation.toString())
        val balance = evaporation - oceanUptake + landEvaporation - precipitation
        val values = listOf(name, m + 1, atlas.moistureSweeps, atlas.maximumMoistureResidualMmPerDay,
            evaporation / n, oceanUptake / n, landEvaporation / n, precipitation / n, balance / n,
            landRain * 365 / max(1, landCount), oceanRain * 365 / max(1, oceanCount), equatorial * 365 / max(1, eqCount), subtropical * 365 / max(1, subCount),
            inlandRain * 365 / max(1, inlandCount), deepRain * 365 / max(1, deepCount),
            coastIn / max(1, landCount), coastOut / max(1, landCount), coastDiffusion / max(1, landCount),
            inlandAdvection / max(1, inlandCount), inlandDiffusion / max(1, inlandCount), landWind / max(1, landCount), humidity.average(), liftedRain / max(1e-12, precipitation), q.indices.maxOf { q[it] / capacity[it] })
        report.appendText(values.joinToString(",") + "\n")
    }
}
