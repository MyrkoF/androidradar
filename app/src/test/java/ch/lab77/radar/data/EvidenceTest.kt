package ch.lab77.radar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceTest {
    private val lat0 = 20.6615; private val lon0 = -87.0466
    private fun at(dN: Double, dE: Double, acc: Float = 5f) = Obs(0, lat0 + dN / 111_320.0, lon0 + dE / (111_320.0 * Math.cos(Math.toRadians(lat0))), acc, -60)
    private val e = Estimate(lat0, lon0, 20f, 5, Persistence.STATIONARY)

    @Test fun `points de vue et couverture`() {
        val obs = listOf(at(30.0, 0.0), at(31.0, 1.0), at(0.0, 30.0), at(-30.0, 0.0, acc = 40f))   // nord ×2 (même endroit), est, sud (mauvaise qualité)
        val ev = EvidenceRules.of(obs, e, listOf(60f, 20f))
        assertEquals(2, ev.viewpoints)
        assertEquals(2, ev.sectors.size)
        assertEquals(60, ev.coverageDeg)
        assertTrue(ev.nextMove.contains("aller vers"))
    }

    @Test fun `un seul endroit et bonne couverture`() {
        assertTrue(EvidenceRules.of(listOf(at(10.0, 0.0)), e, emptyList()).nextMove.contains("seul endroit"))
        val around = listOf(at(30.0, 0.0), at(0.0, 30.0), at(-30.0, 0.0), at(0.0, -30.0))
        val ev = EvidenceRules.of(around, e.copy(radius = 8f), emptyList())
        assertEquals(4, ev.viewpoints); assertTrue(ev.nextMove.contains("solide"))
        assertEquals("nord", EvidenceRules.compass(0.0)); assertEquals("est", EvidenceRules.compass(90.0)); assertEquals("sud-ouest", EvidenceRules.compass(225.0))
    }
}
