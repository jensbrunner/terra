package terra.weather

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.atmosphere.ColumnThermodynamics
import terra.atmosphere.DryAtmosphereConfig
import terra.render.CloudRenderer
import terra.world.World

/** One moist-world trajectory, with physical columns throughout reentry. */
class CloudRainConsistencyTest {
    @Test
    fun centuryReentryRetainsCloudRegimesAndReportsRainFrequencyAndIntensity() {
        val world = World(31742, 48, 36)

        val config = DryAtmosphereConfig(width = 24, height = 18)
        val weather = DynamicalWeather(world, config)
        val initialWater = weather.diagnostics.let { it.totalWaterKg + it.exportedWaterKg }
        repeat(12) { weather.advanceClimateInterval(20 * 365 * 86400.0) }
        weather.advanceClimateInterval(15 * 365 * 86400.0)
        weather.refreshSnapshot()
        val records = StringBuilder("stage,latitude_band,vapor_mm,capacity_mm,rh,cloud_mm,rain_mm_h,cloudy_fraction,visible_fraction,wet_fraction,wet_gt_0_5,wet_gt_2,wet_gt_5\n")
        fun record(stage: String, physical: Boolean): Int {
            val view = weather.atmosphereSnapshot
            var cloudyBands = 0
            val renderer = CloudRenderer(world.width, world.height, world.seed)
            // Read physical columns directly; resolved stages also inspect fresh pixels.
            renderer.update(weather)
            for (band in 0..5) {
                var vapor = 0.0; var capacity = 0.0; var rh = 0.0; var cloud = 0.0; var rain = 0.0
                var cloudy = 0; var wet = 0; var medium = 0; var heavy = 0; var intense = 0; var n = 0
                for (y in band * config.height / 6 until (band + 1) * config.height / 6) for (x in 0 until config.width) {
                    val tileX = x * world.width / config.width
                    val tileY = world.height - 1 - y * world.height / config.height
                    val t = if (physical) view.temperatureKelvinAt(x, y) else weather.airTemperatureC(tileX, tileY) + 273.15
                    val mass = weather.physics.referenceDryColumnKgM2 * view.dynamics.heightAt(x, y) / config.meanHeightMeters
                    val sat = ColumnThermodynamics.saturationMm(t, mass, config.gravity, weather.physics)
                    val c = if (physical) view.cloudWaterMmAt(x, y) else 0.0
                    val v = view.vaporMmAt(x, y)
                    val r = if (physical) view.rainMmPerHourAt(x, y) else weather.precipitationMmPerHour(tileX, tileY)
                    vapor += v; capacity += sat; rh += v / sat; cloud += c; rain += r; n++
                    if (c > 0.01) cloudy++
                    if (r > 0.01) { wet++; if (r > 0.5) medium++; if (r > 2) heavy++; if (r > 5) intense++ }
                }
                var pixels = 0; var visible = 0
                val frame = renderer.frame
                for (py in (5 - band) * frame.height / 6 until (6 - band) * frame.height / 6) for (px in 0 until frame.width) {
                    pixels++; if (frame[px, py].a > 24) visible++
                }
                if (cloudy >= 3) cloudyBands++
                records.appendLine("$stage,$band,${vapor/n},${capacity/n},${rh/n},${cloud/n},${rain/n},${cloudy.toDouble()/n},${visible.toDouble()/pixels},${wet.toDouble()/n},${medium.toDouble()/maxOf(1,wet)},${heavy.toDouble()/maxOf(1,wet)},${intense.toDouble()/maxOf(1,wet)}")
            }
            return cloudyBands
        }
        record("climate255", false)
        val handoff = weather.resumeDetailed()!!
        assertEquals(handoff.waterBeforeKg, handoff.waterAfterKg, handoff.waterBeforeKg * 1e-12)
        assertTrue(record("handoff", true) >= 2, "The moist fixture must hand off cloud structures in several latitude bands")
        val rainySamples = DoubleArray(4)
        var drySamples = 0
        var wetSamples = 0
        var longestWetSteps = 0
        val wetRuns = IntArray(config.cellCount)
        var finalBands = 0
        for (step in 1..3600) {
            weather.advanceBudgeted(20.0, 1, publishSnapshot = false)

            // Sample ten-minute snapshots, not every physical step's publication cost.
            if (step % 30 == 0) {
                weather.refreshSnapshot()
                val view = weather.atmosphereSnapshot
                for (y in 0 until config.height) for (x in 0 until config.width) {
                    val i = y * config.width + x
                    val r = view.rainMmPerHourAt(x, y)
                    if (r > 0.01) {
                        wetSamples++; wetRuns[i]++
                        longestWetSteps = maxOf(longestWetSteps, wetRuns[i])
                        rainySamples[0] += r
                        if (r > 0.5) rainySamples[1]++
                        if (r > 2) rainySamples[2]++
                        if (r > 5) rainySamples[3]++
                    } else { drySamples++; wetRuns[i] = 0 }
                }
            }
            if (step in listOf(180, 1080, 3600)) finalBands = record("detailed${step / 180}", true)
        }
        assertTrue(finalBands >= 2, "Cloud recovery must extend across regimes, not just one surviving pixel")
        assertTrue(drySamples > wetSamples, "Most resolved cells in this fixture should be dry")
        assertEquals(initialWater, weather.diagnostics.let { it.totalWaterKg + it.exportedWaterKg }, initialWater * 1e-10)
        assertTrue(abs(weather.diagnostics.relativeWaterResidual) < 1e-10)
        val output = File("build/reports/clouds/physical-reentry-255.csv")
        output.parentFile.mkdirs(); output.writeText(records.toString())
        println("PHYSICAL_REENTRY wet_fraction=${wetSamples.toDouble()/(wetSamples+drySamples)} conditional_mean_mm_h=${rainySamples[0]/maxOf(1,wetSamples)} conditional_gt_0_5=${rainySamples[1]/maxOf(1,wetSamples)} conditional_gt_2=${rainySamples[2]/maxOf(1,wetSamples)} conditional_gt_5=${rainySamples[3]/maxOf(1,wetSamples)} longest_sampled_event_minutes=${longestWetSteps*10} water_residual=${weather.diagnostics.relativeWaterResidual}")
    }
}
