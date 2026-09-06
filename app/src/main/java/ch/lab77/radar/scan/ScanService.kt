package ch.lab77.radar.scan

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import ch.lab77.radar.MainActivity
import ch.lab77.radar.R
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Service de premier plan : garde les scanners vivants écran éteint. Un seul par process.
 *
 * Résilience (OriginOS / HyperOS tuent les services en arrière-plan) :
 * - l'état voulu (Wi-Fi / BLE) est persisté ; si le système relance le service (START_STICKY,
 *   intent null) ou si la tâche est retirée des récents (alarme de relance), on reprend là où on était ;
 * - un chien de garde vérifie toutes les 30 s que les scanners tournent et signale les trous dans le journal.
 */
class ScanService : Service() {
    companion object {
        const val ACTION_WIFI_ON = "ch.lab77.radar.WIFI_ON"
        const val ACTION_WIFI_OFF = "ch.lab77.radar.WIFI_OFF"
        const val ACTION_BLE_ON = "ch.lab77.radar.BLE_ON"
        const val ACTION_BLE_OFF = "ch.lab77.radar.BLE_OFF"
        const val ACTION_CELL_ON = "ch.lab77.radar.CELL_ON"
        const val ACTION_CELL_OFF = "ch.lab77.radar.CELL_OFF"
        const val ACTION_STOP = "ch.lab77.radar.STOP"
        const val ACTION_RESUME = "ch.lab77.radar.RESUME"
        private const val CHANNEL = "scan"
        private const val NOTIF_ID = 1
        private const val PREFS = "scan"
        private const val WATCHDOG_MS = 30_000L
        private const val WIFI_GAP_MS = 90_000L
        private const val BLE_GAP_MS = 120_000L

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, ScanService::class.java).setAction(action)
            if (action == ACTION_STOP) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }

    private lateinit var wifi: WifiScanner
    private lateinit var ble: BleScanner
    private lateinit var cell: CellScanner
    private lateinit var gps: GpsTracker
    private lateinit var sensors: PhoneSensors
    private lateinit var rtt: RttRanger
    private lateinit var probe: ExternalProbe
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiOn = false
    private var bleOn = false
    private var cellOn = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiGapReported = false
    private var bleGapReported = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (!wifiOn && !bleOn) return
            val now = System.currentTimeMillis()
            if (wifiOn) {
                if (!wifi.isRunning) { ScanRepository.logLine("!! Wi-Fi : scanner arrêté, relance"); wifi.start() }
                val last = ScanRepository.lastWifiResultAt
                val gap = now - maxOf(last, ScanRepository.status.value.sessionStart)
                if (gap > WIFI_GAP_MS && !wifiGapReported) {
                    ScanRepository.logLine("!! Wi-Fi : aucun résultat depuis ${gap / 1000}s (throttling ? service bridé ?)")
                    wifiGapReported = true
                } else if (gap <= WIFI_GAP_MS) wifiGapReported = false
            }
            if (cellOn && !cell.isRunning) { ScanRepository.logLine("!! Cell : relevé arrêté, relance"); cell.start() }
            if (bleOn) {
                if (!ble.isRunning) { ScanRepository.logLine("!! BLE : scanner arrêté, relance"); ble.start() }
                val last = ScanRepository.lastBleResultAt
                val gap = now - maxOf(last, ScanRepository.status.value.sessionStart)
                if (gap > BLE_GAP_MS && !bleGapReported) {
                    ScanRepository.logLine("!! BLE : aucune annonce depuis ${gap / 1000}s")
                    bleGapReported = true
                } else if (gap <= BLE_GAP_MS) bleGapReported = false
            }
            ScanRepository.refreshPersistence()
            updateNotification()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ScanRepository.init(this)
        rtt = RttRanger(this)
        wifi = WifiScanner(this, rtt)
        ble = BleScanner(this)
        cell = CellScanner(this)
        gps = GpsTracker(this)
        sensors = PhoneSensors(this)
        probe = ExternalProbe(this)
        ScanRepository.sensorInventory = sensors.inventory()
        ScanRepository.setStatus { it.copy(sensors = sensors.availability(if (rtt.supported) rtt.available else false)) }
        // Objet suivi dans le moniteur → rafale sur le capteur concerné (#12)
        scope.launch {
            ScanRepository.guideTarget.collect { id ->
                val kind = id?.let { ScanRepository.devices.value[it]?.kind }
                wifi.burst = kind == Kind.WIFI
                cell.burst = kind == Kind.CELL
                if (kind != null) ScanRepository.logLine("Rafale ${kind.name} pour $id")
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radar:scan")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        when (intent?.action) {
            ACTION_WIFI_ON -> { if (!goForeground()) return START_NOT_STICKY; wifi.start(); wifiOn = true }
            ACTION_WIFI_OFF -> { wifi.stop(); wifiOn = false }
            ACTION_BLE_ON -> { if (!goForeground()) return START_NOT_STICKY; if (ble.start()) bleOn = true }
            ACTION_BLE_OFF -> { ble.stop(); bleOn = false }
            ACTION_CELL_ON -> { if (!goForeground()) return START_NOT_STICKY; cellOn = cell.start() }
            ACTION_CELL_OFF -> { cell.stop(); cellOn = false }
            ACTION_STOP -> { wifiOn = false; bleOn = false; cellOn = false }
            else -> {
                // null = relance par le système après kill ; RESUME = alarme après retrait des récents
                val wantWifi = prefs.getBoolean("wifi", false)
                val wantBle = prefs.getBoolean("ble", false)
                val wantCell = prefs.getBoolean("cell", false)
                if (wantWifi || wantBle || wantCell) {
                    ScanRepository.logLine("!! Service relancé (${if (intent == null) "système" else "alarme"}) — reprise Wi-Fi=$wantWifi BLE=$wantBle Cell=$wantCell")
                    if (!goForeground()) return START_NOT_STICKY
                    if (wantWifi) { wifi.start(); wifiOn = true }
                    if (wantBle) bleOn = ble.start()
                    if (wantCell) cellOn = cell.start()
                }
            }
        }
        prefs.edit().putBoolean("wifi", wifiOn).putBoolean("ble", bleOn).putBoolean("cell", cellOn).apply()

        if (wifiOn || bleOn || cellOn) {
            SystemTweaks.onScanStart(this)
            gps.start()
            sensors.start()
            probe.start()
            if (wakeLock?.isHeld == false) wakeLock?.acquire(6 * 60 * 60 * 1000L)
            updateNotification()
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, WATCHDOG_MS)
        } else {
            handler.removeCallbacks(watchdog)
            wifi.stop(); ble.stop(); cell.stop(); gps.stop(); sensors.stop(); probe.stop()
            SystemTweaks.restore(this)
            if (wakeLock?.isHeld == true) wakeLock?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    /** Tâche retirée des récents : certaines surcouches tuent alors le process. On programme une relance. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (wifiOn || bleOn || cellOn) {
            val pi = PendingIntent.getForegroundService(
                this, 2, Intent(this, ScanService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3_000L, pi) } catch (_: Exception) {}
            ScanRepository.logLine("!! App retirée des récents — relance programmée dans 3 s")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacks(watchdog)
        wifi.stop(); ble.stop(); cell.stop(); gps.stop(); sensors.stop(); probe.stop()
        SystemTweaks.restore(this)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    /** Passe en premier plan. Android 12+ peut le refuser si l'app est en arrière-plan : on le dit au journal. */
    private fun goForeground(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotification()
        return try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: Exception) {
            ScanRepository.logLine("!! Premier plan refusé par Android (${e.javaClass.simpleName}) — rouvrir l'app et relancer")
            wifiOn = false; bleOn = false; cellOn = false
            stopSelf()
            false
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val parts = buildList { if (wifiOn) add("Wi-Fi"); if (bleOn) add("BLE"); if (cellOn) add("Cell") }
        val text = "Relevé actif : ${parts.joinToString(" + ").ifBlank { "—" }} · ${ScanRepository.devices.value.size} appareils"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Radar")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher_fg), "Stop", stop).build())
            .build()
    }
}
