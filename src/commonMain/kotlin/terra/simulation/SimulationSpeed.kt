package terra.simulation

enum class SimulationSpeed(val label: String, val simulatedSecondsPerRealSecond: Double) {
    REALTIME("Realtime", 1.0),
    ONE_HOUR("1h/sec", 60.0 * 60.0),
    TWELVE_HOURS("12h/sec", 12.0 * 60.0 * 60.0),
    ONE_DAY("1 day/sec", 24.0 * 60.0 * 60.0),
    ONE_WEEK("1 week/s", 7.0 * 24.0 * 60.0 * 60.0),
    ONE_MONTH("1 month/s", 30.0 * 24.0 * 60.0 * 60.0)
}
