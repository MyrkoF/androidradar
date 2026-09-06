package ch.lab77.radar.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository

/** Scan BLE continu, indépendant du Wi-Fi. N'utilise que les données d'annonce (pas de connexion). */
class BleScanner(ctx: Context) : Sensor {
    override val label = "BLE"
    private val manager = ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var scanner: BluetoothLeScanner? = null
    private var running = false
    override val isRunning: Boolean get() = running

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = ingest(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::ingest) }
        override fun onScanFailed(errorCode: Int) {
            ScanRepository.logLine("BLE : échec du scan (code $errorCode)")
            ScanRepository.setStatus { it.copy(bleOn = false) }
            running = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (running) return true
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            ScanRepository.logLine("BLE : Bluetooth désactivé ou absent")
            return false
        }
        scanner = adapter.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        return try {
            scanner?.startScan(null, settings, callback)
            running = true
            ScanRepository.setStatus { it.copy(bleOn = true) }
            ScanRepository.logLine("BLE : scan démarré")
            true
        } catch (e: Exception) {
            ScanRepository.logLine("BLE : ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        if (!running) return
        running = false
        try { scanner?.stopScan(callback) } catch (_: Exception) {}
        ScanRepository.setStatus { it.copy(bleOn = false) }
        ScanRepository.logLine("BLE : scan arrêté")
    }

    private fun ingest(r: ScanResult) {
        if (r.rssi == 127 || r.rssi > 20) return   // 127 = « RSSI non disponible » (Android) : aucune information, on ignore
        val rec = r.scanRecord
        val name = rec?.deviceName
        var companyId: Int? = null
        val mfg = rec?.manufacturerSpecificData
        if (mfg != null && mfg.size() > 0) companyId = mfg.keyAt(0)
        val services = rec?.serviceUuids?.joinToString(",") { it.uuid.toString().take(8) } ?: ""
        val caps = buildString {
            if (companyId != null) append("mfg=0x%04X".format(companyId))
            if (services.isNotBlank()) { if (isNotEmpty()) append(' '); append("svc=$services") }
            if (r.isConnectable) { if (isNotEmpty()) append(' '); append("connectable") }
        }
        ScanRepository.observe(Kind.BLE, r.device.address, name, r.rssi, 0, caps, companyId)
    }
}
