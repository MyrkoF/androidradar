package ch.lab77.radar.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import ch.lab77.radar.data.ScanRepository

/**
 * Réglages système touchés par l'app (issue #9). La limitation du scan Wi-Fi est un réglage global :
 * lecture libre, écriture seulement si `WRITE_SECURE_SETTINGS` a été accordée une fois par ADB.
 * Tout ce qu'on change est mémorisé et restauré à l'arrêt des relevés ou à la sortie propre.
 */
object SystemTweaks {
    private const val KEY = "wifi_scan_throttle_enabled"
    private const val PREFS = "tweaks"
    const val ADB_GRANT = "adb shell pm grant ch.lab77.radar android.permission.WRITE_SECURE_SETTINGS"

    fun canWrite(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** true = limitation active (4 scans / 2 min), false = désactivée, null = inconnu. */
    fun throttleEnabled(ctx: Context): Boolean? = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isScanThrottleEnabled
    } catch (_: Exception) {
        try { Settings.Global.getInt(ctx.contentResolver, KEY) == 1 } catch (_: Exception) { null }
    }

    /** Préférence utilisateur : désactiver automatiquement la limitation pendant les relevés. */
    fun autoThrottle(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_throttle", true)
    fun setAutoThrottle(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_throttle", on).apply()

    private fun write(ctx: Context, enabled: Boolean): Boolean = try {
        Settings.Global.putInt(ctx.contentResolver, KEY, if (enabled) 1 else 0)
    } catch (_: Exception) { false }

    /** Début des relevés : mémorise l'état, désactive la limitation si on le peut et si l'utilisateur le veut. */
    fun onScanStart(ctx: Context) {
        if (!canWrite(ctx) || !autoThrottle(ctx)) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains("throttle_before")) return          // déjà fait pour cette série de relevés
        val before = throttleEnabled(ctx) ?: return
        prefs.edit().putBoolean("throttle_before", before).apply()
        if (before && write(ctx, false)) ScanRepository.logLine("Réglage : limitation du scan Wi-Fi désactivée pour le relevé (sera restaurée)")
    }

    /** Fin des relevés / sortie : restaure ce qu'on a changé. */
    fun restore(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("throttle_before")) return
        val before = prefs.getBoolean("throttle_before", true)
        prefs.edit().remove("throttle_before").apply()
        if (before && canWrite(ctx) && write(ctx, true)) ScanRepository.logLine("Réglage : limitation du scan Wi-Fi restaurée")
    }

    /** Bascule manuelle depuis l'écran Réglages (quand la permission est accordée). */
    fun setThrottle(ctx: Context, enabled: Boolean): Boolean = canWrite(ctx) && write(ctx, enabled)
}
