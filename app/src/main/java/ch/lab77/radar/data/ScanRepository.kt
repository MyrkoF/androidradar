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
import java.util.concurrent.Executors

/** État partagé entre le service de scan et l'UI. Source unique de vérité. */
object ScanRepository {
    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()

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

    fun init(ctx: Context) {
        if (db == null) db = Db(ctx.applicationContext)
        Oui.load(ctx)
        if (_status.value.sessionStart == 0L) _status.update { it.copy(sessionStart = System.currentTimeMillis()) }
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
    }

    fun setAlerts(on: Boolean) = _status.update { it.copy(alertsOn = on) }

    fun logLine(s: String) { _log.tryEmit(s) }

    /** Point d'entrée unique des scanners. Thread-safe par sérialisation sur `io`. */
    fun observe(kind: Kind, rawId: String, name: String?, rssi: Int, frequency: Int, capabilities: String, bleCompanyId: Int? = null) {
        if (kind == Kind.WIFI) lastWifiResultAt = System.currentTimeMillis() else lastBleResultAt = System.currentTimeMillis()
        io.execute {
            val id = rawId.uppercase()
            val now = System.currentTimeMillis()
            val loc = location?.takeIf { now - it.time < 60_000 }
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
            try { db?.upsert(d, prev == null, prev != null && rssi > prev.bestRssi) } catch (_: Exception) {}

            if (prev == null) {
                val tag = if (category.isPriority) "!! " else ""
                logLine("$tag${kind.name} $id ${d.name.ifBlank { "<sans nom>" }} ${rssi}dBm ${vendor.ifBlank { "?" }} [${category.label}]")
                if (category.isPriority && _status.value.alertsOn) beep()
            }
        }
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        } catch (_: Exception) {}
    }

    fun clearSession() {
        _devices.value = emptyMap()
        _status.update { it.copy(sessionStart = System.currentTimeMillis(), wifiScans = 0) }
    }
}
