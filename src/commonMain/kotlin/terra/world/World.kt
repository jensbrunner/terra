package terra.world

import kotlin.math.expm1
import kotlin.math.max
import terra.MAP_HEIGHT_TILES
import terra.MAP_WIDTH_TILES

/** Why a tile cannot support more cover, kept separate from the cover itself. */
enum class GrowthLimitation { NONE, COLD, WATER, ROCK }

class World(
    val seed: Int,
    val width: Int = MAP_WIDTH_TILES,
    val height: Int = MAP_HEIGHT_TILES,
    internal val settings: TerrainGenerationSettings = TerrainGenerationSettings()
) {
    private val tileCount = width * height
    private val cells = TerrainGenerator(seed, width, height, settings).generate()
        .map { Cell(it, classifyPhysicalTerrain(it)) }
        .toTypedArray()
    private val terrainChanges = BooleanArray(tileCount)
    private val trees = DoubleArray(tileCount)
    private val grasses = DoubleArray(tileCount) { if (cells[it].terrain == Terrain.OCEAN) 0.0 else 1.0 }
    private val limitations = Array(tileCount) { GrowthLimitation.NONE }

    internal var plateIds = IntArray(tileCount)
    internal var plateBoundaries = ByteArray(tileCount)
    internal var plateCount = 0
    var geographyRevision: Long = 0
        private set
    val seaLevel: Double get() = settings.seaLevel
    var biomeRevision: Long = 0
        private set

    operator fun get(x: Int, y: Int): Cell = cells[index(x, y)]

    fun isLand(x: Int, y: Int) = this[x, y].elevation >= seaLevel

    fun elevationMeters(x: Int, y: Int) = max(0.0, this[x, y].elevation - seaLevel) * 10_000

    /** Annual climate sets carrying capacity; cover takes years to establish or die back. */
    internal fun evolveWeatherCover(years: Double, temperatureC: DoubleArray, waterAvailability: DoubleArray) {
        val forestGrowth = -expm1(-years / 25.0)
        val forestLoss = -expm1(-years / 5.0)
        val grassResponse = -expm1(-years / 3.0)
        for (i in cells.indices) {
            val x = i % width
            val y = i / width
            if (!isLand(x, y)) continue
            val warm = smoothstep(temperatureC[i], -8.0, 10.0)
            val moisture = waterAvailability[i].coerceIn(0.0, 1.0)
            val water = smoothstep(moisture, 0.12, 0.55)
            val rock = smoothstep(elevationMeters(x, y), 2400.0, 4200.0)
            val targetTrees = 0.94 * warm * smoothstep(moisture, 0.45, 0.9) * (1 - rock)
            trees[i] += (targetTrees - trees[i]) * if (targetTrees > trees[i]) forestGrowth else forestLoss
            val targetGrass = (1 - trees[i]) * warm * water * (1 - rock)
            grasses[i] += (targetGrass - grasses[i]) * grassResponse
            grasses[i] = grasses[i].coerceIn(0.0, 1 - trees[i])
            limitations[i] = when {
                rock > 0.6 -> GrowthLimitation.ROCK
                warm < 0.25 -> GrowthLimitation.COLD
                water < 0.3 -> GrowthLimitation.WATER
                else -> GrowthLimitation.NONE
            }
            val nextTerrain = classifyCover(cells[i], trees[i], grasses[i], moisture, temperatureC[i])
            if (cells[i].terrain != nextTerrain) cells[i] = cells[i].copy(terrain = nextTerrain)
        }
        biomeRevision++
    }

    internal fun applyGeography(elevation: DoubleArray, sourceX: DoubleArray, sourceY: DoubleArray) {
        val oldTrees = trees.copyOf()
        val oldGrass = grasses.copyOf()
        for (i in cells.indices) {
            val land = elevation[i] >= seaLevel
            trees[i] = if (land) sampleField(oldTrees, sourceX[i], sourceY[i], width, height) else 0.0
            grasses[i] = if (land) sampleField(oldGrass, sourceX[i], sourceY[i], width, height) else 0.0
            val terrain = when {
                !land -> Terrain.OCEAN
                elevation[i] > settings.mountainThreshold -> Terrain.MOUNTAINS
                cells[i].terrain == Terrain.OCEAN || cells[i].terrain == Terrain.MOUNTAINS -> Terrain.PLAINS
                else -> cells[i].terrain
            }
            cells[i] = Cell(elevation[i], terrain)
        }
        geographyRevision++
        biomeRevision++
    }

    fun snapshot(generation: Long, previous: WorldSnapshot? = null): WorldSnapshot {
        if (previous != null && previous.generation == generation) {
            for (i in cells.indices) {
                if (cells[i].terrain != previous[i % width, i / width].terrain) terrainChanges[i] = true
            }
        }

        val changedTiles = IntArray(terrainChanges.count { it })
        var next = 0
        for (i in terrainChanges.indices) if (terrainChanges[i]) changedTiles[next++] = i

        return WorldSnapshot(
            generation, width, height, biomeRevision, geographyRevision, seaLevel, plateIds, plateCount, plateBoundaries, cells.copyOf(), landBiomeSummary(), changedTiles,
            trees.copyOf(), grasses.copyOf(), limitations.copyOf()
        )
    }

    /** Cold limitation is a first-class category: cold wet land must not become desert. */
    private fun classifyCover(cell: Cell, tree: Double, grass: Double, moisture: Double, temperature: Double): Terrain {
        if (cell.terrain == Terrain.OCEAN) return Terrain.OCEAN
        if (cell.elevation > settings.mountainThreshold) return Terrain.MOUNTAINS
        if (temperature < -4.0 || (temperature < 1.0 && tree < 0.12)) return Terrain.TUNDRA

        val wasWoods = cell.terrain == Terrain.WOODS
        val wasDesert = cell.terrain == Terrain.DESERT
        return when {
            tree > if (wasWoods) 0.38 else 0.52 -> Terrain.WOODS
            tree + grass < (if (wasDesert) 0.30 else 0.20) && moisture < 0.45 -> Terrain.DESERT
            moisture < 0.45 || tree + grass < 0.55 -> Terrain.STEPPE
            else -> Terrain.PLAINS
        }
    }

    private fun classifyPhysicalTerrain(elevation: Double): Terrain = when {
        elevation < settings.seaLevel -> Terrain.OCEAN
        else -> Terrain.PLAINS
    }

    /** Single pass keeps the inspector summary cheap as the live map changes. */
    fun landBiomeSummary(): String {
        val counts = IntArray(Terrain.entries.size)
        for (cell in cells) if (cell.terrain != Terrain.OCEAN) counts[cell.terrain.ordinal]++
        val landCount = counts.sum()
        if (landCount == 0) return "No land"

        fun percent(terrain: Terrain) = counts[terrain.ordinal] * 100 / landCount
        return "Plains ${percent(Terrain.PLAINS)}% · Steppe ${percent(Terrain.STEPPE)}% · " +
            "Woods ${percent(Terrain.WOODS)}%\n" +
            "Desert ${percent(Terrain.DESERT)}% · Tundra ${percent(Terrain.TUNDRA)}% · " +
            "Mountains ${percent(Terrain.MOUNTAINS)}%"
    }

    private fun index(x: Int, y: Int) = y * width + ((x % width) + width) % width

    private fun smoothstep(value: Double, edge0: Double, edge1: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
