package ch.lab77.radar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceTest {
    private fun dev() = Device(Kind.WIFI, "A", "AP", -60, -60, 2412, "", "", "", Category.ROUTER_AP, 0, 0, 5, null, null, null, null)

    @Test fun `ma position suit l observateur`() {
        assertEquals(Level.NONE, ConfidenceRules.myPosition(ScanStatus()).level)
        assertEquals(Level.SURE, ConfidenceRules.myPosition(ScanStatus(observer = Observer(CalState.CALIBRATED, Env.OUTDOOR, 8f, "GPS 12 sat"))).level)
        assertEquals(Level.APPROX, ConfidenceRules.myPosition(ScanStatus(observer = Observer(CalState.DEGRADED, Env.INDOOR, 9f, "Je suis ici"))).level)
        assertTrue(ConfidenceRules.myPosition(ScanStatus(observer = Observer(CalState.DEGRADED, Env.INDOOR, 9f, "Je suis ici"))).hint.isNotBlank())
    }

    @Test fun `position d un objet`() {
        assertEquals(Level.NONE, ConfidenceRules.objectPosition(dev(), null).level)
        assertEquals(Level.SURE, ConfidenceRules.objectPosition(dev(), Estimate(1.0, 1.0, 5f, 12, Persistence.STATIONARY, locked = true)).level)
        assertEquals(Level.APPROX, ConfidenceRules.objectPosition(dev(), Estimate(1.0, 1.0, 30f, 1, Persistence.UNKNOWN)).level)
        assertTrue(ConfidenceRules.objectPosition(dev(), Estimate(1.0, 1.0, 30f, 1, Persistence.UNKNOWN)).text.contains("seul endroit"))
        assertEquals(Level.NONE, ConfidenceRules.objectPosition(dev(), Estimate(1.0, 1.0, 30f, 5, Persistence.PASSING)).level)
    }
}
