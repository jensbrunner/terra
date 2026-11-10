# Proposed first experiment: Is economical rotating weather viable?

Status: The user subsequently requested implementation-first work with much less
testing, benchmarking and gating, then explicitly authorized the dry core and
preview. That instruction supersedes the sequencing/approval requirements below.
The dry single-layer solver and interactive 2D preview are implemented; see
[DRY_ATMOSPHERE.md](docs/DRY_ATMOSPHERE.md). Compilation and focused numerical
checks replace the earlier extensive prototype acceptance exercise.

The next authorized implementation, the standalone moist thermal extension, is
also implemented: mass-weighted temperature/vapor/cloud transport, radiation,
surface heat/water exchange, latent phase changes, precipitation and preview tabs.
See [MOIST_ATMOSPHERE.md](docs/MOIST_ATMOSPHERE.md). The historical gates below are
reference material, not a requirement to pause further implementation.

The moist model is now also available in the main terrain game with
`gradle runJvm -PweatherModel=dynamical` or the M mode switch. It has conservative
coarse/fine surface exchange, land/ocean thermal differences, soil/rain/runoff/lake
coupling, live climate sampling and bounded per-frame advancement. Mountain
forcing, extra vertical structure and coarse years/s climate advancement remain
implementation work. See the main-game section of the moist documentation.

Gate A's 18 live trials, headless capacity and schedule-sensitivity evidence remain
recorded. Its performance targets fail at high speeds; observer-overhead regression
and full physical ledgers remain open, without blocking implementation. See
[BASELINE_RESULTS.md](docs/experiments/BASELINE_RESULTS.md) for commands, evidence
and gate limitations.

Basis: [ADR_CLIMATE.md](ADR_CLIMATE.md), revised VISION, local `41138a5`,
10 November 2026. Reinspect source and local edits before each stage.

## Scope and decisions

Start with a current-game baseline, then a small external reference experiment.
Build a custom dry solver only if that comparison still justifies owning one.
Each numbered gate below is a separate decision; approval of the first stage does
not automatically authorize the other stages, moisture, a second layer or gameplay
integration. The preferred first working dynamics prototype is deliberately dry.

User clarification during Gate A: future high speeds may deliberately coarsen
physics and prioritize long-term evolution, targeting years/s and roughly
10–100 years/s. Treat this as an accepted product direction for a labeled climate
mode, with independently validated fluxes, surface responses and mode transitions.
Do not require detailed storm trajectories to match between resolutions or modes.
This does not expand the current baseline phase into implementing fast-forward.

Tests and benchmarks are part of this scientific program. The prohibitions in the
historical PLAN.md and HYDRO_UPDATE.md concern their earlier tasks and do not carry
over. Preserve their deletion states and completed hydrology work. Do not resume
their outstanding tasks or alter README, VISION, terrain settings, art or biomes.

## Gate A — Measure the existing game

Proposed files:

- New `src/jvmMain/kotlin/terra/experiments/BaselineExperiment.kt`: construct
  existing World/Weather/Hydrology without a window, advance the existing bootstrap
  with its per-step handoff, and replay explicit outer-call schedules. Do not
  modify the solver to make the replay faster or more deterministic.
- New `src/jvmMain/kotlin/terra/experiments/ExperimentMetrics.kt`: JVM CPU, allocation,
  GC, memory and monotonic timing; compact result records and CSV output.
- New `src/jvmMain/kotlin/terra/experiments/DesktopBaselineExperiment.kt` and small
  `src/jvmMain/kotlin/terra/DesktopMain.kt` wiring: JVM implementation of the
  shared scene's optional observer, preset automation and bounded frame/schedule
  recording. Normal launches keep a null observer.
- `src/commonMain/kotlin/terra/ui/AtlasScene.kt`: opt-in timing of weather,
  hydrology, overlays, completed simulated time and actual frame intervals;
  opt-in preset sequencing after bootstrap. Preserve the default update order,
  pause behavior, speed multipliers and numerical calls. Sample into bounded
  buffers and export outside measured sections.
