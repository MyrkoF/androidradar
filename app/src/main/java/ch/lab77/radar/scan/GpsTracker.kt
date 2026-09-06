package ch.lab77.radar.scan

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import ch.lab77.radar.data.ScanRepository

/** Position via LocationManager natif (pas de Play Services : fonctionne sur GrapheneOS et sans Google). */
class GpsTracker(ctx: Context) {
    private val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

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
        ScanRepository.logLine("GPS : suivi démarré")
    }

    fun stop() {
        if (!running) return
        running = false
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        ScanRepository.setStatus { it.copy(gpsFix = false) }
    }
}
