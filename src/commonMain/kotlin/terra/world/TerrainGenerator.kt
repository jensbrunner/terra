package terra.world

import kotlin.math.*
import kotlin.random.Random
import terra.hydrology.HydrologyTopology

/** Build-time, tectonic-inspired geography. Not a plate dynamics or geological time solver. */
internal class TerrainGenerator(
    seed: Int, private val width: Int, private val height: Int,
    private val settings: TerrainGenerationSettings
) {
    private data class Crust(val x: Double, val y: Double, val continental: Double, val u: Double, val v: Double)
    private data class OldRange(val x: Double, val y: Double, val u: Double, val v: Double,
        val halfLength: Double, val breadth: Double, val bend: Double)
    private val noiseSalt = Random(seed).nextInt()
    private val noise = TerrainNoise(settings)
    private val plates: List<Crust>
    private val distances: DoubleArray
    private val weights: DoubleArray
    private val closingRates: DoubleArray
    private val oldRanges: List<OldRange>

    init {
        require(width >= 3 && height >= 1)
        require(settings.continentScale > 0 && settings.regionalScale > 0 && settings.detailScale > 0)
        require(settings.continentOctaves > 0 && settings.regionalOctaves > 0 && settings.detailOctaves > 0)
        require(settings.baseElevation in 0.0..1.0 && settings.seaLevel in 0.0..1.0 && settings.mountainThreshold in 0.0..1.0)
        require(listOf(settings.continentScale, settings.regionalScale, settings.detailScale,
            settings.continentStrength, settings.regionalStrength, settings.detailStrength).all { it.isFinite() && it >= 0 })
        require(settings.noisePersistence in 0.0..1.0 && settings.noiseLacunarity.isFinite() && settings.noiseLacunarity >= 1.0)
        require(settings.mountainRelief in 0.0..0.6 && settings.erosionStrength in 0.0..1.0)
        val random = Random(seed xor 0x36ac71)
        val columns = (REFERENCE_WIDTH / settings.continentScale * 1.8).roundToInt().coerceIn(2, 16)
        val rows = (REFERENCE_HEIGHT / settings.continentScale * 1.2).roundToInt().coerceIn(2, 10)
        plates = List(columns * rows) { index ->
            val angle = random.nextDouble() * 2 * PI
            Crust((index % columns + 0.15 + random.nextDouble() * 0.7) * REFERENCE_WIDTH / columns,
                (index / columns + 0.15 + random.nextDouble() * 0.7) * REFERENCE_HEIGHT / rows,
                if (random.nextDouble() < 0.45) 1.0 else 0.0, cos(angle), sin(angle))
        }
        distances = DoubleArray(plates.size)
        weights = DoubleArray(plates.size)
        closingRates = DoubleArray(plates.size * plates.size) { index ->
            val a = plates[index / plates.size]; val b = plates[index % plates.size]
            val dx = longitudeDelta(b.x - a.x); val dy = b.y - a.y
            val separation = sqrt(dx * dx + dy * dy)
            if (separation == 0.0) 0.0 else max(0.0, ((a.u - b.u) * dx + (a.v - b.v) * dy) / separation)
        }
        oldRanges = plates.filter { it.continental > 0 }.map { crust ->
            val angle = random.nextDouble() * 2 * PI
            OldRange(crust.x, crust.y, cos(angle), sin(angle),
                settings.continentScale * (0.35 + 0.25 * random.nextDouble()),
                2.5 + 3.0 * random.nextDouble(), (random.nextDouble() - 0.5) * 22)
        }
    }

    fun generate(): DoubleArray {
        val elevation = DoubleArray(width * height) { i ->
            sample((i % width + 0.5) * REFERENCE_WIDTH / width, (i / width + 0.5) * REFERENCE_HEIGHT / height)
        }
        if (settings.erosionStrength > 0) erode(elevation)
        return elevation
    }

    private fun sample(x: Double, y: Double): Double {
        val warpScale = settings.continentScale * 0.65
        val warp = settings.continentScale * 0.22
        val px = wrap(x + warp * noise.periodic(x, y, noiseSalt + 503, 2, warpScale))
        val py = y + warp * noise.periodic(x, y, noiseSalt + 719, 2, warpScale)
        var minimum = Double.POSITIVE_INFINITY
        for (p in plates.indices) {
            val dx = longitudeDelta(px - plates[p].x)
            val dy = py - plates[p].y
            val distance = sqrt(dx * dx + dy * dy)
            distances[p] = distance
            minimum = min(minimum, distance)
        }
        var continental = 0.0
        var total = 0.0
        var convergence = 0.0
        // Soft membership avoids hard Voronoi coastlines and triple-junction corners.
        for (p in plates.indices) {
            val weight = exp(-(distances[p] - minimum) / 7.0)
            weights[p] = weight
            continental += plates[p].continental * weight
            total += weight
        }
        continental /= total
        // Pairwise blending avoids height jumps when the nearest site changes at a junction.
        for (a in plates.indices) {
            if (weights[a] < 1e-6) continue
            for (b in a + 1 until plates.size) {
                if (weights[b] < 1e-6) continue
                convergence += closingRates[a * plates.size + b] * weights[a] * weights[b]
            }
        }
        convergence *= 2 / total
        val broad = noise.periodic(px, py, noiseSalt, settings.continentOctaves, settings.continentScale)
        val regional = noise.periodic(px, py, noiseSalt + 71, settings.regionalOctaves, settings.regionalScale)
        val detail = noise.periodic(px, py, noiseSalt + 191, settings.detailOctaves, settings.detailScale)
        val ridge = (1 - abs(noise.periodic(px, py, noiseSalt + 997, 3, settings.regionalScale * 1.8))).pow(4)
        // Older folded ranges survive away from today's convergent boundaries.
        var oldRange = 0.0
        for (range in oldRanges) {
            val rx = longitudeDelta(px - range.x); val ry = py - range.y
            val along = (rx * range.u + ry * range.v) / range.halfLength
            if (abs(along) >= 1) continue
            val across = -rx * range.v + ry * range.u - range.bend * (1 - along * along)
            val taper = ((1 - abs(along)) / 0.25).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
            oldRange = max(oldRange, exp(-0.5 * (across / range.breadth).pow(2)) * taper)
        }
        val uplift = settings.mountainRelief * (settings.continentStrength / 0.56) * (
            convergence.coerceAtMost(1.8) * (0.65 + 0.35 * continental) * (0.4 + 0.6 * ridge) +
                continental * continental * oldRange * (0.65 + 0.35 * ridge))
        // Less high-frequency relief in lowlands; coherent ridges carry the mountain detail.
        val roughness = 0.35 + 0.65 * (uplift / 0.2).coerceIn(0.0, 1.0)
        return (settings.baseElevation + settings.continentStrength * (0.52 * (continental - 0.5) + 0.18 * broad) +
            uplift + regional * settings.regionalStrength * roughness + detail * settings.detailStrength * roughness)
            .coerceIn(0.0, 1.0)
    }

    private fun erode(elevation: DoubleArray) {
        val strength = settings.erosionStrength
        val dx = REFERENCE_WIDTH / width
        val dy = REFERENCE_HEIGHT / height
        val delta = DoubleArray(elevation.size)
        // Short conservative talus relaxation: outgoing material is deposited locally.
        repeat(3) {
            delta.fill(0.0)
            for (i in elevation.indices) {
                if (elevation[i] <= settings.seaLevel) continue
                val x = i % width; val y = i / width
                var destination = -1
                var excess = 0.0
                for (oy in -1..1) for (ox in -1..1) {
                    if (ox == 0 && oy == 0 || y + oy !in 0 until height) continue
                    val next = (y + oy) * width + (x + ox + width) % width
                    val distance = sqrt(ox * ox * dx * dx + oy * oy * dy * dy)
                    val drop = elevation[i] - elevation[next] - 0.035 * distance
                    if (drop > excess) { excess = drop; destination = next }
                }
                if (destination >= 0) {
                    // At most eight incoming transfers; <=0.1 keeps this a bounded convex update.
                    val amount = excess * 0.1 * strength
                    delta[i] -= amount; delta[destination] += amount
                }
            }
            for (i in elevation.indices) elevation[i] += delta[i]
        }
        // Reuse proven periodic drainage. This is an incision filter, not simulated rainfall.
        val topology = HydrologyTopology(width, height, elevation,
            BooleanArray(elevation.size) { elevation[it] < settings.seaLevel })
        val original = elevation.copyOf()
        val tileArea = dx * dy
        for (position in topology.processingOrder.indices.reversed()) {
            val i = topology.processingOrder[position]
            val downstream = topology.downstreamIndex[i]
            if (downstream < 0 || original[i] <= settings.seaLevel || original[downstream] >= original[i]) continue
            val ix = i % width; val jx = downstream % width
            val distance = if (ix != jx && i / width != downstream / width) sqrt(dx * dx + dy * dy)
                else if (ix != jx) dx else dy
            val area = topology.upstreamLandTileCount[i] * tileArea
            val coefficient = strength * 0.12 * sqrt(area) / distance
            val target = (original[i] + coefficient * max(settings.seaLevel, elevation[downstream])) / (1 + coefficient)
            elevation[i] = max(settings.seaLevel, max(original[i] - 0.10 * strength, target))
        }
    }

    private fun wrap(x: Double) = ((x % REFERENCE_WIDTH) + REFERENCE_WIDTH) % REFERENCE_WIDTH
    private fun longitudeDelta(x: Double) = wrap(x + REFERENCE_WIDTH / 2) - REFERENCE_WIDTH / 2

    companion object {
        // Existing slider scales retain their native-map units at any requested raster size.
        const val REFERENCE_WIDTH = 283.0
        const val REFERENCE_HEIGHT = 212.0
    }
}
