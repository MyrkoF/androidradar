package ch.lab77.radar.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Une observation géolocalisée : où était le téléphone quand il a vu l'émetteur, et à quel niveau. */
data class Obs(
    val t: Long, val lat: Double, val lon: Double, val acc: Float, val rssi: Int,
    val baroAlt: Float? = null,   // altitude barométrique relative du téléphone à cet instant
    val rttM: Float? = null,      // distance mesurée (Wi-Fi RTT) à cet instant, si disponible
)

/**
 * Estimation de position et statut de persistance d'un émetteur (cahier §3, §3 bis, §4 ter).
 * Position = centroïde des observations pondéré par le signal ; si ≥ 3 distances mesurées (RTT) depuis
 * des positions distinctes, trilatération (Gauss-Newton). Rayon d'incertitude = dispersion pondérée
 * + précision GPS, jamais en dessous de la distance qu'implique le meilleur signal.
 * Une seule observation = position du téléphone + rayon, jamais un point. Pur Kotlin, testé en CI.
 */
object Estimator {
    const val MAX_OBS = 400
    const val MIN_MOVE_M = 3.0          // (a) une mesure par endroit
    const val LOCK_MIN_OBS = 8          // (b) verrou
    const val LOCK_MAX_RADIUS_M = 30f
    const val LOCK_RATE = 0.1           // sous verrou, une nouvelle estimation ne déplace que de 10 %
    const val OUTLIER_MIN_OBS = 6       // (c) rejet des aberrantes
    const val OUTLIER_FACTOR = 2.0
    const val ACC_REF_M = 10.0          // (d) confiance au bon GPS

    /** Poids d'une observation : +20 dB = ×10 (un passage près de l'émetteur domine), et moins de poids quand le GPS est imprécis. */
    fun weight(rssi: Int, acc: Float = ACC_REF_M.toFloat()): Double {
        val signal = 10.0.pow((rssi.coerceIn(-100, -30) + 100) / 20.0)
        val gps = (ACC_REF_M / acc.toDouble().coerceAtLeast(ACC_REF_M)).pow(2)
        return signal * gps
    }

    /** (a) Une mesure par endroit : garder la meilleure lecture tant que le téléphone n'a pas bougé de 3 m. */
    fun samePlace(last: Obs?, lat: Double, lon: Double): Boolean =
        last != null && distanceM(last.lat, last.lon, lat, lon) < MIN_MOVE_M

    /** Distance minimale plausible d'après le meilleur signal (perte en espace libre — ordre de grandeur). */
    fun floorRadius(bestRssi: Int, kind: Kind): Float = when (kind) {
        Kind.WIFI -> 10.0.pow((-40.0 - bestRssi) / 27.0).toFloat().coerceIn(3f, 300f)
        Kind.BLE -> 10.0.pow((-55.0 - bestRssi) / 27.0).toFloat().coerceIn(3f, 300f)
        Kind.CELL -> 10.0.pow((-40.0 - bestRssi) / 25.0).toFloat().coerceIn(50f, 5000f)   // RSRP : -90 ≈ 100 m, -110 ≈ 600 m
    }

    /**
     * Estimation complète. `prev` = estimation précédente : si elle est verrouillée (b), la nouvelle ne la
     * déplace que de LOCK_RATE — beaucoup de mesures contraires finissent par la déplacer, une seule non.
     */
    fun estimate(obs: List<Obs>, kind: Kind, persistence: Persistence, prev: Estimate? = null): Estimate {
        if (obs.isEmpty()) return Estimate(null, null, 0f, 0, persistence)
        var fresh = raw(obs, kind, persistence)
        // (c) rejet des aberrantes : recalcul sans les observations à plus de 2× le rayon du barycentre
        if (obs.size >= OUTLIER_MIN_OBS && fresh.lat != null && !fresh.rttFix) {
            val limit = max(fresh.radius.toDouble() * OUTLIER_FACTOR, 20.0)
            val kept = obs.filter { distanceM(fresh.lat!!, fresh.lon!!, it.lat, it.lon) <= limit }
            if (kept.size >= 3 && kept.size < obs.size) fresh = raw(kept, kind, persistence).copy(n = obs.size)
        }
        // (b) verrou
        val lockNow = persistence == Persistence.STATIONARY && fresh.n >= LOCK_MIN_OBS && fresh.radius < LOCK_MAX_RADIUS_M && fresh.lat != null
        if (prev != null && prev.locked && prev.lat != null && fresh.lat != null && !fresh.rttFix) {
            return prev.copy(
                lat = prev.lat + LOCK_RATE * (fresh.lat - prev.lat), lon = prev.lon!! + LOCK_RATE * (fresh.lon!! - prev.lon),
                radius = (prev.radius + LOCK_RATE * (fresh.radius - prev.radius)).toFloat(),
                n = fresh.n, persistence = persistence, altM = fresh.altM ?: prev.altM, locked = true,
            )
        }
        return fresh.copy(locked = lockNow || fresh.rttFix)
    }

