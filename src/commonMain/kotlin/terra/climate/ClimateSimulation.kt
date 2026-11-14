package terra.climate

import terra.world.World

/** Single climate authority and persistent landscape; called only by the simulation owner. */
class ClimateSimulation(private val world: World, val config: ClimateConfig = ClimateConfig(),
    val solar: SolarConfig = SolarConfig()) {
    var atlas = ClimateAtlasBuilder(solar).build(ClimateGeography(world, config))
        private set
    internal val surfaceState = LandscapeWater(world,
        config.domainWidthMeters * config.domainHeightMeters / (world.width.toDouble() * world.height)).also {
        it.initializeClimate(atlas)
        world.evolveAtlasCover(atlas, 0.0, initialize = true, soilAvailability = it.growingWaterAvailability)
    }
    private val evolution = ClimateEvolution(world, surfaceState, 0.0)
    private var updateSeconds = 0.0
    val elapsedSeconds get() = evolution.completedSeconds
    internal fun ensureAtlas() {
        if (atlas.geometryRevision == world.geometryRevision) return
        atlas = ClimateAtlasBuilder(solar).build(ClimateGeography(world, config))
        surfaceState.refreshGeometry()
    }
    fun advance(seconds: Double): ClimateIntervalDiagnostics {
        require(seconds.isFinite() && seconds > 0)
        ensureAtlas()
        val result = evolution.advance(atlas, seconds)
        updateSeconds = seconds
        return result
    }
    fun snapshot(): EnvironmentSnapshot = EnvironmentSnapshot(atlas, elapsedSeconds, updateSeconds,
        surfaceState.soil.copyOf(), surfaceState.soilCapacityMm.copyOf(),
        DoubleArray(world.width * world.height) { surfaceState.snowWaterMm(it) },
        surfaceState.rain.copyOf(), surfaceState.drainage.copyOf(), surfaceState.overflow.copyOf())
}
