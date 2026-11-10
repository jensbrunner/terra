package terra.experiments

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import terra.atmosphere.DryAtmosphereSnapshot
import terra.atmosphere.MoistAtmosphereSnapshot

internal enum class DryField(val title: String, val scale: Double, val units: String) {
    HEIGHT("Layer-height anomaly", 600.0, "m"),
    VORTICITY("Relative vorticity", 4e-5, "s⁻¹"),
    CLOUDS("Cloud liquid water", 0.5, "mm"),
    RAIN("Precipitation", 0.25, "mm/h"),
    TEMPERATURE("Air temperature", 20.0, "°C"),
    HUMIDITY("Relative humidity", 100.0, "%");

    val signed: Boolean get() = this == HEIGHT || this == VORTICITY || this == TEMPERATURE
}

/** Shared by the live preview and exported frames; visual detail never modifies physical state. */
internal object DryAtmosphereImages {
    val background = Color(16, 25, 34)
    val foreground = Color(226, 235, 240)
    private val panel = Color(24, 37, 49)
    private val outlineStroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    private val windStroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    private val outlineColor = Color(8, 24, 38, 180)
    private val windColor = Color(250, 253, 255, 230)
    private val palette = IntArray(513) { i ->
        val low = if (i <= 256) Color(29, 103, 172) else Color(224, 233, 231)
        val high = if (i <= 256) Color(224, 233, 231) else Color(214, 111, 44)
        val fraction = if (i <= 256) i / 256.0 else (i - 256) / 256.0
        Color(
            (low.red + fraction * (high.red - low.red)).roundToInt(),
            (low.green + fraction * (high.green - low.green)).roundToInt(),
            (low.blue + fraction * (high.blue - low.blue)).roundToInt()
        ).rgb
    }
    private val cloudPalette = IntArray(513) { i ->
        blend(Color(17, 32, 49), Color(245, 246, 241), i / 512.0)
    }
    private val rainPalette = IntArray(513) { i ->
        val amount = sqrt(i / 512.0)
        if (amount < 0.5) blend(Color(17, 28, 43), Color(65, 183, 178), amount * 2)
        else blend(Color(65, 183, 178), Color(246, 186, 81), (amount - 0.5) * 2)
    }

    private fun blend(a: Color, b: Color, fraction: Double): Int = Color(
        (a.red + (b.red - a.red) * fraction).roundToInt(),
        (a.green + (b.green - a.green) * fraction).roundToInt(),
        (a.blue + (b.blue - a.blue) * fraction).roundToInt()
    ).rgb

    private fun fieldColor(value: Double, field: DryField): Int {
        val normalized = if (field.signed) value / field.scale * 0.5 + 0.5 else value / field.scale
        val index = (normalized * 512).roundToInt().coerceIn(0, 512)
        return when (field) {
            DryField.CLOUDS -> cloudPalette[index]
            DryField.RAIN -> rainPalette[index]
            else -> palette[index]
        }
    }

    fun fieldImage(snapshot: DryAtmosphereSnapshot, field: DryField, moisture: MoistAtmosphereSnapshot? = null): BufferedImage {
        val config = snapshot.config
        val image = BufferedImage(config.width, config.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until config.height) for (x in 0 until config.width) {
            val value = when (field) {
                DryField.HEIGHT -> snapshot.heightAt(x, y) - config.meanHeightMeters
                DryField.VORTICITY -> snapshot.vorticityAt(x, y)
                DryField.CLOUDS -> moisture?.cloudWaterMmAt(x, y) ?: 0.0
                DryField.RAIN -> moisture?.rainMmPerHourAt(x, y) ?: 0.0
                DryField.TEMPERATURE -> (moisture?.temperatureKelvinAt(x, y) ?: 273.15) - 273.15
                DryField.HUMIDITY -> (moisture?.relativeHumidityAt(x, y) ?: 0.0) * 100
            }
            image.setRGB(x, config.height - 1 - y, fieldColor(value, field))
        }
        return image
    }

