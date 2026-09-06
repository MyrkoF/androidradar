package ch.lab77.radar.scan

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository

/**
 * Scan Wi-Fi périodique. Android limite les scans à 4 par 2 minutes pour une app au premier plan
 * (levable dans Options développeur > "Limitation du scan Wi-Fi", ou automatiquement via SystemTweaks).
 * Quand startScan() refuse, on relit quand même le cache système : il est rafraîchi par les scans du
 * système lui-même. Les résultats sont aussi passés au module RTT (distances mesurées).
 */
private const val BURST_MS = 3_000L

class WifiScanner(private val ctx: Context, private val rtt: RttRanger? = null, private val intervalMs: Long = 8_000L) : Sensor {
    override val label = "Wi-Fi"
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    override val isRunning: Boolean get() = running
    /** Rafale (objet dans le moniteur, #12) : mesures rapprochées pour que la rose des caps se remplisse en un tour. */
    @Volatile var burst = false
    private val currentInterval: Long get() = if (burst) BURST_MS else intervalMs

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (running) ingest() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            @Suppress("DEPRECATION")
            val ok = try { wifi.startScan() } catch (_: SecurityException) { false }
            ScanRepository.setStatus { it.copy(wifiThrottled = !ok, wifiScans = it.wifiScans + if (ok) 1 else 0) }
            if (!ok) ingest()
            handler.postDelayed(this, currentInterval)
        }
    }

    override fun start(): Boolean {
        if (running) return true
        running = true
        ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED)
        handler.post(tick)
        ScanRepository.setStatus { it.copy(wifiOn = true) }
        ScanRepository.logLine("Wi-Fi : scan démarré (intervalle ${intervalMs / 1000}s)")
        return true
    }

    override fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        ScanRepository.setStatus { it.copy(wifiOn = false, wifiThrottled = false) }
        ScanRepository.logLine("Wi-Fi : scan arrêté")
    }

    @SuppressLint("MissingPermission")
    private fun ingest() {
        val results = try { wifi.scanResults } catch (_: SecurityException) { return }
        for (r in results) {
            val ssid = r.wifiSsid?.toString()?.trim('"') ?: ""
            ScanRepository.observe(Kind.WIFI, r.BSSID ?: continue, ssid, r.level, r.frequency, r.capabilities ?: "", wifiStandard = standard(r))
        }
        rtt?.onScanResults(results)
    }

    private fun standard(r: ScanResult): String = when (r.wifiStandard) {
        ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7"
        ScanResult.WIFI_STANDARD_11AX -> if (r.frequency > 5900) "Wi-Fi 6E" else "Wi-Fi 6"
        ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
        ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
        ScanResult.WIFI_STANDARD_LEGACY -> "legacy"
        else -> ""
    }
}
