package terra.world

import kotlin.math.*
import kotlin.random.Random

/** Shared initial plates: the generated mountain belts and live geology start from the same crust. */
internal data class CrustPlate(var x: Double, var y: Double, val continental: Double, val u: Double, var v: Double)

internal fun crustPlates(random: Random, settings: TerrainGenerationSettings): List<CrustPlate> {
    val columns = (283 / settings.continentScale * 1.8).roundToInt().coerceIn(2, 16)
    val rows = (212 / settings.continentScale * 1.2).roundToInt().coerceIn(2, 10)
    return List(columns * rows) { index ->
        val angle = random.nextDouble() * 2 * PI
        CrustPlate((index % columns + 0.15 + random.nextDouble() * 0.7) * 283 / columns,
            (index / columns + 0.15 + random.nextDouble() * 0.7) * 212 / rows,
            if (random.nextDouble() < 0.45) 1.0 else 0.0, cos(angle), sin(angle))
    }
}

/** Persistent plate-local crust columns. The global raster is a disposable projection, not storage. */
internal class PlateTectonics(private val world: World) {
    private val width = world.width
    private val height = world.height
    private val count = width * height
    private val dx = PLANET_WIDTH_KM / width
    private val dy = PLANET_HEIGHT_KM / height
    private val initial = crustPlates(Random(world.seed xor 0x36ac71), world.settings)
    private val plates = initial.mapIndexed { i, p ->
        val speed = Random(world.seed + i * 997).nextDouble(0.03, 0.09) / 1000
        MaterialPlate(i, p.x / 283 * PLANET_WIDTH_KM, p.y / 212 * PLANET_HEIGHT_KM,
            p.u * speed, p.v * speed, (Random(world.seed + i * 719).nextDouble() - 0.5) * speed / 4000)
    }.toMutableList()
    private val rasters = plates.map { PlateRaster(count) }.toMutableList()
    private val owner = IntArray(count)
    private val totalArea = DoubleArray(count)
    private val elevation = DoubleArray(count)
    private val trench = DoubleArray(count)
    private val sourceX = DoubleArray(count)
    private val sourceY = DoubleArray(count)
    private var boundaries = ByteArray(count)
    private val contacts = HashMap<Long, Double>()
    private var geologicalTime = 0.0
    internal var mantleAddedMass = 0.0
        private set
    internal var mantleRecycledMass = 0.0
        private set
    internal var oceanCreatedArea = 0.0
        private set
    internal var subductedArea = 0.0
        private set
    internal var shortenedArea = 0.0
        private set
    internal var riftArea = 0.0
        private set
    internal var mergers = 0
        private set
    internal var splits = 0
        private set
    internal val rockMass get() = plates.sumOf { p -> p.cells.values.sumOf { it.mass() } }
    internal val continentalVolume get() = plates.sumOf { p -> p.cells.values.sumOf { it.continent } }
    internal val activePlateCount get() = plates.count { it.active }

    init {
        val noise = TerrainNoise(world.settings)
        val salt = Random(world.seed).nextInt()
        val warp = world.settings.continentScale * 0.22
        val scale = world.settings.continentScale * 0.65
        for (i in 0 until count) {
            val x = (i % width + 0.5) * 283 / width
            val y = (i / width + 0.5) * 212 / height
            val px = x + warp * noise.periodic(x, y, salt + 503, 2, scale)
            val py = y + warp * noise.periodic(x, y, salt + 719, 2, scale)
            val plate = plates[initial.indices.minBy { p ->
                val rx = ((px - initial[p].x + 424.5) % 283) - 141.5
                rx * rx + (py - initial[p].y).pow(2)
            }]
            val alt = (world[i % width, i / width].elevation - world.seaLevel) * 10
            val airHeight = if (alt < 0) alt * WATER_LOADING else alt
            val continental = alt >= -1.2
            val c = if (continental) (airHeight + COMPENSATION_KM) / (1 - CONTINENT_DENSITY / MANTLE_DENSITY) else 0.0
            val b = if (continental) 0.0 else 7.0
            val support = c * (1 - CONTINENT_DENSITY / MANTLE_DENSITY) + b * (1 - BASALT_DENSITY / MANTLE_DENSITY) +
                if (continental) 0.0 else 1.7 * exp(-40_000_000.0 / 60_000_000)
            val ix = floor((plate.referenceX + longitudeKm((i % width + 0.5) * dx - plate.x)) / dx).toInt()
            val iy = i / width
            plate.cells[materialKey(ix, iy)] = CrustCell(ix, iy, 1.0, c, b, 0.0, 40_000_000.0,
                airHeight + COMPENSATION_KM - support)
        }
        rasterize()
        classifyBoundaries()
        publishOwners()
    }

