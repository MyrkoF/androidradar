package ch.lab77.radar.data

/**
 * Le podomètre arbitre le mouvement (retour terrain n°3, #10) : un fix GPS n'est un déplacement que s'il est
 * cohérent avec les pas faits depuis le dernier point accepté. En intérieur le GPS saute de 50–100 m sans
 * qu'on bouge ; ces sauts sont tenus, pas dessinés. Pur Kotlin, testé en CI.
 */
object PositionFilter {
    data class Fix(val lat: Double, val lon: Double, val acc: Float, val t: Long, val estimated: Boolean = false)

    const val METERS_PER_STEP = 1.0
    const val SLACK_M = 3.0
    const val GOOD_ACC_M = 20f
    const val MAX_SPEED_MS = 40.0          // véhicule sur route
    const val WALK_SPEED_MS = 1.5          // repli sans capteur de pas

    /**
     * @param prev dernier point accepté (GPS ou estime), null au départ
     * @param next nouveau fix GPS
     * @param stepsSince pas comptés depuis `prev`
     * @param stepsKnown false si le capteur de pas n'existe pas ou est refusé
     */
    const val STALE_MS = 60_000L
    const val AGREE_M = 50.0
    const val AGREE_COUNT = 3

    /** Fixes fiables rejetés récemment : si plusieurs se confirment entre eux, c'est la position tenue qui est fausse. */
    private val rejectedTrusted = ArrayDeque<Fix>()

    /**
     * Un fix GPS fiable (≥ 4 satellites) rejeté est mis en attente ; dès que AGREE_COUNT fixes consécutifs
     * s'accordent à AGREE_M près, la majorité gagne et le dernier est accepté (réancrage). Retour terrain n°7 :
     * une « dernière position connue » périmée à 1,4 km tenait tête à 11 vrais fixes.
     */
    fun agreeAndReanchor(next: Fix): Boolean {
        rejectedTrusted.addLast(next)
        while (rejectedTrusted.size > AGREE_COUNT) rejectedTrusted.removeFirst()
        if (rejectedTrusted.size < AGREE_COUNT) return false
        val first = rejectedTrusted.first()
        val ok = rejectedTrusted.all { Estimator.distanceM(first.lat, first.lon, it.lat, it.lon) <= AGREE_M }
        if (ok) rejectedTrusted.clear()
        return ok
    }
    fun resetAgreement() = rejectedTrusted.clear()

    fun accept(prev: Fix?, next: Fix, stepsSince: Int, stepsKnown: Boolean): Boolean {
        if (next.acc > 100f) return prev == null            // fix inutilisable, sauf si on n'a rien
        if (prev == null) return true
        if (!next.estimated && next.acc <= 30f && next.t - prev.t > STALE_MS) return true   // rien d'accepté depuis > 60 s : un bon fix réancre
        // Un fix nettement plus précis que ce qu'on tient le remplace toujours — sinon l'estime, dont
        // l'incertitude grandit à chaque pas, rejetterait le GPS qui pourrait la corriger (retour terrain n°4).
        if (prev.estimated && next.acc <= GOOD_ACC_M) return true
        if (next.acc <= 30f && next.acc < prev.acc * 0.7f) return true
        val d = Estimator.distanceM(prev.lat, prev.lon, next.lat, next.lon)
        val dt = (next.t - prev.t).coerceAtLeast(1L) / 1000.0
        val budget = (if (stepsKnown) stepsSince * METERS_PER_STEP else WALK_SPEED_MS * dt) + SLACK_M
        if (d <= budget) return true                          // cohérent avec les pas (ou immobile et petit saut)
        val goodGps = next.acc <= GOOD_ACC_M && prev.acc <= GOOD_ACC_M
        return goodGps && d / dt <= MAX_SPEED_MS              // véhicule / très bon GPS : on suit
    }

    /** En intérieur ou sans fix récent, ce sont les pas qui déplacent la position. */
    fun stepsDrive(accepted: Fix?, now: Long): Boolean =
        accepted == null || now - accepted.t > 20_000 || accepted.acc > 30f
}
