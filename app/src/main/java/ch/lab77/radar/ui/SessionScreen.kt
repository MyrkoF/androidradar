package ch.lab77.radar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.DiffEntry
import ch.lab77.radar.data.DiffKind
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus
import ch.lab77.radar.data.SessionDiff
import ch.lab77.radar.data.SessionInfo
import ch.lab77.radar.export.Exporter
import ch.lab77.radar.map.NetworkState
import ch.lab77.radar.scan.SystemTweaks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran Session, DÉFILANT (retour terrain : les boutons d'export avaient disparu sous le pli). Sections
 * dépliables : état, exports, sessions (liste / reprendre / renommer / supprimer / comparer), liste blanche,
 * batterie, réglages du téléphone, journal.
 */
@Composable
fun SessionScreen(devices: Map<String, Device>, st: ScanStatus, onQuit: () -> Unit) {
    val ctx = LocalContext.current
    val all = devices.values
    var log by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) { ScanRepository.log.collect { line -> log = (listOf(line) + log).take(200) } }
    var openExports by rememberSaveable { mutableStateOf(true) }
    var openSessions by rememberSaveable { mutableStateOf(false) }
    var openSettings by rememberSaveable { mutableStateOf(false) }
    var openJournal by rememberSaveable { mutableStateOf(true) }
    val known by ScanRepository.whitelist.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val durMin = (System.currentTimeMillis() - st.sessionStart) / 60_000
            Text(
                "Session ${durMin} min · Wi-Fi ${all.count { it.kind == Kind.WIFI }} · BLE ${all.count { it.kind == Kind.BLE }} · Cell ${all.count { it.kind == Kind.CELL }} · à surveiller ${all.count { it.category.isPriority }} · connus ${all.count { it.id in known }}" +
                    (if (st.lat != null) "\n@ ${"%.5f".format(st.lat)}, ${"%.5f".format(st.lon)}" else "\nGPS : pas de fix") +
                    "\nRéseau : ${NetworkState.describe(ctx)} (fonds de carte seulement) · Capteurs : ${st.sensors.ifBlank { "(au démarrage d'un relevé)" }}" + (if (st.steps > 0) " · ${st.steps} pas" else ""),
                color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
        item { BatteryBanner(st) }
        item { Section("Exports", openExports, { openExports = !openExports }) { ExportsPanel(all, st) } }
        item { Section("Sessions et comparaison", openSessions, { openSessions = !openSessions; if (openSessions) ScanRepository.refreshSessions() }) { SessionsPanel(st, all.size) } }
        item { Section("Réglages du téléphone", openSettings, { openSettings = !openSettings }) { SettingsPanel(st, onQuit) } }
        item { Section("Journal", openJournal, { openJournal = !openJournal }) {} }
        if (openJournal) items(log) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (line.startsWith("!!")) Palette.amber else Palette.text,
                modifier = Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        item { Text(" ", fontSize = 24.sp) }
    }
}

@Composable
private fun Section(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text((if (open) "▾ " else "▸ ") + title, color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle))
        if (open) content()
    }
}

@Composable
private fun ExportsPanel(all: Collection<Device>, st: ScanStatus) {
    val ctx = LocalContext.current
    var clean by rememberSaveable { mutableStateOf(true) }
    val sel = Exporter.select(all, clean)
    val trace by ScanRepository.trace.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = clean, onCheckedChange = { clean = it })
        Text(
            if (clean) "PROPRE : ${sel.size}/${all.size} objets à position confirmée (positions calculées)" else "BRUT : tout (${all.size}), y compris passants et MAC aléatoires",
            color = if (clean) Palette.green else Palette.amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { Exporter.share(ctx, Exporter.fileName("csv"), "text/csv", Exporter.wigleCsv(sel)) },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.green, contentColor = Palette.bg)) { Text("CSV") }
        Button(onClick = { Exporter.share(ctx, Exporter.fileName("geojson"), "application/geo+json", Exporter.geoJson(sel, trace)) },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.violet, contentColor = Palette.bg)) { Text("GeoJSON") }
        Button(onClick = { Exporter.share(ctx, Exporter.fileName("json"), "application/json", Exporter.json(sel, st, clean)) },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.blue, contentColor = Palette.bg)) { Text("JSON") }
        Button(onClick = { Exporter.share(ctx, Exporter.fileName("md"), "text/plain", Exporter.debrief(sel, st, clean)) },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.amber, contentColor = Palette.bg)) { Text("Débrief") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { Exporter.share(ctx, Exporter.fileName("diag.json"), "application/json", Exporter.diagnostic(ctx, all, st)) }) { Text("Diagnostic") }
        OutlinedButton(onClick = { ScanRepository.clearSession() }) { Text("Nouvelle session") }
    }
    val counts = ScanRepository.db()?.let { runCatching { it.counts() }.getOrNull() }
    if (counts != null) Text("Base : ${counts.first} appareils, ${counts.second} observations", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

private val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT)

