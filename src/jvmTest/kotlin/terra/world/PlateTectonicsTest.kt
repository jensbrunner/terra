package terra.world

import kotlin.math.*
import kotlin.test.*
import terra.hydrology.HydrologyTopology

class PlateTectonicsTest {
    private fun field(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun plates(model: PlateTectonics) = field(model, "plates").get(model) as List<MaterialPlate>
    private fun invoke(model: PlateTectonics, name: String, vararg args: Any): Any? =
        model.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(model, *args)
    private fun budget(model: PlateTectonics, initial: Double) =
        assertEquals(initial + model.mantleAddedMass - model.mantleRecycledMass, model.rockMass, initial * 1e-8)

    @Test fun eightInitialPlateSitesAreIndependentOfContinentScale() {
        for (seed in listOf(31742, 42, 123)) {
            var sites: List<Pair<Double, Double>>? = null
            var elevations: List<Double>? = null
            for (scale in listOf(10.0, 85.0, 200.0, 300.0)) {
                val world = World(seed, 36, 26, TerrainGenerationSettings(continentScale = scale))
                val model = PlateTectonics(world)
                assertEquals(8, model.activePlateCount)
                assertEquals(8, world.snapshot(0).plateCount)
                val poses = plates(model).map { it.x to it.y }
                if (sites != null) assertEquals(sites, poses)
                sites = poses
                val terrain = (0 until 36 * 26).map { world[it % 36, it / 36].elevation }
                if (elevations != null) assertNotEquals(elevations, terrain)
                elevations = terrain
            }
        }
    }

    @Test fun bothSeamsAndCornersWrapForTerrainVegetationNoiseAndDrainage() {
        val world = World(42, 24, 18)
        assertEquals(world[23, 17], world[-1, -1])
        assertEquals(world[0, 0], world.snapshot(0)[24, 18])
        val values = DoubleArray(24 * 18) { it.toDouble() }
        assertEquals(sampleField(values, 23.7, 17.8, 24, 18), sampleField(values, -0.3, -0.2, 24, 18), 1e-10)
        val noise = TerrainNoise(TerrainGenerationSettings())
        assertEquals(noise.periodic(3.0, 7.0, 42, 3, 20.0), noise.periodic(286.0, -205.0, 42, 3, 20.0), 1e-12)
        val heights = DoubleArray(25) { 0.8 }.apply { this[24] = 0.1; this[0] = 0.2 }
        val topology = HydrologyTopology(5, 5, heights, BooleanArray(25) { it == 24 })
        assertEquals(24, topology.downstreamIndex[0])
    }

    @Test fun rotatedMaterialProjectionIsPeriodicAndNorthboundMotionCrossesTheEdge() {
        val model = PlateTectonics(World(42, 36, 26))
        for (p in plates(model)) p.angle = 0.15
        invoke(model, "rasterize")
        val before = (field(model, "totalArea").get(model) as DoubleArray).copyOf()
        for (p in plates(model)) { p.x += PLANET_WIDTH_KM; p.y += PLANET_HEIGHT_KM }
        invoke(model, "rasterize")
        val after = field(model, "totalArea").get(model) as DoubleArray
        for (i in before.indices) assertEquals(before[i], after[i], 1e-11)
        val north = plates(model).first()
        for (p in plates(model)) {
            p.desiredX = 0.0; p.vx = 0.0
            p.desiredY = if (p === north) 0.00009 else -0.00001; p.vy = p.desiredY
            p.desiredSpin = 0.0; p.spin = 0.0
        }
        north.y = PLANET_HEIGHT_KM - 1
        val mass = model.rockMass
        invoke(model, "movePlates", 100_000.0)
        assertTrue(north.y in 0.0..20.0)
        assertTrue(north.vy > 0)
        budget(model, mass)
    }

    @Test fun rotatedProjectionPreservesTheStoredInventories() {
        val model = PlateTectonics(World(31742, 36, 26))
        val ps = plates(model)
        for (p in ps) for (cell in p.cells.values) { cell.sediment = 0.3; cell.sedimentContinent = 0.1 }
        @Suppress("UNCHECKED_CAST")
        val rasters = field(model, "rasters").get(model) as List<PlateRaster>
        for (angle in listOf(0.0, 0.17, 0.6)) {
            for (p in ps) { p.angle = angle; p.x += 77.3; p.y += 31.2 }
            invoke(model, "rasterize")
            val stored = listOf(ps.sumOf { p -> p.cells.values.sumOf { it.area } }, model.continentalVolume,
                ps.sumOf { p -> p.cells.values.sumOf { it.basalt } }, ps.sumOf { p -> p.cells.values.sumOf { it.sediment } })
            val projected = listOf<(PlateRaster) -> RasterField>({ it.area }, { it.continent }, { it.basalt }, { it.sediment })
                .map { select -> rasters.sumOf { r -> (0 until 36 * 26).sumOf { select(r)[it] } } }
            for (i in stored.indices) assertEquals(stored[i], projected[i], max(1.0, stored[i]) * 1e-11)
        }
    }

    @Test fun largeGeologicalStepsAndSedimentDepletionKeepTheMassLedgerClosed() {
        val model = collisionFixture(0.45)
        for (p in plates(model)) for (cell in p.cells.values) {
            cell.sediment = 0.2; cell.sedimentContinent = 0.1
        }
        val mass = model.rockMass
        repeat(300) { model.advance(100.0, DoubleArray(36 * 24) { 2000.0 }, speedMultiplier = 10_000.0); budget(model, mass) }
    }

    @Test fun detachedHistoricalContactsCannotWeld() {
        val model = PlateTectonics(World(31742, 36, 26))
        invoke(model, "observeContacts", 100_000.0)
        @Suppress("UNCHECKED_CAST")
        val contacts = field(model, "contacts").get(model) as MutableMap<Long, Any>
        val missing = (0 until plates(model).size).flatMap { a -> (a + 1 until plates(model).size).map { b -> (a.toLong() shl 32) or b.toLong() } }
            .first { it !in contacts }
        val constructor = model.javaClass.declaredClasses.single { it.simpleName == "Contact" }.declaredConstructors.first { it.parameterCount == 3 }.apply { isAccessible = true }
        contacts[missing] = constructor.newInstance(20_000_000.0, 1e9, false)
        invoke(model, "evolvePlates", 100_000.0)
        assertFalse(missing in contacts)
        assertEquals(0, model.mergers)
    }

    @Test fun fracturePreservesPositionsAndRockThenDaughtersSeparate() {
        val model = PlateTectonics(World(42, 48, 36))
        val p = plates(model).maxBy { it.area }
        val mass = model.rockMass
        val positions = p.cells.values.toSet()
        assertEquals(true, invoke(model, "split", p))
        val child = plates(model).last()
        assertEquals(positions, (p.cells.values + child.cells.values).toSet())
        assertEquals(p.x, child.x); assertEquals(p.y, child.y); assertEquals(p.angle, child.angle)
        assertTrue(min(p.area, child.area) >= 48 * 36 * 0.01)
        assertTrue(hypot(p.separationX - child.separationX, p.separationY - child.separationY) > 0.000059)
        budget(model, mass)
        invoke(model, "movePlates", 1_000_000.0)
        assertTrue(hypot(longitudeKm(p.x - child.x), latitudeKm(p.y - child.y)) > 30)
        budget(model, mass)
    }

    @Test fun weldingRequiresEightMillionYearsOfUnbrokenCurrentContact() {
        val model = PlateTectonics(World(31742, 36, 26))
        invoke(model, "observeContacts", 100_000.0)
        @Suppress("UNCHECKED_CAST")
        val contacts = field(model, "contacts").get(model) as MutableMap<Long, Any>
        val pair = contacts.keys.first { key ->
            invoke(model, "mergeFits", plates(model)[(key ushr 32).toInt()], plates(model)[key.toInt()]) == true
        }
        val contact = contacts.getValue(pair)
        field(contact, "duration").setDouble(contact, 7_800_000.0)
        field(contact, "shortening").setDouble(contact, 1e9)
        val mass = model.rockMass
        invoke(model, "evolvePlates", 100_000.0)
        assertEquals(0, model.mergers)
        invoke(model, "evolvePlates", 100_000.0)
        assertEquals(1, model.mergers)
        budget(model, mass)
    }

    @Test fun watchdogFracturesAnInactiveYoungConfigurationWithoutMovingItsMaterial() {
        val model = PlateTectonics(World(42, 48, 36))
        field(model, "geologicalTime").setDouble(model, 12_000_000.0)
        field(model, "lastBreakup").setDouble(model, 0.0)
        for (p in plates(model)) p.bornAt = 12_000_000.0
        val mass = model.rockMass
        invoke(model, "evolvePlates", 0.0)
        assertEquals(1, model.watchdogBreakups)
        assertEquals(1, model.splits)
        budget(model, mass)
    }

    @Test fun sedimentConsolidatesIntoItsOriginalRockWithDensityConversion() {
        val model = PlateTectonics(World(42, 24, 18))
        val cell = plates(model).first().cells.values.first()
        cell.sediment = 10.0; cell.sedimentContinent = 4.0
        val c = cell.continent; val b = cell.basalt; val mass = model.rockMass
        invoke(model, "movePlates", 20_000_000.0)
        assertEquals(10 * exp(-1.0), cell.sediment, 1e-12)
        assertEquals(c + 4 * (1 - exp(-1.0)) * SEDIMENT_DENSITY / CONTINENT_DENSITY, cell.continent, 1e-12)
        assertEquals(b + 6 * (1 - exp(-1.0)) * SEDIMENT_DENSITY / BASALT_DENSITY, cell.basalt, 1e-12)
        budget(model, mass)
    }

    private fun collisionFixture(erosion: Double = 0.0): PlateTectonics {
        val model = PlateTectonics(World(42, 36, 24, TerrainGenerationSettings(erosionStrength = erosion)))
        val ps = plates(model)
        for (p in ps) {
            p.cells.clear(); p.active = p.id < 6
            p.vx = if (p.id % 3 == 1) -0.00006 else 0.00006
            p.vy = 0.0; p.spin = 0.0; p.desiredSpin = 0.0
            p.desiredX = p.vx; p.desiredY = 0.0
        }
        val dx = PLANET_WIDTH_KM / 36; val dy = PLANET_HEIGHT_KM / 24
        for (y in 0 until 24) for (x in 0 until 36) {
            val p = ps[y / 12 * 3 + x / 12]
            val ix = floor((p.referenceX + longitudeKm((x + 0.5) * dx - p.x)) / dx).toInt()
            val iy = floor((p.referenceY + latitudeKm((y + 0.5) * dy - p.y)) / dy).toInt()
            p.cells[materialKey(ix, iy)] = CrustCell(ix, iy, 1.0, 30.0, 0.0, 0.0, 0.0)
        }
        invoke(model, "rasterize")
        return model
    }

    @Test fun collisionBuildsRootsAndRiftingConservesContinentalRock() {
        val model = collisionFixture()
        val mass = model.rockMass; val continent = model.continentalVolume
        repeat(10) { model.advance(100.0, DoubleArray(36 * 24) { 1000.0 }); budget(model, mass) }
        assertTrue(model.shortenedArea > 0)
        assertTrue(model.riftArea > 0)
        assertTrue(plates(model).any { p -> p.cells.values.any { it.continent / it.area > 30.001 } })
        assertTrue(model.continentalVolume >= continent - continent * 1e-10)
        // Newly spread basalt can already subduct and supply arcs during this coupled fixture.
        assertTrue(model.continentalVolume - continent <= model.mantleAddedMass / CONTINENT_DENSITY + continent * 1e-10)
    }

    @Test fun isolatedRidgeErodesItsRockAndSubductionAccountsForRecycledFragments() {
        val erosion = collisionFixture(0.45)
        for (p in plates(erosion)) {
            p.vx = 0.0; p.vy = 0.0; p.desiredX = 0.0
            for (cell in p.cells.values) if (cell.x % 5 == 0) cell.continent = 40.0
        }
        invoke(erosion, "rasterize")
        val c = erosion.continentalVolume; val mass = erosion.rockMass
        erosion.advance(100.0, DoubleArray(36 * 24) { 1500.0 })
        assertTrue(erosion.continentalVolume < c)
        assertTrue(plates(erosion).any { p -> p.cells.values.any { it.sedimentContinent > 0 } })
        budget(erosion, mass)
        val subduction = collisionFixture()
        for (p in plates(subduction).filter { it.id % 3 == 0 }) for (cell in p.cells.values) {
            cell.continent = 0.1; cell.basalt = 7.0; cell.sediment = 0.2; cell.sedimentContinent = 0.1
        }
        val initial = subduction.rockMass
        repeat(10) { subduction.advance(100.0, DoubleArray(36 * 24) { 1000.0 }); budget(subduction, initial) }
        assertTrue(subduction.subductedArea > 0)
        assertTrue(subduction.mantleRecycledMass > 0)
        assertTrue(subduction.mantleAddedMass > 0)
    }

    @Test fun jaggedMaterialFracturesAreDeterministicAndRespondToInheritedWeakness() {
        fun cut(offset: Int): Set<Long> {
            val model = PlateTectonics(World(42, 80, 60))
            val p = plates(model).maxBy { it.area }
            p.stressXX = 1.0
            val middle = (p.minX + p.maxX) / 2
            for (cell in p.cells.values) {
                cell.continent = cell.area * 30
                cell.basalt = 0.0
                val fault = middle + ((cell.y + offset) / 3 % 5) - 2
                cell.weakness = if (abs(cell.x - fault) <= 1) 1.0 else 0.0
            }
            invoke(model, "rasterize")
            val mass = model.rockMass
            assertEquals(true, invoke(model, "split", p))
            budget(model, mass)
            return plates(model).last().cells.keys.toSet()
        }
        assertEquals(cut(0), cut(0))
        assertNotEquals(cut(0), cut(7))
    }

    @Test fun detachedCapturePreservesEveryRockAndMaterialMomentWithoutMantleExchange() {
        val model = PlateTectonics(World(31742, 48, 36))
        val ps = plates(model)
        val owner = field(model, "owner").get(model) as IntArray
        val source = ps[0]
        val tile = owner.indices.first { i ->
            owner[i] != source.id && (-2..2).all { y -> (-2..2).all { x ->
                owner[worldIndex(i % 48 + x, i / 48 + y, 48, 36)] == owner[i]
            } }
        }
        val target = owner[tile]
        invoke(model, "addWorld", source, (tile % 48 + 0.5) * PLANET_WIDTH_KM / 48,
            (tile / 48 + 0.5) * PLANET_HEIGHT_KM / 36, 8.0, 160.0, 7.0, 0.3, 23_000_000.0, 0.2, 0.1, 0.23)
        fun moment(value: (CrustCell) -> Double) = ps.sumOf { p -> p.cells.values.sumOf(value) }
        val before = listOf(moment { it.area }, moment { it.continent }, moment { it.basalt },
            moment { it.sediment }, moment { it.sedimentContinent }, moment { it.age * it.area },
            moment { it.relief * it.area }, moment { it.weakness * it.area })
        val mass = model.rockMass; val added = model.mantleAddedMass; val recycled = model.mantleRecycledMass
        invoke(model, "rasterize")
        assertEquals(target, owner[tile])
        invoke(model, "captureFragments")
        val after = listOf(moment { it.area }, moment { it.continent }, moment { it.basalt },
            moment { it.sediment }, moment { it.sedimentContinent }, moment { it.age * it.area },
            moment { it.relief * it.area }, moment { it.weakness * it.area })
        for (i in before.indices) assertEquals(before[i], after[i], max(1.0, abs(before[i])) * 1e-11)
        assertTrue(model.capturedMass > 0)
        assertEquals(added, model.mantleAddedMass); assertEquals(recycled, model.mantleRecycledMass)
        budget(model, mass)
    }

    @Test fun freshCrustBelongsToTheOpeningTerritoryRatherThanAlternatingTiles() {
        val model = collisionFixture()
        val ps = plates(model)
        for (p in ps) if (p.active) for (cell in p.cells.values) cell.area *= 0.9
        invoke(model, "rasterize")
        val territory = (field(model, "owner").get(model) as IntArray).copyOf()
        val before = ps.map { p -> p.cells.mapValues { it.value.area } }
        invoke(model, "fillGaps")
        var births = 0
        for (p in ps) if (p.active) for ((key, cell) in p.cells) {
            if (cell.area - (before[p.id][key] ?: 0.0) > 1e-8) {
                assertEquals(p.id, territory[worldIndex(cell.x, cell.y, 36, 24)])
                births++
            }
        }
        assertTrue(births > 0)
    }

    @Test fun unusedAreaBudgetDoesNotCreateCrustWithoutAnOpeningInterface() {
        val model = collisionFixture()
        for (p in plates(model)) if (p.active) {
            p.vx = 0.0; p.vy = 0.0; p.spin = 0.0
            for (cell in p.cells.values) cell.area *= 0.9
        }
        invoke(model, "rasterize")
        val mass = model.rockMass
        invoke(model, "fillGaps")
        assertEquals(0.0, model.oceanCreatedArea)
        assertEquals(0.0, model.mantleAddedMass)
        budget(model, mass)
    }

    @Test fun unsupportedRepairedTerritoryDoesNotSubductZeroArea() {
        val model = collisionFixture()
        @Suppress("UNCHECKED_CAST")
        val rasters = field(model, "rasters").get(model) as List<PlateRaster>
        val owner = field(model, "owner").get(model) as IntArray
        val area = field(model, "totalArea").get(model) as DoubleArray
        val i = 5 * 36 + 13
        owner[i] = 0 // The coherent territory front reaches a tile with only foreign coverage.
        rasters[1].area[i] = 2.0; area[i] = 2.0
        val mass = model.rockMass
        invoke(model, "collide", 1_000_000.0)
        invoke(model, "applyEvents")
        budget(model, mass)
        assertTrue(model.rockMass.isFinite())
    }

    @Test fun colderOceanicSlabsStrengthenConvergenceWithoutChangingBasalDrive() {
        fun approach(age: Double): Double {
            val model = collisionFixture()
            val ps = plates(model)
            for (p in ps.filter { it.active && it.id % 3 == 0 }) for (cell in p.cells.values) {
                cell.continent = 0.0; cell.basalt = cell.area * 7; cell.age = age
            }
            invoke(model, "rasterize")
            invoke(model, "movePlates", 1_000_000.0)
            return ps[0].vx - ps[1].vx
        }
        assertTrue(approach(80_000_000.0) > approach(1_000_000.0))
    }

    @Test fun motionHasNoEightMillionYearRandomHeadingChangesOrAgeOnlyFractures() {
        val a = PlateTectonics(World(42, 36, 26))
        val b = PlateTectonics(World(42, 36, 26))
        field(a, "geologicalTime").setDouble(a, 7_900_000.0)
        field(b, "geologicalTime").setDouble(b, 80_100_000.0)
        invoke(a, "movePlates", 100_000.0)
        invoke(b, "movePlates", 100_000.0)
        for ((p, q) in plates(a).zip(plates(b))) {
            assertEquals(p.vx, q.vx, 1e-14)
            assertEquals(p.vy, q.vy, 1e-14)
            assertEquals(p.spin, q.spin, 1e-14)
        }
        field(b, "lastDeformation").setDouble(b, b.effectiveYears)
        invoke(b, "observeContacts", 0.0)
        invoke(b, "evolvePlates", 0.0)
        assertEquals(0, b.splits)
    }

    @Test fun oceanClosureCarriesContinentsIntoLastingCollisionAndBuildsRoots() {
        val width = 60; val height = 36
        val world = World(42, width, height, TerrainGenerationSettings(erosionStrength = 0.0))
        val model = PlateTectonics(world)
        val ps = plates(model)
        for (p in ps) {
            p.cells.clear(); p.active = p.id < 6
            p.vx = when (p.id % 3) { 0 -> 0.00006; 1 -> 0.0; else -> -0.00004 }
            p.desiredX = p.vx; p.vy = 0.0; p.desiredY = 0.0; p.spin = 0.0; p.desiredSpin = 0.0
        }
        for (y in 0 until height) for (x in 0 until width) {
            val p = ps[y / 18 * 3 + x / 20]
            val ocean = x % 20 >= 16 && p.id % 3 == 0 || p.id % 3 == 2
            invoke(model, "addWorld", p, (x + 0.5) * PLANET_WIDTH_KM / width,
                (y + 0.5) * PLANET_HEIGHT_KM / height, 1.0, if (ocean) 0.0 else 30.0,
                if (ocean) 7.0 else 0.0, 0.0, 60_000_000.0, 0.0, 0.0, 0.5)
        }
        invoke(model, "rasterize")
        // Isolate ocean closure from population recovery and fracture timing in this fixture.
        field(model, "lastBreakup").setDouble(model, 1e12)
        val mass = model.rockMass
        val rain = DoubleArray(width * height)
        var travel = 0.0
        var firstCollision = -1
        var lateShortening = 0.0
        var joinedTicks = 0
        repeat(120) { tick ->
            model.advance(100.0, rain, speedMultiplier = 10_000.0)
            travel += (ps[0].vx - ps[1].vx) * 1_000_000
            if (model.shortenedArea > 1.0 && firstCollision < 0) firstCollision = tick
            if (tick == 99) lateShortening = model.shortenedArea
            if (tick >= 100 && world.plateIds.indices.any { i ->
                val j = worldIndex(i % width + 1, i / width, width, height)
                world.plateIds[i] == 0 && world.plateIds[j] == 1 &&
                    world.isLand(i % width, i / width) && world.isLand(j % width, j / width)
            }) joinedTicks++
            budget(model, mass)
        }
        assertTrue(travel > 2000, "Continents approached by only $travel km")
        assertTrue(model.subductedArea > 10)
        assertTrue(firstCollision in 1..99, "No continental collision: $firstCollision")
        assertTrue(model.shortenedArea > lateShortening, "Convergence did not persist after ocean closure")
        assertTrue(joinedTicks >= 15, "Joined continental land did not persist: $joinedTicks ticks")
        assertTrue(ps.any { p -> p.cells.values.any { it.continent / it.area > 35 } })
        assertTrue(model.continentalVolume > mass / CONTINENT_DENSITY * 0.5)
    }

    @Test fun repeatedLifecycleKeepsRelativeMotionAndConservesMass() {
        val model = PlateTectonics(World(31742, 30, 22))
        val mass = model.rockMass
        val rain = DoubleArray(30 * 22) { 1000.0 }
        repeat(800) {
            model.advance(100.0, rain)
            assertTrue(model.relativeSpeed > 0.000001)
            assertTrue(model.activePlateCount in 6..10)
            val snapshot = (field(model, "world").get(model) as World).snapshot(0)
            val seen = BooleanArray(30 * 22)
            val regions = HashSet<Int>()
            val topology = PlateTopology(30, 22)
            for (i in seen.indices) {
                val id = snapshot.plateId(i % 30, i / 30)
                val edge = id != snapshot.plateId(i % 30 - 1, i / 30) || id != snapshot.plateId(i % 30, i / 30 - 1)
                assertEquals(edge, snapshot.plateBoundary(i % 30, i / 30) != PlateBoundary.NONE)
                if (seen[i]) continue
                assertTrue(regions.add(id), "Disconnected territory $id")
                val queue = ArrayDeque<Int>(); queue.add(i); seen[i] = true
                while (queue.isNotEmpty()) topology.neighbors(queue.removeFirst()) { j ->
                    if (!seen[j] && snapshot.plateId(j % 30, j / 30) == id) { seen[j] = true; queue.add(j) }
                }
            }
            assertEquals(model.activePlateCount, regions.size)
            budget(model, mass)
        }
        assertTrue(model.splits > 0)
        assertTrue(model.shortenedArea > 0 && model.subductedArea > 0)
        assertTrue(model.lifecycleEvents.isNotEmpty())
    }
}
