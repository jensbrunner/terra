package terra.render

import korlibs.graphics.*
import korlibs.graphics.shader.*
import korlibs.image.bitmap.Bitmap32
import korlibs.korge.render.*
import korlibs.korge.view.BaseImage
import korlibs.korge.internal.KorgeInternal

/** Mix premultiplied raster samples, then composite ONCE.
 * Fading two source-over images separately makes even unchanged clouds pulse.
 */
@OptIn(KorgeInternal::class)
internal class RasterBlendImage(private val previous: Bitmap32, private val current: Bitmap32) : BaseImage(current) {
    var fraction = 0.0
        set(value) {
            val next = value.coerceIn(0.0, 1.0)
            if (next != field) { field = next; invalidateRender() }
        }

    init {
        require(previous.premultiplied && current.premultiplied)
        require(previous.width == current.width && previous.height == current.height)
        program = BLEND_PROGRAM
    }

    override fun drawVertices(ctx: RenderContext) {
        ctx.useBatcher { batch ->
            if (fraction <= 0.0 || fraction >= 1.0) {
                // Completed/unchanged overlays need one texture, no blend shader
                // or per-draw uniforms. Use the same premultiplied source format.
                batch.drawVertices(vertices, ctx.getTex(if (fraction >= 1.0) current else previous), smoothing, renderBlendMode)
                return@useBatcher
            }
            ctx[BlendUniforms].push { it[u_Fraction] = fraction }
            batch.temporalTextureUnit(previousSampler, ctx.getTex(previous).base,
                AGTextureUnitInfo.DEFAULT.withLinearTrilinear(smoothing, false)) {
                batch.drawVertices(vertices, ctx.getTex(current), smoothing, renderBlendMode,
                    program = BLEND_PROGRAM)
            }
        }
    }

    private object BlendUniforms : UniformBlock(fixedLocation = 5) {
        val u_Fraction by float()
    }

    private companion object {
        val previousSampler = Sampler("u_PreviousRaster", ShaderIndices.SAMPLER_INDEX0, SamplerVarType.Sampler2D)
        val BLEND_PROGRAM = Program(BatchBuilder2D.PROGRAM.vertex, FragmentShaderDefault {
            BatchBuilder2D.createTextureLookup(this)
            SET(out, mix(texture2D(previousSampler, DefaultShaders.v_Tex["xy"]), out, BlendUniforms.u_Fraction))
            SET(out, out * DefaultShaders.v_Col)
        }, name = "Raster premultiplied crossfade")
    }
}
