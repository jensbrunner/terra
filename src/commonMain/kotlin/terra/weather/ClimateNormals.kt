package terra.weather

import terra.atmosphere.PlanetLatitude
import terra.atmosphere.SolarConfig
import terra.world.World
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class ClimateEstimateSource { PROVISIONAL_PRIOR, BLENDED_OBSERVATIONS, BLENDED_ESTIMATES }

/** Four orbital-quarter normals on the existing north-up atmosphere grid. No mutable world stores. */
internal class ClimateNormalsSnapshot(
    val width: Int, val height: Int, val yearSeconds: Double, val observedWeight: Double,
    private val temperatures: Array<DoubleArray>, private val rainRates: Array<DoubleArray>, val aggregateHistory: Boolean = false
) {
    val source = if (observedWeight == 0.0) ClimateEstimateSource.PROVISIONAL_PRIOR else if (aggregateHistory) ClimateEstimateSource.BLENDED_ESTIMATES else ClimateEstimateSource.BLENDED_OBSERVATIONS
    fun temperatureC(season: Int, cell: Int) = temperatures[season][cell]
    fun precipitationMmPerDay(season: Int, cell: Int) = rainRates[season][cell]
}

/** Immutable representative estimate; predicted rain remains demand until a conservative step funds it. */
internal class ClimateEstimateSnapshot(
    val normals: ClimateNormalsSnapshot, val atSeconds: Double,
    private val temperature: DoubleArray, private val rain: DoubleArray, private val wet: DoubleArray
) {
    val width get() = normals.width
    val height get() = normals.height
    fun wetAnomaly(cell: Int) = wet[cell]
    fun temperatureC(cell: Int) = temperature[cell]
    fun precipitationMmPerDay(cell: Int) = rain[cell]
    fun temperatureCAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int) = sample(x, y, tileWidth, tileHeight, temperature)
    fun precipitationMmPerDayAtTile(x: Int, y: Int, tileWidth: Int, tileHeight: Int) = sample(x, y, tileWidth, tileHeight, rain)

    private fun sample(x: Int, y: Int, tileWidth: Int, tileHeight: Int, field: DoubleArray): Double {
        require(tileWidth > 0 && tileHeight > 0 && y in 0 until tileHeight)
        val wrappedX = ((x % tileWidth) + tileWidth) % tileWidth
        val px = (wrappedX + 0.5) * width / tileWidth - 0.5
        val py = (height - (y + 0.5) * height / tileHeight - 0.5).coerceIn(0.0, height - 1.0)
        val left = floor(px).toInt(); val bottom = floor(py).toInt()
        val x0 = (left + width) % width; val x1 = (x0 + 1) % width
        val y1 = minOf(bottom + 1, height - 1)
        val fx = px - left; val fy = py - bottom
        return (field[bottom * width + x0] * (1 - fx) + field[bottom * width + x1] * fx) * (1 - fy) +
            (field[y1 * width + x0] * (1 - fx) + field[y1 * width + x1] * fx) * fy
    }
}

/** Cheap provisional geography/season prior plus observed seasonal evidence and regional anomalies.
 * Tunable game-scale heuristics, not equilibrated or calibrated Earth climatology.
 */
internal class ClimateNormals(world: World, surface: TerrainMoistSurface, private val solar: SolarConfig) {
    val yearSeconds get() = solar.yearSeconds
    val initialYearFraction get() = solar.initialYearFraction
    private val config = surface.mapping.config
    private val count = config.cellCount
    private val anomalies = ClimateAnomalies(world.seed, solar.yearSeconds)
    private val warmScratch = DoubleArray(count)
    private val prior: ClimateNormalsSnapshot
    private var latestObservation: SeasonalClimateSnapshot? = null
    private var blended: ClimateNormalsSnapshot? = null

    init {
        val baseTemperature = DoubleArray(count)
        for (cell in 0 until count) surface.mapping.forCell(cell) { tile, fraction ->
            baseTemperature[cell] += world[tile % world.width, tile / world.width].meanTemperatureC * fraction / surface.mapping.tilesPerCell
        }
        // A short, periodic coastal fetch proxy. This is diagnostic geography, not a moisture store.
        var fetch = DoubleArray(count) { 1 - surface.landFraction[it] }
        var scratch = DoubleArray(count)
        repeat(4) {
            for (y in 0 until config.height) for (x in 0 until config.width) {
                val c = y * config.width + x
                scratch[c] = 0.4 * fetch[c] + 0.15 * (
                    fetch[y * config.width + (x + config.width - 1) % config.width] +
                    fetch[y * config.width + (x + 1) % config.width] +
                    fetch[maxOf(0, y - 1) * config.width + x] +
                    fetch[minOf(config.height - 1, y + 1) * config.width + x])
            }
            val old = fetch; fetch = scratch; scratch = old
        }
        val temperature = Array(4) { DoubleArray(count) }
        val rain = Array(4) { DoubleArray(count) }
        for (cell in 0 until count) {
            val latitude = PlanetLatitude.atmosphereRowRadians(cell / config.width, config.height)
            val land = surface.landFraction[cell]
            val lag = 0.04 * (1 - land)
            val light = DoubleArray(4) { season -> dailyMeanLight(latitude, (season + 0.5) / 4.0 - lag) }
            val annualLight = light.average()
            // Diagnosed prevailing flow for relief only; no pressure/momentum evolution.
            val east = -8.0 * cos(3 * latitude)
            val uplift = surface.orographicUpliftMetersPerSecond(cell, east, 0.0)
            val descent = max(0.0, -east * surface.terrain.slopeEast[cell])
            val relief = (1 + (20 * uplift).coerceIn(0.0, 2.0)) * exp(-20 * descent)
            val moisture = (0.25 + 0.75 * fetch[cell]) * exp(-surface.terrain.meanElevationMeters[cell] / 8000.0)
            for (season in 0..3) {
                val t = (baseTemperature[cell] + (10 + 20 * land) * (light[season] - annualLight)).coerceIn(-80.0, 60.0)
                temperature[season][cell] = t
                val warmth = ((t + 20) / 45).coerceIn(0.1, 1.5)
                rain[season][cell] = (0.3 + 6 * moisture * warmth * (light[season] / 0.32) * relief).coerceIn(0.0, 20.0)
            }
        }
        prior = ClimateNormalsSnapshot(config.width, config.height, solar.yearSeconds, 0.0, temperature, rain)
    }

