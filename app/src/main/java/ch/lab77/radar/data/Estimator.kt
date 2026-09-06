package ch.lab77.radar.data

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Une observation géolocalisée : où était le téléphone quand il a vu l'émetteur, et à quel niveau. */
data class Obs(val t: Long, val lat: Double, val lon: Double, val acc: Float, val rssi: Int)

/**
 * Estimation de position et statut de persistance d'un émetteur (cahier §3, §3 bis).
 * Position = centroïde des observations pondéré par le signal. Rayon d'incertitude = dispersion pondérée
 * autour du centroïde + précision GPS, jamais en dessous de la distance qu'implique le meilleur signal.
 * Une seule observation = position du téléphone + rayon, jamais un point. Pur Kotlin, testé en CI.
 */
object Estimator {
    const val MAX_OBS = 400

    /** Poids d'une observation : +20 dB = ×10. Un passage près de l'émetteur domine les mesures lointaines. */
    fun weight(rssi: Int): Double = 10.0.pow((rssi.coerceIn(-100, -30) + 100) / 20.0)

    /** Distance minimale plausible d'après le meilleur RSSI (perte en espace libre, exposant 2,7 — ordre de grandeur). */
    fun floorRadius(bestRssi: Int, kind: Kind): Float {
        val ref = if (kind == Kind.WIFI) -40.0 else -55.0     // RSSI typique à 1 m
        return 10.0.pow((ref - bestRssi) / 27.0).toFloat().coerceIn(3f, 300f)
    }

    fun estimate(obs: List<Obs>, kind: Kind, persistence: Persistence): Estimate {
        if (obs.isEmpty()) return Estimate(null, null, 0f, 0, persistence)
        var sw = 0.0; var slat = 0.0; var slon = 0.0
        for (o in obs) { val w = weight(o.rssi); sw += w; slat += w * o.lat; slon += w * o.lon }
        val lat = slat / sw; val lon = slon / sw
        var sd2 = 0.0; var sacc = 0.0
        for (o in obs) { val w = weight(o.rssi); val d = distanceM(lat, lon, o.lat, o.lon); sd2 += w * d * d; sacc += w * o.acc }
        val rms = sqrt(sd2 / sw); val acc = sacc / sw
        val radius = max(rms + acc, floorRadius(obs.maxOf { it.rssi }, kind).toDouble()).toFloat()
        return Estimate(lat, lon, radius, obs.size, persistence)
    }

    /**
     * Statut de persistance. `passant` : vu peu de temps puis disparu. `avec moi` : signal fort et stable
     * alors que le téléphone s'est déplacé. `stationnaire` : vu longtemps depuis plusieurs positions.
     */
    fun persistence(firstSeen: Long, lastSeen: Long, now: Long, obs: List<Obs>): Persistence {
        val span = lastSeen - firstSeen
        val gone = now - lastSeen
        if (gone > 60_000 && span < 120_000) return Persistence.PASSING
        if (obs.size >= 3) {
            val spread = spreadM(obs)
            val rssi = obs.map { it.rssi.toDouble() }
            val mean = rssi.average()
            val std = sqrt(rssi.sumOf { (it - mean) * (it - mean) } / rssi.size)
            if (spread >= 30.0 && std < 4.0 && mean >= -70.0) return Persistence.WITH_ME
            if (span >= 180_000 && spread >= 10.0) return Persistence.STATIONARY
        }
        return Persistence.UNKNOWN
    }

    /** Étendue des positions du téléphone (diagonale de la boîte englobante), en mètres. */
    fun spreadM(obs: List<Obs>): Double {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (o in obs) { if (o.lat < minLat) minLat = o.lat; if (o.lat > maxLat) maxLat = o.lat; if (o.lon < minLon) minLon = o.lon; if (o.lon > maxLon) maxLon = o.lon }
        return distanceM(minLat, minLon, maxLat, maxLon)
    }

    /** Distance équirectangulaire — largement suffisante à l'échelle d'un relevé. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return sqrt(x * x + y * y) * r
    }

}