    fun advance(years: Double, rainMmPerYear: DoubleArray, drainage: terra.hydrology.SurfaceWaterSnapshot? = null) {
        val dt = years * SPEEDUP
        geologicalTime += dt
        boundaries = ByteArray(count)
        trench.fill(0.0)
        movePlates(dt)
        rasterize()
        collide(dt)
        applyEvents()
        spreadRoots()
        rasterize()
        fillGaps()
        rasterize()
        erode(rainMmPerYear, dt, drainage)
        applyEvents()
        evolvePlates()
        rasterize()
        classifyBoundaries()
        publishOwners()
        for (i in 0 until count) {
            var support = 0.0
            for (p in plates.indices) if (plates[p].active) support += rasters[p].support[i]
            val air = if (totalArea[i] > 1e-10) support / totalArea[i] - COMPENSATION_KM else -3.0
            val h = if (air < 0) air / WATER_LOADING else air
            elevation[i] = world.seaLevel + (h + if (h < 0) trench[i] else 0.0) / 10
            val p = owner[i]
            if (p >= 0) {
                val plate = plates[p]
                val rx = longitudeKm((i % width + 0.5) * dx - plate.x)
                val ry = (i / width + 0.5) * dy - plate.y
                val lx = plate.cosAngle * rx + plate.sinAngle * ry
                val ly = -plate.sinAngle * rx + plate.cosAngle * ry
                val ca = cos(plate.previousAngle); val sa = sin(plate.previousAngle)
                sourceX[i] = (plate.previousX + ca * lx - sa * ly) / dx - 0.5
                sourceY[i] = (plate.previousY + sa * lx + ca * ly) / dy - 0.5
            } else { sourceX[i] = (i % width).toDouble(); sourceY[i] = (i / width).toDouble() }
        }
        world.applyGeography(elevation, sourceX, sourceY)
    }

    private fun movePlates(dt: Double) {
        for (p in plates) if (p.active) {
            p.previousX = p.x; p.previousY = p.y; p.previousAngle = p.angle
            p.impulseX = 0.0; p.impulseY = 0.0; p.impulseSpin = 0.0
            p.prepare()
            val nextAngle = p.angle + p.spin * dt
            val ca = cos(nextAngle); val sa = sin(nextAngle)
            var low = Double.POSITIVE_INFINITY; var high = Double.NEGATIVE_INFINITY
            for (x in listOf(p.minX, p.maxX)) for (y in listOf(p.minY, p.maxY)) {
                val off = sa * ((x + 0.5) * dx - p.referenceX) + ca * ((y + 0.5) * dy - p.referenceY)
                low = min(low, off); high = max(high, off)
            }
            // Impermeable polar edges: turn a plate before its material leaves the map.
            if (p.y + p.vy * dt + low < 0 || p.y + p.vy * dt + high > PLANET_HEIGHT_KM) {
                p.vy = -p.vy; p.spin = -p.spin
                low = Double.POSITIVE_INFINITY; high = Double.NEGATIVE_INFINITY
                for (x in listOf(p.minX, p.maxX)) for (y in listOf(p.minY, p.maxY)) {
                    val off = sin(p.angle) * ((x + 0.5) * dx - p.referenceX) + cos(p.angle) * ((y + 0.5) * dy - p.referenceY)
                    low = min(low, off); high = max(high, off)
                }
            } else p.angle = nextAngle
            p.x = ((p.x + p.vx * dt) % PLANET_WIDTH_KM + PLANET_WIDTH_KM) % PLANET_WIDTH_KM
            val lower = max(0.0, -low); val upper = min(PLANET_HEIGHT_KM, PLANET_HEIGHT_KM - high)
            p.y = if (lower <= upper) (p.y + p.vy * dt).coerceIn(lower, upper) else p.y
            for (cell in p.cells.values) cell.age += dt
        }
    }

