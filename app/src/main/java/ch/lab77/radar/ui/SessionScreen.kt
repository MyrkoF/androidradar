package ch.lab77.radar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus
import ch.lab77.radar.export.Exporter
import ch.lab77.radar.map.NetworkState
import ch.lab77.radar.scan.SystemTweaks
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

@Composable
fun SessionScreen(devices: Map<String, Device>, st: ScanStatus, onQuit: () -> Unit) {
    val ctx = LocalContext.current
    val all = devices.values
    var log by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        ScanRepository.log.collect { line -> log = (listOf(line) + log).take(200) }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val durMin = (System.currentTimeMillis() - st.sessionStart) / 60_000
        Text(
            "Session ${durMin} min · Wi-Fi ${all.count { it.kind == Kind.WIFI }} · BLE ${all.count { it.kind == Kind.BLE }} · prioritaires ${all.count { it.category.isPriority }}" +
                (if (st.lat != null) "\n@ ${"%.5f".format(st.lat)}, ${"%.5f".format(st.lon)}" else "\nGPS : pas de fix"),
            color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp
        )
        Text(
            "Réseau : ${NetworkState.describe(ctx)} — utilisé uniquement pour les fonds de carte (onglet Carte)\n" +
                "Capteurs : ${st.sensors.ifBlank { "(au démarrage d'un relevé)" }}" + (if (st.steps > 0) " · ${st.steps} pas" else ""),
            color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
        BatteryBanner(st)
        SettingsPanel(st, onQuit)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Exporter.share(ctx, Exporter.fileName("csv"), "text/csv", Exporter.wigleCsv(all)) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.green, contentColor = Palette.bg)) { Text("CSV WiGLE") }
            Button(onClick = { Exporter.share(ctx, Exporter.fileName("json"), "application/json", Exporter.json(all, st)) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.blue, contentColor = Palette.bg)) { Text("JSON") }
            Button(onClick = { Exporter.share(ctx, Exporter.fileName("md"), "text/plain", Exporter.debrief(all, st)) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.amber, contentColor = Palette.bg)) { Text("Débrief") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ScanRepository.clearSession() }) { Text("Nouvelle session") }
            val counts = ScanRepository.db()?.let { runCatching { it.counts() }.getOrNull() }
            if (counts != null) Text(
                "Base : ${counts.first} appareils, ${counts.second} positions",
                color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp)
            )
        }
        Text("Journal", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        LazyColumn(Modifier.fillMaxSize().background(Palette.surface).padding(6.dp)) {
            items(log) { line ->
                Text(
                    line, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = if (line.startsWith("!!")) Palette.amber else Palette.text
                )
            }
        }
    }
}

/**
 * Optimisation batterie : si elle est active, Android (et surtout OriginOS / HyperOS) tuera le relevé
 * écran éteint. On le détecte à chaque retour sur l'écran et on guide vers les réglages.
 */
@Composable
private fun BatteryBanner(st: ScanStatus) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    var guide by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxWidth().background(Palette.surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (ignoring) "Batterie : sans restriction ✓" else "!! Optimisation batterie ACTIVE — le relevé sera tué écran éteint",
            color = if (ignoring) Palette.green else Palette.amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
        if (st.wifiOn && st.wifiThrottled) Text(
            "!! Wi-Fi bridé (4 scans / 2 min) — Options développeur → « Limitation du scan Wi-Fi » à désactiver",
            color = Palette.amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ignoring) OutlinedButton(onClick = { requestIgnoreBatteryOptimizations(ctx) }) { Text("Désactiver") }
            OutlinedButton(onClick = { guide = !guide }) { Text(if (guide) "Masquer le guide" else "Réglages téléphone") }
        }
        if (guide) Text(GUIDE, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

/**
 * Réglages système depuis l'app (issue #9) : état de la limitation du scan Wi-Fi, raccourcis vers les
 * écrans système, désactivation automatique pendant les relevés si la permission a été accordée par ADB,
 * sortie propre qui restaure tout.
 */
@Composable
private fun SettingsPanel(st: ScanStatus, onQuit: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var throttle by remember { mutableStateOf(SystemTweaks.throttleEnabled(ctx)) }
    var canWrite by remember { mutableStateOf(SystemTweaks.canWrite(ctx)) }
    var auto by remember { mutableStateOf(SystemTweaks.autoThrottle(ctx)) }
    LifecycleResumeEffect(Unit) {
        throttle = SystemTweaks.throttleEnabled(ctx); canWrite = SystemTweaks.canWrite(ctx)
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxWidth().background(Palette.surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Réglages du téléphone", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(
            "Limitation du scan Wi-Fi (option développeur) : " + when (throttle) { true -> "ACTIVE — 4 scans / 2 min"; false -> "désactivée ✓"; null -> "inconnue" },
            color = if (throttle == true) Palette.amber else Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
        if (canWrite) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = auto, onCheckedChange = { auto = it; SystemTweaks.setAutoThrottle(ctx, it) })
                Text("Désactiver automatiquement pendant les relevés, restaurer à l'arrêt", color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { SystemTweaks.setThrottle(ctx, false); throttle = SystemTweaks.throttleEnabled(ctx) }) { Text("Désactiver maintenant") }
                OutlinedButton(onClick = { SystemTweaks.setThrottle(ctx, true); throttle = SystemTweaks.throttleEnabled(ctx) }) { Text("Rétablir") }
            }
        } else {
            Text("Pour que l'app la bascule elle-même (une fois, en USB) :", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(SystemTweaks.ADB_GRANT, color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(SystemTweaks.ADB_GRANT)) }) { Text("Copier") }
                OutlinedButton(onClick = { open(ctx, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }) { Text("Options développeur") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }) { Text("Infos de l'app") }
            OutlinedButton(onClick = { open(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("Localisation") }
            OutlinedButton(onClick = onQuit, colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.amber)) { Text("Quitter proprement") }
        }
        Text("Quitter proprement : arrête les relevés, restaure les réglages changés, ferme l'app.", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

private fun open(ctx: Context, action: String) {
    try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

private fun requestIgnoreBatteryOptimizations(ctx: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    for (i in listOf(direct, list, appInfo)) {
        try { ctx.startActivity(i); return } catch (_: Exception) {}
    }
}

private val GUIDE = """
Commun (tous Android)
 · Infos de l'app → Batterie → Sans restriction
 · Options développeur → Limitation du scan Wi-Fi : désactivée
 · Ne pas fermer l'app depuis les récents pendant un relevé

vivo (OriginOS)
 · i Gestionnaire → Batterie → Consommation élevée en arrière-plan → autoriser Radar
 · i Gestionnaire → Apps → Radar → Démarrage automatique : activé
 · Récents → glisser Radar vers le bas → cadenas (verrouiller)

Xiaomi (HyperOS)
 · Sécurité → Autostart → Radar : activé
 · Réglages → Apps → Radar → Économiseur de batterie → Aucune restriction
 · Récents → appui long sur Radar → cadenas (verrouiller)

Vérification : Wi-Fi + BLE lancés, écran éteint 10 min ; le compteur doit monter
et le journal ne doit pas contenir de ligne « aucun résultat depuis ».
""".trimIndent()
