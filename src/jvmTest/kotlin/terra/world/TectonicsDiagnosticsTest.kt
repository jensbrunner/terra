package terra.world

import java.io.File
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.*
import terra.climate.ClimateSimulation
import terra.climate.YEAR_SECONDS
import terra.simulation.GeologySettings

/** Explicitly enabled long experiment; ordinary tests only run the isolated transfer fixture. */
class TectonicsDiagnosticsTest {
    private fun field(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun plates(model: PlateTectonics) = field(model, "plates").get(model) as List<MaterialPlate>
    private fun invoke(model: PlateTectonics, name: String, vararg args: Any): Any? =
        model.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(model, *args)

    @Test fun repeatedFrameTransferMeasuresDiffusionWithoutDeformation() {
        val model = PlateTectonics(World(31742, 16, 12))
        val ps = plates(model)
        for (p in ps) { p.cells.clear(); p.active = p.id < 2 }
        val a = ps[0]; val b = ps[1]
        a.angle = 0.0
        b.x = PLANET_WIDTH_KM - 57.3; b.y = PLANET_HEIGHT_KM - 31.2; b.angle = 0.17
        b.prepare()
        for (y in 0 until 12) for (x in 0 until 16) {
            invoke(model, "addWorld", a, (x + 0.5) * PLANET_WIDTH_KM / 16,
                (y + 0.5) * PLANET_HEIGHT_KM / 12, 1.0, if (x < 8) 30.0 else 0.0,
                if (x < 8) 0.0 else 7.0, 0.3, 23e6, 0.2, 0.1, 0.23)
        }
        fun moments() = listOf<(CrustCell) -> Double>({ it.area }, { it.continent }, { it.basalt },
            { it.sediment }, { it.sedimentContinent }, { it.age * it.area }, { it.relief * it.area }, { it.weakness * it.area })
            .map { f -> ps.sumOf { p -> p.cells.sumOf(f) } }
        fun contrast() = a.cells.sumOf { it.area * (it.continent / it.area - 15).pow(2) }
        val before = moments(); val initialContrast = contrast()
        val originals = a.cells.toList()
        val properties = originals.map { listOf(it.area, it.continent, it.basalt, it.sediment,
            it.sedimentContinent, it.age, it.relief, it.weakness) }
        fun position(p: MaterialPlate, cell: CrustCell) =
            (p.x + p.cosAngle * cell.x - p.sinAngle * cell.y) to (p.y + p.sinAngle * cell.x + p.cosAngle * cell.y)
        val positions = originals.map { position(a, it) }
        invoke(model, "rasterize")
        @Suppress("UNCHECKED_CAST")
        val rasters = field(model, "rasters").get(model) as List<PlateRaster>
        val projected = (0 until 16 * 12).map { listOf(rasters[0].area[it], rasters[0].continent[it], rasters[0].support[it]) }
        repeat(100) { step ->
            val source = if (step % 2 == 0) a else b
            val target = if (step % 2 == 0) b else a
            source.prepare(); target.prepare()
            for (cell in source.cells) {
                val (x, y) = position(source, cell)
                invoke(model, "changeFrame", target, cell, x, y)
            }
            source.cells.clear()
        }
        for ((x, y) in before.zip(moments())) assertEquals(x, y, max(1.0, abs(x)) * 1e-11)
        assertEquals(initialContrast, contrast(), initialContrast * 1e-12)
        assertEquals(originals, a.cells)
        invoke(model, "rasterize")
        for (i in projected.indices) {
            val after = listOf(rasters[0].area[i], rasters[0].continent[i], rasters[0].support[i])
            for ((x, y) in projected[i].zip(after)) assertEquals(x, y, 1e-10)
        }
        for (i in originals.indices) {
            val cell = originals[i]
            assertEquals(properties[i], listOf(cell.area, cell.continent, cell.basalt, cell.sediment,
                cell.sedimentContinent, cell.age, cell.relief, cell.weakness))
            val (x, y) = position(a, cell)
            assertEquals(0.0, longitudeKm(x - positions[i].first), 1e-8)
            assertEquals(0.0, latitudeKm(y - positions[i].second), 1e-8)
        }
    }

    @Test fun rotatedUniformMaterialMeasuresRasterNoise() {
        val width = 60; val height = 44
        val dx = PLANET_WIDTH_KM / width; val dy = PLANET_HEIGHT_KM / height
        val model = PlateTectonics(World(31742, width, height))
        val ps = plates(model)
        for (p in ps) { p.cells.clear(); p.active = p.id == 0 }
        val p = ps[0]
        p.x = PLANET_WIDTH_KM * 0.5; p.y = PLANET_HEIGHT_KM * 0.5
        for (y in -6 until 6) for (x in -10 until 10)
            p.cells += CrustCell((x + 0.5) * dx, (y + 0.5) * dy, 1.0, 30.0, 0.0, 0.0, 40e6)
        for (angle in listOf(0.0, 0.17, 0.6)) {
            p.angle = angle
            invoke(model, "rasterize")
            val area = field(model, "totalArea").get(model) as DoubleArray
            val interior = area.indices.filter { i ->
                val rx = (i % width + 0.5) * dx - p.x
                val ry = (i / width + 0.5) * dy - p.y
                abs(p.cosAngle * rx + p.sinAngle * ry) < 8 * dx &&
                    abs(-p.sinAngle * rx + p.cosAngle * ry) < 4 * dy
            }.map { area[it] }
            val rms = sqrt(interior.sumOf { (it - 1).pow(2) } / interior.size)
            println("rotated uniform patch angle=$angle rms=$rms min=${interior.min()} max=${interior.max()}")
            assertEquals(240.0, area.sum(), 1e-10)
            assertTrue(interior.all { it > 0.0 && it.isFinite() })
        }
    }

    @Test fun coupledBaseline() {
        val output = System.getenv("TERRA_DIAGNOSTICS") ?: return
        val world = World(System.getenv("TERRA_SEED")?.toInt() ?: 31742,
            System.getenv("TERRA_WIDTH")?.toInt() ?: 283, System.getenv("TERRA_HEIGHT")?.toInt() ?: 212)
        val climate = ClimateSimulation(world)
        climate.geologySettings = GeologySettings(updateYears = System.getenv("TERRA_INTERVAL")?.toInt() ?: 100)
        val model = field(climate, "geology").get(climate) as PlateTectonics
        val initialMass = model.rockMass
        val initialComponents = listOf<(CrustCell) -> Double>({ it.continent }, { it.basalt }, { it.sediment }, { it.sedimentContinent })
            .map { f -> plates(model).sumOf { p -> p.cells.sumOf(f) } }
        val file = File(output)
        file.writeText("coupled seed=${world.seed} size=${world.width}x${world.height} multiplier=10000 interval=${climate.geologySettings.updateYears}\n")
        val start = System.nanoTime()
        fun checkpoint(myr: Double, tickMs: Double) {
            val cells = plates(model).flatMap { it.cells }
            assertTrue(cells.all { it.area > 0 && it.x.isFinite() && it.y.isFinite() && it.area.isFinite() &&
                it.continent.isFinite() && it.basalt.isFinite() && it.sediment.isFinite() && it.sedimentContinent.isFinite() &&
                it.age.isFinite() && it.relief.isFinite() && it.weakness.isFinite() &&
                it.continent >= 0 && it.basalt >= 0 && it.sediment >= 0 && it.sedimentContinent in 0.0..it.sediment })
            val thickness = cells.filter { it.area >= 0.01 && it.continent > 0 }.map { it.continent / it.area }.sorted()
            fun percentile(q: Double) = thickness[(q * (thickness.size - 1)).toInt()]
            val area = field(model, "totalArea").get(model) as DoubleArray
            val owner = field(model, "owner").get(model) as IntArray
            @Suppress("UNCHECKED_CAST")
            val rasters = field(model, "rasters").get(model) as List<PlateRaster>
            assertTrue(owner.all { it >= 0 && plates(model)[it].active })
            val seen = BooleanArray(owner.size)
            val regions = HashSet<Int>()
            val topology = PlateTopology(world.width, world.height)
            for (i in owner.indices) if (!seen[i]) {
                assertTrue(regions.add(owner[i]), "Disconnected published owner ${owner[i]}")
                val queue = ArrayDeque<Int>(); queue.add(i); seen[i] = true
                while (queue.isNotEmpty()) topology.neighbors(queue.removeFirst()) { j ->
                    if (!seen[j] && owner[j] == owner[i]) { seen[j] = true; queue.add(j) }
                }
            }
            val elevations = area.indices.map { world[it % world.width, it / world.width].elevation }
            val peak = elevations.indices.maxBy { elevations[it] }
            val peakVolumes = listOf(rasters.sumOf { it.continent[peak] }, rasters.sumOf { it.basalt[peak] }, rasters.sumOf { it.sediment[peak] })
            val histogram = (0..10).map { bin -> elevations.count { floor(it * 10).toInt().coerceIn(0, 10) == bin } }
            val absent = area.indices.sumOf { i -> area[i] - if (owner[i] >= 0) rasters[owner[i]].area[i] else 0.0 }
            val components = listOf(cells.sumOf { it.continent }, cells.sumOf { it.basalt }, cells.sumOf { it.sediment }, cells.sumOf { it.sedimentContinent })
            for (k in components.indices) assertEquals(initialComponents[k] + model.mantleAddedComponents[k] -
                model.mantleRecycledComponents[k] + model.convertedComponents[k], components[k], initialMass / 2400 * 1e-8, "Component $k at $myr Myr")
            val error = (model.rockMass - initialMass - model.mantleAddedMass + model.mantleRecycledMass) / initialMass
            assertTrue(abs(error) < 1e-8, "Mass ledger error at $myr Myr: $error")
            val image = BufferedImage(world.width * 2, world.height, BufferedImage.TYPE_INT_RGB)
            for (i in area.indices) {
                val h = elevations[i] - world.seaLevel
                val color = if (h < 0) Color(0, (65 + h * 90).toInt().coerceIn(15, 65), (120 + h * 120).toInt().coerceIn(30, 120))
                    else Color((60 + h * 400).toInt().coerceIn(60, 255), (125 + h * 150).toInt().coerceIn(125, 255), (45 + h * 400).toInt().coerceIn(45, 255))
                image.setRGB(i % world.width, i / world.width, color.rgb)
                image.setRGB(world.width + i % world.width, i / world.width, Color.HSBtoRGB((owner[i] * 0.618034 % 1).toFloat(), 0.65f, 0.85f))
            }
            ImageIO.write(image, "png", File("$output-$myr.png"))
            file.appendText("myr=$myr land=${elevations.count { it >= world.seaLevel }.toDouble() / area.size} " +
                "elevationRange=${elevations.min()}..${elevations.max()} peakTile=$peak peakArea=${area[peak]} peakVolumes=$peakVolumes maxParcelThickness=${cells.maxOf { (it.continent + it.basalt + it.sediment) / it.area }} thickness=${listOf(percentile(0.1), percentile(0.5), percentile(0.9))} " +
                "continent=${model.continentalVolume} basalt=${cells.sumOf { it.basalt }} sediment=${cells.sumOf { it.sediment }} " +
                "areaEmpty=${area.count { it < 1e-10 }} under=${area.count { it < 0.9 }} over=${area.count { it > 1.1 }} foreign=$absent " +
                "captureCount=${model.captureTransfers} captureMass=${model.capturedMass} subducted=${model.subductedArea} shortened=${model.shortenedArea} spread=${model.oceanCreatedArea} " +
                "requestedClosing=${model.requestedConvergenceArea} unresolvedClosing=${model.unresolvedConvergenceArea} pendingOpening=${model.pendingOpeningArea} requestedOpening=${model.requestedDivergenceArea} unresolvedOpening=${model.unresolvedDivergenceArea} ledger=$error plates=${model.activePlateCount} speed=${model.relativeSpeed} splits=${model.splits} welds=${model.mergers} " +
                "steps=${model.geologicalSteps} substeps=${model.geologicalSubsteps} geologyMs=${model.lastWorkSeconds * 1000} geologySeconds=${model.geologicalWorkSeconds} maxDisplacementKm=${model.maxSubstepDisplacementKm} unresolvedArea=${model.unresolvedArea} unresolvedRemovalMass=${model.unresolvedRemovalMass} unplacedAdditionMass=${model.unplacedAdditionMass} parcels=${cells.size} tickMs=$tickMs elapsedSeconds=${(System.nanoTime() - start) / 1e9} histogram=$histogram\n")
            assertTrue(elevations.all { it.isFinite() && abs(it - world.seaLevel) < 10.0 },
                "Unbounded terrain at $myr Myr: ${elevations[peak]}")
        }
        checkpoint(0.0, 0.0)
        val interval = climate.geologySettings.updateYears
        val targetMyr = System.getenv("TERRA_MYR")?.toInt() ?: 500
        repeat(targetMyr * 100 / interval) { tick ->
            val begin = System.nanoTime()
            climate.advance(interval * YEAR_SECONDS)
            assertTrue(model.unresolvedRemovalMass < 1e-6,
                "Component overdraw at ${model.effectiveYears}: ${model.unresolvedRemovalMass}")
            val ms = (System.nanoTime() - begin) / 1e6
            val myr = (tick + 1) * interval / 100.0
            if (myr in listOf(1.0, 25.0, 100.0, 300.0, 500.0, targetMyr.toDouble())) checkpoint(myr, ms)
        }
        file.appendText("events=${model.lifecycleEvents.joinToString()}\n")
    }
}
