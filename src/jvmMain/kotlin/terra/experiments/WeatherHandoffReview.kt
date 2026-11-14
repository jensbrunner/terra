package terra.experiments

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import terra.render.tileColor
import terra.simulation.*
import terra.weather.WeatherLayer

/** Exercises the real automatic worker, controls, immutable publications and cloud
 * renderer. Direct solver-only checks cannot detect a return straight to climate.
 */
fun main(args: Array<String>) {
    val years = args.firstOrNull { it.startsWith("--years=") }?.substringAfter('=')?.toInt() ?: 100
    val output = File("build/experiments/weather-handoff").apply { mkdirs() }
    val worker = SimulationWorker(WorldRequest())
    val rows = StringBuilder("stage,wall_s,world_s,fidelity,quality,pressure_rms_percent,curl_rms_s1,wind_rms_tiles_day,cloud_mm,rh,visible_fraction\n")
    var returnedEarly: WorldPresentationSnapshot? = null
    var lastRecorded = Double.NEGATIVE_INFINITY
    fun record(label: String, wall: Double, save: Boolean = false) {
        val s = worker.latestSnapshot() ?: return
        if (!save && wall - lastRecorded < 0.5) return
        lastRecorded = wall
        val w = s.weather
        var pressure = 0.0; var curl = 0.0; var wind = 0.0; var cloud = 0.0; var rh = 0.0; var n = 0
        for (y in 0 until w.height step 4) for (x in 0 until w.width step 4) {
            pressure += (w.pressureAnomalyPercent(x, y) ?: 0.0).pow(2)
            curl += (w.relativeVorticityPerSecond(x, y) ?: 0.0).pow(2)
            wind += w.windUTilesPerDay(x, y).pow(2) + w.windVTilesPerDay(x, y).pow(2)
            cloud += w.cloudWaterMm(x, y); rh += w.relativeHumidity(x,y); n++
        }
        val pixels = s.clouds
        var visible = 0
        if (pixels != null) for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x,y].a > 24) visible++
        val visibleFraction = if (pixels == null) 0.0 else visible.toDouble() / (pixels.width*pixels.height)
        val line = "$label,$wall,${s.progress.completedSeconds},${s.fidelity},${w.fieldSemantics.quality},${sqrt(pressure/n)},${sqrt(curl/n)},${sqrt(wind/n)},${cloud/n},${rh/n},$visibleFraction"
        rows.appendLine(line); println("HANDOFF $line")
        if (label == "returned" && wall >= 1 && returnedEarly == null) {
            returnedEarly = s
            record("returned-early", wall, true)
        }
        if (save) {
            val frame = s.clouds ?: return
            val image = BufferedImage(frame.width * 3, frame.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val c = frame[x,y]; val a = c.a / 255.0
                image.setRGB(x,y,java.awt.Color((25*(1-a)+c.r*a).toInt(),(65*(1-a)+c.g*a).toInt(),(90*(1-a)+c.b*a).toInt()).rgb)
                for ((column, layer) in listOf(WeatherLayer.PRESSURE_ANOMALY, WeatherLayer.RELATIVE_VORTICITY).withIndex()) {
                    val color = tileColor(layer,w,x/2,y/2)
                    image.setRGB((column+1)*frame.width+x,y,java.awt.Color(color.r,color.g,color.b).rgb)
                }
            }
            ImageIO.write(image,"png",File(output,"$label.png"))
        }
    }
    fun runStage(label: String, seconds: Double) {
        val start = System.nanoTime()
        while ((System.nanoTime()-start)/1e9 < seconds) {
            check(worker.failure == null) { worker.failure.toString() }
            record(label,(System.nanoTime()-start)/1e9)
            Thread.sleep(100)
        }
        record(label,seconds,true)
    }
    try {
        worker.setCloudsVisible(true)
        worker.updateControls(SimulationSpeed.ONE_HOUR,false)
        runStage("startup",3.0)
        worker.updateControls(SimulationSpeed.TEN_YEARS,false)
        val deadline = System.nanoTime()+60_000_000_000L
        while ((worker.latestSnapshot()?.progress?.completedSeconds ?: 0.0) < years*365*86400.0) {
            check(worker.failure == null) { worker.failure.toString() }
            check(System.nanoTime()<deadline) { "Fast climate did not reach $years years" }
            Thread.sleep(50)
        }
        record("fast",0.0,true)
        worker.updateControls(SimulationSpeed.ONE_HOUR,false,resetPending=true)
        runStage("returned",8.0)
        File(output,"trajectory.csv").writeText(rows.toString())
        val early = checkNotNull(returnedEarly)
        val late = checkNotNull(worker.latestSnapshot())
        check(late.fidelity == SimulationFidelity.DETAILED && late.weather.hasResolvedDynamics)
        check(early.drainage === late.drainage && early.surfaceWater === late.surfaceWater)
        var pressureChange = 0.0
        var curl = 0.0
        for (y in 0 until late.world.height step 4) for (x in 0 until late.world.width step 4) {
            pressureChange += abs(late.weather.pressureAnomalyPercent(x,y)!! - early.weather.pressureAnomalyPercent(x,y)!!)
            curl += abs(late.weather.relativeVorticityPerSecond(x,y)!!)
        }
        val first = checkNotNull(early.clouds); val last = checkNotNull(late.clouds)
        var changed = 0; var visible = 0
        for (y in 0 until last.height) for (x in 0 until last.width) {
            if (abs(last[x,y].a-first[x,y].a)>8) changed++
            if (last[x,y].a>24) visible++
        }
        check(pressureChange > 1 && curl > 1e-4) { "Returned atmosphere is stagnant" }
        check(visible > last.width*last.height/20 && changed > last.width*last.height/100) {
            "Returned clouds must remain visible and evolve"
        }
        println("HANDOFF_MOTION changed_cloud_pixels=$changed visible_cloud_pixels=$visible pressure_change=$pressureChange curl=$curl")
    } finally { worker.close() }
}
