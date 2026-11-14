package terra.weather

import terra.atmosphere.DryAtmosphereConfig
import terra.world.World
import kotlin.math.*

/** Immutable physical snapshot. All arrays use map order (north at row zero). */
class ClimateGeography(internal val world: World, val config: DryAtmosphereConfig = DryAtmosphereConfig()) {
    val geometryRevision = world.geometryRevision
    val width = config.width
    val height = config.height
    val count = width * height
    internal val overlap = GridOverlap(world.width, world.height, width, height)
    internal val tileElevation = DoubleArray(world.width * world.height) { world.elevationMeters(it % world.width, it / world.width) }
    internal val tileLand = BooleanArray(tileElevation.size) { world.isLand(it % world.width, it / world.width) }
    internal val elevationMeters = DoubleArray(count).also { overlap.restrict(tileElevation, it) }
    internal val landFraction = DoubleArray(count).also { a -> overlap.restrict(DoubleArray(tileLand.size) { if (tileLand[it]) 1.0 else 0.0 }, a) }
    internal val coastDistanceMeters = DoubleArray(count) { if (landFraction[it] < 0.5) 0.0 else config.domainWidthMeters + config.domainHeightMeters }
    internal val slopeEast = DoubleArray(count)
    internal val slopeSouth = DoubleArray(count)
    init {
        // Two directional distance-transform passes repeated for wrapped longitude.
        repeat(3) {
            for (i in 0 until count) relax(i)
            for (i in count - 1 downTo 0) relax(i)
        }
        for (i in 0 until count) {
            val x = i % width; val y = i / width
            slopeEast[i] = (elevationMeters[y * width + (x + 1) % width] - elevationMeters[y * width + (x + width - 1) % width]) / (2 * config.dx)
            slopeSouth[i] = (elevationMeters[min(height - 1, y + 1) * width + x] - elevationMeters[max(0, y - 1) * width + x]) / (2 * config.dy)
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
