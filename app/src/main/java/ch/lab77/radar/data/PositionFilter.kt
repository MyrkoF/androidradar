package ch.lab77.radar.data

/**
 * Le podomètre arbitre le mouvement (retour terrain n°3, #10) : un fix GPS n'est un déplacement que s'il est
 * cohérent avec les pas faits depuis le dernier point accepté. En intérieur le GPS saute de 50–100 m sans
 * qu'on bouge ; ces sauts sont tenus, pas dessinés. Pur Kotlin, testé en CI.
 */
object PositionFilter {
    data class Fix(val lat: Double, val lon: Double, val acc: Float, val t: Long)

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
    fun accept(prev: Fix?, next: Fix, stepsSince: Int, stepsKnown: Boolean): Boolean {
        if (next.acc > 100f) return prev == null            // fix inutilisable, sauf si on n'a rien
        if (prev == null) return true
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
