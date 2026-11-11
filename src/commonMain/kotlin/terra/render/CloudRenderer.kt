package terra.render

import korlibs.image.color.RGBA
import terra.weather.WeatherModel
import terra.weather.WeatherFieldQuality
import terra.weather.SynopticPattern
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt

/** Display-only cloud detail. No water, rain, or atmosphere state is written here. */
internal class CloudRenderer(private val width: Int, private val height: Int, seed: Int) {
    private val pixelWidth = width * PIXELS_PER_TILE
    private val pixelHeight = height * PIXELS_PER_TILE
    private val size = pixelWidth * pixelHeight
    // Fixed 2x raster sampling coordinates: reuse across every field and refresh.
    private val tileLeft = IntArray(pixelWidth) { (floor((it + 0.5) / 2 - 0.5).toInt() + width) % width }
    private val tileRight = IntArray(pixelWidth) { (tileLeft[it] + 1) % width }
    private val tileFx = DoubleArray(pixelWidth) { if (it % 2 == 0) 0.75 else 0.25 }
    private val tileTop = IntArray(pixelHeight) { floor((it + 0.5) / 2 - 0.5).toInt().coerceIn(0, height - 1) * width }
    private val tileBottom = IntArray(pixelHeight) { (floor((it + 0.5) / 2 - 0.5).toInt() + 1).coerceIn(0, height - 1) * width }
    private val tileFy = DoubleArray(pixelHeight) { if (it % 2 == 0) 0.75 else 0.25 }
    var frame = RasterCanvas(pixelWidth, pixelHeight).finish()
        private set
    private val cloud = FloatArray(width * height)
    private val rain = FloatArray(width * height)
    private val instability = FloatArray(width * height)
    private val windU = FloatArray(width * height)
    private val windV = FloatArray(width * height)
    private val illustrativeU = FloatArray(width * height)
    private val illustrativeV = FloatArray(width * height)
    private val broadSource = FloatArray(size)
    private val fineSource = FloatArray(size)
    private var broad = FloatArray(size)
    private var fine = FloatArray(size)
    private var nextBroad = FloatArray(size)
    private var nextFine = FloatArray(size)
    private var previousWeather: WeatherModel? = null
    private var previousSeconds = Double.NaN
    private var previousIllustrative = false

    init { reset(seed) }

    fun reset(seed: Int) {
        val pattern = SynopticPattern(seed, height.toDouble() / width)
        for (y in 0 until pixelHeight) for (x in 0 until pixelWidth) {
            val i = y * pixelWidth + x
            val longitude = x.toDouble() / pixelWidth
            val tileY = y.toDouble() / PIXELS_PER_TILE
            // Warp larger air-mass structures before adding billows; multiplying
            // opacity by fine noise alone cannot make coherent cloud-free gaps.
            val warp = noise(longitude, tileY, 4, max(8.0, height / 4.0), seed xor 0x71b9) - 0.5
            val broadValue = noise(longitude + warp * 0.13, tileY + warp * height * 0.16,
                max(3, (width / 22.0).roundToInt()), max(6.0, height / 9.0), seed)
            val frontal = pattern.cloudBands(longitude, 1 - (y + 0.5) / pixelHeight, coreRadiusSquared = 0.0005)
            broadSource[i] = (0.65 * smoothstep(0.12, 0.65, frontal) +
                0.35 * smoothstep(0.25, 0.75, broadValue)).toFloat()
            fineSource[i] = (0.65 * noise(longitude, tileY,
                max(2, (width / 2.8).roundToInt()), 2.8, seed xor 0x51a3) +
                0.35 * noise(longitude, tileY, max(2, width), 1.0, seed xor 0x195b)).toFloat()
        }
        // A steady streamfunction-like flow for the illustrative climate texture.
        // Precompute once; no synthetic wind is fed to the atmosphere or inspector.
        val radius = minOf(width, height) * 0.20
        for (y in 0 until height) for (x in 0 until width) {
            val latitude = (y + 0.5) / height
            var u = 30 + 35 * sin(latitude * PI * 2) * sin(latitude * PI * 2)
            var v = 0.0
            for (hemisphere in 0..1) for (system in 0..1) {
                val centerX = width * pattern.centerX(hemisphere, system)
                val dx = x + 0.5 - centerX - floor((x + 0.5 - centerX) / width + 0.5) * width
                val dy = y + 0.5 - height * (1 - pattern.centerNorth(hemisphere))
                val turn = (if (hemisphere == 0) 1 else -1) * 170 * exp(-(dx * dx + dy * dy) / (2 * radius * radius))
                u -= dy / radius * turn
                v += dx / radius * turn
            }
            illustrativeU[y * width + x] = u.toFloat()
            illustrativeV[y * width + x] = v.toFloat()
        }
        broadSource.copyInto(broad)
        fineSource.copyInto(fine)
        previousWeather = null
        previousSeconds = Double.NaN
    }

