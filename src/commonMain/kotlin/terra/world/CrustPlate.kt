package terra.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import terra.world.TerrainGenerator.Companion.REFERENCE_HEIGHT
import terra.world.TerrainGenerator.Companion.REFERENCE_WIDTH

/** Shared initial plates: the generated mountain belts and live geology start from the same crust. */
internal data class CrustPlate(val x: Double, val y: Double, val continental: Double, val u: Double, val v: Double)

internal fun crustPlates(random: Random): List<CrustPlate> {
    val columns = 4
    val rows = 2
    return List(columns * rows) { index ->
        val angle = random.nextDouble() * 2 * PI
        CrustPlate((index % columns + 0.15 + random.nextDouble() * 0.7) * REFERENCE_WIDTH / columns,
            (index / columns + 0.15 + random.nextDouble() * 0.7) * REFERENCE_HEIGHT / rows,
            if (random.nextDouble() < 0.45) 1.0 else 0.0, cos(angle), sin(angle))
    }
}
