package terra.world

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random
import terra.hydrology.SurfaceWaterSnapshot
import terra.world.TerrainGenerator.Companion.REFERENCE_HEIGHT
import terra.world.TerrainGenerator.Companion.REFERENCE_WIDTH

/** The grid carries one crust type and one elevation per tile. Plate drift moves whole tiles. */
internal class PlateTectonics(private val world: World) {
    private val width = world.width
    private val height = world.height
    private val count = width * height
    private val sites = crustPlates(Random(world.seed xor 0x36ac71))
    // Drift is in reference-map tiles per million years, independent of raster resolution.
    private val vx = DoubleArray(sites.size) { sites[it].u * 0.24 }
    private val vy = DoubleArray(sites.size) { sites[it].v * 0.24 }
    private val topology = PlateTopology(width, height)
    private val east = IntArray(count) { index(it % width + 1, it / width) }
    private val south = IntArray(count) { index(it % width, it / width + 1) }
    private val shelf = world.seaLevel - 0.08
    private val riftFloor = world.seaLevel - 0.025
    private val oceanFloor = world.seaLevel - 0.19

    private var owner = IntArray(count)
    private var elevation = DoubleArray(count) { world[it % width, it / width].elevation }
    private var continental = BooleanArray(count) { elevation[it] >= shelf }
    private var vegetationSource = IntArray(count)
    private var nextOwner = IntArray(count)
    private var nextElevation = DoubleArray(count)
    private var nextContinental = BooleanArray(count)
    private var nextSource = IntArray(count)
    private val boundaries = ByteArray(count)
    private val forcing = DoubleArray(count)
    private val changes = DoubleArray(count)
    private val relief = DoubleArray(count)

    var effectiveYears = 0.0
        private set

    init {
        val noise = TerrainNoise(world.settings)
        val salt = Random(world.seed).nextInt()
        val scale = world.settings.continentScale
        for (i in 0 until count) {
            val x = (i % width + 0.5) * REFERENCE_WIDTH / width
            val y = (i / width + 0.5) * REFERENCE_HEIGHT / height
            val px = x + scale * 0.22 * noise.periodic(x, y, salt + 503, 2, scale * 0.65)
            val py = y + scale * 0.22 * noise.periodic(x, y, salt + 719, 2, scale * 0.65)
            owner[i] = sites.indices.minBy { p ->
                val dx = periodicDelta(px - sites[p].x, REFERENCE_WIDTH)
                val dy = periodicDelta(py - sites[p].y, REFERENCE_HEIGHT)
                dx * dx + dy * dy
            }
            relief[i] = noise.periodic(x, y, salt + 997, 3, 12.0)
        }
        topology.repair(owner, sites.size) { _, _ -> 0.0 }
        markBoundaries()
        publishPlates()
    }

    fun advance(years: Double, rainMmPerYear: DoubleArray, drainage: SurfaceWaterSnapshot? = null, speedMultiplier: Double) {
        val end = effectiveYears + years * speedMultiplier
        for (i in vegetationSource.indices) vegetationSource[i] = i
        // Keep the terrain increment bounded when advancing multiple geological updates at once.
        val steps = ceil((end - effectiveYears) / 1_000_000).toInt().coerceAtLeast(1)
        val start = effectiveYears
        repeat(steps) { step ->
            val next = start + (end - start) * (step + 1) / steps
            val myr = (next - effectiveYears) / 1_000_000
            drift(effectiveYears / 1_000_000, next / 1_000_000)
            deform(myr)
            erode(myr, rainMmPerYear, drainage)
            effectiveYears = next
        }
        publishPlates()
        world.applyGeography(elevation, vegetationSource)
    }

    private fun drift(fromMyr: Double, toMyr: Double) {
        val shiftX = IntArray(sites.size) { displacement(vx[it] * width / REFERENCE_WIDTH, fromMyr, toMyr) }
        val shiftY = IntArray(sites.size) { displacement(vy[it] * height / REFERENCE_HEIGHT, fromMyr, toMyr) }
        if (sites.indices.all { shiftX[it] == 0 && shiftY[it] == 0 }) return

        nextOwner.fill(-1)
        for (i in 0 until count) {
            val p = owner[i]
            val j = index(i % width + shiftX[p], i / width + shiftY[p])
            // Continental crust overrides oceanic crust. Between like tiles keep the higher surface.
            if (nextOwner[j] < 0 || continental[i] && !nextContinental[j] ||
                continental[i] == nextContinental[j] && elevation[i] > nextElevation[j]) {
                nextOwner[j] = p
                nextContinental[j] = continental[i]
                nextElevation[j] = elevation[i]
                nextSource[j] = vegetationSource[i]
            }
        }
        for (i in 0 until count) if (nextOwner[i] < 0) {
            var rim = 0.0
            var neighbors = 0
            var continentalRim = 0
            topology.neighbors(i) { j ->
                if (nextOwner[j] >= 0) {
                    rim += nextElevation[j]
                    neighbors++
                    if (nextContinental[j]) continentalRim++
                }
            }
            // Openings within a continent start as valleys. Open water leaves oceanic crust.
            val rift = continentalRim >= 2 && continentalRim * 2 >= neighbors
            val floor = if (rift) riftFloor else oceanFloor + 0.035 * relief[i]
            val fresh = if (neighbors > 0) (rim / neighbors + floor) * 0.5 else floor
            nextElevation[i] = if (rift) fresh else minOf(shelf, fresh)
            nextContinental[i] = rift
            nextSource[i] = -1
        }
        topology.repair(nextOwner, sites.size) { p, i -> if (owner[i] == p) 1.0 else 0.0 }
        owner = nextOwner.also { nextOwner = owner }
        elevation = nextElevation.also { nextElevation = elevation }
        continental = nextContinental.also { nextContinental = continental }
        vegetationSource = nextSource.also { nextSource = vegetationSource }
        markBoundaries()
    }

