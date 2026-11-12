package terra.world

import kotlin.math.abs
import kotlin.math.floor
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
    private val pendingBiome = arrayOfNulls<Terrain>(width * height)
    private val pendingBiomeSeconds = DoubleArray(width * height)
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
        climateBiomesApplied = true
        biomeRevision++
    }

    /** Delayed categorical land-cover response, separate from weather and measured climate.
     * Require two post-spin-up annual cycles, local stationarity, and a target robust
     * to ±1°C/±10% rainfall. A persistent candidate needs two further model years.
     */
    fun advanceClimateBiomes(climate: SeasonalClimateSnapshot?, seconds: Double): Boolean {
        require(seconds.isFinite() && seconds >= 0)
        if (climate == null || !climate.hasAnnualCoverage || seconds == 0.0) return false
        var changed = false
        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.terrain == Terrain.OCEAN || cell.terrain == Terrain.MOUNTAINS) continue
            val x = i % width; val y = i / width
            val temperature = climate.meanSurfaceTemperatureAtTile(x, y, width, height)
            val rain = climate.annualPrecipitationAtTile(x, y, width, height)
            fun classify(t: Double, r: Double) = ClimateBiomeClassifier.classifyBiome(
                cell.terrain, cell.elevation, settings, r, t)
            val target = classify(temperature, rain)
            val robust = target != cell.terrain && climate.stableAtTile(x, y, width, height) &&
                classify(temperature - 1, rain * 0.9) == target && classify(temperature - 1, rain * 1.1) == target &&
                classify(temperature + 1, rain * 0.9) == target && classify(temperature + 1, rain * 1.1) == target
            if (!robust) {
                pendingBiome[i] = null
                pendingBiomeSeconds[i] = 0.0
                continue
            }
            if (pendingBiome[i] != target) {
                pendingBiome[i] = target
                pendingBiomeSeconds[i] = 0.0
            }
            pendingBiomeSeconds[i] += seconds
            if (pendingBiomeSeconds[i] >= 2 * climate.yearSeconds) {
                cells[i] = cell.copy(terrain = target)
                pendingBiome[i] = null
                pendingBiomeSeconds[i] = 0.0
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
