package ch.lab77.radar.map

import ch.lab77.radar.data.Device
import ch.lab77.radar.data.Estimate
import ch.lab77.radar.data.Kind
import ch.lab77.radar.data.Persistence
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.turf.TurfConstants
import org.maplibre.turf.TurfTransformation

/** Construit les couches GeoJSON de la carte à partir de l'état du dépôt. Pur, sans référence à l'UI. */
object GeoJson {
    fun kindHex(k: Kind) = if (k == Kind.WIFI) "#3DDC97" else "#5CB8FF"

    /** Appareils à poser sur la carte : ceux qui ont une position estimée, filtrés par persistance (cahier §3 bis). */
    fun placed(devices: Map<String, Device>, estimates: Map<String, Estimate>, showAll: Boolean, accepts: (Device) -> Boolean): List<Pair<Device, Estimate>> =
        devices.values.mapNotNull { d ->
            val e = estimates[d.id] ?: return@mapNotNull null
            if (e.lat == null || e.lon == null) return@mapNotNull null
            if (!accepts(d)) return@mapNotNull null
            if (!showAll && (!e.persistence.onMapByDefault || d.category == ch.lab77.radar.data.Category.RANDOMIZED)) return@mapNotNull null
            d to e
        }

    fun devices(placed: List<Pair<Device, Estimate>>, selected: String?): FeatureCollection =
        FeatureCollection.fromFeatures(placed.map { (d, e) ->
            Feature.fromGeometry(Point.fromLngLat(e.lon!!, e.lat!!)).apply {
                addStringProperty("id", d.id)
                addStringProperty("color", kindHex(d.kind))
                addStringProperty("stroke", if (d.category.isPriority) "#FF5C5C" else "#FFFFFF")
                addNumberProperty("r", when { d.id == selected -> 11f; d.category.isPriority -> 9f; else -> 6f })
                addNumberProperty("op", if (e.persistence == Persistence.UNKNOWN) 0.55f else 1f)
                addStringProperty("label", if (d.category.isPriority || d.id == selected) d.name.ifBlank { d.vendor } else "")
            }
        })

    fun uncertainty(placed: List<Pair<Device, Estimate>>): FeatureCollection =
        FeatureCollection.fromFeatures(placed.map { (d, e) ->
            val circle = TurfTransformation.circle(Point.fromLngLat(e.lon!!, e.lat!!), e.radius.toDouble(), 48, TurfConstants.UNIT_METERS)
            Feature.fromGeometry(circle).apply { addStringProperty("color", kindHex(d.kind)) }
        })

    fun trace(points: List<DoubleArray>): FeatureCollection =
        if (points.size < 2) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it[1], it[0]) }))))

    fun me(lat: Double?, lon: Double?): FeatureCollection =
        if (lat == null || lon == null) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(lon, lat))))
}
