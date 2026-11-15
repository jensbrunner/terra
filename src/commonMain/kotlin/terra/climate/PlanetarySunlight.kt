package terra.climate

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared by physics and tile presentation. Geometry is cached; updating the clock allocates nothing. */
class PlanetarySunlight(
    width: Int,
    height: Int
) {
    private val sinLatitude = DoubleArray(height) { 1.0 - 2.0 * (it + 0.5) / height }
    private val cosLatitude = DoubleArray(height) { sqrt(1 - sinLatitude[it] * sinLatitude[it]) }
    private val sinLongitude = DoubleArray(width) { sin(2 * PI * ((it + 0.5) / width - 0.5)) }
    private val cosLongitude = DoubleArray(width) { cos(2 * PI * ((it + 0.5) / width - 0.5)) }
    private val tiltSin = sin(23.44 * PI / 180.0)
    private var sinDeclination = 0.0
    private var cosDeclination = 1.0
    private var sinRotation = 0.0
    private var cosRotation = 1.0
    private var lastSeconds = Double.NaN

    init {
        require(width > 0 && height > 0)
        update(0.0)
    }

    fun update(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0)
        if (seconds == lastSeconds) return
        sinDeclination = tiltSin * sin(2 * PI * ((seconds / YEAR_SECONDS) % 1.0))
        cosDeclination = sqrt(max(0.0, 1 - sinDeclination * sinDeclination))
        val rotation = 2 * PI * ((seconds / DAY_SECONDS) % 1.0)
        sinRotation = sin(rotation)
        cosRotation = cos(rotation)
        lastSeconds = seconds
    }

    fun cosineZenith(x: Int, y: Int): Double = sinLatitude[y] * sinDeclination +
        cosLatitude[y] * cosDeclination * (cosLongitude[x] * cosRotation - sinLongitude[x] * sinRotation)

    fun inputFactor(x: Int, y: Int): Double = max(0.0, cosineZenith(x, y))

    /** Diurnal mean of the same cosine-zenith geometry; independent of longitude/rotation. */
    fun dailyMeanInputFactor(y: Int): Double {
        val a = sinLatitude[y] * sinDeclination
        val b = cosLatitude[y] * cosDeclination
        if (b <= 1e-15) return max(0.0, a)
        val sunset = acos((-a / b).coerceIn(-1.0, 1.0))
        return max(0.0, (sunset * a + b * sin(sunset)) / PI)
    }

    /** Smooth visual twilight around the horizon, independent of shortwave intensity. */
    fun nightOpacity(x: Int, y: Int): Double {
        val daylight = ((cosineZenith(x, y) + 0.1) / 0.2).coerceIn(0.0, 1.0)
        return 0.65 * (1 - daylight * daylight * (3 - 2 * daylight))
    }

}
