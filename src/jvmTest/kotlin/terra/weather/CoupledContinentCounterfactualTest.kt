package terra.weather

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import terra.atmosphere.DryAtmosphereConfig
import terra.hydrology.HydrologySimulation
import terra.hydrology.HydrologyTopology
import terra.world.Terrain
import terra.world.TerrainGenerationSettings
import terra.world.World

/**
 * Coupled counterfactuals through the full fast model, not only the circulation closure.
 * A fixed continent is translated in longitude, and the seasonal low-level coastal wind
 * is compared between northern summer and winter.
 */
class CoupledContinentCounterfactualTest {
    private val width = 32
    private val height = 16
    private val year = 365.0 * 86400.0
    private val settings = TerrainGenerationSettings(baseElevation = 0.1, continentStrength = 0.0,
        regionalStrength = 0.0, detailStrength = 0.0, seaLevel = 0.305, mountainThreshold = 0.6)

    private fun world(centerX: Int): World {
        val elevation = DoubleArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                y in 2..8 && x in (centerX - 4)..(centerX + 4) -> 0.45
                else -> 0.10
            }
        }
        return World.withElevation(4242, width, height, settings, elevation)
    }

    private fun run(weather: DynamicalWeather, quarters: Int): DynamicalWeather {
        repeat(quarters) { weather.advanceClimateInterval(year / 4) }
        weather.refreshSnapshot()
        return weather
    }

    @Test
    fun translatingAContinentShiftsItsRainAndLowLevelWind() {
        val west = world(8)
        val east = world(24)
        val weatherWest = run(DynamicalWeather(west, HydrologySimulation(HydrologyTopology(west)),
            DryAtmosphereConfig(width = width, height = height)), 8)
        val weatherEast = run(DynamicalWeather(east, HydrologySimulation(HydrologyTopology(east)),
            DryAtmosphereConfig(width = width, height = height)), 8)

        fun rainAnomaly(weather: DynamicalWeather): DoubleArray {
            val field = DoubleArray(width * height) {
                weather.seasonalClimate!!.annualPrecipitationAtTile(it % width, it / width, width, height)
            }
            // Remove the latitude background so the continent's longitudinal signature
            // is what the cross-correlation measures.
            for (y in 0 until height) {
                var mean = 0.0
                for (x in 0 until width) mean += field[y * width + x]
                mean /= width
                for (x in 0 until width) field[y * width + x] -= mean
            }
            return field
        }
        val anomalyWest = rainAnomaly(weatherWest)
        val anomalyEast = rainAnomaly(weatherEast)
        fun correlation(shift: Int): Double {
            var dot = 0.0; var west2 = 0.0; var east2 = 0.0
            for (y in 0 until height) for (x in 0 until width) {
                val a = anomalyWest[y * width + x]
                val b = anomalyEast[y * width + (x + shift) % width]
                dot += a * b; west2 += a * a; east2 += b * b
            }
            return dot / kotlin.math.sqrt(west2 * east2)
        }
        val correlations = (0 until width).map { correlation(it) }
        val bestShift = correlations.indices.maxBy { correlations[it] }
        assertTrue(kotlin.math.abs(bestShift - 16) <= 3,
            "moving the continent must shift the rain anomaly: best=$bestShift correlations=$correlations")

        val windAtWest = weatherWest.windUTilesPerDay(8, 5)
        val windAtEast = weatherEast.windUTilesPerDay(8, 5)
        assertTrue(abs(windAtWest - windAtEast) > 0.05,
            "fixed-point low-level wind must depend on continent position: $windAtWest vs $windAtEast")
        println("COUPLED_TRANSLATION best_shift=$bestShift wind_west=$windAtWest wind_east=$windAtEast " +
            "correlations=${correlations.map { "%.2f".format(it) }}")
    }

    @Test
    fun wideContinentKeepsAProductiveMaritimeInterior() {
        fun run(wide: Boolean): Pair<DynamicalWeather, World> {
            val elevation = DoubleArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val land = if (wide) x in 4..28 else x in 12..20
                if (land && y in 2..8) 0.40 else 0.10
            }
            val world = World.withElevation(4242, width, height, settings, elevation)
            val weather = DynamicalWeather(world, HydrologySimulation(HydrologyTopology(world)),
                DryAtmosphereConfig(width = width, height = height))
            repeat(8) { weather.advanceClimateInterval(year / 4) }
            weather.refreshSnapshot()
            return weather to world
        }
        val (wideWeather, wideWorld) = run(true)
        val (narrowWeather, narrowWorld) = run(false)
        fun rain(weather: DynamicalWeather, x: Int) =
            weather.seasonalClimate!!.annualPrecipitationAtTile(x, 5, width, height)
        val wideCoast = rain(wideWeather, 5)
        val wideMid = rain(wideWeather, 16)
        val wideInterior = rain(wideWeather, 24)
        val narrowMid = rain(narrowWeather, 16)
        assertTrue(wideCoast > 0 && wideMid > 0 && wideInterior > 0,
            "wide continent must sustain rain across its interior: $wideCoast/$wideMid/$wideInterior")
        assertTrue(wideMid > 50.0, "maritime interior must not be a compulsory desert: $wideMid")
        assertTrue(wideWorld[24, 5].terrain != Terrain.OCEAN)
        println("WIDE_CONTINENT coast=$wideCoast mid=$wideMid interior=$wideInterior narrow_mid=$narrowMid")
    }

    @Test
    fun boundedBasinTransportConservesWaterAndKeepsThermalStateFinite() {
        val world = world(8)
        val weather = DynamicalWeather(world, HydrologySimulation(HydrologyTopology(world)),
            DryAtmosphereConfig(width = width, height = height),
            oceanTransport = OceanTransportMode.BASIN_TRANSPORT)
        var residual = 0.0
        repeat(8) { residual = maxOf(residual, abs(weather.advanceClimateInterval(year).relativeWaterResidual)) }
        weather.refreshSnapshot()
        var minimum = Double.POSITIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
        for (y in 0 until world.height) for (x in 0 until world.width) {
            val temperature = weather.surfaceTemperatureC(x, y)
            minimum = minOf(minimum, temperature); maximum = maxOf(maximum, temperature)
        }
        assertTrue(residual < 1e-10, "basin transport must conserve water: $residual")
        assertTrue(minimum.isFinite() && maximum.isFinite() && minimum > -80.0 && maximum < 45.0)
        println("BASIN_TRANSPORT residual=$residual temperature_range=$minimum..$maximum")
    }

    @Test
    fun seasonalLandOceanContrastReversesTheCoupledCoastalFlow() {
        val world = world(8)
        val weather = DynamicalWeather(world, HydrologySimulation(HydrologyTopology(world)),
            DryAtmosphereConfig(width = width, height = height))
        // A dry continent has a stronger seasonal thermal contrast; this is the
        // configuration in which a coastal reversal is physically supported.
        for (tile in weather.surfaceState.soil.indices) {
            if (world[tile % width, tile / width].terrain != Terrain.OCEAN) weather.surfaceState.soil[tile] = 5.0
        }
        run(weather, 8)
        val winds = DoubleArray(4)
        val oceanTemperatures = DoubleArray(4)
        val landTemperatures = DoubleArray(4)
        for (season in 0..3) {
            weather.advanceClimateInterval(year / 4)
            weather.refreshSnapshot()
            winds[season] = weather.windUTilesPerDay(3, 5)
            oceanTemperatures[season] = weather.surfaceTemperatureC(3, 5)
            landTemperatures[season] = weather.surfaceTemperatureC(6, 5)
        }
        val meanWind = winds.average()
        val summer = winds[1] - meanWind
        val winter = winds[3] - meanWind
        assertTrue(summer * winter < 0.0,
            "summer and winter coastal wind anomalies must reverse: summer=$summer winter=$winter winds=${winds.toList()}")
        assertTrue(abs(summer - winter) > 0.05)
        println("COUPLED_SEASONAL winds=${winds.toList()} anomalies=$summer/$winter " +
            "ocean_t=${oceanTemperatures.toList()} land_t=${landTemperatures.toList()}")
    }
}
