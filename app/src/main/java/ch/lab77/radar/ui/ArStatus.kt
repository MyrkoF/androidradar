package ch.lab77.radar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.delay

/** Prérequis ARCore détecté sur le téléphone (demande Myrko) : prêt / à installer / non compatible, avec bouton Play Store. */
enum class ArState(val label: String) {
    READY("prêt ✓"), INSTALL("à installer — Google Play Services for AR"), UNSUPPORTED("téléphone non compatible (pas dans la liste ARCore de Google)"), CHECKING("vérification…"), ERROR("indisponible")
}

fun arState(ctx: Context): ArState = try {
    when (ArCoreApk.getInstance().checkAvailability(ctx)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArState.READY
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED, ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArState.INSTALL
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArState.UNSUPPORTED
        ArCoreApk.Availability.UNKNOWN_CHECKING -> ArState.CHECKING
        else -> ArState.ERROR
    }
} catch (_: Throwable) { ArState.ERROR }

@Composable
fun ArStatusLine() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(arState(ctx)) }
    LaunchedEffect(Unit) { repeat(5) { if (state == ArState.CHECKING) { delay(1000); state = arState(ctx) } } }
    val color = when (state) { ArState.READY -> Palette.green; ArState.INSTALL -> Palette.amber; ArState.UNSUPPORTED, ArState.ERROR -> Palette.red; ArState.CHECKING -> Palette.muted }
    Column(Modifier.fillMaxWidth().background(Palette.surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Prérequis suivi caméra (ARCore) : ${state.label}", color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        when (state) {
            ArState.INSTALL -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallOutlined(onClick = {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.ar.core")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: Exception) { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.ar.core")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
                }) { Text("Installer depuis le Play Store") }
            }
            ArState.UNSUPPORTED -> Text("Sur ce téléphone, le positionnement intérieur utilise pas + boussole + « Je suis ici » + ◎ Viser. ARCore s'activera seul sur un téléphone compatible.", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            else -> {}
        }
    }
}
