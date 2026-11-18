package terra.world

import kotlin.math.*
import kotlin.time.TimeSource
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
    private val topology = PlateTopology(width, height)
    private val weaknessNoise = DoubleArray(count)
    private val totalArea = DoubleArray(count)
    internal val pendingOpeningArea get() = contacts.values.sumOf { it.openingCredit }
    private val elevation = DoubleArray(count)
    private val sourceX = DoubleArray(count)
    private val sourceY = DoubleArray(count)
    private var boundaries = ByteArray(count)
    private data class Contact(var duration: Double = 0.0, var shortening: Double = 0.0, var seen: Boolean = false) {
        var slabDepth = 200.0 // A seeded trench represents an already established shallow slab, in km.
        var length = 0.0
        var oceanLength = 0.0
        var recentShortening = 0.0
        var continentalLength = 0.0
        var openingCredit = 0.0
        var birthPhase = 0.0
        var subductionYears = 0.0
        var subductionObserved = false
    }
    private val contacts = HashMap<Long, Contact>()
    private var lastBreakup = -30_000_000.0
    private var nextIdentity = plates.size
    internal data class LifecycleEvent(val years: Double, val kind: String, val first: Int, val second: Int, val reason: String)
    internal val lifecycleEvents = ArrayDeque<LifecycleEvent>()
    private fun event(kind: String, first: Int, second: Int, reason: String) {
        if (lifecycleEvents.size == 256) lifecycleEvents.removeFirst()
        lifecycleEvents.addLast(LifecycleEvent(geologicalTime, kind, plates[first].identity, plates[second].identity, reason))
    }
    internal var capturedMass = 0.0
        private set
    internal var captureTransfers = 0L
        private set
    internal var unresolvedRemovalMass = 0.0
        private set
    internal var unplacedAdditionMass = 0.0
        private set
    internal var requestedConvergenceArea = 0.0
        private set
    internal var requestedDivergenceArea = 0.0
        private set
    internal val unresolvedConvergenceArea get() = max(0.0, requestedConvergenceArea - shortenedArea - subductedArea)
    internal val unresolvedDivergenceArea get() = max(0.0, requestedDivergenceArea - oceanCreatedArea - riftArea)
    internal var unresolvedArea = 0.0
        private set
    internal var relativeSpeed = 0.0
        private set
    private var geologicalTime = 0.0
    internal val effectiveYears get() = geologicalTime
    internal var geologicalSteps = 0L
        private set
    internal var geologicalSubsteps = 0L
        private set
    internal var lastWorkSeconds = 0.0
        private set
    internal var geologicalWorkSeconds = 0.0
        private set
    internal var maxSubstepDisplacementKm = 0.0
        private set
    // Continental rock, basalt, sediment, and continental-derived sediment volume.
    internal val mantleAddedComponents = DoubleArray(4)
    internal val mantleRecycledComponents = DoubleArray(4)
    internal val convertedComponents = DoubleArray(4)
    private fun addition(c: Double, b: Double, s: Double = 0.0, sc: Double = 0.0) {
        mantleAddedComponents[0] += c; mantleAddedComponents[1] += b
        mantleAddedComponents[2] += s; mantleAddedComponents[3] += sc
        mantleAddedMass += c * CONTINENT_DENSITY + b * BASALT_DENSITY + s * SEDIMENT_DENSITY
    }
    private fun recycle(c: Double, b: Double, s: Double, sc: Double) {
        mantleRecycledComponents[0] += c; mantleRecycledComponents[1] += b
        mantleRecycledComponents[2] += s; mantleRecycledComponents[3] += sc
        mantleRecycledMass += c * CONTINENT_DENSITY + b * BASALT_DENSITY + s * SEDIMENT_DENSITY
    }
    private fun conversion(c: Double, b: Double, s: Double, sc: Double) {
        convertedComponents[0] += c; convertedComponents[1] += b
        convertedComponents[2] += s; convertedComponents[3] += sc
    }
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
    internal val rockMass get() = plates.sumOf { p -> p.cells.sumOf { it.mass() } }
    internal val continentalVolume get() = plates.sumOf { p -> p.cells.sumOf { it.continent } }
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
            val lx = longitudeKm((i % width + 0.5) * dx - plate.x)
            val ly = latitudeKm((i / width + 0.5) * dy - plate.y)
            plate.cells += CrustCell(lx, ly, 1.0, c, b, 0.0, 40_000_000.0,
                airHeight + COMPENSATION_KM - support, weaknessNoise[i])
        }
        rasterize()
        classifyBoundaries()
        publishOwners()
    }

    fun advance(years: Double, rainMmPerYear: DoubleArray, drainage: terra.hydrology.SurfaceWaterSnapshot? = null, speedMultiplier: Double = SPEEDUP) {
        val work = TimeSource.Monotonic.markNow()
        val endTime = geologicalTime + years * speedMultiplier
        geologicalSteps++
        for (p in plates) if (p.active) {
            p.previousX = p.x; p.previousY = p.y; p.previousAngle = p.angle
        }
        while (geologicalTime < endTime) {
            val dt = stepDuration(endTime - geologicalTime)
            geologicalStep(dt, rainMmPerYear, drainage)
        }
        geologicalTime = endTime
        classifyBoundaries()
        publishOwners()
        for (i in 0 until count) {
            var support = 0.0
            for (p in plates.indices) if (plates[p].active) support += rasters[p].support[i]
            val air = if (totalArea[i] > 1e-10) support / totalArea[i] - COMPENSATION_KM else -3.0
            val h = if (air < 0) air / WATER_LOADING else air
            elevation[i] = world.seaLevel + h / 10
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
        lastWorkSeconds = work.elapsedNow().inWholeNanoseconds / 1e9
        geologicalWorkSeconds += lastWorkSeconds
    }

    private fun geologicalStep(dt: Double, rainMmPerYear: DoubleArray, drainage: terra.hydrology.SurfaceWaterSnapshot?) {
        geologicalSubsteps++
        geologicalTime += dt
        movePlates(dt)
        rasterize()
        observeContacts(dt)
        collide(dt)
        applyEvents()
        spreadRoots()
        repairOwners()
        fillGaps(dt)
        repairOwners()
        erode(rainMmPerYear, dt, drainage)
        applyEvents()
        spreadRoots()
        repairOwners()
        val before = splits
        evolvePlates(dt)
        if (splits != before) rasterize()
    }

    /** Predict the same damped motion used by the step; retries never age rock or contacts. */
    private fun stepDuration(remaining: Double): Double {
        val saved = plates.map { doubleArrayOf(it.vx, it.vy, it.spin) }
        fun restore() {
            for (p in plates) {
                p.vx = saved[p.id][0]; p.vy = saved[p.id][1]; p.spin = saved[p.id][2]
            }
        }
        var dt = remaining
        while (true) {
            restore()
            drivePlates(dt)
            for (p in plates) if (p.active) restrictRotation(p, dt)
            var speed = 0.0
            for (edge in boundaryEdges()) for (sign in listOf(-1.0, 1.0)) {
                val x = edge.x - edge.ny * edge.length * 0.5 * sign
                val y = edge.y + edge.nx * edge.length * 0.5 * sign
                val (vx, vy) = relativeVelocity(plates[edge.p], plates[edge.q], x, y)
                speed = max(speed, hypot(vx, vy))
            }
            val limit = if (speed > 0) 0.3 * min(dx, dy) / speed else remaining
            if (dt <= limit * (1 + 1e-12)) {
                maxSubstepDisplacementKm = max(maxSubstepDisplacementKm, speed * dt)
                restore()
                return dt
            }
            dt = min(dt * 0.8, limit * 0.98)
        }
    }

    /** Overdamped boundary-force balance. Forces are velocity equivalents, not calibrated newtons.
     * The weak, seed-fixed basal traction starts circulation; boundary history supplies the rest. */
    private fun drivePlates(dt: Double) {
        val fx = DoubleArray(plates.size)
        val fy = DoubleArray(plates.size)
        val torque = DoubleArray(plates.size)
        val resistanceLoad = DoubleArray(plates.size)
        for (contact in contacts.values) {
            contact.length = 0.0; contact.oceanLength = 0.0
        }
        fun force(p: Int, i: Int, x: Double, y: Double) {
            val plate = plates[p]
            fx[p] += x; fy[p] += y
            val rx = longitudeKm((i % width + 0.5) * dx - plate.x) - plate.centerX
            val ry = latitudeKm((i / width + 0.5) * dy - plate.y) - plate.centerY
            torque[p] += rx * y - ry * x
        }
        for (edge in boundaryEdges()) {
            val (p, q, i, j) = edge
            val nx = edge.nx; val ny = edge.ny; val length = edge.length; val speed = edge.speed
            val a = rasters[p]; val b = rasters[q]
            if (a.area[i] <= 1e-8 || b.area[j] <= 1e-8) continue
            val continental = a.buoyant(i) && b.buoyant(j)
            val contact = contacts.getOrPut(pairKey(p, q)) { Contact() }
            contact.length += length
            if (!continental) contact.oceanLength += length
            if (speed > 0 && !continental) {
                val sub = subductingPlate(p, q, i, j)
                val tile = if (sub == p) i else j
                val r = rasters[sub]
                val age = r.age[tile] / max(1e-12, r.area[tile])
                val cooling = 1 - exp(-age / 60_000_000)
                val pull = if (r.buoyant(tile)) 0.0 else
                    0.00024 * (0.35 + 0.65 * cooling) * min(1.0, contact.slabDepth / 600) * length
                val sign = if (sub == p) 1.0 else -1.0
                force(sub, tile, nx * sign * pull, ny * sign * pull)
            }
            if (speed < 0) {
                val push = (if (continental) 0.000035 else 0.00006) * length
                force(p, i, -nx * push, -ny * push); force(q, j, nx * push, ny * push)
            }
            if (continental && speed > 0) {
                val resistance = speed * length * 2.0
                resistanceLoad[p] += length * 2; resistanceLoad[q] += length * 2
                force(p, i, -nx * resistance, -ny * resistance)
                force(q, j, nx * resistance, ny * resistance)
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
        drivePlates(dt)
        for (p in plates) if (p.active) restrictRotation(p, dt)
        var meanX = 0.0; var meanY = 0.0; var area = 0.0
        for (p in plates) if (p.active) {
            meanX += p.vx * p.area; meanY += p.vy * p.area; area += p.area
        }
        meanX /= max(1.0, area); meanY /= max(1.0, area)
        relativeSpeed = 0.0
        for (p in plates) if (p.active) {
            p.vx -= meanX; p.vy -= meanY
            relativeSpeed += p.area * (p.vx * p.vx + p.vy * p.vy)
            p.angle += p.spin * dt
            p.x = wrapPosition(p.x + p.vx * dt, PLANET_WIDTH_KM)
            p.y = wrapPosition(p.y + p.vy * dt, PLANET_HEIGHT_KM)
            val consolidated = 1 - exp(-dt / 20_000_000)
            for (cell in p.cells) {
                cell.age += dt
                val c = cell.sedimentContinent * consolidated
                val b = (cell.sediment - cell.sedimentContinent) * consolidated
                cell.continent += c * SEDIMENT_DENSITY / CONTINENT_DENSITY
                cell.basalt += b * SEDIMENT_DENSITY / BASALT_DENSITY
                cell.sediment -= c + b; cell.sedimentContinent -= c
                conversion(c * SEDIMENT_DENSITY / CONTINENT_DENSITY, b * SEDIMENT_DENSITY / BASALT_DENSITY, -c - b, -c)
            }
        }
        relativeSpeed = sqrt(relativeSpeed / max(1.0, area))
    }

    private fun restrictRotation(p: MaterialPlate, dt: Double) {
        if (fits(p, p.angle + p.spin * dt, 1.0)) return
        // Keep centroid translation when the rigid body's rotation cannot be applied.
        p.vx -= p.spin * p.centerY; p.vy += p.spin * p.centerX; p.spin = 0.0
    }

    private fun fits(p: MaterialPlate, angle: Double, fraction: Double): Boolean {
        val ca = cos(angle); val sa = sin(angle)
        for (x in listOf(p.minX - dx, p.maxX + dx)) for (y in listOf(p.minY - dy, p.maxY + dy)) {
            if (abs(ca * x - sa * y) >= PLANET_WIDTH_KM * 0.5 * fraction ||
                abs(sa * x + ca * y) >= PLANET_HEIGHT_KM * 0.5 * fraction) return false
        }
        return true
    }

    private fun worldPosition(p: MaterialPlate, cell: CrustCell): Pair<Double, Double> =
        (p.x + p.cosAngle * cell.x - p.sinAngle * cell.y) to
            (p.y + p.sinAngle * cell.x + p.cosAngle * cell.y)

    private fun localPosition(p: MaterialPlate, x: Double, y: Double): Pair<Double, Double> {
        val rx = longitudeKm(x - p.x); val ry = latitudeKm(y - p.y)
        return (p.cosAngle * rx + p.sinAngle * ry) to (-p.sinAngle * rx + p.cosAngle * ry)
    }

    /** A frame change moves the original parcel, never its inventories or footprint. */
    private fun changeFrame(p: MaterialPlate, cell: CrustCell, x: Double, y: Double) {
        val (lx, ly) = localPosition(p, x, y)
        cell.x = lx; cell.y = ly
        p.cells += cell
    }

    /** Disposable cloud-in-cell scatter and its identical gather stencil. */
    private inline fun project(p: MaterialPlate, cell: CrustCell, action: (Int, Double) -> Unit) {
        val (x, y) = worldPosition(p, cell)
        val gx = x / dx - 0.5; val gy = y / dy - 0.5
        val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
        val tx = gx - ix; val ty = gy - iy
        for (oy in 0..1) for (ox in 0..1) {
            val w = (if (ox == 0) 1 - tx else tx) * (if (oy == 0) 1 - ty else ty)
            if (w > 0) action(worldIndex(ix + ox, iy + oy, width, height), w)
        }
    }

    private fun rasterize() {
        for (p in plates) {
            val r = rasters[p.id]
            r.clear()
            if (!p.active) continue
            p.prepare()
            for (cell in p.cells) {
                cell.prepare()
                project(p, cell) { i, w -> r.add(i, cell, w) }
            }
        }
        repairOwners()
    }

    private fun repairOwners() {
        owner.fill(-1); totalArea.fill(0.0)
        for (p in plates) if (p.active) {
            val r = rasters[p.id]
            r.forEachIndex { i ->
                totalArea[i] += r.area[i]
                if (r.area[i] > 1e-12 && (owner[i] < 0 || r.area[i] > rasters[owner[i]].area[i])) owner[i] = p.id
            }
        }
        topology.repair(owner, plates.size) { p, i -> rasters[p].area[i] }
    }

    private fun materialState(cell: CrustCell, state: DoubleArray) {
        state[0] = cell.area; state[1] = cell.continent; state[2] = cell.basalt
        state[3] = cell.sediment; state[4] = cell.sedimentContinent
        state[5] = cell.support; state[6] = cell.compressible
    }

    private fun updateRaster(p: MaterialPlate, cell: CrustCell, before: DoubleArray, present: Boolean = true) {
        if (present) cell.prepare()
        before[0] = (if (present) cell.area else 0.0) - before[0]
        before[1] = (if (present) cell.continent else 0.0) - before[1]
        before[2] = (if (present) cell.basalt else 0.0) - before[2]
        before[3] = (if (present) cell.sediment else 0.0) - before[3]
        before[4] = (if (present) cell.sedimentContinent else 0.0) - before[4]
        before[5] = (if (present) cell.support else 0.0) - before[5]
        before[6] = (if (present) cell.compressible else 0.0) - before[6]
        project(p, cell) { i, w -> rasters[p.id].adjust(i, w, before, cell.age, cell.weakness) }
        p.area += before[0]
    }

    private fun applyEvents() {
        var rejectedCompression = 0.0
        val before = DoubleArray(7)
        for (p in plates) if (p.active) {
            val r = rasters[p.id]
            for (cell in p.cells) project(p, cell) { i, w -> r.distribute(i, cell, w) }
            var retained = 0
            for (cell in p.cells) {
                if (cell.removeArea == 0.0 && cell.compressArea == 0.0 && cell.dc == 0.0 && cell.db == 0.0 &&
                    cell.ds == 0.0 && cell.dsc == 0.0 && !(cell.area < 1e-7 && cell.continent < 1e-10 && cell.sediment < 1e-10)) {
                    p.cells[retained++] = cell
                    continue
                }
                materialState(cell, before)
                val removed = min(cell.area, cell.removeArea)
                unresolvedArea += cell.removeArea - removed
                val remaining = max(0.0, cell.area - removed)
                val fraction = removed / max(1e-12, cell.area)
                recycle(cell.continent * fraction, cell.basalt * fraction, cell.sediment * fraction, cell.sedimentContinent * fraction)
                cell.continent *= 1 - fraction; cell.basalt *= 1 - fraction; cell.sediment *= 1 - fraction; cell.sedimentContinent *= 1 - fraction
                unresolvedRemovalMass += max(0.0, -cell.continent - cell.dc) * CONTINENT_DENSITY +
                    max(0.0, -cell.basalt - cell.db) * BASALT_DENSITY + max(0.0, -cell.sediment - cell.ds) * SEDIMENT_DENSITY
                cell.continent = max(0.0, cell.continent + cell.dc)
                cell.basalt = max(0.0, cell.basalt + cell.db)
                cell.sediment = max(0.0, cell.sediment + cell.ds)
                cell.sedimentContinent = (cell.sedimentContinent + cell.dsc).coerceIn(0.0, cell.sediment)
                // Incoming rock occupies space too: bound shortening by the final inventory.
                val capacity = max(0.0, remaining - (cell.continent + cell.basalt + cell.sediment) / ROOT_THICKNESS_KM)
                val compression = min(max(0.0, min(cell.area * 0.5, capacity)), cell.compressArea)
                rejectedCompression += cell.compressArea - compression
                unresolvedArea += cell.compressArea - compression
                subductedArea += removed; shortenedArea += compression
                cell.area = max(1e-12, remaining - compression)
                if (cell.area < 1e-7 && cell.continent < 1e-10 && cell.sediment < 1e-10) {
                    recycle(cell.continent, cell.basalt, cell.sediment, cell.sedimentContinent)
                    updateRaster(p, cell, before, false)
                } else { p.cells[retained++] = cell; updateRaster(p, cell, before) }
            }
            if (retained < p.cells.size) p.cells.subList(retained, p.cells.size).clear()
            if (p.cells.isEmpty()) p.active = false
            r.clearEvents()
        }
        // A conservative lower bound on each contact's actually applied shortening.
        if (rejectedCompression > 0) for (contact in contacts.values) {
            val rejected = min(contact.recentShortening, rejectedCompression)
            contact.recentShortening -= rejected; contact.shortening -= rejected
        }
    }

    private data class BoundaryEdge(val p: Int, val q: Int, val i: Int, val j: Int,
        val x: Double, val y: Double, val nx: Double, val ny: Double, val length: Double,
        val speed: Double, val type: PlateBoundary)

    private fun relativeVelocity(p: MaterialPlate, q: MaterialPlate, x: Double, y: Double): Pair<Double, Double> =
        (p.vx - p.spin * latitudeKm(y - p.y) - q.vx + q.spin * latitudeKm(y - q.y)) to
            (p.vy + p.spin * longitudeKm(x - p.x) - q.vy - q.spin * longitudeKm(x - q.x))

    /** Each east/south interface is enumerated once, including both periodic seams. */
    private fun boundaryEdges(): List<BoundaryEdge> {
        val edges = ArrayList<BoundaryEdge>()
        for (i in 0 until count) {
            val p = owner[i]
            if (p < 0 || !plates[p].active) continue
            for (axis in 0..1) {
                val j = worldIndex(i % width + if (axis == 0) 1 else 0,
                    i / width + if (axis == 1) 1 else 0, width, height)
                val q = owner[j]
                if (q < 0 || q == p || !plates[q].active) continue
                val nx = if (axis == 0) 1.0 else 0.0; val ny = 1 - nx
                val x = (i % width + 0.5 + nx * 0.5) * dx
                val y = (i / width + 0.5 + ny * 0.5) * dy
                val (vx, vy) = relativeVelocity(plates[p], plates[q], x, y)
                val speed = vx * nx + vy * ny
                val continental = rasters[p].buoyant(i) && rasters[q].buoyant(j)
                val type = when {
                    abs(speed) < 1e-10 -> PlateBoundary.TRANSFORM
                    speed > 0 && continental -> PlateBoundary.COLLISION
                    speed > 0 -> PlateBoundary.SUBDUCTION
                    continental -> PlateBoundary.RIFT
                    else -> PlateBoundary.SPREADING
                }
                edges += BoundaryEdge(p, q, i, j, x, y, nx, ny, if (axis == 0) dy else dx, speed, type)
            }
        }
        return edges
    }

    /** Spread an exact amount among supported columns, using physical distances. */
    private fun patch(p: Int, field: RasterField, i: Int, offsetX: Double, offsetY: Double, amount: Double, radius: Double, continentalFraction: Double = 0.0): Double {
        if (amount == 0.0) return 0.0
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
        if (sum < 1e-12) {
            val r = rasters[p]
            unplacedAdditionMass += max(0.0, amount) * when (field) {
                r.dc -> CONTINENT_DENSITY
                r.db -> BASALT_DENSITY
                r.ds -> SEDIMENT_DENSITY
                else -> 0.0
            }
            return 0.0
        }
        var applied = 0.0
        for (y in top..bottom) for (x in left..right) {
            val j = worldIndex(x, y, width, height)
            val r2 = (((x + 0.5) * dx - cx).pow(2) + ((y + 0.5) * dy - cy).pow(2)) / (radius * radius)
            var delta = amount * area[j] * exp(-0.5 * r2) / sum
            val r = rasters[p]
            if (field === r.compress && area[j] > 0) {
                val capacity = r.compressible[j] * (1 - r.remove[j] / area[j]) - r.compress[j]
                delta = min(delta, max(0.0, min(capacity, area[j] * 0.5 - r.compress[j] - r.remove[j])))
            }
            if (field === r.dc || field === r.db || field === r.ds) {
                val capacity = ROOT_THICKNESS_KM * area[j] - r.continent[j] - r.basalt[j] - r.sediment[j] -
                    max(0.0, r.dc[j]) - max(0.0, r.db[j]) - max(0.0, r.ds[j])
                delta = min(delta, max(0.0, capacity))
            }
            field[j] += delta; applied += delta
            if (field === r.ds) r.dsc[j] += delta * continentalFraction
        }
        val density = when (field) { rasters[p].dc -> CONTINENT_DENSITY; rasters[p].db -> BASALT_DENSITY; rasters[p].ds -> SEDIMENT_DENSITY; else -> 0.0 }
        unplacedAdditionMass += max(0.0, amount - applied) * density
        return applied
    }

    private fun collide(dt: Double) {
        for (edge in boundaryEdges()) {
            if (edge.speed <= 1e-10) continue
            val requested = edge.speed * edge.length * dt / (dx * dy)
            requestedConvergenceArea += requested
            val (p, q, i, j) = edge
            val a = rasters[p]; val b = rasters[q]
            if (a.area[i] <= 1e-8 || b.area[j] <= 1e-8) continue
            val contact = contacts.getOrPut(pairKey(p, q)) { Contact() }
            if (edge.type == PlateBoundary.COLLISION) {
                val applied = patch(p, a.compress, i, 0.0, 0.0, requested * 0.5, 300.0) +
                    patch(q, b.compress, j, 0.0, 0.0, requested * 0.5, 300.0)
                contact.shortening += applied
                contact.recentShortening += applied
            } else {
                val sub = subductingPlate(p, q, i, j)
                val over = if (sub == p) q else p
                val tile = if (sub == p) i else j; val upperTile = if (sub == p) j else i
                val r = rasters[sub]; val upper = rasters[over]
                val receivingArea = max(0.0, upper.area[upperTile] - upper.remove[upperTile] - upper.compress[upperTile])
                val removed = min(requested, min(receivingArea * 0.5, max(0.0, r.area[tile] * 0.5 - r.remove[tile] - r.compress[tile])))
                if (removed <= 0) continue
                if (!contact.subductionObserved) { contact.subductionYears += dt; contact.subductionObserved = true }
                val fraction = removed / r.area[tile]
                r.remove[tile] += removed
                contact.slabDepth = min(700.0, contact.slabDepth + removed * dx * dy / max(edge.length, contact.length))
                val direction = if (sub == p) 1 else -1
                val nx = edge.nx * direction; val ny = edge.ny * direction
                val accretedC = patch(over, upper.dc, upperTile, nx * 60, ny * 60, r.continent[tile] * fraction * 0.90, 120.0)
                val provenance = if (r.sediment[tile] > 0) r.sedimentContinent[tile] / r.sediment[tile] else 0.0
                val accretedS = patch(over, upper.ds, upperTile, nx * 60, ny * 60, r.sediment[tile] * fraction * 0.50, 120.0, provenance)
                val accretedSc = accretedS * provenance
                val accretedMass = accretedC * CONTINENT_DENSITY + accretedS * SEDIMENT_DENSITY
                recycle(-accretedC, 0.0, -accretedS, -accretedSc); capturedMass += accretedMass
                if (accretedMass > 0) captureTransfers++
                val maturity = 1 - exp(-contact.subductionYears / 2_000_000)
                val basaltMass = r.basalt[tile] * fraction * BASALT_DENSITY * maturity
                val addedC = patch(over, upper.dc, upperTile, nx * 120, ny * 120, basaltMass * 0.15 / CONTINENT_DENSITY, 100.0)
                val addedB = patch(over, upper.db, upperTile, nx * 120, ny * 120, basaltMass * 0.25 / BASALT_DENSITY, 100.0)
                addition(addedC, addedB)
            }
        }
    }

    private data class Birth(val plate: Int, val x: Double, val y: Double, val area: Double, val continent: Double, val basalt: Double)

    private fun fillGaps(dt: Double) {
        var budget = max(0.0, count - plates.filter { it.active }.sumOf { it.area })
        val births = ArrayList<Birth>()
        val openingBatchArea = 1.0 // Two half-tile parcels; initial parcels each have one tile of area.
        val openings = LinkedHashMap<Long, MutableList<BoundaryEdge>>()
        for (edge in boundaryEdges()) {
            if (edge.speed >= -1e-10) continue
            requestedDivergenceArea += -edge.speed * edge.length * dt / (dx * dy)
            openings.getOrPut(pairKey(edge.p, edge.q)) { ArrayList() }.add(edge)
        }
        for ((key, contact) in contacts) if (key !in openings) contact.openingCredit = 0.0
        for ((key, edges) in openings) {
            val contact = contacts.getOrPut(key) { Contact() }
            var flux = 0.0
            val cumulative = DoubleArray(edges.size) { index ->
                flux += -edges[index].speed * edges[index].length
                flux
            }
            contact.openingCredit += flux * dt / (dx * dy)
            // Batch area requests, never rock. Spatial samples follow current local opening flux.
            while (min(contact.openingCredit, budget) >= openingBatchArea) {
                val sample = contact.birthPhase * flux
                val index = cumulative.binarySearch(sample)
                val edge = edges[if (index >= 0) index else -index - 1]
                contact.birthPhase = (contact.birthPhase + 0.6180339887498949) % 1.0
                val (p, q, i, j) = edge
                val r = rasters[p]; val other = rasters[q]
                val area = openingBatchArea
                var continent = 0.0
                val supported = r.area[i] > 1e-8 && other.area[j] > 1e-8
                val thickness = if (supported) min(r.continent[i] / r.area[i], other.continent[j] / other.area[j]) else 0.0
                if (supported && edge.type == PlateBoundary.RIFT && thickness > 12) {
                    val availableP = max(0.0, r.continent[i] + min(0.0, r.dc[i])) * 0.2
                    val availableQ = max(0.0, other.continent[j] + min(0.0, other.dc[j])) * 0.2
                    continent = min(area * thickness * 0.8, 2 * min(availableP, availableQ))
                    r.dc[i] -= continent * 0.5; other.dc[j] -= continent * 0.5
                }
                val basalt = if (continent > 0) 0.0 else area * 7
                // The two sides carry equal halves of the new opening strip.
                val offsetX = edge.nx * area * dx * 0.25
                val offsetY = edge.ny * area * dy * 0.25
                births += Birth(p, edge.x - offsetX, edge.y - offsetY, area * 0.5, continent * 0.5, basalt * 0.5)
                births += Birth(q, edge.x + offsetX, edge.y + offsetY, area * 0.5, continent * 0.5, basalt * 0.5)
                budget -= area; contact.openingCredit -= area
            }
        }
        applyEvents()
        for (birth in births) {
            val p = plates[birth.plate]
            val cell = addWorld(p, birth.x, birth.y, birth.area, birth.continent, birth.basalt, 0.0, 0.0, 0.0)
            cell.prepare()
            project(p, cell) { i, w -> rasters[p.id].add(i, cell, w) }
            p.area += cell.area
            addition(0.0, birth.basalt)
            if (birth.basalt > 0) oceanCreatedArea += birth.area else riftArea += birth.area
        }
    }

    private fun addWorld(p: MaterialPlate, x: Double, y: Double, area: Double, c: Double, b: Double, s: Double, age: Double, relief: Double, sedimentContinent: Double = 0.0,
        weakness: Double = weaknessNoise[worldIndex(floor(x / dx).toInt(), floor(y / dy).toInt(), width, height)]): CrustCell {
        val (lx, ly) = localPosition(p, x, y)
        val cell = CrustCell(lx, ly, area, c, b, s, age, relief, weakness)
        cell.sedimentContinent = sedimentContinent
        p.cells += cell
        return cell
    }

    private fun spreadRoots() {
        val before = DoubleArray(7); val nextBefore = DoubleArray(7)
        // World-space buckets also connect material across a plate-frame cut at either seam.
        val bucketKm = 400.0
        val columns = (PLANET_WIDTH_KM / bucketKm).toInt(); val rows = (PLANET_HEIGHT_KM / bucketKm).toInt()
        val radiusKm = 400.0 + 0.5 * hypot(dx, dy)
        val span = ceil(radiusKm / bucketKm).toInt()
        data class Receiver(val plate: MaterialPlate, val cell: CrustCell, val x: Double, val y: Double)
        var otherBuckets: Map<Int, List<Receiver>>? = null
        for (p in plates) if (p.active && p.cells.any { it.continent + it.basalt + it.sediment - ROOT_THICKNESS_KM * it.area > 1e-12 }) {
            val points = p.cells.map { cell ->
                val (x, y) = worldPosition(p, cell)
                Triple(cell, wrapPosition(x, PLANET_WIDTH_KM), wrapPosition(y, PLANET_HEIGHT_KM))
            }
            val buckets = points.groupBy { worldIndex(floor(it.second / bucketKm).toInt(), floor(it.third / bucketKm).toInt(), columns, rows) }
            for ((cell, x, y) in points) {
                var excess = cell.continent + cell.basalt + cell.sediment - ROOT_THICKNESS_KM * cell.area
                if (excess <= 1e-12) continue
                fun transfer(nextPlate: MaterialPlate, next: CrustCell) {
                    val amount = min(excess, max(0.0, ROOT_THICKNESS_KM * next.area - next.continent - next.basalt - next.sediment))
                    if (amount <= 0) return
                    materialState(cell, before); materialState(next, nextBefore)
                    val fraction = amount / (cell.continent + cell.basalt + cell.sediment)
                    val c = cell.continent * fraction; val b = cell.basalt * fraction
                    val sediment = cell.sediment * fraction; val provenance = cell.sedimentContinent * fraction
                    cell.continent -= c; cell.basalt -= b; cell.sediment -= sediment; cell.sedimentContinent -= provenance
                    next.continent += c; next.basalt += b; next.sediment += sediment; next.sedimentContinent += provenance
                    updateRaster(p, cell, before); updateRaster(nextPlate, next, nextBefore)
                    excess -= amount
                }
                val bx = floor(x / bucketKm).toInt(); val by = floor(y / bucketKm).toInt()
                for (oy in -span..span) for (ox in -span..span) {
                    if (excess <= 1e-12) continue
                    for ((next, nx, ny) in buckets[worldIndex(bx + ox, by + oy, columns, rows)].orEmpty()) {
                        if (excess <= 0.0) break
                        if (hypot(longitudeKm(x - nx), latitudeKm(y - ny)) > radiusKm) continue
                        transfer(p, next)
                    }
                }
                // A detached fragment can have no receiving material of its own plate nearby.
                // Physical lateral flow can cross that boundary without changing territory labels.
                if (excess > 1e-12) {
                    if (otherBuckets == null) otherBuckets = plates.filter { it.active }.flatMap { plate ->
                        plate.cells.map { next ->
                            val (nx, ny) = worldPosition(plate, next)
                            Receiver(plate, next, wrapPosition(nx, PLANET_WIDTH_KM), wrapPosition(ny, PLANET_HEIGHT_KM))
                        }
                    }.groupBy { worldIndex(floor(it.x / bucketKm).toInt(), floor(it.y / bucketKm).toInt(), columns, rows) }
                    for (oy in -span..span) for (ox in -span..span) {
                        if (excess <= 1e-12) continue
                        for (next in otherBuckets[worldIndex(bx + ox, by + oy, columns, rows)].orEmpty()) {
                            if (excess <= 0.0) break
                            if (next.plate === p || hypot(longitudeKm(x - next.x), latitudeKm(y - next.y)) > radiusKm) continue
                            transfer(next.plate, next.cell)
                        }
                    }
                }
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
        // A vanishing CIC tail cannot receive an entire raster catchment's sediment.
        val surfaceAreaMinimum = 0.01
        for (i in 0 until count) {
            val x = i % width; val y = i / width
            val routed = if (drainage != null && !drainage.isVirtualSpillLink(x, y)) drainage.downstreamTileIndex(x, y) else -1
            var next = if (routed >= 0 && totalArea[routed] >= surfaceAreaMinimum && surface[routed] < surface[i]) routed else i
            if (next == i) for (oy in -1..1) for (ox in -1..1) {
                val j = worldIndex(x + ox, y + oy, width, height)
                if (totalArea[j] >= surfaceAreaMinimum && surface[j] < surface[next]) next = j
            }
            if (next != i) downstream[i] = next
            if (surface[i] <= 0 || next == i || totalArea[i] < surfaceAreaMinimum) continue
            val distance = hypot(longitudeKm((next % width - x) * dx), latitudeKm((next / width - y) * dy))
            val drop = surface[i] - surface[next]
            val slope = drop / max(1.0, distance)
            val catchment = (drainage?.upstreamLandTileCount(x, y)?.toDouble() ?: 1.0) *
                dx * dy / ((PLANET_WIDTH_KM / 283) * (PLANET_HEIGHT_KM / 212))
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
            conversion(-takeC, -takeB, (takeC * CONTINENT_DENSITY + takeB * BASALT_DENSITY) / SEDIMENT_DENSITY,
                takeC * CONTINENT_DENSITY / SEDIMENT_DENSITY)
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
            // Export weathered material from active slopes; retaining most as lighter sediment
            // can raise the source surface through buoyancy despite removing its bedrock.
            val carried = if (next >= 0 && surface[i] > 0) amount * (0.75 + sqrt(max(0.0, slope) * 100)).coerceAtMost(0.90) else 0.0
            val sc = continentalFlux[i] / amount
            if (next >= 0) { flux[next] += carried; continentalFlux[next] += carried * sc }
            val deposited = amount - carried
            for (p in plates) if (p.active) {
                val share = deposited * rasters[p.id].area[i] / totalArea[i]
                rasters[p.id].deposit[i] += share; rasters[p.id].depositContinent[i] += share * sc
            }
        }
    }

    private fun pairKey(a: Int, b: Int) = (min(a, b).toLong() shl 32) or max(a, b).toLong()

    private fun observeContacts(dt: Double) {
        val memory = exp(-dt / 20_000_000)
        for (p in plates) if (p.active) {
            p.extension *= memory; p.stressXX *= memory; p.stressXY *= memory; p.stressYY *= memory
            p.extensionAdded = 0.0
        }
        for (contact in contacts.values) contact.seen = false
        for (contact in contacts.values) {
            contact.recentShortening = 0.0
            contact.subductionObserved = false
            contact.continentalLength = 0.0
            contact.shortening *= memory
            contact.length = 0.0; contact.oceanLength = 0.0
        }
        for (edge in boundaryEdges()) {
            val contact = contacts.getOrPut(pairKey(edge.p, edge.q)) { Contact() }
            contact.seen = true
            contact.length += edge.length
            if (!(rasters[edge.p].buoyant(edge.i) && rasters[edge.q].buoyant(edge.j))) contact.oceanLength += edge.length
            if (edge.type == PlateBoundary.COLLISION && edge.speed > 0.000005) contact.continentalLength += edge.length
            val extension = max(0.0, -edge.speed) * edge.length * dt / (dx * dy)
            for (id in intArrayOf(edge.p, edge.q)) {
                val plate = plates[id]
                val lx = plate.cosAngle * edge.nx + plate.sinAngle * edge.ny
                val ly = -plate.sinAngle * edge.nx + plate.cosAngle * edge.ny
                val strain = extension * 0.5 / max(1.0, plate.area)
                plate.extension += strain; plate.extensionAdded += strain
                plate.stressXX += lx * lx * extension
                plate.stressXY += lx * ly * extension
                plate.stressYY += ly * ly * extension
            }
        }
        for (contact in contacts.values) if (contact.oceanLength == 0.0) contact.slabDepth *= exp(-dt / 5_000_000)
        for (p in plates) if (p.active)
            p.riftYears = if (p.extensionAdded > dt * 1e-10) p.riftYears + dt else 0.0
    }

    private fun evolvePlates(dt: Double) {
        for (p in plates) if (p.active) p.prepare()
        contacts.entries.removeAll { !it.value.seen || !plates[(it.key ushr 32).toInt()].active || !plates[it.key.toInt()].active }
        for ((key, contact) in contacts) {
            if (!contact.subductionObserved) contact.subductionYears = 0.0
            val smaller = min(plates[(key ushr 32).toInt()].area, plates[key.toInt()].area)
            val meaningful = contact.continentalLength >= max(500.0, contact.length * 0.25) && contact.recentShortening > smaller * dt * 1e-11
            contact.duration = if (meaningful) contact.duration + dt else 0.0
        }
        val weld = contacts.entries.firstOrNull {
            val a = plates[(it.key ushr 32).toInt()]; val b = plates[it.key.toInt()]
            it.value.duration >= 30_000_000 && it.value.shortening >= 0.12 * min(a.area, b.area) && mergeFits(a, b)
        }
        if (weld != null) { merge(plates[(weld.key ushr 32).toInt()], plates[weld.key.toInt()]); rasterize() }
        if (geologicalTime - lastBreakup < 30_000_000) return
        val candidates = plates.filter { it.active && it.area >= count * 0.02 &&
            geologicalTime - it.bornAt >= 80_000_000 && it.riftYears >= 20_000_000 &&
            it.extension >= 0.20 && it.extensionAdded > 0 }.sortedByDescending { it.extension }
        for (p in candidates) if (split(p)) { lastBreakup = geologicalTime; break }
    }

    private fun mergeFits(a: MaterialPlate, b: MaterialPlate): Boolean {
        var lowX = a.minX; var highX = a.maxX; var lowY = a.minY; var highY = a.maxY
        for (cell in b.cells) {
            val (wx, wy) = worldPosition(b, cell)
            val (x, y) = localPosition(a, wx, wy)
            lowX = min(lowX, x - dx); highX = max(highX, x + dx)
            lowY = min(lowY, y - dy); highY = max(highY, y + dy)
        }
        val x = highX - lowX + dx; val y = highY - lowY + dy
        return abs(a.cosAngle) * x + abs(a.sinAngle) * y < PLANET_WIDTH_KM * 0.8 &&
            abs(a.sinAngle) * x + abs(a.cosAngle) * y < PLANET_HEIGHT_KM * 0.8
    }

    private fun merge(a: MaterialPlate, b: MaterialPlate) {
        val total = max(1e-12, a.area + b.area)
        a.vx = (a.vx * a.area + b.vx * b.area) / total
        a.vy = (a.vy * a.area + b.vy * b.area) / total
        a.spin = (a.spin * a.area + b.spin * b.area) / total
        a.desiredX = (a.desiredX * a.area + b.desiredX * b.area) / total
        a.desiredY = (a.desiredY * a.area + b.desiredY * b.area) / total
        a.desiredSpin = (a.desiredSpin * a.area + b.desiredSpin * b.area) / total
        for (cell in b.cells) {
            val (x, y) = worldPosition(b, cell)
            changeFrame(a, cell, x, y)
        }
        b.cells.clear(); b.active = false
        a.extension = 0.0; a.riftYears = 0.0; a.extensionAdded = 0.0; a.bornAt = geologicalTime
        a.stressXX = 0.0; a.stressXY = 0.0; a.stressYY = 0.0
        a.separationUntil = 0.0; a.separationX = 0.0; a.separationY = 0.0
        a.prepare()
        contacts.keys.removeAll { (it ushr 32).toInt() == a.id || it.toInt() == a.id ||
            (it ushr 32).toInt() == b.id || it.toInt() == b.id }
        event("weld", a.id, b.id, "sustained continental shortening")
        mergers++
    }

    private fun split(p: MaterialPlate): Boolean {
        val theta = if (p.stressXX + p.stressYY > 1e-12) 0.5 * atan2(2 * p.stressXY, p.stressXX - p.stressYY)
            else if (p.maxX - p.minX >= p.maxY - p.minY) 0.0 else PI / 2
        val nx = cos(theta); val ny = sin(theta)
        val ux = p.cosAngle * nx - p.sinAngle * ny; val uy = p.sinAngle * nx + p.cosAngle * ny
        val cells = p.cells.toList()
        val tiles = IntArray(cells.size) { c ->
            val cell = cells[c]
            val (x, y) = worldPosition(p, cell)
            worldIndex(floor(x / dx).toInt(), floor(y / dy).toInt(), width, height)
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
        val child = MaterialPlate(id, p.x, p.y, p.vx, p.vy, p.spin)
        child.identity = nextIdentity++
        child.angle = p.angle; child.cosAngle = p.cosAngle; child.sinAngle = p.sinAngle
        child.previousX = p.previousX; child.previousY = p.previousY; child.previousAngle = p.previousAngle
        p.cells.removeAll(part.toSet()); child.cells.addAll(part)
        for (i in side.indices) if (side[i] == 1) owner[i] = id
        for ((plate, sign) in listOf(p to 1.0, child to -1.0)) {
            plate.desiredX = p.desiredX; plate.desiredY = p.desiredY; plate.desiredSpin = p.desiredSpin
            plate.separationX = sign * 0.00003 * ux; plate.separationY = sign * 0.00003 * uy
            plate.separationUntil = geologicalTime + 20_000_000
            plate.vx += plate.separationX; plate.vy += plate.separationY
            plate.extension = 0.0; plate.riftYears = 0.0; plate.extensionAdded = 0.0; plate.stressXX = 0.0; plate.stressXY = 0.0; plate.stressYY = 0.0
            plate.bornAt = geologicalTime
            plate.prepare()
        }
        contacts.keys.removeAll { (it ushr 32).toInt() == p.id || it.toInt() == p.id ||
            (it ushr 32).toInt() == id || it.toInt() == id }
        if (vacant >= 0) plates[vacant] = child else { plates += child; rasters += PlateRaster(count) }
        event("fracture", p.id, child.id, "mature rift under recent extension")
        splits++
        return true
    }

    private fun classifyBoundaries() {
        boundaries = ByteArray(count)
        val strongest = DoubleArray(count) { -1.0 }
        for (edge in boundaryEdges()) if (abs(edge.speed) > strongest[edge.j]) {
            strongest[edge.j] = abs(edge.speed)
            boundaries[edge.j] = edge.type.ordinal.toByte()
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
