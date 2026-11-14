package terra.climate

import terra.MAP_WIDTH_TILES
import terra.MAP_HEIGHT_TILES

/** Resolution and physical extent of the diagnostic climate grid. */
data class ClimateConfig(
    val width: Int = MAP_WIDTH_TILES, val height: Int = MAP_HEIGHT_TILES,
    val domainWidthMeters: Double = 12_000_000.0,
    val domainHeightMeters: Double = 9_000_000.0,
    val rotationRateRadiansPerSecond: Double = 7.2921159e-5
) {
    init {
        require(width >= 4 && height >= 4 && width.toLong() * height <= Int.MAX_VALUE)
        require(domainWidthMeters.isFinite() && domainWidthMeters > 0)
        require(domainHeightMeters.isFinite() && domainHeightMeters > 0)
        require(rotationRateRadiansPerSecond.isFinite())
    }
    val dx get() = domainWidthMeters / width
    val dy get() = domainHeightMeters / height
}
