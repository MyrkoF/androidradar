package ch.lab77.radar.data

/**
 * Analyse des lignes de la sonde externe (docs/PROTOCOLE-SONDE.md). Pur Kotlin, testé.
 * Une ligne → une observation (`Reading`) ou un message de statut, ou null si illisible.
 */
object ProbeProtocol {
    sealed class Msg {
        data class Reading(val kind: Kind, val id: String, val name: String, val rssi: Int, val frequency: Int, val caps: String, val vendor: String? = null) : Msg()
        data class Gps(val lat: Double, val lon: Double, val alt: Double?, val acc: Float, val sats: Int) : Msg()
        data class Sweep(val freqHz: Long, val rssi: Int) : Msg()
        data class Info(val name: String, val firmware: String, val battery: Int, val uptime: Long) : Msg()
        data class Env(val pressureHpa: Float?, val tempC: Float?) : Msg()
        data class Error(val text: String) : Msg()
    }

    fun parse(raw: String): Msg? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        val f = line.split(';')
        fun s(i: Int) = f.getOrNull(i)?.trim() ?: ""
        fun i(i: Int) = s(i).toIntOrNull()
        return try {
            when (f[0].trim().uppercase()) {
                "W" -> { val mac = s(1).uppercase(); if (mac.length < 11) null else Msg.Reading(Kind.WIFI, mac, s(2), i(3) ?: return null, i(4) ?: 0, s(5)) }
                "S" -> { val mac = s(1).uppercase(); if (mac.length < 11) null else Msg.Reading(Kind.STATION, "STA-$mac", s(3).replace(',', ' ').trim(), i(2) ?: return null, 0, "cherche=${s(3)}") }
                "B" -> { val mac = s(1).uppercase(); if (mac.length < 11) null else Msg.Reading(Kind.BLE, mac, s(2), i(3) ?: return null, 0, if (s(4).isNotBlank()) "mfg=0x${s(4)}" else "") }
                "L" -> {
                    val freq = s(1).toLongOrNull() ?: return null
                    val meta = s(5); val type = s(4).ifBlank { "raw" }
                    val node = Regex("!([0-9a-fA-F]{6,8})|devAddr=([0-9a-fA-F]{8})").find(meta)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
                    val id = if (node != null) "LORA-$node" else "LORA-${freq / 1000}-${meta.hashCode().toUInt().toString(16)}"
                    Msg.Reading(Kind.LORA, id, "$type ${node ?: ""}".trim(), i(2) ?: return null, (freq / 1000).toInt(), "type=$type snr=${s(3)} $meta".trim(), vendor = type)
                }
                "C" -> {
                    val freq = s(1).toLongOrNull() ?: return null
                    val mod = s(3).ifBlank { "?" }
                    Msg.Reading(Kind.LORA, "SUBGHZ-${freq / 1000}-$mod", "$mod ${freq / 1_000_000.0} MHz", i(2) ?: return null, (freq / 1000).toInt(), "mod=$mod ${s(4)}".trim(), vendor = "sub-GHz $mod")
                }
                "R" -> Msg.Sweep(s(1).toLongOrNull() ?: return null, i(2) ?: return null)
                "G" -> Msg.Gps(s(1).toDouble(), s(2).toDouble(), s(3).toDoubleOrNull(), s(4).toFloatOrNull() ?: 10f, i(5) ?: 0)
                "I" -> Msg.Info(s(1), s(2), i(3) ?: -1, s(4).toLongOrNull() ?: 0L)
                "P" -> Msg.Env(s(1).toFloatOrNull(), s(2).toFloatOrNull())
                "E" -> Msg.Error(f.drop(1).joinToString(";"))
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