    /** Daily mean of the existing cosine-zenith geometry; no diurnal marching or weather spin-up. */
    private fun dailyMeanLight(latitude: Double, yearFraction: Double): Double {
        val sinDeclination = sin(solar.axialTiltDegrees * PI / 180) * sin(2 * PI * yearFraction)
        val cosDeclination = sqrt(max(0.0, 1 - sinDeclination * sinDeclination))
        val a = sin(latitude) * sinDeclination
        val b = cos(latitude) * cosDeclination
        val sunset = acos((-a / b).coerceIn(-1.0, 1.0))
        return max(0.0, (sunset * a + b * sin(sunset)) / PI)
    }

    fun normals(observed: SeasonalClimateSnapshot? = null): ClimateNormalsSnapshot {
        if (observed == null) return prior
        require(observed.width == config.width && observed.height == config.height && observed.yearSeconds == solar.yearSeconds)
        if (observed.containsAggregateEstimates) return prior
        if (observed === latestObservation) return blended!!
        // One post-spin-up year gives 40% observed weight; two or more give 80%.
        val weight = 0.8 * minOf(1.0, observed.observedYears / 2.0)
        val temperature = Array(4) { season -> DoubleArray(count) { cell ->
            prior.temperatureC(season, cell) * (1 - weight) + observed.seasonMeanSurfaceTemperatureC(season, cell) * weight
        } }
        val rain = Array(4) { season -> DoubleArray(count) { cell ->
            prior.precipitationMmPerDay(season, cell) * (1 - weight) + observed.seasonMeanPrecipitationMmPerDay(season, cell) * weight
        } }
        return ClimateNormalsSnapshot(config.width, config.height, solar.yearSeconds, weight, temperature, rain, observed.containsAggregateEstimates)
            .also { latestObservation = observed; blended = it }
    }

    /** Representative season with the anomaly integrated over the actual requested time window. */
    fun estimateSeason(start: Double, seconds: Double, season: Int, observed: SeasonalClimateSnapshot?): ClimateEstimateSnapshot {
        val normal = normals(observed)
        val wet = DoubleArray(count)
        val temperature = DoubleArray(count)
        val rain = DoubleArray(count)
        anomalies.fillGrid(wet, warmScratch, config.width, config.height, start, seconds)
        for (cell in 0 until count) {
            val warm = warmScratch[cell]
            temperature[cell] = normal.temperatureC(season, cell) + 3 * (0.6 * warm - 0.4 * wet[cell])
            rain[cell] = normal.precipitationMmPerDay(season, cell) * (1 + 0.65 * wet[cell])
        }
        return ClimateEstimateSnapshot(normal, start + seconds / 2, temperature, rain, wet)
    }

    /** Cyclic quarter-center interpolation; anomalies update on the climate-month clock. */
    fun estimate(seconds: Double, observed: SeasonalClimateSnapshot? = null): ClimateEstimateSnapshot {
        require(seconds.isFinite() && seconds >= 0.0)
        val normal = normals(observed)
        val phase = ((seconds / solar.yearSeconds + solar.initialYearFraction) % 1.0) * 4 - 0.5
        val left = floor(phase).toInt()
        val first = (left + 4) % 4; val second = (first + 1) % 4
        val fraction = phase - left
        val temperature = DoubleArray(count)
        val rain = DoubleArray(count)
        val wetness = DoubleArray(count)
        anomalies.fillGrid(wetness, warmScratch, config.width, config.height, seconds)
        for (cell in 0 until count) {
            val wet = wetness[cell]
            val warm = warmScratch[cell]
            temperature[cell] = normal.temperatureC(first, cell) * (1 - fraction) + normal.temperatureC(second, cell) * fraction +
                3 * (0.6 * warm - 0.4 * wet)
            rain[cell] = (normal.precipitationMmPerDay(first, cell) * (1 - fraction) +
                normal.precipitationMmPerDay(second, cell) * fraction) * (1 + 0.65 * wet)
        }
        return ClimateEstimateSnapshot(normal, seconds, temperature, rain, wetness)
    }
}
