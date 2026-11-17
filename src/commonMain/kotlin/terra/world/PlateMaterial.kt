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

internal fun longitudeKm(x: Double) = periodicDelta(x, PLANET_WIDTH_KM)
internal fun latitudeKm(y: Double) = periodicDelta(y, PLANET_HEIGHT_KM)

internal class CrustCell(val x: Int, val y: Int, var area: Double, var continent: Double,
    var basalt: Double, var sediment: Double, var age: Double, var relief: Double = 0.0, var weakness: Double = 0.5) {
    var projectionRevision = -1L
    var projectionWeight = 1.0
    var sedimentContinent = 0.0
    var dsc = 0.0
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
        removeArea = 0.0; compressArea = 0.0; dc = 0.0; db = 0.0; ds = 0.0; dsc = 0.0
    }
}

internal class MaterialPlate(val id: Int, var x: Double, var y: Double, var vx: Double, var vy: Double,
    var spin: Double = 0.0, val referenceX: Double = x, val referenceY: Double = y) {
    val cells = LinkedHashMap<Long, CrustCell>()
    var projectionRevision = 0L
    var projectionX = Double.NaN
    var projectionY = Double.NaN
    var projectionAngle = Double.NaN
    var desiredX = vx
    var desiredY = vy
    var desiredSpin = spin
    var bornAt = 0.0
    var separationUntil = 0.0
    var separationX = 0.0
    var separationY = 0.0
    var angle = 0.0
    var previousX = x
    var previousY = y
    var previousAngle = 0.0
    var area = 0.0
    var centerX = 0.0
    var centerY = 0.0
    var radius2 = 0.0
    var extension = 0.0
    var stressXX = 0.0
    var stressXY = 0.0
    var stressYY = 0.0
    var active = true
    var cosAngle = 1.0
    var sinAngle = 0.0
    var minX = 0
    var maxX = 0
    var minY = 0
    var maxY = 0

    fun prepare(width: Int, height: Int) {
        area = 0.0
        var mx = 0.0; var my = 0.0; var moment = 0.0
        minX = Int.MAX_VALUE; minY = Int.MAX_VALUE
        maxX = Int.MIN_VALUE; maxY = Int.MIN_VALUE
        for (cell in cells.values) {
            area += cell.area
            val x = (cell.x + 0.5) * PLANET_WIDTH_KM / width - referenceX
            val y = (cell.y + 0.5) * PLANET_HEIGHT_KM / height - referenceY
            mx += cell.area * x; my += cell.area * y; moment += cell.area * (x * x + y * y)
            minX = min(minX, cell.x); maxX = max(maxX, cell.x)
            minY = min(minY, cell.y); maxY = max(maxY, cell.y)
            cell.prepare()
        }
        cosAngle = cos(angle); sinAngle = sin(angle)
        mx /= max(1e-12, area); my /= max(1e-12, area)
        centerX = cosAngle * mx - sinAngle * my
        centerY = sinAngle * mx + cosAngle * my
        radius2 = max(1.0, moment / max(1e-12, area) - mx * mx - my * my)
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
    val sedimentContinent = RasterField(grid)
    val weakness = RasterField(grid)
    val support = RasterField(grid)
    val age = RasterField(grid)
    val remove = RasterField(grid)
    val compress = RasterField(grid)
    val dc = RasterField(grid)
    val db = RasterField(grid)
    val ds = RasterField(grid)
    val dsc = RasterField(grid)
    fun clear() = grid.clear()
    fun forEachIndex(action: (Int) -> Unit) {
        for (j in 0 until grid.size) action(grid.globals[j])
    }
    fun buoyant(i: Int) = continent[i] > area[i] * 12 || continent[i] + basalt[i] > area[i] * 22
    fun add(i: Int, cell: CrustCell?, weight: Double) {
        if (cell == null || weight <= 0) return
        val w = weight / cell.projectionWeight
        area[i] += w * cell.area; continent[i] += w * cell.continent
        basalt[i] += w * cell.basalt; sediment[i] += w * cell.sediment
        sedimentContinent[i] += w * cell.sedimentContinent
        weakness[i] += w * cell.area * cell.weakness
        support[i] += w * cell.support; age[i] += w * cell.area * cell.age
    }
    fun distribute(i: Int, cell: CrustCell?, weight: Double) {
        if (cell == null || weight <= 0 || area[i] <= 1e-12) return
        val w = weight / cell.projectionWeight
        val fraction = w * cell.area / area[i]
        cell.removeArea += remove[i] * fraction
        cell.compressArea += compress[i] * fraction
        cell.dc += if (dc[i] >= 0) dc[i] * fraction else dc[i] * w * cell.continent / max(1e-12, continent[i])
        cell.db += if (db[i] >= 0) db[i] * fraction else db[i] * w * cell.basalt / max(1e-12, basalt[i])
        cell.ds += if (ds[i] >= 0) ds[i] * fraction else ds[i] * w * cell.sediment / max(1e-12, sediment[i])
        cell.dsc += if (dsc[i] >= 0) dsc[i] * fraction else dsc[i] * w * cell.sedimentContinent / max(1e-12, sedimentContinent[i])
    }
}

enum class PlateBoundary(val label: String) {
    NONE("Interior"), COLLISION("Collision"), SUBDUCTION("Subduction"), SPREADING("Spreading"), RIFT("Rift"), TRANSFORM("Transform")
}