    private inline fun visit(p: MaterialPlate, action: (Int, CrustCell?, CrustCell?, CrustCell?, CrustCell?, Double, Double, Double, Double) -> Unit) {
        if (p.cells.isEmpty()) return
        var left = Double.POSITIVE_INFINITY; var right = Double.NEGATIVE_INFINITY
        var top = Double.POSITIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
        for (ix in listOf(p.minX - 1, p.maxX + 1)) for (iy in listOf(p.minY - 1, p.maxY + 1)) {
            val lx = (ix + 0.5) * dx - p.referenceX; val ly = (iy + 0.5) * dy - p.referenceY
            val x = p.x + p.cosAngle * lx - p.sinAngle * ly
            val y = p.y + p.sinAngle * lx + p.cosAngle * ly
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
        }
        val startX = floor(max(left / dx - 0.5, p.x / dx - width / 2.0)).toInt()
        val endX = min(ceil(right / dx - 0.5).toInt(), startX + width - 1)
        for (y in max(0, floor(top / dy - 0.5).toInt())..min(height - 1, ceil(bottom / dy - 0.5).toInt())) {
            for (x in startX..endX) {
                val rx = (x + 0.5) * dx - p.x; val ry = (y + 0.5) * dy - p.y
                val gx = (p.referenceX + p.cosAngle * rx + p.sinAngle * ry) / dx - 0.5
                val gy = (p.referenceY - p.sinAngle * rx + p.cosAngle * ry) / dy - 0.5
                val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
                val a = p.cells[materialKey(ix, iy)]; val b = p.cells[materialKey(ix + 1, iy)]
                val c = p.cells[materialKey(ix, iy + 1)]; val d = p.cells[materialKey(ix + 1, iy + 1)]
                if (a == null && b == null && c == null && d == null) continue
                val tx = gx - ix; val ty = gy - iy
                action(y * width + ((x % width) + width) % width, a, b, c, d,
                    (1 - tx) * (1 - ty), tx * (1 - ty), (1 - tx) * ty, tx * ty)
            }
        }
    }

    private fun rasterize() {
        owner.fill(-1); totalArea.fill(0.0)
        for (p in plates) {
            val r = rasters[p.id]
            r.clear()
            if (!p.active) continue
            p.prepare()
            visit(p) { i, a, b, c, d, wa, wb, wc, wd ->
                r.add(i, a, wa); r.add(i, b, wb); r.add(i, c, wc); r.add(i, d, wd)
                totalArea[i] += r.area[i]
                if (r.area[i] > 1e-12 && (owner[i] < 0 || r.area[i] > rasters[owner[i]].area[i])) owner[i] = p.id
            }
        }
    }

    private fun applyEvents() {
        for (p in plates) if (p.active) {
            val r = rasters[p.id]
            visit(p) { i, a, b, c, d, wa, wb, wc, wd ->
                r.distribute(i, a, wa); r.distribute(i, b, wb); r.distribute(i, c, wc); r.distribute(i, d, wd)
            }
            val iterator = p.cells.values.iterator()
            while (iterator.hasNext()) {
                val cell = iterator.next()
                val removed = min(cell.area * 0.95, cell.removeArea)
                val fraction = removed / max(1e-12, cell.area)
                mantleRecycledMass += cell.basalt * fraction * BASALT_DENSITY
                cell.continent *= 1 - fraction; cell.basalt *= 1 - fraction; cell.sediment *= 1 - fraction
                cell.area = max(1e-12, cell.area - removed - min(cell.area * 0.5, cell.compressArea))
                cell.continent = max(0.0, cell.continent + cell.dc)
                cell.basalt = max(0.0, cell.basalt + cell.db)
                cell.sediment = max(0.0, cell.sediment + cell.ds)
                if (cell.area < 1e-7 && cell.continent < 1e-10 && cell.sediment < 1e-10) {
                    mantleRecycledMass += cell.mass(); iterator.remove()
                } else cell.prepare()
            }
            if (p.cells.isEmpty()) p.active = false
        }
    }

    private fun normal(p: Int, q: Int, i: Int): Pair<Double, Double> {
        val x = i % width; val y = i / width
        val west = y * width + (x + width - 1) % width; val east = y * width + (x + 1) % width
        val north = max(0, y - 1) * width + x; val south = min(height - 1, y + 1) * width + x
        val a = rasters[p].area; val b = rasters[q].area
        var nx = (a[west] - a[east] - b[west] + b[east]) / dx
        var ny = (a[north] - a[south] - b[north] + b[south]) / dy
        if (hypot(nx, ny) < 1e-10) {
            nx = longitudeKm(plates[q].x - plates[p].x); ny = plates[q].y - plates[p].y
        }
        val length = max(1e-12, hypot(nx, ny))
        return nx / length to ny / length
    }

