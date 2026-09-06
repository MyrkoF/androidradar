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
    private val accel = sm.getDefaultSensor(HwSensor.TYPE_ACCELEROMETER)
    private val gyro = sm.getDefaultSensor(HwSensor.TYPE_GYROSCOPE)
    private var running = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Agrégats par seconde pour le diagnostic (« la totale », #13)
    private var gyroSum = 0.0; private var gyroMax = 0f; private var gyroN = 0
    private var accSum = 0.0; private var accMax = 0f; private var accN = 0
    private var headingAcc = -1
    private var lastPressure: Float? = null
    private var turnEma = 0.0                                   // °/s lissé
    private var lastPacePush = 0L
    private val stepTimes = ArrayDeque<Long>()
    private val oneHz = object : Runnable {
        override fun run() {
            if (!running) return
            val st = ScanRepository.status.value
            ScanRepository.recordSensors(ScanRepository.SensorRow(
                System.currentTimeMillis(), st.heading, headingAcc,
                if (gyroN > 0) (gyroSum / gyroN).toFloat() else 0f, gyroMax,
                if (accN > 0) (accSum / accN).toFloat() else 0f, accMax,
                lastPressure, st.baroAltM, steps, st.accuracy, st.satsUsed, st.satsVisible, st.deadReckoning,
            ))
            gyroSum = 0.0; gyroMax = 0f; gyroN = 0; accSum = 0.0; accMax = 0f; accN = 0
            handler.postDelayed(this, 1000)
        }
    }

    /** Inventaire des capteurs physiques du téléphone (diagnostic). */
    fun inventory(): List<String> = sm.getSensorList(HwSensor.TYPE_ALL).map { "${it.name} (type ${it.type}, ${it.vendor})" }
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
        append(" · gyro ").append(if (gyro != null) "✓" else "✗")
        append(" · Wi-Fi RTT ").append(when (rtt) { true -> "✓"; false -> "✗ (matériel)"; null -> "?" })
    }

    override fun start(): Boolean {
        if (running) return true
        running = true
        rotation?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        pressure?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        val stepsOk = try { stepDetector?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } ?: false } catch (_: SecurityException) { false }
        ScanRepository.setStatus { it.copy(stepsKnown = stepsOk) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        handler.postDelayed(oneHz, 1000)
        ScanRepository.logLine("Capteurs : " + availability(null).substringBefore(" · Wi-Fi"))
        return true
    }

    override fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(oneHz)
        sm.unregisterListener(this)
        ScanRepository.setStatus { it.copy(heading = null, deadReckoning = false) }
    }

    /** Nouvelle session : la pression de référence repart de zéro. */
    fun resetBaseline() { p0 = null; steps = 0 }

    override fun onAccuracyChanged(sensor: HwSensor?, accuracy: Int) {
        if (sensor?.type == HwSensor.TYPE_ROTATION_VECTOR) headingAcc = accuracy   // 0 = à recalibrer (mouvement en 8), 3 = haute
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            HwSensor.TYPE_ROTATION_VECTOR -> onRotation(e)
            HwSensor.TYPE_PRESSURE -> onPressure(e.values[0])
            HwSensor.TYPE_STEP_DETECTOR -> onStep()
            HwSensor.TYPE_ACCELEROMETER -> { val m = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]); accSum += m; accN++; if (m > accMax) accMax = m }
            HwSensor.TYPE_GYROSCOPE -> {
                val m = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]); gyroSum += m; gyroN++; if (m > gyroMax) gyroMax = m
                turnEma = turnEma * 0.85 + Math.toDegrees(m.toDouble()) * 0.15
                val now = SystemClock.elapsedRealtime()
                if (now - lastPacePush >= 300) {
                    lastPacePush = now
                    val t = System.currentTimeMillis()
                    while (stepTimes.isNotEmpty() && t - stepTimes.first() > 5_000) stepTimes.removeFirst()
                    val rate = stepTimes.size / 5f
                    ScanRepository.setStatus { it.copy(turnRateDps = turnEma.toFloat(), stepRate = rate) }
                }
            }
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
            // Élévation de l'axe de la caméra arrière (−Z de l'appareil) : asin(−R[8]), négatif = vers le bas.
            // Indépendant de la rotation d'écran → télémètre par visée.
            val pitch = Math.toDegrees(kotlin.math.asin((-rot[8]).toDouble().coerceIn(-1.0, 1.0))).toFloat()
            ScanRepository.setHeading(h, pitch)
        }
    }

    private fun onPressure(hpa: Float) {
        lastPressure = hpa
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
        stepTimes.addLast(now)
        ScanRepository.setStatus { it.copy(steps = steps) }
        val st = ScanRepository.status.value
        val heading = st.heading ?: return
        val accepted = ScanRepository.acceptedFix ?: return
        // Dehors avec un bon fix récent, le GPS mène ; en intérieur (précision > 30 m) ou sans fix, les pas mènent
        if (!ch.lab77.radar.data.PositionFilter.stepsDrive(accepted, now)) return
        val p = Estimator.advance(accepted.lat, accepted.lon, heading, 0.72)
        val est = Location("estime").apply {
            latitude = p[0]; longitude = p[1]
            accuracy = (accepted.acc + 0.3f).coerceAtMost(150f)
            ScanRepository.location?.takeIf { it.hasAltitude() }?.let { altitude = it.altitude }
            time = now; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        ScanRepository.setLocation(est, estimated = true)
    }
}
