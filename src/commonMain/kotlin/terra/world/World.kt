package terra.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random
import terra.MAP_HEIGHT_TILES
import terra.MAP_WIDTH_TILES
import terra.weather.ClimateSnapshot

class World(
    val seed: Int,
    val width: Int = MAP_WIDTH_TILES,
    val height: Int = MAP_HEIGHT_TILES,
    private val settings: TerrainGenerationSettings = TerrainGenerationSettings()
) {
    private val noiseSalt = Random(seed).nextInt()
    private val cells = Array(width * height) {
        Cell(0.0, 0.0, Terrain.OCEAN)
    }
    private var climateBiomesApplied = false
    operator fun get(x: Int, y: Int): Cell = cells[y * width + x]

    init {
        generate()
    }

    private fun generate() {
        val salt = noiseSalt
        val elevation = generateElevation(salt)
        val temperature = generateTemperature(elevation)

        for (i in cells.indices) {
            val e = elevation[i]
            val temp = temperature[i]
            val terrain = classifyPhysicalTerrain(e)
            cells[i] = Cell(e, temp, terrain)
        }
    }

    /** Apply climate-derived land cover once, preserving the generated ocean and mountain cells. */
    fun applyClimateBiomes(climate: ClimateSnapshot) {
        check(!climateBiomesApplied) { "Climate biomes have already been applied" }
        require(climate.width == width && climate.height == height) {
            "Climate snapshot dimensions must match the world"
        }

        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.terrain == Terrain.OCEAN || cell.terrain == Terrain.MOUNTAINS) continue
            val x = i % width
            val y = i / width
            val terrain = ClimateBiomeClassifier.classifyBiome(
                physicalTerrain = cell.terrain,
                elevation = cell.elevation,
                settings = settings,
                annualPrecipitationMm = climate.annualPrecipitationMmPerYear(x, y),
                meanAirTemperatureC = climate.meanAirTemperatureC(x, y)
            )
            cells[i] = cell.copy(terrain = terrain)
        }
        climateBiomesApplied = true
    }

    private fun generateElevation(salt: Int): DoubleArray {
        val elevation = DoubleArray(width * height)

        for (y in 0 until height) for (x in 0 until width) {
            val i = index(x, y)
            val continental = fbm(x, y, salt, settings.continentOctaves, settings.continentScale)
            val regional = fbm(x, y, salt + 71, settings.regionalOctaves, settings.regionalScale)
            val detail = fbm(x, y, salt + 191, settings.detailOctaves, settings.detailScale)
            elevation[i] = (settings.baseElevation +
                continental * settings.continentStrength +
                regional * settings.regionalStrength +
                detail * settings.detailStrength)
                .coerceIn(0.0, 1.0)
        }

        return elevation
    }

    private fun generateTemperature(elevation: DoubleArray): DoubleArray {
        val temperature = DoubleArray(width * height)

        for (y in 0 until height) {
            // This map is treated as a north-to-south slice of a planet:
            // latitude is 0 at the equator (center) and 1 at either pole (edge).
            val latitude = if (height <= 1) 0.0 else abs(2.0 * y / (height - 1) - 1.0)

            for (x in 0 until width) {
                val i = index(x, y)
                val elevationAboveSea = max(0.0, elevation[i] - settings.seaLevel)
                temperature[i] = settings.equatorTemperatureC -
                    latitude * settings.poleTemperatureDropC -
                    elevationAboveSea * settings.elevationCoolingCOPerUnit
            }
        }

        return temperature
    }

    private fun classifyPhysicalTerrain(elevation: Double): Terrain = when {
        elevation < settings.seaLevel -> Terrain.OCEAN
        elevation > settings.mountainThreshold -> Terrain.MOUNTAINS
        else -> Terrain.PLAINS
    }

    fun landBiomeSummary(): String {
        val landCount = cells.count { it.terrain != Terrain.OCEAN }
        if (landCount == 0) return "No land"

        fun percent(terrain: Terrain) =
            (cells.count { it.terrain == terrain } * 100 / landCount)

        return "Plains ${percent(Terrain.PLAINS)}% · Steppe ${percent(Terrain.STEPPE)}% · " +
            "Woods ${percent(Terrain.WOODS)}%\n" +
            "Desert ${percent(Terrain.DESERT)}% · Tundra ${percent(Terrain.TUNDRA)}% · " +
            "Mountains ${percent(Terrain.MOUNTAINS)}%"
    }

    private fun index(x: Int, y: Int) = y * width + x

    private fun fbm(x: Int, y: Int, seed: Int, octaves: Int, baseScale: Double): Double {
        var total = 0.0
        var weight = 0.0
        var amplitude = 1.0
        var scale = baseScale
        for (octave in 0 until octaves) {
            total += simplexNoise(x / scale, y / scale, seed + octave * 997) * amplitude
            weight += amplitude
            amplitude *= settings.noisePersistence
            scale /= settings.noiseLacunarity
        }
        return total / weight
    }

    private fun simplexNoise(x: Double, y: Double, seed: Int): Double {
        val skew = (x + y) * F2
        val i = floor(x + skew).toInt()
        val j = floor(y + skew).toInt()
        val unskew = (i + j) * G2
        val x0 = x - (i - unskew)
        val y0 = y - (j - unskew)

        val i1 = if (x0 > y0) 1 else 0
        val j1 = if (x0 > y0) 0 else 1
        val x1 = x0 - i1 + G2
        val y1 = y0 - j1 + G2
        val x2 = x0 - 1.0 + 2.0 * G2
        val y2 = y0 - 1.0 + 2.0 * G2

        val corner0 = simplexCorner(i, j, x0, y0, seed)
        val corner1 = simplexCorner(i + i1, j + j1, x1, y1, seed)
        val corner2 = simplexCorner(i + 1, j + 1, x2, y2, seed)
        return ((corner0 + corner1 + corner2) * 70.0).coerceIn(-1.0, 1.0)
    }

    private fun simplexCorner(i: Int, j: Int, x: Double, y: Double, seed: Int): Double {
        val attenuation = 0.5 - x * x - y * y
        if (attenuation <= 0.0) return 0.0
        val gradientIndex = (((lattice(i, j, seed) + 1.0) * 4.0).toInt()).coerceIn(0, 7)
        val gradient = when (gradientIndex) {
            0 -> x + y
            1 -> -x + y
            2 -> x - y
            3 -> -x - y
            4 -> x
            5 -> -x
            6 -> y
            else -> -y
        }
        val squared = attenuation * attenuation
        return squared * squared * gradient
    }

    private fun lattice(x: Int, y: Int, seed: Int): Double {
        var n = x * 374761393 + y * 668265263 + seed * 1442695041
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n.toUInt().toLong() / 4294967295.0) * 2.0 - 1.0
    }


    private companion object {
        const val F2 = 0.3660254037844386
        const val G2 = 0.21132486540518713
    }
}
