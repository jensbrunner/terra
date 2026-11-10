package terra.experiments

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.JTabbedPane
import javax.swing.WindowConstants
import kotlin.concurrent.thread
import terra.atmosphere.DryAtmosphereConfig
import terra.atmosphere.DryAtmosphereSnapshot
import terra.atmosphere.DryScenario
import terra.atmosphere.DryScenarios
import terra.atmosphere.DryAtmosphere
import terra.atmosphere.MoistAtmosphere
import terra.atmosphere.MoistAtmosphereSnapshot

private enum class DryPlayback(val label: String, val secondsPerSecond: Double) {
    HOUR("1h/s", 3600.0), SIX_HOURS("6h/s", 21600.0), DAY("1 day/s", 86400.0);
    override fun toString() = label
}

private data class PreviewFrame(
    val snapshot: DryAtmosphereSnapshot,
    val initialVolume: Double,
    val pendingSeconds: Double = 0.0,
    val achievedSecondsPerSecond: Double = 0.0,
    val error: String? = null,
    val moisture: MoistAtmosphereSnapshot? = null
)

/** One owner for all integration state; Swing receives copied snapshots at 10 Hz. */
private class DryPreviewController(private val moist: Boolean) : AutoCloseable {
    val paused = AtomicBoolean(false)
    val playback = AtomicReference(DryPlayback.SIX_HOURS)
    val scenario = AtomicReference(DryScenario.JET)
    private val resetRequest = AtomicReference<DryScenario?>(null)
    @Volatile private var running = true
    private var wetModel: MoistAtmosphere? = if (moist) MoistAtmosphere() else null
    private var dryModel: DryAtmosphere? = if (moist) null else DryScenarios.create()
    private val elapsedSeconds: Double get() = wetModel?.elapsedSeconds ?: dryModel!!.elapsedSeconds
    private fun capture(): PreviewFrame {
        val wet = wetModel?.snapshot()
        val state = wet?.dynamics ?: dryModel!!.snapshot()
        return PreviewFrame(state, state.diagnostics.volumeCubicMeters, moisture = wet)
    }
    val latest = AtomicReference(capture())
    private val worker = thread(name = "terra-dry-atmosphere", isDaemon = true) { integrate() }

    fun reset(value: DryScenario = scenario.get()) {
        scenario.set(value)
        resetRequest.set(value)
    }

    private fun integrate() {
        var previous = System.nanoTime()
        var publishedAt = previous
        var publishedSimulatedTime = 0.0
        var initialVolume = latest.get().initialVolume
        var pending = 0.0
        var achieved = 0.0
        var error: String? = null
        while (running) {
            val now = System.nanoTime()
            val elapsed = (now - previous) / 1e9
            previous = now
            resetRequest.getAndSet(null)?.let { requested ->
                wetModel = if (moist) MoistAtmosphere(scenario = requested) else null
                dryModel = if (moist) null else DryScenarios.create(requested)
                initialVolume = capture().initialVolume
                pending = 0.0
                achieved = 0.0
                error = null
                publishedSimulatedTime = 0.0
                publishedAt = now - 100_000_000L
            }
            if (!paused.get()) pending += elapsed * playback.get().secondsPerSecond
            val workUntil = System.nanoTime() + 8_000_000L
            try {
                val stepSeconds = wetModel?.config?.stepSeconds ?: dryModel!!.config.stepSeconds
                while (!paused.get() && pending >= stepSeconds && System.nanoTime() < workUntil) {
                    if (wetModel != null) wetModel!!.step() else dryModel!!.step()
                    pending -= stepSeconds
                }
            } catch (failure: IllegalStateException) {
                error = failure.message
                paused.set(true)
            }
            val finished = System.nanoTime()
            if (finished - publishedAt >= 100_000_000L) {
                val rate = (elapsedSeconds - publishedSimulatedTime) / ((finished - publishedAt) / 1e9)
                achieved = 0.8 * achieved + 0.2 * rate
                latest.set(capture().copy(initialVolume = initialVolume, pendingSeconds = pending, achievedSecondsPerSecond = achieved, error = error))
                publishedAt = finished
                publishedSimulatedTime = elapsedSeconds
            }
            try { Thread.sleep(2) } catch (_: InterruptedException) { return }
        }
    }

    override fun close() { running = false; worker.interrupt() }
}

private class DryFieldPanel(private val field: DryField) : JPanel() {
    private var snapshot: DryAtmosphereSnapshot? = null
    private var image: java.awt.image.BufferedImage? = null
    var wind = true

    init { background = DryAtmosphereImages.background; preferredSize = Dimension(570, 520) }

    fun display(value: DryAtmosphereSnapshot, moisture: MoistAtmosphereSnapshot?) {
        snapshot = value
        image = DryAtmosphereImages.fieldImage(value, field, moisture)
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val state = snapshot ?: return
        val pixels = image ?: return
        val copy = graphics.create() as Graphics2D
        try { DryAtmosphereImages.paintPanel(copy, state, pixels, field, width, height, wind) }
        finally { copy.dispose() }
    }
}