- `build.gradle.kts`: separate `baselineExperiment` JavaExec task on the current
  JVM runtime; leave `runJvm` and dependencies unchanged.
- New `src/jvmTest/kotlin/terra/experiments/BaselineRepeatabilityTest.kt` and JVM
  Kotlin-test wiring: compare two independent same-seed runs under identical
  schedules using existing public fields. Describe this as observable-state
  repeatability, not proof that hidden state or arbitrary frame schedules match.
- New `docs/experiments/BASELINE_RESULTS.md`: commands, environment, raw-output
  locations, measurements and correctness gaps. Update this plan's gate status.

Use the existing default seed 31742, 283 × 212 world, fixed viewport and cloud
overlay. Record machine CPU/model, RAM, OS/power mode, JDK, GC/heap flags, revision
and dirty diff. Keep world/topology creation, 210-day bootstrap and steady operation
as separate timings. After bootstrap, use ≥15 seconds warmup and three ≥30-second
wall trials at each of Realtime, 1h/s, 12h/s, 1 day/s, 1 week/s and 1 month/s.

Record actual simulated days/s; weather/hydrology/overlay CPU or wall durations;
frame p50/p95/max; allocated bytes/s; GC; peak process/heap memory; offered,
completed and pending simulation time. Headless schedule replay measures compute
cost and sensitivity, not live frame rates. A synchronous update that overruns a
trial must remain in its timing record. Do not silently skip time or shorten a
slow run. Mark unsupported metrics explicitly.

Repeat identical schedules, and compare equal-duration three-hour calls against
the actual high-speed batches. Measure differences in rain and stores rather than
requiring the existing model to be frame independent. Diagnose lifted-cloud
condensation/evaporation ordering from the source; do not fix it in this stage.

Acceptance: six live preset measurements and a reproducible headless command;
same-schedule public-state checks pass; timing disabled preserves default behavior;
measured instrumentation overhead is ≤5% in throughput and frame p95, with timing
disabled ≤2% (report uncertainty if noise exceeds the budget). Report baseline
failures of the proposed p95 ≤33.3 ms / maximum ≤100 ms responsiveness targets.
Missing display or measurement access leaves that evidence open.

The complete water ledger in [PHASE_0A_PLAN.md](PHASE_0A_PLAN.md) remains proposed
before wet replacement. Gate A reports its absence, clipping and schedule
sensitivity as correctness gaps. Air-mass and physical energy budgets are
unavailable in the existing model; do not manufacture them. This limited baseline
must not be described as complete physical validation.

Estimated complexity: 1–2 engineering days, depending on measurement access.
Stop with the baseline and choose the next action using its evidence.

## Gate B — Run SpeedyWeather as a reference before a custom core

Proposed files, isolated from Terra's runtime and Gradle dependencies:

- New `experiments/speedyweather/Project.toml` and generated pinned `Manifest.toml`.
- New `experiments/speedyweather/reference.jl`: CPU hierarchy/resolution trials,
  repeatability checkpoints, numerical summaries and bounded output samples.
- New `experiments/speedyweather/README.md`: exact Julia/package setup, commands,
  enabled physics, units and startup/steady-state distinctions.
- New `docs/experiments/SPEEDYWEATHER_RESULTS.md`: results and integration decision.

Use one pinned release or commit and its matching documentation, one Julia/BLAS
thread initially, explicit precision, seed and initial conditions. Record actual
physical grid points, rings, truncation and timesteps; the published T31/T42/T63
cases are useful starting comparisons, not identical 96 × 72/128 × 96 meshes.
Match a spherical jet/wave case by physical scale and dimensionless parameters,
and document why it is not identical to the later periodic f-plane experiment.

Measure dry SW and a low-resolution wet primitive configuration. Separate package
installation, first compilation, initialization, steady integration and snapshots.
After warmup, run three 30-second throughput trials with output disabled, then
repeat with copied snapshots at 10 Hz wall cadence while advancing the same fixed
physical steps. Record bytes/s and latency. Do not retain all snapshots in RAM.
Compare repeated checksums on the pinned CPU setup. Inspect dynamic cloud fields,
precipitation units and surface-water ownership before recommending an adapter.

