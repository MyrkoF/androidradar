package ch.lab77.radar.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Preuves par objet (cahier §3 quater) : de combien de points de vue de qualité il a été vu, quelle
 * couverture angulaire autour de sa position estimée, comment son rayon a évolué, et le prochain geste.
 * Pur, testé.
 */
data class Evidence(
    val viewpoints: Int,          // endroits distincts (à 3 m près) d'où il a été vu avec une bonne position
    val sectors: Set<Int>,        // secteurs de 30° (0 = nord) depuis lesquels il a été vu
    val coverageDeg: Int,         // sectors.size × 30
    val radiusHistory: List<Float>,
    val nextMove: String,
)

object EvidenceRules {
    const val VIEWPOINT_M = 3.0
    private val names = listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest")

    fun bearingDeg(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val dLon = Math.toRadians(toLon - fromLon)
        val y = sin(dLon) * cos(Math.toRadians(toLat))
        val x = cos(Math.toRadians(fromLat)) * sin(Math.toRadians(toLat)) - sin(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun compass(deg: Double) = names[((deg + 22.5) % 360 / 45).toInt() % 8]

    fun of(obs: List<Obs>, e: Estimate?, radiusHistory: List<Float>): Evidence {
        val good = obs.filter { it.acc <= ObserverRules.OUTDOOR_M }
        // points de vue distincts
        val vp = ArrayList<Obs>()
        for (o in good) if (vp.none { Estimator.distanceM(it.lat, it.lon, o.lat, o.lon) < VIEWPOINT_M }) vp += o
        val sectors = HashSet<Int>()
        if (e?.lat != null && e.lon != null) for (o in vp) {
            if (Estimator.distanceM(e.lat, e.lon, o.lat, o.lon) < 1.0) continue
            sectors += (bearingDeg(e.lat, e.lon, o.lat, o.lon) / 30).toInt() % 12   // direction de l'observateur vue depuis l'objet
        }
        val next = when {
            e?.lat == null -> "Pas encore positionné : calibrer la position, puis passer près de lui."
            vp.isEmpty() -> "Aucune observation de qualité : recalibrer, puis repasser près de lui."
            vp.size == 1 -> "Vu depuis un seul endroit (posé sur toi) : s'éloigner de 10 m et le revoir, puis d'un autre côté."
            sectors.size < 4 -> {
                val missing = (0 until 12).filter { it !in sectors }
                val target = missing.minByOrNull { m -> sectors.minOf { s -> val d = kotlin.math.abs(m - s) % 12; kotlin.math.min(d, 12 - d) }.let { -it } } ?: 0
                "Vu depuis ${sectors.size} côté(s) : aller vers le ${compass(target * 30.0 + 15)} de l'objet."
            }
            e.radius > 15f -> "Bonne couverture, rayon encore ${e.radius.toInt()} m : s'approcher (signal fort = poids fort) ou « ◎ Viser »."
            else -> "Couverture et rayon bons : position solide."
        }
        return Evidence(vp.size, sectors, sectors.size * 30, radiusHistory, next)
    }
}
