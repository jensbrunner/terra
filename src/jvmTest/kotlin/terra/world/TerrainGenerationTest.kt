package terra.world

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import terra.hydrology.HydrologyTopology

class TerrainGenerationTest {
    @Test
    fun reproducibleTerrainPreservesFlatControlsAndSamplesTheFinalSurface() {
        val world = World(31742, 45, 33)
        val repeated = World(31742, 45, 33)
        val other = World(42, 45, 33)
        var different = false
        for (y in 0 until world.height) for (x in 0 until world.width) {
            assertEquals(world[x, y], repeated[x, y])
            assertEquals(world[x, y].elevation, world.sampleElevation(x + 0.5, y + 0.5), 1e-12)
            if (world[x, y] != other[x, y]) different = true
        }
        assertTrue(different)
        val flat = World(42, 9, 7, TerrainGenerationSettings(baseElevation = 0.4,
            continentStrength = 0.0, regionalStrength = 0.0, detailStrength = 0.0))
        for (y in 0 until flat.height) for (x in 0 until flat.width) assertEquals(0.4, flat[x, y].elevation)
    }

    @Test
    fun unErodedGeographyUsesTheSamePlanetCoordinatesAtDifferentResolutions() {
        val settings = TerrainGenerationSettings(erosionStrength = 0.0)
        val low = World(31742, 9, 7, settings)
        val high = World(31742, 27, 21, settings)
        for (y in 0 until low.height) for (x in 0 until low.width)
            assertEquals(low[x, y].elevation, high[x * 3 + 1, y * 3 + 1].elevation, 1e-12)
    }

    @Test
    fun inspectThreeWorldsAndTheirDrainage() {
        val output = File("build/reports/terrain").apply { mkdirs() }
        val summary = StringBuilder()
        val seeds = listOf(31742, 42, 2026)
        val image = BufferedImage(283 * 3, 212 * 2, BufferedImage.TYPE_INT_RGB)
        for ((column, seed) in seeds.withIndex()) {
            val start = System.nanoTime()
            val world = World(seed)
            val generationMs = (System.nanoTime() - start) / 1e6
            val topology = HydrologyTopology(world)
            var land = 0; var mountains = 0; var slope = 0.0; var pits = 0
            for (y in 0 until world.height) for (x in 0 until world.width) {
                val i = y * world.width + x
                val elevation = world[x, y].elevation
                assertTrue(elevation.isFinite() && elevation in 0.0..1.0)
                val isLand = elevation >= world.seaLevel
                if (isLand) land++
                if (world[x, y].terrain == Terrain.MOUNTAINS) mountains++
                if (isLand && topology.filledElevation[i] > elevation + 0.001) pits++
                val dx = world[x + 1, y].elevation - world[x - 1, y].elevation
                val dy = world[x, (y + 1).coerceAtMost(world.height - 1)].elevation -
                    world[x, (y - 1).coerceAtLeast(0)].elevation
                val shade = (0.82 - 2 * dx - 3 * dy).coerceIn(0.4, 1.15)
                slope += sqrt(dx * dx + dy * dy) / 2
                val color = if (!isLand) {
                    val shallow = (elevation / world.seaLevel).coerceIn(0.0, 1.0)
                    Color((10 + 22 * shallow).toInt(), (30 + 55 * shallow).toInt(), (60 + 62 * shallow).toInt())
                } else {
                    val h = ((elevation - world.seaLevel) / (1 - world.seaLevel)).coerceIn(0.0, 1.0)
                    val r = if (h < 0.35) 95 + 115 * h else 135 + 120 * (h - 0.35)
                    val g = if (h < 0.35) 135 - 20 * h else 128 + 100 * (h - 0.35)
                    val b = 70 + 190 * h
                    Color((r * shade).toInt().coerceIn(0, 255), (g * shade).toInt().coerceIn(0, 255), (b * shade).toInt().coerceIn(0, 255))
                }
                image.setRGB(column * world.width + x, y, color.rgb)
                val flow = topology.upstreamLandTileCount[i]
                val drainage = if (isLand && flow >= 18) Color(40, 150, 205) else color
                image.setRGB(column * world.width + x, y + world.height, drainage.rgb)
            }
            val n = world.width * world.height.toDouble()
            summary.appendLine("seed=$seed generation_ms=$generationMs land_fraction=${land / n} mountain_fraction=${mountains / n} mean_slope=${slope / n} depression_land_fraction=${pits.toDouble() / land.coerceAtLeast(1)} lakes=${topology.lakeCandidates.size}")
        }
        ImageIO.write(image, "png", File(output, "terrain-current.png"))
        File(output, "terrain-current.txt").writeText(summary.toString())
        println(summary)
    }
}
