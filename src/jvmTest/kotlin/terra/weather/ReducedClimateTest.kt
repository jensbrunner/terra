package terra.weather

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.*
import terra.atmosphere.*
import terra.hydrology.*
import terra.world.*

/** Small physical comparisons plus one native-grid century review, exported under ignored build/. */
class ReducedClimateTest {
    private val day = 86400.0
    private val year = 365 * day

    @Test
    fun transportedWaterReachesInteriorAndMountainsRemoveItUpstream() {
        val config = DryAtmosphereConfig(width = 32, height = 12)
        // Isolate transport and uplift from complex generated terrain in this experiment.
        fun run(ridge: Boolean): Pair<DoubleArray, DoubleArray> {
            val q = DoubleArray(config.cellCount)
            val rain = DoubleArray(config.cellCount)
            // Test a controlled ocean-fed westerly belt at 35 degrees north.
            val y = 8
            val transport = ClimateMoistureTransport(config)
            repeat(60) {
                for (row in 0 until config.height) q[row * config.width + 2] += 16.0
                transport.advance(q, rain, day, 0.0,
                    { c, east, _ -> if (ridge && c % config.width in 7..9) max(0.0, east) * 0.04 else 0.0 },
                    { _, t -> 20 * exp(0.06 * (t - 288.15))
                    }, DoubleArray(config.cellCount) { 20.0 }, { 0.0 })
            }
            assertEquals(60 * 16.0 * config.height, q.sum() + rain.sum(), 1e-8)
            assertTrue(q[y * config.width + 12] > 0.0)
            return q to rain
        }
        val flat = run(false); val mountain = run(true)
        fun sum(field: DoubleArray, xs: IntRange) = xs.sumOf { field[8 * config.width + it] }
        assertTrue(sum(mountain.second, 7..9) > sum(flat.second, 7..9))
        assertTrue(sum(mountain.first, 11..16) < sum(flat.first, 11..16))
        println("MOISTURE_SHADOW windward_rain_flat=${sum(flat.second, 7..9)} ridge=${sum(mountain.second, 7..9)} leeward_water_flat=${sum(flat.first, 11..16)} ridge=${sum(mountain.first, 11..16)}")
    }

    @Test
    fun twentyYearsPartitionedIntoCalendarStepsMatchesSingleTransaction() {
        val config = DryAtmosphereConfig(width = 12, height = 8)
        fun create(): Pair<World, DynamicalWeather> {
            val w = World(42, 31, 23)
            return w to DynamicalWeather(w, HydrologySimulation(HydrologyTopology(w)), config)
        }
        val (wa, a) = create(); val (wb, b) = create()
        a.advanceClimateInterval(20 * year)
        repeat(730) { b.advanceClimateInterval(10 * day) }
        var maxSoil = 0.0; var maxCover = 0.0
        for (y in 0 until 23) for (x in 0 until 31) {
            maxSoil = max(maxSoil, abs(a.soilWaterMm(x, y) - b.soilWaterMm(x, y)))
            maxCover = max(maxCover, abs(wa.treeCover(x, y) - wb.treeCover(x, y)))
        }
        assertTrue(maxSoil < 1e-8 && maxCover < 1e-10)
        assertTrue(a.seasonalClimate!!.containsAggregateEstimates)
        assertEquals(0.0, a.climateNormals.normals(a.seasonalClimate).observedWeight)
        val (wc, c) = create()
        repeat(240) { c.advanceClimateInterval(year / 12) }
        var soilError = 0.0; var coverError = 0.0; var coverSquare = 0.0
        for (y in 0 until 23) for (x in 0 until 31) {
            soilError += (a.soilWaterMm(x, y) - c.soilWaterMm(x, y)).pow(2)
            val coverDelta = wa.treeCover(x, y) - wc.treeCover(x, y)
            coverError = max(coverError, abs(coverDelta))
            coverSquare += coverDelta * coverDelta
        }
        val rmsSoil = sqrt(soilError / (31 * 23))
        val rmsCover = sqrt(coverSquare / (31 * 23))
        // Monthly and annual requests decompose the same ten-day climate phases
        // differently; soil and cover must remain statistically comparable.
        assertTrue(rmsSoil < 10 && rmsCover < 0.05,
            "rms soil=$rmsSoil rms cover=$rmsCover max cover=$coverError")
        println("CLIMATE_PARTITION max_soil_mm=$maxSoil max_cover=$maxCover monthly_rms_soil=$rmsSoil monthly_max_cover=$coverError monthly_rms_cover=$rmsCover")
    }

