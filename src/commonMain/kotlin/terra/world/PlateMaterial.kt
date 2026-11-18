package terra.world

import kotlin.math.*

/** Densities in kg/m³; columns in km, areas in terrain-cell units. */
internal const val CONTINENT_DENSITY = 2800.0
internal const val BASALT_DENSITY = 3000.0
internal const val SEDIMENT_DENSITY = 2400.0
internal const val MANTLE_DENSITY = 3300.0
internal const val WATER_LOADING = (MANTLE_DENSITY - 1025.0) / MANTLE_DENSITY
internal const val COMPENSATION_KM = 3.7
internal const val ROOT_THICKNESS_KM = 70.0
internal const val PLANET_WIDTH_KM = 40_000.0
internal const val PLANET_HEIGHT_KM = 20_000.0

internal fun longitudeKm(x: Double) = periodicDelta(x, PLANET_WIDTH_KM)
internal fun latitudeKm(y: Double) = periodicDelta(y, PLANET_HEIGHT_KM)

internal class CrustCell(var x: Double, var y: Double, var area: Double, var continent: Double,
    var basalt: Double, var sediment: Double, var age: Double, var relief: Double = 0.0, var weakness: Double = 0.5) {
    var sedimentContinent = 0.0
    var dsc = 0.0
    var support = 0.0
    var removeArea = 0.0
    var compressArea = 0.0
    var compressible = 0.0
    var dc = 0.0
    var db = 0.0
    var ds = 0.0
    fun mass() = continent * CONTINENT_DENSITY + basalt * BASALT_DENSITY + sediment * SEDIMENT_DENSITY
    fun prepare() {
        compressible = max(0.0, area - (continent + basalt + sediment) / ROOT_THICKNESS_KM)
        val ocean = basalt / max(1e-12, continent + basalt)
        support = (continent * (1 - CONTINENT_DENSITY / MANTLE_DENSITY) +
            basalt * (1 - BASALT_DENSITY / MANTLE_DENSITY) + sediment * (1 - SEDIMENT_DENSITY / MANTLE_DENSITY)) +
            area * (relief + ocean * 1.7 * exp(-age / 60_000_000))
        removeArea = 0.0; compressArea = 0.0; dc = 0.0; db = 0.0; ds = 0.0; dsc = 0.0
    }
}

