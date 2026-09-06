package ch.lab77.radar.export

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import ch.lab77.radar.data.Category
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.Persistence
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Exports de session : CSV compatible WiGLE 1.4, JSON complet, débrief texte prêt pour un LLM. */
object Exporter {
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private fun dir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun share(ctx: Context, name: String, mime: String, content: String) {
        val f = File(dir(ctx), name).apply { writeText(content) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun fileName(ext: String) = "radar-${fileStamp.format(Date())}.$ext"

    // ---- CSV WiGLE 1.4 ------------------------------------------------------------------------

    fun wigleCsv(devices: Collection<Device>): String {
        val sb = StringBuilder()
        sb.append("WigleWifi-1.4,appRelease=radar-0.2,model=${Build.MODEL},release=${Build.VERSION.RELEASE},")
        sb.append("device=${Build.DEVICE},display=${Build.DISPLAY},board=${Build.BOARD},brand=${Build.BRAND}\n")
        sb.append("MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n")
        for (d in devices.sortedBy { it.firstSeen }) {
            val auth = when (d.kind) { Kind.WIFI -> d.capabilities; Kind.BLE -> "Misc [BLE]"; Kind.CELL -> d.capabilities }
            sb.append(csv(d.id)).append(',').append(csv(d.name)).append(',').append(csv(auth)).append(',')
            sb.append(stamp.format(Date(d.firstSeen))).append(',')
            sb.append(d.channel).append(',').append(d.bestRssi).append(',')
            sb.append(d.lat ?: 0.0).append(',').append(d.lon ?: 0.0).append(',')
            sb.append(d.altitude ?: 0.0).append(',').append(d.accuracy ?: 0f).append(',')
            sb.append(when (d.kind) { Kind.WIFI -> "WIFI"; Kind.BLE -> "BLE"; Kind.CELL -> d.band }).append('\n')
        }
        return sb.toString()
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---- JSON ---------------------------------------------------------------------------------

    fun json(devices: Collection<Device>, st: ScanStatus): String {
        val root = JSONObject()
        root.put("tool", "Radar 0.2")
        root.put("exported", stamp.format(Date()))
        root.put("session_start", stamp.format(Date(st.sessionStart)))
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        val arr = JSONArray()
        for (d in devices.sortedByDescending { it.bestRssi }) {
            arr.put(JSONObject().apply {
                put("kind", d.kind.name); put("id", d.id); put("name", d.name)
                put("rssi_last", d.rssi); put("rssi_best", d.bestRssi)
                put("frequency", d.frequency); put("channel", d.channel); put("band", d.band)
                put("security", d.security); put("capabilities", d.capabilities)
                put("vendor", d.vendor); put("vendor_long", d.vendorLong); put("category", d.category.name)
                put("first_seen", stamp.format(Date(d.firstSeen))); put("last_seen", stamp.format(Date(d.lastSeen)))
                put("seen_count", d.seenCount)
                put("lat", d.lat ?: JSONObject.NULL); put("lon", d.lon ?: JSONObject.NULL)
                put("altitude", d.altitude ?: JSONObject.NULL); put("accuracy", d.accuracy ?: JSONObject.NULL)
                if (d.wifiStandard.isNotBlank()) put("wifi_standard", d.wifiStandard)
                if (d.rttCapable) { put("rtt_capable", true); put("rtt_m", d.rttM ?: JSONObject.NULL); put("rtt_std_m", d.rttStdM ?: JSONObject.NULL) }
                ScanRepository.estimates.value[d.id]?.let { e ->
                    put("estimate", JSONObject().apply {
                        put("lat", e.lat ?: JSONObject.NULL); put("lon", e.lon ?: JSONObject.NULL); put("radius_m", e.radius)
                        put("observations", e.n); put("persistence", e.persistence.name); put("rtt_fix", e.rttFix)
                        put("alt_rel_m", e.altM ?: JSONObject.NULL)
                    })
                }
            })
        }
        root.put("devices", arr)
        return root.toString(2)
    }

    // ---- Débrief texte ------------------------------------------------------------------------

    fun debrief(devices: Collection<Device>, st: ScanStatus): String {
        val now = System.currentTimeMillis()
        val wifi = devices.filter { it.kind == Kind.WIFI }
        val ble = devices.filter { it.kind == Kind.BLE }
        val cells = devices.filter { it.kind == Kind.CELL }
        val estimates = ScanRepository.estimates.value
        val durMin = ((now - st.sessionStart) / 60_000).coerceAtLeast(1)
        val lats = devices.mapNotNull { it.lat }; val lons = devices.mapNotNull { it.lon }
        val tz = TimeZone.getDefault().id

        val sb = StringBuilder()
        sb.appendLine("# DÉBRIEF RELEVÉ RF — Radar")
        sb.appendLine()
        sb.appendLine("Ce document est un instantané d'environnement radio passif (Wi-Fi + BLE), produit hors ligne.")
        sb.appendLine("Il ne contient aucune interception de trafic : uniquement des métadonnées diffusées publiquement.")
        sb.appendLine("Analyse-le comme un relevé de site : densité, occupation spectrale, infrastructure présente, anomalies.")
        sb.appendLine()
        sb.appendLine("## Session")
        sb.appendLine("- Début : ${stamp.format(Date(st.sessionStart))} ($tz)")
        sb.appendLine("- Export : ${stamp.format(Date(now))}")
        sb.appendLine("- Durée : $durMin min · scans Wi-Fi : ${st.wifiScans}")
        sb.appendLine("- Appareil : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        if (lats.isNotEmpty()) {
            sb.appendLine("- Zone couverte : lat ${"%.5f".format(lats.min())}→${"%.5f".format(lats.max())}, lon ${"%.5f".format(lons.min())}→${"%.5f".format(lons.max())}")
        } else sb.appendLine("- Zone couverte : pas de fix GPS pendant la session")
        sb.appendLine()
        sb.appendLine("## Volumes")
        sb.appendLine("- Wi-Fi : ${wifi.size} points d'accès (${wifi.count { it.name.isBlank() }} SSID cachés, ${wifi.count { it.category == Category.RANDOMIZED }} MAC locales/randomisées)")
        sb.appendLine("- BLE : ${ble.size} émetteurs (${ble.count { it.category == Category.RANDOMIZED }} adresses aléatoires)")
        sb.appendLine("- Cellules mobiles : ${cells.size}")
        sb.appendLine("- Actifs dans les 2 dernières minutes : ${devices.count { it.ageMs < 120_000 }}")
        sb.appendLine("- Persistance : ${estimates.count { it.value.persistence == Persistence.STATIONARY }} stationnaires, ${estimates.count { it.value.persistence == Persistence.PASSING }} passants, ${estimates.count { it.value.persistence == Persistence.WITH_ME }} avec l'opérateur, ${estimates.count { it.value.rttFix }} trilatérés (RTT)")
        sb.appendLine()
        if (cells.isNotEmpty()) {
            sb.appendLine("## Couverture cellulaire")
            cells.sortedByDescending { it.bestRssi }.forEach { d ->
                sb.appendLine("- ${d.bestRssi} dBm · ${d.name} · ${d.id} · ${d.capabilities}" + (if (d.lat != null) " · @ ${"%.5f".format(d.lat)},${"%.5f".format(d.lon)}" else ""))
            }
            sb.appendLine()
        }
        sb.appendLine("## Occupation spectrale Wi-Fi")
        for (band in listOf("2.4 GHz", "5 GHz", "6 GHz")) {
            val inBand = wifi.filter { it.band == band }
            if (inBand.isEmpty()) continue
            val byCh = inBand.groupBy { it.channel }.toSortedMap()
            sb.appendLine("- $band : ${inBand.size} AP · canaux " + byCh.entries.joinToString(", ") { "${it.key}(${it.value.size})" })
        }
        sb.appendLine("- Sécurité : " + wifi.groupBy { it.security }.entries.sortedByDescending { it.value.size }
            .joinToString(", ") { "${it.key} ${it.value.size}" })
        sb.appendLine()
        sb.appendLine("## Fabricants (top 12)")
        devices.filter { it.vendor.isNotBlank() }.groupBy { it.vendor }.entries
            .sortedByDescending { it.value.size }.take(12)
            .forEach { sb.appendLine("- ${it.key} : ${it.value.size}") }
        sb.appendLine()
        sb.appendLine("## Catégories")
        devices.groupBy { it.category }.entries.sortedByDescending { it.key.priority }
            .forEach { sb.appendLine("- ${it.key.label} : ${it.value.size}") }
        sb.appendLine()
        val prio = devices.filter { it.category.isPriority }.sortedByDescending { it.bestRssi }
        sb.appendLine("## Infrastructure et matériel prioritaire (${prio.size})")
        if (prio.isEmpty()) sb.appendLine("- aucun")
        prio.forEach { d ->
            sb.appendLine("- [${d.category.label}] ${d.kind.name} ${d.id} « ${d.name.ifBlank { "—" }} » ${d.vendorLong.ifBlank { "?" }} · ${d.bestRssi} dBm · ${d.band} · vu ${d.seenCount}× · 1er ${stamp.format(Date(d.firstSeen))}" +
                (if (d.lat != null) " · @ ${"%.5f".format(d.lat)},${"%.5f".format(d.lon)}" else ""))
        }
        sb.appendLine()
        sb.appendLine("## Signaux les plus forts (top 15)")
        devices.sortedByDescending { it.bestRssi }.take(15).forEach { d ->
            sb.appendLine("- ${d.bestRssi} dBm · ${d.kind.name} ${d.id} « ${d.name.ifBlank { "—" }} » ${d.vendor.ifBlank { "?" }} · ${d.band}" +
                (if (d.kind == Kind.WIFI) " ch${d.channel} ${d.security}" else ""))
        }
        sb.appendLine()
        sb.appendLine("## Persistance (vus le plus souvent, top 10)")
        devices.sortedByDescending { it.seenCount }.take(10).forEach { d ->
            sb.appendLine("- ${d.seenCount}× · ${d.kind.name} ${d.id} « ${d.name.ifBlank { "—" }} » ${d.vendor.ifBlank { "?" }}")
        }
        sb.appendLine()
        sb.appendLine("## Questions utiles pour l'analyse")
        sb.appendLine("1. Quelle est la densité d'AP par bande et quels canaux 2.4 GHz sont saturés (pertinent pour LoRa/2.4 et Wi-Fi mesh) ?")
        sb.appendLine("2. Quels équipements d'infrastructure sont présents et que suggèrent-ils sur le site (opérateur, vidéo, industriel) ?")
        sb.appendLine("3. Quels signaux sont anormalement forts ou persistants pour le contexte ?")
        sb.appendLine("4. Si un relevé précédent du même lieu est fourni, quelles différences (nouveaux, disparus, déplacés) ?")
        return sb.toString()
    }
}