    private fun closing(p: MaterialPlate, q: MaterialPlate, i: Int, nx: Double, ny: Double): Double {
        val x = (i % width + 0.5) * dx; val y = (i / width + 0.5) * dy
        val ux = p.vx - p.spin * (y - p.y) - q.vx + q.spin * (y - q.y)
        val uy = p.vy + p.spin * longitudeKm(x - p.x) - q.vy - q.spin * longitudeKm(x - q.x)
        return ux * nx + uy * ny
    }

    /** Spread an exact amount among supported columns, using physical distances. */
    private fun patch(p: Int, field: RasterField, i: Int, offsetX: Double, offsetY: Double, amount: Double, radius: Double) {
        if (amount == 0.0) return
        val cx = (i % width + 0.5) * dx + offsetX; val cy = (i / width + 0.5) * dy + offsetY
        val area = rasters[p].area
        val left = floor((cx - 2 * radius) / dx).toInt(); val right = ceil((cx + 2 * radius) / dx).toInt()
        val top = max(0, floor((cy - 2 * radius) / dy).toInt()); val bottom = min(height - 1, ceil((cy + 2 * radius) / dy).toInt())
        var sum = 0.0
        for (y in top..bottom) for (x in left..right) {
            val j = y * width + ((x % width) + width) % width
            val r2 = (((x + 0.5) * dx - cx).pow(2) + ((y + 0.5) * dy - cy).pow(2)) / (radius * radius)
            sum += area[j] * exp(-0.5 * r2)
        }
        if (sum < 1e-12) { field[i] += amount; return }
        for (y in top..bottom) for (x in left..right) {
            val j = y * width + ((x % width) + width) % width
            val r2 = (((x + 0.5) * dx - cx).pow(2) + ((y + 0.5) * dy - cy).pow(2)) / (radius * radius)
            field[j] += amount * area[j] * exp(-0.5 * r2) / sum
        }
    }

    private fun collide(dt: Double) {
        for (i in 0 until count) {
            if (totalArea[i] <= 1.000001) continue
            val p = owner[i]
            if (p < 0) continue
            var q = -1
            for (other in plates) if (other.active && other.id != p &&
                rasters[other.id].area[i] > if (q < 0) 1e-8 else rasters[q].area[i]) q = other.id
            if (q < 0) continue
            val (nx, ny) = normal(p, q, i)
            val speed = closing(plates[p], plates[q], i, nx, ny)
            if (speed <= 0) { boundaries[i] = PlateBoundary.TRANSFORM.ordinal.toByte(); continue }
            val amount = min(totalArea[i] - 1, min(0.25, 2 * speed * dt / min(dx, dy)))
            val a = rasters[p]; val b = rasters[q]
            if (a.buoyant(i) && b.buoyant(i)) {
                val share = a.area[i] / (a.area[i] + b.area[i])
                patch(p, a.compress, i, 0.0, 0.0, amount * share, 300.0)
                patch(q, b.compress, i, 0.0, 0.0, amount * (1 - share), 300.0)
                shortenedArea += amount
                boundaries[i] = PlateBoundary.COLLISION.ordinal.toByte()
                val impulse = speed * amount
                plates[p].impulseX -= impulse * nx; plates[p].impulseY -= impulse * ny
                plates[q].impulseX += impulse * nx; plates[q].impulseY += impulse * ny
                val x = (i % width + 0.5) * dx; val y = (i / width + 0.5) * dy
                plates[p].impulseSpin -= impulse * (longitudeKm(x - plates[p].x) * ny - (y - plates[p].y) * nx)
                plates[q].impulseSpin += impulse * (longitudeKm(x - plates[q].x) * ny - (y - plates[q].y) * nx)
                val pair = min(p, q).toLong() shl 32 or max(p, q).toLong()
                contacts[pair] = (contacts[pair] ?: 0.0) + amount
            } else {
                val sub = when {
                    a.buoyant(i) -> q
                    b.buoyant(i) -> p
                    abs(a.age[i] / a.area[i] - b.age[i] / b.area[i]) > 1000 ->
                        if (a.age[i] / a.area[i] > b.age[i] / b.area[i]) p else q
                    else -> max(p, q)
                }
                val over = if (sub == p) q else p
                val r = rasters[sub]; val upper = rasters[over]
                val removed = min(amount, r.area[i] * 0.5)
                val fraction = removed / r.area[i]
                r.remove[i] += removed
                subductedArea += removed
                val direction = if (sub == p) 1 else -1
                // Scraped-off buoyant material stays in the margin, not in the mantle sink.
                patch(over, upper.dc, i, nx * direction * 60, ny * direction * 60, r.continent[i] * fraction, 120.0)
                patch(over, upper.ds, i, nx * direction * 60, ny * direction * 60, r.sediment[i] * fraction, 120.0)
                val basaltMass = r.basalt[i] * fraction * BASALT_DENSITY
                val newContinent = basaltMass * 0.15 / CONTINENT_DENSITY
                val newBasalt = basaltMass * 0.25 / BASALT_DENSITY
                patch(over, upper.dc, i, nx * direction * 220, ny * direction * 220, newContinent, 150.0)
                patch(over, upper.db, i, nx * direction * 220, ny * direction * 220, newBasalt, 150.0)
                mantleAddedMass += basaltMass * 0.40
                boundaries[i] = PlateBoundary.SUBDUCTION.ordinal.toByte()
                val tx = (i % width + 0.5) * dx - nx * direction * 100
                val ty = (i / width + 0.5) * dy - ny * direction * 100
                val radius = max(80.0, min(dx, dy) * 0.55)
                for (oy in -2..2) for (ox in -2..2) {
                    val y = floor(ty / dy).toInt() + oy
                    if (y !in 0 until height) continue
                    val x = floor(tx / dx).toInt() + ox
                    val distance = hypot((x + 0.5) * dx - tx, (y + 0.5) * dy - ty)
                    val j = y * width + ((x % width) + width) % width
                    if (!rasters[over].buoyant(j)) trench[j] = min(trench[j], -3 * exp(-0.5 * (distance / radius).pow(2)))
                }
            }
        }
    }

