package ch.lab77.radar.scan

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor as HwSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import ch.lab77.radar.data.Estimator
import ch.lab77.radar.data.ScanRepository
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Capteurs internes (cahier §4 ter) : boussole (cap vrai), baromètre (altitude relative), podomètre
 * (estime quand le GPS décroche). Tournent avec le service de scan. Rien n'est présenté comme une mesure
 * de position : l'estime marque la position « estime » et fait grandir son incertitude à chaque pas.
 */
class PhoneSensors(ctx: Context) : Sensor, SensorEventListener {
    override val label = "capteurs"
    private val appCtx = ctx.applicationContext
    private val sm = appCtx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = sm.getDefaultSensor(HwSensor.TYPE_ROTATION_VECTOR)
    private val pressure = sm.getDefaultSensor(HwSensor.TYPE_PRESSURE)
    private val stepDetector = sm.getDefaultSensor(HwSensor.TYPE_STEP_DETECTOR)
    private var running = false
    override val isRunning: Boolean get() = running

    private val rot = FloatArray(9)
    private val rotRemap = FloatArray(9)
    private val orient = FloatArray(3)
    private var sinSum = 0.0; private var cosSum = 0.0      // lissage circulaire du cap
    private var lastHeadingPush = 0L
    private var p0: Float? = null                            // pression de référence (départ de session)
    private var lastBaroPush = 0L
    private var steps = 0
    private var lastStepAt = 0L

    /** Résumé de disponibilité pour l'écran Session. */
    fun availability(rtt: Boolean?): String = buildString {
        append("boussole ").append(if (rotation != null) "✓" else "✗")
        append(" · baromètre ").append(if (pressure != null) "✓" else "✗")
        append(" · podomètre ").append(if (stepDetector != null) "✓" else "✗")
        append(" · Wi-Fi RTT ").append(when (rtt) { true -> "✓"; false -> "✗ (matériel)"; null -> "?" })
    }

    override fun start(): Boolean {
        if (running) return true
        running = true
        rotation?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        pressure?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        try { stepDetector?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } } catch (_: SecurityException) {}
        ScanRepository.logLine("Capteurs : " + availability(null).substringBefore(" · Wi-Fi"))
        return true
    }

    override fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
        ScanRepository.setStatus { it.copy(heading = null, deadReckoning = false) }
    }

    /** Nouvelle session : la pression de référence repart de zéro. */
    fun resetBaseline() { p0 = null; steps = 0 }

    override fun onAccuracyChanged(sensor: HwSensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            HwSensor.TYPE_ROTATION_VECTOR -> onRotation(e)
            HwSensor.TYPE_PRESSURE -> onPressure(e.values[0])
            HwSensor.TYPE_STEP_DETECTOR -> onStep()
        }
    }

    private fun onRotation(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Portrait par défaut ; en paysage on remappe pour garder « le haut de l'écran = devant »
        @Suppress("DEPRECATION")
        val rotationDeg = (appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val (ax, ay) = when (rotationDeg) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, ax, ay, rotRemap)
        SensorManager.getOrientation(rotRemap, orient)
        var magnetic = Math.toDegrees(orient[0].toDouble())
        // Déclinaison magnétique → cap vrai, quand on sait où on est
        ScanRepository.location?.let { l ->
            magnetic += GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination
        }
        val rad = Math.toRadians(magnetic)
        sinSum = sinSum * 0.8 + sin(rad) * 0.2; cosSum = cosSum * 0.8 + cos(rad) * 0.2
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeadingPush >= 200) {
            lastHeadingPush = now
            val h = ((Math.toDegrees(atan2(sinSum, cosSum)) + 360.0) % 360.0).toFloat()
            ScanRepository.setHeading(h)
        }
    }

    private fun onPressure(hpa: Float) {
        if (p0 == null) p0 = hpa
        val now = SystemClock.elapsedRealtime()
        if (now - lastBaroPush < 1000) return
        lastBaroPush = now
        ScanRepository.setBaro(hpa, Estimator.baroAltitude(hpa, p0!!))
    }

    /** Un pas : si le GPS est absent ou vieux, on avance la position à l'estime (0,72 m dans la direction du cap). */
    private fun onStep() {
        steps++
        val now = System.currentTimeMillis()
        lastStepAt = now
        val st = ScanRepository.status.value
        val loc = ScanRepository.location ?: return
        val heading = st.heading ?: return
        val gpsFresh = !st.deadReckoning && now - loc.time < 10_000 && (loc.accuracy <= 40f)
        ScanRepository.setStatus { it.copy(steps = steps) }
        if (gpsFresh) return
        val p = Estimator.advance(loc.latitude, loc.longitude, heading, 0.72)
        val est = Location("estime").apply {
            latitude = p[0]; longitude = p[1]
            accuracy = (loc.accuracy + 0.3f).coerceAtMost(150f)
            if (loc.hasAltitude()) altitude = loc.altitude
            time = now; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        ScanRepository.setLocation(est, estimated = true)
    }
}