Require at least a 30-simulated-day dry run with bounded state and reported
mass/energy changes. If the wet engine remains an adoption candidate, separately
measure ≥3 simulated years, reporting annual means, drift and water budgets; a
short speed test cannot close that stability question. Native-grid and remapped
rainfall must later agree within `1e-10 × max(1 kg, source water mass)` per interval
in Float64 exchange calculations. No hydrology coupling is implemented here.

Acceptance: reproducible reference command, finite and bounded dry run, honest
budget errors, sample field sequence and measured startup/output cost. Rank an
external process against custom Kotlin on solver effort, packaging, geography,
surface ownership and required fields. If the engine cannot expose a needed
quantity, record the gap rather than invent a cloud or pressure diagnostic.

Julia is not currently on PATH. Do not claim a proof of concept ran. Acquire a
runtime in an isolated experiment setup as part of this separately approved stage;
do not make Julia a game installation requirement. Metal/native embedding are
additional experiments only if CPU/process evidence warrants them.

Estimated complexity: 1–2 days for the dry comparison, plus any multi-year wet
analysis. Stop before implementing a custom solver or production bridge.

## Gate C — Small custom dry prototype, if still justified

Proposed files, all independent of gameplay:

- New `src/jvmMain/kotlin/terra/experiments/dynamics/DryShallowWater.kt`: flat
  primitive-array state, periodic fluxes and rotation; no solver framework.
- New `src/jvmMain/kotlin/terra/experiments/dynamics/DryScenarios.kt`: deterministic
  rest, wave, balanced jet, vortex and perturbed-shear initial conditions.
- New `src/jvmMain/kotlin/terra/experiments/DryExperimentMain.kt`: fixed-step CLI,
  invariant checks and resolution/timestep comparisons, reusing metrics from A.
- New `src/jvmMain/kotlin/terra/experiments/ExperimentImages.kt`: sampled top-down
  height-anomaly/vorticity/wind PNG frames and a small animated preview with fixed
  scales, simulation timestamps and clear dry-model labels. Use JVM ImageIO.
- New `src/jvmTest/kotlin/terra/experiments/DryShallowWaterTest.kt`: the analytic
  and numerical checks below; no copies of the solver equations as expected values.
- `build.gradle.kts`: independent `dryAtmosphereExperiment` entrypoint.
- New `docs/experiments/DRY_DYNAMICS_RESULTS.md`: numerical/visual/performance
  comparison and next decision. Update this plan's gate status.

Start with physical Cartesian east/north coordinates on a doubly periodic
12,000 × 9,000 km f-plane. Use 96 × 72 and 128 × 96; define `g = 9.81 m/s²`, mean
`H = 10,000 m`, and `f = ±1e-4 s⁻¹` for separate hemisphere tests. These describe
an equivalent-fluid numerical laboratory, not Terra's planetary atmosphere.