    private data class Birth(val plate: Int, val i: Int, val area: Double, val continent: Double, val basalt: Double)

    private fun fillGaps() {
        // A finite planet: new area replaces consumed/shortened area, not interpolation undershoots.
        val budget = max(0.0, count - plates.filter { it.active }.sumOf { it.area })
        if (budget < 1e-8) return
        val gapSum = totalArea.sumOf { max(0.0, 1 - it) }
        if (gapSum < 1e-10) return
        val fraction = min(1.0, budget / gapSum)
        val births = ArrayList<Birth>()
        for (i in 0 until count) {
            val area = max(0.0, 1 - totalArea[i]) * fraction
            if (area < 1e-8) continue
            var p = owner[i]
            var q = -1
            var pi = i; var qi = i
            val x = i % width; val y = i / width
            for (oy in -1..1) for (ox in -1..1) {
                if (y + oy !in 0 until height) continue
                val j = (y + oy) * width + (x + ox + width) % width
                val id = owner[j]
                if (id < 0 || rasters[id].area[j] < 0.05) continue
                if (p < 0 || rasters[p].area[pi] < 0.05) { p = id; pi = j }
                else if (id != p && (q < 0 || rasters[id].area[j] > rasters[q].area[qi])) { q = id; qi = j }
            }
            if (p < 0) p = plates.filter { it.active }.minBy {
                longitudeKm((x + 0.5) * dx - it.x).pow(2) + ((y + 0.5) * dy - it.y).pow(2)
            }.id
            val r = rasters[p]
            var continent = 0.0
            if (q >= 0) {
                val (nx, ny) = normal(p, q, i)
                val opening = -closing(plates[p], plates[q], i, nx, ny)
                if (opening > 0) for (id in listOf(p, q)) {
                    val plate = plates[id]
                    val lx = plate.cosAngle * nx + plate.sinAngle * ny
                    val ly = -plate.sinAngle * nx + plate.cosAngle * ny
                    plate.extension += area * 0.5 / max(1.0, plate.area)
                    plate.stressXX += lx * lx * area; plate.stressXY += lx * ly * area; plate.stressYY += ly * ly * area
                }
                val thickness = min(r.continent[pi] / max(1e-12, r.area[pi]),
                    rasters[q].continent[qi] / max(1e-12, rasters[q].area[qi]))
                if (opening > 0 && r.buoyant(pi) && rasters[q].buoyant(qi) && thickness > 12) {
                    val requested = area * thickness * 0.8
                    val availableP = max(0.0, r.continent[pi] + min(0.0, r.dc[pi])) * 0.2
                    val availableQ = max(0.0, rasters[q].continent[qi] + min(0.0, rasters[q].dc[qi])) * 0.2
                    continent = min(requested, 2 * min(availableP, availableQ))
                    r.dc[pi] -= continent * 0.5; rasters[q].dc[qi] -= continent * 0.5
                    if (continent > 0) {
                        boundaries[i] = PlateBoundary.RIFT.ordinal.toByte()
                        riftArea += area
                    }
                }
            }
            val b = if (continent > 0) 0.0 else area * 7
            if (b > 0) {
                mantleAddedMass += b * BASALT_DENSITY; oceanCreatedArea += area
                if (q >= 0 && area > 0.001) boundaries[i] = PlateBoundary.SPREADING.ordinal.toByte()
            }
            val id = if (q >= 0 && (i and 1) == 0) q else p
            births += Birth(id, i, area, continent, b)
        }
        // Withdraw stretched continental rock before adding its new, thinner columns.
        applyEvents()
        for (birth in births) addWorld(plates[birth.plate], (birth.i % width + 0.5) * dx,
            (birth.i / width + 0.5) * dy, birth.area, birth.continent, birth.basalt, 0.0, 0.0, 0.0)
        // The caller reprojects once after births and resets all accumulators.
    }

