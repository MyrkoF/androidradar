package ch.lab77.radar.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/** Une seule utilisation de la caméra à la fois : les écrans Viser / AR le déclarent, la vignette s'efface. */
object CameraUse {
    var busy by mutableStateOf(false)
    var thumb by mutableStateOf(false)      // vignette activée — commune à la carte et au radar
}

/**
 * Vignette caméra (demande Myrko) : petit rectangle avec la vue de la caméra en direct par-dessus la carte ou
 * le radar, comme le viseur d'un appareil photo. Toucher = ouvrir l'écran de visée de l'objet sélectionné.
 */
@Composable
fun CameraThumb(onTap: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (CameraUse.busy) return
    Box(Modifier.size(110.dp, 150.dp).border(1.dp, Palette.green).background(Palette.surface).clickable { if (granted) onTap() else ask.launch(Manifest.permission.CAMERA) }) {
        if (granted) {
            var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
            AndroidView(factory = { c ->
                PreviewView(c).also { pv ->
                    pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        try {
                            val p = future.get(); provider = p
                            val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                            p.unbindAll(); p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(c))
                }
            }, modifier = Modifier.size(110.dp, 150.dp))
            DisposableEffect(Unit) { onDispose { try { provider?.unbindAll() } catch (_: Exception) {} } }
            Text("◎ viser", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).background(Palette.bg.copy(alpha = 0.6f)))
        } else Text("📷\ntoucher pour\nautoriser", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
    }
}
