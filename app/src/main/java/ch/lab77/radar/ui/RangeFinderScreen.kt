package ch.lab77.radar.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.RangeFinder
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus
import ch.lab77.radar.scan.SystemTweaks

/**
 * Télémètre par visée (#18) : aperçu caméra avec réticule, téléphone à hauteur des yeux, on vise le pied
 * de l'objet ; l'inclinaison donne la distance. « Enregistrer » l'injecte comme distance mesurée (même
 * chemin que le Wi-Fi RTT → trilatération). Sans permission caméra : réticule seul, ça marche quand même.
 */
@Composable
fun RangeFinderScreen(d: Device, st: ScanStatus, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camGranted = it }
    LaunchedEffect(Unit) { if (!camGranted) ask.launch(Manifest.permission.CAMERA) }
    val eyeM = SystemTweaks.eyeHeightCm(ctx) / 100f
    val pitch = st.pitch
    val dist = pitch?.let { RangeFinder.distanceM(eyeM, it) }
    var saved by remember { mutableStateOf<Float?>(null) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            if (camGranted) AndroidView(
                factory = { c ->
                    PreviewView(c).also { pv ->
                        val future = ProcessCameraProvider.getInstance(c)
                        future.addListener({
                            try {
                                val provider = future.get()
                                val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                            } catch (_: Exception) {}
                        }, ContextCompat.getMainExecutor(c))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(Palette.green, 28.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                drawLine(Palette.green, Offset(c.x - 40.dp.toPx(), c.y), Offset(c.x + 40.dp.toPx(), c.y), 2.dp.toPx())
                drawLine(Palette.green, Offset(c.x, c.y - 40.dp.toPx()), Offset(c.x, c.y + 40.dp.toPx()), 2.dp.toPx())
            }
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Palette.surface.copy(alpha = 0.85f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Viser le PIED de : ${d.name.ifBlank { d.vendor.ifBlank { d.id } }}", color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("Téléphone vertical à hauteur des yeux (${(eyeM * 100).toInt()} cm, réglable dans Session → Réglages)", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(
                    when {
                        pitch == null -> "Pas de boussole / gyroscope : impossible"
                        dist == null -> "Inclinaison ${"%.0f".format(pitch)}° — visez plus bas, vers le sol"
                        else -> "Inclinaison ${"%.0f".format(pitch)}° → distance ≈ ${"%.1f".format(dist)} m (± ${"%.0f".format(dist * RangeFinder.REL_ERROR)} m)"
                    },
                    color = if (dist != null) Palette.green else Palette.amber, fontFamily = FontFamily.Monospace, fontSize = 14.sp
                )
                if (!camGranted) Text("Sans caméra : visez « à l'œil » par-dessus le téléphone.", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                if (saved != null) Text("✓ ${"%.1f".format(saved)} m enregistrée → position recalculée", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Palette.surface.copy(alpha = 0.85f)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = dist != null, onClick = {
                    dist?.let { ScanRepository.setRanging(d.id, it, it * RangeFinder.REL_ERROR); saved = it }
                }) { Text("Enregistrer la distance") }
                OutlinedButton(onClick = onClose) { Text("Fermer") }
            }
        }
    }
}
