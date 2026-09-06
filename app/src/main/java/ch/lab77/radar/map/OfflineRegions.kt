package ch.lab77.radar.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Emprises hors ligne (cahier §8) : téléchargement explicite d'une zone (tuiles + style + polices + sprites)
 * via le gestionnaire hors ligne de MapLibre. Jamais automatique.
 */
object OfflineRegions {
    data class Info(val id: Long, val name: String, val bytes: Long, val complete: Boolean)
    data class Progress(val name: String, val done: Long, val required: Long, val bytes: Long, val complete: Boolean, val error: String? = null)

    private val _regions = MutableStateFlow<List<Info>>(emptyList())
    val regions = _regions.asStateFlow()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress = _progress.asStateFlow()
    private var current: OfflineRegion? = null

    private fun manager(ctx: Context): OfflineManager {
        MapConfig.init(ctx)
        return OfflineManager.getInstance(ctx.applicationContext).also { it.setOfflineMapboxTileCountLimit(Long.MAX_VALUE) }
    }

    fun refresh(ctx: Context) {
        manager(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regs = offlineRegions ?: emptyArray()
                if (regs.isEmpty()) { _regions.value = emptyList(); return }
                val out = ArrayList<Info>()
                for (r in regs) {
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            synchronized(out) {
                                out += Info(r.id, nameOf(r), status?.completedResourceSize ?: 0L, status?.isComplete ?: false)
                                if (out.size == regs.size) _regions.value = out.sortedBy { it.id }
                            }
                        }
                        override fun onError(error: String?) {
                            synchronized(out) {
                                out += Info(r.id, nameOf(r), 0L, false)
                                if (out.size == regs.size) _regions.value = out.sortedBy { it.id }
                            }
                        }
                    })
                }
            }
            override fun onError(error: String) { _progress.value = Progress("", 0, 0, 0, false, "liste : $error") }
        })
    }

    private fun nameOf(r: OfflineRegion): String =
        try { JSONObject(String(r.metadata, Charsets.UTF_8)).optString("name", "zone ${r.id}") } catch (_: Exception) { "zone ${r.id}" }

    /** Nombre de tuiles d'une emprise entre deux niveaux de zoom (pour afficher une estimation avant de télécharger). */
    fun estimateTiles(b: LatLngBounds, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val n = 2.0.pow(z)
            val x1 = floor((b.longitudeWest + 180) / 360 * n); val x2 = floor((b.longitudeEast + 180) / 360 * n)
            val y1 = floor((1 - ln(tan(Math.toRadians(b.latitudeNorth)) + 1 / Math.cos(Math.toRadians(b.latitudeNorth))) / PI) / 2 * n)
            val y2 = floor((1 - ln(tan(Math.toRadians(b.latitudeSouth)) + 1 / Math.cos(Math.toRadians(b.latitudeSouth))) / PI) / 2 * n)
            total += ((x2 - x1 + 1) * (y2 - y1 + 1)).toLong()
        }
        return total
    }

    fun download(ctx: Context, name: String, bounds: LatLngBounds, minZoom: Int, maxZoom: Int, pixelRatio: Float) {
        if (current != null) return
        val def = OfflineTilePyramidRegionDefinition(MapConfig.STYLE_URL, bounds, minZoom.toDouble(), maxZoom.toDouble(), pixelRatio)
        val meta = JSONObject().put("name", name).put("created", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
        _progress.value = Progress(name, 0, 0, 0, false)
        manager(ctx).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                current = offlineRegion
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        _progress.value = Progress(name, status.completedResourceCount, status.requiredResourceCount, status.completedResourceSize, status.isComplete)
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            offlineRegion.setObserver(null)
                            current = null
                            refresh(ctx)
                        }
                    }
                    override fun onError(error: OfflineRegionError) {
                        _progress.value = _progress.value?.copy(error = "${error.reason}: ${error.message}")
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        _progress.value = _progress.value?.copy(error = "limite de tuiles ($limit)")
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) { _progress.value = Progress(name, 0, 0, 0, false, error); current = null }
        })
    }

    fun cancel() {
        current?.let { it.setDownloadState(OfflineRegion.STATE_INACTIVE); it.setObserver(null) }
        current = null
        _progress.value = null
    }

    fun delete(ctx: Context, id: Long) {
        manager(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions?.firstOrNull { it.id == id }?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() { refresh(ctx) }
                    override fun onError(error: String) { _progress.value = Progress("", 0, 0, 0, false, "suppression : $error") }
                })
            }
            override fun onError(error: String) {}
        })
    }
}
