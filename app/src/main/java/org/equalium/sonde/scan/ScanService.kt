package org.equalium.sonde.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.equalium.sonde.MainActivity
import org.equalium.sonde.R
import org.equalium.sonde.data.ScanRepository

/** Service de premier plan : garde les scanners vivants écran éteint. Un seul par process. */
class ScanService : Service() {
    companion object {
        const val ACTION_WIFI_ON = "org.equalium.sonde.WIFI_ON"
        const val ACTION_WIFI_OFF = "org.equalium.sonde.WIFI_OFF"
        const val ACTION_BLE_ON = "org.equalium.sonde.BLE_ON"
        const val ACTION_BLE_OFF = "org.equalium.sonde.BLE_OFF"
        const val ACTION_STOP = "org.equalium.sonde.STOP"
        private const val CHANNEL = "scan"
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, ScanService::class.java).setAction(action)
            if (action == ACTION_STOP) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }

    private lateinit var wifi: WifiScanner
    private lateinit var ble: BleScanner
    private lateinit var gps: GpsTracker
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiOn = false
    private var bleOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ScanRepository.init(this)
        wifi = WifiScanner(this)
        ble = BleScanner(this)
        gps = GpsTracker(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonde:scan")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WIFI_ON -> { goForeground(); wifi.start(); wifiOn = true }
            ACTION_WIFI_OFF -> { wifi.stop(); wifiOn = false }
            ACTION_BLE_ON -> { goForeground(); if (ble.start()) bleOn = true }
            ACTION_BLE_OFF -> { ble.stop(); bleOn = false }
            ACTION_STOP -> { wifiOn = false; bleOn = false }
        }
        if (wifiOn || bleOn) {
            gps.start()
            if (wakeLock?.isHeld == false) wakeLock?.acquire(6 * 60 * 60 * 1000L)
            updateNotification()
        } else {
            wifi.stop(); ble.stop(); gps.stop()
            if (wakeLock?.isHeld == true) wakeLock?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wifi.stop(); ble.stop(); gps.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIF_ID, n)
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
        val parts = buildList { if (wifiOn) add("Wi-Fi"); if (bleOn) add("BLE") }
        val text = "Relevé actif : ${parts.joinToString(" + ").ifBlank { "—" }} · ${ScanRepository.devices.value.size} appareils"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Sonde")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher_fg), "Stop", stop).build())
            .build()
    }
}