State is `h, hu, hv`. For flat bottom, use conservative shallow-water momentum
and height fluxes, with equal/opposite transfers at each shared face. The Coriolis
source is `(0, f hv, −f hu)`. A limited-linear MUSCL reconstruction with Rusanov
fluxes, SSPRK2 flux integration and half-step exact momentum rotations is a compact
starting discretization. Limit reconstructed heights without adding water. A
first-order debugging mode may verify transfers, but cannot be the sole basis for
rejecting instability: its diffusion can erase the behavior under investigation.
The [Clawpack shallow-water reference](https://www.clawpack.org/riemann_book/html/Shallow_water.html)
provides the conservative variables and characteristic wave speeds.

Test fixed 10/20/40 s steps and calculate the actual maximum face-based wave CFL
at every stage; reject runs exceeding the method's chosen conservative 0.4 bound.
Do not secretly clamp height, reduce gravity, inject vortices or skip steps. If
the bound forces a smaller step, rerun with a stated fixed step. Report numerical
energy dissipation and potential-vorticity/enstrophy behavior; do not claim exact
energy conservation from mass-conservative fluxes.

For a reproducible balanced shear control, use a periodic sinusoidal eastward
jet with the matching integrated height gradient `g dh/dy = −f u`. Seed the
perturbed run once at initialization with a small, zero-mean deterministic
disturbance. Compare multiple permitted along-jet wavelengths and perturbation
amplitudes against the unperturbed control. Do not continuously force a wave phase
or insert a cyclone. A lack of growth needs a numerical-diffusion and physical
stability assessment; it is not proof that shallow water cannot create storms.

Proposed acceptance targets:

| Check | Required evidence |
|---|---|
| Rest / finite state | Uniform flat fluid stays at rest: relative height error ≤1e-12 and speed ≤1e-10 m/s over 1,000 steps. Every scenario stays finite with strictly positive h and zero state clamps. |
| Volume / model mass | `Σ A h` relative error ≤1e-10 over a 30-day run and periodic seam crossings. Call it equivalent-fluid volume, not conserved atmospheric dry-air mass. |
| Rotation | Uniform inertial motion reverses with sign of f; amplitude and phase errors ≤1e-10 over ten periods for the rotation-only case. |
| Pressure gradient | A small smooth positive height anomaly accelerates surrounding fluid outward before rotation organizes it. Check acceleration direction against its known initial gradient. |
| Wave | Small-amplitude periodic wave speed within 5% of the linear rotating-wave dispersion relation; retain ≥75% amplitude for one period. Halving spatial spacing with proportionally reduced timestep reduces error by ≥20%. |
| Geostrophic balance | Balanced periodic jet changes by ≤5% in relative velocity/height-anomaly L2 norm over one day. Report extrema/limiter effects and refinement behavior. |
| Energy | Monitor `Σ A [h(u²+v²)/2 + g(h−H)²/2]` per unit density. No unexplained increase >1e-6 relative to initial energy in unforced runs; report dissipation and vortex/jet decay, rather than hiding losses under background potential energy. |
| Repeatability | Identical state hashes at every checkpoint for the same build, seed and fixed timestep; changing snapshot cadence does not alter hashes. Compare timestep refinements as accuracy tests, not equal-output promises. |
| Visual emergence | At least one perturbed shear experiment shows sustained disturbance growth above the unperturbed/numerical-noise control and moving coherent vortical structures, followed by reported deformation/dissipation. Use side-by-side fixed-scale frame sequences; initialized vortices alone do not satisfy spontaneous growth. |
| Cost | Three ≥30-second warmed trials at both grids; measured days/s, stage time, allocation and memory, with/without snapshots. Target ≥30 simulated days/s dry integration; report lower achieved rates explicitly. No per-cell allocations or unexplained >5% post-GC live-heap growth in a ten-minute run. |

Treat thresholds as proposed experiment gates, not existing capabilities. Record
failures and stop for a decision; do not quietly weaken criteria or expand into a
new implicit/high-order solver. Compare C-grid balance preservation or a
semi-implicit approach only if evidence identifies the limitation. A pure f-plane
experiment is not a pass of Williamson's spherical suite or evidence of tropical
and seasonal behavior.

Estimated complexity: 2–4 days including verification and preview artifacts.
No moisture, terrain forcing, second layer, pressure in hPa, subgrid cloud noise,
thread migration or WeatherSimulation adapter belongs in this stage.

## Validation and rollback

During implementation use `gradle compileKotlinJvm`, `gradle jvmTest` and the
stage-specific runner; Gate A also requires actual `gradle runJvm` measurements.
Follow AGENTS.md for Gradle's native-library sandbox failure. The assessment's
baseline compilation already succeeded; it validates build health only.

Keep experiment output separate from production state. Remove only each stage's
new experiment files/build entries and its opt-in UI hook to roll it back; retain
measurement evidence. Existing WeatherSimulation, terrain, hydrology and public
interfaces remain the fallback throughout. No commits, runtime installation or
deployment are part of this assessment.

The next report must name the gate, actual commands, empirical evidence, numerical
and visual failures, cost versus baseline and the next proposed decision. Neither
a compelling animation nor a successful compilation closes all gates.