/** Independent preview entrypoint; the existing game and its weather remain available. */
fun main() {
    SwingUtilities.invokeLater {
        val moist = System.getProperty("terra.moistPreview", "false").toBoolean()
        val controller = DryPreviewController(moist)
        val kind = if (moist) "Moist" else "Dry"
        val frame = JFrame("Terra · $kind atmosphere")
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.minimumSize = Dimension(1000, 690)
        val root = JPanel(BorderLayout(12, 12)).apply {
            background = DryAtmosphereImages.background
            border = BorderFactory.createEmptyBorder(18, 18, 18, 18)
        }
        val heading = JPanel(BorderLayout(0, 9)).apply { isOpaque = false }
        heading.add(JLabel("Terra / $kind atmosphere").apply {
            foreground = DryAtmosphereImages.foreground
            font = Font(Font.SANS_SERIF, Font.BOLD, 25)
        }, BorderLayout.NORTH)
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        for (scenario in listOf(DryScenario.JET, DryScenario.PULSE, DryScenario.VORTEX, DryScenario.REST)) {
            controls.add(JButton(scenario.label).apply { addActionListener { controller.reset(scenario) } })
        }
        val pause = JButton("Pause").apply {
            addActionListener {
                val value = !controller.paused.get()
                controller.paused.set(value)
                text = if (value) "Resume" else "Pause"
            }
        }
        controls.add(pause)
        controls.add(JButton("Reset").apply { addActionListener { controller.reset() } })
        controls.add(JComboBox(DryPlayback.entries.toTypedArray()).apply {
            selectedItem = DryPlayback.SIX_HOURS
            addActionListener { controller.playback.set(selectedItem as DryPlayback) }
        })
        val fields = if (moist) DryField.entries else listOf(DryField.HEIGHT, DryField.VORTICITY)
        val panels = fields.associateWith { DryFieldPanel(it) }
        controls.add(JCheckBox("Wind arrows", true).apply {
            isOpaque = false
            foreground = DryAtmosphereImages.foreground
            addActionListener { panels.values.forEach { it.wind = isSelected; it.repaint() } }
        })
        heading.add(controls, BorderLayout.SOUTH)
        root.add(heading, BorderLayout.NORTH)
        fun pair(first: DryField, second: DryField) = JPanel(GridLayout(1, 2, 12, 0)).apply {
            isOpaque = false; add(panels.getValue(first)); add(panels.getValue(second))
        }
        root.add(if (moist) JTabbedPane().apply {
            addTab("Clouds & rain", pair(DryField.CLOUDS, DryField.RAIN))
            addTab("Dynamics", pair(DryField.HEIGHT, DryField.VORTICITY))
            addTab("Temperature & humidity", pair(DryField.TEMPERATURE, DryField.HUMIDITY))
        } else pair(DryField.HEIGHT, DryField.VORTICITY), BorderLayout.CENTER)
        val footer = JPanel(GridLayout(3, 1, 0, 6)).apply { isOpaque = false }
        val status = JLabel().apply { foreground = DryAtmosphereImages.foreground }
        val description = JLabel(if (moist) "96 × 72 · thermal layer · finite ocean · radiation, evaporation and latent heat" else
            "96 × 72 · periodic plane · dry single layer · blue: lower height · orange: higher height").apply {
            foreground = Color(160, 185, 200)
        }
        val help = JLabel(if (moist) "Clouds and rain follow local moisture, temperature and circulation. Terrain coupling comes later." else
            "Jets develop over simulated days. Pressure pulse shows wave adjustment immediately. No terrain or moisture yet.").apply {
            foreground = Color(160, 185, 200)
        }
        footer.add(status); footer.add(description); footer.add(help)
        root.add(footer, BorderLayout.SOUTH)
        frame.contentPane = root
        var previousFrame: PreviewFrame? = null
        val timer = Timer(100) {
            val latest = controller.latest.get()
            if (latest !== previousFrame) {
                panels.values.forEach { it.display(latest.snapshot, latest.moisture) }
                val state = latest.snapshot
                val diagnostics = state.diagnostics
                status.foreground = if (latest.error == null) DryAtmosphereImages.foreground else Color(255, 149, 128)
                status.text = latest.error ?: "Day %.2f   |   wind ≤ %.1f m/s   |   CFL %.3f   |   volume drift %.2e   |   actual %.2f h/s   |   pending %.2f days".format(
                    state.elapsedSeconds / 86400, diagnostics.maximumSpeedMetersPerSecond, diagnostics.maximumCourant,
                    (diagnostics.volumeCubicMeters - latest.initialVolume) / latest.initialVolume,
                    latest.achievedSecondsPerSecond / 3600, latest.pendingSeconds / 86400
                )
                latest.moisture?.let { wet ->
                    description.text = "Vapor %.1f mm   |   cloud %.3f mm   |   accumulated rain %.1f mm   |   peak rain %.2f mm/h   |   water residual %.2e".format(
                        wet.diagnostics.meanVaporMm, wet.diagnostics.meanCloudMm, wet.diagnostics.meanRainTotalMm,
                        wet.diagnostics.maximumRainMmPerHour, wet.diagnostics.relativeWaterResidual)
                }
                if (latest.error != null) pause.text = "Resume"
                previousFrame = latest
            }
        }
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) { timer.stop(); controller.close() }
        })
        frame.setSize(1240, 760)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        timer.start()
        val closeAfter = System.getProperty("terra.atmospherePreview.seconds", "0").toDouble()
        if (closeAfter > 0) Timer((closeAfter * 1000).toInt()) { frame.dispose() }.apply {
            isRepeats = false
            start()
        }
    }
}
