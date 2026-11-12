package terra.ui

import kotlin.math.floor

/** Viewport-local camera. Small maps stay centered; enlarged maps retain reachable edges. */
internal class MapCamera(
    val viewportWidth: Double, val viewportHeight: Double,
    val mapWidth: Int, val mapHeight: Int
) {
    val fitScale = minOf(viewportWidth / mapWidth, viewportHeight / mapHeight)
    var scale = fitScale
        private set
    var x = 0.0
        private set
    var y = 0.0
        private set

    init {
        require(viewportWidth > 0 && viewportHeight > 0 && mapWidth > 0 && mapHeight > 0)
        home()
    }

    fun home() { scale = fitScale; moveTo(0.0, 0.0) }

    fun moveTo(left: Double, top: Double) {
        fun bound(offset: Double, extent: Double, viewport: Double) =
            if (extent <= viewport) (viewport - extent) / 2 else offset.coerceIn(viewport - extent, 0.0)
        x = bound(left, mapWidth * scale, viewportWidth)
        y = bound(top, mapHeight * scale, viewportHeight)
    }

    fun zoomBy(factor: Double, anchorX: Double, anchorY: Double) {
        require(factor.isFinite() && factor > 0)
        val mapX = (anchorX - x) / scale
        val mapY = (anchorY - y) / scale
        scale = (scale * factor).coerceIn(fitScale * 0.5, fitScale * 32)
        moveTo(anchorX - mapX * scale, anchorY - mapY * scale)
    }

    fun tileAt(screenX: Double, screenY: Double): Pair<Int, Int>? {
        if (screenX !in 0.0..<viewportWidth || screenY !in 0.0..<viewportHeight) return null
        val tileX = floor((screenX - x) / scale).toInt()
        val tileY = floor((screenY - y) / scale).toInt()
        return if (tileX in 0 until mapWidth && tileY in 0 until mapHeight) tileX to tileY else null
    }
}
