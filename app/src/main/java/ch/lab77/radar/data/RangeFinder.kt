package ch.lab77.radar.data

import kotlin.math.tan

/**
 * Télémètre par visée (#16, #18) : téléphone à hauteur des yeux, on vise le PIED de l'objet ;
 * distance horizontale = hauteur des yeux ÷ tan(angle vers le bas). Trigonométrie pure, ± 15 %.
 * Retourne null si on ne vise pas vers le bas (l'objet serait « à l'infini ») ou trop à pic (< 1 m).
 */
object RangeFinder {
    const val REL_ERROR = 0.15f

    fun distanceM(eyeHeightM: Float, pitchDeg: Float): Float? {
        if (pitchDeg >= -2f || pitchDeg < -85f) return null
        val d = eyeHeightM / tan(Math.toRadians(-pitchDeg.toDouble())).toFloat()
        return if (d.isFinite() && d >= 0.3f && d <= 500f) d else null
    }
}
