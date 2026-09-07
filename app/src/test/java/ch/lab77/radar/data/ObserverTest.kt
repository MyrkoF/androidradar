package ch.lab77.radar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverTest {
    private val lat0 = 20.6615; private val lon0 = -87.0466
    private fun fix(dEast: Double, acc: Float, t: Long) = PositionFilter.Fix(lat0, lon0 + dEast / (111_320.0 * Math.cos(Math.toRadians(lat0))), acc, t)

    @Test fun `calibration exterieure`() {
        val ok = listOf(fix(0.0, 8f, 0), fix(3.0, 6f, 7_000), fix(5.0, 9f, 14_000))
        assertTrue(ObserverRules.outdoorCalibrated(ok, 15_000))
        assertFalse(ObserverRules.outdoorCalibrated(ok.take(2), 15_000))                                   // pas assez de fixes
        assertFalse(ObserverRules.outdoorCalibrated(ok + fix(40.0, 8f, 16_000), 16_000))                   // dispersés
        assertFalse(ObserverRules.outdoorCalibrated(listOf(fix(0.0, 20f, 0), fix(1.0, 20f, 5_000), fix(2.0, 20f, 10_000)), 11_000))   // trop imprécis
        assertFalse(ObserverRules.outdoorCalibrated(ok, 60_000))                                           // trop vieux
    }

    @Test fun `budget et etat`() {
        assertEquals(CalState.UNCALIBRATED, ObserverRules.state(null, 3f, Env.INDOOR))
        assertEquals(CalState.CALIBRATED, ObserverRules.state(3f, 4.5f, Env.INDOOR))
        assertEquals(CalState.DEGRADED, ObserverRules.state(3f, 5.5f, Env.INDOOR))
        assertEquals(CalState.CALIBRATED, ObserverRules.state(8f, 9.5f, Env.OUTDOOR))
        // 3 m d'ancre + 20 pas + 15 m marchés ≈ 3 + 2 + 0,9 = 5,9 m → perdu dedans, ok dehors
        val acc = ObserverRules.currentAcc(3f, 20, 15.0)
        assertEquals(5.9f, acc, 0.05f)
        assertEquals(CalState.DEGRADED, ObserverRules.state(3f, acc, Env.INDOOR))
        assertEquals(CalState.CALIBRATED, ObserverRules.state(3f, acc, Env.OUTDOOR))
    }

    @Test fun `environnement`() {
        assertEquals(Env.OUTDOOR, ObserverRules.env(EnvMode.AUTO, 90_000, 100_000))
        assertEquals(Env.INDOOR, ObserverRules.env(EnvMode.AUTO, 10_000, 100_000))
        assertEquals(Env.INDOOR, ObserverRules.env(EnvMode.INDOOR, 99_000, 100_000))
        assertTrue(ObserverRules.describe(Observer()).second.isNotBlank())
        assertTrue(ObserverRules.describe(Observer(CalState.CALIBRATED, Env.OUTDOOR, 8f, "GPS")).second.isBlank())
    }
}
