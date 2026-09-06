package ch.lab77.radar.scan

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository

/**
 * Scan Wi-Fi périodique. Android 9+ limite les scans à 4 par 2 minutes pour une app au premier plan
 * (levable dans Options développeur > "Limitation du scan Wi-Fi"). Quand startScan() refuse, on relit
 * quand même le cache système : il est rafraîchi par les scans du système lui-même.
 */
class WifiScanner(private val ctx: Context, private val intervalMs: Long = 8_000L) {
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

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
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(receiver, f)
        handler.post(tick)
        ScanRepository.setStatus { it.copy(wifiOn = true) }
        ScanRepository.logLine("Wi-Fi : scan démarré (intervalle ${intervalMs / 1000}s)")
    }

    fun stop() {
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
            val ssid = if (Build.VERSION.SDK_INT >= 33) {
                r.wifiSsid?.toString()?.trim('"') ?: ""
            } else {
                @Suppress("DEPRECATION") r.SSID ?: ""
            }
            ScanRepository.observe(Kind.WIFI, r.BSSID ?: continue, ssid, r.level, r.frequency, r.capabilities ?: "")
        }
    }
}
