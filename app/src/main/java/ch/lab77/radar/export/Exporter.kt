package ch.lab77.radar.export

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import ch.lab77.radar.data.Category
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Estimate
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

/**
 * Exports de session : CSV WiGLE 1.4, JSON, GeoJSON (QGIS), débrief texte prêt pour un LLM.
 * Position exportée = position CALCULÉE (barycentre / RTT / triangulation / verrou), précision = rayon
 * d'incertitude — jamais la position du téléphone au dernier passage (#13). Mode « propre » par défaut :
 * seulement les objets dont la position vaut quelque chose ; « brut » = tout.
 */
object Exporter {
    const val CLEAN_MAX_RADIUS_M = 60f

    /** Point exportable : position calculée si elle existe, sinon position du téléphone (brut seulement). */
    data class Pt(val lat: Double, val lon: Double, val radius: Float, val method: String, val e: Estimate?)

    fun point(d: Device, e: Estimate?): Pt? = when {
        e?.lat != null && e.lon != null -> Pt(e.lat, e.lon, e.radius, when { e.rttFix -> "rtt"; e.bearingFix -> "triangulation"; e.locked -> "verrou"; else -> "barycentre" }, e)
        d.lat != null && d.lon != null -> Pt(d.lat, d.lon, d.accuracy ?: 50f, "dernier passage", null)
        else -> null
    }

    /** Propre : stationnaire ou position confirmée (RTT / △ / 🔒), rayon ≤ 60 m, ni passant ni MAC aléatoire. */
    fun isClean(d: Device, e: Estimate?): Boolean {
        if (e?.lat == null) return false
        if (d.category == Category.RANDOMIZED || e.persistence == Persistence.PASSING) return false
        val confirmed = e.rttFix || e.bearingFix || e.locked || e.persistence == Persistence.STATIONARY
        return confirmed && e.radius <= CLEAN_MAX_RADIUS_M
    }

    fun select(devices: Collection<Device>, clean: Boolean): List<Device> {
        val est = ScanRepository.estimates.value
        return if (clean) devices.filter { isClean(it, est[it.id]) } else devices.toList()
    }

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
        val est = ScanRepository.estimates.value
        val sb = StringBuilder()
        sb.append("WigleWifi-1.4,appRelease=radar-0.2,model=${Build.MODEL},release=${Build.VERSION.RELEASE},")
        sb.append("device=${Build.DEVICE},display=${Build.DISPLAY},board=${Build.BOARD},brand=${Build.BRAND}\n")
        sb.append("MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n")
        for (d in devices.sortedBy { it.firstSeen }) {
            val auth = when (d.kind) { Kind.WIFI -> d.capabilities; Kind.BLE -> "Misc [BLE]"; Kind.CELL -> d.capabilities }
            sb.append(csv(d.id)).append(',').append(csv(d.name)).append(',').append(csv(auth)).append(',')
            sb.append(stamp.format(Date(d.firstSeen))).append(',')
            sb.append(d.channel).append(',').append(d.bestRssi).append(',')
            val pt = point(d, est[d.id])
            sb.append(pt?.lat ?: 0.0).append(',').append(pt?.lon ?: 0.0).append(',')
            sb.append(d.altitude ?: 0.0).append(',').append(pt?.radius ?: 0f).append(',')
            sb.append(when (d.kind) { Kind.WIFI -> "WIFI"; Kind.BLE -> "BLE"; Kind.CELL -> d.band }).append('\n')
        }
        return sb.toString()
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---- JSON ---------------------------------------------------------------------------------