    private fun addWorld(p: MaterialPlate, x: Double, y: Double, area: Double, c: Double, b: Double, s: Double, age: Double, relief: Double) {
        val rx = longitudeKm(x - p.x); val ry = y - p.y
        val gx = (p.referenceX + p.cosAngle * rx + p.sinAngle * ry) / dx - 0.5
        val gy = (p.referenceY - p.sinAngle * rx + p.cosAngle * ry) / dy - 0.5
        val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
        val tx = gx - ix; val ty = gy - iy
        for (oy in 0..1) for (ox in 0..1) {
            val w = (if (ox == 0) 1 - tx else tx) * (if (oy == 0) 1 - ty else ty)
            if (w < 1e-12) continue
            val cell = p.cells.getOrPut(materialKey(ix + ox, iy + oy)) { CrustCell(ix + ox, iy + oy, 0.0, 0.0, 0.0, 0.0, age) }
            cell.age = (cell.age * cell.area + age * area * w) / max(1e-12, cell.area + area * w)
            cell.relief = (cell.relief * cell.area + relief * area * w) / max(1e-12, cell.area + area * w)
            cell.area += area * w; cell.continent += c * w; cell.basalt += b * w; cell.sediment += s * w
        }
    }

    private fun spreadRoots() {
        for (p in plates) if (p.active) for (cell in p.cells.values) {
            var excess = cell.continent - 70 * cell.area
            if (excess <= 0) continue
            // Lateral root flow spreads an orogen instead of clipping height or deleting rock.
            for (radius in 1..3) for (oy in -radius..radius) for (ox in -radius..radius) {
                if (excess <= 1e-12 || max(abs(ox), abs(oy)) != radius) continue
                val next = p.cells[materialKey(cell.x + ox, cell.y + oy)] ?: continue
                val amount = min(excess, max(0.0, 70 * next.area - next.continent))
                cell.continent -= amount; next.continent += amount; excess -= amount
            }
        }
    }

