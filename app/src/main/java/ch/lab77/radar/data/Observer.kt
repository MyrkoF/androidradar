package ch.lab77.radar.data

/**
 * L'observateur (le téléphone) doit être calibré avant que ses observations servent à positionner (cahier §3 quater).
 * État : non calibré / calibré (±acc) / précision perdue. Le budget se dégrade avec la dérive (pas, boussole) et se
 * regagne avec une ancre : fix GPS fiable dehors, « Je suis ici » ou ancre nommée dedans, suivi caméra. Pur, testé.
 */
enum class CalState { UNCALIBRATED, CALIBRATED, DEGRADED }
enum class Env { INDOOR, OUTDOOR }
enum class EnvMode { AUTO, INDOOR, OUTDOOR }

data class Observer(
    val state: CalState = CalState.UNCALIBRATED,
    val env: Env = Env.INDOOR,
    val acc: Float = Float.MAX_VALUE,      // précision courante estimée, m
    val source: String = "",               // "GPS 8 sat", "Je suis ici", "ancre Porte", "caméra"
    val anchorT: Long = 0L,
    val stepsSinceAnchor: Int = 0,
    val walkedSinceAnchor: Double = 0.0,
) {
    val ok get() = state == CalState.CALIBRATED
}

object ObserverRules {
    const val INDOOR_M = 5f
    const val OUTDOOR_M = 10f
    const val DRIFT_PER_STEP_M = 0.10       // erreur de longueur de pas
    const val HEADING_DRIFT = 0.06          // ~3,5° d'erreur de cap → 6 % de la distance
    const val CAL_MIN_SATS = 6
    const val CAL_MAX_ACC_M = 10f
    const val CAL_WINDOW_MS = 20_000L
    const val CAL_MIN_FIXES = 3
    const val CAL_MAX_SPREAD_M = 10.0
    const val OUTDOOR_RECENT_MS = 30_000L

    fun threshold(env: Env) = if (env == Env.INDOOR) INDOOR_M else OUTDOOR_M

    /** Extérieur si un fix GPS fiable (≥ 6 satellites, ≤ 15 m) est arrivé il y a moins de 30 s ; forçable. */
    fun env(mode: EnvMode, lastTrustedFixT: Long, now: Long): Env = when (mode) {
        EnvMode.INDOOR -> Env.INDOOR
        EnvMode.OUTDOOR -> Env.OUTDOOR
        EnvMode.AUTO -> if (now - lastTrustedFixT <= OUTDOOR_RECENT_MS) Env.OUTDOOR else Env.INDOOR
    }

    /** Calibration extérieure : au moins 3 fixes fiables ≤ 10 m en 20 s, groupés à 10 m près. */
    fun outdoorCalibrated(fixes: List<PositionFilter.Fix>, now: Long): Boolean {
        val recent = fixes.filter { now - it.t <= CAL_WINDOW_MS && it.acc <= CAL_MAX_ACC_M }
        if (recent.size < CAL_MIN_FIXES) return false
        val f = recent.first()
        return recent.all { Estimator.distanceM(f.lat, f.lon, it.lat, it.lon) <= CAL_MAX_SPREAD_M }
    }

    /** Précision courante = précision de l'ancre + dérive depuis (pas et cap). */
    fun currentAcc(anchorAcc: Float, stepsSince: Int, walkedSince: Double): Float =
        (anchorAcc + stepsSince * DRIFT_PER_STEP_M + walkedSince * HEADING_DRIFT).toFloat()

    fun state(anchorAcc: Float?, acc: Float, env: Env): CalState = when {
        anchorAcc == null -> CalState.UNCALIBRATED
        acc <= threshold(env) -> CalState.CALIBRATED
        else -> CalState.DEGRADED
    }

    /** Phrase et geste, en clair. */
    fun describe(o: Observer): Pair<String, String> = when (o.state) {
        CalState.UNCALIBRATED -> "Position NON calibrée — les objets ne sont pas posés sur la carte" to
            (if (o.env == Env.OUTDOOR) "Attendre un GPS ≤ 10 m avec ≥ 6 satellites pendant 20 s." else "Appui long sur la carte → « Je suis ici » (ou une ancre), puis mesurer.")
        CalState.CALIBRATED -> "Position calibrée ±${o.acc.toInt()} m (${o.source}) — les mesures comptent" to ""
        CalState.DEGRADED -> "Précision perdue ±${o.acc.toInt()} m (seuil ${threshold(o.env).toInt()} m) — mesures en pause" to
            (if (o.env == Env.OUTDOOR) "Un fix GPS ≤ 10 m recalibre ; ciel dégagé." else "Passer sur une ancre (« Je suis à l'ancre… ») ou « Je suis ici ».")
    }
}
