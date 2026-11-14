package terra.climate

import kotlin.math.PI

/** Cell-centered pseudo-latitude on an equal-area Cartesian map, with no pole crossing. */
object PlanetLatitude {
    fun radians(northwardFraction: Double): Double = (northwardFraction - 0.5) * 170.0 * PI / 180.0
    fun northwardRowRadians(y: Int, height: Int): Double = radians((y + 0.5) / height)
    fun terrainRowRadians(y: Int, height: Int): Double = radians(1.0 - (y + 0.5) / height)
}