    /** Barycentre pondéré (signal × précision GPS), ou trilatération RTT si possible. */
    private fun raw(obs: List<Obs>, kind: Kind, persistence: Persistence): Estimate {
        var sw = 0.0; var slat = 0.0; var slon = 0.0; var salt = 0.0; var swAlt = 0.0
        for (o in obs) {
            val w = weight(o.rssi, o.acc); sw += w; slat += w * o.lat; slon += w * o.lon
            if (o.baroAlt != null) { salt += w * o.baroAlt; swAlt += w }
        }
        val lat = slat / sw; val lon = slon / sw
        val altM = if (swAlt > 0) (salt / swAlt).toFloat() else null
        val tri = trilaterate(obs, lat, lon)
        if (tri != null) {
            return Estimate(tri[0], tri[1], max(tri[2] + 1.0, 2.0).toFloat(), obs.size, persistence, altM, rttFix = true)   // +1 m : écart-type RTT typique
        }
        var sd2 = 0.0; var sacc = 0.0
        for (o in obs) { val w = weight(o.rssi, o.acc); val d = distanceM(lat, lon, o.lat, o.lon); sd2 += w * d * d; sacc += w * o.acc }
        val rms = sqrt(sd2 / sw); val acc = sacc / sw
        val radius = max(rms + acc, floorRadius(obs.maxOf { it.rssi }, kind).toDouble()).toFloat()
        return Estimate(lat, lon, radius, obs.size, persistence, altM)
    }

    /**
     * Trilatération sur les observations portant une distance mesurée : minimise Σ(d_i − rtt_i)² par
     * Gauss-Newton en coordonnées locales (m). Retourne [lat, lon, résidu RMS] ou null si < 3 mesures
     * ou positions du téléphone trop groupées (< 10 m) — dans ce cas la géométrie ne contraint rien.
     */
    fun trilaterate(obs: List<Obs>, lat0: Double, lon0: Double): DoubleArray? {
        val pts = obs.filter { it.rttM != null }
        if (pts.size < 3 || spreadM(pts) < 10.0) return null
        val kx = 111_320.0 * cos(Math.toRadians(lat0)); val ky = 111_320.0
        var x = 0.0; var y = 0.0
        repeat(40) {
            var gx = 0.0; var gy = 0.0; var hxx = 0.0; var hyy = 0.0; var hxy = 0.0
            for (p in pts) {
                val px = (p.lon - lon0) * kx; val py = (p.lat - lat0) * ky
                val dx = x - px; val dy = y - py
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.1)
                val r = d - p.rttM!!
                val jx = dx / d; val jy = dy / d
                gx += jx * r; gy += jy * r
                hxx += jx * jx; hyy += jy * jy; hxy += jx * jy
            }
            val det = hxx * hyy - hxy * hxy
            if (abs(det) < 1e-9) return null
            val sx = (hyy * gx - hxy * gy) / det; val sy = (hxx * gy - hxy * gx) / det
            x -= sx; y -= sy
            if (abs(sx) + abs(sy) < 0.01) return@repeat
        }
        var s2 = 0.0
        for (p in pts) {
            val px = (p.lon - lon0) * kx; val py = (p.lat - lat0) * ky
            val d = sqrt((x - px) * (x - px) + (y - py) * (y - py))
            s2 += (d - p.rttM!!) * (d - p.rttM)
        }
        return doubleArrayOf(lat0 + y / ky, lon0 + x / kx, sqrt(s2 / pts.size))
    }

    /**
     * Statut de persistance. `passant` : vu peu de temps puis disparu. `avec moi` : signal fort et stable
     * alors que le téléphone s'est déplacé. `stationnaire` : vu longtemps depuis plusieurs positions.
     * Une cellule mobile est stationnaire par nature.
     */
    fun persistence(firstSeen: Long, lastSeen: Long, now: Long, obs: List<Obs>, kind: Kind = Kind.WIFI): Persistence {
        if (kind == Kind.CELL) return Persistence.STATIONARY
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

    /** Altitude relative d'après la pression (formule barométrique standard), en mètres, par rapport à p0. */
    fun baroAltitude(pHpa: Float, p0Hpa: Float): Float =
        (44_330.0 * (1.0 - (pHpa / p0Hpa).toDouble().pow(1 / 5.255))).toFloat()

    /** Déplace (lat, lon) de `meters` dans la direction `headingDeg` (cap vrai). */
    fun advance(lat: Double, lon: Double, headingDeg: Float, meters: Double): DoubleArray {
        val rad = Math.toRadians(headingDeg.toDouble())
        val kx = 111_320.0 * cos(Math.toRadians(lat)); val ky = 111_320.0
        return doubleArrayOf(lat + meters * cos(rad) / ky, lon + meters * kotlin.math.sin(rad) / kx)
    }
}
