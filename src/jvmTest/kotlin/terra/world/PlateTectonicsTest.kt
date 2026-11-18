package terra.world

import kotlin.math.*
import kotlin.test.*
import terra.hydrology.HydrologyTopology

class PlateTectonicsTest {
    private fun field(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun plates(model: PlateTectonics) = field(model, "plates").get(model) as List<MaterialPlate>
    private fun invoke(model: PlateTectonics, name: String, vararg args: Any?): Any? =
        model.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(model, *args)
    private fun budget(model: PlateTectonics, initial: Double) =
        assertEquals(initial + model.mantleAddedMass - model.mantleRecycledMass, model.rockMass, initial * 1e-8)

    @Test fun substepsProcessAllTimeAndPublishGeographyOnlyAtTheOuterCadence() {
        val world = World(31742, 283, 212)
        val model = PlateTectonics(world)
        val original = plates(model).first().cells.first()
        val mass = model.rockMass
        model.advance(100.0, DoubleArray(283 * 212) { 1000.0 }, speedMultiplier = 10_000.0)
        assertEquals(1_000_000.0, model.effectiveYears)
        assertEquals(41_000_000.0, original.age, 1e-6)
        assertEquals(1L, world.geographyRevision)
        assertEquals(1L, model.geologicalSteps)
        assertTrue(model.geologicalSubsteps > 1)
        assertTrue(model.maxSubstepDisplacementKm <= 0.3 * min(PLANET_WIDTH_KM / 283, PLANET_HEIGHT_KM / 212) + 1e-8)
        budget(model, mass)
    }

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

    @Test fun eventProjectionAgreesWithAFreshConservativeScatter() {
        val model = collisionFixture(0.45)
        repeat(5) { model.advance(100.0, DoubleArray(36 * 24) { 1500.0 }, speedMultiplier = 10_000.0) }
        @Suppress("UNCHECKED_CAST")
        val rasters = field(model, "rasters").get(model) as List<PlateRaster>
        val fields = listOf<(PlateRaster) -> RasterField>({ it.area }, { it.continent }, { it.basalt },
            { it.sediment }, { it.sedimentContinent }, { it.support }, { it.compressible }, { it.age }, { it.weakness })
        val before = rasters.map { r -> fields.map { select -> DoubleArray(36 * 24) { select(r)[it] } } }
        invoke(model, "rasterize")
        for (p in rasters.indices) for (f in fields.indices) for (i in 0 until 36 * 24)
            assertEquals(before[p][f][i], fields[f](rasters[p])[i], max(1.0, abs(before[p][f][i])) * 1e-9)
    }

    @Test fun rotatedProjectionPreservesTheStoredInventories() {
        val model = PlateTectonics(World(31742, 36, 26))
        val ps = plates(model)
        for (p in ps) for (cell in p.cells) { cell.sediment = 0.3; cell.sedimentContinent = 0.1 }
        @Suppress("UNCHECKED_CAST")
        val rasters = field(model, "rasters").get(model) as List<PlateRaster>
        for (angle in listOf(0.0, 0.17, 0.6)) {
            for (p in ps) { p.angle = angle; p.x += 77.3; p.y += 31.2 }
            invoke(model, "rasterize")
            val stored = listOf(ps.sumOf { p -> p.cells.sumOf { it.area } }, model.continentalVolume,
                ps.sumOf { p -> p.cells.sumOf { it.basalt } }, ps.sumOf { p -> p.cells.sumOf { it.sediment } },
                ps.sumOf { p -> p.cells.sumOf { it.sedimentContinent } }, ps.sumOf { p -> p.cells.sumOf { it.area * it.age } },
                ps.sumOf { p -> p.cells.sumOf { it.area * it.weakness } }, ps.sumOf { p -> p.cells.sumOf { it.support } },
                ps.sumOf { p -> p.cells.sumOf { it.compressible } })
            val projected = listOf<(PlateRaster) -> RasterField>({ it.area }, { it.continent }, { it.basalt }, { it.sediment },
                { it.sedimentContinent }, { it.age }, { it.weakness }, { it.support }, { it.compressible })
                .map { select -> rasters.sumOf { r -> (0 until 36 * 26).sumOf { select(r)[it] } } }
            for (i in stored.indices) assertEquals(stored[i], projected[i], max(1.0, abs(stored[i])) * 1e-11)
        }
    }

    @Test fun largeGeologicalStepsAndSedimentDepletionKeepTheMassLedgerClosed() {
        val model = collisionFixture(0.45)
        for (p in plates(model)) for (cell in p.cells) {
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

    @Test fun aRotatedWeldPreservesParcelsAndReusedSlotsDoNotReuseIdentity() {
        val model = PlateTectonics(World(31742, 36, 26))
        val a = plates(model)[0]; val b = plates(model)[1]
        a.angle = 0.17; b.angle = -0.23; b.x += PLANET_WIDTH_KM; b.y -= PLANET_HEIGHT_KM
        invoke(model, "rasterize")
        val original = a.cells.toList() + b.cells.toList()
        val positions = listOf(a, b).flatMap { p -> p.cells.map { cell ->
            cell to ((p.x + p.cosAngle * cell.x - p.sinAngle * cell.y) to
                (p.y + p.sinAngle * cell.x + p.cosAngle * cell.y))
        } }.toMap()
        val properties = original.associateWith { listOf(it.area, it.continent, it.basalt, it.sediment,
            it.sedimentContinent, it.age, it.relief, it.weakness) }
        val mass = model.rockMass
        invoke(model, "merge", a, b)
        assertEquals(original, a.cells)
        for (cell in original) {
            val (x, y) = positions.getValue(cell)
            assertEquals(0.0, longitudeKm(a.x + a.cosAngle * cell.x - a.sinAngle * cell.y - x), 1e-8)
            assertEquals(0.0, latitudeKm(a.y + a.sinAngle * cell.x + a.cosAngle * cell.y - y), 1e-8)
            assertEquals(properties.getValue(cell), listOf(cell.area, cell.continent, cell.basalt, cell.sediment,
                cell.sedimentContinent, cell.age, cell.relief, cell.weakness))
        }
        invoke(model, "rasterize")
        val parent = plates(model).filter { it.active && it !== a }.maxBy { it.area }
        assertEquals(true, invoke(model, "split", parent))
        val child = plates(model)[b.id]
        assertNotEquals(b.identity, child.identity)
        assertEquals(child.identity, model.lifecycleEvents.last().second)
        budget(model, mass)
    }

    @Test fun fracturePreservesPositionsAndRockThenDaughtersSeparate() {
        val model = PlateTectonics(World(42, 48, 36))
        val p = plates(model).maxBy { it.area }
        val mass = model.rockMass
        val positions = p.cells.toSet()
        assertEquals(true, invoke(model, "split", p))
        val child = plates(model).last()
        assertEquals(positions, (p.cells + child.cells).toSet())
        assertEquals(p.x, child.x); assertEquals(p.y, child.y); assertEquals(p.angle, child.angle)
        assertTrue(min(p.area, child.area) >= 48 * 36 * 0.01)
        assertTrue(hypot(p.separationX - child.separationX, p.separationY - child.separationY) > 0.000059)
        budget(model, mass)
        invoke(model, "movePlates", 1_000_000.0)
        assertTrue(hypot(longitudeKm(p.x - child.x), latitudeKm(p.y - child.y)) > 30)
        budget(model, mass)
    }

    @Test fun weldingRequiresThirtyMillionYearsOfAppliedContinentalConvergence() {
        val model = PlateTectonics(World(31742, 36, 26))
        for (p in plates(model)) for (cell in p.cells) { cell.continent = 30.0; cell.basalt = 0.0 }
        invoke(model, "rasterize")
        invoke(model, "observeContacts", 100_000.0)
        invoke(model, "collide", 100_000.0)
        invoke(model, "applyEvents")
        @Suppress("UNCHECKED_CAST")
        val contacts = field(model, "contacts").get(model) as MutableMap<Long, Any>
        val pair = contacts.keys.first { key ->
            field(contacts.getValue(key), "continentalLength").getDouble(contacts.getValue(key)) >= 500 &&
                invoke(model, "mergeFits", plates(model)[(key ushr 32).toInt()], plates(model)[key.toInt()]) == true
        }
        val contact = contacts.getValue(pair)
        field(contact, "duration").setDouble(contact, 29_800_000.0)
        field(contact, "shortening").setDouble(contact, 1e9)
        field(contact, "recentShortening").setDouble(contact, 1.0)
        val survivor = plates(model)[(pair ushr 32).toInt()]
        survivor.stressXX = 1.0; survivor.separationUntil = 1e12; survivor.separationX = 0.00003
        val mass = model.rockMass
        invoke(model, "evolvePlates", 100_000.0)
        assertEquals(0, model.mergers)
        invoke(model, "evolvePlates", 100_000.0)
        assertEquals(1, model.mergers)
        assertEquals(0.0, survivor.stressXX + survivor.stressXY + survivor.stressYY)
        assertEquals(0.0, survivor.separationUntil + survivor.separationX + survivor.separationY)
        budget(model, mass)
    }

    @Test fun inactivityAgeAndHistoricalStrainCannotForceAFracture() {
        val model = PlateTectonics(World(42, 48, 36))
        field(model, "geologicalTime").setDouble(model, 500_000_000.0)
        val p = plates(model).maxBy { it.area }
        p.extension = 10.0; p.riftYears = 100_000_000.0; p.extensionAdded = 0.0
        val mass = model.rockMass
        invoke(model, "evolvePlates", 0.0)
        assertEquals(0, model.splits)
        budget(model, mass)
    }

    @Test fun aMatureRecentlyExtendingPlateCanSplitAfterItsPhysicalCooldown() {
        val model = PlateTectonics(World(42, 48, 36))
        field(model, "geologicalTime").setDouble(model, 100_000_000.0)
        val p = plates(model).maxBy { it.area }
        p.extension = 0.3; p.riftYears = 30_000_000.0; p.extensionAdded = 0.01
        val mass = model.rockMass
        invoke(model, "evolvePlates", 0.0)
        assertEquals(1, model.splits)
        assertEquals("mature rift under recent extension", model.lifecycleEvents.last().reason)
        budget(model, mass)
    }

    @Test fun sedimentConsolidatesIntoItsOriginalRockWithDensityConversion() {
        val model = PlateTectonics(World(42, 24, 18))
        val cell = plates(model).first().cells.first()
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
            val ix = floor((p.x + longitudeKm((x + 0.5) * dx - p.x)) / dx).toInt()
            val iy = floor((p.y + latitudeKm((y + 0.5) * dy - p.y)) / dy).toInt()
            p.cells += CrustCell((ix + 0.5) * dx - p.x, (iy + 0.5) * dy - p.y, 1.0, 30.0, 0.0, 0.0, 0.0)
        }
        invoke(model, "rasterize")
        return model
    }

    @Test fun collisionBuildsRootsAndRiftingConservesContinentalRock() {
        val model = collisionFixture()
        val mass = model.rockMass; val continent = model.continentalVolume
        repeat(60) { model.advance(100.0, DoubleArray(36 * 24) { 1000.0 }); budget(model, mass) }
        assertTrue(model.shortenedArea > 0)
        assertTrue(model.riftArea > 0)
        assertTrue(plates(model).any { p -> p.cells.any { it.continent / it.area > 30.001 } })
        assertTrue(model.continentalVolume >= continent - continent * 1e-10)
        // Newly spread basalt can already subduct and supply arcs during this coupled fixture.
        assertTrue(model.continentalVolume - continent <= model.mantleAddedMass / CONTINENT_DENSITY + continent * 1e-10)
    }

    @Test fun continentalRiftingCanSubmergeLandWithoutLosingContinentalRock() {
        val model = collisionFixture()
        for (cell in plates(model)[0].cells) cell.area *= 0.9
        invoke(model, "rasterize")
        @Suppress("UNCHECKED_CAST")
        val rasters = field(model, "rasters").get(model) as List<PlateRaster>
        val area = field(model, "totalArea").get(model) as DoubleArray
        fun exposed() = area.indices.count { i -> rasters.sumOf { it.support[i] } >= COMPENSATION_KM * area[i] }
        val land = exposed(); val continent = model.continentalVolume; val mass = model.rockMass
        invoke(model, "fillGaps", 5_000_000.0)
        invoke(model, "rasterize")
        assertTrue(model.riftArea > 0 && exposed() < land)
        assertEquals(continent, model.continentalVolume, continent * 1e-10)
        assertEquals(0.0, model.mantleAddedMass)
        budget(model, mass)
    }

    @Test fun sustainedOceanSubductionBuildsVolcanicLandFromAccountedRock() {
        val model = collisionFixture()
        val world = field(model, "world").get(model) as World
        for (p in plates(model)) for (cell in p.cells) {
            cell.continent = 0.0; cell.basalt = 7.0 * cell.area; cell.relief = 0.0; cell.age = 60_000_000.0
        }
        invoke(model, "rasterize")
        val mass = model.rockMass
        var formedLand = false
        // Initial and ridge-only 7 km basalt are below sea level under the unchanged support law.
        repeat(160) {
            if (!formedLand) {
                model.advance(100.0, DoubleArray(36 * 24), speedMultiplier = 10_000.0)
                formedLand = (0 until 36 * 24).any { world.isLand(it % 36, it / 36) }
                budget(model, mass)
            }
        }
        assertTrue(formedLand, "Sustained arc additions never built exposed land")
        assertTrue(model.subductedArea > 0 && model.mantleAddedComponents[0] > 0)
        assertTrue(model.continentalVolume > 0)
    }

    @Test fun isolatedRidgeErodesItsRockAndSubductionAccountsForRecycledFragments() {
        val erosion = collisionFixture(0.45)
        for (p in plates(erosion)) {
            p.vx = 0.0; p.vy = 0.0; p.desiredX = 0.0
        }
        plates(erosion).first().cells.first().continent = 40.0
        invoke(erosion, "rasterize")
        @Suppress("UNCHECKED_CAST")
        val beforeRasters = field(erosion, "rasters").get(erosion) as List<PlateRaster>
        val beforeArea = field(erosion, "totalArea").get(erosion) as DoubleArray
        val oldPeak = beforeArea.indices.maxOf { i -> beforeRasters.sumOf { it.support[i] } / beforeArea[i] - COMPENSATION_KM }
        val c = erosion.continentalVolume; val mass = erosion.rockMass
        erosion.advance(100.0, DoubleArray(36 * 24) { 1500.0 })
        assertTrue(erosion.continentalVolume < c)
        val erodedWorld = field(erosion, "world").get(erosion) as World
        val newPeak = beforeArea.indices.maxOf { (erodedWorld[it % 36, it / 36].elevation - erodedWorld.seaLevel) * 10 }
        assertTrue(newPeak < oldPeak, "Old peak $oldPeak, eroded peak $newPeak")
        assertTrue(plates(erosion).any { p -> p.cells.any { it.sedimentContinent > 0 } })
        budget(erosion, mass)
        val subduction = collisionFixture()
        for (p in plates(subduction).filter { it.id % 3 == 0 }) for (cell in p.cells) {
            cell.continent = 0.1; cell.basalt = 7.0; cell.sediment = 0.2; cell.sedimentContinent = 0.1
        }
        val initial = subduction.rockMass
        repeat(10) { subduction.advance(100.0, DoubleArray(36 * 24) { 1000.0 }); budget(subduction, initial) }
        assertTrue(subduction.subductedArea > 0)
        assertTrue(subduction.mantleRecycledMass > 0)
        assertTrue(subduction.mantleAddedMass > 0)
    }

    @Test fun jaggedMaterialFracturesAreDeterministicAndRespondToInheritedWeakness() {
        fun cut(offset: Int): Set<Pair<Double, Double>> {
            val model = PlateTectonics(World(42, 80, 60))
            val p = plates(model).maxBy { it.area }
            p.stressXX = 1.0
            val middle = (p.minX + p.maxX) / 2
            for (cell in p.cells) {
                cell.continent = cell.area * 30
                cell.basalt = 0.0
                val fault = middle + (((cell.y / (PLANET_HEIGHT_KM / 60)).toInt() + offset) / 3 % 5 - 2) * (PLANET_WIDTH_KM / 80)
                cell.weakness = if (abs(cell.x - fault) <= PLANET_WIDTH_KM / 80) 1.0 else 0.0
            }
            invoke(model, "rasterize")
            val mass = model.rockMass
            assertEquals(true, invoke(model, "split", p))
            budget(model, mass)
            return plates(model).last().cells.map { it.x to it.y }.toSet()
        }
        assertEquals(cut(0), cut(0))
        assertNotEquals(cut(0), cut(7))
    }

    @Test fun displayRepairPreservesDetachedMaterialWithoutChangingItsFrame() {
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
        fun moment(value: (CrustCell) -> Double) = ps.sumOf { p -> p.cells.sumOf(value) }
        val before = listOf(moment { it.area }, moment { it.continent }, moment { it.basalt },
            moment { it.sediment }, moment { it.sedimentContinent }, moment { it.age * it.area },
            moment { it.relief * it.area }, moment { it.weakness * it.area })
        val mass = model.rockMass; val added = model.mantleAddedMass; val recycled = model.mantleRecycledMass
        val originals = ps.map { it.cells.toList() }
        val positions = ps.map { p -> p.cells.map { it.x to it.y } }
        invoke(model, "rasterize")
        assertEquals(target, owner[tile])
        repeat(10) { invoke(model, "rasterize") }
        val after = listOf(moment { it.area }, moment { it.continent }, moment { it.basalt },
            moment { it.sediment }, moment { it.sedimentContinent }, moment { it.age * it.area },
            moment { it.relief * it.area }, moment { it.weakness * it.area })
        for (i in before.indices) assertEquals(before[i], after[i], max(1.0, abs(before[i])) * 1e-11)
        assertEquals(originals, ps.map { it.cells.toList() })
        assertEquals(positions, ps.map { p -> p.cells.map { it.x to it.y } })
        assertEquals(0.0, model.capturedMass)
        assertEquals(added, model.mantleAddedMass); assertEquals(recycled, model.mantleRecycledMass)
        budget(model, mass)
    }

    @Test fun freshCrustBelongsToTheOpeningTerritoryRatherThanAlternatingTiles() {
        val model = collisionFixture()
        val ps = plates(model)
        for (p in ps) if (p.active) for (cell in p.cells) cell.area *= 0.9
        invoke(model, "rasterize")
        val territory = (field(model, "owner").get(model) as IntArray).copyOf()
        val before = ps.map { p -> p.cells.toSet() }
        invoke(model, "fillGaps", 5_000_000.0)
        var births = 0
        for (p in ps) if (p.active) for (cell in p.cells) {
            if (cell !in before[p.id]) {
                val x = p.x + p.cosAngle * cell.x - p.sinAngle * cell.y
                val y = p.y + p.sinAngle * cell.x + p.cosAngle * cell.y
                assertEquals(p.id, territory[worldIndex(floor(x / (PLANET_WIDTH_KM / 36)).toInt(), floor(y / (PLANET_HEIGHT_KM / 24)).toInt(), 36, 24)])
                births++
            }
        }
        assertTrue(births > 0)
    }

    @Test fun exhaustedContinentalShorteningIsUnresolvedInsteadOfCollapsingColumns() {
        val model = collisionFixture()
        val mass = model.rockMass
        repeat(100) {
            invoke(model, "collide", 1_000_000.0)
            invoke(model, "applyEvents")
            invoke(model, "rasterize")
        }
        assertTrue(model.shortenedArea > 0)
        assertTrue(model.unresolvedConvergenceArea > 0)
        assertTrue(plates(model).flatMap { it.cells }.all { (it.continent + it.basalt + it.sediment) / it.area <= ROOT_THICKNESS_KM + 1e-8 })
        budget(model, mass)
    }

    @Test fun simultaneousRemovalShorteningAndEmplacementUseTheFinalInventoryCapacity() {
        val model = PlateTectonics(World(42, 36, 24))
        val ps = plates(model)
        for (p in ps) { p.cells.clear(); p.active = p.id == 0 }
        val cell = invoke(model, "addWorld", ps[0], PLANET_WIDTH_KM / 72, PLANET_HEIGHT_KM / 48,
            1.0, 0.0, 0.0, 0.0, 40e6, 0.0, 0.0, 0.5) as CrustCell
        invoke(model, "rasterize")
        @Suppress("UNCHECKED_CAST")
        val r = (field(model, "rasters").get(model) as List<PlateRaster>)[0]
        r.remove[0] = 0.5; r.compress[0] = 0.5; r.dc[0] = 35.0
        invoke(model, "applyEvents")
        assertEquals(0.5, cell.area, 1e-12)
        assertEquals(35.0, cell.continent, 1e-12)
        assertEquals(70.0, cell.continent / cell.area, 1e-10)
        assertEquals(0.5, model.unresolvedArea, 1e-12)
    }

    @Test fun rootFlowCrossesWorldSeamsEvenAcrossThePlateFrameCut() {
        val model = PlateTectonics(World(42, 36, 24))
        val ps = plates(model)
        for (p in ps) { p.cells.clear(); p.active = p.id == 0 }
        val p = ps[0]; p.x = PLANET_WIDTH_KM * 0.5; p.y = PLANET_HEIGHT_KM * 0.5
        invoke(model, "addWorld", p, 10.0, p.y, 1.0, 100.0, 0.0, 0.0, 40e6, 0.0, 0.0, 0.5)
        invoke(model, "addWorld", p, PLANET_WIDTH_KM - 10.0, p.y, 1.0, 30.0, 0.0, 0.0, 40e6, 0.0, 0.0, 0.5)
        val mass = model.rockMass
        invoke(model, "rasterize")
        invoke(model, "spreadRoots")
        assertEquals(70.0, p.cells[0].continent, 1e-12)
        assertEquals(60.0, p.cells[1].continent, 1e-12)
        budget(model, mass)
    }

    @Test fun erosionDoesNotDepositAWholeCatchmentOnAVanishingMaterialTail() {
        val model = PlateTectonics(World(42, 283, 212))
        val ps = plates(model)
        for (p in ps) { p.cells.clear(); p.active = p.id == 0 }
        val dx = PLANET_WIDTH_KM / 283; val dy = PLANET_HEIGHT_KM / 212
        val source = invoke(model, "addWorld", ps[0], dx * 0.5, dy * 0.5,
            1.0, 70.0, 0.0, 0.0, 40e6, 0.0, 0.0, 0.5) as CrustCell
        val tail = invoke(model, "addWorld", ps[0], dx * 1.5, dy * 0.5,
            1e-10, 0.0, 7e-10, 0.0, 40e6, 0.0, 0.0, 0.5) as CrustCell
        invoke(model, "rasterize")
        val mass = model.rockMass
        invoke(model, "erode", DoubleArray(283 * 212) { 1000.0 }, 1e6, null)
        invoke(model, "applyEvents")
        invoke(model, "spreadRoots")
        assertEquals(70.0, source.continent, 1e-10)
        assertEquals(0.0, tail.sediment, 1e-12)
        budget(model, mass)
    }

    @Test fun strandedSedimentSpreadsConservativelyAcrossANearbyPlateBoundary() {
        val model = PlateTectonics(World(42, 36, 24))
        val ps = plates(model)
        for (p in ps) { p.cells.clear(); p.active = p.id < 2 }
        invoke(model, "addWorld", ps[0], 10.0, 1000.0, 1e-10, 0.0, 0.0, 0.01, 40e6, 0.0, 0.004, 0.5)
        invoke(model, "addWorld", ps[1], PLANET_WIDTH_KM - 10.0, 1000.0, 1.0, 0.0, 7.0, 0.0, 40e6, 0.0, 0.0, 0.5)
        invoke(model, "rasterize")
        val mass = model.rockMass
        invoke(model, "spreadRoots")
        assertEquals(ROOT_THICKNESS_KM * ps[0].cells[0].area, ps[0].cells[0].sediment, 1e-12)
        assertEquals(0.01, ps.sumOf { p -> p.cells.sumOf { it.sediment } }, 1e-12)
        assertEquals(0.004, ps.sumOf { p -> p.cells.sumOf { it.sedimentContinent } }, 1e-12)
        assertTrue(ps[1].cells[0].sediment > 0)
        budget(model, mass)
    }

    @Test fun highlyShortenedParcelsStillReceiveTheirExactInventoryDeltas() {
        val r = PlateRaster(1)
        val cell = CrustCell(0.0, 0.0, 1e-16, 30.0, 0.0, 0.0, 0.0)
        r.add(0, cell, 1.0)
        r.dc[0] = -3.0; r.db[0] = 2.0
        r.distribute(0, cell, 1.0)
        assertEquals(-3.0, cell.dc, 1e-12)
        assertEquals(2.0, cell.db, 1e-12)
    }

    @Test fun sedimentWithdrawalsAndDepositsPreserveMixedColumnProvenance() {
        val r = PlateRaster(1)
        val a = CrustCell(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0)
        val b = CrustCell(0.0, 0.0, 1.0, 0.0, 0.0, 10.0, 0.0)
        b.sedimentContinent = 10.0
        r.add(0, a, 1.0); r.add(0, b, 1.0)
        r.ds[0] = -5.5; r.dsc[0] = -5.0
        r.deposit[0] = 2.0; r.depositContinent[0] = 2.0
        r.distribute(0, a, 1.0); r.distribute(0, b, 1.0)
        assertEquals(7.5, a.sediment + a.ds + b.sediment + b.ds, 1e-12)
        assertEquals(7.0, a.sedimentContinent + a.dsc + b.sedimentContinent + b.dsc, 1e-12)
        for (cell in listOf(a, b)) assertTrue(cell.sedimentContinent + cell.dsc in 0.0..(cell.sediment + cell.ds))
    }

    @Test fun tangentialContactsProduceNoNewRockOrHorizontalAreaChange() {
        val model = collisionFixture()
        for (p in plates(model)) {
            p.vx = 0.0; p.vy = if (p.id % 3 == 0) 0.00006 else -0.00006
            p.spin = 0.0
            for (cell in p.cells) cell.area *= 0.9
        }
        invoke(model, "rasterize")
        val mass = model.rockMass
        invoke(model, "collide", 1_000_000.0)
        invoke(model, "applyEvents")
        invoke(model, "rasterize")
        invoke(model, "fillGaps", 1_000_000.0)
        assertEquals(0.0, model.mantleAddedMass)
        assertEquals(0.0, model.shortenedArea + model.subductedArea + model.oceanCreatedArea + model.riftArea)
        budget(model, mass)
    }

    @Test fun unusedAreaBudgetDoesNotCreateCrustWithoutAnOpeningInterface() {
        val model = collisionFixture()
        for (p in plates(model)) if (p.active) {
            p.vx = 0.0; p.vy = 0.0; p.spin = 0.0
            for (cell in p.cells) cell.area *= 0.9
        }
        invoke(model, "rasterize")
        val mass = model.rockMass
        invoke(model, "fillGaps", 1_000_000.0)
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
            for (p in ps.filter { it.active && it.id % 3 == 0 }) for (cell in p.cells) {
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
        // Isolate ocean closure from fracture timing in this fixture.
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
        assertTrue(ps.any { p -> p.cells.any { it.continent / it.area > 35 } })
        assertTrue(model.continentalVolume > mass / CONTINENT_DENSITY * 0.5)
    }

    @Test fun repeatedLifecycleKeepsRelativeMotionAndConservesMass() {
        val model = PlateTectonics(World(31742, 30, 22))
        val mass = model.rockMass
        val rain = DoubleArray(30 * 22) { 1000.0 }
        repeat(800) {
            model.advance(100.0, rain)
            assertTrue(model.relativeSpeed > 0.000001)
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
            assertTrue(regions.all { id -> plates(model)[id].active })
            budget(model, mass)
        }
        assertTrue(model.shortenedArea > 0 && model.subductedArea > 0)
        assertTrue(model.lifecycleEvents.all { it.kind in setOf("fracture", "weld") && it.reason.isNotEmpty() })
    }
}
