package ch.lab77.radar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.scan.ScanService
import ch.lab77.radar.scan.SystemTweaks
import ch.lab77.radar.ui.App
import ch.lab77.radar.ui.RadarTheme

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
            RadarTheme {
                App(
                    onWifi = { on -> withPerms(allPerms()) { ScanService.send(this, if (on) ScanService.ACTION_WIFI_ON else ScanService.ACTION_WIFI_OFF) } },
                    onBle = { on -> withPerms(allPerms()) { ScanService.send(this, if (on) ScanService.ACTION_BLE_ON else ScanService.ACTION_BLE_OFF) } },
                    onCell = { on -> withPerms(allPerms()) { ScanService.send(this, if (on) ScanService.ACTION_CELL_ON else ScanService.ACTION_CELL_OFF) } },
                    onQuit = { quitCleanly() },
                    onStopAll = { ScanService.send(this, ScanService.ACTION_STOP) },
                )
            }
        }
    }

    /** Android 13+ : tout est demandé d'un coup au premier relevé ; seule la localisation précise est bloquante. */
    private fun allPerms(): List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACTIVITY_RECOGNITION,
    )

    /** Sortie propre (issue #9) : arrête les relevés, restaure les réglages système, ferme l'app. */
    private fun quitCleanly() {
        ScanService.send(this, ScanService.ACTION_STOP)
        SystemTweaks.restore(this)
        finishAndRemoveTask()
    }

    private fun withPerms(perms: List<String>, action: () -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { pending = action; askPerms.launch(missing.toTypedArray()) }
    }
}