@Composable
private fun SessionsPanel(st: ScanStatus, currentCount: Int) {
    val ctx = LocalContext.current
    val sessions by ScanRepository.sessions.collectAsStateWithLifecycle()
    val known by ScanRepository.whitelist.collectAsStateWithLifecycle()
    var rename by remember { mutableStateOf<SessionInfo?>(null) }
    var confirmDelete by remember { mutableStateOf<SessionInfo?>(null) }
    var compareA by remember { mutableStateOf<SessionInfo?>(null) }
    var diff by remember { mutableStateOf<Triple<SessionInfo, SessionInfo, List<DiffEntry>>?>(null) }

    Text("Liste blanche : ${known.size} objets connus du lieu (pas d'alerte). « Connu ? » dans le moniteur d'un objet.", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { ScanRepository.markAllKnown() }) { Text("Tout marquer connu ($currentCount)") }
    }
    Text(
        if (compareA == null) "Comparer : choisir une première session (« A »), puis la seconde." else "A = « ${compareA!!.name} » — choisir la session à comparer.",
        color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp
    )
    for (s in sessions) {
        val current = s.id == st.sessionId
        Column(Modifier.fillMaxWidth().background(Palette.surface2).padding(6.dp)) {
            Text((if (current) "● " else "") + "${s.name} · ${dateFmt.format(Date(s.start))}" + (s.end?.let { " → ${dateFmt.format(Date(it))}" } ?: " (en cours)") + " · ${s.positioned} positionnés",
                color = if (current) Palette.green else Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!current) TextButton(onClick = { ScanRepository.resumeSession(s.id) }) { Text("Reprendre") }
                TextButton(onClick = { rename = s }) { Text("Renommer") }
                if (!current) TextButton(onClick = { confirmDelete = s }) { Text("Supprimer") }
                TextButton(onClick = {
                    val a = compareA
                    if (a == null || a.id == s.id) compareA = s
                    else { diff = Triple(a, s, SessionDiff.diff(ScanRepository.sessionEstimates(a.id), ScanRepository.sessionEstimates(s.id))); compareA = null }
                }) { Text(if (compareA?.id == s.id) "= A" else if (compareA == null) "Comparer" else "→ B") }
            }
        }
    }
    diff?.let { (a, b, entries) ->
        Column(Modifier.fillMaxWidth().background(Palette.surface2).padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Diff « ${a.name} » → « ${b.name} » : ${entries.size} différence(s) sur les positions confirmées", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            for (e in entries.take(60)) Text(
                "${e.kind.label.uppercase()} [${e.category.label}] ${e.name.ifBlank { e.id }} — ${e.detail}",
                color = when (e.kind) { DiffKind.NEW -> Palette.amber; DiffKind.GONE -> Palette.muted; DiffKind.MOVED -> Palette.violet; DiffKind.CHANGED -> Palette.blue },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp
            )
            if (entries.isEmpty()) Text("Aucune différence.", color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    val ca = ScanRepository.sessionEstimates(a.id).count { it.value.confirmed }; val cb = ScanRepository.sessionEstimates(b.id).count { it.value.confirmed }
                    Exporter.share(ctx, Exporter.fileName("diff.md"), "text/plain", SessionDiff.text(a.name, b.name, entries, ca, cb))
                }) { Text("Exporter le diff") }
                OutlinedButton(onClick = { diff = null }) { Text("Fermer") }
            }
        }
    }
    rename?.let { s ->
        var name by remember { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { rename = null },
            confirmButton = { TextButton(onClick = { ScanRepository.renameSession(s.id, name); rename = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Annuler") } },
            title = { Text("Nom de la session (le lieu)") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) }
        )
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { TextButton(onClick = { ScanRepository.deleteSession(s.id); confirmDelete = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } },
            title = { Text("Supprimer « ${s.name} » ?") },
            text = { Text("Ses ${s.positioned} positions et ses observations seront effacées. Les appareils restent dans la base commune.") }
        )
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
 * Réglages système depuis l'app (issue #9) + hauteur des yeux du télémètre (#18).
 */
@Composable
private fun SettingsPanel(st: ScanStatus, onQuit: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var throttle by remember { mutableStateOf(SystemTweaks.throttleEnabled(ctx)) }
    var canWrite by remember { mutableStateOf(SystemTweaks.canWrite(ctx)) }
    var auto by remember { mutableStateOf(SystemTweaks.autoThrottle(ctx)) }
    var eye by remember { mutableStateOf(SystemTweaks.eyeHeightCm(ctx).toString()) }
    LifecycleResumeEffect(Unit) {
        throttle = SystemTweaks.throttleEnabled(ctx); canWrite = SystemTweaks.canWrite(ctx)
        onPauseOrDispose { }
    }
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = eye, onValueChange = { v -> eye = v.filter { it.isDigit() }.take(3); eye.toIntOrNull()?.let { SystemTweaks.setEyeHeightCm(ctx, it) } },
            label = { Text("Hauteur des yeux (cm)") }, singleLine = true, modifier = Modifier.weight(1f))
        Text("pour le télémètre par visée (◎ Viser)", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }) { Text("Infos de l'app") }
        OutlinedButton(onClick = { open(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("Localisation") }
        OutlinedButton(onClick = onQuit, colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.amber)) { Text("Quitter proprement") }
    }
    Text("Quitter proprement : arrête les relevés, restaure les réglages changés, ferme l'app.", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
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
