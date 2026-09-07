package ch.lab77.radar.ui

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.data.ScanStatus
import ch.lab77.radar.map.GeoJson
import ch.lab77.radar.map.MapConfig
import ch.lab77.radar.map.NetworkState
import ch.lab77.radar.map.OfflineRegions
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Carte (cahier §4) : fond MapLibre + OpenFreeMap, position du téléphone, trace GPS de la session,
 * objets Wi-Fi/BLE à leur position estimée avec cercle d'incertitude, tap = fiche détail.
 * Par défaut seuls les objets stationnaires (et indéterminés, estompés) sont posés (cahier §3 bis).
 * Téléchargement d'emprise explicite (cahier §8).
 */
@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun MapScreen(devices: Map<String, Device>, st: ScanStatus) {
    val ctx = LocalContext.current
    remember { MapConfig.init(ctx); true }
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(ctx).also { it.onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var followMe by rememberSaveable { mutableStateOf(true) }
    var panel by rememberSaveable { mutableStateOf(false) }
    var maxZoom by rememberSaveable { mutableStateOf(15) }
    var headUp by rememberSaveable { mutableStateOf(false) }
    var manual by remember { mutableStateOf<LatLng?>(null) }
    var arOpen by rememberSaveable { mutableStateOf(false) }
    var aimOpen by rememberSaveable { mutableStateOf(false) }
    var savedCam by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    val estimates by ScanRepository.estimates.collectAsStateWithLifecycle()
    val trace by ScanRepository.trace.collectAsStateWithLifecycle()
    val progress by OfflineRegions.progress.collectAsStateWithLifecycle()
    val regions by OfflineRegions.regions.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            map?.cameraPosition?.target?.let { t -> savedCam = doubleArrayOf(t.latitude, t.longitude, map?.cameraPosition?.zoom ?: 15.0) }
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        OfflineRegions.refresh(ctx)
        mapView.getMapAsync { m ->
            map = m
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) followMe = false
            }
            m.addOnMapLongClickListener { ll -> manual = ll; true }
            m.setMaxZoomPreference(22.0)
            m.addOnMapClickListener { ll ->
                val pt: PointF = m.projection.toScreenLocation(ll)
                val r = 24f * ctx.resources.displayMetrics.density
                val hits = m.queryRenderedFeatures(android.graphics.RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r), "devices")
                // le plus proche du doigt
                selected = hits.minByOrNull { f ->
                    val g = f.geometry() as? org.maplibre.geojson.Point ?: return@minByOrNull Double.MAX_VALUE
                    val sp = m.projection.toScreenLocation(LatLng(g.latitude(), g.longitude()))
                    (sp.x - pt.x) * (sp.x - pt.x) + (sp.y - pt.y) * (sp.y - pt.y).toDouble()
                }?.getStringProperty("id")
                true
            }
            m.setStyle(Style.Builder().fromUri(MapConfig.STYLE_URL)) { style ->
                style.addSource(GeoJsonSource("uncert"))
                style.addSource(GeoJsonSource("obs"))
                style.addSource(GeoJsonSource("trace"))
                style.addSource(GeoJsonSource("devices"))
                style.addSource(GeoJsonSource("me"))
                style.addLayer(FillLayer("uncert-fill", "uncert").withProperties(
                    PropertyFactory.fillColor(Expression.toColor(Expression.get("color"))), PropertyFactory.fillOpacity(0.10f)))
                style.addLayer(LineLayer("uncert-line", "uncert").withProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))), PropertyFactory.lineWidth(1f), PropertyFactory.lineOpacity(0.5f)))
                style.addLayer(CircleLayer("obs", "obs").withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(Expression.get("r")), PropertyFactory.circleOpacity(0.5f),
                    PropertyFactory.circleStrokeColor("#0B1215"), PropertyFactory.circleStrokeWidth(0.5f)))
                style.addLayer(LineLayer("trace-line", "trace").withProperties(
                    PropertyFactory.lineColor("#3DDC97"), PropertyFactory.lineWidth(3f), PropertyFactory.lineOpacity(0.7f)))
                style.addLayer(CircleLayer("devices", "devices").withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(Expression.get("r")),
                    PropertyFactory.circleOpacity(Expression.get("op")),
                    PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("stroke"))),
                    PropertyFactory.circleStrokeWidth(1.5f)))
                style.addLayer(SymbolLayer("devices-label", "devices").withProperties(
                    PropertyFactory.textField(Expression.get("label")), PropertyFactory.textFont(MapConfig.FONTS),
                    PropertyFactory.textSize(11f), PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
                    PropertyFactory.textColor("#D9E4E8"), PropertyFactory.textHaloColor("#0B1215"), PropertyFactory.textHaloWidth(1.2f),
                    PropertyFactory.textOptional(true)))
                style.addLayer(FillLayer("me-cone", "me").withProperties(
                    PropertyFactory.fillColor("#5CB8FF"), PropertyFactory.fillOpacity(0.25f)))
                style.addLayer(CircleLayer("me", "me").withProperties(
                    PropertyFactory.circleColor("#5CB8FF"), PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
                val cam = savedCam
                if (cam != null) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(cam[0], cam[1]), cam[2]))
                else if (st.lat != null && st.lon != null) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(st.lat, st.lon), 16.0))
                styleReady = true
            }
        }
    }

    // Objets + cercles : au plus une mise à jour par seconde, quel que soit le rythme des scans
    LaunchedEffect(styleReady) {
        if (!styleReady) return@LaunchedEffect
        snapshotFlow { Triple(devices, estimates, showAll to selected) }.sample(1000).collect { (devs, ests, flags) ->
            val style = map?.style ?: return@collect
            val placed = GeoJson.placed(devs, ests, flags.first, ViewFilter::accepts)
            style.getSourceAs<GeoJsonSource>("devices")?.setGeoJson(GeoJson.devices(placed, flags.second))
            style.getSourceAs<GeoJsonSource>("uncert")?.setGeoJson(GeoJson.uncertainty(placed, flags.second))
            val selDev = flags.second?.let { devs[it] }
            style.getSourceAs<GeoJsonSource>("obs")?.setGeoJson(
                if (selDev != null) GeoJson.observations(ScanRepository.observationsOf(selDev.id), selDev.kind) else GeoJson.observations(emptyList(), ch.lab77.radar.data.Kind.WIFI))
        }
    }
    LaunchedEffect(styleReady) {
        if (!styleReady) return@LaunchedEffect
        snapshotFlow { trace }.sample(2000).collect { map?.style?.getSourceAs<GeoJsonSource>("trace")?.setGeoJson(GeoJson.trace(it)) }
    }
    val headingRounded = st.heading?.let { (it / 5).toInt() * 5 }
    LaunchedEffect(styleReady, st.lat, st.lon, followMe, headingRounded, headUp) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.getSourceAs<GeoJsonSource>("me")?.setGeoJson(GeoJson.me(st.lat, st.lon, st.heading))
        val m = map ?: return@LaunchedEffect
        if (followMe && st.lat != null && st.lon != null) {
            if (headUp && headingRounded != null) m.animateCamera(CameraUpdateFactory.newCameraPosition(
                org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(st.lat, st.lon)).bearing(headingRounded.toDouble()).zoom(m.cameraPosition.zoom).build()))
            else m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(st.lat, st.lon)))
        } else if (!headUp && m.cameraPosition.bearing != 0.0) m.animateCamera(CameraUpdateFactory.bearingTo(0.0))
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (CameraUse.thumb) Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                CameraThumb { if (selected != null) aimOpen = true else arOpen = true }
            }
            val aimDev = selected?.let { devices[it] }
            if (aimOpen && aimDev != null) RangeFinderScreen(aimDev, st) { aimOpen = false }
            Column(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalAlignment = Alignment.End) {
                Box(Modifier.background(Palette.surface.copy(alpha = 0.85f))) { FilterMenu() }
                val sel = selected?.let { devices[it] }
                if (sel != null) Box(Modifier.padding(top = 4.dp)) { Monitor(sel, estimates[sel.id], st) { selected = null } }
            }
        }
        val placedCount = remember(devices, estimates, showAll) { GeoJson.placed(devices, estimates, showAll, ViewFilter::accepts).size }
        Column(Modifier.fillMaxWidth().background(Palette.surface).padding(8.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactChip(followMe, {
                    followMe = true
                    if (st.lat != null && st.lon != null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(st.lat, st.lon), 16.0))
                }, "Suivre")
                CompactChip(headUp, { headUp = !headUp; if (headUp) followMe = true }, "Orienter", enabled = st.heading != null)
                CompactChip(showAll, { showAll = !showAll }, if (showAll) "Tout" else "Stationnaires")
                CompactChip(panel, { panel = !panel; if (panel) OfflineRegions.refresh(ctx) }, "Hors ligne")
                CompactChip(arOpen, { arOpen = true }, "📷 Caméra")
                CompactChip(CameraUse.thumb, { CameraUse.thumb = !CameraUse.thumb }, "Vignette")
            }
            if (arOpen) ArScreen(selected?.let { devices[it] }) { arOpen = false }
            manual?.let { ll ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { manual = null },
                    confirmButton = { SmallText(onClick = { ScanRepository.setManualPosition(ll.latitude, ll.longitude); followMe = true; manual = null }) { Text("Je suis ici") } },
                    dismissButton = { SmallText(onClick = { manual = null }) { Text("Annuler") } },
                    title = { Text("Poser ma position ici ?") },
                    text = { Text("Ancre en intérieur (±3 m) : les pas, la boussole et la caméra repartent de ce point ; un bon fix GPS reprendra la main dehors.") }
                )
            }
            Text(
                "Suivre = centrer sur moi · Orienter = cap en haut · Stationnaires⇄Tout = montrer ou non passants, MAC aléatoires, indéterminés · Hors ligne = télécharger la vue · 📷 Caméra = suivi ARCore (Google Play Services for AR requis) · appui long sur la carte = « Je suis ici » · tap un objet → moniteur : ◎ Viser, 📷 Pointer, Connu\n" +
                    "$placedCount posés · ● Wi-Fi ● BLE ● Cell · anneau rouge = à surveiller · estompé = indéterminé · tap = moniteur ; l'objet choisi montre ses points d'observation (taille = signal) et son cercle d'incertitude\n" +
                    MapConfig.ATTRIBUTION,
                color = Palette.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            if (panel) OfflinePanel(map, maxZoom, { maxZoom = it }, progress, regions)
        }
    }
}