    private fun erode(rain: DoubleArray, dt: Double, drainage: terra.hydrology.SurfaceWaterSnapshot?) {
        if (world.settings.erosionStrength == 0.0) return
        val surface = DoubleArray(count)
        for (i in 0 until count) {
            var support = 0.0
            for (p in plates) if (p.active) support += rasters[p.id].support[i]
            val air = if (totalArea[i] > 1e-10) support / totalArea[i] - COMPENSATION_KM else -3.0
            surface[i] = if (air < 0) air / WATER_LOADING else air
        }
        val downstream = IntArray(count) { -1 }
        val flux = DoubleArray(count)
        for (i in 0 until count) {
            val x = i % width; val y = i / width
            val routed = if (drainage != null && !drainage.isVirtualSpillLink(x, y)) drainage.downstreamTileIndex(x, y) else -1
            var next = if (routed >= 0 && surface[routed] < surface[i]) routed else i
            if (next == i) for (oy in -1..1) for (ox in -1..1) {
                if (y + oy !in 0 until height) continue
                val j = (y + oy) * width + (x + ox + width) % width
                if (surface[j] < surface[next]) next = j
            }
            if (next != i) downstream[i] = next
            if (surface[i] <= 0 || next == i || totalArea[i] < 1e-10) continue
            val distance = hypot(longitudeKm((next % width - x) * dx), (next / width - y) * dy)
            val drop = surface[i] - surface[next]
            val slope = drop / max(1.0, distance)
            val catchment = drainage?.upstreamLandTileCount(x, y)?.toDouble() ?: 1.0
            val rateMeters = (0.00002 + 0.01 * slope * sqrt(catchment)) *
                (rain[i] / 1000).coerceIn(0.0, 3.0) * world.settings.erosionStrength
            var volume = min(drop * 0.20, rateMeters * dt / 1000) * totalArea[i]
            val sediment = plates.sumOf { if (it.active) rasters[it.id].sediment[i] else 0.0 }
            val continent = plates.sumOf { if (it.active) rasters[it.id].continent[i] else 0.0 }
            val basalt = plates.sumOf { if (it.active) rasters[it.id].basalt[i] else 0.0 }
            val loose = min(volume, sediment)
            volume -= loose
            val rock = min(volume, continent + basalt)
            val takeC = if (continent + basalt > 0) rock * continent / (continent + basalt) else 0.0
            val takeB = rock - takeC
            for (p in plates) if (p.active) {
                val r = rasters[p.id]
                if (sediment > 0) r.ds[i] -= loose * r.sediment[i] / sediment
                if (continent > 0) r.dc[i] -= takeC * r.continent[i] / continent
                if (basalt > 0) r.db[i] -= takeB * r.basalt[i] / basalt
            }
            flux[i] += loose + (takeC * CONTINENT_DENSITY + takeB * BASALT_DENSITY) / SEDIMENT_DENSITY
        }
        // The graph can change during a geological tick. Sorting the new surface keeps transport
        // downhill and acyclic, including new basins, instead of following a stale spill link uphill.
        val order = surface.indices.sortedByDescending { surface[it] }
        for (i in order) {
            val amount = flux[i]
            if (amount <= 0 || totalArea[i] < 1e-12) continue
            val next = downstream[i]
            val slope = if (next >= 0) (surface[i] - surface[next]) / max(dx, dy) else 0.0
            val carried = if (next >= 0 && surface[i] > 0) amount * (0.15 + sqrt(max(0.0, slope) * 100)).coerceAtMost(0.90) else 0.0
            if (next >= 0) flux[next] += carried
            val deposited = amount - carried
            for (p in plates) if (p.active) rasters[p.id].ds[i] += deposited * rasters[p.id].area[i] / totalArea[i]
        }
    }

