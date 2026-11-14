package terra.world

import kotlin.math.floor
import kotlin.math.expm1
import kotlin.math.max
import kotlin.math.sin
import terra.MAP_HEIGHT_TILES
import terra.MAP_WIDTH_TILES

/** Why a tile cannot support more cover, kept separate from the cover itself. */
enum class GrowthLimitation { NONE, COLD, WATER, ROCK, DISTURBANCE }

class World private constructor(
    val seed: Int,
    val width: Int,
    val height: Int,
    private val settings: TerrainGenerationSettings,
    elevationOverride: DoubleArray?
) {
    constructor(seed: Int, width: Int = MAP_WIDTH_TILES, height: Int = MAP_HEIGHT_TILES,
        settings: TerrainGenerationSettings = TerrainGenerationSettings()) : this(seed, width, height, settings, null)

    private val elevation = elevationOverride?.copyOf() ?: TerrainGenerator(seed, width, height, settings).generate()
    private val cells = Array(width * height) {
        Cell(0.0, Terrain.OCEAN)
    }
    private val publishedTerrainChanges = BooleanArray(width * height)
    private val trees = DoubleArray(width * height)
    private val canopyBefore = DoubleArray(width * height)
    private val grasses = DoubleArray(width * height) { 0.55 }
    private val bare = DoubleArray(width * height) { 0.45 }
    private val limitation = Array(width * height) { GrowthLimitation.NONE }
    private val disturbanceAge = DoubleArray(width * height) { 100.0 }
    private var ecologyElapsedSeconds = 0.0
    fun treeCover(x: Int, y: Int): Double = trees[y * width + ((x % width) + width) % width]
    fun grassCover(x: Int, y: Int): Double = grasses[y * width + ((x % width) + width) % width]
    fun bareCover(x: Int, y: Int): Double = bare[y * width + ((x % width) + width) % width]
    fun growthLimitation(x: Int, y: Int): GrowthLimitation = limitation[y * width + ((x % width) + width) % width]
    val seaLevel: Double get() = settings.seaLevel
    var geometryRevision: Long = 0
        private set
    fun isLand(x: Int, y: Int) = elevation[y * width + ((x % width) + width) % width] >= seaLevel
    fun elevationMeters(x: Int, y: Int) = max(0.0, elevation[y * width + ((x % width) + width) % width] - seaLevel) * 10_000

    /** Physical edits preserve cover on surviving land. New sea/land starts with empty cover. */
    fun setElevation(x: Int, y: Int, value: Double) {
        require(value.isFinite() && value in 0.0..1.0)
        val i = y * width + ((x % width) + width) % width
        if (elevation[i] == value) return
        val wasLand = isLand(x, y)
        elevation[i] = value
        val physical = classifyPhysicalTerrain(value)
        cells[i] = Cell(value, if (wasLand && isLand(x, y) && physical != Terrain.MOUNTAINS && cells[i].terrain != Terrain.MOUNTAINS) cells[i].terrain else physical)
        if (wasLand != isLand(x, y)) { trees[i] = 0.0; grasses[i] = 0.0; bare[i] = 1.0 }
        publishedTerrainChanges[i] = true
        geometryRevision++; biomeRevision++
    }

    /** Equilibrium at creation; thereafter cover responds over years and retains disturbances.
     * Growing-season root-zone availability controls water suitability when supplied by
     * hydrology. Annual aridity is a fallback for atlas-only diagnostic callers. */
    internal fun evolveAtlasCover(atlas: terra.climate.SeasonalClimateAtlas, years: Double, initialize: Boolean = false,
        drought: DoubleArray? = null, soilAvailability: DoubleArray? = null) {
        trees.copyInto(canopyBefore)
        for (i in cells.indices) {
            val x = i % width; val y = i / width
            if (!isLand(x, y)) continue
            val warm = smoothstep(atlas.growingWarmth[i], 100.0, 900.0)
            val moisture = soilAvailability?.let { 1.5 * it[i].coerceIn(0.0, 1.0) } ?: atlas.aridity(i)
            val water = smoothstep(moisture, 0.10, 0.85)
            val rock = smoothstep(elevationMeters(x, y), 2400.0, 4200.0)
            val patch = 0.9 + 0.1 * sin(x * 0.13 + seed) * sin(y * 0.17)
            val targetTree = (0.94 * warm * smoothstep(moisture, 0.45, 1.2) * (1 - rock) * patch)
            val targetGrass = (1 - targetTree) * warm * water * (1 - rock)
            val stress = drought?.get(i) ?: 0.0
            val nearbySeeds = (canopyBefore[y * width + (x + width - 1) % width] + canopyBefore[y * width + (x + 1) % width] +
                canopyBefore[maxOf(0, y - 1) * width + x] + canopyBefore[minOf(height - 1, y + 1) * width + x]) / 4
            // Mature canopy and nearby seed sources speed recruitment; grass competes with saplings.
            val growthRate = (0.015 + 0.12 * nearbySeeds + 0.12 * canopyBefore[i]) * (1 - 0.5 * grasses[i])
            val rate = if (targetTree < trees[i]) 1.0 / (7 - 4 * stress) else growthRate
            val response = if (initialize) 1.0 else -expm1(-years * rate)
            trees[i] += (targetTree * (1 - 0.4 * stress) - trees[i]) * response
            val grassSpace = (1 - trees[i]) * warm * water * (1 - rock)
            grasses[i] += ((if (initialize) targetGrass else grassSpace) - grasses[i]) * (if (initialize) 1.0 else -expm1(-years / 3.0))
            grasses[i] = grasses[i].coerceIn(0.0, 1 - trees[i])
            bare[i] = 1 - trees[i] - grasses[i]
            limitation[i] = when { rock > 0.6 -> GrowthLimitation.ROCK; warm < 0.25 -> GrowthLimitation.COLD; water < 0.3 -> GrowthLimitation.WATER; else -> GrowthLimitation.NONE }
            disturbanceAge[i] += years
            if (!initialize && stress > 0.4 && disturbanceAge[i] > 25 && trees[i] + grasses[i] > 0.4 &&
                hash01(i, (ecologyElapsedSeconds / atlas.solar.yearSeconds).toInt()) < 0.01 * stress) {
                trees[i] *= 0.4; grasses[i] *= 0.7; bare[i] = 1 - trees[i] - grasses[i]
                disturbanceAge[i] = 0.0; limitation[i] = GrowthLimitation.DISTURBANCE
            }
            val next = if (cellIsAlpine(i)) Terrain.MOUNTAINS else if (atlas.warmest[i] < 10) Terrain.TUNDRA else classifyCover(cells[i], trees[i], grasses[i], moisture.coerceIn(0.0, 1.0), atlas.meanTemperature[i])
            if (cells[i].terrain != next) { cells[i] = cells[i].copy(terrain = next) }
        }
        ecologyElapsedSeconds += years * atlas.solar.yearSeconds
        biomeRevision++
    }
    var biomeRevision: Long = 0
        private set
    operator fun get(x: Int, y: Int): Cell = cells[y * width + ((x % width) + width) % width]

    init {
        generate()
    }

    fun snapshot(generation: Long, previous: WorldSnapshot? = null): WorldSnapshot {
        if (previous != null && previous.generation == generation) {
            for (i in cells.indices) if (cells[i].terrain != previous[i % width, i / width].terrain)
                publishedTerrainChanges[i] = true
        }
        val dirty = IntArray(publishedTerrainChanges.count { it })
        var next = 0
        for (i in cells.indices) if (publishedTerrainChanges[i]) dirty[next++] = i
        return WorldSnapshot(generation, seed, width, height, biomeRevision, cells.copyOf(), landBiomeSummary(), dirty,
            trees.copyOf(), grasses.copyOf(), bare.copyOf(), limitation.copyOf(), geometryRevision)
    }

    private fun generate() {
        for (i in cells.indices) {
            val e = elevation[i]
            val terrain = classifyPhysicalTerrain(e)
            cells[i] = Cell(e, terrain)
        }
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
    private fun cellIsAlpine(i: Int) = elevation[i] > settings.mountainThreshold

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
        val t = ((value - edge0) / max(1e-9, edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Smooth sampling of the final eroded raster, including the longitude seam. */
    internal fun sampleElevation(x: Double, y: Double): Double {
        val px = ((x % width) + width) % width - 0.5
        val py = (y - 0.5).coerceIn(0.0, height - 1.0)
        val left = floor(px).toInt(); val top = floor(py).toInt()
        fun cubic(a: Double, b: Double, c: Double, d: Double, t: Double): Double =
            b + 0.5 * t * (c - a + t * (2 * a - 5 * b + 4 * c - d + t * (3 * (b - c) + d - a)))
        fun row(row: Int): Double {
            fun at(column: Int) = elevation[row.coerceIn(0, height - 1) * width + ((column % width) + width) % width]
            return cubic(at(left - 1), at(left), at(left + 1), at(left + 2), px - left)
        }
        return cubic(row(top - 1), row(top), row(top + 1), row(top + 2), py - top).coerceIn(0.0, 1.0)
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

    internal companion object {
        /** Deterministic test/experiment hook; production worlds always come from the generator. */
        fun withElevation(seed: Int, width: Int, height: Int, settings: TerrainGenerationSettings,
            elevation: DoubleArray): World {
            require(elevation.size == width * height && elevation.all { it.isFinite() })
            return World(seed, width, height, settings, elevation)
        }
    }
}
