package terra.atmosphere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class DryScenario(val label: String) {
    JET("Perturbed jet"), PULSE("Pressure pulse"), VORTEX("Geostrophic vortex"), REST("Rest")
}

object DryScenarios {
    fun create(
        scenario: DryScenario = DryScenario.JET,
        config: DryAtmosphereConfig = DryAtmosphereConfig(),
        seed: Int = 31742,
        perturbed: Boolean = true
    ): DryAtmosphere = DryAtmosphere(config, initializer(scenario, config, seed, perturbed))

    internal fun initializer(
        scenario: DryScenario, config: DryAtmosphereConfig, seed: Int, perturbed: Boolean = true
    ): (Double, Double) -> DryCell {
        val random = Random(seed)
        val phases = DoubleArray(3) { random.nextDouble() * 2 * PI }
        val amplitudes = doubleArrayOf(0.08, 0.14, 0.4)
        val kx = 2 * PI / config.domainWidthMeters
        val ky = 2 * PI / config.domainHeightMeters
        val radius = 800_000.0
        require(scenario != DryScenario.VORTEX || abs(config.coriolisPerSecond) > 1e-12) {
            "A geostrophic vortex requires nonzero Coriolis"
        }
        return { x, y ->
            when (scenario) {
                DryScenario.REST -> DryCell(config.meanHeightMeters, 0.0, 0.0)
                DryScenario.JET -> {
                    // Opposed Bickley jets. Periodic images close the seam; dh/dy = -f u/g.
                    val jetWidth = config.domainHeightMeters / 15.0
                    var heightIntegral = 0.0
                    var eastWind = 0.0
                    var northWind = 0.0
                    for (image in -1..1) {
                        val first = (y - config.domainHeightMeters * 0.25 + image * config.domainHeightMeters) / jetWidth
                        val second = (y - config.domainHeightMeters * 0.75 + image * config.domainHeightMeters) / jetWidth
                        heightIntegral += tanh(first) - tanh(second)
                        val firstCosh = cosh(first)
                        val secondCosh = cosh(second)
                        eastWind += 40.0 * (1 / (firstCosh * firstCosh) - 1 / (secondCosh * secondCosh))
                    }
                    val height = config.meanHeightMeters - config.coriolisPerSecond * 40.0 * jetWidth /
                        config.gravity * (heightIntegral - 1.0)
                    // Small, one-time divergence-free disturbances; no prescribed wind thereafter.
                    if (perturbed) for (mode in 0..2) {
                        val wave = kx * (mode + 1)
                        eastWind -= amplitudes[mode] * ky / wave * sin(wave * x + phases[mode]) * cos(ky * y)
                        northWind += amplitudes[mode] * cos(wave * x + phases[mode]) * sin(ky * y)
                    }
                    DryCell(height, eastWind, northWind)
                }
                DryScenario.PULSE, DryScenario.VORTEX -> {
                    val dx = x - config.domainWidthMeters * 0.5
                    val dy = y - config.domainHeightMeters * 0.5
                    val bell = exp(-(dx * dx + dy * dy) / (2 * radius * radius))
                    if (scenario == DryScenario.PULSE) {
                        DryCell(config.meanHeightMeters + 150 * bell, 0.0, 0.0)
                    } else {
                        val gradient = 150 * bell / (radius * radius)
                        DryCell(config.meanHeightMeters - 150 * bell,
                            -config.gravity / config.coriolisPerSecond * gradient * dy,
                            config.gravity / config.coriolisPerSecond * gradient * dx)
                    }
                }
            }
        }
    }
}
