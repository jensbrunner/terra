package terra.climate

import kotlin.math.asin

/** Equal-area latitude: a row’s area is proportional to sin(latitude), not latitude. */
object PlanetLatitude {
    fun radians(northwardFraction: Double): Double = asin(2 * northwardFraction - 1)
    fun northwardRowRadians(y: Int, height: Int): Double = radians((y + 0.5) / height)
    fun terrainRowRadians(y: Int, height: Int): Double = radians(1.0 - (y + 0.5) / height)
}
