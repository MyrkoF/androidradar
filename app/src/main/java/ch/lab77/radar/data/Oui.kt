package ch.lab77.radar.data

import android.content.Context

/**
 * Table OUI embarquée (format Wireshark manuf, compacté en TSV : préfixe hex, nom court, nom long).
 * Gère les blocs MA-L (24 bits), MA-M (28 bits) et MA-S (36 bits). Lecture longest-prefix-first.
 */
object Oui {
    data class Vendor(val short: String, val long: String)

    private val map = HashMap<String, Vendor>(50_000)
    @Volatile private var loaded = false

    /** Identifiants d'entreprise Bluetooth SIG (sous-ensemble courant, sans base réseau). */
    private val bleCompanies = mapOf(
        0x0002 to "Intel", 0x0003 to "IBM", 0x0006 to "Microsoft", 0x000A to "Qualcomm/CSR",
        0x000D to "Texas Instruments", 0x0059 to "Nordic Semi", 0x004C to "Apple", 0x0075 to "Samsung",
        0x0087 to "Garmin", 0x009E to "Bose", 0x00E0 to "Google", 0x012D to "Sony",
        0x027D to "Huawei", 0x038F to "Xiaomi",
    )

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        ctx.assets.open("oui.tsv").bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.split('\t')
                if (t.size >= 2) map[t[0]] = Vendor(t[1], if (t.size > 2) t[2] else t[1])
            }
        }
        loaded = true
    }

    fun lookup(mac: String): Vendor? {
        val hex = mac.replace(":", "").replace("-", "").uppercase()
        if (hex.length < 6) return null
        return map[hex.take(9)] ?: map[hex.take(7)] ?: map[hex.take(6)]
    }

    fun bleCompany(id: Int): String? = bleCompanies[id]

    private val legalWords = Regex(
        "\\b(co|ltd|inc|corp|corporation|company|gmbh|ag|sa|sas|srl|llc|oy|ab|bv|nv|kg|plc|uab|limited|pty|pvt|" +
        "technologies|technology|electronics|electronic|international|industries|industrial|manufacturing|holdings|group|systems)\\b\\.?",
        RegexOption.IGNORE_CASE
    )
    private val cityPrefix = Regex(
        "^(shenzhen|xiamen|hangzhou|beijing|shanghai|guangzhou|dongguan|zhuhai|nanjing|chengdu|wuhan|suzhou|tianjin|hefei|sz|" +
        "sichuan|guangdong|zhejiang|jiangsu|fujian|hong kong|taiwan|shenzen)\\s+", RegexOption.IGNORE_CASE
    )

    /**
     * Nom d'affichage dérivé du nom long : le nom court de Wireshark est tronqué à 12 caractères
     * (« Routerboardc », « HuaweiTechno »). On enlève mentions légales, ville en tête, « .com »,
     * et on garde au plus deux mots.
     */
    fun displayName(long: String, fallback: String = ""): String {
        var s = long.replace("\"", "").substringBefore(',')
        s = s.replace(Regex("\\.com\\b", RegexOption.IGNORE_CASE), "")
        s = legalWords.replace(s, " ")
        s = s.replace(Regex("[\\s.\\-&/]+$"), "").replace(Regex("^[\\s.\\-&/]+"), "").replace(Regex("\\s+"), " ").trim()
        val stripped = cityPrefix.replace(s, "").trim()
        if (stripped.isNotBlank()) s = stripped
        s = s.split(' ').take(2).joinToString(" ")
        if (s.length > 22) s = s.take(22)
        return s.ifBlank { fallback }
    }

    /** Bit "locally administered" (Wi-Fi) → adresse randomisée / logicielle. */
    fun isLocallyAdministered(mac: String): Boolean {
        val b = mac.take(2).toIntOrNull(16) ?: return false
        return b and 0x02 != 0
    }

    /** Heuristique BLE : les deux bits de poids fort de l'adresse indiquent une adresse aléatoire. */
    fun bleAddressType(mac: String): String {
        val b = mac.take(2).toIntOrNull(16) ?: return "?"
        return when (b and 0xC0) {
            0xC0 -> "aléatoire statique"
            0x40 -> "privée résolvable"
            else -> "publique/non-résolvable"
        }
    }
}
