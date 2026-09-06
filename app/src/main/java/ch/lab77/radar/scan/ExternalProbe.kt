package ch.lab77.radar.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import ch.lab77.radar.data.ProbeProtocol
import ch.lab77.radar.data.ScanRepository
import java.util.UUID

/**
 * Tête de sonde externe (v0.3, cahier §9, docs/PROTOCOLE-SONDE.md) : un T-Beam (+ CC1101) relié en BLE
 * (service UART Nordic) envoie des lignes texte qui entrent dans le même `observe()`. Le téléphone la
 * cherche dès qu'un relevé tourne, se connecte seul, se reconnecte si la liaison tombe.
 * Permission BLUETOOTH_CONNECT requise (Android 12+).
 */
class ExternalProbe(ctx: Context) : Sensor {
    override val label = "Sonde"
    private val appCtx = ctx.applicationContext
    private val manager = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    override val isRunning: Boolean get() = running
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val buffer = StringBuilder()
    private var probeName = ""
    var connected = false
        private set

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val NAME_PREFIX = "Radar-"
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith(NAME_PREFIX) || gatt != null) return
            stopScan()
            probeName = name
            ScanRepository.setStatus { it.copy(probe = "connexion à $name") }
            ScanRepository.logLine("Sonde : $name trouvée, connexion")
            gatt = result.device.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { g.requestMtu(185); g.discoverServices() }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; rx = null
                g.close(); gatt = null
                ScanRepository.setStatus { it.copy(probe = if (running) "recherche" else "") }
                ScanRepository.logLine("Sonde : déconnectée" + (if (running) ", nouvelle recherche" else ""))
                if (running) handler.postDelayed({ startScan() }, 3_000)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(NUS_SERVICE) ?: run { ScanRepository.logLine("Sonde : pas de service UART"); g.disconnect(); return }
            rx = svc.getCharacteristic(NUS_RX)
            val tx = svc.getCharacteristic(NUS_TX) ?: return
            g.setCharacteristicNotification(tx, true)
            tx.getDescriptor(CCCD)?.let { d -> g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
            connected = true
            ScanRepository.setStatus { it.copy(probe = "connectée $probeName") }
            ScanRepository.logLine("Sonde : $probeName connectée")
            handler.postDelayed({ send("START") }, 500)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            buffer.append(String(value, Charsets.UTF_8))
            var idx: Int
            while (buffer.indexOf("\n").also { idx = it } >= 0) {
                val line = buffer.substring(0, idx); buffer.delete(0, idx + 1)
                handle(line)
            }
            if (buffer.length > 4000) buffer.setLength(0)
        }
    }

    private fun handle(line: String) {
        when (val m = ProbeProtocol.parse(line)) {
            is ProbeProtocol.Msg.Reading -> ScanRepository.observe(m.kind, m.id, m.name, m.rssi, m.frequency, m.caps, vendorOverride = m.vendor)
            is ProbeProtocol.Msg.Gps -> if (m.sats >= 4 && m.acc <= 15f) ScanRepository.setLocation(Location("gps").apply {
                latitude = m.lat; longitude = m.lon; accuracy = m.acc; m.alt?.let { altitude = it }; time = System.currentTimeMillis()
            })
            is ProbeProtocol.Msg.Sweep -> ScanRepository.sweep(m.freqHz, m.rssi)
            is ProbeProtocol.Msg.Info -> ScanRepository.setStatus { it.copy(probe = "connectée ${m.name} · ${m.battery} %") }
            is ProbeProtocol.Msg.Env -> {}
            is ProbeProtocol.Msg.Error -> ScanRepository.logLine("!! Sonde : ${m.text}")
            null -> {}
        }
    }

    @SuppressLint("MissingPermission")
    fun send(cmd: String) {
        val g = gatt ?: return; val c = rx ?: return
        try { g.writeCharacteristic(c, "$cmd\n".toByteArray(Charsets.UTF_8), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled || gatt != null) return
        try {
            adapter.bluetoothLeScanner?.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback
            )
            ScanRepository.setStatus { it.copy(probe = "recherche") }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() { try { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {} }

    override fun start(): Boolean {
        if (running) return true
        running = true
        startScan()
        return true
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        if (!running) return
        running = false
        stopScan()
        send("STOP")
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; connected = false
        ScanRepository.setStatus { it.copy(probe = "") }
    }
}