    private fun evolvePlates() {
        for (p in plates) if (p.active) {
            if (p.cells.isEmpty()) { p.active = false; continue }
            // Continental contact resists further penetration; oceanic subduction remains mobile.
            p.vx += (p.impulseX * 30 / max(1.0, p.area)).coerceIn(-0.00002, 0.00002)
            p.vy += (p.impulseY * 30 / max(1.0, p.area)).coerceIn(-0.00002, 0.00002)
            val radius2 = (((p.maxX - p.minX + 1) * dx).pow(2) + ((p.maxY - p.minY + 1) * dy).pow(2)) / 12
            p.spin += (p.impulseSpin * 30 / max(1.0, p.area * radius2)).coerceIn(-5e-9, 5e-9)
        }
        val welded = HashSet<Int>()
        val iterator = contacts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val a = (entry.key ushr 32).toInt(); val b = entry.key.toInt()
            if (!plates[a].active || !plates[b].active) { iterator.remove(); continue }
            if (a in welded || b in welded) continue
            val pa = plates[a]; val pb = plates[b]
            val relative = hypot(pa.vx - pb.vx, pa.vy - pb.vy)
            if (entry.value > 0.08 * min(pa.area, pb.area) && relative < 0.000025) {
                merge(pa, pb)
                welded += a; welded += b
                iterator.remove()
            }
        }
        val candidates = plates.filter { it.active && it.extension > 0.12 &&
            geologicalTime - it.lastSplit > 2_000_000 && it.area > 40 }
        for (p in candidates) if (activePlateCount < max(24, initial.size * 2)) split(p)
    }

    private fun merge(a: MaterialPlate, b: MaterialPlate) {
        val total = max(1e-12, a.area + b.area)
        a.vx = (a.vx * a.area + b.vx * b.area) / total
        a.vy = (a.vy * a.area + b.vy * b.area) / total
        a.spin = (a.spin * a.area + b.spin * b.area) / total
        for (cell in b.cells.values) {
            val lx = (cell.x + 0.5) * dx - b.referenceX; val ly = (cell.y + 0.5) * dy - b.referenceY
            val x = b.x + b.cosAngle * lx - b.sinAngle * ly; val y = b.y + b.sinAngle * lx + b.cosAngle * ly
            addWorld(a, x, y, cell.area, cell.continent, cell.basalt, cell.sediment, cell.age, cell.relief)
        }
        b.cells.clear(); b.active = false
        a.extension = 0.0; a.lastSplit = geologicalTime
        mergers++
    }

    private fun split(p: MaterialPlate) {
        val theta = 0.5 * atan2(2 * p.stressXY, p.stressXX - p.stressYY)
        val nx = cos(theta); val ny = sin(theta)
        var middle = 0.0
        for (cell in p.cells.values) middle += cell.area *
            (((cell.x + 0.5) * dx - p.referenceX) * nx + ((cell.y + 0.5) * dy - p.referenceY) * ny)
        middle /= max(1e-12, p.area)
        val part = p.cells.values.filter { ((it.x + 0.5) * dx - p.referenceX) * nx +
            ((it.y + 0.5) * dy - p.referenceY) * ny > middle }
        val area = part.sumOf { it.area }
        if (area < p.area * 0.10 || area > p.area * 0.90) { p.extension = 0.0; return }
        val vacant = plates.indexOfFirst { !it.active }
        val id = if (vacant >= 0) vacant else plates.size
        val child = MaterialPlate(id, p.x, p.y, p.vx, p.vy, p.spin, p.referenceX, p.referenceY)
        child.angle = p.angle; child.cosAngle = p.cosAngle; child.sinAngle = p.sinAngle
        child.previousX = p.previousX; child.previousY = p.previousY; child.previousAngle = p.previousAngle
        for (cell in part) { p.cells.remove(materialKey(cell.x, cell.y)); child.cells[materialKey(cell.x, cell.y)] = cell }
        val ux = p.cosAngle * nx - p.sinAngle * ny; val uy = p.sinAngle * nx + p.cosAngle * ny
        val pull = 0.000015
        p.vx -= pull * ux * area / p.area; p.vy -= pull * uy * area / p.area
        child.vx += pull * ux * (1 - area / p.area); child.vy += pull * uy * (1 - area / p.area)
        p.extension = 0.0; p.stressXX = 0.0; p.stressXY = 0.0; p.stressYY = 0.0
        p.lastSplit = geologicalTime; child.lastSplit = geologicalTime
        if (vacant >= 0) {
            plates[vacant] = child
            contacts.entries.removeAll { (it.key ushr 32).toInt() == vacant || it.key.toInt() == vacant }
        } else { plates += child; rasters += PlateRaster(count) }
        splits++
    }

    private fun classifyBoundaries() {
        for (i in 0 until count) {
            val p = owner[i]
            if (p < 0) continue
            val x = i % width; val y = i / width
            for (j in intArrayOf(y * width + (x + 1) % width, min(height - 1, y + 1) * width + x)) {
                val q = owner[j]
                if (q < 0 || p == q) continue
                val (nx, ny) = normal(p, q, i)
                val speed = closing(plates[p], plates[q], i, nx, ny)
                val relative = hypot(plates[p].vx - plates[q].vx, plates[p].vy - plates[q].vy)
                val type = when {
                    abs(speed) < max(1e-10, relative * 0.25) -> PlateBoundary.TRANSFORM
                    speed > 0 && rasters[p].buoyant(i) && rasters[q].buoyant(j) -> PlateBoundary.COLLISION
                    speed > 0 -> PlateBoundary.SUBDUCTION
                    rasters[p].buoyant(i) && rasters[q].buoyant(j) -> PlateBoundary.RIFT
                    else -> PlateBoundary.SPREADING
                }
                if (boundaries[i] == 0.toByte()) boundaries[i] = type.ordinal.toByte()
                if (boundaries[j] == 0.toByte()) boundaries[j] = type.ordinal.toByte()
            }
        }
    }

    private fun publishOwners() {
        val ids = IntArray(count) { max(0, owner[it]) }
        world.plateIds = ids
        world.plateBoundaries = boundaries.copyOf()
        world.plateCount = activePlateCount
    }

    companion object {
        const val UPDATE_YEARS = 100L
        const val SPEEDUP = 1000.0
    }
}

/** Longitude wraps; polar rows clamp. Vegetation sampling never changes crust material. */
internal fun sampleField(values: DoubleArray, x: Double, y: Double, width: Int, height: Int): Double {
    val xx = ((x % width) + width) % width
    val yy = y.coerceIn(0.0, height - 1.0)
    val ix = floor(xx).toInt().coerceAtMost(width - 1); val iy = floor(yy).toInt()
    val east = (ix + 1) % width; val south = min(height - 1, iy + 1)
    val tx = xx - ix; val ty = yy - iy
    return (values[iy * width + ix] * (1 - tx) + values[iy * width + east] * tx) * (1 - ty) +
        (values[south * width + ix] * (1 - tx) + values[south * width + east] * tx) * ty
}
