package terra.render

import korlibs.image.color.RGBA

/** Completed CPU raster. Ownership of pixels transfers here; callers can only read or copy. */
class RasterFrame internal constructor(val width: Int, val height: Int, private val pixels: IntArray) {
    operator fun get(x: Int, y: Int): RGBA = RGBA(pixels[y * width + x])
    fun copyInto(destination: IntArray) { require(destination.size == pixels.size); pixels.copyInto(destination) }
}

/** Worker-only staging buffer. Never reused after finish(). */
internal class RasterCanvas(val width: Int, val height: Int) {
    private val pixels = IntArray(width * height)
    operator fun set(x: Int, y: Int, color: RGBA) { pixels[y * width + x] = color.value }
    fun finish() = RasterFrame(width, height, pixels)
}
