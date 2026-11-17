package terra.world

import kotlin.math.*
import kotlin.random.Random

/** Shared initial plates: the generated mountain belts and live geology start from the same crust. */
internal data class CrustPlate(val x: Double, val y: Double, val continental: Double, val u: Double, val v: Double)

internal fun crustPlates(random: Random): List<CrustPlate> {
    val columns = 4
    val rows = 2
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
    private val initial = crustPlates(Random(world.seed xor 0x36ac71))
    private val plates = initial.mapIndexed { i, p ->
        val speed = Random(world.seed + i * 997).nextDouble(0.03, 0.09) / 1000
        MaterialPlate(i, p.x / 283 * PLANET_WIDTH_KM, p.y / 212 * PLANET_HEIGHT_KM,
            p.u * speed, p.v * speed, (Random(world.seed + i * 719).nextDouble() - 0.5) * speed / 4000)
    }.toMutableList()
    private val rasters = plates.map { PlateRaster(count) }.toMutableList()
    private val owner = IntArray(count)
    private val rawOwner = IntArray(count)
    private val topology = PlateTopology(width, height)
    private val weaknessNoise = DoubleArray(count)
    private val totalArea = DoubleArray(count)
    private val elevation = DoubleArray(count)
    private val trench = DoubleArray(count)
    private val sourceX = DoubleArray(count)
    private val sourceY = DoubleArray(count)
    private var boundaries = ByteArray(count)
    private var deformationEvents = ByteArray(count)
    private data class Contact(var duration: Double = 0.0, var shortening: Double = 0.0, var seen: Boolean = false) {
        var initialized = false
        var subducting = -1
        var spreading = false
        var slabDepth = 200.0 // A seeded trench represents an already established shallow slab, in km.
        var length = 0.0
        var oceanLength = 0.0
        var convergence = 0.0
        var reversalTime = 0.0
    }
    private val contacts = HashMap<Long, Contact>()
    private var lastBreakup = -8_000_000.0
    private var lastDeformation = 0.0
    internal data class LifecycleEvent(val years: Double, val kind: String, val first: Int, val second: Int)
    internal val lifecycleEvents = ArrayDeque<LifecycleEvent>()
    private fun event(kind: String, first: Int, second: Int) {
        if (lifecycleEvents.size == 256) lifecycleEvents.removeFirst()
        lifecycleEvents.addLast(LifecycleEvent(geologicalTime, kind, first, second))
    }
    internal var capturedMass = 0.0
        private set
    internal var relativeSpeed = 0.0
        private set
    internal var watchdogBreakups = 0
        private set
    private var geologicalTime = 0.0
    internal val effectiveYears get() = geologicalTime
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
            weaknessNoise[i] = 0.5 + 0.5 * noise.periodic(x, y, world.seed + 8191, 2, 9.0)
            val px = x + warp * noise.periodic(x, y, salt + 503, 2, scale)
            val py = y + warp * noise.periodic(x, y, salt + 719, 2, scale)
            val plate = plates[initial.indices.minBy { p ->
                val rx = periodicDelta(px - initial[p].x, 283.0)
                rx * rx + periodicDelta(py - initial[p].y, 212.0).pow(2)
            }]
            val alt = (world[i % width, i / width].elevation - world.seaLevel) * 10
            val airHeight = if (alt < 0) alt * WATER_LOADING else alt
            val continental = alt >= -1.2
            val c = if (continental) (airHeight + COMPENSATION_KM) / (1 - CONTINENT_DENSITY / MANTLE_DENSITY) else 0.0
            val b = if (continental) 0.0 else 7.0
            val support = c * (1 - CONTINENT_DENSITY / MANTLE_DENSITY) + b * (1 - BASALT_DENSITY / MANTLE_DENSITY) +
                if (continental) 0.0 else 1.7 * exp(-40_000_000.0 / 60_000_000)
            val ix = floor((plate.referenceX + longitudeKm((i % width + 0.5) * dx - plate.x)) / dx).toInt()
            val iy = floor((plate.referenceY + latitudeKm((i / width + 0.5) * dy - plate.y)) / dy).toInt()
            plate.cells[materialKey(ix, iy)] = CrustCell(ix, iy, 1.0, c, b, 0.0, 40_000_000.0,
                airHeight + COMPENSATION_KM - support, weaknessNoise[i])
        }
        rasterize()
        classifyBoundaries()
        publishOwners()
    }

    fun advance(years: Double, rainMmPerYear: DoubleArray, drainage: terra.hydrology.SurfaceWaterSnapshot? = null, speedMultiplier: Double = SPEEDUP) {
        val dt = years * speedMultiplier
        geologicalTime += dt
        deformationEvents = ByteArray(count)
        trench.fill(0.0)
        movePlates(dt)
        rasterize()
        // Let overlapping crust shorten or subduct before ownership cleanup can capture it.
        observeContacts(dt)
        val deformation = shortenedArea + subductedArea + riftArea + oceanCreatedArea
        collide(dt)
        applyEvents()
        spreadRoots()
        rasterize()
        fillGaps()
        rasterize()
        erode(rainMmPerYear, dt, drainage)
        applyEvents()
        if (shortenedArea + subductedArea + riftArea + oceanCreatedArea - deformation > count * 0.00001)
            lastDeformation = geologicalTime
        evolvePlates(dt)
        rasterize()
        captureFragments()
        if (activePlateCount < MIN_PLATES) {
            evolvePlates(0.0)
            rasterize()
        }
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
                val ry = latitudeKm((i / width + 0.5) * dy - plate.y)
                val lx = plate.cosAngle * rx + plate.sinAngle * ry
                val ly = -plate.sinAngle * rx + plate.cosAngle * ry
                val ca = cos(plate.previousAngle); val sa = sin(plate.previousAngle)
                sourceX[i] = (plate.previousX + ca * lx - sa * ly) / dx - 0.5
                sourceY[i] = (plate.previousY + sa * lx + ca * ly) / dy - 0.5
            } else { sourceX[i] = (i % width).toDouble(); sourceY[i] = (i / width).toDouble() }
        }
        world.applyGeography(elevation, sourceX, sourceY)
    }

    /** Overdamped boundary-force balance. Forces are velocity equivalents, not calibrated newtons.
     * The weak, seed-fixed basal traction starts circulation; boundary history supplies the rest. */
    private fun drivePlates(dt: Double) {
        val fx = DoubleArray(plates.size)
        val fy = DoubleArray(plates.size)
        val torque = DoubleArray(plates.size)
        val resistanceLoad = DoubleArray(plates.size)
        for (contact in contacts.values) {
            contact.length = 0.0; contact.oceanLength = 0.0; contact.convergence = 0.0
        }
        fun force(p: Int, i: Int, x: Double, y: Double) {
            val plate = plates[p]
            fx[p] += x; fy[p] += y
            val rx = longitudeKm((i % width + 0.5) * dx - plate.x) - plate.centerX
            val ry = latitudeKm((i / width + 0.5) * dy - plate.y) - plate.centerY
            torque[p] += rx * y - ry * x
        }
        for (i in 0 until count) {
            val p = owner[i]
            if (p < 0 || !plates[p].active) continue
            val x = i % width; val y = i / width
            for (axis in 0..1) {
                val j = if (axis == 0) worldIndex(x + 1, y, width, height) else worldIndex(x, y + 1, width, height)
                val q = owner[j]
                if (q < 0 || p == q || !plates[q].active) continue
                val nx = if (axis == 0) 1.0 else 0.0
                val ny = if (axis == 1) 1.0 else 0.0
                val length = if (axis == 0) dy else dx
                val a = rasters[p]; val b = rasters[q]
                val continental = a.buoyant(i) && b.buoyant(j)
                val speed = closing(plates[p], plates[q], i, nx, ny)
                val contact = contacts.getOrPut(pairKey(p, q)) { Contact() }
                if (!contact.initialized) {
                    contact.initialized = true
                    contact.spreading = speed <= 0.0
                    if (!continental && !contact.spreading) contact.subducting = subductingPlate(p, q, i, j)
                }
                contact.length += length
                contact.convergence += speed * length
                if (!continental) contact.oceanLength += length
                if (contact.subducting >= 0 && !continental) {
                    val sub = contact.subducting
                    val tile = if (sub == p) i else j
                    val r = rasters[sub]
                    // Cooling makes old oceanic lithosphere denser; slab length carries trench memory.
                    val age = r.age[tile] / max(1e-12, r.area[tile])
                    val cooling = 1 - exp(-age / 60_000_000)
                    val pull = if (r.buoyant(tile)) 0.0 else
                        0.00024 * (0.35 + 0.65 * cooling) * min(1.0, contact.slabDepth / 600) * length
                    val sign = if (sub == p) 1.0 else -1.0
                    force(sub, tile, nx * sign * pull, ny * sign * pull)
                }
                if (contact.spreading) {
                    val push = (if (continental) 0.000035 else 0.00006) * length
                    force(p, i, -nx * push, -ny * push)
                    force(q, j, nx * push, ny * push)
                }
                if (continental && speed > 0.0) {
                    // Viscous continental resistance slows convergence without bouncing plates.
                    val resistance = speed * length * 2.0
                    resistanceLoad[p] += length * 2; resistanceLoad[q] += length * 2
                    force(p, i, -nx * resistance, -ny * resistance)
                    force(q, j, nx * resistance, ny * resistance)
                }
            }
        }
        for ((key, contact) in contacts) if (contact.length > 0) {
            if (contact.oceanLength == 0.0) contact.slabDepth *= exp(-dt / 5_000_000)
            contact.reversalTime = if (contact.convergence / contact.length > 0.000005)
                contact.reversalTime + dt else 0.0
            // A ridge can be overridden by the rest of the force system, but not by one noisy tick.
            if (contact.spreading && contact.oceanLength > 0 && contact.reversalTime >= 5_000_000) {
                contact.spreading = false
                val a = plates[(key ushr 32).toInt()]; val b = plates[key.toInt()]
                contact.subducting = if (a.cells.values.sumOf { it.continent } / max(1.0, a.area) <
                    b.cells.values.sumOf { it.continent } / max(1.0, b.area)) a.id else b.id
                contact.slabDepth = 100.0
            }
        }
        val relaxation = 1 - exp(-dt / 2_000_000)
        val maxSpin = 0.2 * PI / 180 / 1_000_000
        for (p in plates) if (p.active) {
            val drag = max(2000.0, p.area * dx * dy / 5000)
            // Relax stiff contact resistance without a tick-to-tick velocity reversal.
            val response = relaxation / (1 + resistanceLoad[p.id] / drag)
            val separating = geologicalTime < p.separationUntil
            var tx = p.desiredX * 0.5 + fx[p.id] / drag + if (separating) p.separationX else 0.0
            var ty = p.desiredY * 0.5 + fy[p.id] / drag + if (separating) p.separationY else 0.0
            val scale = min(1.0, 0.00012 / max(1e-12, hypot(tx, ty)))
            tx *= scale; ty *= scale
            val spin = (p.desiredSpin * 0.25 + torque[p.id] / (drag * max(1.0, p.radius2))).coerceIn(-maxSpin, maxSpin)
            // vx/vy are velocities at the pose origin; drag acts at the material centroid.
            p.vx += (tx + spin * p.centerY - p.vx) * response
            p.vy += (ty - spin * p.centerX - p.vy) * response
            p.spin += (spin - p.spin) * response
        }
    }

    private fun subductingPlate(p: Int, q: Int, i: Int, j: Int): Int {
        val a = rasters[p]; val b = rasters[q]
        return when {
            a.buoyant(i) -> q
            b.buoyant(j) -> p
            a.age[i] / max(1e-12, a.area[i]) > b.age[j] / max(1e-12, b.area[j]) + 1000 -> p
            b.age[j] / max(1e-12, b.area[j]) > a.age[i] / max(1e-12, a.area[i]) + 1000 -> q
            else -> max(p, q)
        }
    }

    private fun movePlates(dt: Double) {
        for (p in plates) if (p.active) p.prepare(width, height)
        drivePlates(dt)
        var meanX = 0.0; var meanY = 0.0; var area = 0.0
        for (p in plates) if (p.active) {
            meanX += p.vx * p.area; meanY += p.vy * p.area; area += p.area
        }
        meanX /= max(1.0, area); meanY /= max(1.0, area)
        relativeSpeed = 0.0
        for (p in plates) if (p.active) {
            p.vx -= meanX; p.vy -= meanY
            relativeSpeed += p.area * (p.vx * p.vx + p.vy * p.vy)
            p.previousX = p.x; p.previousY = p.y; p.previousAngle = p.angle
            val angle = p.angle + p.spin * dt
            // Translation always continues. Only defer rotation of an oversized periodic body.
            if (fits(p, angle, 1.0)) p.angle = angle
            p.x = wrapPosition(p.x + p.vx * dt, PLANET_WIDTH_KM)
            p.y = wrapPosition(p.y + p.vy * dt, PLANET_HEIGHT_KM)
            val consolidated = 1 - exp(-dt / 20_000_000)
            for (cell in p.cells.values) {
                cell.age += dt
                val c = cell.sedimentContinent * consolidated
                val b = (cell.sediment - cell.sedimentContinent) * consolidated
                cell.continent += c * SEDIMENT_DENSITY / CONTINENT_DENSITY
                cell.basalt += b * SEDIMENT_DENSITY / BASALT_DENSITY
                cell.sediment -= c + b; cell.sedimentContinent -= c
            }
        }
        relativeSpeed = sqrt(relativeSpeed / max(1.0, area))
    }

    private fun fits(p: MaterialPlate, angle: Double, fraction: Double): Boolean {
        val x = (p.maxX - p.minX + 2) * dx; val y = (p.maxY - p.minY + 2) * dy
        return abs(cos(angle)) * x + abs(sin(angle)) * y < PLANET_WIDTH_KM * fraction &&
            abs(sin(angle)) * x + abs(cos(angle)) * y < PLANET_HEIGHT_KM * fraction
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
        val startX = floor(left / dx - 0.5).toInt()
        val endX = ceil(right / dx - 0.5).toInt()
        for (y in floor(top / dy - 0.5).toInt()..ceil(bottom / dy - 0.5).toInt()) {
            for (x in startX..endX) {
                val rx = (x + 0.5) * dx - p.x; val ry = (y + 0.5) * dy - p.y
                val gx = (p.referenceX + p.cosAngle * rx + p.sinAngle * ry) / dx - 0.5
                val gy = (p.referenceY - p.sinAngle * rx + p.cosAngle * ry) / dy - 0.5
                val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
                val a = p.cells[materialKey(ix, iy)]; val b = p.cells[materialKey(ix + 1, iy)]
                val c = p.cells[materialKey(ix, iy + 1)]; val d = p.cells[materialKey(ix + 1, iy + 1)]
                if (a == null && b == null && c == null && d == null) continue
                val tx = gx - ix; val ty = gy - iy
                action(worldIndex(x, y, width, height), a, b, c, d,
                    (1 - tx) * (1 - ty), tx * (1 - ty), (1 - tx) * ty, tx * ty)
            }
        }
    }

    /** Normalize each rotated interpolation footprint once per pose, including periodic images.
     * This makes projected inventories equal stored inventories even when sediment is fully eroded. */
    private fun prepareProjection(p: MaterialPlate) {
        if (p.projectionX != p.x || p.projectionY != p.y || p.projectionAngle != p.angle) {
            p.projectionRevision++
            p.projectionX = p.x; p.projectionY = p.y; p.projectionAngle = p.angle
        }
        val ca = p.cosAngle; val sa = p.sinAngle
        val radiusX = abs(ca) * dx + abs(sa) * dy
        val radiusY = abs(sa) * dx + abs(ca) * dy
        for (cell in p.cells.values) if (cell.projectionRevision != p.projectionRevision) {
            val lx = (cell.x + 0.5) * dx - p.referenceX; val ly = (cell.y + 0.5) * dy - p.referenceY
            val cx = p.x + ca * lx - sa * ly; val cy = p.y + sa * lx + ca * ly
            var sum = 0.0
            for (y in floor((cy - radiusY) / dy - 0.5).toInt()..ceil((cy + radiusY) / dy - 0.5).toInt()) {
                for (x in floor((cx - radiusX) / dx - 0.5).toInt()..ceil((cx + radiusX) / dx - 0.5).toInt()) {
                    val rx = (x + 0.5) * dx - cx; val ry = (y + 0.5) * dy - cy
                    sum += max(0.0, 1 - abs((ca * rx + sa * ry) / dx)) *
                        max(0.0, 1 - abs((-sa * rx + ca * ry) / dy))
                }
            }
            cell.projectionWeight = max(1e-12, sum)
            cell.projectionRevision = p.projectionRevision
        }
    }

    private fun rasterize() {
        owner.fill(-1); totalArea.fill(0.0)
        for (p in plates) {
            val r = rasters[p.id]
            r.clear()
            if (!p.active) continue
            p.prepare(width, height)
            prepareProjection(p)
            visit(p) { i, a, b, c, d, wa, wb, wc, wd ->
                r.add(i, a, wa); r.add(i, b, wb); r.add(i, c, wc); r.add(i, d, wd)
            }
            r.forEachIndex { i ->
                totalArea[i] += r.area[i]
                if (r.area[i] > 1e-12 && (owner[i] < 0 || r.area[i] > rasters[owner[i]].area[i])) owner[i] = p.id
            }
        }
        owner.copyInto(rawOwner)
        topology.repair(owner, plates.size) { p, i -> rasters[p].area[i] }
    }

    private data class Capture(val source: MaterialPlate, val target: Int, val cell: CrustCell,
        val x: Double, val y: Double)

    /** Detached columns change frames locally, once per batch; rock never enters the mantle. */
    private fun captureFragments() {
        val transfers = ArrayList<Capture>()
        for (p in plates) if (p.active) for (cell in p.cells.values) {
            val lx = (cell.x + 0.5) * dx - p.referenceX
            val ly = (cell.y + 0.5) * dy - p.referenceY
            val x = p.x + p.cosAngle * lx - p.sinAngle * ly
            val y = p.y + p.sinAngle * lx + p.cosAngle * ly
            val i = worldIndex(floor(x / dx).toInt(), floor(y / dy).toInt(), width, height)
            val target = owner[i]
            if (target >= 0 && target != p.id) {
                var touchesHome = false
                topology.neighbors(i) { if (owner[it] == p.id) touchesHome = true }
                if (rawOwner[i] == p.id || !topology.represented[p.id] || !touchesHome)
                    transfers += Capture(p, target, cell, x, y)
            }
        }
        if (transfers.isEmpty()) return
        val territory = owner.copyOf()
        for (transfer in transfers) transfer.source.cells.remove(materialKey(transfer.cell.x, transfer.cell.y))
        for ((_, target, cell, x, y) in transfers) {
            addWorld(plates[target], x, y, cell.area, cell.continent, cell.basalt, cell.sediment,
                cell.age, cell.relief, cell.sedimentContinent, cell.weakness)
            capturedMass += cell.mass()
        }
        for (p in plates) if (p.active && p.cells.isEmpty()) {
            p.active = false
            contacts.keys.removeAll { (it ushr 32).toInt() == p.id || it.toInt() == p.id }
        }
        rasterize()
        // Capture changes material frames, not the territory that authorized the transfer.
        // Keep those connected cores until the next motion/topology update.
        var retired = false
        for (i in owner.indices) {
            owner[i] = if (plates[territory[i]].active) territory[i] else { retired = true; -1 }
        }
        if (retired) topology.repair(owner, plates.size) { p, i -> rasters[p].area[i] }
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
                mantleRecycledMass += cell.mass() * fraction
                cell.continent *= 1 - fraction; cell.basalt *= 1 - fraction; cell.sediment *= 1 - fraction; cell.sedimentContinent *= 1 - fraction
                cell.area = max(1e-12, cell.area - removed - min(cell.area * 0.5, cell.compressArea))
                cell.continent = max(0.0, cell.continent + cell.dc)
                cell.basalt = max(0.0, cell.basalt + cell.db)
                cell.sediment = max(0.0, cell.sediment + cell.ds)
                cell.sedimentContinent = (cell.sedimentContinent + cell.dsc).coerceIn(0.0, cell.sediment)
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
        val north = worldIndex(x, y - 1, width, height); val south = worldIndex(x, y + 1, width, height)
        val a = rasters[p].area; val b = rasters[q].area
        var nx = (a[west] - a[east] - b[west] + b[east]) / dx
        var ny = (a[north] - a[south] - b[north] + b[south]) / dy
        if (hypot(nx, ny) < 1e-10) {
            nx = longitudeKm(plates[q].x - plates[p].x); ny = latitudeKm(plates[q].y - plates[p].y)
        }
        val length = max(1e-12, hypot(nx, ny))
        return nx / length to ny / length
    }

    private fun closing(p: MaterialPlate, q: MaterialPlate, i: Int, nx: Double, ny: Double): Double {
        val x = (i % width + 0.5) * dx; val y = (i / width + 0.5) * dy
        val ux = p.vx - p.spin * latitudeKm(y - p.y) - q.vx + q.spin * latitudeKm(y - q.y)
        val uy = p.vy + p.spin * longitudeKm(x - p.x) - q.vy - q.spin * longitudeKm(x - q.x)
        return ux * nx + uy * ny
    }

    /** Spread an exact amount among supported columns, using physical distances. */
    private fun patch(p: Int, field: RasterField, i: Int, offsetX: Double, offsetY: Double, amount: Double, radius: Double) {
        if (amount == 0.0) return
        val cx = (i % width + 0.5) * dx + offsetX; val cy = (i / width + 0.5) * dy + offsetY
        val area = rasters[p].area
        val left = floor((cx - 2 * radius) / dx).toInt(); val right = ceil((cx + 2 * radius) / dx).toInt()
        val top = floor((cy - 2 * radius) / dy).toInt(); val bottom = ceil((cy + 2 * radius) / dy).toInt()
        var sum = 0.0
        for (y in top..bottom) for (x in left..right) {
            val j = worldIndex(x, y, width, height)
            val r2 = (((x + 0.5) * dx - cx).pow(2) + ((y + 0.5) * dy - cy).pow(2)) / (radius * radius)
            sum += area[j] * exp(-0.5 * r2)
        }
        if (sum < 1e-12) { field[i] += amount; return }
        for (y in top..bottom) for (x in left..right) {
            val j = worldIndex(x, y, width, height)
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
            topology.neighbors(i) { j ->
                val other = owner[j]
                if (other >= 0 && other != p && rasters[other].area[i] > if (q < 0) 1e-8 else rasters[q].area[i]) q = other
            }
            if (q < 0) continue
            val (nx, ny) = normal(p, q, i)
            val speed = closing(plates[p], plates[q], i, nx, ny)
            if (speed <= 0) { deformationEvents[i] = PlateBoundary.TRANSFORM.ordinal.toByte(); continue }
            val amount = min(totalArea[i] - 1, min(0.25, 2 * speed * dt / min(dx, dy)))
            val a = rasters[p]; val b = rasters[q]
            // Connected ownership can include a deficit tile with no incoming column yet.
            if (a.area[i] <= 1e-8) continue
            if (a.buoyant(i) && b.buoyant(i)) {
                val share = a.area[i] / (a.area[i] + b.area[i])
                patch(p, a.compress, i, 0.0, 0.0, amount * share, 300.0)
                patch(q, b.compress, i, 0.0, 0.0, amount * (1 - share), 300.0)
                shortenedArea += amount
                deformationEvents[i] = PlateBoundary.COLLISION.ordinal.toByte()
                val pair = min(p, q).toLong() shl 32 or max(p, q).toLong()
                contacts.getOrPut(pair) { Contact() }.shortening += amount
            } else {
                val contact = contacts.getOrPut(pairKey(p, q)) { Contact() }
                contact.initialized = true
                val sub = if ((contact.subducting == p || contact.subducting == q) &&
                    !rasters[contact.subducting].buoyant(i)) contact.subducting else subductingPlate(p, q, i, i)
                contact.subducting = sub
                contact.spreading = false
                val over = if (sub == p) q else p
                val r = rasters[sub]; val upper = rasters[over]
                val removed = min(amount, r.area[i] * 0.5)
                val fraction = removed / r.area[i]
                r.remove[i] += removed
                subductedArea += removed
                contact.slabDepth = min(700.0, contact.slabDepth + removed * dx * dy / max(max(dx, dy), contact.length))
                val direction = if (sub == p) 1 else -1
                // Accreted fractions return to the margin; the remainder enters the mantle ledger.
                patch(over, upper.dc, i, nx * direction * 60, ny * direction * 60, r.continent[i] * fraction * 0.90, 120.0)
                patch(over, upper.ds, i, nx * direction * 60, ny * direction * 60, r.sediment[i] * fraction * 0.50, 120.0)
                patch(over, upper.dsc, i, nx * direction * 60, ny * direction * 60, r.sedimentContinent[i] * fraction * 0.50, 120.0)
                mantleRecycledMass -= fraction * (r.continent[i] * 0.90 * CONTINENT_DENSITY + r.sediment[i] * 0.50 * SEDIMENT_DENSITY)
                val basaltMass = r.basalt[i] * fraction * BASALT_DENSITY
                val newContinent = basaltMass * 0.15 / CONTINENT_DENSITY
                val newBasalt = basaltMass * 0.25 / BASALT_DENSITY
                patch(over, upper.dc, i, nx * direction * 220, ny * direction * 220, newContinent, 150.0)
                patch(over, upper.db, i, nx * direction * 220, ny * direction * 220, newBasalt, 150.0)
                mantleAddedMass += basaltMass * 0.40
                deformationEvents[i] = PlateBoundary.SUBDUCTION.ordinal.toByte()
                val tx = (i % width + 0.5) * dx - nx * direction * 100
                val ty = (i / width + 0.5) * dy - ny * direction * 100
                val radius = max(80.0, min(dx, dy) * 0.55)
                for (oy in -2..2) for (ox in -2..2) {
                    val y = floor(ty / dy).toInt() + oy
                    val x = floor(tx / dx).toInt() + ox
                    val distance = hypot((x + 0.5) * dx - tx, (y + 0.5) * dy - ty)
                    val j = worldIndex(x, y, width, height)
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
        val opening = DoubleArray(count)
        val opposite = IntArray(count) { -1 }
        for (i in 0 until count) {
            val p = owner[i]
            if (p < 0) continue
            topology.neighbors(i) { j ->
                val q = owner[j]
                if (q >= 0 && q != p) {
                    val nx = longitudeKm((j % width - i % width) * dx) / dx
                    val ny = latitudeKm((j / width - i / width) * dy) / dy
                    val speed = -closing(plates[p], plates[q], i, nx, ny)
                    if (speed > opening[i] && rasters[q].area[j] > 0.05) {
                        opening[i] = speed; opposite[i] = j
                    }
                }
            }
        }
        var gapSum = 0.0
        for (i in 0 until count) if (opposite[i] >= 0) gapSum += max(0.0, 1 - totalArea[i])
        if (gapSum < 1e-10) return
        val fraction = min(1.0, budget / gapSum)
        val births = ArrayList<Birth>()
        for (i in 0 until count) {
            val j = opposite[i]
            if (j < 0) continue
            val area = max(0.0, 1 - totalArea[i]) * fraction
            if (area < 1e-8) continue
            val p = owner[i]; val q = owner[j]
            val r = rasters[p]; val other = rasters[q]
            var continent = 0.0
            val thickness = min(r.continent[i] / max(1e-12, r.area[i]), other.continent[j] / max(1e-12, other.area[j]))
            if (r.buoyant(i) && other.buoyant(j) && thickness > 12) {
                val availableP = max(0.0, r.continent[i] + min(0.0, r.dc[i])) * 0.2
                val availableQ = max(0.0, other.continent[j] + min(0.0, other.dc[j])) * 0.2
                continent = min(area * thickness * 0.8, 2 * min(availableP, availableQ))
                r.dc[i] -= continent * 0.5; other.dc[j] -= continent * 0.5
                if (continent > 0) { deformationEvents[i] = PlateBoundary.RIFT.ordinal.toByte(); riftArea += area }
            }
            val basalt = if (continent > 0) 0.0 else area * 7
            if (basalt > 0) {
                mantleAddedMass += basalt * BASALT_DENSITY; oceanCreatedArea += area
                deformationEvents[i] = PlateBoundary.SPREADING.ordinal.toByte()
            }
            births += Birth(p, i, area, continent, basalt)
        }
        // Withdraw stretched continental rock before adding its new, thinner columns.
        applyEvents()
        for (birth in births) addWorld(plates[birth.plate], (birth.i % width + 0.5) * dx,
            (birth.i / width + 0.5) * dy, birth.area, birth.continent, birth.basalt, 0.0, 0.0, 0.0)
        // The caller reprojects once after births and resets all accumulators.
    }

    private fun addWorld(p: MaterialPlate, x: Double, y: Double, area: Double, c: Double, b: Double, s: Double, age: Double, relief: Double, sedimentContinent: Double = 0.0,
        weakness: Double = weaknessNoise[worldIndex(floor(x / dx).toInt(), floor(y / dy).toInt(), width, height)]) {
        val rx = longitudeKm(x - p.x); val ry = latitudeKm(y - p.y)
        val gx = (p.referenceX + p.cosAngle * rx + p.sinAngle * ry) / dx - 0.5
        val gy = (p.referenceY - p.sinAngle * rx + p.cosAngle * ry) / dy - 0.5
        val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
        val tx = gx - ix; val ty = gy - iy
        for (oy in 0..1) for (ox in 0..1) {
            val w = (if (ox == 0) 1 - tx else tx) * (if (oy == 0) 1 - ty else ty)
            if (w < 1e-12) continue
            val cell = p.cells.getOrPut(materialKey(ix + ox, iy + oy)) { CrustCell(ix + ox, iy + oy, 0.0, 0.0, 0.0, 0.0, age) }
            cell.weakness = (cell.weakness * cell.area + weakness * area * w) / max(1e-12, cell.area + area * w)
            cell.age = (cell.age * cell.area + age * area * w) / max(1e-12, cell.area + area * w)
            cell.relief = (cell.relief * cell.area + relief * area * w) / max(1e-12, cell.area + area * w)
            cell.area += area * w; cell.continent += c * w; cell.basalt += b * w; cell.sediment += s * w; cell.sedimentContinent += sedimentContinent * w
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
        val continentalFlux = DoubleArray(count)
        for (i in 0 until count) {
            val x = i % width; val y = i / width
            val routed = if (drainage != null && !drainage.isVirtualSpillLink(x, y)) drainage.downstreamTileIndex(x, y) else -1
            var next = if (routed >= 0 && surface[routed] < surface[i]) routed else i
            if (next == i) for (oy in -1..1) for (ox in -1..1) {
                val j = worldIndex(x + ox, y + oy, width, height)
                if (surface[j] < surface[next]) next = j
            }
            if (next != i) downstream[i] = next
            if (surface[i] <= 0 || next == i || totalArea[i] < 1e-10) continue
            val distance = hypot(longitudeKm((next % width - x) * dx), latitudeKm((next / width - y) * dy))
            val drop = surface[i] - surface[next]
            val slope = drop / max(1.0, distance)
            val catchment = drainage?.upstreamLandTileCount(x, y)?.toDouble() ?: 1.0
            val rateMeters = 3 * (0.00002 + 0.01 * slope * sqrt(catchment)) *
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
                if (sediment > 0) {
                    r.ds[i] -= loose * r.sediment[i] / sediment
                    val sc = loose * r.sedimentContinent[i] / sediment
                    r.dsc[i] -= sc; continentalFlux[i] += sc
                }
                if (continent > 0) r.dc[i] -= takeC * r.continent[i] / continent
                if (basalt > 0) r.db[i] -= takeB * r.basalt[i] / basalt
            }
            continentalFlux[i] += takeC * CONTINENT_DENSITY / SEDIMENT_DENSITY
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
            val sc = continentalFlux[i] / amount
            if (next >= 0) { flux[next] += carried; continentalFlux[next] += carried * sc }
            val deposited = amount - carried
            for (p in plates) if (p.active) {
                val share = deposited * rasters[p.id].area[i] / totalArea[i]
                rasters[p.id].ds[i] += share; rasters[p.id].dsc[i] += share * sc
            }
        }
    }

    private fun pairKey(a: Int, b: Int) = (min(a, b).toLong() shl 32) or max(a, b).toLong()

    private fun observeContacts(dt: Double) {
        for (contact in contacts.values) contact.seen = false
        for (i in 0 until count) {
            val p = owner[i]
            if (p < 0) continue
            val x = i % width; val y = i / width
            for (j in intArrayOf(worldIndex(x + 1, y, width, height), worldIndex(x, y + 1, width, height))) {
                val q = owner[j]
                if (q < 0 || p == q) continue
                contacts.getOrPut(pairKey(p, q)) { Contact() }.seen = true
                // Boundary opening drives strain even before a visible gap develops.
                val (nx, ny) = normal(p, q, i)
                val opening = max(0.0, -closing(plates[p], plates[q], i, nx, ny))
                val extension = opening * dt / min(dx, dy)
                for (id in intArrayOf(p, q)) {
                    val plate = plates[id]
                    val lx = plate.cosAngle * nx + plate.sinAngle * ny
                    val ly = -plate.sinAngle * nx + plate.cosAngle * ny
                    plate.extension += extension * 0.5 / max(1.0, plate.area)
                    plate.stressXX += lx * lx * extension
                    plate.stressXY += lx * ly * extension
                    plate.stressYY += ly * ly * extension
                }
            }
        }
    }

    private fun evolvePlates(dt: Double) {
        contacts.entries.removeAll { !it.value.seen || !plates[(it.key ushr 32).toInt()].active || !plates[it.key.toInt()].active }
        for (contact in contacts.values) contact.duration += dt
        if (activePlateCount > MIN_PLATES) {
            val weld = contacts.entries.firstOrNull {
                val a = plates[(it.key ushr 32).toInt()]; val b = plates[it.key.toInt()]
                it.value.duration >= 8_000_000 && it.value.shortening >= 0.05 * min(a.area, b.area) && mergeFits(a, b)
            }
            if (weld != null) { merge(plates[(weld.key ushr 32).toInt()], plates[weld.key.toInt()]); rasterize() }
        }
        val idle = geologicalTime - max(lastDeformation, lastBreakup) >= 12_000_000
        if (geologicalTime - lastBreakup < 8_000_000 && activePlateCount >= MIN_PLATES) return
        val candidates = plates.filter { it.active && it.area >= count * 0.02 &&
            (idle || activePlateCount < MIN_PLATES || !fits(it, it.angle, 0.8) ||
                geologicalTime - it.bornAt >= 40_000_000 && it.extension >= 0.12) }
            .sortedByDescending { (if (!fits(it, it.angle, 0.8)) 1e12 else 0.0) +
                it.extension * 1e8 + geologicalTime - it.bornAt }
        for (p in candidates) {
            if (activePlateCount >= MAX_PLATES) {
                if (!consolidate(p)) continue
                rasterize()
            }
            if (split(p)) {
                lastBreakup = geologicalTime
                if (idle) watchdogBreakups++
                break
            }
        }
    }

    private fun mergeFits(a: MaterialPlate, b: MaterialPlate): Boolean {
        var lowX = a.minX; var highX = a.maxX; var lowY = a.minY; var highY = a.maxY
        for (cell in b.cells.values) {
            val lx = (cell.x + 0.5) * dx - b.referenceX; val ly = (cell.y + 0.5) * dy - b.referenceY
            val rx = longitudeKm(b.x + b.cosAngle * lx - b.sinAngle * ly - a.x)
            val ry = latitudeKm(b.y + b.sinAngle * lx + b.cosAngle * ly - a.y)
            val x = floor((a.referenceX + a.cosAngle * rx + a.sinAngle * ry) / dx).toInt()
            val y = floor((a.referenceY - a.sinAngle * rx + a.cosAngle * ry) / dy).toInt()
            lowX = min(lowX, x - 1); highX = max(highX, x + 1)
            lowY = min(lowY, y - 1); highY = max(highY, y + 1)
        }
        val x = (highX - lowX + 1) * dx; val y = (highY - lowY + 1) * dy
        return abs(a.cosAngle) * x + abs(a.sinAngle) * y < PLANET_WIDTH_KM * 0.8 &&
            abs(a.sinAngle) * x + abs(a.cosAngle) * y < PLANET_HEIGHT_KM * 0.8
    }

    private fun consolidate(candidate: MaterialPlate): Boolean {
        val pairs = contacts.keys.map { plates[(it ushr 32).toInt()] to plates[it.toInt()] }
        val pair = pairs.minByOrNull { (a, b) ->
            val oceanA = a.cells.values.sumOf { it.continent } < a.area
            val oceanB = b.cells.values.sumOf { it.continent } < b.area
            when {
                oceanA && oceanB -> min(a.area, b.area)
                oceanA -> a.area
                oceanB -> b.area
                else -> count + a.area + b.area
            }
        } ?: return false
        when {
            pair.first === candidate -> merge(pair.first, pair.second, true)
            pair.second === candidate -> merge(pair.second, pair.first, true)
            pair.first.area >= pair.second.area -> merge(pair.first, pair.second, true)
            else -> merge(pair.second, pair.first, true)
        }
        return true
    }

    private fun merge(a: MaterialPlate, b: MaterialPlate, consolidation: Boolean = false) {
        val total = max(1e-12, a.area + b.area)
        a.vx = (a.vx * a.area + b.vx * b.area) / total
        a.vy = (a.vy * a.area + b.vy * b.area) / total
        a.spin = (a.spin * a.area + b.spin * b.area) / total
        a.desiredX = (a.desiredX * a.area + b.desiredX * b.area) / total
        a.desiredY = (a.desiredY * a.area + b.desiredY * b.area) / total
        a.desiredSpin = (a.desiredSpin * a.area + b.desiredSpin * b.area) / total
        for (cell in b.cells.values) {
            val lx = (cell.x + 0.5) * dx - b.referenceX; val ly = (cell.y + 0.5) * dy - b.referenceY
            val x = b.x + b.cosAngle * lx - b.sinAngle * ly; val y = b.y + b.sinAngle * lx + b.cosAngle * ly
            addWorld(a, x, y, cell.area, cell.continent, cell.basalt, cell.sediment, cell.age, cell.relief, cell.sedimentContinent, cell.weakness)
        }
        b.cells.clear(); b.active = false
        a.extension = 0.0; a.bornAt = geologicalTime
        a.prepare(width, height)
        contacts.keys.removeAll { (it ushr 32).toInt() == a.id || it.toInt() == a.id ||
            (it ushr 32).toInt() == b.id || it.toInt() == b.id }
        event(if (consolidation) "consolidate" else "weld", a.id, b.id)
        mergers++
    }

    private fun split(p: MaterialPlate): Boolean {
        val theta = if (p.stressXX + p.stressYY > 1e-12) 0.5 * atan2(2 * p.stressXY, p.stressXX - p.stressYY)
            else if ((p.maxX - p.minX) * dx >= (p.maxY - p.minY) * dy) 0.0 else PI / 2
        val nx = cos(theta); val ny = sin(theta)
        val ux = p.cosAngle * nx - p.sinAngle * ny; val uy = p.sinAngle * nx + p.cosAngle * ny
        val cells = p.cells.values.toList()
        val tiles = IntArray(cells.size) { c ->
            val cell = cells[c]
            val lx = (cell.x + 0.5) * dx - p.referenceX; val ly = (cell.y + 0.5) * dy - p.referenceY
            worldIndex(floor((p.x + p.cosAngle * lx - p.sinAngle * ly) / dx).toInt(),
                floor((p.y + p.sinAngle * lx + p.cosAngle * ly) / dy).toInt(), width, height)
        }
        val region = owner.indices.filter { owner[it] == p.id }.sortedBy {
            longitudeKm((it % width + 0.5) * dx - p.x) * ux + latitudeKm((it / width + 0.5) * dy - p.y) * uy
        }
        if (region.size < 2) return false
        val r = rasters[p.id]
        val sum = region.sumOf { r.area[it] }
        fun seed(fraction: Double): Int {
            var area = 0.0
            for (i in region) { area += r.area[i]; if (area >= sum * fraction) return i }
            return region.last()
        }
        val a = seed(0.25); val b = seed(0.75)
        if (a == b) return false
        val strengthCost = DoubleArray(count)
        for (i in region) {
            val area = max(1e-12, r.area[i])
            val weak = if (r.area[i] > 1e-12) r.weakness[i] / area else weaknessNoise[i]
            strengthCost[i] = 1 + 3 * (0.5 * weak + 0.5 * ((35 - r.continent[i] / area) / 25).coerceIn(0.0, 1.0))
        }
        val minimum = max(count * 0.01, p.area * 0.20)
        var best: IntArray? = null
        var balance = Double.POSITIVE_INFINITY
        var lower = -sqrt(region.size.toDouble()) * max(dx, dy) * 4
        var upper = -lower
        repeat(8) {
            val bias = (lower + upper) * 0.5
            val side = topology.divide(owner, p.id, a, b, bias) { from, to ->
                val x = longitudeKm((to % width - from % width) * dx)
                val y = latitudeKm((to / width - from / width) * dy)
                val along = x * ux + y * uy; val across = -x * uy + y * ux
                sqrt(along * along + across * across * 0.25) * (strengthCost[from] + strengthCost[to]) * 0.5
            }
            var area = 0.0
            for (i in cells.indices) if (side[tiles[i]] == 1) area += cells[i].area
            val error = abs(area - p.area * 0.5)
            if (area >= minimum && p.area - area >= minimum && error < balance) { best = side; balance = error }
            if (area > p.area * 0.5) lower = bias else upper = bias
        }
        val side = best ?: return false
        val part = cells.indices.filter { side[tiles[it]] == 1 }.map { cells[it] }
        val vacant = plates.indexOfFirst { !it.active }
        val id = if (vacant >= 0) vacant else plates.size
        val child = MaterialPlate(id, p.x, p.y, p.vx, p.vy, p.spin, p.referenceX, p.referenceY)
        child.angle = p.angle; child.cosAngle = p.cosAngle; child.sinAngle = p.sinAngle
        child.previousX = p.previousX; child.previousY = p.previousY; child.previousAngle = p.previousAngle
        for (cell in part) { p.cells.remove(materialKey(cell.x, cell.y)); child.cells[materialKey(cell.x, cell.y)] = cell }
        for (i in side.indices) if (side[i] == 1) owner[i] = id
        for ((plate, sign) in listOf(p to 1.0, child to -1.0)) {
            plate.desiredX = p.desiredX; plate.desiredY = p.desiredY; plate.desiredSpin = p.desiredSpin
            plate.separationX = sign * 0.00003 * ux; plate.separationY = sign * 0.00003 * uy
            plate.separationUntil = geologicalTime + 20_000_000
            plate.vx += plate.separationX; plate.vy += plate.separationY
            plate.extension = 0.0; plate.stressXX = 0.0; plate.stressXY = 0.0; plate.stressYY = 0.0
            plate.bornAt = geologicalTime
            plate.prepare(width, height)
        }
        contacts.keys.removeAll { (it ushr 32).toInt() == p.id || it.toInt() == p.id ||
            (it ushr 32).toInt() == id || it.toInt() == id }
        if (vacant >= 0) plates[vacant] = child else { plates += child; rasters += PlateRaster(count) }
        event("fracture", p.id, child.id)
        splits++
        return true
    }

    private fun classifyBoundaries() {
        boundaries = ByteArray(count)
        for (i in 0 until count) {
            val p = owner[i]
            if (p < 0) continue
            val x = i % width; val y = i / width
            var strongest = -1.0
            for (j in intArrayOf(worldIndex(x - 1, y, width, height), worldIndex(x, y - 1, width, height))) {
                val q = owner[j]
                if (q < 0 || p == q) continue
                val nx = longitudeKm((j % width - x) * dx) / dx
                val ny = latitudeKm((j / width - y) * dy) / dy
                val speed = closing(plates[p], plates[q], i, nx, ny)
                if (abs(speed) <= strongest) continue
                strongest = abs(speed)
                val relative = hypot(plates[p].vx - plates[q].vx, plates[p].vy - plates[q].vy)
                val type = when {
                    abs(speed) < max(1e-10, relative * 0.25) -> PlateBoundary.TRANSFORM
                    speed > 0 && rasters[p].buoyant(i) && rasters[q].buoyant(j) -> PlateBoundary.COLLISION
                    speed > 0 -> PlateBoundary.SUBDUCTION
                    rasters[p].buoyant(i) && rasters[q].buoyant(j) -> PlateBoundary.RIFT
                    else -> PlateBoundary.SPREADING
                }
                boundaries[i] = type.ordinal.toByte()
            }
        }
    }

    private fun publishOwners() {
        val ids = IntArray(count) { max(0, owner[it]) }
        world.plateIds = ids
        world.plateBoundaries = boundaries.copyOf()
        world.plateCount = activePlateCount
        world.movingPlateCount = plates.count { it.active && hypot(it.vx, it.vy) > 0.000005 }
    }

    companion object {
        const val MIN_PLATES = 6
        const val MAX_PLATES = 10
        const val SPEEDUP = 1000.0
    }
}

/** Both axes wrap. Vegetation sampling never changes crust material. */
internal fun sampleField(values: DoubleArray, x: Double, y: Double, width: Int, height: Int): Double {
    val xx = ((x % width) + width) % width
    val yy = wrapPosition(y, height.toDouble())
    val ix = floor(xx).toInt().coerceAtMost(width - 1); val iy = floor(yy).toInt()
    val east = (ix + 1) % width; val south = (iy + 1) % height
    val tx = xx - ix; val ty = yy - iy
    return (values[iy * width + ix] * (1 - tx) + values[iy * width + east] * tx) * (1 - ty) +
        (values[south * width + ix] * (1 - tx) + values[south * width + east] * tx) * ty
}
