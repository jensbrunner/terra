package terra.world

import kotlin.math.*

/** Densities in kg/m³; columns in km, areas in terrain-cell units. */
internal const val CONTINENT_DENSITY = 2800.0
internal const val BASALT_DENSITY = 3000.0
internal const val SEDIMENT_DENSITY = 2400.0
internal const val MANTLE_DENSITY = 3300.0
internal const val WATER_LOADING = (MANTLE_DENSITY - 1025.0) / MANTLE_DENSITY
internal const val COMPENSATION_KM = 3.7
internal const val PLANET_WIDTH_KM = 40_000.0
internal const val PLANET_HEIGHT_KM = 20_000.0

internal fun materialKey(x: Int, y: Int): Long {
    var bits = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    bits = (bits xor (bits ushr 33)) * -49064778989728563L
    return bits xor (bits ushr 33)
}

internal fun longitudeKm(x: Double) = ((x + 20_000) % 40_000 + 40_000) % 40_000 - 20_000

internal class CrustCell(val x: Int, val y: Int, var area: Double, var continent: Double,
    var basalt: Double, var sediment: Double, var age: Double, var relief: Double = 0.0) {
    var support = 0.0
    var removeArea = 0.0
    var compressArea = 0.0
    var dc = 0.0
    var db = 0.0
    var ds = 0.0
    fun mass() = continent * CONTINENT_DENSITY + basalt * BASALT_DENSITY + sediment * SEDIMENT_DENSITY
    fun prepare() {
        val ocean = basalt / max(1e-12, continent + basalt)
        support = (continent * (1 - CONTINENT_DENSITY / MANTLE_DENSITY) +
            basalt * (1 - BASALT_DENSITY / MANTLE_DENSITY) + sediment * (1 - SEDIMENT_DENSITY / MANTLE_DENSITY)) +
            area * (relief + ocean * 1.7 * exp(-age / 60_000_000))
        removeArea = 0.0; compressArea = 0.0; dc = 0.0; db = 0.0; ds = 0.0
    }
}

internal class MaterialPlate(val id: Int, var x: Double, var y: Double, var vx: Double, var vy: Double,
    var spin: Double = 0.0, val referenceX: Double = x, val referenceY: Double = y) {
    val cells = LinkedHashMap<Long, CrustCell>()
    var angle = 0.0
    var previousX = x
    var previousY = y
    var previousAngle = 0.0
    var area = 0.0
    var impulseX = 0.0
    var impulseY = 0.0
    var impulseSpin = 0.0
    var extension = 0.0
    var stressXX = 0.0
    var stressXY = 0.0
    var stressYY = 0.0
    var lastSplit = 0.0
    var active = true
    var cosAngle = 1.0
    var sinAngle = 0.0
    var minX = 0
    var maxX = 0
    var minY = 0
    var maxY = 0

    fun prepare() {
        area = 0.0
        minX = Int.MAX_VALUE; minY = Int.MAX_VALUE
        maxX = Int.MIN_VALUE; maxY = Int.MIN_VALUE
        for (cell in cells.values) {
            area += cell.area
            minX = min(minX, cell.x); maxX = max(maxX, cell.x)
            minY = min(minY, cell.y); maxY = max(maxY, cell.y)
            cell.prepare()
        }
        cosAngle = cos(angle); sinAngle = sin(angle)
    }
}

/** Compact global-index lookup shared by a plate's projected fields. Empty ocean/other plates cost no field storage. */
internal class RasterIndex(count: Int) {
    val indexes = IntArray(count) { -1 }
    var globals = IntArray(256)
    var size = 0
    val fields = ArrayList<RasterField>()
    fun add(global: Int): Int {
        if (size == globals.size) {
            globals = globals.copyOf(globals.size * 2)
            for (field in fields) field.values = field.values.copyOf(globals.size)
        }
        val index = size++
        globals[index] = global; indexes[global] = index
        return index
    }
    fun clear() {
        for (field in fields) field.values.fill(0.0, 0, size)
        for (i in 0 until size) indexes[globals[i]] = -1
        size = 0
    }
}

internal class RasterField(private val grid: RasterIndex) {
    var values = DoubleArray(grid.globals.size)
    init { grid.fields += this }
    operator fun get(global: Int): Double {
        val index = grid.indexes[global]
        return if (index >= 0) values[index] else 0.0
    }
    operator fun set(global: Int, value: Double) {
        val index = grid.indexes[global]
        if (index >= 0) values[index] = value
        else if (value != 0.0) {
            val next = grid.add(global)
            values[next] = value
        }
    }
}

/** Temporary world-space projection. Material never moves from these fields back into a plate. */
internal class PlateRaster(count: Int) {
    private val grid = RasterIndex(count)
    val area = RasterField(grid)
    val continent = RasterField(grid)
    val basalt = RasterField(grid)
    val sediment = RasterField(grid)
    val support = RasterField(grid)
    val age = RasterField(grid)
    val remove = RasterField(grid)
    val compress = RasterField(grid)
    val dc = RasterField(grid)
    val db = RasterField(grid)
    val ds = RasterField(grid)
    fun clear() = grid.clear()
    fun buoyant(i: Int) = continent[i] > area[i] * 12 || continent[i] + basalt[i] > area[i] * 22
    fun add(i: Int, cell: CrustCell?, weight: Double) {
        if (cell == null || weight <= 0) return
        area[i] += weight * cell.area; continent[i] += weight * cell.continent
        basalt[i] += weight * cell.basalt; sediment[i] += weight * cell.sediment
        support[i] += weight * cell.support; age[i] += weight * cell.area * cell.age
    }
    fun distribute(i: Int, cell: CrustCell?, weight: Double) {
        if (cell == null || weight <= 0 || area[i] <= 1e-12) return
        val fraction = weight * cell.area / area[i]
        cell.removeArea += remove[i] * fraction
        cell.compressArea += compress[i] * fraction
        cell.dc += if (dc[i] >= 0) dc[i] * fraction else dc[i] * weight * cell.continent / max(1e-12, continent[i])
        cell.db += if (db[i] >= 0) db[i] * fraction else db[i] * weight * cell.basalt / max(1e-12, basalt[i])
        cell.ds += if (ds[i] >= 0) ds[i] * fraction else ds[i] * weight * cell.sediment / max(1e-12, sediment[i])
    }
}

enum class PlateBoundary(val label: String) {
    NONE("Interior"), COLLISION("Collision"), SUBDUCTION("Subduction"), SPREADING("Spreading"), RIFT("Rift"), TRANSFORM("Transform")
}
