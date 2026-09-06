package ch.lab77.radar.scan

/**
 * Un capteur alimente `ScanRepository.observe()` — Wi-Fi interne, BLE interne, et en v0.3 la tête de
 * sonde LoRa externe (cahier §9). Même contrat pour tous : démarrer, arrêter, dire si ça tourne.
 */
interface Sensor {
    val label: String
    val isRunning: Boolean
    fun start(): Boolean
    fun stop()
}
