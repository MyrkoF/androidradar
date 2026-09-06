package ch.lab77.radar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeFinderTest {
    @Test fun `geometrie de visee`() {
        assertEquals(1.6f, RangeFinder.distanceM(1.6f, -45f)!!, 0.01f)          // 45° vers le bas : distance = hauteur
        assertEquals(1.6f / Math.tan(Math.toRadians(10.0)).toFloat(), RangeFinder.distanceM(1.6f, -10f)!!, 0.01f)
        assertNull(RangeFinder.distanceM(1.6f, 5f))                              // vers le haut : pas de sol visé
        assertNull(RangeFinder.distanceM(1.6f, -0.5f))                           // horizon : infini
        assertNull(RangeFinder.distanceM(1.6f, -89f))                            // à pic
    }
}
