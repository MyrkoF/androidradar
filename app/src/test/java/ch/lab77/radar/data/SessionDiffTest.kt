package ch.lab77.radar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDiffTest {
    private val lat0 = 20.6615; private val lon0 = -87.0466
    private fun row(id: String, dEast: Double, r: Float = 15f, name: String = "AP", sec: String = "WPA2", confirmed: Boolean = true) =
        EstRow(id, lat0, lon0 + dEast / (111_320.0 * Math.cos(Math.toRadians(lat0))), r, 10, Persistence.STATIONARY, name, sec, Kind.WIFI, Category.ROUTER_AP, confirmed)

    @Test fun `nouveau disparu deplace modifie`() {
        val before = mapOf("A" to row("A", 0.0), "B" to row("B", 0.0), "C" to row("C", 0.0), "D" to row("D", 0.0, sec = "WPA2"))
        val after = mapOf("A" to row("A", 0.0), "C" to row("C", 100.0), "D" to row("D", 5.0, sec = "OUVERT"), "E" to row("E", 0.0))
        val d = SessionDiff.diff(before, after)
        assertEquals(listOf(DiffKind.NEW, DiffKind.GONE, DiffKind.MOVED, DiffKind.CHANGED), d.map { it.kind })
        assertEquals("E", d[0].id); assertEquals("B", d[1].id); assertEquals("C", d[2].id); assertEquals("D", d[3].id)
        assertTrue(d[3].detail.contains("OUVERT"))
    }

    @Test fun `un deplacement dans l incertitude n est pas un deplacement`() {
        val d = SessionDiff.diff(mapOf("A" to row("A", 0.0, r = 20f)), mapOf("A" to row("A", 25.0, r = 20f)))
        assertTrue(d.isEmpty())
    }

    @Test fun `les non confirmes sont ignores`() {
        val d = SessionDiff.diff(emptyMap(), mapOf("P" to row("P", 0.0, confirmed = false)))
        assertTrue(d.isEmpty())
        assertTrue(SessionDiff.text("a", "b", d, 0, 0).contains("Aucune différence"))
    }
}
