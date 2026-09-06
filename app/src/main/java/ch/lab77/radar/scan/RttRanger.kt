package ch.lab77.radar.scan

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.core.content.ContextCompat
import ch.lab77.radar.data.ScanRepository

/**
 * Wi-Fi RTT (802.11mc, 802.11az sur Android 15) : la seule vraie mesure de distance qu'un téléphone sait
 * faire (±1–2 m), uniquement vers les points d'accès qui le supportent. Les AP capables sont marqués ;
 * ceux à portée sont mesurés toutes les ~12 s ; la distance mesurée alimente la trilatération.
 */
class RttRanger(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val mgr: WifiRttManager? =
        if (appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) appCtx.getSystemService(WifiRttManager::class.java) else null
    val available: Boolean get() = mgr?.isAvailable == true
    val supported: Boolean get() = mgr != null
    private var lastRanging = 0L
    private var busy = false

    private val callback = object : RangingResultCallback() {
        override fun onRangingFailure(code: Int) { busy = false; ScanRepository.logLine("RTT : échec ($code)") }
        override fun onRangingResults(results: MutableList<RangingResult>) {
            busy = false
            for (r in results) if (r.status == RangingResult.STATUS_SUCCESS) {
                val mac = r.macAddress?.toString()?.uppercase() ?: continue
                ScanRepository.setRanging(mac, r.distanceMm / 1000f, r.distanceStdDevMm / 1000f)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun onScanResults(results: List<ScanResult>) {
        val m = mgr ?: return
        val capable = results.filter { it.is80211mcResponder || (Build.VERSION.SDK_INT >= 35 && it.is80211azNtbResponder) }
        if (capable.isEmpty()) return
        ScanRepository.markRttCapable(capable.mapNotNull { it.BSSID?.uppercase() })
        val now = System.currentTimeMillis()
        if (busy || !m.isAvailable || now - lastRanging < 12_000) return
        lastRanging = now
        try {
            val req = RangingRequest.Builder().addAccessPoints(capable.take(RangingRequest.getMaxPeers())).build()
            busy = true
            m.startRanging(req, ContextCompat.getMainExecutor(appCtx), callback)
        } catch (e: Exception) { busy = false; ScanRepository.logLine("RTT : ${e.message}") }
    }
}
