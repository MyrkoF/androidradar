package ch.lab77.radar.data

enum class Kind { WIFI, BLE, CELL }

/** Catégorie déduite du fabricant (OUI) et des métadonnées publiques. */
enum class Category(val label: String, val priority: Int) {
    CELLULAR_ROUTER("Routeur cellulaire", 5),
    NETWORK_INFRA("Infra réseau", 4),
    CAMERA("Caméra / vidéo", 4),
    INDUSTRIAL("Industriel / IoT pro", 3),
    FLEET("Flotte / télématique", 4),
    ROUTER_AP("Routeur Wi-Fi / box", 2),
    CONSUMER("Grand public", 1),
    CELL_TOWER("Cellule mobile", 0),
    RANDOMIZED("MAC aléatoire", 0),
    UNKNOWN("Inconnu", 0);

    val isPriority get() = priority >= 3
}

/** Statut de persistance (cahier §3 bis) : ce qui reste fait la carte réelle, le reste est du passage. */
enum class Persistence(val label: String) {
    UNKNOWN("indéterminé"), STATIONARY("stationnaire"), PASSING("passant"), WITH_ME("avec moi");
    val onMapByDefault get() = this == STATIONARY || this == UNKNOWN
}

/** Position estimée d'un émetteur : centroïde pondéré + rayon d'incertitude calculé (cahier §3). lat/lon null = jamais géolocalisé. */
data class Estimate(
    val lat: Double?, val lon: Double?, val radius: Float, val n: Int, val persistence: Persistence,
    val altM: Float? = null,        // altitude barométrique relative au départ de session (étage probable)
    val rttFix: Boolean = false,    // position obtenue par trilatération de distances mesurées (Wi-Fi RTT)
    val locked: Boolean = false,    // 🔒 position figée : stationnaire, assez d'observations, rayon petit (cahier §3 ter)
    val bearingFix: Boolean = false, // △ position obtenue par triangulation de directions (guide de marche, #12)
)

data class Device(
    val kind: Kind,
    val id: String,                 // BSSID ou adresse BLE, majuscules
    val name: String,               // SSID ou nom BLE ("" si caché / absent)
    val rssi: Int,
    val bestRssi: Int,
    val frequency: Int,             // MHz (Wi-Fi), 0 pour BLE
    val capabilities: String,       // chaîne Android (Wi-Fi) ou résumé BLE
    val vendor: String,
    val vendorLong: String,
    val category: Category,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Int,
    val lat: Double?,
    val lon: Double?,
    val altitude: Double?,
    val accuracy: Float?,
    val rttM: Float? = null,            // distance mesurée Wi-Fi RTT (802.11mc/az), null si non mesurée
    val rttStdM: Float? = null,
    val rttCapable: Boolean = false,
    val wifiStandard: String = "",      // Wi-Fi 4/5/6/7 d'après ScanResult.wifiStandard
) {
    val channel: Int get() = if (kind == Kind.WIFI) freqToChannel(frequency) else 0
    val band: String get() = when {
        kind == Kind.BLE -> "BLE"
        kind == Kind.CELL -> capabilities.substringAfter("tech=", "cell").substringBefore(' ')
        frequency < 3000 -> "2.4 GHz"
        frequency < 5900 -> "5 GHz"
        else -> "6 GHz"
    }
    val security: String get() = when {
        kind == Kind.BLE -> "-"
        capabilities.contains("WPA3") || capabilities.contains("SAE") -> "WPA3"
        capabilities.contains("WPA2") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        else -> "OUVERT"
    }
    val ageMs: Long get() = System.currentTimeMillis() - lastSeen
}

fun freqToChannel(freq: Int): Int = when {
    freq == 0 -> 0
    freq == 2484 -> 14
    freq in 2412..2472 -> (freq - 2407) / 5
    freq in 5170..5825 -> (freq - 5000) / 5
    freq in 5935..7115 -> (freq - 5950) / 5
    else -> 0
}

data class ScanStatus(
    val wifiOn: Boolean = false,
    val bleOn: Boolean = false,
    val wifiThrottled: Boolean = false,
    val wifiScans: Int = 0,
    val gpsFix: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val satsVisible: Int = 0,       // satellites GNSS vus / utilisés dans le fix
    val satsUsed: Int = 0,
    val cellOn: Boolean = false,
    val heading: Float? = null,     // cap vrai en degrés (boussole), null si pas de capteur
    val pitch: Float? = null,       // élévation de l'axe de la caméra en degrés (négatif = vers le bas), télémètre par visée
    val pressureHpa: Float? = null,
    val baroAltM: Float? = null,    // altitude barométrique relative au départ de session
    val deadReckoning: Boolean = false,   // position actuelle = estime (pas + cap), pas un fix GPS
    val gpsHeld: Boolean = false,         // dernier fix GPS rejeté (saut sans pas) : position tenue
    val stepsKnown: Boolean = false,      // capteur de pas disponible et autorisé
    val steps: Int = 0,
    val sensors: String = "",       // disponibilité des capteurs, pour l'écran Session
    val sessionStart: Long = 0L,
    val sessionId: Long = 0L,
    val alertsOn: Boolean = true,
)

/** Une session en base (un lieu + une date). */
data class SessionInfo(val id: Long, val name: String, val start: Long, val end: Long?, val positioned: Int)

/** Estimation persistée d'une session, avec ce qu'il faut pour un diff lisible. */
data class EstRow(
    val id: String, val lat: Double, val lon: Double, val radius: Float, val n: Int, val persistence: Persistence,
    val name: String, val security: String, val kind: Kind, val category: Category, val confirmed: Boolean,
)

enum class DiffKind(val label: String) { NEW("nouveau"), GONE("disparu"), MOVED("déplacé"), CHANGED("modifié") }

data class DiffEntry(val kind: DiffKind, val id: String, val name: String, val category: Category, val detail: String)
