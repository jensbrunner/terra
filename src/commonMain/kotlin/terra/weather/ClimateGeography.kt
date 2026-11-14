package terra.weather

import terra.atmosphere.AtmosphereTerrain
import terra.atmosphere.AtmosphereTileMapping
import terra.world.Terrain
import terra.world.World
import kotlin.math.max

/** Static climate geography uses the same above-sea terrain and directional uplift
 * as detailed weather. Submerged relief cannot produce atmospheric rain shadows.
 */
internal class ClimateGeography(world: World, grid: ClimateGrid) {
    private val mapping = AtmosphereTileMapping(world.width, world.height, grid.config)
    private val terrain = AtmosphereTerrain(mapping, DoubleArray(world.width * world.height) {
        max(0.0, world[it % world.width, it / world.width].elevation - world.seaLevel) * 10_000.0
    })
    val landFraction = DoubleArray(grid.count) { cell ->
        var amount = 0.0
        mapping.forCell(cell) { tile, fraction ->
            if (world[tile % world.width, tile / world.width].terrain != Terrain.OCEAN) amount += fraction
        }
        amount / mapping.tilesPerCell
    }
    val elevationMeters get() = terrain.meanElevationMeters
    val slopeEast get() = terrain.slopeEast
    val slopeNorth get() = terrain.slopeNorth
    fun upliftMetersPerSecond(cell: Int, east: Double, north: Double) = terrain.upliftMetersPerSecond(cell, east, north)
}