    /** Caller caps the real-time refresh rate. Repeated observations of a paused model do no work. */
    fun update(weather: WeatherModel, visualElapsedSeconds: Double = 0.0): Boolean {
        require(visualElapsedSeconds.isFinite() && visualElapsedSeconds >= 0)
        require(weather.width == width && weather.height == height)
        val seconds = weather.elapsedSeconds
        require(seconds.isFinite() && seconds >= 0)
        val illustrative = weather.fieldSemantics.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL
        if (weather.presentationIdentity === previousWeather?.presentationIdentity && seconds == previousSeconds &&
            (!illustrative || visualElapsedSeconds == 0.0)) return false
        val restart = weather.presentationIdentity !== previousWeather?.presentationIdentity || seconds < previousSeconds
        if (restart) {
            broadSource.copyInto(broad)
            fineSource.copyInto(fine)
        }
        val days = if (restart || previousSeconds.isNaN()) 0.0 else if (illustrative)
            minOf(0.25, visualElapsedSeconds) / 60.0 else
            (if (previousIllustrative) weather.fieldSemantics.intervalDurationSeconds else seconds - previousSeconds).coerceIn(0.0, 21600.0) / 86400.0
        val response = if (restart || visualElapsedSeconds == 0.0) 1.0 else 1 - exp(-minOf(0.25, visualElapsedSeconds) / 0.7)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val targetWater = max(0.0, weather.cloudWaterMm(x, y))
            cloud[i] = (cloud[i] + response * (targetWater - cloud[i])).toFloat()
            val rainfall = max(0.0, weather.precipitationMmPerHour(x, y))
            rain[i] = (rainfall / (rainfall + 0.8)).toFloat()
            // A visual regime proxy, not an independent convective cloud or rain source.
            instability[i] = ((weather.surfaceTemperatureC(x, y) - weather.airTemperatureC(x, y) - 15) / 15)
                .coerceIn(0.0, 1.0).toFloat()
            // Climate has no resolved wind. This bounded, sheared display flow is
            // explicitly illustrative; detailed texture follows the actual wind.
            windU[i] = if (illustrative) illustrativeU[i] else weather.windUTilesPerDay(x, y).toFloat()
            windV[i] = if (illustrative) illustrativeV[i] else weather.windVTilesPerDay(x, y).toFloat()
        }
        if (days > 0.0) advect(days)
        val pixels = RasterCanvas(pixelWidth, pixelHeight)
        for (y in 0 until pixelHeight) for (x in 0 until pixelWidth) {
            val i = y * pixelWidth + x
            val water = sampleTile(cloud, x, y)
            if (water <= 0) {
                pixels[x, y] = RGBA(0, 0, 0, 0)
                continue
            }
            val wet = sampleTile(rain, x, y)
            val convective = sampleTile(instability, x, y) * wet
            val small = fine[i].toDouble()
            val billow = smoothstep(0.2, 0.8, small)
            val coverage = 0.82 * (1 - exp(-water / 0.08))
            val structure = 0.8 * broad[i] + 0.2 * small
            val edge = smoothstep(1 - coverage - 0.09, 1 - coverage + 0.09, structure)
            val optical = 1 - exp(-water / 0.10)
            val alpha = (edge * optical * (0.78 + 0.22 * billow)).coerceIn(0.0, 0.94)
            // Gentle upper-left highlights and cool shaded cores distinguish dense rain cloud.
            val lit = fine[maxOf(0, y - 2) * pixelWidth + (if (x == 0) pixelWidth - 1 else x - 1)] - small
            val dense = water / (water + 0.4)
            val shade = (0.99 - 0.10 * dense - 0.17 * wet + 0.18 * lit +
                (0.025 + 0.08 * convective) * (billow - 0.5)).coerceIn(0.60, 1.0)
            pixels[x, y] = RGBA((244 * shade).roundToInt(), (248 * shade).roundToInt(),
                (249 * shade).roundToInt(), (255 * alpha).roundToInt())
        }
        frame = pixels.finish()
        previousWeather = weather
        previousSeconds = seconds
        previousIllustrative = illustrative
        return true
    }

    /** One bounded semi-Lagrangian pass, even after a long hidden-overlay/fast-forward interval.
     * Weak deterministic renewal restores detail lost to interpolation; it changes only texture.
     */
    private fun advect(days: Double) {
        val broadRenewal = 1 - exp(-days * 0.08)
        val fineRenewal = 1 - exp(-days * 0.3)
        for (y in 0 until pixelHeight) for (x in 0 until pixelWidth) {
            val i = y * pixelWidth + x
            val fromX = x - sampleTile(windU, x, y) * days * PIXELS_PER_TILE
            val fromY = y - sampleTile(windV, x, y) * days * PIXELS_PER_TILE
            // Both textures follow the same backtrace. Most samples are inside
            // the map, so avoid modulo/floor and duplicate coordinate work there.
            val wrappedX = if (fromX >= 0 && fromX < pixelWidth) fromX else
                ((fromX % pixelWidth) + pixelWidth) % pixelWidth
            val boundedY = fromY.coerceIn(0.0, pixelHeight - 1.0)
            val x0 = wrappedX.toInt()
            val x1 = if (x0 + 1 == pixelWidth) 0 else x0 + 1
            val y0 = boundedY.toInt() * pixelWidth
            val y1 = minOf(boundedY.toInt() + 1, pixelHeight - 1) * pixelWidth
            val fx = wrappedX - x0
            val fy = boundedY - boundedY.toInt()
            val movedBroad = interpolate(broad, y0 + x0, y0 + x1, y1 + x0, y1 + x1, fx, fy)
            val movedFine = interpolate(fine, y0 + x0, y0 + x1, y1 + x0, y1 + x1, fx, fy)
            nextBroad[i] = (movedBroad + broadRenewal * (broadSource[i] - movedBroad)).toFloat()
            nextFine[i] = (movedFine + fineRenewal * (fineSource[i] - movedFine)).toFloat()
        }
        val oldBroad = broad; broad = nextBroad; nextBroad = oldBroad
        val oldFine = fine; fine = nextFine; nextFine = oldFine
    }

    private fun sampleTile(values: FloatArray, x: Int, y: Int): Double = interpolate(values,
        tileTop[y] + tileLeft[x], tileTop[y] + tileRight[x],
        tileBottom[y] + tileLeft[x], tileBottom[y] + tileRight[x], tileFx[x], tileFy[y])

    private fun interpolate(values: FloatArray, a: Int, b: Int, c: Int, d: Int, fx: Double, fy: Double): Double =
        (values[a] * (1 - fx) + values[b] * fx) * (1 - fy) +
            (values[c] * (1 - fx) + values[d] * fx) * fy

    /** Seamless seeded value noise in longitude, with smooth local detail in latitude. */
    private fun noise(longitude: Double, y: Double, columns: Int, scaleY: Double, seed: Int): Double {
        val px = longitude * columns
        val py = y / scaleY
        val ix = floor(px).toInt()
        val iy = floor(py).toInt()
        val fx = smoothstep(0.0, 1.0, px - ix)
        val fy = smoothstep(0.0, 1.0, py - iy)
        fun lattice(x: Int, y: Int): Double {
            var hash = ((x % columns + columns) % columns) * 374761393 + y * 668265263 + seed
            hash = (hash xor (hash ushr 13)) * 1274126177
            hash = hash xor (hash ushr 16)
            return hash.toUInt().toLong() / 4294967295.0
        }
        return (lattice(ix, iy) * (1 - fx) + lattice(ix + 1, iy) * fx) * (1 - fy) +
            (lattice(ix, iy + 1) * (1 - fx) + lattice(ix + 1, iy + 1) * fx) * fy
    }

    private fun smoothstep(low: Double, high: Double, value: Double): Double {
        val t = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    companion object {
        const val PIXELS_PER_TILE = 2
        const val REFRESH_SECONDS = 0.1
    }
}
