package org.equalium.sonde

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.equalium.sonde.data.ScanRepository
import org.equalium.sonde.scan.ScanService
import org.equalium.sonde.ui.App
import org.equalium.sonde.ui.SondeTheme

class MainActivity : ComponentActivity() {
    private var pending: (() -> Unit)? = null

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fine) pending?.invoke()
        else Toast.makeText(this, "La localisation précise est requise par Android pour scanner.", Toast.LENGTH_LONG).show()
        pending = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScanRepository.init(this)
        setContent {
            SondeTheme {
                App(
                    onWifi = { on -> withPerms(wifiPerms()) { ScanService.send(this, if (on) ScanService.ACTION_WIFI_ON else ScanService.ACTION_WIFI_OFF) } },
                    onBle = { on -> withPerms(blePerms()) { ScanService.send(this, if (on) ScanService.ACTION_BLE_ON else ScanService.ACTION_BLE_OFF) } },
                )
            }
        }
    }

    private fun basePerms(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun wifiPerms(): List<String> = basePerms() + buildList<String> {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    private fun blePerms(): List<String> = basePerms() + buildList<String> {
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
    }

    private fun withPerms(perms: List<String>, action: () -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { pending = action; askPerms.launch(missing.toTypedArray()) }
    }
}
