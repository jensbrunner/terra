package terra.climate

import terra.world.World
import kotlin.math.*

/** Surface-only flux accounting; atlas humidity is diagnostic and excluded. */
data class ClimateIntervalDiagnostics(
    val subperiods: Int, val relativeWaterResidual: Double
)

internal class ClimateEvolution(private val world: World, private val surface: LandscapeWater,
    startSeconds: Double) {
    var completedSeconds = startSeconds
        private set
    private val drought = surface.climateDrought
    // Re-evaluate a partial month's prefix from one anchor. Routing and root uptake
    // are nonlinear too, so merely moving the rain pulse does not ensure invariance.
    private var anchor: LandscapeWater.ClimateState? = null
    private var anchorAtlas: SeasonalClimateAtlas? = null
    private var anchorMonth = Long.MIN_VALUE
    private var anchorFraction = 0.0
    private var prefixEvaporation = 0.0
    private val prefixFlux = Array(3) { DoubleArray(drought.size) }
    private val updateFlux = Array(3) { DoubleArray(drought.size) }
    fun advance(atlas: SeasonalClimateAtlas, seconds: Double): ClimateIntervalDiagnostics {
        val before = surface.totalWaterKg
        val boundaryBefore = surface.exportedWaterKg
        surface.beginUpdate()
        var remaining = seconds; var months = 0; var evaporation = 0.0; var rain = 0.0
        while (remaining > 1e-7) {
            val calendar = completedSeconds / atlas.solar.yearSeconds + atlas.solar.initialYearFraction
            val monthIndex = floor(calendar * 12 + 1e-10).toLong()
            val month = ((monthIndex % 12 + 12) % 12).toInt()
            val untilBoundary = ((monthIndex + 1) / 12.0 - atlas.solar.initialYearFraction) * atlas.solar.yearSeconds - completedSeconds
            val span = min(remaining, max(1e-7, untilBoundary))
            val startFraction = (1 - untilBoundary * 12 / atlas.solar.yearSeconds).coerceIn(0.0, 1.0)
            val endFraction = (startFraction + span * 12 / atlas.solar.yearSeconds).coerceAtMost(1.0)
            if (anchorMonth != monthIndex || anchorAtlas !== atlas) {
                anchor = surface.captureClimateState()
                anchorAtlas = atlas; anchorMonth = monthIndex; anchorFraction = startFraction
                prefixEvaporation = 0.0
                for (flux in prefixFlux) flux.fill(0.0)
            }
            val fluxes = arrayOf(surface.rain, surface.drainage, surface.overflow)
            for (f in fluxes.indices) fluxes[f].copyInto(updateFlux[f])
            surface.restoreClimateState(anchor!!)
            surface.beginUpdate()
            val nextEvaporation = surface.evolveMonth(atlas, month, endFraction - anchorFraction, anchorFraction, monthIndex)
            evaporation += nextEvaporation - prefixEvaporation
            prefixEvaporation = nextEvaporation
            for (f in fluxes.indices) for (i in drought.indices) {
                val next = fluxes[f][i]
                val change = next - prefixFlux[f][i]
                if (f == 0 && atlas.geography.tileLand[i]) rain += change * surface.tileAreaM2
                fluxes[f][i] = updateFlux[f][i] + change
                prefixFlux[f][i] = next
            }
            completedSeconds += span; remaining -= span; months++
            if (span >= untilBoundary - 1e-6 && month == 11) world.evolveAtlasCover(atlas, 1.0, drought = drought, soilAvailability = surface.growingWaterAvailability)
        }
        val after = surface.totalWaterKg
        val export = surface.exportedWaterKg - boundaryBefore
        // Rain on the ocean was never inserted in the terrestrial stores.
        val residual = (after - before - rain + evaporation + export) / max(1.0, before + rain)
        return ClimateIntervalDiagnostics(months, residual)
    }
}
