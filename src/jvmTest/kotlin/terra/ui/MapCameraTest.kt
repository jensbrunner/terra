package terra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCameraTest {
    @Test
    fun fitAndPanKeepTheMapReachableAndOutsideClicksDoNotPickEdgeTiles() {
        val camera = MapCamera(1000.0, 600.0, 200, 200)
        assertEquals(3.0, camera.scale)
        assertEquals(200.0, camera.x)
        assertEquals(0 to 0, camera.tileAt(200.0, 0.0))
        assertNull(camera.tileAt(199.9, 0.0))
        assertNull(camera.tileAt(800.0, 300.0))
        camera.zoomBy(4.0, 500.0, 300.0)
        camera.moveTo(-1e6, 1e6)
        assertEquals(1000.0 - 200 * camera.scale, camera.x)
        assertEquals(0.0, camera.y)
        camera.home()
        assertEquals(3.0, camera.scale)
        assertEquals(200.0, camera.x)
    }

    @Test
    fun zoomKeepsThePointUnderTheCursorUntilTheMapBoundaryAndCapsMagnification() {
        val camera = MapCamera(1000.0, 600.0, 200, 120)
        camera.zoomBy(2.0, 400.0, 250.0)
        val tile = camera.tileAt(400.0, 250.0)
        camera.zoomBy(2.0, 400.0, 250.0)
        assertEquals(tile, camera.tileAt(400.0, 250.0))
        camera.zoomBy(1e9, 400.0, 250.0)
        assertTrue(camera.scale <= camera.fitScale * 32)
        camera.zoomBy(1e-9, 400.0, 250.0)
        assertEquals(camera.fitScale * 0.5, camera.scale)
        assertEquals((1000 - 200 * camera.scale) / 2, camera.x)
    }
}
