package terra.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.expm1
import kotlin.math.max
import terra.MAP_HEIGHT_TILES
import terra.MAP_WIDTH_TILES
import terra.weather.ClimateSnapshot
import terra.weather.SeasonalClimateSnapshot

class World(
    val seed: Int,
    val width: Int = MAP_WIDTH_TILES,
    val height: Int = MAP_HEIGHT_TILES,
    private val settings: TerrainGenerationSettings = TerrainGenerationSettings()
) {
    private val elevation = TerrainGenerator(seed, width, height, settings).generate()
    private val cells = Array(width * height) {
        Cell(0.0, 0.0, Terrain.OCEAN)
    }
    private val publishedTerrainChanges = BooleanArray(width * height)
    private var climateBiomesApplied = false
    private val trees = DoubleArray(width * height)
    private val grasses = DoubleArray(width * height) { 0.65 }
    fun treeCover(x: Int, y: Int): Double = trees[y * width + ((x % width) + width) % width]
    fun grassCover(x: Int, y: Int): Double = grasses[y * width + ((x % width) + width) % width]
    val seaLevel: Double get() = settings.seaLevel
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
        return WorldSnapshot(generation, seed, width, height, biomeRevision, cells.copyOf(), landBiomeSummary(), dirty)
    }

    private fun generate() {
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
        for (i in cells.indices) {
            trees[i] = if (cells[i].terrain == Terrain.WOODS) 0.8 else 0.05
            grasses[i] = if (cells[i].terrain == Terrain.DESERT) 0.05 else (1 - trees[i]) * 0.7
        }
        climateBiomesApplied = true
        biomeRevision++
    }

    /** Persistent fractional cover; establishment is slower than drought mortality.
     * Labels are derived with hysteresis. Annual calls follow the calendar in fast mode.
     */
    fun advanceClimateBiomes(climate: SeasonalClimateSnapshot?, seconds: Double, soilAvailability: DoubleArray? = null): Boolean {
        require(seconds.isFinite() && seconds >= 0)
        if (climate == null || !climate.hasAnnualCoverage || seconds == 0.0) return false
        require(soilAvailability == null || soilAvailability.size == cells.size)
        val years = seconds / climate.yearSeconds
        val previousTrees = trees.copyOf()
        var changed = false
        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.terrain == Terrain.OCEAN || cell.terrain == Terrain.MOUNTAINS) continue
            val x = i % width; val y = i / width
            val temperature = climate.meanSurfaceTemperatureAtTile(x, y, width, height)
            val rain = climate.annualPrecipitationAtTile(x, y, width, height)
            val pet = ClimateBiomeClassifier.potentialEvaporationMmPerYear(temperature)
            val moisture = rain / max(1.0, pet)
            val thermal = ((temperature + 6) / 12).coerceIn(0.0, 1.0)
            val droughtSurvival = soilAvailability?.let { 0.5 + 0.5 * (it[i] / 0.5).coerceIn(0.0, 1.0) } ?: 1.0
            val treeTarget = ((moisture - 0.45) / 0.85).coerceIn(0.0, 1.0) * thermal * droughtSurvival
            val grassTarget = minOf(1 - treeTarget, (moisture / 0.55).coerceIn(0.0, 1.0) * thermal)
            val neighborTrees = (previousTrees[y * width + (x + width - 1) % width] +
                previousTrees[y * width + (x + 1) % width] +
                previousTrees[maxOf(0, y - 1) * width + x] +
                previousTrees[minOf(height - 1, y + 1) * width + x]) / 4
            val establishmentYears = 25.0 / (0.5 + neighborTrees)
            val tau = if (treeTarget > trees[i]) establishmentYears else 6.0
            trees[i] += (treeTarget - trees[i]) * -expm1(-years / tau)
            grasses[i] += (grassTarget - grasses[i]) * -expm1(-years / 2.0)
            grasses[i] = grasses[i].coerceIn(0.0, 1 - trees[i])
            val next = when {
                temperature < -6 -> Terrain.TUNDRA
                trees[i] > if (cell.terrain == Terrain.WOODS) 0.40 else 0.55 -> Terrain.WOODS
                trees[i] + grasses[i] < if (cell.terrain == Terrain.DESERT) 0.25 else 0.15 -> Terrain.DESERT
                moisture < 0.55 -> Terrain.STEPPE
                else -> Terrain.PLAINS
            }
            if (next != cell.terrain) {
                cells[i] = cell.copy(terrain = next)
                changed = true
            }
        }
        if (changed) biomeRevision++
        return changed
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

}
