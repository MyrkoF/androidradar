package ch.lab77.radar.scan

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import ch.lab77.radar.data.ScanRepository

/** Position via LocationManager natif (pas de Play Services : fonctionne sur GrapheneOS et sans Google). */
class GpsTracker(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    /** Satellites vus / utilisés : affiché dans la barre d'état, utile pour juger la qualité du fix. */
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            ScanRepository.setStatus { it.copy(satsVisible = status.satelliteCount, satsUsed = used) }
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { ScanRepository.setLocation(location) }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.getLastKnownLocation(p)?.let { ScanRepository.setLocation(it) }
                    lm.requestLocationUpdates(p, 2_000L, 2f, listener, Looper.getMainLooper())
                }
            } catch (_: Exception) {}
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) lm.registerGnssStatusCallback(ContextCompat.getMainExecutor(appCtx), gnss)
            else @Suppress("DEPRECATION") lm.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {}
        ScanRepository.logLine("GPS : suivi démarré")
    }

    fun stop() {
        if (!running) return
        running = false
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        try { lm.unregisterGnssStatusCallback(gnss) } catch (_: Exception) {}
        ScanRepository.setStatus { it.copy(gpsFix = false, satsVisible = 0, satsUsed = 0) }
    }
}
