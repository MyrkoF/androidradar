package ch.lab77.radar.data

/**
 * Niveau de confiance en clair (retour Myrko n°11 : « rien ne me dit si ma position est sûre, ni si un objet
 * est vraiment au bon endroit »). Trois niveaux, une phrase, et le geste qui améliore. Pur, testé.
 */
enum class Level { SURE, APPROX, NONE }

data class Confidence(val level: Level, val text: String, val hint: String)

object ConfidenceRules {
    fun myPosition(st: ScanStatus): Confidence {
        val o = st.observer
        val (text, hint) = ObserverRules.describe(o)
        return when (o.state) {
            CalState.CALIBRATED -> Confidence(Level.SURE, text, hint)
            CalState.DEGRADED -> Confidence(Level.APPROX, text, hint)
            CalState.UNCALIBRATED -> Confidence(Level.NONE, text, hint)
        }
    }

    @Suppress("unused")
    private fun legacyMyPosition(st: ScanStatus): Confidence {
        val acc = st.accuracy?.toInt()
        return when {
            st.lat == null -> Confidence(Level.NONE, "Position inconnue", "Dehors : attendre le GPS. Dedans : appui long sur la carte → « Je suis ici ».")
            st.deadReckoning -> Confidence(Level.APPROX, "Position approximative ±${acc ?: "?"} m — à l'estime (pas + boussole)", "Un bon fix GPS (≥ 4 satellites) ou « Je suis ici » la remet d'aplomb.")
            st.gpsHeld -> Confidence(Level.APPROX, "Position approximative ±${acc ?: "?"} m — GPS incohérent, position tenue", "Marcher un peu : les pas confirment le mouvement ; dehors, dégager le ciel.")
            st.gpsFix && (acc ?: 999) <= 15 && st.satsUsed >= 4 -> Confidence(Level.SURE, "Position sûre — GPS ±$acc m, ${st.satsUsed} satellites", "")
            st.gpsFix && (acc ?: 999) <= 40 -> Confidence(Level.APPROX, "Position approximative — GPS ±$acc m, ${st.satsUsed} satellites", "Ciel dégagé et 30 s d'attente améliorent le fix.")
            else -> Confidence(Level.APPROX, "Position approximative ±${acc ?: "?"} m — GPS faible", "Dedans le GPS ne passe pas : « Je suis ici » puis marcher (pas + boussole).")
        }
    }

    fun objectPosition(d: Device, e: Estimate?): Confidence {
        if (e?.lat == null) return Confidence(Level.NONE, "Pas positionné", "Il a été vu sans position GPS. Dehors : refaire un passage ; dedans : « Je suis ici » puis se déplacer.")
        val r = e.radius.toInt()
        val how = when { e.rttFix -> "distance mesurée"; e.bearingFix -> "triangulé △"; e.locked -> "figé 🔒"; else -> "" }
        return when {
            (e.locked || e.rttFix || e.bearingFix) && e.radius <= 25f -> Confidence(Level.SURE, "Position confirmée ±$r m ($how)", "")
            e.persistence == Persistence.STATIONARY && e.radius <= 40f -> Confidence(Level.APPROX, "Position probable ±$r m — stationnaire, ${e.n} mesures", "Un tour sur soi-même depuis deux endroits (△) ou « ◎ Viser » le confirme.")
            e.n <= 2 -> Confidence(Level.APPROX, "Position approximative ±$r m — vu depuis un seul endroit (posé sur toi)", "Marcher autour : il faut le voir depuis plusieurs directions.")
            e.persistence == Persistence.PASSING -> Confidence(Level.NONE, "Passant — vu brièvement, disparu", "Rien à faire : il n'est pas du lieu.")
            e.persistence == Persistence.WITH_ME -> Confidence(Level.NONE, "Avec toi — bouge quand tu bouges", "C'est dans ta poche ou sur toi : pas un objet du lieu.")
            else -> Confidence(Level.APPROX, "Position approximative ±$r m — ${e.n} mesures", "Marcher autour, ou « ◎ Viser » depuis deux endroits.")
        }
    }
}
