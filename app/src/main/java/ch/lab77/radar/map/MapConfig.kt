package ch.lab77.radar.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.maplibre.android.MapLibre

/**
 * Seul package de l'app à toucher au réseau (cahier §8) : fonds de carte OpenFreeMap (tuiles vectorielles
 * OSM, sans clé, sans quota) rendus par MapLibre Native, téléchargés sur le téléphone pour l'usage hors ligne.
 */
object MapConfig {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val ATTRIBUTION = "OpenFreeMap · © OpenMapTiles · © contributeurs OpenStreetMap"
    val FONTS = arrayOf("Noto Sans Regular")

    @Volatile private var initialized = false

    /** À appeler avant toute création de MapView ou d'OfflineManager. */
    fun init(ctx: Context) {
        if (!initialized) { MapLibre.getInstance(ctx.applicationContext); initialized = true }
    }
}

/** État réseau, affiché dans l'écran Session pour que l'usage du réseau reste visible (cahier §8). */
object NetworkState {
    fun describe(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "hors ligne"
        return when {
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "hors ligne"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "données mobiles"
            else -> "connecté"
        }
    }
}
