package terra.climate

/** SI units; atmosphere and terrain always share the same one-kilometre grid. */
internal const val TILE_METERS = 1000.0
internal const val GRAVITY = 9.80665
internal const val AIR_DENSITY = 1.225
internal const val SEA_LEVEL_PRESSURE = 101325.0
internal const val ROTATION_RATE = 40 * 7.2921159e-5
internal const val COLUMN_DEPTH = SEA_LEVEL_PRESSURE / (AIR_DENSITY * GRAVITY)
internal const val DAY_SECONDS = 86400.0
internal const val YEAR_SECONDS = 365 * DAY_SECONDS
// Hydrostatic thermal shallow-water forcing and column heat capacity.
internal const val THERMAL_THICKNESS_PER_KELVIN = COLUMN_DEPTH / (2 * 288.15)
internal const val CONDENSATION_KELVIN_PER_MM = 2_500_000.0 / (AIR_DENSITY * COLUMN_DEPTH * 1004.0)
