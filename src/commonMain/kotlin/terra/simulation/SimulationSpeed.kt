package terra.simulation

enum class SimulationSpeed(val label: String, val simulatedSecondsPerRealSecond: Double) {
    REALTIME("Realtime", 1.0),
    ONE_HOUR("1h/sec", 60.0 * 60.0),
    TWELVE_HOURS("12h/sec", 12.0 * 60.0 * 60.0),
    ONE_DAY("1 day/sec", 24.0 * 60.0 * 60.0),
}
