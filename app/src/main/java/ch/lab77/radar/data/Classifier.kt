package ch.lab77.radar.data

/**
 * Classification par nom de fabricant (nom long de la table OUI).
 *
 * Règles : une ligne = un fabricant = UNE catégorie. Correspondance sur mot entier, insensible à la casse,
 * première règle qui matche. Un libellé plus spécifique doit précéder le libellé générique qu'il contient
 * (« teltonika telematics » avant « teltonika »), sinon il est mort — `ruleProblems()` le détecte et le
 * test unitaire `ClassifierTest` le fait échouer en CI. Enrichir au fil des relevés terrain.
 */
object Classifier {
    private val rules: List<Pair<String, Category>> = listOf(
        // --- Routeurs cellulaires (priorité 5) ---
        "cradlepoint" to Category.CELLULAR_ROUTER,
        "sierra wireless" to Category.CELLULAR_ROUTER,
        "teltonika telematics" to Category.FLEET,            // trackers FMB — avant le générique
        "teltonika networks" to Category.CELLULAR_ROUTER,    // routeurs RUT
        "teltonika" to Category.CELLULAR_ROUTER,
        "peplink" to Category.CELLULAR_ROUTER,
        "pepwave" to Category.CELLULAR_ROUTER,
        "inseego" to Category.CELLULAR_ROUTER,
        "novatel wireless" to Category.CELLULAR_ROUTER,
        "digi international" to Category.CELLULAR_ROUTER,
        "robustel" to Category.CELLULAR_ROUTER,
        "inhand" to Category.CELLULAR_ROUTER,
        "four-faith" to Category.CELLULAR_ROUTER,
        "quectel" to Category.CELLULAR_ROUTER,
        "telit" to Category.CELLULAR_ROUTER,
        "fibocom" to Category.CELLULAR_ROUTER,
        "space exploration technologies" to Category.CELLULAR_ROUTER,   // Starlink
        "jaguar network" to Category.CELLULAR_ROUTER,

        // --- Caméras / vidéo (priorité 4) ---
        "hikvision" to Category.CAMERA,
        "dahua" to Category.CAMERA,
        "axis communications" to Category.CAMERA,
        "hanwha" to Category.CAMERA,
        "samsung techwin" to Category.CAMERA,
        "uniview" to Category.CAMERA,
        "vivotek" to Category.CAMERA,
        "reolink" to Category.CAMERA,
        "arlo" to Category.CAMERA,
        "ring llc" to Category.CAMERA,
        "wyze" to Category.CAMERA,
        "verkada" to Category.CAMERA,
        "avigilon" to Category.CAMERA,
        "flir" to Category.CAMERA,
        "mobotix" to Category.CAMERA,
        "geovision" to Category.CAMERA,
        "lorex" to Category.CAMERA,
        "swann" to Category.CAMERA,
        "amcrest" to Category.CAMERA,
        "bosch security systems" to Category.CAMERA,
        "milesight taiwan" to Category.CAMERA,               // branche caméras IP — avant le générique

        // --- Flotte / télématique (priorité 4) ---
        "geotab" to Category.FLEET,
        "samsara" to Category.FLEET,
        "calamp" to Category.FLEET,
        "queclink" to Category.FLEET,
        "ruptela" to Category.FLEET,
        "trackimo" to Category.FLEET,
        "mix telematics" to Category.FLEET,
        "omnicomm" to Category.FLEET,
        "concox" to Category.FLEET,
        "fleet complete" to Category.FLEET,
        "verizon connect" to Category.FLEET,
        "lytx" to Category.FLEET,
        "smartdrive" to Category.FLEET,
        "netradyne" to Category.FLEET,
        "zonar" to Category.FLEET,
        "trimble" to Category.FLEET,
        "sensata" to Category.FLEET,
        "orbcomm" to Category.FLEET,
        "iridium" to Category.FLEET,
        "globalstar" to Category.FLEET,
        "motorola solutions" to Category.FLEET,
        "harris corporation" to Category.FLEET,
        "l3harris" to Category.FLEET,
        "sepura" to Category.FLEET,
        "hytera" to Category.FLEET,
        "kenwood" to Category.FLEET,
        "tait electronics" to Category.FLEET,

        // --- Infra réseau (priorité 4) ---
        "cisco" to Category.NETWORK_INFRA,
        "meraki" to Category.NETWORK_INFRA,
        "aruba" to Category.NETWORK_INFRA,
        "hewlett packard enterprise" to Category.NETWORK_INFRA,   // avant « hewlett packard »
        "procurve" to Category.NETWORK_INFRA,
        "ubiquiti" to Category.NETWORK_INFRA,
        "ruckus" to Category.NETWORK_INFRA,
        "commscope" to Category.NETWORK_INFRA,
        "juniper" to Category.NETWORK_INFRA,
        "mist systems" to Category.NETWORK_INFRA,
        "extreme networks" to Category.NETWORK_INFRA,
        "fortinet" to Category.NETWORK_INFRA,
        "cambium" to Category.NETWORK_INFRA,
        "mikrotik" to Category.NETWORK_INFRA,
        "routerboard" to Category.NETWORK_INFRA,             // OUI MikroTik = Routerboard.com
        "engenius" to Category.NETWORK_INFRA,
        "edgecore" to Category.NETWORK_INFRA,
        "edge-core" to Category.NETWORK_INFRA,
        "arista" to Category.NETWORK_INFRA,
        "huawei symantec" to Category.NETWORK_INFRA,
        "nokia" to Category.NETWORK_INFRA,                   // les téléphones Nokia sont « HMD Global »
        "ericsson" to Category.NETWORK_INFRA,
        "alcatel" to Category.NETWORK_INFRA,
        "telrad" to Category.NETWORK_INFRA,
        "siklu" to Category.NETWORK_INFRA,
        "ceragon" to Category.NETWORK_INFRA,
        "airspan" to Category.NETWORK_INFRA,
        "baicells" to Category.NETWORK_INFRA,
        "sonicwall" to Category.NETWORK_INFRA,
        "watchguard" to Category.NETWORK_INFRA,
        "palo alto" to Category.NETWORK_INFRA,
        "aerohive" to Category.NETWORK_INFRA,
        "lancom" to Category.NETWORK_INFRA,
        "grandstream" to Category.NETWORK_INFRA,
        "aviat" to Category.NETWORK_INFRA,

        // --- Routeurs Wi-Fi / box (priorité 2, non prioritaire) : fabricants de CPE grand public et box opérateur.
        // ZTE et Huawei font aussi du LTE et du cœur de réseau, mais un AP Wi-Fi de ces marques est presque
        // toujours une box (Telmex, Orange…) : déduction par fabricant, jamais une certitude. ---
        "zte" to Category.ROUTER_AP,
        "huawei technologies" to Category.ROUTER_AP,          // « huawei device » = téléphones, plus bas
        "sagemcom" to Category.ROUTER_AP,
        "technicolor" to Category.ROUTER_AP,
        "arris" to Category.ROUTER_AP,
        "askey" to Category.ROUTER_AP,
        "tp-link" to Category.ROUTER_AP,
        "netgear" to Category.ROUTER_AP,
        "d-link" to Category.ROUTER_AP,
        "zyxel" to Category.ROUTER_AP,
        "draytek" to Category.ROUTER_AP,
        "eero" to Category.ROUTER_AP,
        "avm" to Category.ROUTER_AP,                          // Fritz!Box
        "sercomm" to Category.ROUTER_AP,

        // --- Industriel / IoT pro (priorité 3) ---
        "siemens" to Category.INDUSTRIAL,
        "schneider" to Category.INDUSTRIAL,
        "rockwell" to Category.INDUSTRIAL,
        "allen-bradley" to Category.INDUSTRIAL,
        "abb" to Category.INDUSTRIAL,
        "honeywell" to Category.INDUSTRIAL,
        "emerson" to Category.INDUSTRIAL,
        "yokogawa" to Category.INDUSTRIAL,
        "phoenix contact" to Category.INDUSTRIAL,
        "moxa" to Category.INDUSTRIAL,
        "advantech" to Category.INDUSTRIAL,
        "beckhoff" to Category.INDUSTRIAL,
        "wago" to Category.INDUSTRIAL,
        "b&r" to Category.INDUSTRIAL,
        "omron" to Category.INDUSTRIAL,
        "mitsubishi electric" to Category.INDUSTRIAL,
        "fanuc" to Category.INDUSTRIAL,
        "kuka" to Category.INDUSTRIAL,
        "bosch rexroth" to Category.INDUSTRIAL,
        "robert bosch" to Category.INDUSTRIAL,
        "hms industrial" to Category.INDUSTRIAL,
        "prosoft" to Category.INDUSTRIAL,
        "red lion" to Category.INDUSTRIAL,
        "weidmüller" to Category.INDUSTRIAL,
        "weidmuller" to Category.INDUSTRIAL,
        "pepperl" to Category.INDUSTRIAL,
        "sick ag" to Category.INDUSTRIAL,
        "ifm electronic" to Category.INDUSTRIAL,
        "balluff" to Category.INDUSTRIAL,
        "festo" to Category.INDUSTRIAL,
        "danfoss" to Category.INDUSTRIAL,
        "grundfos" to Category.INDUSTRIAL,
        "carrier corporation" to Category.INDUSTRIAL,
        "trane" to Category.INDUSTRIAL,
        "daikin" to Category.INDUSTRIAL,
        "johnson controls" to Category.INDUSTRIAL,
        "itron" to Category.INDUSTRIAL,
        "landis" to Category.INDUSTRIAL,
        "kamstrup" to Category.INDUSTRIAL,
        "u-blox" to Category.INDUSTRIAL,
        "espressif" to Category.INDUSTRIAL,                  // ESP32/ESP8266 : modules IoT, un seul classement
        "raspberry pi" to Category.INDUSTRIAL,
        "arduino" to Category.INDUSTRIAL,
        "seeed" to Category.INDUSTRIAL,
        "heltec" to Category.INDUSTRIAL,
        "lilygo" to Category.INDUSTRIAL,
        "rakwireless" to Category.INDUSTRIAL,                // LoRa — avant le fourre-tout « shenzhen »
        "semtech" to Category.INDUSTRIAL,
        "multitech" to Category.INDUSTRIAL,
        "the things" to Category.INDUSTRIAL,                 // The Things Network / Industries
        "kerlink" to Category.INDUSTRIAL,
        "dragino" to Category.INDUSTRIAL,
        "milesight" to Category.INDUSTRIAL,                  // Xiamen Milesight IoT : passerelles LoRaWAN, capteurs
        "laird" to Category.INDUSTRIAL,
        "silicon laboratories" to Category.INDUSTRIAL,
        "silicon labs" to Category.INDUSTRIAL,
        "nordic semiconductor" to Category.INDUSTRIAL,
        "texas instruments" to Category.INDUSTRIAL,

        // --- Grand public (priorité 1) ---
        "apple" to Category.CONSUMER,
        "samsung" to Category.CONSUMER,
        "google" to Category.CONSUMER,
        "xiaomi" to Category.CONSUMER,
        "huawei device" to Category.CONSUMER,
        "oppo" to Category.CONSUMER,
        "vivo" to Category.CONSUMER,
        "oneplus" to Category.CONSUMER,
        "realme" to Category.CONSUMER,
        "motorola mobility" to Category.CONSUMER,
        "sony" to Category.CONSUMER,
        "lg electronics" to Category.CONSUMER,
        "hmd global" to Category.CONSUMER,
        "amazon" to Category.CONSUMER,
        "microsoft" to Category.CONSUMER,
        "intel" to Category.CONSUMER,
        "dell" to Category.CONSUMER,
        "lenovo" to Category.CONSUMER,
        "asustek" to Category.CONSUMER,
        "acer" to Category.CONSUMER,
        "hewlett packard" to Category.CONSUMER,
        "hp" to Category.CONSUMER,
        "micro-star" to Category.CONSUMER,
        "razer" to Category.CONSUMER,
        "bose" to Category.CONSUMER,
        "jbl" to Category.CONSUMER,
        "harman" to Category.CONSUMER,
        "sonos" to Category.CONSUMER,
        "garmin" to Category.CONSUMER,
        "fitbit" to Category.CONSUMER,
        "polar electro" to Category.CONSUMER,
        "suunto" to Category.CONSUMER,
        "logitech" to Category.CONSUMER,
        "corsair" to Category.CONSUMER,
        "steelseries" to Category.CONSUMER,
        "roku" to Category.CONSUMER,
        "nintendo" to Category.CONSUMER,
        "valve corporation" to Category.CONSUMER,
        "oculus" to Category.CONSUMER,
        "meta platforms" to Category.CONSUMER,
        "gopro" to Category.CONSUMER,
        "dji" to Category.CONSUMER,
        "azurewave" to Category.CONSUMER,
        "liteon" to Category.CONSUMER,
        "murata" to Category.CONSUMER,
        "foxconn" to Category.CONSUMER,
        "hon hai" to Category.CONSUMER,
        "wistron" to Category.CONSUMER,
        "compal" to Category.CONSUMER,
        "quanta" to Category.CONSUMER,
        "pegatron" to Category.CONSUMER,
        "ampak" to Category.CONSUMER,
        "realtek" to Category.CONSUMER,
        "mediatek" to Category.CONSUMER,
        "broadcom" to Category.CONSUMER,
        "qualcomm" to Category.CONSUMER,
        "tuya" to Category.CONSUMER,
        "shenzhen" to Category.CONSUMER,                     // fourre-tout, toujours en dernier
    )

    private val compiled: List<Pair<Regex, Category>> = rules.map { (kw, cat) ->
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(kw)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) to cat
    }

    fun classify(vendorLong: String, kind: Kind, mac: String): Category {
        if (vendorLong.isBlank()) {
            return if ((kind == Kind.WIFI || kind == Kind.STATION) && Oui.isLocallyAdministered(mac)) Category.RANDOMIZED
            else if (kind == Kind.BLE && Oui.bleAddressType(mac) != "publique/non-résolvable") Category.RANDOMIZED
            else Category.UNKNOWN
        }
        for ((rx, cat) in compiled) if (rx.containsMatchIn(vendorLong)) return cat
        return Category.UNKNOWN
    }

    /** Recoupements dans les règles : doublons, et règle masquée par une règle précédente qu'elle contient. */
    fun ruleProblems(): List<String> {
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        rules.forEachIndexed { i, (kw, _) ->
            if (!seen.add(kw)) out += "doublon : « $kw »"
            for (j in 0 until i) {
                val prev = rules[j].first
                if (kw != prev && compiled[j].first.containsMatchIn(kw)) out += "« $kw » (#$i) est masqué par « $prev » (#$j)"
            }
        }
        return out
    }
}
