package terra.climate

import kotlin.math.*

/** Instantaneous weather, evaluated directly at absolute time; no atmospheric history or RNG state.
 * Precipitation is the shared conservative landscape forcing. */
class ProceduralWeather(private val atlas: SeasonalClimateAtlas, val elapsedSeconds: Double) {
    private val width = atlas.width
    private val height = atlas.height
    private val config = atlas.geography.config
    private val spanX = config.domainWidthMeters / 1000
    private val spanY = config.domainHeightMeters / 1000
    private val days = elapsedSeconds / 86400
    private val seed = atlas.geography.world.seed
    private val season = elapsedSeconds / atlas.solar.yearSeconds + atlas.solar.initialYearFraction
    private val monthPosition = season * 12 - 0.5
    private val monthFloor = floor(monthPosition)
    private val month = wrapMonth(monthFloor.toLong())
    private val nextMonth = (month + 1) % 12
    private val monthFraction = monthPosition - monthFloor
    private val blend = (1 - cos(PI * monthFraction)) / 2
    private val flowWindow = floor(days / FLOW_DAYS).toLong()
    private val flowBlend = smooth(0.0, 1.0, days / FLOW_DAYS - floor(days / FLOW_DAYS))
    private val travelWeights = Array(2) { windWeights((flowWindow - 1 + it) * FLOW_DAYS) }
    data class Sample(val cloud: Double, val precipitation: Double, val humidity: Double, val temperature: Double)
    private val samples by lazy { arrayOfNulls<Sample>(width * height) }

    init {
        require(elapsedSeconds.isFinite())
    }

    fun cloudCover(x: Int, y: Int) = tile(x, y).cloud
    fun precipitationMmPerHour(x: Int, y: Int) = atlas.rainfall.rateMmPerHour(x, y, elapsedSeconds)
    fun relativeHumidity(x: Int, y: Int) = tile(x, y).humidity
    fun temperatureC(x: Int, y: Int) = tile(x, y).temperature
    fun windEastMetersPerSecond(x: Int, y: Int) = interpolate(atlas.east, y * width + x)
    fun windSouthMetersPerSecond(x: Int, y: Int) = interpolate(atlas.south, y * width + x)

    private fun tile(x: Int, y: Int): Sample {
        val i = y * width + x
        return samples[i] ?: sample(x + 0.5, y + 0.5).also { samples[i] = it }
    }

    /** Fractional terrain coordinates: rendering can sample finer than the simulation grid. */
    fun sample(x: Double, y: Double): Sample {
        val tx = floor(x).toInt().mod(width)
        val ty = floor(y).toInt().coerceIn(0, height - 1)
        val i = ty * width + tx
        val px = x / width * spanX
        val py = y / height * spanY
        val wet = interpolate(atlas.humidity, x, y)
        val temp = atlas.temperature[month][i] * (1 - blend) + atlas.temperature[nextMonth][i] * blend
        val u = interpolate(atlas.east, x, y); val v = interpolate(atlas.south, x, y)
        // Analytic integral of the periodic, cosine-interpolated seasonal winds.
        // Multiplying today's wind by absolute time would make season changes teleport clouds.
        // Overlapping flow windows bound shear even after centuries. Each window
        // uses its own seed and fades in/out with zero slope at the handoff.
        var detail = 0.0
        for (packetIndex in 0..1) {
            val packet = flowWindow - 1 + packetIndex
            val weight = if (packetIndex == 1) flowBlend else 1 - flowBlend
            val nx = px - windTravel(atlas.cloudFlow[0], x, y, travelWeights[packetIndex])
            val ny = py - windTravel(atlas.cloudFlow[1], x, y, travelWeights[packetIndex])
            val salt = packet.toInt() * 1013
            val b = noise(nx, ny, 18, days / 7, 11 xor salt)
            val c = noise(nx + 90 * b, ny + 65 * b, 54, days / 2.5, 23 xor salt)
            val f = noise(nx + 40 * c, ny, 150, days / 1.2, 37 xor salt)
            detail += (0.55 * b + 0.30 * c + 0.15 * f) * weight
        }
        val precipitation = atlas.rainfall.rateMmPerHour(x, y, elapsedSeconds)
        val west = atlas.geography.tileElevation[ty * width + (tx + width - 1) % width]
        val east = atlas.geography.tileElevation[ty * width + (tx + 1) % width]
        val north = atlas.geography.tileElevation[max(0, ty - 1) * width + tx]
        val south = atlas.geography.tileElevation[min(height - 1, ty + 1) * width + tx]
        val ascent = u * (east - west) / (2 * config.domainWidthMeters / width) +
            v * (south - north) / (2 * config.domainHeightMeters / height)
        val terrainLift = (ascent * 1.5).coerceIn(-0.06, 0.12)
        val density = detail + wet * 0.50 + terrainLift - 0.60
        val cloud = smooth(0.0, 0.55, density)
        return Sample(cloud, precipitation, wet, temp)
    }

