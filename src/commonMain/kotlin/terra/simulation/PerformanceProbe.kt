package terra.simulation

/** Optional, single-threaded timing probe. Ordinary gameplay does not create one. */
enum class SimulationWork {
    INTEGRATION, SURFACE_BUCKET, CLIMATE_OBSERVATION, SNAPSHOT, CLOUD_BITMAP, OTHER_OVERLAYS, WATER_RASTER, TERRAIN_TILES, FRAME_UPDATE
}

class SimulationTimings(val nanoTime: () -> Long) {
    private val starts = LongArray(SimulationWork.entries.size)
    val totals = LongArray(SimulationWork.entries.size)
    val counts = LongArray(SimulationWork.entries.size)
    val maxima = LongArray(SimulationWork.entries.size)
    fun begin(work: SimulationWork) { starts[work.ordinal] = nanoTime() }
    fun end(work: SimulationWork) {
        val i = work.ordinal
        val elapsed = nanoTime() - starts[i]
        totals[i] += elapsed
        counts[i]++
        maxima[i] = maxOf(maxima[i], elapsed)
    }
    fun clear() { totals.fill(0); counts.fill(0); maxima.fill(0) }
}

data class ProfileSettings(val speed: SimulationSpeed, val clouds: Boolean, val dayNight: Boolean = false)

interface PerformanceProbe {
    val timings: SimulationTimings
    fun beforeFrame(completedSeconds: Double, pendingSeconds: Double): ProfileSettings?
    fun afterFrame(offeredSeconds: Double)
    fun workerProgress(progress: SimulationProgress) {}
}
