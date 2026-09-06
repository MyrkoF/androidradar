package ch.lab77.radar.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionFilterTest {
    private val lat0 = 20.6615; private val lon0 = -87.0466
    private fun fix(dEastM: Double, acc: Float, t: Long) =
        PositionFilter.Fix(lat0, lon0 + dEastM / (111_320.0 * Math.cos(Math.toRadians(lat0))), acc, t)

    @Test fun `saut GPS sans pas en interieur = bruit`() {
        val prev = fix(0.0, 45f, 0)
        assertFalse(PositionFilter.accept(prev, fix(60.0, 50f, 10_000), stepsSince = 0, stepsKnown = true))
        assertFalse(PositionFilter.accept(prev, fix(60.0, 50f, 10_000), stepsSince = 10, stepsKnown = true))   // 10 pas ≠ 60 m
    }

    @Test fun `marche coherente avec les pas`() {
        val prev = fix(0.0, 30f, 0)
        assertTrue(PositionFilter.accept(prev, fix(12.0, 30f, 10_000), stepsSince = 10, stepsKnown = true))
        assertTrue(PositionFilter.accept(prev, fix(2.0, 30f, 10_000), stepsSince = 0, stepsKnown = true))       // petit saut ≤ 3 m
    }

    @Test fun `vehicule avec bon GPS suivi sans pas`() {
        val prev = fix(0.0, 8f, 0)
        assertTrue(PositionFilter.accept(prev, fix(150.0, 10f, 10_000), stepsSince = 0, stepsKnown = true))
        assertFalse(PositionFilter.accept(prev, fix(150.0, 35f, 10_000), stepsSince = 0, stepsKnown = true))   // GPS moyen : non
        assertFalse(PositionFilter.accept(prev, fix(1500.0, 10f, 10_000), stepsSince = 0, stepsKnown = true))  // 150 m/s : non
    }

    @Test fun `sans capteur de pas budget temporel`() {
        val prev = fix(0.0, 30f, 0)
        assertTrue(PositionFilter.accept(prev, fix(12.0, 30f, 10_000), stepsSince = 0, stepsKnown = false))    // 15 m + 3 m
        assertFalse(PositionFilter.accept(prev, fix(60.0, 30f, 10_000), stepsSince = 0, stepsKnown = false))
    }

    @Test fun `un bon GPS reprend toujours la main sur l estime`() {
        // capture du 07/09 : estime dérivée à ±97 m, 19 satellites, GPS rejeté faute de pas
        val drift = PositionFilter.Fix(lat0, lon0, 97f, 0, estimated = true)
        assertTrue(PositionFilter.accept(drift, fix(60.0, 8f, 10_000), stepsSince = 3, stepsKnown = true))
        // position tenue imprécise (45 m) puis fix précis à 60 m : la précédente était fausse, on prend
        assertTrue(PositionFilter.accept(fix(0.0, 45f, 0), fix(60.0, 12f, 10_000), stepsSince = 0, stepsKnown = true))
        // mais un fix aussi imprécis que le précédent, sans pas, reste rejeté
        assertFalse(PositionFilter.accept(fix(0.0, 45f, 0), fix(60.0, 40f, 10_000), stepsSince = 0, stepsKnown = true))
    }

    @Test fun `majorite de fixes frais contre une position tenue fausse`() {
        // retour terrain n°7 : position tenue précise mais à 1,4 km ; trois vrais fixes d'accord entre eux
        PositionFilter.resetAgreement()
        val held = fix(0.0, 16f, 0)
        val far1 = fix(1420.0, 16f, 5_000); val far2 = fix(1425.0, 20f, 10_000); val far3 = fix(1418.0, 18f, 15_000)
        assertFalse(PositionFilter.accept(held, far1, 3, true))
        assertFalse(PositionFilter.agreeAndReanchor(far1))
        assertFalse(PositionFilter.agreeAndReanchor(far2))
        assertTrue(PositionFilter.agreeAndReanchor(far3))
        // des fixes qui ne s'accordent pas entre eux ne réancrent pas
        PositionFilter.resetAgreement()
        assertFalse(PositionFilter.agreeAndReanchor(fix(1000.0, 16f, 0)))
        assertFalse(PositionFilter.agreeAndReanchor(fix(2000.0, 16f, 5_000)))
        assertFalse(PositionFilter.agreeAndReanchor(fix(3000.0, 16f, 10_000)))
        // rien d'accepté depuis > 60 s : un bon fix réancre directement
        assertTrue(PositionFilter.accept(held, fix(1420.0, 16f, 70_000), 3, true))
    }

    @Test fun `premier fix et fix inutilisable`() {
        assertTrue(PositionFilter.accept(null, fix(0.0, 30f, 0), 0, true))
        assertFalse(PositionFilter.accept(fix(0.0, 30f, 0), fix(0.0, 150f, 5_000), 0, true))
        assertTrue(PositionFilter.stepsDrive(fix(0.0, 45f, 0), 5_000))
        assertFalse(PositionFilter.stepsDrive(fix(0.0, 10f, 0), 5_000))
        assertTrue(PositionFilter.stepsDrive(fix(0.0, 10f, 0), 30_000))
    }
}
