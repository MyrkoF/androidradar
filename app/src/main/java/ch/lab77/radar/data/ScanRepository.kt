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

    /** Échantillon (temps, signal, cap) pour le guide de marche — masquage corporel (cahier §4 ter). */
    data class Sample(val t: Long, val rssi: Int, val heading: Float?)
    private val samples = HashMap<String, ArrayDeque<Sample>>()
    private val rtt = HashMap<String, Pair<Float, Float>>()      // id → (distance m, écart-type m)

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

    /** Position du téléphone. `estimated` = à l'estime (pas + cap), jamais confondue avec un fix GPS. */
    @Volatile var lastRealFixAt = 0L
        private set
    /** Dernier point accepté (GPS filtré ou estime) — c'est lui que voient la trace et les observations. */
    @Volatile var acceptedFix: PositionFilter.Fix? = null
        private set
    private var stepsAtAccepted = 0

    /**
     * Position du téléphone. Un fix GPS passe par `PositionFilter` (le podomètre arbitre) ; rejeté, la
     * position est tenue. `estimated` = à l'estime (pas + cap), toujours acceptée, jamais confondue avec un fix.
     */
    fun setLocation(loc: Location, estimated: Boolean = false) {
        val now = System.currentTimeMillis()
        val st = _status.value
        val next = PositionFilter.Fix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 50f, now, estimated)
        val stepsSince = st.steps - stepsAtAccepted
        val ok = estimated || PositionFilter.accept(acceptedFix, next, stepsSince, st.stepsKnown)
        if (!estimated) lastRealFixAt = now
        recordPos(PosEvent(now, if (estimated) "estime" else loc.provider ?: "gps", loc.latitude, loc.longitude, next.acc, ok, stepsSince, st.heading,
            when { estimated -> "pas + cap"; ok -> "accepté"; else -> "rejeté : ${acceptedFix?.let { "saut de ${Estimator.distanceM(it.lat, it.lon, loc.latitude, loc.longitude).toInt()} m pour $stepsSince pas (tenu ±${it.acc.toInt()} m${if (it.estimated) ", estime" else ""})" } ?: "?"}" }))
        if (ok) {
            acceptedFix = next; stepsAtAccepted = st.steps
            location = loc.also { it.time = now }
            _status.update {
                it.copy(gpsFix = !estimated, deadReckoning = estimated, gpsHeld = false, lat = loc.latitude, lon = loc.longitude,
                    altitude = if (loc.hasAltitude()) loc.altitude else null, accuracy = next.acc)
            }
            val last = _trace.value.lastOrNull()
            if (last == null || Estimator.distanceM(last[0], last[1], loc.latitude, loc.longitude) >= 3.0) {
                _trace.update { (it + doubleArrayOf(loc.latitude, loc.longitude)).takeLast(5000) }
            }
        } else {
            // Saut sans pas : on garde la position, on rafraîchit l'horodatage (fix toujours « frais ») et la précision
            val held = acceptedFix ?: return
            location = Location("tenu").apply { latitude = held.lat; longitude = held.lon; accuracy = maxOf(held.acc, next.acc); time = now }
            acceptedFix = held.copy(t = now)
            _status.update { it.copy(gpsFix = true, gpsHeld = true, deadReckoning = false, accuracy = next.acc) }
        }
    }

    fun setAlerts(on: Boolean) = _status.update { it.copy(alertsOn = on) }
    fun setHeading(deg: Float) = _status.update { it.copy(heading = deg) }
    fun setBaro(hpa: Float, altRelM: Float) = _status.update { it.copy(pressureHpa = hpa, baroAltM = altRelM) }

    /** Distance mesurée Wi-Fi RTT vers un AP (thread principal) → portée dans l'appareil et les observations. */
    fun setRanging(id: String, distM: Float, stdM: Float) {
        io.execute {
            rtt[id] = distM to stdM
            _devices.value[id]?.let { d -> _devices.update { it + (id to d.copy(rttM = distM, rttStdM = stdM, rttCapable = true)) } }
            logLine("RTT $id : ${"%.1f".format(distM)} m ±${"%.1f".format(stdM)}")
        }
    }

    fun markRttCapable(ids: List<String>) {
        io.execute {
            var m = _devices.value; var changed = false
            for (id in ids) m[id]?.takeIf { !it.rttCapable }?.let { m = m + (id to it.copy(rttCapable = true)); changed = true }
            if (changed) _devices.value = m
        }
    }

    /** Derniers échantillons (60 s) d'un appareil, pour le guide de marche. */
    fun samplesOf(id: String): List<Sample> = synchronized(samples) { samples[id]?.toList() ?: emptyList() }

    /** Objet suivi dans le moniteur : le service passe les capteurs en rafale pour lui (#12). */
    private val _guideTarget = MutableStateFlow<String?>(null)
    val guideTarget: StateFlow<String?> = _guideTarget.asStateFlow()
    fun setGuideTarget(id: String?) { _guideTarget.value = id }

    private val lastBearingAt = HashMap<String, Long>()

    /**
     * Direction trouvée par masquage corporel (rose des caps nette) : enregistrée comme observation depuis
     * la position actuelle, au plus une par minute et par objet, puis position recalculée (triangulation).
     */
    fun addBearing(id: String, bearingDeg: Float, rssi: Int) {
        io.execute {
            val now = System.currentTimeMillis()
            if (now - (lastBearingAt[id] ?: 0L) < 60_000) return@execute
            val loc = location?.takeIf { now - it.time < 60_000 } ?: return@execute
            val d = _devices.value[id] ?: return@execute
            lastBearingAt[id] = now
            val acc = if (loc.hasAccuracy()) loc.accuracy else 30f
            synchronized(obsById) {
                val list = obsById.getOrPut(id) { ArrayDeque() }
                list.addLast(Obs(now, loc.latitude, loc.longitude, acc, rssi, _status.value.baroAltM, null, bearingDeg))
                while (list.size > Estimator.MAX_OBS) list.removeFirst()
            }
            try { db?.insertBearing(_status.value.sessionId, id, loc.latitude, loc.longitude, acc, rssi, bearingDeg, now) } catch (_: Exception) {}
            val obs = synchronized(obsById) { obsById[id]?.toList() ?: emptyList() }
            val est = Estimator.estimate(obs, d.kind, Estimator.persistence(d.firstSeen, d.lastSeen, now, obs, d.kind), _estimates.value[id])
            _estimates.update { it + (id to est) }
            logLine("Direction $id : ${bearingDeg.toInt()}° depuis ma position" + (if (est.bearingFix) " → position triangulée ±${est.radius.toInt()} m" else ""))
        }
    }

    /** Observations géolocalisées d'un appareil — pour montrer sur la carte d'où il a été vu. */
    fun observationsOf(id: String): List<Obs> = synchronized(obsById) { obsById[id]?.toList() ?: emptyList() }

    /** Journal complet horodaté (diagnostic) — l'écran n'en montre que les 200 dernières lignes. */
    private val logHistory = ArrayDeque<Pair<Long, String>>()
    fun logLine(s: String) {
        _log.tryEmit(s)
        synchronized(logHistory) { logHistory.addLast(System.currentTimeMillis() to s); while (logHistory.size > 2000) logHistory.removeFirst() }
    }
    fun logHistory(): List<Pair<Long, String>> = synchronized(logHistory) { logHistory.toList() }

    /** Capteurs bruts agrégés à 1 Hz (diagnostic) : cap, gyroscope, accéléromètre, pression, pas, GPS. */
    data class SensorRow(
        val t: Long, val heading: Float?, val headingAcc: Int, val gyroMean: Float, val gyroMax: Float,
        val accelMean: Float, val accelMax: Float, val pressureHpa: Float?, val baroAltM: Float?, val steps: Int,
        val gpsAcc: Float?, val satsUsed: Int, val satsVisible: Int, val deadReckoning: Boolean,
    )
    @Volatile var sensorInventory: List<String> = emptyList()
    private val sensorHistory = ArrayDeque<SensorRow>()
    fun sensorHistory(): List<SensorRow> = synchronized(sensorHistory) { sensorHistory.toList() }
    fun recordSensors(r: SensorRow) = synchronized(sensorHistory) { sensorHistory.addLast(r); while (sensorHistory.size > 7200) sensorHistory.removeFirst() }

    /** Historique des positions (diagnostic) : chaque fix, accepté ou non, et chaque pas à l'estime. */
    data class PosEvent(val t: Long, val source: String, val lat: Double, val lon: Double, val acc: Float, val accepted: Boolean, val stepsSince: Int, val heading: Float?, val reason: String)
    private val posHistory = ArrayDeque<PosEvent>()
    fun posHistory(): List<PosEvent> = synchronized(posHistory) { posHistory.toList() }
    private fun recordPos(e: PosEvent) = synchronized(posHistory) { posHistory.addLast(e); while (posHistory.size > 3000) posHistory.removeFirst() }

    /** Point d'entrée unique des scanners. Thread-safe par sérialisation sur `io`. */
    fun observe(
        kind: Kind, rawId: String, name: String?, rssi: Int, frequency: Int, capabilities: String,
        bleCompanyId: Int? = null, vendorOverride: String? = null, wifiStandard: String = "",
    ) {
        when (kind) { Kind.WIFI -> lastWifiResultAt = System.currentTimeMillis(); Kind.BLE -> lastBleResultAt = System.currentTimeMillis(); Kind.CELL -> {} }
        val headingNow = _status.value.heading
        io.execute {
            val id = rawId.uppercase()
            val now = System.currentTimeMillis()
            synchronized(samples) {
                val q = samples.getOrPut(id) { ArrayDeque() }
                q.addLast(Sample(now, rssi, headingNow))
                while (q.size > 150 || now - q.first().t > 60_000) q.removeFirst()
            }
            val loc = location?.takeIf { now - it.time < 60_000 }   // jamais de position sans fix de moins de 60 s
            val prev = _devices.value[id]

            val vendor: String; val vendorLong: String
            if (prev != null) { vendor = prev.vendor; vendorLong = prev.vendorLong }
            else if (vendorOverride != null) { vendor = vendorOverride; vendorLong = vendorOverride }
            else {
                val v = Oui.lookup(id)
                val company = bleCompanyId?.let { Oui.bleCompany(it) }
                vendor = v?.let { Oui.displayName(it.long, it.short) } ?: company ?: ""
                vendorLong = v?.long ?: company ?: ""
            }
            val category = prev?.category ?: if (kind == Kind.CELL) Category.CELL_TOWER else Classifier.classify(vendorLong, kind, id)
            val displayName = name?.takeIf { it.isNotBlank() } ?: prev?.name ?: ""

            val d = if (prev == null) Device(
                kind, id, displayName, rssi, rssi, frequency, capabilities, vendor, vendorLong, category,
                now, now, 1, loc?.latitude, loc?.longitude, loc?.takeIf { it.hasAltitude() }?.altitude,
                loc?.takeIf { it.hasAccuracy() }?.accuracy, rtt[id]?.first, rtt[id]?.second, rtt.containsKey(id), wifiStandard
            ) else prev.copy(
                name = displayName, rssi = rssi, bestRssi = maxOf(prev.bestRssi, rssi),
                frequency = if (frequency != 0) frequency else prev.frequency,
                capabilities = if (capabilities.isNotBlank()) capabilities else prev.capabilities,
                lastSeen = now, seenCount = prev.seenCount + 1,
                lat = loc?.latitude ?: prev.lat, lon = loc?.longitude ?: prev.lon,
                altitude = loc?.takeIf { it.hasAltitude() }?.altitude ?: prev.altitude,
                accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy ?: prev.accuracy,
                wifiStandard = wifiStandard.ifBlank { prev.wifiStandard },
            )

            _devices.update { it + (id to d) }
            val sessionId = _status.value.sessionId
            try { db?.upsert(d, prev == null, prev != null && rssi > prev.bestRssi, sessionId) } catch (_: Exception) {}

            // Estimation de position : une observation par relevé géolocalisé
            if (loc != null) synchronized(obsById) {
                val list = obsById.getOrPut(id) { ArrayDeque() }
                val fresh = rtt[id]?.first   // distance mesurée récente (RTT toutes les ~12 s)
                val acc = if (loc.hasAccuracy()) loc.accuracy else 30f
                val last = list.lastOrNull()
                if (Estimator.samePlace(last, loc.latitude, loc.longitude) && last!!.bearing == null) {
                    // (a) même endroit : on garde la meilleure lecture, sans empiler
                    list[list.lastIndex] = last!!.copy(t = now, rssi = maxOf(last.rssi, rssi), acc = minOf(last.acc, acc), rttM = fresh ?: last.rttM)
                } else {
                    list.addLast(Obs(now, loc.latitude, loc.longitude, acc, rssi, _status.value.baroAltM, fresh))
                    while (list.size > Estimator.MAX_OBS) list.removeFirst()
                }
            }
            val obs = synchronized(obsById) { obsById[id]?.toList() ?: emptyList() }
            val est = Estimator.estimate(obs, kind, Estimator.persistence(d.firstSeen, d.lastSeen, now, obs, kind), _estimates.value[id])
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
                val obs = synchronized(obsById) { obsById[id]?.toList() ?: emptyList() }
                val p = Estimator.persistence(d.firstSeen, d.lastSeen, now, obs, d.kind)
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
            synchronized(obsById) { obsById.clear() }; lastEstimateWrite.clear(); rtt.clear(); lastBearingAt.clear()
            synchronized(samples) { samples.clear() }
            _devices.value = emptyMap()
            _estimates.value = emptyMap()
            _trace.value = emptyList()
            openSession()
        }
    }
}
