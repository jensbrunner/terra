package terra.weather

/** Interval diagnostics only. These arrays never own, withdraw, or route water. */
internal class ClimateFluxTotals(fields: Array<DoubleArray>) {
    val peakRain = fields[0]
    val rain = fields[1]
    val drainage = fields[2]
    val overflow = fields[3]
    val wetSeconds = fields[4]
    val intenseRain = fields[5]
}
