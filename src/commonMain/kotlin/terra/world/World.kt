package terra.world

import kotlin.math.expm1
import kotlin.math.max
import kotlin.math.sin
import terra.MAP_HEIGHT_TILES
import terra.MAP_WIDTH_TILES

/** Why a tile cannot support more cover, kept separate from the cover itself. */
enum class GrowthLimitation { NONE, COLD, WATER, ROCK, DISTURBANCE }

class World(
    val seed: Int,
    val width: Int = MAP_WIDTH_TILES,
    val height: Int = MAP_HEIGHT_TILES,
    private val settings: TerrainGenerationSettings = TerrainGenerationSettings()
) {

    private val cells = TerrainGenerator(seed, width, height, settings).generate()
        .map { Cell(it, classifyPhysicalTerrain(it)) }.toTypedArray()
    private val publishedTerrainChanges = BooleanArray(width * height)
    private val trees = DoubleArray(width * height)
    private val canopyBefore = DoubleArray(width * height)
    private val grasses = DoubleArray(width * height) { 0.55 }
    private val limitation = Array(width * height) { GrowthLimitation.NONE }
    private val disturbanceAge = DoubleArray(width * height) { 100.0 }
    private var ecologyElapsedSeconds = 0.0
    val seaLevel: Double get() = settings.seaLevel
    fun isLand(x: Int, y: Int) = this[x, y].elevation >= seaLevel
    fun elevationMeters(x: Int, y: Int) = max(0.0, this[x, y].elevation - seaLevel) * 10_000

    /** Cover responds to accumulated live weather and retains disturbances. */
    internal fun evolveWeatherCover(years: Double, temperatureC: DoubleArray, soilAvailability: DoubleArray) {
        trees.copyInto(canopyBefore)
        for (i in cells.indices) {
            val x = i % width; val y = i / width
            if (!isLand(x, y)) continue
            val warm = smoothstep(temperatureC[i], -5.0, 18.0)
            val moisture = 1.5 * soilAvailability[i].coerceIn(0.0, 1.0)
            val water = smoothstep(moisture, 0.10, 0.85)
            val rock = smoothstep(elevationMeters(x, y), 2400.0, 4200.0)
            val patch = 0.9 + 0.1 * sin(x * 0.13 + seed) * sin(y * 0.17)
            val targetTree = (0.94 * warm * smoothstep(moisture, 0.45, 1.2) * (1 - rock) * patch)
            val stress = 1.0 - moisture.coerceIn(0.0, 1.0)
            val nearbySeeds = (canopyBefore[y * width + (x + width - 1) % width] + canopyBefore[y * width + (x + 1) % width] +
                canopyBefore[maxOf(0, y - 1) * width + x] + canopyBefore[minOf(height - 1, y + 1) * width + x]) / 4
            // Mature canopy and nearby seed sources speed recruitment; grass competes with saplings.
            val growthRate = (0.015 + 0.12 * nearbySeeds + 0.12 * canopyBefore[i]) * (1 - 0.5 * grasses[i])
            val rate = if (targetTree < trees[i]) 1.0 / (7 - 4 * stress) else growthRate
            val response = -expm1(-years * rate)
            trees[i] += (targetTree * (1 - 0.4 * stress) - trees[i]) * response
            val grassSpace = (1 - trees[i]) * warm * water * (1 - rock)
            grasses[i] += (grassSpace - grasses[i]) * -expm1(-years / 3.0)
            grasses[i] = grasses[i].coerceIn(0.0, 1 - trees[i])
            limitation[i] = when { rock > 0.6 -> GrowthLimitation.ROCK; warm < 0.25 -> GrowthLimitation.COLD; water < 0.3 -> GrowthLimitation.WATER; else -> GrowthLimitation.NONE }
            disturbanceAge[i] += years
            if (stress > 0.4 && disturbanceAge[i] > 25 && trees[i] + grasses[i] > 0.4 &&
                hash01(i, (ecologyElapsedSeconds / (365.0 * 86400)).toInt()) < 0.01 * stress) {
                trees[i] *= 0.4; grasses[i] *= 0.7
                disturbanceAge[i] = 0.0; limitation[i] = GrowthLimitation.DISTURBANCE
            }
            val next = if (temperatureC[i] < -5 && cells[i].terrain != Terrain.MOUNTAINS) Terrain.TUNDRA else classifyCover(cells[i], trees[i], grasses[i], moisture.coerceIn(0.0, 1.0), temperatureC[i])
            if (cells[i].terrain != next) { cells[i] = cells[i].copy(terrain = next) }
        }
        ecologyElapsedSeconds += years * 365.0 * 86400
        biomeRevision++
    }
    var biomeRevision: Long = 0
        private set
    operator fun get(x: Int, y: Int): Cell = cells[y * width + ((x % width) + width) % width]

    fun snapshot(generation: Long, previous: WorldSnapshot? = null): WorldSnapshot {
        if (previous != null && previous.generation == generation) {
            for (i in cells.indices) if (cells[i].terrain != previous[i % width, i / width].terrain)
                publishedTerrainChanges[i] = true
        }
        val dirty = IntArray(publishedTerrainChanges.count { it })
        var next = 0
        for (i in cells.indices) if (publishedTerrainChanges[i]) dirty[next++] = i
        return WorldSnapshot(generation, width, height, biomeRevision, cells.copyOf(), landBiomeSummary(), dirty,
            trees.copyOf(), grasses.copyOf(), limitation.copyOf())
    }

    /** Deterministic [0,1) weather opportunity for a tile and absolute year. */
    private fun hash01(tile: Int, year: Int): Double {
        var value = seed.toLong() xor (tile.toLong() * 73_856_093L) xor (year.toLong() * 83_492_791L)
        value = value xor (value ushr 16)
        value *= 0x7feb352dL
        value = value xor (value ushr 15)
        value *= 0x846ca68bL
        value = value xor (value ushr 16)
        return (value ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /** Cold limitation is a first-class category: cold wet land must not become desert. */
    private fun classifyCover(cell: Cell, tree: Double, grass: Double, moisture: Double, temperature: Double): Terrain {
        if (cell.terrain == Terrain.OCEAN) return Terrain.OCEAN
        if (cell.terrain == Terrain.MOUNTAINS || cell.elevation > settings.mountainThreshold) return Terrain.MOUNTAINS
        val cold = temperature < -4.0 || (temperature < 1.0 && tree < 0.12)
        if (cold) return Terrain.TUNDRA
        val wasWoods = cell.terrain == Terrain.WOODS
        val wasDesert = cell.terrain == Terrain.DESERT
        return when {
            tree > if (wasWoods) 0.38 else 0.52 -> Terrain.WOODS
            tree + grass < (if (wasDesert) 0.30 else 0.20) && moisture < 0.45 -> Terrain.DESERT
            moisture < 0.58 || tree + grass < 0.55 -> Terrain.STEPPE
            else -> Terrain.PLAINS
        }
    }

    private fun smoothstep(value: Double, edge0: Double, edge1: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
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

}
