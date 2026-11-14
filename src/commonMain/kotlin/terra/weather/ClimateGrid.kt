package terra.weather

import terra.atmosphere.DryAtmosphereConfig
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative overlap between two equal-area Cartesian grids covering the same domain.
 * Both restriction and prolongation conserve an extensive quantity because every target
 * cell's weights sum to one, and every source cell's weights sum to one.
 */
internal class GridOverlap(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
    }

    private val sourceCount = sourceWidth * sourceHeight
    private val targetCount = targetWidth * targetHeight

    /** For each target cell: source cells and the fraction of the target they cover. */
    val targetOffsets = IntArray(targetCount + 1)
    val targetSources: IntArray
    val targetWeights: DoubleArray

    /** For each source cell: target cells and the fraction of the source they cover. */
    val sourceOffsets = IntArray(sourceCount + 1)
    val sourceTargets: IntArray
    val sourceWeights: DoubleArray

    /** The target cell containing the largest part of each source cell. */
    val targetOfSource = IntArray(sourceCount)

    init {
        val forwardSources = ArrayList<Int>()
        val forwardWeights = ArrayList<Double>()
        val backwardTargets = ArrayList<Int>()
        val backwardWeights = ArrayList<Double>()

        // Equal-area cells span identical normalized domains. Build both incidence lists
        // in one sweep over candidate rectangle intersections.
        for (ty in 0 until targetHeight) {
            val top = ty.toDouble() / targetHeight
            val bottom = (ty + 1.0) / targetHeight
            for (tx in 0 until targetWidth) {
                val target = ty * targetWidth + tx
                targetOffsets[target] = forwardSources.size
                val left = tx.toDouble() / targetWidth
                val right = (tx + 1.0) / targetWidth
                val sy0 = floor(top * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
                val sy1 = ceil(bottom * sourceHeight).toInt().coerceIn(0, sourceHeight)
                val sx0 = floor(left * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
                val sx1 = ceil(right * sourceWidth).toInt().coerceIn(0, sourceWidth)
                for (sy in sy0 until sy1) {
                    val oy = max(0.0, min(bottom, (sy + 1.0) / sourceHeight) - max(top, sy.toDouble() / sourceHeight))
                    if (oy <= 0.0) continue
                    for (sx in sx0 until sx1) {
                        val ox = max(0.0, min(right, (sx + 1.0) / sourceWidth) - max(left, sx.toDouble() / sourceWidth))
                        val weight = ox * oy * targetWidth * targetHeight
                        if (weight <= 0.0) continue
                        forwardSources.add(sy * sourceWidth + sx)
                        forwardWeights.add(weight)
                    }
                }
            }
        }
        targetOffsets[targetCount] = forwardSources.size
        targetSources = forwardSources.toIntArray()
        targetWeights = forwardWeights.toDoubleArray()

        // Source-major incidence for prolongation. Normalized so that each source cell's
        // weights sum to exactly one regardless of floating point overlap slop.
        val sourceMajor = Array(sourceCount) { ArrayList<Pair<Int, Double>>() }
        val bestSourceWeight = DoubleArray(sourceCount) { -1.0 }
        for (target in 0 until targetCount) {
            for (j in targetOffsets[target] until targetOffsets[target + 1]) {
                val source = targetSources[j]
                sourceMajor[source].add(target to targetWeights[j])
                if (targetWeights[j] > bestSourceWeight[source]) {
                    bestSourceWeight[source] = targetWeights[j]
                    targetOfSource[source] = target
                }
            }
        }
        for (source in 0 until sourceCount) {
            sourceOffsets[source] = backwardTargets.size
            val entries = sourceMajor[source]
            val total = entries.sumOf { it.second }
            if (total > 0.0) for ((target, weight) in entries) {
                backwardTargets.add(target)
                backwardWeights.add(weight / total)
            }
        }
        sourceOffsets[sourceCount] = backwardTargets.size
        sourceTargets = backwardTargets.toIntArray()
        sourceWeights = backwardWeights.toDoubleArray()
    }

    /** Area-weighted mean of a source field on the target grid. */
    fun restrict(source: DoubleArray, target: DoubleArray) {
        require(source.size >= sourceCount && target.size == targetCount)
        for (cell in 0 until targetCount) {
            var sum = 0.0
            for (j in targetOffsets[cell] until targetOffsets[cell + 1]) {
                sum += source[targetSources[j]] * targetWeights[j]
            }
            target[cell] = sum
        }
    }

    /** Uniform target values back to source cells, conserving the source-cell total.
     * A source cell that straddles two target cells receives an area-weighted blend. */
    fun prolongUniform(target: DoubleArray, source: DoubleArray) {
        require(target.size == targetCount && source.size >= sourceCount)
        for (cell in 0 until sourceCount) {
            var sum = 0.0
            for (j in sourceOffsets[cell] until sourceOffsets[cell + 1]) {
                sum += target[sourceTargets[j]] * sourceWeights[j]
            }
            source[cell] = sum
        }
    }

    /**
     * Prolong a target field using normalized sub-cell weights. The weight field is
     * area-weighted on the target grid so the source total equals the target total.
     */
    fun prolongWeighted(target: DoubleArray, weight: DoubleArray, source: DoubleArray, scratch: DoubleArray) {
        require(target.size == targetCount && source.size >= sourceCount && weight.size >= sourceCount)
        require(scratch.size >= targetCount)
        for (cell in 0 until targetCount) {
            var sum = 0.0
            for (j in targetOffsets[cell] until targetOffsets[cell + 1]) {
                sum += weight[targetSources[j]] * targetWeights[j]
            }
            scratch[cell] = sum
        }
        for (sourceCell in 0 until sourceCount) {
            var value = 0.0
            for (j in sourceOffsets[sourceCell] until sourceOffsets[sourceCell + 1]) {
                val targetCell = sourceTargets[j]
                val mean = scratch[targetCell]
                val factor = if (mean > 1e-12) weight[sourceCell] / mean else 1.0
                value += target[targetCell] * factor * sourceWeights[j]
            }
            source[sourceCell] = value
        }
    }
}

/**
 * Shared reduced climate mesh. It is deliberately coarse in the horizontal
 * (at most 36x27) while the surface energetics and every physical reservoir stay
 * on their authoritative grids. Climate cells never own water or heat themselves.
 */
internal class ClimateGrid(val fine: DryAtmosphereConfig) {
    val config: DryAtmosphereConfig = fine.copy(
        width = minOf(fine.width, MAX_CLIMATE_WIDTH),
        height = minOf(fine.height, MAX_CLIMATE_HEIGHT)
    )
    val count: Int get() = config.cellCount
    val overlap = GridOverlap(fine.width, fine.height, config.width, config.height)
    val equalsFine: Boolean get() = config.width == fine.width && config.height == fine.height

    fun restrict(source: DoubleArray, target: DoubleArray) = overlap.restrict(source, target)

    fun prolongUniform(target: DoubleArray, source: DoubleArray) = overlap.prolongUniform(target, source)

    companion object {
        const val MAX_CLIMATE_WIDTH = 36
        const val MAX_CLIMATE_HEIGHT = 27
    }
}
