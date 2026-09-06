package ch.lab77.radar.scan

import android.app.Activity
import android.content.Context
import android.location.Location
import ch.lab77.radar.data.Estimator
import ch.lab77.radar.data.ScanRepository
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Suivi caméra ARCore (#16, décision Myrko) : odométrie visuelle-inertielle → savoir si on a bougé de 10 cm,
 * 1 m ou 10 m, détecter l'immobilité. Tant que l'écran « Suivi caméra » est ouvert, la position du téléphone
 * est une position ESTIMÉE continue (±1,5 m + 1 % de la distance parcourue), ancrée sur la dernière position
 * acceptée ; un fix GPS ≥ 4 satellites dehors, ou « Je suis ici » dedans, re-ancre. L'app fonctionne sans.
 */
class ArTracker(private val ctx: Context) {
    var session: Session? = null
        private set
    private var origin: Pose? = null
    private var originLat = 0.0; private var originLon = 0.0
    private var yawOffsetDeg = 0.0          // cap boussole − cap ARCore au départ
    private var lastPose: Pose? = null
    private var lastMoveAt = 0L
    private var lastEmitAt = 0L
    var walkedM = 0.0
        private set
    var still = false
        private set

    fun availability(): String = try {
        when (ArCoreApk.getInstance().checkAvailability(ctx)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> "prêt"
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED, ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "à installer (Google Play Services for AR)"
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "téléphone non compatible"
            else -> "vérification…"
        }
    } catch (e: Exception) { "indisponible (${e.javaClass.simpleName})" }

    /** Propose l'installation du service AR si besoin ; true si prêt. */
    fun ensureInstalled(activity: Activity): Boolean = try {
        ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED
    } catch (e: Exception) { ScanRepository.logLine("ARCore : ${e.message}"); false }

    fun create(): Boolean = try {
        val s = Session(ctx)
        val cfg = Config(s).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            depthMode = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }
        s.configure(cfg)
        session = s
        ScanRepository.setStatus { it.copy(arTracking = "initialisation") }
        true
    } catch (e: Exception) {
        ScanRepository.logLine("ARCore : session impossible (${e.javaClass.simpleName})")
        ScanRepository.setStatus { it.copy(arTracking = "indisponible") }
        false
    }

    fun resume() { try { session?.resume() } catch (e: Exception) { ScanRepository.logLine("ARCore : ${e.message}") } }
    fun pause() { try { session?.pause() } catch (_: Exception) {} }
    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null; origin = null; lastPose = null; walkedM = 0.0
        ScanRepository.setStatus { it.copy(arTracking = "") }
    }

    /** Cap ARCore de la caméra (degrés, sens horaire depuis −Z du monde AR). */
    private fun arYawDeg(p: Pose): Double {
        val f = p.rotateVector(floatArrayOf(0f, 0f, -1f))
        return Math.toDegrees(atan2(f[0].toDouble(), -f[2].toDouble()))
    }

    /** Appelé à chaque image par le rendu (thread GL). */
    fun onFrame(frame: Frame) {
        val cam = frame.camera
        val now = System.currentTimeMillis()
        if (cam.trackingState != TrackingState.TRACKING) {
            ScanRepository.setStatus { it.copy(arTracking = if (origin == null) "initialisation — bougez lentement le téléphone" else "perdu — revenez vers un décor connu") }
            return
        }
        val pose = cam.pose
        if (origin == null) {
            val loc = ScanRepository.location ?: return
            val heading = ScanRepository.status.value.heading ?: return
            origin = pose; originLat = loc.latitude; originLon = loc.longitude
            yawOffsetDeg = heading - arYawDeg(pose)
            lastPose = pose; lastMoveAt = now
            ScanRepository.logLine("ARCore : suivi démarré depuis ${"%.5f".format(originLat)}, ${"%.5f".format(originLon)} (cap ${heading.toInt()}°)")
        }
        val o = origin!!
        lastPose?.let { lp ->
            val step = hypot((pose.tx() - lp.tx()).toDouble(), (pose.tz() - lp.tz()).toDouble())
            if (step > 0.03) { walkedM += step; lastMoveAt = now }
        }
        lastPose = pose
        still = now - lastMoveAt > 1_500
        ScanRepository.setStatus { it.copy(arTracking = if (still) "suivi · immobile · ${walkedM.toInt()} m parcourus" else "suivi · ${walkedM.toInt()} m parcourus") }
        if (still || now - lastEmitAt < 500) return
        lastEmitAt = now
        val (lat, lon) = toGeo(pose.tx() - o.tx(), pose.tz() - o.tz())
        ScanRepository.setLocation(Location("ar").apply {
            latitude = lat; longitude = lon; accuracy = (1.5 + 0.01 * walkedM).toFloat(); time = now
        }, estimated = true)
    }

    /** Déplacement AR (dx, dz) depuis l'origine → lat/lon, via le cap boussole du départ. */
    private fun toGeo(dx: Float, dz: Float): Pair<Double, Double> {
        val d = hypot(dx.toDouble(), dz.toDouble())
        val bearing = Math.toRadians(Math.toDegrees(atan2(dx.toDouble(), -dz.toDouble())) + yawOffsetDeg)
        val p = Estimator.advance(originLat, originLon, Math.toDegrees(bearing).toFloat(), d)
        return p[0] to p[1]
    }

    /** « Pointer l'objet » : point visé au centre de l'écran → position géographique + distance. */
    fun hitCenter(frame: Frame, w: Int, h: Int): Triple<Double, Double, Float>? {
        val o = origin ?: return null
        val hit = try { frame.hitTest(w / 2f, h / 2f).firstOrNull() } catch (_: Exception) { null } ?: return null
        val p = hit.hitPose
        val (lat, lon) = toGeo(p.tx() - o.tx(), p.tz() - o.tz())
        return Triple(lat, lon, hit.distance)
    }

    val hasOrigin get() = origin != null
}