@Composable
private fun OfflinePanel(map: MapLibreMap?, maxZoom: Int, onZoom: (Int) -> Unit, progress: OfflineRegions.Progress?, regions: List<OfflineRegions.Info>) {
    val ctx = LocalContext.current
    val bounds = map?.projection?.visibleRegion?.latLngBounds
    val minZoom = 6
    val tiles = bounds?.let { OfflineRegions.estimateTiles(it, minZoom, maxZoom) } ?: 0L
    val tooBig = tiles > 12_000
    val downloading = progress != null && !progress.complete && progress.error == null
    Column(Modifier.fillMaxWidth().background(Palette.surface2).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Réseau : ${NetworkState.describe(ctx)} · la vue actuelle devient une zone hors ligne (zoom $minZoom → $maxZoom, ≈ $tiles tuiles)",
            color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (z in listOf(13, 14, 15, 16)) CompactChip(maxZoom == z, { onZoom(z) }, "z$z")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallOutlined(enabled = bounds != null && !downloading && !tooBig, onClick = {
                val name = "Zone " + SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date())
                OfflineRegions.download(ctx, name, bounds!!, minZoom, maxZoom, ctx.resources.displayMetrics.density)
            }) { Text("Télécharger la vue") }
            if (downloading) SmallOutlined(onClick = { OfflineRegions.cancel() }) { Text("Annuler") }
            if (tooBig) Text("trop grand : zoomer ou baisser z", color = Palette.amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        if (progress != null) Text(
            when {
                progress.error != null -> "!! ${progress.name} : ${progress.error}"
                progress.complete -> "✓ ${progress.name} : ${progress.bytes / 1_000_000} Mo"
                else -> "${progress.name} : ${progress.done}/${progress.required} ressources · ${progress.bytes / 1_000_000} Mo"
            },
            color = if (progress.error != null) Palette.amber else Palette.green, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
        for (r in regions) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${r.name} · ${r.bytes / 1_000_000} Mo" + (if (!r.complete) " (incomplet)" else ""), color = Palette.text,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f))
            SmallOutlined(onClick = { OfflineRegions.delete(ctx, r.id) }) { Text("Suppr.") }
        }
        if (regions.isEmpty()) Text("Aucune zone hors ligne. Sans zone, la carte a besoin du réseau.", color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
