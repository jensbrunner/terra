package terra.weather

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.world.World

class AtmosphericTimestepTest {
    @Test
    fun fortySecondNativeStepsRetainSixHourWeatherAccuracyAndCflHeadroom() {
        val world = World(31742)
        val states = listOf(20.0, 40.0).map { dt ->
            val hydrology = HydrologySimulation(HydrologyTopology(world))
            val weather = DynamicalWeather(world, hydrology, DryAtmosphereConfig(stepSeconds = dt))
            val started = System.nanoTime()
            repeat((21600 / dt).toInt()) {
                hydrology.acceptWeatherStep(weather, weather.advanceBudgeted(dt, 1, publishSnapshot = false))
            }
            weather.refreshSnapshot()
            weather.atmosphereSnapshot.also { view ->
                println("TIMESTEP dt=$dt wall_seconds=${(System.nanoTime() - started) / 1e9} cfl=${view.dynamics.diagnostics.maximumCourant} cloud_mm=${view.diagnostics.meanCloudMm} rain_mm=${view.diagnostics.meanRainTotalMm}")
                assertTrue(abs(view.diagnostics.relativeWaterResidual) < 1e-10)
                assertTrue(view.dynamics.diagnostics.maximumCourant < 0.3)
            }
        }
        val a = states[0]; val b = states[1]
        var windError = 0.0; var temperatureError = 0.0
        for (y in 0 until 72) for (x in 0 until 96) {
            val du = a.dynamics.eastWindAt(x, y) - b.dynamics.eastWindAt(x, y)
            val dv = a.dynamics.northWindAt(x, y) - b.dynamics.northWindAt(x, y)
            windError += du * du + dv * dv
            val dt = a.temperatureKelvinAt(x, y) - b.temperatureKelvinAt(x, y)
            temperatureError += dt * dt
        }
        val windRms = sqrt(windError / (96 * 72))
        val temperatureRms = sqrt(temperatureError / (96 * 72))
        println("TIMESTEP_COMPARE wind_rms_error_ms=$windRms air_temperature_rms_error_k=$temperatureRms")
        assertTrue(windRms < 0.5)
        assertTrue(temperatureRms < 0.2)
        assertTrue(abs(a.diagnostics.meanRainTotalMm - b.diagnostics.meanRainTotalMm) < 0.1)
    }
}