    private fun interpolate(field: Array<DoubleArray>, i: Int) = interpolate(field, i % width + 0.5, i / width + 0.5)
    private fun interpolate(field: Array<DoubleArray>, x: Double, y: Double) =
        spatial(field[month], x, y) * (1 - blend) + spatial(field[nextMonth], x, y) * blend

    /** Reconstruct coarse atlas forcing continuously rather than advecting each
     * prolonged climate cell independently. Terrain lift remains a local effect. */
    private fun spatial(field: DoubleArray, x: Double, y: Double): Double {
        val columns = atlas.geography.width
        val rows = atlas.geography.height
        val gx = x * columns / width - 0.5
        val gy = y * rows / height - 0.5
        val left = floor(gx).toInt(); val top = floor(gy).toInt()
        fun at(x: Int, y: Int): Double {
            val tx = floor((x + 0.5) * width / columns).toInt()
            val ty = floor((y + 0.5) * height / rows).toInt().coerceIn(0, height - 1)
            return field[ty * width + ((tx % width) + width) % width]
        }
        val fx = gx - floor(gx); val fy = gy - floor(gy)
        val a = at(left, top) * (1 - fx) + at(left + 1, top) * fx
        val b = at(left, top + 1) * (1 - fx) + at(left + 1, top + 1) * fx
        return a * (1 - fy) + b * fy
    }

    private fun windTravel(field: Array<DoubleArray>, x: Double, y: Double, weights: DoubleArray): Double {
        var travel = 0.0
        for (m in 0..11) if (abs(weights[m]) > 1e-9) travel += flowSpatial(field[m], x, y) * weights[m]
        return travel
    }

    private fun flowSpatial(field: DoubleArray, x: Double, y: Double): Double {
        val w = atlas.cloudFlowWidth; val h = atlas.cloudFlowHeight
        val gx = x * w / width - 0.5; val gy = y * h / height - 0.5
        val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
        val fx = gx - floor(gx); val fy = gy - floor(gy)
        fun at(x: Int, y: Int) = field[y.coerceIn(0, h - 1) * w + x.mod(w)]
        return (at(ix, iy) * (1 - fx) + at(ix + 1, iy) * fx) * (1 - fy) +
            (at(ix, iy + 1) * (1 - fx) + at(ix + 1, iy + 1) * fx) * fy
    }

    /** Exact integration weights shared by every tile in a flow window. */
    private fun windWeights(startDays: Double): DoubleArray {
        fun integral(phase: Double): DoubleArray {
            val m = wrapMonth(floor(phase).toLong())
            val weights = DoubleArray(12) { floor(phase / 12) }
            for (j in 0 until m) { weights[j] += 0.5; weights[(j + 1) % 12] += 0.5 }
            val f = phase - floor(phase)
            val b = f / 2 - sin(PI * f) / (2 * PI)
            weights[m] += f - b; weights[(m + 1) % 12] += b
            return weights
        }
        val start = (startDays * 86400 / atlas.solar.yearSeconds + atlas.solar.initialYearFraction) * 12 - 0.5
        val a = integral(start); val b = integral(monthPosition)
        return DoubleArray(12) { (b[it] - a[it]) * atlas.solar.yearSeconds / 12000 }
    }

    /** C2 value noise, periodic in longitude. Time is another smooth lattice axis,
     * so features gradually condense and evaporate as well as translate. */
    private fun noise(x: Double, y: Double, frequency: Int, time: Double, salt: Int): Double {
        val px = wrap(x, spanX) / spanX * frequency
        val py = y / spanX * frequency
        val ix = floor(px).toLong(); val iy = floor(py).toLong(); val it = floor(time).toLong()
        fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
        val fx = fade(px - floor(px)); val fy = fade(py - floor(py)); val ft = fade(time - floor(time))
        fun plane(t: Long): Double {
            fun row(y: Long): Double {
                val a = hash((ix % frequency + frequency) % frequency, y, t.toInt() xor salt)
                val b = hash(((ix + 1) % frequency + frequency) % frequency, y, t.toInt() xor salt)
                return a + (b - a) * fx
            }
            val a = row(iy); return a + (row(iy + 1) - a) * fy
        }
        val a = plane(it); return a + (plane(it + 1) - a) * ft
    }

    private fun hash(a: Long, b: Long, salt: Int): Double {
        var n = a * 374761393L + b * 668265263L + seed * 1442695041L + salt * 1274126177L
        n = (n xor (n ushr 30)) * -4658895280553007687L
        n = (n xor (n ushr 27)) * -7723592293110705685L
        return ((n xor (n ushr 31)) ushr 11).toDouble() / 9007199254740992.0
    }

    private fun wrapMonth(month: Long) = ((month % 12 + 12) % 12).toInt()
    private fun wrap(value: Double, period: Double) = value - floor(value / period) * period
    private fun smooth(low: Double, high: Double, value: Double): Double {
        val t = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
    private companion object { const val FLOW_DAYS = 0.75 }

}
