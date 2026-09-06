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
    fun kindHex(k: Kind) = when (k) { Kind.WIFI -> "#3DDC97"; Kind.BLE -> "#5CB8FF"; Kind.CELL -> "#FF8A50"; Kind.STATION -> "#D9E4E8"; Kind.LORA -> "#B39DFF" }

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
                addStringProperty("stroke", when { d.category.isPriority -> "#FF5C5C"; e.rttFix || e.bearingFix -> "#FFB74D"; e.locked -> "#D9E4E8"; else -> "#7C8F97" })
                addNumberProperty("r", when { d.id == selected -> 11f; d.category.isPriority -> 9f; else -> 6f })
                addNumberProperty("op", when {
                    selected != null && d.id != selected -> 0.3f          // un objet sélectionné : les autres s'estompent
                    e.persistence == Persistence.UNKNOWN -> 0.55f
                    else -> 1f
                })
                addStringProperty("label", if (d.category.isPriority || d.id == selected) d.name.ifBlank { d.vendor } else "")
            }
        })

    /** Cercle d'incertitude de l'objet sélectionné seulement — 80 cercles de 100 m rendent la carte illisible. */
    fun uncertainty(placed: List<Pair<Device, Estimate>>, selected: String?): FeatureCollection =
        FeatureCollection.fromFeatures(placed.filter { it.first.id == selected }.map { (d, e) ->
            val circle = TurfTransformation.circle(Point.fromLngLat(e.lon!!, e.lat!!), e.radius.toDouble(), 48, TurfConstants.UNIT_METERS)
            Feature.fromGeometry(circle).apply { addStringProperty("color", kindHex(d.kind)) }
        })

    /** Observations de l'objet sélectionné : d'où il a été vu, taille = signal. Rend le raisonnement visible. */
    fun observations(obs: List<ch.lab77.radar.data.Obs>, kind: Kind): FeatureCollection =
        FeatureCollection.fromFeatures(obs.map { o ->
            Feature.fromGeometry(Point.fromLngLat(o.lon, o.lat)).apply {
                addStringProperty("color", kindHex(kind))
                addNumberProperty("r", (2f + 6f * ((o.rssi + 100).coerceIn(0, 70) / 70f)))
            }
        })

    fun trace(points: List<DoubleArray>): FeatureCollection =
        if (points.size < 2) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it[1], it[0]) }))))

    /** Ma position + cône de cap (±30°, 20 m) quand la boussole est disponible. */
    fun me(lat: Double?, lon: Double?, heading: Float?): FeatureCollection {
        if (lat == null || lon == null) return FeatureCollection.fromFeatures(emptyList())
        val fs = mutableListOf(Feature.fromGeometry(Point.fromLngLat(lon, lat)))
        if (heading != null) {
            val kx = 111_320.0 * Math.cos(Math.toRadians(lat)); val ky = 111_320.0
            val ring = mutableListOf(Point.fromLngLat(lon, lat))
            for (a in -30..30 step 10) {
                val rad = Math.toRadians(heading + a.toDouble())
                ring += Point.fromLngLat(lon + 20 * Math.sin(rad) / kx, lat + 20 * Math.cos(rad) / ky)
            }
            ring += Point.fromLngLat(lon, lat)
            fs += Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(listOf(ring)))
        }
        return FeatureCollection.fromFeatures(fs)
    }
}