internal class MaterialPlate(val id: Int, var x: Double, var y: Double, var vx: Double, var vy: Double,
    var spin: Double = 0.0) {
    val cells = ArrayList<CrustCell>()
    var desiredX = vx
    var desiredY = vy
    var desiredSpin = spin
    var identity = id
    var bornAt = 0.0
    var riftYears = 0.0
    var extensionAdded = 0.0
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
    var minX = 0.0
    var maxX = 0.0
    var minY = 0.0
    var maxY = 0.0

    fun prepare() {
        area = 0.0
        var mx = 0.0; var my = 0.0; var moment = 0.0
        minX = Double.POSITIVE_INFINITY; minY = Double.POSITIVE_INFINITY
        maxX = Double.NEGATIVE_INFINITY; maxY = Double.NEGATIVE_INFINITY
        for (cell in cells) {
            area += cell.area
            val x = cell.x
            val y = cell.y
            mx += cell.area * x; my += cell.area * y; moment += cell.area * (x * x + y * y)
            minX = min(minX, cell.x); maxX = max(maxX, cell.x)
            minY = min(minY, cell.y); maxY = max(maxY, cell.y)
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
    val compressible = RasterField(grid)
    val dc = RasterField(grid)
    val db = RasterField(grid)
    val ds = RasterField(grid)
    val dsc = RasterField(grid)
    // Erosion deposits must not cancel withdrawals before component-weighted gathering.
    val deposit = RasterField(grid)
    val depositContinent = RasterField(grid)
    fun clear() = grid.clear()
    fun forEachIndex(action: (Int) -> Unit) {
        for (j in 0 until grid.size) action(grid.globals[j])
    }
    fun buoyant(i: Int) = continent[i] > area[i] * 12 || continent[i] + basalt[i] > area[i] * 22
    fun add(i: Int, cell: CrustCell?, weight: Double) {
        if (cell == null || weight <= 0) return
        val index = grid.indexes[i]
        val j = if (index >= 0) index else grid.add(i)
        val w = weight
        compressible.values[j] += w * cell.compressible
        area.values[j] += w * cell.area; continent.values[j] += w * cell.continent
        basalt.values[j] += w * cell.basalt; sediment.values[j] += w * cell.sediment
        sedimentContinent.values[j] += w * cell.sedimentContinent
        weakness.values[j] += w * cell.area * cell.weakness
        support.values[j] += w * cell.support; age.values[j] += w * cell.area * cell.age
    }
    /** Update only changed parcel contributions between rigid-motion projections. */
    fun adjust(i: Int, weight: Double, delta: DoubleArray, years: Double, weak: Double) {
        val index = grid.indexes[i]
        val j = if (index >= 0) index else grid.add(i)
        area.values[j] = max(0.0, area.values[j] + weight * delta[0])
        continent.values[j] = max(0.0, continent.values[j] + weight * delta[1])
        basalt.values[j] = max(0.0, basalt.values[j] + weight * delta[2])
        sediment.values[j] = max(0.0, sediment.values[j] + weight * delta[3])
        sedimentContinent.values[j] = (sedimentContinent.values[j] + weight * delta[4]).coerceIn(0.0, sediment.values[j])
        support.values[j] += weight * delta[5]
        compressible.values[j] = max(0.0, compressible.values[j] + weight * delta[6])
        age.values[j] = max(0.0, age.values[j] + weight * delta[0] * years)
        weakness.values[j] = max(0.0, weakness.values[j] + weight * delta[0] * weak)
    }

    fun clearEvents() {
        for (field in listOf(remove, compress, dc, db, ds, dsc, deposit, depositContinent))
            field.values.fill(0.0, 0, grid.size)
    }

    fun distribute(i: Int, cell: CrustCell?, weight: Double) {
        if (cell == null || weight <= 0) return
        val j = grid.indexes[i]
        if (j < 0 || area.values[j] <= 0.0) return
        if (remove.values[j] == 0.0 && compress.values[j] == 0.0 && dc.values[j] == 0.0 && db.values[j] == 0.0 &&
            ds.values[j] == 0.0 && dsc.values[j] == 0.0 && deposit.values[j] == 0.0 && depositContinent.values[j] == 0.0) return
        val w = weight
        val fraction = w * cell.area / area.values[j]
        cell.removeArea += remove.values[j] * fraction
        if (compressible.values[j] > 0) cell.compressArea += compress.values[j] * w * cell.compressible / compressible.values[j]
        cell.dc += if (dc.values[j] >= 0) dc.values[j] * fraction else dc.values[j] * w * cell.continent / (if (continent.values[j] > 0) continent.values[j] else 1.0)
        cell.db += if (db.values[j] >= 0) db.values[j] * fraction else db.values[j] * w * cell.basalt / (if (basalt.values[j] > 0) basalt.values[j] else 1.0)
        cell.ds += if (ds.values[j] >= 0) ds.values[j] * fraction else ds.values[j] * w * cell.sediment / (if (sediment.values[j] > 0) sediment.values[j] else 1.0)
        cell.dsc += if (dsc.values[j] >= 0) dsc.values[j] * fraction else dsc.values[j] * w * cell.sedimentContinent / (if (sedimentContinent.values[j] > 0) sedimentContinent.values[j] else 1.0)
        cell.ds += deposit.values[j] * fraction
        cell.dsc += depositContinent.values[j] * fraction
    }
}

enum class PlateBoundary(val label: String) {
    NONE("Interior"), COLLISION("Collision"), SUBDUCTION("Subduction"), SPREADING("Spreading"), RIFT("Rift"), TRANSFORM("Transform")
}
