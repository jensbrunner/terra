package terra.climate

import terra.world.World
import kotlin.math.*

/** Immutable physical snapshot. All arrays use map order (north at row zero). */
class ClimateGeography(internal val world: World, val config: ClimateConfig = ClimateConfig(width = world.width, height = world.height)) {
    val geometryRevision = world.geometryRevision
    val width = config.width
    val height = config.height
    val count = width * height
    internal val overlap = if (width == world.width && height == world.height) null
        else GridOverlap(world.width, world.height, width, height)
    internal val tileElevation = DoubleArray(world.width * world.height) { world.elevationMeters(it % world.width, it / world.width) }
    internal val tileLand = BooleanArray(tileElevation.size) { world.isLand(it % world.width, it / world.width) }
    internal val elevationMeters = overlap?.let { grid -> DoubleArray(count).also { grid.restrict(tileElevation, it) } } ?: tileElevation
    internal val landFraction = DoubleArray(tileLand.size) { if (tileLand[it]) 1.0 else 0.0 }.let { land ->
        overlap?.let { grid -> DoubleArray(count).also { grid.restrict(land, it) } } ?: land
    }
    internal val coastDistanceMeters = DoubleArray(count) { if (landFraction[it] < 0.5) 0.0 else config.domainWidthMeters + config.domainHeightMeters }
    init {
        // Two directional distance-transform passes repeated for wrapped longitude.
        repeat(3) {
            for (i in 0 until count) relax(i)
            for (i in count - 1 downTo 0) relax(i)
        }

    }
    private fun relax(i: Int) {
        val x = i % width; val y = i / width
        coastDistanceMeters[i] = minOf(coastDistanceMeters[i],
            coastDistanceMeters[y * width + (x + 1) % width] + config.dx,
            coastDistanceMeters[y * width + (x + width - 1) % width] + config.dx,
            coastDistanceMeters[max(0, y - 1) * width + x] + config.dy,
            coastDistanceMeters[min(height - 1, y + 1) * width + x] + config.dy)
    }
}
