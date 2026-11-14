package terra.climate

import kotlin.math.*

/** Instantaneous weather, evaluated directly at absolute time; no atmospheric history or RNG state.
 * Landscape water continues to use the conservative monthly climate budget. */
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
    private val flowWindow = floor(days / 3).toLong()
    private val flowBlend = smooth(0.0, 1.0, days / 3 - floor(days / 3))
    private val travelWeights = Array(2) { windWeights((flowWindow - 1 + it) * 3.0) }
    private val storms = buildStorms()
    private val sampled = BooleanArray(width * height)
    private val clouds = DoubleArray(width * height)
    private val rain = DoubleArray(width * height)
    private val humidity = DoubleArray(width * height)
    private val temperature = DoubleArray(width * height)

    init {
        require(elapsedSeconds.isFinite())
    }

    fun cloudCover(x: Int, y: Int) = clouds[index(x, y)]
    fun precipitationMmPerHour(x: Int, y: Int) = rain[index(x, y)]
    fun relativeHumidity(x: Int, y: Int) = humidity[index(x, y)]
    fun temperatureC(x: Int, y: Int) = temperature[index(x, y)]
    fun windEastMetersPerSecond(x: Int, y: Int) = interpolate(atlas.east, y * width + x)
    fun windSouthMetersPerSecond(x: Int, y: Int) = interpolate(atlas.south, y * width + x)

    private fun index(x: Int, y: Int): Int {
        val i = y * width + x
        if (!sampled[i]) { evaluate(x, y); sampled[i] = true }
        return i
    }

    private data class Storm(val x: Double, val y: Double, val radius: Double, val strength: Double,
        val angle: Double, val age: Double, val kind: Int, val spin: Double)

    private fun buildStorms(): List<Storm> = buildList {
        // Bounded overlap of independently seeded births. The envelope reaches zero at
        // both ends, so dropping expired systems or adding new ones cannot pop a cloud.
        val epoch = floor(days * 2).toLong()
        for (birth in epoch - 12..epoch) for (slot in 0..2) {
            fun random(channel: Int) = hash(birth, slot, channel)
            val kind = when { random(0) < 0.08 -> 2; random(0) < 0.42 -> 1; else -> 0 }
            val lifetime = when (kind) { 1 -> 0.35 + random(1) * 0.65; 2 -> 4 + random(1) * 2; else -> 2 + random(1) * 2 }
            val age = days - birth * 0.5
            if (age <= 0 || age >= lifetime) continue
            val x = random(2) * spanX
            val y = (0.06 + 0.88 * random(3)) * spanY
            val tileX = (x / spanX * width).toInt().coerceIn(0, width - 1)
            val tileY = (y / spanY * height).toInt().coerceIn(0, height - 1)
            val i = tileY * width + tileX
            val birthTime = birth * 43200.0
            val birthPhase = (birthTime / atlas.solar.yearSeconds + atlas.solar.initialYearFraction) * 12 - 0.5
            val birthMonth = wrapMonth(floor(birthPhase).toLong())
            val f = (1 - cos(PI * (birthPhase - floor(birthPhase)))) / 2
            fun atBirth(field: Array<DoubleArray>) = field[birthMonth][i] * (1 - f) + field[(birthMonth + 1) % 12][i] * f
            val u = atBirth(atlas.east); val v = atBirth(atlas.south)
            val wet = atBirth(atlas.humidity)
            val warm = smooth(8.0, 28.0, atBirth(atlas.temperature))
            val latitude = PlanetLatitude.terrainRowRadians(tileY, height)
            val tropical = smooth(0.08, 0.22, abs(latitude)) * (1 - smooth(0.45, 0.85, abs(latitude)))
            val suitability = when (kind) {
                1 -> warm * wet
                2 -> tropical * smooth(23.0, 29.0, atBirth(atlas.temperature)) * wet * if (atlas.geography.tileLand[i]) 0.12 else 1.0
                else -> 0.35 + 0.65 * wet
            }
            val envelope = smooth(0.0, 0.2, age / lifetime) * (1 - smooth(0.65, 1.0, age / lifetime))
            val radius = when (kind) { 1 -> 100 + 140 * random(4); 2 -> 450 + 350 * random(4); else -> 550 + 650 * random(4) }
            add(Storm(x + u * age * 86.4, y + v * age * 86.4, radius,
                envelope * suitability * (0.7 + random(5) * 0.6), atan2(v, u), age, kind,
                if (latitude >= 0) -1.0 else 1.0))
        }
    }

    private fun evaluate(x: Int, y: Int) {
        val i = y * width + x
        val px = (x + 0.5) / width * spanX
        val py = (y + 0.5) / height * spanY
        val wet = interpolate(atlas.humidity, i)
        val temp = atlas.temperature[month][i] * (1 - blend) + atlas.temperature[nextMonth][i] * blend
        val climateRain = interpolate(atlas.rain, i) * 12 * 3600 / atlas.solar.yearSeconds
        val u = interpolate(atlas.east, i); val v = interpolate(atlas.south, i)
        // Analytic integral of the periodic, cosine-interpolated seasonal winds.
        // Multiplying today's wind by absolute time would make season changes teleport clouds.
        // Overlapping flow windows bound shear even after centuries. Each window
        // uses its own seed and fades in/out with zero slope at the handoff.
        var broad = 0.0; var billow = 0.0; var detail = 0.0
        for (packetIndex in 0..1) {
            val packet = flowWindow - 1 + packetIndex
            val weight = if (packetIndex == 1) flowBlend else 1 - flowBlend
            val nx = px - windTravel(atlas.east, i, travelWeights[packetIndex])
            val ny = py - windTravel(atlas.south, i, travelWeights[packetIndex])
            val salt = packet.toInt() * 1013
            val b = noise(nx, ny, 18, days / 7, 11 xor salt)
            val c = noise(nx + 90 * b, ny + 65 * b, 54, days / 2.5, 23 xor salt)
            val f = noise(nx + 40 * c, ny, 150, days / 1.2, 37 xor salt)
            broad += b * weight
            billow += c * weight
            detail += (0.55 * b + 0.30 * c + 0.15 * f) * weight
        }
        var lifting = 0.0
        var convection = 0.0
        var cooling = 0.0
        for (storm in storms) {
            val dx = wrap(px - storm.x + spanX / 2, spanX) - spanX / 2
            val dy = py - storm.y
            if (abs(dx) > storm.radius * 2.5 || abs(dy) > storm.radius * 2.5) continue
            val along = (dx * cos(storm.angle) + dy * sin(storm.angle)) / storm.radius
            val across = (-dx * sin(storm.angle) + dy * cos(storm.angle)) / storm.radius
            val radius = hypot(along, across)
            val shape = when (storm.kind) {
                1 -> exp(-3 * radius * radius) * (0.6 + 0.4 * billow)
                2 -> {
                    val angle = atan2(dy, dx) + storm.spin * (storm.age * 1.4 - radius * 5)
                    val bands = 0.45 + 0.55 * (0.5 + 0.5 * cos(3 * angle))
                    exp(-radius * radius) * bands * smooth(0.07, 0.24, radius)
                }
                else -> {
                    val front = across + 0.3 * sin(along * 2 + storm.age * 0.4)
                    exp(-along * along * 0.35 - front * front * 15) * (0.65 + 0.35 * billow)
                }
            }
            val force = shape * storm.strength * (1 - smooth(2.0, 2.5, radius))
            lifting += force
            if (storm.kind == 1) convection += force
            cooling += force * if (storm.kind == 0) 2.5 else 1.0
        }
        val west = atlas.geography.tileElevation[y * width + (x + width - 1) % width]
        val east = atlas.geography.tileElevation[y * width + (x + 1) % width]
        val north = atlas.geography.tileElevation[max(0, y - 1) * width + x]
        val south = atlas.geography.tileElevation[min(height - 1, y + 1) * width + x]
        val ascent = u * (east - west) / (2 * config.domainWidthMeters / width) +
            v * (south - north) / (2 * config.domainHeightMeters / height)
        val terrainLift = (ascent * 1.5).coerceIn(-0.06, 0.12)
        val moisture = smooth(0.12, 0.85, wet)
        val density = detail + wet * 0.50 + lifting * 0.65 + terrainLift - 0.60
        clouds[i] = smooth(0.0, 0.55, density)
        val rainCloud = smooth(0.50, 0.94, clouds[i])
        rain[i] = rainCloud * moisture * (climateRain * (0.4 + 10 * lifting) +
            9 * lifting + 24 * convection * smooth(8.0, 28.0, temp))
        humidity[i] = (wet + clouds[i] * 0.16 + lifting * 0.12 - (1 - detail) * 0.08).coerceIn(0.0, 1.0)
        temperature[i] = temp + (broad - 0.5) * 3 - cooling.coerceAtMost(5.0)
    }

    private fun interpolate(field: Array<DoubleArray>, i: Int) = spatial(field[month], i) * (1 - blend) + spatial(field[nextMonth], i) * blend

    /** Reconstruct coarse atlas forcing continuously rather than advecting each
     * prolonged climate cell independently. Terrain lift remains a local effect. */
    private fun spatial(field: DoubleArray, i: Int): Double {
        val gx = (i % width + 0.5) * atlas.geography.width / width - 0.5
        val gy = (i / width + 0.5) * atlas.geography.height / height - 0.5
        val left = floor(gx).toInt(); val top = floor(gy).toInt()
        fun at(x: Int, y: Int): Double {
            val tx = floor((x + 0.5) * width / atlas.geography.width).toInt()
            val ty = floor((y + 0.5) * height / atlas.geography.height).toInt().coerceIn(0, height - 1)
            return field[ty * width + ((tx % width) + width) % width]
        }
        val fx = gx - floor(gx); val fy = gy - floor(gy)
        val a = at(left, top) * (1 - fx) + at(left + 1, top) * fx
        val b = at(left, top + 1) * (1 - fx) + at(left + 1, top + 1) * fx
        return a * (1 - fy) + b * fy
    }

    private fun windTravel(field: Array<DoubleArray>, i: Int, weights: DoubleArray): Double {
        var travel = 0.0
        for (m in 0..11) if (abs(weights[m]) > 1e-9) travel += spatial(field[m], i) * weights[m]
        return travel
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

    private fun hash(a: Long, b: Int, salt: Int) = hash(a, b.toLong(), salt)
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
}
