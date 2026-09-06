package ch.lab77.radar.data

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** État partagé entre le service de scan et l'UI. Source unique de vérité. */
object ScanRepository {
    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()

    /** Position estimée + incertitude + persistance par émetteur (cahier §3, §3 bis). */
    private val _estimates = MutableStateFlow<Map<String, Estimate>>(emptyMap())
    val estimates: StateFlow<Map<String, Estimate>> = _estimates.asStateFlow()

    /** Trace GPS de la session : paires (lat, lon), un point tous les ~3 m. */
    private val _trace = MutableStateFlow<List<DoubleArray>>(emptyList())
    val trace: StateFlow<List<DoubleArray>> = _trace.asStateFlow()

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _log = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 200)
    val log = _log.asSharedFlow()

    @Volatile var location: Location? = null
        private set

    /** Horodatage du dernier résultat reçu par type — lu par le chien de garde du service. */
    @Volatile var lastWifiResultAt = 0L
        private set
    @Volatile var lastBleResultAt = 0L
        private set

    private var db: Db? = null
    private val io = Executors.newSingleThreadExecutor()
    private var tone: ToneGenerator? = null

    // Accédés uniquement depuis le thread `io`
    private val obsById = HashMap<String, ArrayDeque<Obs>>()
    private val lastEstimateWrite = HashMap<String, Long>()
    private val sessionNameFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun init(ctx: Context) {
        if (db == null) db = Db(ctx.applicationContext)
        Oui.load(ctx)
        if (_status.value.sessionStart == 0L) openSession()
    }

    private fun openSession() {
        val now = System.currentTimeMillis()
        val id = try { db?.newSession(now, sessionNameFmt.format(Date(now))) ?: 0L } catch (_: Exception) { 0L }
        _status.update { it.copy(sessionStart = now, sessionId = id, wifiScans = 0) }
    }

    fun db(): Db? = db

    fun setStatus(f: (ScanStatus) -> ScanStatus) = _status.update(f)

    fun setLocation(loc: Location) {
        location = loc
        _status.update {
            it.copy(gpsFix = true, lat = loc.latitude, lon = loc.longitude,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null)
        }
        val last = _trace.value.lastOrNull()
        if (last == null || Estimator.distanceM(last[0], last[1], loc.latitude, loc.longitude) >= 3.0) {
            _trace.update { (it + doubleArrayOf(loc.latitude, loc.longitude)).takeLast(5000) }
        }
    }

    fun setAlerts(on: Boolean) = _status.update { it.copy(alertsOn = on) }

    fun logLine(s: String) { _log.tryEmit(s) }

    /** Point d'entrée unique des scanners. Thread-safe par sérialisation sur `io`. */
    fun observe(kind: Kind, rawId: String, name: String?, rssi: Int, frequency: Int, capabilities: String, bleCompanyId: Int? = null) {
        if (kind == Kind.WIFI) lastWifiResultAt = System.currentTimeMillis() else lastBleResultAt = System.currentTimeMillis()
        io.execute {
            val id = rawId.uppercase()
            val now = System.currentTimeMillis()
            val loc = location?.takeIf { now - it.time < 60_000 }   // jamais de position sans fix de moins de 60 s
            val prev = _devices.value[id]

            val vendor: String; val vendorLong: String
            if (prev != null) { vendor = prev.vendor; vendorLong = prev.vendorLong } else {
                val v = Oui.lookup(id)
                val company = bleCompanyId?.let { Oui.bleCompany(it) }
                vendor = v?.let { Oui.displayName(it.long, it.short) } ?: company ?: ""
                vendorLong = v?.long ?: company ?: ""
            }
            val category = prev?.category ?: Classifier.classify(vendorLong, kind, id)
            val displayName = name?.takeIf { it.isNotBlank() } ?: prev?.name ?: ""

            val d = if (prev == null) Device(
                kind, id, displayName, rssi, rssi, frequency, capabilities, vendor, vendorLong, category,
                now, now, 1, loc?.latitude, loc?.longitude, loc?.takeIf { it.hasAltitude() }?.altitude,
                loc?.takeIf { it.hasAccuracy() }?.accuracy
            ) else prev.copy(
                name = displayName, rssi = rssi, bestRssi = maxOf(prev.bestRssi, rssi),
                frequency = if (frequency != 0) frequency else prev.frequency,
                capabilities = if (capabilities.isNotBlank()) capabilities else prev.capabilities,
                lastSeen = now, seenCount = prev.seenCount + 1,
                lat = loc?.latitude ?: prev.lat, lon = loc?.longitude ?: prev.lon,
                altitude = loc?.takeIf { it.hasAltitude() }?.altitude ?: prev.altitude,
                accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy ?: prev.accuracy,
            )

            _devices.update { it + (id to d) }
            val sessionId = _status.value.sessionId
            try { db?.upsert(d, prev == null, prev != null && rssi > prev.bestRssi, sessionId) } catch (_: Exception) {}

            // Estimation de position : une observation par relevé géolocalisé
            if (loc != null) {
                val list = obsById.getOrPut(id) { ArrayDeque() }
                list.addLast(Obs(now, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 30f, rssi))
                while (list.size > Estimator.MAX_OBS) list.removeFirst()
            }
            val obs = obsById[id] ?: emptyList<Obs>()
            val est = Estimator.estimate(obs.toList(), kind, Estimator.persistence(d.firstSeen, d.lastSeen, now, obs.toList()))
            _estimates.update { it + (id to est) }
            if (est.lat != null && now - (lastEstimateWrite[id] ?: 0L) > 10_000) {
                lastEstimateWrite[id] = now
                try { db?.upsertEstimate(sessionId, id, est, now) } catch (_: Exception) {}
            }

            if (prev == null) {
                val tag = if (category.isPriority) "!! " else ""
                logLine("$tag${kind.name} $id ${d.name.ifBlank { "<sans nom>" }} ${rssi}dBm ${vendor.ifBlank { "?" }} [${category.label}]")
                if (category.isPriority && _status.value.alertsOn) beep()
            }
        }
    }

    /** Recalcule le statut de persistance de tous les appareils (le « passant » dépend du temps écoulé). Appelé par le chien de garde. */
    fun refreshPersistence() {
        io.execute {
            val now = System.currentTimeMillis()
            val devs = _devices.value
            val cur = _estimates.value
            var next = cur
            for ((id, d) in devs) {
                val obs = obsById[id]?.toList() ?: emptyList()
                val p = Estimator.persistence(d.firstSeen, d.lastSeen, now, obs)
                val e = cur[id]
                if (e == null) next = next + (id to Estimator.estimate(obs, d.kind, p))
                else if (e.persistence != p) next = next + (id to e.copy(persistence = p))
            }
            if (next !== cur) _estimates.value = next
        }
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        } catch (_: Exception) {}
    }

    fun clearSession() {
        io.execute {
            try { db?.endSession(_status.value.sessionId, System.currentTimeMillis()) } catch (_: Exception) {}
            obsById.clear(); lastEstimateWrite.clear()
            _devices.value = emptyMap()
            _estimates.value = emptyMap()
            _trace.value = emptyList()
            openSession()
        }
    }
}