    @Test
    fun nativeCenturyConservesWaterEvolvesCoverAndReenters() {
        val w = World(31742)
        val weather = DynamicalWeather(w, HydrologySimulation(HydrologyTopology(w)))
        val before = weather.diagnostics.totalWaterKg
        var residual = 0.0
        // JIT warmup is included, so the measured rate is deliberately conservative.
        val start = System.nanoTime()
        repeat(5) { residual = max(residual, abs(weather.advanceClimateInterval(20 * year).relativeWaterResidual)) }
        val wall = (System.nanoTime() - start) / 1e9
        weather.refreshSnapshot()
        val climate = weather.seasonalClimate!!
        val image = BufferedImage(w.width * 4, w.height, BufferedImage.TYPE_INT_RGB)
        var wetSoil = 0; var land = 0; var trees = 0.0
        var landRain = 0.0; var oceanRain = 0.0; var landCount = 0; var oceanCount = 0; var landRh = 0.0
        var meanLandT = 0.0; var meanLandAir = 0.0; var globalT = 0.0; var globalN = 0; var tropT = 0.0; var tropN = 0
        var minRain = Double.POSITIVE_INFINITY; var maxRain = 0.0
        for (y in 0 until w.height) for (x in 0 until w.width) {
            val rain = climate.annualPrecipitationAtTile(x, y, w.width, w.height)
            if (w[x, y].terrain != Terrain.OCEAN) {
                land++; if (weather.soilWaterMm(x, y) > 10) wetSoil++
                trees += w.treeCover(x, y)
                meanLandT += weather.surfaceTemperatureC(x, y); meanLandAir += weather.airTemperatureC(x, y)
                minRain = min(minRain, rain); maxRain = max(maxRain, rain)
                landRain += rain; landCount++; landRh += weather.relativeHumidity(x, y)
            } else { oceanRain += rain; oceanCount++ }
            globalT += weather.surfaceTemperatureC(x, y); globalN++
            if (y in w.height / 3..2 * w.height / 3) { tropT += weather.surfaceTemperatureC(x, y); tropN++ }
            val terrainColor = when(w[x, y].terrain) {
                Terrain.OCEAN -> Color(25, 65, 105)
                Terrain.WOODS -> Color(25, 95, 45)
                Terrain.PLAINS -> Color(125, 160, 65)
                Terrain.STEPPE -> Color(175, 160, 80)
                Terrain.DESERT -> Color(215, 190, 125)
                Terrain.TUNDRA -> Color(165, 175, 165)
                Terrain.MOUNTAINS -> Color(110, 100, 95)
            }
            image.setRGB(x, y, terrainColor.rgb)
            image.setRGB(x + w.width, y, Color.getHSBColor((0.65 - 0.65 * (rain / 1800).coerceIn(0.0, 1.0)).toFloat(), 0.8f, 0.9f).rgb)
            image.setRGB(x + 2 * w.width, y, Color(0, (weather.soilWaterMm(x, y) / 250 * 255).toInt().coerceIn(0,255), 80).rgb)
            image.setRGB(x + 3 * w.width, y, Color(0, (w.treeCover(x,y) * 255).toInt().coerceIn(0,255), 0).rgb)
        }
        val output = File("build/experiments/climate-century.png"); output.parentFile.mkdirs()
        ImageIO.write(image, "png", output)
        println("CENTURY_THERMAL surface_c=${globalT/globalN} tropics_c=${tropT/max(1,tropN)} land_c=${meanLandT/max(1,landCount)} wet=${wetSoil.toDouble()/land} trees=${trees/land} rainfall=${landRain/max(1,landCount)}")
        assertTrue(residual < 1e-10)
        assertTrue(wetSoil > land / 10 && trees / land > 0.05)
        assertTrue(maxRain > minRain + 300)
        val handoff = weather.resumeDetailed()!!
        assertEquals(before, handoff.waterAfterKg + weather.surfaceState.totalWaterKg, before * 1e-10)
        assertTrue(handoff.maximumWindMetersPerSecond in 1.0..35.0)
        weather.advanceBudgeted(40.0, 1)
        assertTrue(abs(weather.diagnostics.relativeWaterResidual) < 1e-10)
        println("CLIMATE_CENTURY wall_s=$wall years_per_s=${100 / wall} residual=$residual wet_land_fraction=${wetSoil.toDouble()/land} " +
            "mean_tree_cover=${trees/land} rain_range=$minRain..$maxRain land_rain_mean=${landRain / max(1, landCount)} " +
            "ocean_rain_mean=${oceanRain / max(1, oceanCount)} land_rh=${landRh / max(1, landCount)} " +
            "land_t=${meanLandT / max(1, landCount)} global_t=${globalT / globalN} trop_t=${tropT / max(1, tropN)} " +
            "biomes=${w.snapshot(1).landBiomeSummary()} reentry_cloud_kg=${handoff.cloudKg}")
    }
}
