package terra.weather

import kotlin.math.floor

/** Advances climate-initialization weather in bounded batches and reports each emitted step. */
class ClimateBootstrap(
    private val weather: WeatherSimulation,
    private val afterWeatherStep: (Double) -> Unit = {}
) {
    private var phase = Phase.EQUILIBRATION
    private var phaseElapsedSeconds = 0.0
    var snapshot: ClimateSnapshot? = null
        private set

    val isComplete: Boolean
        get() = phase == Phase.COMPLETE

    val status: String
        get() = when (phase) {
            Phase.EQUILIBRATION -> "Climate equilibration: ${elapsedDays()}/${EQUILIBRATION_DAYS.toInt()} days"
            Phase.OBSERVATION -> "Climate sampling: ${elapsedDays()}/${CLIMATE_SAMPLE_DAYS.toInt()} days"
            Phase.COMPLETE -> "Climate initialized"
        }

    /** Advances no more than the named number of full weather substeps. */
    fun advanceBatch() {
        if (isComplete) return
        repeat(MAX_SUBSTEPS_PER_BATCH) {
            val elapsed = weather.advanceOneInternalSubstep()
            afterWeatherStep(elapsed)
            phaseElapsedSeconds += elapsed
            when (phase) {
                Phase.EQUILIBRATION -> if (phaseElapsedSeconds >= EQUILIBRATION_SECONDS) {
                    phase = Phase.OBSERVATION
                    phaseElapsedSeconds = 0.0
                    weather.beginClimateSampling()
                }
                Phase.OBSERVATION -> if (phaseElapsedSeconds >= CLIMATE_SAMPLE_SECONDS) {
                    snapshot = weather.endClimateSampling()
                    phase = Phase.COMPLETE
                }
                Phase.COMPLETE -> Unit
            }
        }
    }

    private fun elapsedDays() = floor(phaseElapsedSeconds / SECONDS_PER_DAY).toInt()

    private enum class Phase { EQUILIBRATION, OBSERVATION, COMPLETE }

    private companion object {
        // Game-tuning defaults; these are bounded initialization windows, not physical constants.
        const val EQUILIBRATION_DAYS = 30.0
        const val CLIMATE_SAMPLE_DAYS = 180.0
        const val MAX_SUBSTEPS_PER_BATCH = 1

        const val SECONDS_PER_DAY = 24.0 * 60.0 * 60.0
        const val EQUILIBRATION_SECONDS = EQUILIBRATION_DAYS * SECONDS_PER_DAY
        const val CLIMATE_SAMPLE_SECONDS = CLIMATE_SAMPLE_DAYS * SECONDS_PER_DAY
    }
}