    fun paintPanel(
        graphics: Graphics2D, snapshot: DryAtmosphereSnapshot, image: BufferedImage,
        field: DryField, width: Int, height: Int, wind: Boolean
    ) {
        graphics.color = panel
        graphics.fillRect(0, 0, width, height)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        graphics.color = foreground
        graphics.drawString(field.title, 16, 27)
        val aspect = snapshot.config.domainWidthMeters / snapshot.config.domainHeightMeters
        val plotWidth = min(width - 32, ((height - 110) * aspect).toInt()).coerceAtLeast(1)
        val plotHeight = (plotWidth / aspect).toInt().coerceAtLeast(1)
        val left = (width - plotWidth) / 2
        val top = 48
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(image, left, top, plotWidth, plotHeight, null)
        if (wind) drawWind(graphics, snapshot, left, top, plotWidth, plotHeight)
        val legendY = top + plotHeight + 18
        val legendWidth = min(240, plotWidth)
        for (x in 0 until legendWidth) {
            val fraction = x.toDouble() / (legendWidth - 1).coerceAtLeast(1)
            graphics.color = Color(fieldColor((if (field.signed) 2 * fraction - 1 else fraction) * field.scale, field))
            graphics.drawLine(left + x, legendY, left + x, legendY + 6)
        }
        graphics.color = foreground
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val magnitude = when (field) {
            DryField.HEIGHT -> "600"
            DryField.VORTICITY -> "4 × 10⁻⁵"
            DryField.TEMPERATURE -> "20"
            else -> field.scale.toString()
        }
        graphics.drawString(if (field.signed) "−$magnitude" else "0", left, legendY + 24)
        graphics.drawString(if (field.signed) "0" else (field.scale / 2).toString(), left + legendWidth / 2 - 3, legendY + 24)
        graphics.drawString("$magnitude ${field.units}", left + legendWidth - 30, legendY + 24)
    }

    private fun drawWind(g: Graphics2D, snapshot: DryAtmosphereSnapshot, left: Int, top: Int, width: Int, height: Int) {
        val oldClip = g.clip
        g.clipRect(left, top, width, height)
        val config = snapshot.config
        for (y in 3 until config.height step 6) for (x in 3 until config.width step 6) {
            val u = snapshot.eastWindAt(x, y)
            val v = snapshot.northWindAt(x, y)
            val speed = hypot(u, v)
            if (speed < 0.05) continue
            val px = left + (x + 0.5) / config.width * width
            val py = top + (config.height - y - 0.5) / config.height * height
            val length = min(22.0, 4 + speed * 0.45)
            val dx = u / speed
            val dy = -v / speed
            val endX = px + length * dx
            val endY = py + length * dy
            for (outline in 0..1) {
                g.stroke = if (outline == 0) outlineStroke else windStroke
                g.color = if (outline == 0) outlineColor else windColor
                g.drawLine(px.roundToInt(), py.roundToInt(), endX.roundToInt(), endY.roundToInt())
                for (sideIndex in 0..1) {
                    val side = sideIndex * 2 - 1
                    val hx = endX - 4 * dx + side * 2.7 * dy
                    val hy = endY - 4 * dy - side * 2.7 * dx
                    g.drawLine(endX.roundToInt(), endY.roundToInt(), hx.roundToInt(), hy.roundToInt())
                }
            }
        }
        g.clip = oldClip
    }

    fun frame(snapshot: DryAtmosphereSnapshot, moisture: MoistAtmosphereSnapshot? = null): BufferedImage {
        val image = BufferedImage(1200, if (moisture == null) 600 else 1140, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = background
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = foreground
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
            graphics.drawString("Terra · ${if (moisture == null) "dry" else "moist"} rotating atmosphere · day %.2f".format(snapshot.elapsedSeconds / 86400), 20, 30)
            val fields = if (moisture == null) listOf(DryField.HEIGHT, DryField.VORTICITY)
                else listOf(DryField.CLOUDS, DryField.RAIN, DryField.HEIGHT, DryField.TEMPERATURE)
            for ((index, field) in fields.withIndex()) {
                val panelGraphics = graphics.create(12 + (index % 2) * 594, 52 + (index / 2) * 540, 582, 530) as Graphics2D
                try { paintPanel(panelGraphics, snapshot, fieldImage(snapshot, field, moisture), field, 582, 530, true) }
                finally { panelGraphics.dispose() }
            }
        } finally { graphics.dispose() }
        return image
    }
}
