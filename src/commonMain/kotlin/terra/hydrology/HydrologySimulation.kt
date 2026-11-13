package terra.hydrology

import terra.weather.WeatherModel
import kotlin.math.expm1
import kotlin.math.max
import kotlin.math.min

/** Routes weather fluxes and owns mutable channel and lake water state. */
class HydrologySimulation(private val topology: HydrologyTopology,
    /** Effective reach residence time; the game-scale tile geometry is not a hydraulic channel survey. */
    val channelTravelSeconds: Double = 6 * 3600.0
) {
    val width: Int = topology.width
    val height: Int = topology.height
    private val count = width * height
    private val candidates = topology.lakeCandidates
    /** One outlet/catchment identifier per land tile, derived once from D8 topology. */
    internal val catchmentId = IntArray(count) { -1 }
    val lakeCount = candidates.size
    val oceanTileCount = (0 until count).count { !topology.isLandTile(it) }
    /** One finite well-mixed reservoir; temperatures remain spatially varying in the atmosphere. */
    var oceanWaterMmTiles: Double = oceanTileCount * 10_000.0
        private set
    val meanOceanDepthMm: Double get() = if (oceanTileCount > 0) oceanWaterMmTiles / oceanTileCount else 0.0

    // Fine-cell evaporation repeatedly debits a much larger ocean store. Compensate
    // additions so centuries of small transfers do not accumulate roundoff drift.
    private var oceanRoundoff = 0.0
    private fun adjustOceanWater(amount: Double) {
        val corrected = amount - oceanRoundoff
        val updated = oceanWaterMmTiles + corrected
        oceanRoundoff = (updated - oceanWaterMmTiles) - corrected
        oceanWaterMmTiles = updated
    }

    fun receiveOceanWaterMmTiles(amount: Double) {
        require(amount.isFinite() && amount >= 0 && (amount == 0.0 || oceanTileCount > 0))
        adjustOceanWater(amount)
    }

    fun evaporateOceanWaterMmTiles(requested: Double): Double {
        require(requested.isFinite() && requested >= 0)
        val actual = min(oceanWaterMmTiles, requested)
        if (actual == oceanWaterMmTiles) {
            oceanWaterMmTiles = 0.0
            oceanRoundoff = 0.0
        } else adjustOceanWater(-actual)
        return actual
    }

    private val pendingRunoffMmTiles = DoubleArray(count)
    private val pendingLakeRainMmTiles = DoubleArray(count)
    private val pendingLakePotentialEvaporationMmTiles = DoubleArray(count)
    private var pendingElapsedSeconds = 0.0
    private var pendingExchange: HydrologyExchange? = null
    private val channelWaterMmTiles = DoubleArray(count)
    val channelStorageMmTiles: Double get() = channelWaterMmTiles.sum()
    fun channelStorageMmTilesAt(x: Int, y: Int): Double = channelWaterMmTiles[index(x, y)]
    private val routedMmTiles = DoubleArray(count)
    private val dischargeMmTilesPerDay = DoubleArray(count)

    private val lakeIdByTile = IntArray(count) { -1 }
    private val sortedLakeTiles = Array(lakeCount) { IntArray(0) }
    private val lakeCapacityMmTiles = DoubleArray(lakeCount)
    private val lakeStorageMmTiles = DoubleArray(lakeCount)
    private val lakeInflowMmTiles = DoubleArray(lakeCount)
    private val lakeDirectRainMmTiles = DoubleArray(lakeCount)
    private val lakePotentialEvaporationMmTiles = DoubleArray(lakeCount)
    private val lakeWaterSurfaceElevation = DoubleArray(lakeCount)
    private val lakeWetMask = BooleanArray(count)
    private val previousLakeWetMask = BooleanArray(count)
    internal var lakeMaskRevision = 0L
        private set

    /** River-to-ocean transfer from the most recently routed period, in mm·tiles. */
    var outletMmTilesLastPeriod: Double = 0.0
        private set

    /** Cumulative river-to-ocean transfer, diagnostic only, not another reservoir. */
    var outletMmTilesTotal: Double = 0.0
        private set

    /** Simulated days represented by the latest routed and discharge arrays. */
    var lastRoutedPeriodDays: Double = 0.0
        private set

    /** Increments when a routing period completes or immediate evaporation changes lake geometry. */
    var routedPeriodRevision: Long = 0L
        private set

    /** Water in ocean, lakes, channels, and the unprocessed routing interval. */
    val storedAndPendingWaterMmTiles: Double
        get() = oceanWaterMmTiles + lakeStorageMmTiles.sum() + channelStorageMmTiles + pendingRunoffMmTiles.sum() + pendingLakeRainMmTiles.sum()

    var completedRoutingPasses = 0L
        private set
    var aggregatedClimateInterval = false
        private set
    private var lakeEvaporationChanged = false
    private val presentationGeography by lazy {
        HydrologyGeography(width, height, BooleanArray(count) { topology.isLandTile(it) },
            topology.downstreamIndex.copyOf(), topology.upstreamLandTileCount.copyOf(),
            BooleanArray(count) { topology.isVirtualSpillLink(it) }, lakeIdByTile.copyOf())
    }

    fun snapshot(): HydrologySnapshot = HydrologySnapshot(presentationGeography, routedPeriodRevision,
        dischargeMmTilesPerDay.copyOf(), lakeWetMask.copyOf(), lakeStorageMmTiles.copyOf(), lakeWaterSurfaceElevation.copyOf(), routedMmTiles.copyOf(), lastRoutedPeriodDays, aggregatedClimateInterval)

    /** Immediate finite-store withdrawal for a coupled atmosphere; caller pays the latent heat. */
    fun evaporateLakeWaterMmTiles(x: Int, y: Int, requested: Double): Double {
        require(requested.isFinite() && requested >= 0.0)
        val tile = index(x, y)
        val lake = lakeIdByTile[tile]
        if (lake < 0 || !lakeWetMask[tile]) return 0.0
        val actual = min(requested, lakeStorageMmTiles[lake])
        lakeStorageMmTiles[lake] -= actual
        if (actual > 0.0) lakeEvaporationChanged = true
        return actual
    }

    /** Refresh geometry once after a batch, rather than once for every surface fragment. */
    fun finishSurfaceExchange(weather: HydrologyExchange) {
        if (!lakeEvaporationChanged) return
        for (lake in candidates.indices) lakeWaterSurfaceElevation[lake] = waterSurfaceElevation(lake)
        updateLakeWetMask(weather)
        routedPeriodRevision++
        lakeEvaporationChanged = false
    }

    init {
        require(channelTravelSeconds.isFinite() && channelTravelSeconds > 0.0)
        for (i in topology.processingOrder.indices.reversed()) {
            val tile = topology.processingOrder[i]
            val downstream = topology.downstreamIndex[tile]
            catchmentId[tile] = when {
                downstream < 0 -> tile
                topology.isLandTile(downstream) -> catchmentId[downstream]
                else -> downstream
            }
        }
        for (candidate in candidates) {
            val lakeId = candidate.id
            val sortedTiles = candidate.tileIndices.copyOf()
            sortTilesByElevation(sortedTiles)
            sortedLakeTiles[lakeId] = sortedTiles
            lakeWaterSurfaceElevation[lakeId] = topology.terrainElevation[sortedTiles[0]]
            var capacity = 0.0
            for (tile in candidate.tileIndices) {
                check(lakeIdByTile[tile] < 0) { "Potential lake basins must not overlap" }
                lakeIdByTile[tile] = lakeId
                capacity += max(0.0, candidate.spillElevation - topology.terrainElevation[tile]) *
                    VERTICAL_MILLIMETERS_PER_ELEVATION_UNIT
            }
            lakeCapacityMmTiles[lakeId] = capacity
        }
    }

    /** Add exactly one outer weather step's runoff and lake surface fluxes. */
    fun acceptWeatherStep(weather: WeatherModel, elapsedSimulatedSeconds: Double) {
        require(weather.width == width && weather.height == height) {
            "Weather and hydrology dimensions must match"
        }
        require(elapsedSimulatedSeconds >= 0.0 && elapsedSimulatedSeconds.isFinite()) {
            "Elapsed simulated time must be finite and non-negative"
        }
        if (elapsedSimulatedSeconds == 0.0) return
        val batch = weather.pendingWaterFluxBatch ?: WaterFluxBatch.fromWeather(weather, elapsedSimulatedSeconds)
        require(batch.durationSeconds == elapsedSimulatedSeconds) { "Handoff duration must match the completed weather update" }
        acceptFluxBatch(batch)
    }

    /** Detailed/prescribed compatibility path retains its existing daily cadence. */
    fun acceptFluxBatch(batch: WaterFluxBatch) = acceptBatch(batch, forceRoute = false)

    /**
     * Accept a representative climate phase. Routing occurs at a meaningful catchment
     * cadence rather than once per atmospheric phase; a caller can force the final route.
     * Returns the newly routed duration, or zero while water remains pending.
     */
    fun acceptClimateFluxBatch(batch: WaterFluxBatch, forceRoute: Boolean = true): Double {
        val representedSeconds = pendingElapsedSeconds + batch.durationSeconds
        pendingExchange = batch.exchange
        val routed = pendingElapsedSeconds + batch.durationSeconds >= CLIMATE_ROUTING_CADENCE_SECONDS
        acceptBatch(batch, forceRoute = forceRoute || routed, cadenceSeconds = CLIMATE_ROUTING_CADENCE_SECONDS)
        aggregatedClimateInterval = true
        return if (forceRoute) representedSeconds else batch.durationSeconds
    }

    /** Pending catchment water not yet routed; part of the authoritative inventory. */
    val pendingDurationSeconds: Double get() = pendingElapsedSeconds

    /** Only publishes diagnostic throughput: never credits ocean or routes physical water again. */
    internal fun publishClimateThroughput(seconds: Double, throughput: DoubleArray, lakeInflow: DoubleArray, outlet: Double) {
        require(seconds.isFinite() && seconds > 0 && throughput.size == count && lakeInflow.size == lakeCount)
        require(throughput.all { it.isFinite() && it >= 0 } && lakeInflow.all { it.isFinite() && it >= 0 } && outlet.isFinite() && outlet >= 0)
        throughput.copyInto(routedMmTiles)
        lakeInflow.copyInto(lakeInflowMmTiles)
        lastRoutedPeriodDays = seconds / SECONDS_PER_DAY
        for (i in 0 until count) dischargeMmTilesPerDay[i] = throughput[i] / lastRoutedPeriodDays
        outletMmTilesLastPeriod = outlet
        aggregatedClimateInterval = true
        routedPeriodRevision++
    }

    private fun acceptBatch(batch: WaterFluxBatch, forceRoute: Boolean,
        cadenceSeconds: Double = HYDROLOGY_CADENCE_SECONDS) {
        require(batch.width == width && batch.height == height) { "Flux and hydrology dimensions must match" }
        batch.claim()
        val exchange = batch.exchange
        finishSurfaceExchange(exchange)
        fun acceptTile(i: Int) {
            val runoff = batch.runoffMmAt(i)
            val rain = batch.directLakeRainMmAt(i)
            val evaporation = batch.potentialLakeEvaporationMmAt(i)
            check(runoff.isFinite() && runoff >= 0 && rain.isFinite() && rain >= 0 && evaporation.isFinite() && evaporation >= 0)
            check(runoff == 0.0 || topology.isLandTile(i)) { "Runoff must come from land" }
            check(rain == 0.0 || lakeIdByTile[i] >= 0) { "Direct lake rain must target a lake candidate" }
            check(evaporation == 0.0 || lakeIdByTile[i] >= 0) { "Lake evaporation must target a lake candidate" }
            pendingRunoffMmTiles[i] += runoff
            pendingLakeRainMmTiles[i] += rain
            pendingLakePotentialEvaporationMmTiles[i] += evaporation
        }
        val active = batch.activeTiles
        if (active != null) for (i in active) acceptTile(i) else for (i in 0 until count) acceptTile(i)
        pendingElapsedSeconds += batch.durationSeconds
        if (forceRoute || pendingElapsedSeconds >= cadenceSeconds) routePendingPeriod(exchange)
        exchange.hydrologyAccepted()
    }

    fun waterInventory() = HydrologyWaterInventory(oceanWaterMmTiles, lakeStorageMmTiles.sum(),
        pendingRunoffMmTiles.sum(), pendingLakeRainMmTiles.sum(), pendingLakePotentialEvaporationMmTiles.sum(),
        pendingElapsedSeconds, channelStorageMmTiles)

    /** Latest routed throughput for this tile, in mm·tiles over the latest processed period. */
    fun routedMmTiles(x: Int, y: Int): Double = routedMmTiles[index(x, y)]

    /** Relative flow proxy; this is not a physical volumetric discharge. */
    fun dischargeMmTilesPerDay(x: Int, y: Int): Double = dischargeMmTilesPerDay[index(x, y)]

    fun upstreamLandTileCount(x: Int, y: Int): Int =
        topology.upstreamLandTileCount[index(x, y)]

    /** Fixed game-scale filter for future river rendering; no water is created by this predicate. */
    fun isStreamTile(x: Int, y: Int): Boolean {
        val i = index(x, y)
        return topology.isLandTile(i) &&
            topology.upstreamLandTileCount[i] >= MIN_STREAM_CONTRIBUTING_AREA_TILES &&
            dischargeMmTilesPerDay[i] >= MIN_STREAM_DISCHARGE_MM_TILES_PER_DAY
    }

    internal fun catchmentIdAt(tile: Int): Int = catchmentId[tile]

    fun lakeIdAt(x: Int, y: Int): Int = lakeIdByTile[index(x, y)]

    fun lakeStorageMmTiles(lakeId: Int): Double = lakeStorageMmTiles[lakeIndex(lakeId)]

    fun lakeCapacityMmTiles(lakeId: Int): Double = lakeCapacityMmTiles[lakeIndex(lakeId)]

    fun lakeInflowMmTilesLastPeriod(lakeId: Int): Double = lakeInflowMmTiles[lakeIndex(lakeId)]

    fun lakeWaterSurfaceElevation(lakeId: Int): Double =
        lakeWaterSurfaceElevation[lakeIndex(lakeId)]

    fun isLakeTileWet(x: Int, y: Int): Boolean = lakeWetMask[index(x, y)]

    fun isLakeCandidateTile(x: Int, y: Int): Boolean = lakeIdByTile[index(x, y)] >= 0

    fun isLandTile(x: Int, y: Int): Boolean = topology.isLandTile(index(x, y))

    fun downstreamTileIndex(x: Int, y: Int): Int = topology.downstreamIndex[index(x, y)]

    /** Whether the internal drainage edge climbs terrain and should be omitted from the river art. */
    fun isVirtualSpillLink(x: Int, y: Int): Boolean =
        topology.isVirtualSpillLink(index(x, y))

    private fun routePendingPeriod(weather: HydrologyExchange) {
        val elapsedDays = pendingElapsedSeconds / SECONDS_PER_DAY
        routedMmTiles.fill(0.0)
        for (i in 0 until count) routedMmTiles[i] = pendingRunoffMmTiles[i]
        lakeInflowMmTiles.fill(0.0)
        lakeDirectRainMmTiles.fill(0.0)
        lakePotentialEvaporationMmTiles.fill(0.0)

        for (lakeId in candidates.indices) {
            for (tile in candidates[lakeId].tileIndices) {
                lakeDirectRainMmTiles[lakeId] += pendingLakeRainMmTiles[tile]
                lakePotentialEvaporationMmTiles[lakeId] +=
                    pendingLakePotentialEvaporationMmTiles[tile]
            }
        }

        // Exact linear reach under interval-mean inflow. Small detailed intervals retain
        // flood pulses; climate intervals use the same stores with an analytic aggregate step.
        val response = -expm1(-pendingElapsedSeconds / channelTravelSeconds)
        val inflowRetention = channelTravelSeconds / pendingElapsedSeconds * response
        var outletAmount = 0.0
        for (tile in topology.processingOrder) {
            val lakeId = lakeIdByTile[tile]
            if (lakeId >= 0) {
                lakeInflowMmTiles[lakeId] += routedMmTiles[tile]
                routedMmTiles[tile] = 0.0
                if (tile == candidates[lakeId].exitTileIndex) {
                    val spillMmTiles = updateLake(lakeId, weather)
                    routedMmTiles[tile] = spillMmTiles
                    val downstream = candidates[lakeId].downstreamTileIndex
                    if (downstream >= 0) {
                        routedMmTiles[downstream] += spillMmTiles
                    } else {
                        outletAmount += spillMmTiles
                    }
                }
            } else {
                if (topology.isLandTile(tile)) {
                    val start = channelWaterMmTiles[tile]
                    val incoming = routedMmTiles[tile]
                    val end = start * (1 - response) + incoming * inflowRetention
                    val released = max(0.0, start + incoming - end)
                    channelWaterMmTiles[tile] = end
                    routedMmTiles[tile] = released
                }
                val downstream = topology.downstreamIndex[tile]
                if (downstream >= 0) {
                    routedMmTiles[downstream] += routedMmTiles[tile]
                } else {
                    outletAmount += routedMmTiles[tile]
                }
            }
        }

        for (i in 0 until count) dischargeMmTilesPerDay[i] = routedMmTiles[i] / elapsedDays
        outletMmTilesLastPeriod = outletAmount
        outletMmTilesTotal += outletAmount
        receiveOceanWaterMmTiles(outletAmount)
        lastRoutedPeriodDays = elapsedDays
        aggregatedClimateInterval = false
        completedRoutingPasses++
        routedPeriodRevision++
        pendingRunoffMmTiles.fill(0.0)
        pendingLakeRainMmTiles.fill(0.0)
        pendingLakePotentialEvaporationMmTiles.fill(0.0)
        pendingElapsedSeconds = 0.0
        pendingExchange = null
        updateLakeWetMask(weather)
    }

    private fun updateLake(lakeId: Int, weather: HydrologyExchange): Double {
        val inflow = lakeInflowMmTiles[lakeId]
        val directRain = lakeDirectRainMmTiles[lakeId]
        val potentialEvaporation = lakePotentialEvaporationMmTiles[lakeId]
        val available = lakeStorageMmTiles[lakeId] + inflow + directRain
        val actualEvaporation = returnLakeEvaporation(
            weather,
            lakeId,
            min(available, potentialEvaporation),
            potentialEvaporation
        )

        val waterBeforeSpill = max(0.0, available - actualEvaporation)
        val spill = max(0.0, waterBeforeSpill - lakeCapacityMmTiles[lakeId])
        lakeStorageMmTiles[lakeId] = waterBeforeSpill - spill
        lakeWaterSurfaceElevation[lakeId] = waterSurfaceElevation(lakeId)
        return spill
    }

    private fun returnLakeEvaporation(
        weather: HydrologyExchange,
        lakeId: Int,
        actualEvaporation: Double,
        potentialEvaporation: Double
    ): Double {
        if (actualEvaporation <= 0.0 || potentialEvaporation <= 0.0) return 0.0
        val tiles = candidates[lakeId].tileIndices
        var lastWetPotentialTile = -1
        for (tile in tiles) if (pendingLakePotentialEvaporationMmTiles[tile] > 0.0) {
            lastWetPotentialTile = tile
        }
        var returned = 0.0
        var remainingPotential = potentialEvaporation
        var remainingEvaporation = actualEvaporation
        for (tile in tiles) {
            val potential = pendingLakePotentialEvaporationMmTiles[tile]
            if (potential <= 0.0) continue
            val actual = if (tile == lastWetPotentialTile) {
                remainingEvaporation
            } else {
                min(remainingEvaporation, remainingEvaporation * (potential / remainingPotential))
            }
            weather.returnLakeEvaporationMm(tile % width, tile / width, actual)
            returned += actual
            remainingEvaporation -= actual
            remainingPotential -= potential
        }
        return returned
    }

    private fun updateLakeWetMask(weather: HydrologyExchange) {
        lakeWetMask.copyInto(previousLakeWetMask)
        lakeWetMask.fill(false)
        for (lakeId in candidates.indices) {
            if (lakeStorageMmTiles[lakeId] <= 0.0) continue
            val surface = lakeWaterSurfaceElevation[lakeId]
            for (tile in candidates[lakeId].tileIndices) {
                if (topology.terrainElevation[tile] < surface) lakeWetMask[tile] = true
            }
        }
        if (!lakeWetMask.contentEquals(previousLakeWetMask)) lakeMaskRevision++
        weather.updateLakeWaterMask(lakeWetMask)
    }

    private fun waterSurfaceElevation(lakeId: Int): Double {
        val sortedTiles = sortedLakeTiles[lakeId]
        if (sortedTiles.isEmpty()) return candidates[lakeId].spillElevation
        var remainingElevationTileUnits =
            lakeStorageMmTiles[lakeId] / VERTICAL_MILLIMETERS_PER_ELEVATION_UNIT
        var activeTileCount = 0
        var surface = topology.terrainElevation[sortedTiles[0]]
        for (tile in sortedTiles) {
            val elevation = topology.terrainElevation[tile]
            if (activeTileCount == 0) {
                surface = elevation
                activeTileCount = 1
                continue
            }
            val neededToReachNext = max(0.0, elevation - surface) * activeTileCount
            if (remainingElevationTileUnits <= neededToReachNext) {
                return min(
                    candidates[lakeId].spillElevation,
                    surface + remainingElevationTileUnits / activeTileCount
                )
            }
            remainingElevationTileUnits -= neededToReachNext
            surface = elevation
            activeTileCount++
        }
        return min(
            candidates[lakeId].spillElevation,
            surface + remainingElevationTileUnits / activeTileCount
        )
    }

    private fun sortTilesByElevation(tiles: IntArray) {
        fun before(a: Int, b: Int): Boolean {
            val elevationOrder = topology.terrainElevation[a].compareTo(topology.terrainElevation[b])
            return elevationOrder < 0 || (elevationOrder == 0 && a < b)
        }
        fun siftDown(rootStart: Int, end: Int) {
            var root = rootStart
            while (root * 2 + 1 <= end) {
                val left = root * 2 + 1
                val right = left + 1
                var child = left
                if (right <= end && before(tiles[left], tiles[right])) child = right
                if (!before(tiles[root], tiles[child])) return
                val swap = tiles[root]
                tiles[root] = tiles[child]
                tiles[child] = swap
                root = child
            }
        }

        for (start in tiles.size / 2 - 1 downTo 0) siftDown(start, tiles.lastIndex)
        for (end in tiles.lastIndex downTo 1) {
            val swap = tiles[0]
            tiles[0] = tiles[end]
            tiles[end] = swap
            siftDown(0, end - 1)
        }
    }

    private fun lakeIndex(lakeId: Int): Int {
        require(lakeId in 0 until lakeCount) { "Lake ID is outside hydrology" }
        return lakeId
    }

    private fun index(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "Tile coordinates are outside hydrology" }
        return y * width + x
    }

    companion object {
        /** Game-tuning interval; overshooting batches are routed once over their actual duration. */
        const val HYDROLOGY_CADENCE_SECONDS = 24.0 * 60.0 * 60.0
        /** Climate routing may accumulate several ten-day phases without erasing basin memory. */
        const val CLIMATE_ROUTING_CADENCE_SECONDS = 30.0 * 24.0 * 60.0 * 60.0
        const val MIN_STREAM_CONTRIBUTING_AREA_TILES = 64
        /** A relative mm·tiles/day display cutoff, not an Earth-calibrated flow. */
        const val MIN_STREAM_DISCHARGE_MM_TILES_PER_DAY = 1.0
        /** Shared game-scale depth conversion for basin capacity and storage-derived water level. */
        const val VERTICAL_MILLIMETERS_PER_ELEVATION_UNIT = 1000.0
        private const val SECONDS_PER_DAY = 24.0 * 60.0 * 60.0
    }
}
