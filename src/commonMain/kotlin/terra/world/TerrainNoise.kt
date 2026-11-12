package terra.world

import kotlin.math.floor

/** Seeded simplex fractals with a C2 longitude-periodic join. */
internal class TerrainNoise(private val settings: TerrainGenerationSettings) {
    fun periodic(x: Double, y: Double, seed: Int, octaves: Int, scale: Double): Double {
        val longitude = ((x % TerrainGenerator.REFERENCE_WIDTH) + TerrainGenerator.REFERENCE_WIDTH) % TerrainGenerator.REFERENCE_WIDTH
        val t = longitude / TerrainGenerator.REFERENCE_WIDTH
        val blend = t * t * t * (t * (t * 6 - 15) + 10)
        return fbm(longitude, y, seed, octaves, scale) * (1 - blend) +
            fbm(longitude - TerrainGenerator.REFERENCE_WIDTH, y, seed, octaves, scale) * blend
    }
    private fun fbm(x: Double, y: Double, seed: Int, octaves: Int, baseScale: Double): Double {
        var total = 0.0
        var weight = 0.0
        var amplitude = 1.0
        var scale = baseScale
        for (octave in 0 until octaves) {
            total += simplexNoise(x / scale, y / scale, seed + octave * 997) * amplitude
            weight += amplitude
            amplitude *= settings.noisePersistence
            scale /= settings.noiseLacunarity
        }
        return total / weight
    }

    private fun simplexNoise(x: Double, y: Double, seed: Int): Double {
        val skew = (x + y) * F2
        val i = floor(x + skew).toInt()
        val j = floor(y + skew).toInt()
        val unskew = (i + j) * G2
        val x0 = x - (i - unskew)
        val y0 = y - (j - unskew)

        val i1 = if (x0 > y0) 1 else 0
        val j1 = if (x0 > y0) 0 else 1
        val x1 = x0 - i1 + G2
        val y1 = y0 - j1 + G2
        val x2 = x0 - 1.0 + 2.0 * G2
        val y2 = y0 - 1.0 + 2.0 * G2

        val corner0 = simplexCorner(i, j, x0, y0, seed)
        val corner1 = simplexCorner(i + i1, j + j1, x1, y1, seed)
        val corner2 = simplexCorner(i + 1, j + 1, x2, y2, seed)
        return ((corner0 + corner1 + corner2) * 70.0).coerceIn(-1.0, 1.0)
    }

    private fun simplexCorner(i: Int, j: Int, x: Double, y: Double, seed: Int): Double {
        val attenuation = 0.5 - x * x - y * y
        if (attenuation <= 0.0) return 0.0
        val gradientIndex = (((lattice(i, j, seed) + 1.0) * 4.0).toInt()).coerceIn(0, 7)
        val gradient = when (gradientIndex) {
            0 -> x + y
            1 -> -x + y
            2 -> x - y
            3 -> -x - y
            4 -> x
            5 -> -x
            6 -> y
            else -> -y
        }
        val squared = attenuation * attenuation
        return squared * squared * gradient
    }

    private fun lattice(x: Int, y: Int, seed: Int): Double {
        var n = x * 374761393 + y * 668265263 + seed * 1442695041
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n.toUInt().toLong() / 4294967295.0) * 2.0 - 1.0
    }

    private companion object {
        const val F2 = 0.3660254037844386
        const val G2 = 0.21132486540518713
    }
}
