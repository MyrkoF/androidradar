package ch.lab77.radar.data

enum class Kind { WIFI, BLE }

/** Catégorie déduite du fabricant (OUI) et des métadonnées publiques. */
enum class Category(val label: String, val priority: Int) {
    CELLULAR_ROUTER("Routeur cellulaire", 5),
    NETWORK_INFRA("Infra réseau", 4),
    CAMERA("Caméra / vidéo", 4),
    INDUSTRIAL("Industriel / IoT pro", 3),
    FLEET("Flotte / télématique", 4),
    CONSUMER("Grand public", 1),
    RANDOMIZED("MAC aléatoire", 0),
    UNKNOWN("Inconnu", 0);

    val isPriority get() = priority >= 3
}

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
) {
    val channel: Int get() = freqToChannel(frequency)
    val band: String get() = when {
        frequency == 0 -> "BLE"
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
    val sessionStart: Long = 0L,
    val alertsOn: Boolean = true,
)
