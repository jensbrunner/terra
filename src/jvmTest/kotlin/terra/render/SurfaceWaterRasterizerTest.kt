package terra.render

import kotlin.test.*
import terra.hydrology.SurfaceWaterGeography
import terra.hydrology.SurfaceWaterSnapshot

class SurfaceWaterRasterizerTest {
    @Test fun riverCrossesBothSeamsAtTheCornerWithoutDrawingAcrossTheMap() {
        val geography = SurfaceWaterGeography(5, 5, BooleanArray(25) { it != 24 },
            IntArray(25) { if (it == 0) 24 else -1 }, IntArray(25) { 20 },
            BooleanArray(25), IntArray(25) { -1 })
        val water = SurfaceWaterSnapshot(geography, BooleanArray(25), DoubleArray(25) { if (it == 0) 1.0 else 0.0 }, 0)
        val frame = SurfaceWaterRasterizer(5, 5).render(water)
        val pixels = IntArray(frame.width * frame.height)
        frame.copyInto(pixels)
        fun wet(x: Int, y: Int) = pixels[y * frame.width + x] ushr 24 > 0
        assertTrue((0 until frame.width).any { wet(it, 0) })
        assertTrue((0 until frame.width).any { wet(it, frame.height - 1) })
        assertTrue((0 until frame.height).any { wet(0, it) })
        assertTrue((0 until frame.height).any { wet(frame.width - 1, it) })
        assertFalse((0 until frame.width).any { wet(it, frame.height / 2) })
    }
}
