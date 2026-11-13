package terra.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.expm1
import kotlin.math.max
import terra.MAP_HEIGHT_TILES
import terra.MAP_WIDTH_TILES
import terra.weather.ClimateSnapshot
import terra.weather.SeasonalClimateSnapshot

/** Why a tile cannot support more cover, kept separate from the cover itself. */
enum class GrowthLimitation { NONE, COLD, FROST, WATER, SOIL, ROCK, DISTURBANCE }

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
        Cell(0.0, 0.0, Terrain.OCEAN)
    }
    private val publishedTerrainChanges = BooleanArray(width * height)
    private var climateBiomesApplied = false
    private val trees = DoubleArray(width * height)
    private val grasses = DoubleArray(width * height) { 0.55 }
    private val bare = DoubleArray(width * height) { 0.45 }
    private val evergreen = DoubleArray(width * height)
    private val droughtStress = DoubleArray(width * height)
    private val seedBank = DoubleArray(width * height)
    private val limitation = Array(width * height) { GrowthLimitation.NONE }
    private val disturbanceAge = DoubleArray(width * height) { 100.0 }
    private var ecologyElapsedSeconds = 0.0
    private var burnCount = 0
    val fireCount: Int get() = burnCount
    fun disturbanceAgeAt(x: Int, y: Int): Double = disturbanceAge[y * width + ((x % width) + width) % width]
    fun treeCover(x: Int, y: Int): Double = trees[y * width + ((x % width) + width) % width]
    fun grassCover(x: Int, y: Int): Double = grasses[y * width + ((x % width) + width) % width]
    fun bareCover(x: Int, y: Int): Double = bare[y * width + ((x % width) + width) % width]
    fun evergreenFraction(x: Int, y: Int): Double = evergreen[y * width + ((x % width) + width) % width]
    fun growthLimitation(x: Int, y: Int): GrowthLimitation = limitation[y * width + ((x % width) + width) % width]
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
        return WorldSnapshot(generation, seed, width, height, biomeRevision, cells.copyOf(), landBiomeSummary(), dirty,
            trees.copyOf(), grasses.copyOf(), bare.copyOf(), evergreen.copyOf(), limitation.copyOf())
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

    /** Establish a mature-looking initial ecology consistent with the observed climate. */
    fun applyClimateBiomes(climate: ClimateSnapshot) {
        check(!climateBiomesApplied) { "Climate biomes have already been applied" }
        require(climate.width == width && climate.height == height) {
            "Climate snapshot dimensions must match the world"
        }
        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.terrain == Terrain.OCEAN) { trees[i] = 0.0; grasses[i] = 0.0; bare[i] = 1.0; continue }
            val x = i % width
            val y = i / width
            val rain = climate.annualPrecipitationMmPerYear(x, y)
            val temperature = climate.meanAirTemperatureC(x, y)
            val pet = ClimateBiomeClassifier.potentialEvaporationMmPerYear(temperature)
            val moisture = (rain / max(1.0, pet)).coerceIn(0.0, 2.0)
            val warmth = smoothstep(temperature, -6.0, 12.0)
            val cold = smoothstep(temperature, -14.0, 3.0)
            val treePotential = ((moisture - 0.32) / 0.85).coerceIn(0.0, 1.0) * warmth * cold
            val grassPotential = ((moisture + 0.15) / 0.95).coerceIn(0.0, 1.0) * max(0.2, warmth)
            trees[i] = (0.82 * treePotential).coerceIn(0.0, 0.92)
            grasses[i] = (grassPotential * (1 - trees[i])).coerceIn(0.0, 1 - trees[i])
            bare[i] = (1 - trees[i] - grasses[i]).coerceIn(0.0, 1.0)
            evergreen[i] = smoothstep(temperature, 0.0, 16.0) * (0.5 + 0.5 * (1 - moisture.coerceIn(0.0, 1.0)))
            seedBank[i] = treePotential
            droughtStress[i] = 0.0
            limitation[i] = when {
                cell.terrain == Terrain.MOUNTAINS -> GrowthLimitation.ROCK
                cold < 0.3 -> GrowthLimitation.COLD
                moisture < 0.25 -> GrowthLimitation.WATER
                else -> GrowthLimitation.NONE
            }
            val next = classifyCover(i, cell, trees[i], grasses[i], moisture, temperature)
            if (next != Terrain.OCEAN && next != Terrain.MOUNTAINS) cells[i] = cell.copy(terrain = next)
        }
        climateBiomesApplied = true
        biomeRevision++
    }

    /**
     * Persistent fractional ecology driven by growing-season warmth, frost, root-zone
     * water and seasonal deficit - never annual mean temperature and rain alone.
     *
     * Growth, establishment, mortality and competition have separate timescales.
     * Extant canopy stores ecological memory; neighboring seed availability accelerates
     * migration, and repeated drought thins the canopy before grasses or bare ground expand.
     */
    fun advanceClimateBiomes(climate: SeasonalClimateSnapshot?, seconds: Double, soilAvailability: DoubleArray? = null): Boolean {
        require(seconds.isFinite() && seconds >= 0)
        if (climate == null || !climate.hasAnnualCoverage || seconds == 0.0) return false
        require(soilAvailability == null || soilAvailability.size == cells.size)
        val years = seconds / climate.yearSeconds
        if (years <= 0.0) return false
        val previousTrees = trees.copyOf()
        var changed = false
        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.terrain == Terrain.OCEAN) continue
            val x = i % width; val y = i / width
            val warmth = climate.growingSeasonWarmthAtTile(x, y, width, height)
            val frost = climate.frostMinimumAtTile(x, y, width, height)
            val rain = climate.annualPrecipitationAtTile(x, y, width, height)
            val deficit = climate.seasonalWaterDeficitAtTile(x, y, width, height)
            val meanTemperature = climate.meanSurfaceTemperatureAtTile(x, y, width, height)
            val soil = soilAvailability?.get(i) ?: 0.5
            val coldLimit = smoothstep(warmth, 180.0, 720.0)
            val frostLimit = smoothstep(frost, -20.0, -2.0)
            val waterLimit = smoothstep(soil, 0.06, 0.42) * smoothstep(rain, 35.0, 260.0)
            val aridity = (deficit / 1200.0).coerceIn(0.0, 1.0)
            val growth = coldLimit * frostLimit * waterLimit
            val treeTarget = (growth * (1 - 0.65 * aridity)).coerceIn(0.0, 0.95)
            val grassTarget = ((1 - treeTarget) * (0.05 + 0.95 * growth) * (1 - 0.3 * aridity)).coerceIn(0.0, 1 - treeTarget)

            // Persistent drought stress: a short dry spell is tolerated; repeated or
            // prolonged deficit accumulates and accelerates canopy loss.
            val droughtSeverity = ((0.30 - soil) / 0.30).coerceIn(0.0, 1.0) * (0.4 + 0.6 * aridity)
            droughtStress[i] = (droughtStress[i] * kotlin.math.exp(-years / 1.8) + droughtSeverity * years / 1.8)
                .coerceIn(0.0, 1.0)
            val stress = droughtStress[i]

            val neighborTrees = (previousTrees[y * width + (x + width - 1) % width] +
                previousTrees[y * width + (x + 1) % width] +
                previousTrees[maxOf(0, y - 1) * width + x] +
                previousTrees[minOf(height - 1, y + 1) * width + x]) / 4
            seedBank[i] = maxOf(seedBank[i] * kotlin.math.exp(-years / 30.0), treeTarget * 0.25,
                neighborTrees * 0.85)
            val seed = maxOf(seedBank[i], neighborTrees)
            val growthTau = if (trees[i] < 0.12) 6.0 / (0.3 + seed) else 20.0 / (0.35 + seed)
            val declineTau = 5.0 + 8.0 * stress
            val tau = if (treeTarget > trees[i]) growthTau else declineTau
            val response = -kotlin.math.expm1(-years / tau)
            trees[i] = (trees[i] + (treeTarget - trees[i]) * response).coerceIn(0.0, 0.98)
            grasses[i] = (grasses[i] + (grassTarget - grasses[i]) * -kotlin.math.expm1(-years / 3.0))
                .coerceIn(0.0, 1 - trees[i])
            bare[i] = (1 - trees[i] - grasses[i]).coerceIn(0.0, 1.0)
            val evergreenTarget = smoothstep(meanTemperature, 1.0, 15.0) * (1 - 0.45 * aridity)
            evergreen[i] = (evergreen[i] + (evergreenTarget - evergreen[i]) * -kotlin.math.expm1(-years / 8.0))
                .coerceIn(0.0, 1.0)

            limitation[i] = when {
                cell.terrain == Terrain.MOUNTAINS -> GrowthLimitation.ROCK
                coldLimit < 0.25 -> GrowthLimitation.COLD
                frostLimit < 0.4 -> GrowthLimitation.FROST
                waterLimit < 0.3 -> GrowthLimitation.WATER
                soil < 0.08 -> GrowthLimitation.SOIL
                trees[i] + grasses[i] < 0.04 -> GrowthLimitation.DISTURBANCE
                else -> GrowthLimitation.NONE
            }
            // Low-frequency fire emerges from fuel, drought and a deterministic weather
            // opportunity. It changes cover only; the water and energy stores are untouched.
            disturbanceAge[i] += years
            val fuel = trees[i] + grasses[i]
            val dryness = (droughtStress[i] + 0.3 * aridity).coerceIn(0.0, 1.0)
            if (disturbanceAge[i] > FIRE_MINIMUM_AGE_YEARS && fuel > 0.35 && meanTemperature > 1.0 && dryness > 0.12) {
                val yearIndex = (ecologyElapsedSeconds / climate.yearSeconds).toInt()
                val probability = (0.003 + 0.02 * dryness) * (0.4 + 0.6 * fuel)
                if (hash01(i, yearIndex) < probability) {
                    val severity = 0.45 + 0.45 * hash01(i, yearIndex + 7919)
                    trees[i] = (trees[i] * (1 - severity)).coerceIn(0.0, 0.98)
                    grasses[i] = (grasses[i] * (1 - 0.5 * severity)).coerceIn(0.0, 1 - trees[i])
                    bare[i] = (1 - trees[i] - grasses[i]).coerceIn(0.0, 1.0)
                    seedBank[i] = maxOf(seedBank[i], 0.25)
                    droughtStress[i] = 0.0
                    disturbanceAge[i] = 0.0
                    limitation[i] = GrowthLimitation.DISTURBANCE
                    burnCount++
                }
            }
            val moistureLabel = (rain / max(1.0, rain + deficit)).coerceIn(0.0, 1.0)
            val next = classifyCover(i, cell, trees[i], grasses[i], moistureLabel, meanTemperature)
            if (next != cell.terrain) {
                cells[i] = cell.copy(terrain = next)
                changed = true
            }
        }
        ecologyElapsedSeconds += seconds
        if (changed) biomeRevision++
        return changed
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
    private fun classifyCover(i: Int, cell: Cell, tree: Double, grass: Double, moisture: Double, temperature: Double): Terrain {
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

    internal companion object {
        private const val FIRE_MINIMUM_AGE_YEARS = 25.0

        /** Deterministic test/experiment hook; production worlds always come from the generator. */
        fun withElevation(seed: Int, width: Int, height: Int, settings: TerrainGenerationSettings,
            elevation: DoubleArray): World {
            require(elevation.size == width * height && elevation.all { it.isFinite() })
            return World(seed, width, height, settings, elevation)
        }
    }
}
