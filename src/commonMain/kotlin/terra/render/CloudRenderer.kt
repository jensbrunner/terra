package terra.render

import korlibs.image.color.RGBA
import terra.weather.WeatherModel
import terra.weather.WeatherFieldQuality
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Display-only cloud detail. No water, rain, or atmosphere state is written here. */
internal class CloudRenderer(private val width: Int, private val height: Int, seed: Int) {
    private val pixelWidth = width * PIXELS_PER_TILE
    private val pixelHeight = height * PIXELS_PER_TILE
    private val size = pixelWidth * pixelHeight
    var frame = RasterCanvas(pixelWidth, pixelHeight).finish()
        private set
    private val cloud = FloatArray(width * height)
    private val rain = FloatArray(width * height)
    private val instability = FloatArray(width * height)
    private val windU = FloatArray(width * height)
    private val windV = FloatArray(width * height)
    private val broadSource = FloatArray(size)
    private val fineSource = FloatArray(size)
    private var broad = FloatArray(size)
    private var fine = FloatArray(size)
    private var nextBroad = FloatArray(size)
    private var nextFine = FloatArray(size)
    private var previousWeather: WeatherModel? = null
    private var previousSeconds = Double.NaN

    init { reset(seed) }

    fun reset(seed: Int) {
        for (y in 0 until pixelHeight) for (x in 0 until pixelWidth) {
            val i = y * pixelWidth + x
            broadSource[i] = noise(x.toDouble() / pixelWidth, y.toDouble() / PIXELS_PER_TILE,
                max(2, (width / 6.0).roundToInt()), 6.0, seed).toFloat()
            fineSource[i] = noise(x.toDouble() / pixelWidth, y.toDouble() / PIXELS_PER_TILE,
                max(2, (width / 1.5).roundToInt()), 1.5, seed xor 0x51a3).toFloat()
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
        if (weather.presentationIdentity === previousWeather?.presentationIdentity && seconds == previousSeconds) return false
        val restart = weather.presentationIdentity !== previousWeather?.presentationIdentity || seconds < previousSeconds
        if (restart) {
            broadSource.copyInto(broad)
            fineSource.copyInto(fine)
        }
        val illustrative = weather.fieldSemantics.quality == WeatherFieldQuality.REPRESENTATIVE_FOR_INTERVAL
        val days = if (restart || previousSeconds.isNaN()) 0.0 else if (illustrative)
            minOf(0.25, visualElapsedSeconds) / 300.0 else (seconds - previousSeconds) / 86400.0
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            cloud[i] = max(0.0, weather.cloudWaterMm(x, y)).toFloat()
            val rainfall = max(0.0, weather.precipitationMmPerHour(x, y))
            rain[i] = (rainfall / (rainfall + 0.8)).toFloat()
            // A visual regime proxy, not an independent convective cloud or rain source.
            instability[i] = ((weather.surfaceTemperatureC(x, y) - weather.airTemperatureC(x, y) - 15) / 15)
                .coerceIn(0.0, 1.0).toFloat()
            windU[i] = if (illustrative) 30f else weather.windUTilesPerDay(x, y).toFloat()
            windV[i] = if (illustrative) 0f else weather.windVTilesPerDay(x, y).toFloat()
        }
        if (days > 0.0) advect(days)
        val pixels = RasterCanvas(pixelWidth, pixelHeight)
        for (y in 0 until pixelHeight) for (x in 0 until pixelWidth) {
            val i = y * pixelWidth + x
            val px = (x + 0.5) / PIXELS_PER_TILE - 0.5
            val py = (y + 0.5) / PIXELS_PER_TILE - 0.5
            val water = sample(cloud, width, height, px, py)
            if (water <= 0) {
                pixels[x, y] = RGBA(0, 0, 0, 0)
                continue
            }
            val wet = sample(rain, width, height, px, py)
            val convective = sample(instability, width, height, px, py) * wet
            val small = fine[i].toDouble()
            val billow = smoothstep(0.2, 0.8, small)
            val texture = 0.45 + 0.95 * broad[i] + (0.12 + 0.6 * convective) * (billow - 0.5)
            val alpha = (1 - exp(-water / 0.20 * texture)).coerceIn(0.0, 0.96)
            // Gentle upper-left highlights and cool shaded cores distinguish dense rain cloud.
            val lit = sample(fine, pixelWidth, pixelHeight, x - 1.0, y - 2.0) - small
            val dense = water / (water + 0.4)
            val shade = (0.99 - 0.10 * dense - 0.17 * wet + 0.18 * lit +
                (0.025 + 0.08 * convective) * (billow - 0.5)).coerceIn(0.60, 1.0)
            pixels[x, y] = RGBA((244 * shade).roundToInt(), (248 * shade).roundToInt(),
                (249 * shade).roundToInt(), (255 * alpha).roundToInt())
        }
        frame = pixels.finish()
        previousWeather = weather
        previousSeconds = seconds
        return true
    }

    /** One bounded semi-Lagrangian pass, even after a long hidden-overlay/fast-forward interval.
     * Weak deterministic renewal restores detail lost to interpolation; it changes only texture.
     */
    private fun advect(days: Double) {
        val broadRenewal = 1 - exp(-days)
        val fineRenewal = 1 - exp(-days * 3)
        for (y in 0 until pixelHeight) for (x in 0 until pixelWidth) {
            val i = y * pixelWidth + x
            val px = (x + 0.5) / PIXELS_PER_TILE - 0.5
            val py = (y + 0.5) / PIXELS_PER_TILE - 0.5
            val fromX = x - sample(windU, width, height, px, py) * days * PIXELS_PER_TILE
            val fromY = y - sample(windV, width, height, px, py) * days * PIXELS_PER_TILE
            val movedBroad = sample(broad, pixelWidth, pixelHeight, fromX, fromY)
            val movedFine = sample(fine, pixelWidth, pixelHeight, fromX, fromY)
            nextBroad[i] = (movedBroad + broadRenewal * (broadSource[i] - movedBroad)).toFloat()
            nextFine[i] = (movedFine + fineRenewal * (fineSource[i] - movedFine)).toFloat()
        }
        val oldBroad = broad; broad = nextBroad; nextBroad = oldBroad
        val oldFine = fine; fine = nextFine; nextFine = oldFine
    }

    private fun sample(values: FloatArray, w: Int, h: Int, x: Double, y: Double): Double {
        val wrappedX = ((x % w) + w) % w
        val boundedY = y.coerceIn(0.0, h - 1.0)
        val x0 = floor(wrappedX).toInt()
        val x1 = (x0 + 1) % w
        val y0 = floor(boundedY).toInt()
        val y1 = minOf(y0 + 1, h - 1)
        val fx = wrappedX - x0
        val fy = boundedY - y0
        return (values[y0 * w + x0] * (1 - fx) + values[y0 * w + x1] * fx) * (1 - fy) +
            (values[y1 * w + x0] * (1 - fx) + values[y1 * w + x1] * fx) * fy
    }

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
    }
}