    private fun displacement(speed: Double, fromMyr: Double, toMyr: Double) =
        (speed * toMyr).roundToInt() - (speed * fromMyr).roundToInt()

    private fun markBoundaries() {
        boundaries.fill(0)
        forcing.fill(0.0)
        for (i in 0 until count) {
            markEdge(i, east[i], vx, vy)
            markEdge(i, south[i], vy, vx)
        }
    }

    private fun markEdge(i: Int, j: Int, normal: DoubleArray, tangent: DoubleArray) {
        val p = owner[i]
        val q = owner[j]
        if (p == q) return
        val speed = normal[p] - normal[q]
        val landCollision = continental[i] && continental[j]
        val type = when {
            abs(speed) <= 0.25 * abs(tangent[p] - tangent[q]) -> PlateBoundary.TRANSFORM
            speed > 0 && landCollision -> PlateBoundary.COLLISION
            speed > 0 -> PlateBoundary.SUBDUCTION
            landCollision -> PlateBoundary.RIFT
            else -> PlateBoundary.SPREADING
        }
        if (boundaries[j] == 0.toByte() || type != PlateBoundary.TRANSFORM) boundaries[j] = type.ordinal.toByte()
        when (type) {
            PlateBoundary.COLLISION, PlateBoundary.RIFT, PlateBoundary.SPREADING -> {
                force(i, speed)
                force(j, speed)
            }
            PlateBoundary.SUBDUCTION -> {
                val overriding = if (continental[i] || !continental[j] && p < q) i else j
                force(overriding, speed)
            }
            else -> Unit
        }
    }

    private fun force(i: Int, speed: Double) {
        if (abs(speed) > abs(forcing[i])) forcing[i] = speed
    }

    private fun deform(myr: Double) {
        val mountainHeight = world.settings.mountainRelief
        for (i in 0 until count) {
            var sum = forcing[i] * 2
            topology.neighbors(i) { sum += forcing[it] }
            changes[i] = sum / 6
        }
        for (i in 0 until count) {
            var sum = changes[i] * 2
            topology.neighbors(i) { sum += changes[it] }
            val speed = sum / 6
            if (speed == 0.0) continue
            val h = elevation[i]
            val target = when {
                speed > 0 && continental[i] -> world.seaLevel + mountainHeight * (1.1 + 0.4 * relief[i])
                speed > 0 -> world.seaLevel + mountainHeight * (0.4 * relief[i] - 0.1)
                continental[i] -> riftFloor
                else -> oceanFloor + 0.05
            }
            val difference = if (speed > 0 || !continental[i]) maxOf(0.0, target - h) else minOf(0.0, target - h)
            elevation[i] += (abs(speed) * myr * 0.3).coerceAtMost(1.0) * difference
        }
    }

    private fun erode(myr: Double, rain: DoubleArray, drainage: SurfaceWaterSnapshot?) {
        val strength = world.settings.erosionStrength
        if (strength == 0.0) return
        changes.fill(0.0)
        for (i in 0 until count) {
            val h = elevation[i]
            if (h <= world.seaLevel) {
                // Spread sediment into lower seabed nearby, softening narrow scars from fresh crust.
                topology.neighbors(i) { j ->
                    if (elevation[j] < h) {
                        val sediment = (h - elevation[j]) * strength * myr * 0.025
                        changes[i] -= sediment
                        changes[j] += sediment
                    }
                }
                continue
            }
            if (rain[i] <= 0.0) continue
            val x = i % width
            val y = i / width
            var next = i
            if (drainage != null && !drainage.isVirtualSpillLink(x, y)) {
                val routed = drainage.downstreamTileIndex(x, y)
                if (routed >= 0 && elevation[routed] < h) next = routed
            }
            if (next == i) topology.neighbors(i) { if (elevation[it] < elevation[next]) next = it }
            if (next == i) continue
            val rate = 0.025 * strength * (rain[i] / 1000).coerceAtMost(2.0)
            val sediment = (h - elevation[next]) * rate * myr
            changes[i] -= sediment
            changes[next] += sediment
        }
        for (i in 0 until count) elevation[i] += changes[i]
    }

    private fun publishPlates() {
        val present = BooleanArray(sites.size)
        for (p in owner) present[p] = true
        world.plateIds = owner.copyOf()
        world.plateBoundaries = boundaries.copyOf()
        world.plateCount = present.count { it }
        world.movingPlateCount = world.plateCount
    }

    private fun index(x: Int, y: Int) = worldIndex(x, y, width, height)
}
