package ch.lab77.radar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatorTest {
    private val lat0 = 20.6615; private val lon0 = -87.0466
    private fun at(dNorthM: Double, dEastM: Double, rssi: Int, t: Long = 0L, acc: Float = 5f) =
        Obs(t, lat0 + dNorthM / 111_320.0, lon0 + dEastM / (111_320.0 * Math.cos(Math.toRadians(lat0))), acc, rssi)

    @Test fun `une observation = position du telephone plus un rayon`() {
        val e = Estimator.estimate(listOf(at(0.0, 0.0, -60)), Kind.WIFI, Persistence.UNKNOWN)
        assertEquals(lat0, e.lat!!, 1e-9); assertEquals(lon0, e.lon!!, 1e-9)
        assertTrue("rayon ${e.radius}", e.radius >= Estimator.floorRadius(-60, Kind.WIFI))
        assertEquals(1, e.n)
    }

    @Test fun `deux observations symetriques = milieu`() {
        val e = Estimator.estimate(listOf(at(0.0, -20.0, -70), at(0.0, 20.0, -70)), Kind.WIFI, Persistence.UNKNOWN)
        assertEquals(lon0, e.lon!!, 1e-6)
        assertTrue("rayon ${e.radius} doit couvrir la dispersion", e.radius >= 20f)
    }

    @Test fun `le signal fort tire le centroide`() {
        val e = Estimator.estimate(listOf(at(0.0, -20.0, -50), at(0.0, 20.0, -90)), Kind.WIFI, Persistence.UNKNOWN)
        assertTrue("centroïde côté signal fort", e.lon!! < lon0)
    }

    @Test fun `distance plancher decroit avec le signal`() {
        assertTrue(Estimator.floorRadius(-40, Kind.WIFI) < Estimator.floorRadius(-70, Kind.WIFI))
        assertTrue(Estimator.floorRadius(-70, Kind.WIFI) < Estimator.floorRadius(-90, Kind.WIFI))
        assertEquals(1000.0, Estimator.distanceM(lat0, lon0, lat0 + 1000.0 / 111_320.0, lon0), 2.0)
    }

    @Test fun `trilateration sur distances mesurees`() {
        // cible à (0, 0) local ; observateurs à (-30 E), (+30 N), (+40 E) avec distances exactes
        val obs = listOf(
            Obs(0, lat0, lon0 - 30.0 / (111_320.0 * Math.cos(Math.toRadians(lat0))), 5f, -60, rttM = 30f),
            Obs(1, lat0 + 30.0 / 111_320.0, lon0, 5f, -60, rttM = 30f),
            Obs(2, lat0, lon0 + 40.0 / (111_320.0 * Math.cos(Math.toRadians(lat0))), 5f, -60, rttM = 40f),
        )
        val e = Estimator.estimate(obs, Kind.WIFI, Persistence.UNKNOWN)
        assertTrue(e.rttFix)
        assertEquals(0.0, Estimator.distanceM(lat0, lon0, e.lat!!, e.lon!!), 1.0)
        assertTrue("rayon ${e.radius}", e.radius < 5f)
        // deux mesures seulement : pas de trilatération
        assertTrue(!Estimator.estimate(obs.take(2), Kind.WIFI, Persistence.UNKNOWN).rttFix)
    }

    @Test fun `barometre et estime`() {
        assertEquals(0f, Estimator.baroAltitude(1013.25f, 1013.25f), 0.01f)
        assertTrue(Estimator.baroAltitude(1012.0f, 1013.25f) in 9f..12f)     // ~1 hPa ≈ 8–9 m… 1,25 hPa ≈ 10 m
        val p = Estimator.advance(lat0, lon0, 90f, 100.0)
        assertEquals(100.0, Estimator.distanceM(lat0, lon0, p[0], p[1]), 0.5)
        assertTrue(p[1] > lon0)
        assertEquals(Persistence.STATIONARY, Estimator.persistence(0, 0, 0, emptyList(), Kind.CELL))
    }

    @Test fun `persistance`() {
        val m = 60_000L
        // vu 30 s puis disparu depuis 2 min → passant
        assertEquals(Persistence.PASSING, Estimator.persistence(0, 30_000, 3 * m, listOf(at(0.0, 0.0, -70))))
        // vu 4 min depuis 3 positions étalées, signal variable → stationnaire
        val stat = listOf(at(0.0, 0.0, -50, 0), at(0.0, 40.0, -70, 2 * m), at(0.0, 80.0, -85, 4 * m))
        assertEquals(Persistence.STATIONARY, Estimator.persistence(0, 4 * m, 4 * m, stat))
        // téléphone déplacé de 100 m, signal fort et stable → avec moi
        val mine = listOf(at(0.0, 0.0, -50, 0), at(0.0, 50.0, -52, m), at(0.0, 100.0, -51, 2 * m))
        assertEquals(Persistence.WITH_ME, Estimator.persistence(0, 2 * m, 2 * m, mine))
        // une seule mesure récente → indéterminé
        assertEquals(Persistence.UNKNOWN, Estimator.persistence(0, 10_000, 20_000, listOf(at(0.0, 0.0, -70))))
    }
}