    fun json(devices: Collection<Device>, st: ScanStatus, clean: Boolean = true): String {
        val root = JSONObject()
        root.put("tool", "Radar 0.2")
        root.put("export_mode", if (clean) "propre" else "brut")
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
                        put("bearing_fix", e.bearingFix); put("locked", e.locked)
                        put("method", point(d, e)?.method ?: JSONObject.NULL)
                        put("alt_rel_m", e.altM ?: JSONObject.NULL)
                    })
                    put("quality", if (isClean(d, e)) "propre" else "bruit")
                }
            })
        }
        root.put("devices", arr)
        return root.toString(2)
    }

    // ---- Diagnostic : tout ce qu'il faut pour rejouer une session (pour la session de développement) ----

    fun diagnostic(ctx: Context, devices: Collection<Device>, st: ScanStatus): String {
        val root = JSONObject()
        val pkg = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        root.put("tool", "Radar ${pkg?.versionName ?: "?"} (code ${pkg?.longVersionCode ?: 0})")
        root.put("exported", stamp.format(Date()))
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        root.put("permissions", JSONObject().apply {
            for (perm in listOf("ACCESS_FINE_LOCATION", "NEARBY_WIFI_DEVICES", "BLUETOOTH_SCAN", "ACTIVITY_RECOGNITION", "POST_NOTIFICATIONS", "WRITE_SECURE_SETTINGS"))
                put(perm, ctx.checkSelfPermission("android.permission.$perm") == android.content.pm.PackageManager.PERMISSION_GRANTED)
        })
        root.put("wifi_throttle_enabled", ch.lab77.radar.scan.SystemTweaks.throttleEnabled(ctx) ?: JSONObject.NULL)
        root.put("status", JSONObject().apply {
            put("session_start", stamp.format(Date(st.sessionStart))); put("session_id", st.sessionId)
            put("wifi_on", st.wifiOn); put("ble_on", st.bleOn); put("cell_on", st.cellOn); put("wifi_throttled", st.wifiThrottled); put("wifi_scans", st.wifiScans)
            put("gps_fix", st.gpsFix); put("gps_held", st.gpsHeld); put("dead_reckoning", st.deadReckoning); put("accuracy", st.accuracy ?: JSONObject.NULL)
            put("sats_used", st.satsUsed); put("sats_visible", st.satsVisible); put("heading", st.heading ?: JSONObject.NULL)
            put("pressure_hpa", st.pressureHpa ?: JSONObject.NULL); put("baro_alt_m", st.baroAltM ?: JSONObject.NULL)
            put("steps", st.steps); put("steps_known", st.stepsKnown); put("sensors", st.sensors)
        })
        root.put("journal", JSONArray().apply { for ((t, line) in ScanRepository.logHistory()) put(JSONObject().put("t", stamp.format(Date(t))).put("line", line)) })
        root.put("positions", JSONArray().apply {
            for (e in ScanRepository.posHistory()) put(JSONObject().apply {
                put("t", stamp.format(Date(e.t))); put("source", e.source); put("lat", e.lat); put("lon", e.lon); put("acc", e.acc)
                put("accepted", e.accepted); put("steps_since", e.stepsSince); put("heading", e.heading ?: JSONObject.NULL); put("reason", e.reason)
            })
        })
        root.put("trace_points", ScanRepository.trace.value.size)
        root.put("sensor_inventory", JSONArray(ScanRepository.sensorInventory))
        root.put("sensors_1hz", JSONArray().apply {
            for (r in ScanRepository.sensorHistory()) put(JSONObject().apply {
                put("t", stamp.format(Date(r.t))); put("heading", r.heading ?: JSONObject.NULL); put("heading_accuracy", r.headingAcc)
                put("gyro_mean_rad_s", r.gyroMean); put("gyro_max_rad_s", r.gyroMax); put("accel_mean_m_s2", r.accelMean); put("accel_max_m_s2", r.accelMax)
                put("pressure_hpa", r.pressureHpa ?: JSONObject.NULL); put("baro_alt_m", r.baroAltM ?: JSONObject.NULL); put("steps", r.steps)
                put("gps_acc", r.gpsAcc ?: JSONObject.NULL); put("sats_used", r.satsUsed); put("sats_visible", r.satsVisible); put("dead_reckoning", r.deadReckoning)
            })
        })
        val est = ScanRepository.estimates.value
        root.put("devices", JSONArray().apply {
            for (d in devices.sortedByDescending { it.seenCount }) put(JSONObject().apply {
                put("id", d.id); put("kind", d.kind.name); put("name", d.name); put("vendor_long", d.vendorLong); put("category", d.category.name)
                put("rssi_best", d.bestRssi); put("seen_count", d.seenCount); put("first_seen", stamp.format(Date(d.firstSeen))); put("last_seen", stamp.format(Date(d.lastSeen)))
                put("rtt_capable", d.rttCapable); put("rtt_m", d.rttM ?: JSONObject.NULL); put("wifi_standard", d.wifiStandard)
                est[d.id]?.let { e -> put("estimate", JSONObject().apply {
                    put("lat", e.lat ?: JSONObject.NULL); put("lon", e.lon ?: JSONObject.NULL); put("radius_m", e.radius); put("observations", e.n)
                    put("persistence", e.persistence.name); put("method", point(d, e)?.method ?: JSONObject.NULL); put("locked", e.locked); put("rtt_fix", e.rttFix); put("bearing_fix", e.bearingFix)
                }) }
                put("observations", JSONArray().apply {
                    for (o in ScanRepository.observationsOf(d.id)) put(JSONObject().apply {
                        put("t", stamp.format(Date(o.t))); put("lat", o.lat); put("lon", o.lon); put("acc", o.acc); put("rssi", o.rssi)
                        put("baro_alt", o.baroAlt ?: JSONObject.NULL); put("rtt_m", o.rttM ?: JSONObject.NULL); put("bearing", o.bearing ?: JSONObject.NULL)
                    })
                })
            })
        })
        ScanRepository.guideTarget.value?.let { id ->
            root.put("guide_target", id)
            root.put("guide_samples", JSONArray().apply {
                for (x in ScanRepository.samplesOf(id)) put(JSONObject().put("t", stamp.format(Date(x.t))).put("rssi", x.rssi).put("heading", x.heading ?: JSONObject.NULL))
            })
        }
        return root.toString(1)
    }

    // ---- GeoJSON (QGIS) : points, cercles d'incertitude, trace ------------------------------------

    fun geoJson(devices: Collection<Device>, trace: List<DoubleArray>): String {
        val est = ScanRepository.estimates.value
        val features = JSONArray()
        for (d in devices) {
            val pt = point(d, est[d.id]) ?: continue
            val props = JSONObject().apply {
                put("id", d.id); put("name", d.name); put("kind", d.kind.name); put("category", d.category.name)
                put("vendor", d.vendor); put("rssi_best", d.bestRssi); put("band", d.band); put("security", d.security)
                put("radius_m", pt.radius); put("method", pt.method); put("observations", pt.e?.n ?: 0)
                put("persistence", pt.e?.persistence?.name ?: "UNKNOWN"); put("quality", if (isClean(d, pt.e)) "propre" else "bruit")
                put("first_seen", stamp.format(Date(d.firstSeen))); put("last_seen", stamp.format(Date(d.lastSeen)))
            }
            features.put(JSONObject().apply {
                put("type", "Feature"); put("properties", props)
                put("geometry", JSONObject().apply { put("type", "Point"); put("coordinates", JSONArray().put(pt.lon).put(pt.lat)) })
            })
            features.put(JSONObject().apply {
                put("type", "Feature"); put("properties", JSONObject().apply { put("id", d.id); put("layer", "incertitude"); put("radius_m", pt.radius) })
                put("geometry", JSONObject().apply { put("type", "Polygon"); put("coordinates", JSONArray().put(circle(pt.lat, pt.lon, pt.radius.toDouble()))) })
            })
        }
        if (trace.size >= 2) features.put(JSONObject().apply {
            put("type", "Feature"); put("properties", JSONObject().apply { put("layer", "trace") })
            put("geometry", JSONObject().apply {
                put("type", "LineString")
                put("coordinates", JSONArray().apply { for (p in trace) put(JSONArray().put(p[1]).put(p[0])) })
            })
        })
        return JSONObject().apply { put("type", "FeatureCollection"); put("features", features) }.toString(2)
    }

    private fun circle(lat: Double, lon: Double, radiusM: Double): JSONArray {
        val ring = JSONArray()
        val kx = 111_320.0 * Math.cos(Math.toRadians(lat)); val ky = 111_320.0
        for (i in 0..36) {
            val a = Math.toRadians(i * 10.0)
            ring.put(JSONArray().put(lon + radiusM * Math.sin(a) / kx).put(lat + radiusM * Math.cos(a) / ky))
        }
        return ring
    }

    // ---- Débrief texte ------------------------------------------------------------------------

    fun debrief(devices: Collection<Device>, st: ScanStatus, clean: Boolean = true): String {
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
        sb.appendLine("- Mode : ${if (clean) "PROPRE — seulement les objets à position confirmée (stationnaire / RTT / △ / 🔒, rayon ≤ ${CLEAN_MAX_RADIUS_M.toInt()} m)" else "BRUT — tout, y compris passants et MAC aléatoires"}")
        sb.appendLine("- Positions = positions CALCULÉES (barycentre pondéré, RTT, triangulation), avec leur rayon d'incertitude")
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
                (point(d, estimates[d.id])?.let { " · @ ${"%.5f".format(it.lat)},${"%.5f".format(it.lon)} ±${it.radius.toInt()} m (${it.method})" } ?: ""))
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
