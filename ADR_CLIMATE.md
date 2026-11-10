# ADR: Test economical rotating dynamics before choosing Terra's atmosphere

Status: Proposed; authorizes no solver implementation.

Reviewed against the revised VISION: 10 November 2026.
Local HEAD: `41138a59b70bcdc3f552bb6032b6745718e4ad20`.
Scope: first-invocation assessment under [VISION.md §18](VISION.md), followed by
[ATMOSPHERE_EXPERIMENT_PLAN.md](ATMOSPHERE_EXPERIMENT_PLAN.md). This revision
replaces the earlier recommendation favoring a small primitive-equation model.

## Decision proposed

Keep existing gameplay authoritative. The next experiment should ask whether a
small dry, single-layer rotating solver produces coherent evolving disturbances
cheaply enough at **96 × 72 and 128 × 96**, with a SpeedyWeather reference run
before committing to a custom production solver. Measure the current game first.
Do not make an exhaustive instrumentation framework a prerequisite for a small
standalone experiment.

**Moist two-layer shallow water is a credible leading hypothesis, not the selected
production model.** It has more relevant evidence for compact moist cyclone
dynamics than the previous assessment allowed. However, single-layer dynamics
may supply enough visible structure, and an existing semi-implicit solver may
beat a custom explicit implementation on total effort. A primitive-equation model
is an escalation option if simpler models demonstrably fail the product goals.

The first dry prototype can establish pressure-like adjustment, rotation, waves
and shear instability. It cannot establish thermally driven cyclones, fronts,
rainfall, hurricanes or global climate. Keep all presentation top-down and 2D;
additional hidden layers must earn their computational and maintenance cost.

### Product clarification: years per second

The user explicitly clarified during Gate A (10 November 2026) that higher speeds
may coarsen the simulation, with eventual targets of years/s and roughly
10–100 years/s. At those speeds, overall climate and planetary evolution take
priority over visible weather details. This authorizes the architectural use of
coarser approximate climate advancement; it is not a request to preserve individual
storm trajectories through every speed change.

Plan for two distinct advance contracts: detailed weather at slow speeds and
clearly identified climate evolution at high speeds. The latter may use fewer
cells, averaged or parameterized fast processes, larger justified steps and slower
surface/visual updates. Reducing resolution alone does not establish an annual
throughput target. Validate sustained long-term fluxes and surface responses;
do not merely run the current storm solver on larger timesteps.

Climate mode need not reproduce the detailed model's exact weather sequence.
Its coarse-cell precipitation, reservoir exchange and model energy accounting
must remain internally consistent. Physical transfers between resolutions and
mode transitions need explicit accounting and conservative remapping; do not
convert the same water twice or rebuild surface stores from arbitrary averages.
Numerical determinism is conditional on the chosen mode, schedule and resolution.

For scale, at 100 years/s a daily climate step would require 36,500 updates per
real second, a 30-day step about 1,217, or an annual step 100. These are arithmetic
counts, not selected timesteps or performance claims. The annual budget approach,
surface/soil/lake responses and seasonal averaging will need their own experiments.
The present Gate A measures the existing weather model; it does not implement
these modes or reject them because detailed dynamics cannot reach the target.

## Repository evidence and changes since VISION's baseline

The earlier vision assessment used `1abd91e`. The subsequent commit `41138a5` changes hydrology
topology, hydrology accessors/documentation, water rendering, and planning files.
It does not change weather physics. At task entry the working tree had an
untracked `VISION.md` and a local deletion of `HYDRO_UPDATE.md`; preserve both states.

| Finding | Current evidence | Consequence |
|---|---|---|
| Flat arrays and conservative face exchanges already exist | [WeatherSimulation.kt](src/commonMain/kotlin/terra/weather/WeatherSimulation.kt), `transportAirMass`, `buildFaceWindField`, `transportTracer` | Retain these principles. Do not reimplement the old forward-scatter correction. |
| Transport positivity is partly hidden by clipping | `transportTracer` clamps every negative destination, including values below its roundoff threshold | Record correction amounts and pre-clamp minima before claiming conservation or positivity. |
| Wind is prescribed; air mass and pressure are absent | `updateWindField`, latitude belts and two traveling waves; transported fields are vapor, cloud water and temperature | `transportAirMass` is a misleading name for tracer transport. No air-mass conservation test can yet be passed. |
| Cloud cancellation remains | `advanceSubstep` condenses using `effectiveLiftedTemperatureC`, then evaporates using the warmer `saturationCapacityMm` | Water can conserve while the intended cloud development is undone. Reconcile the represented thermodynamic environment before tuning rain. |
| Evaporation remains daylight gated | `MAX_EVAPORATION_MM_PER_DAY * solarInputFactor * warmth * availableWater` | No nighttime flux or humidity deficit. Its continuous-time ideal daily ceiling is 6/π ≈ 1.91 mm/day; finite-step sampling may differ. |
| Rain memory remains on the ground | `rainActive: BooleanArray`, omitted from transport | Historical atmospheric plan step 5 is still outstanding. |
| Thermal state is relaxed, not energy balanced | `updateThermalState`, `updateAirTemperature`; no heat capacities or latent-heat coupling | Report energy budget as unavailable. Summing temperatures is not an energy ledger. |
| Update batching affects physics | `step(seconds)` chooses `ceil(seconds / 10800)` equal substeps; [AtlasScene.kt](src/commonMain/kotlin/terra/ui/AtlasScene.kt) submits accumulated render time at a nominal 0.05 s cadence | Equal total time with unequal call schedules need not reproduce the same state. Only identical schedules currently have a defensible repeatability contract. |
| Hydrology coupling also depends on the outer call | [HydrologySimulation.kt](src/commonMain/kotlin/terra/hydrology/HydrologySimulation.kt), `acceptWeatherStep`, routes the entire pending interval once it reaches one day | Bootstrap hands off every three hours; interactive play hands off once per outer update, possibly several days at high speed. Measure this difference before promising exact speed equivalence. |
| Geometry is inconsistent across subsystems | Atmosphere wraps X and closes Y; [HydrologyTopology.kt](src/commonMain/kotlin/terra/hydrology/HydrologyTopology.kt) drains at ocean and all four perimeter edges | This is a rectangular game model, not yet a coherent sphere. Ledger perimeter export separately; defer topology changes. |
| Climate initialization is short and seasonless | [ClimateBootstrap.kt](src/commonMain/kotlin/terra/weather/ClimateBootstrap.kt): 30-day equilibration, 180-day sample | These windows do not establish annual climate convergence. Keep observed and annualized quantities distinct. |
| Original UI has limited timing diagnostics | Default frame counter is an exponential average; weather runs inside the render updater; overlays request 20 Hz | Gate A subsequently adds opt-in wall-frame and phase timing; the default game still uses its existing counter. |

The source inventory was revisited, including topology, rendering, settings,
biome classification and build wiring. The initial architecture assessment ran
compilation only. Gate A has subsequently implemented the baseline runner and
collected [local measurements and repeatability checks](docs/experiments/BASELINE_RESULTS.md):
18 live trials, headless capacity, schedule sensitivity and captured-schedule
replays. Weather/hydrology equations remain unchanged. Source findings, local
measurements, published benchmarks and arithmetic estimates are distinct evidence.

## Reconciliation with earlier plans

`PLAN.md` was deleted in `41138a5`. Its last version at `1abd91e` records transport,
rainfall diagnostics, convective lift and orographic lift as completed; the source
contains those changes. Its remaining rain-memory, evaporation and display work
must not be mistaken for completed work. The historical plan forbids tests,
benchmarks and pressure dynamics and requires one step per invocation.

The committed `HYDRO_UPDATE.md` records required steps 1–5 complete; optional
topology optimization still requires a separate decision. It forbids tests and
weather changes. It is currently deleted locally; do not restore it or execute its
optional step as part of this program.

The updated VISION explicitly calls for numerical tests and benchmarks in this
new scientific program. The old prohibitions remain scoped to their old tasks;
they do not prohibit the experiments proposed here. Approval of a bounded phase
would authorize its listed checks, not resume the old optional tasks. The earlier
[Phase 0a document](PHASE_0A_PLAN.md) is retained as a ledger checklist with
superseded sequencing; the new experiment plan controls the proposed next work.

## Alternatives

| Candidate | Minimum prognostic state | Unscripted visible behavior and lifecycle | Moisture, tropics and limitations |
|---|---|---|---|
| Existing kinematic model | Vapor, cloud, air/surface temperature, soil water, local rain flag; wind is prescribed | Moving moisture and local cloud/rain changes; no dynamically generated pressure systems | Local condensation does not affect circulation. No credible cyclone mechanism in either latitude range. Cheapest integrated fallback. |
| Single-layer rotating shallow water | `h, hu, hv`, or equivalent vorticity/divergence/height | Pressure-height adjustment, waves, vortices, deformation and potentially unstable jets; decay from dissipation | No moisture unless added; no vertical thermal shear. Full nonlinear equations can cross the equator with suitable geometry, but do not resolve tropical convection or hurricanes. |
| Dry two-layer shallow water | `h1, h1u1, h1v1, h2, h2u2, h2v2`, with specified stratification | Shear interaction and potential-energy release can add growing cyclonic disturbances | Thermal interpretation comes from the layer derivation; adding arbitrary temperature tracers does not create baroclinicity. No rainfall. Equatorial behavior needs separate tests. |
| Moist two-layer shallow water | Dry state plus lower-layer water tracer; persistent cloud water needs an additional explicit reservoir | Potential moist amplification and organized cyclone precipitation | Promising midlatitude evidence; latent coupling must influence mass/pressure consistently. No established realistic tropical-storm or seasonal-climate capability for Terra. |
| Two-layer quasi-geostrophic | Two potential-vorticity fields, plus moisture if used; diagnostic inversion for flow | Efficient balanced midlatitude eddies, wave growth and cyclone organization | Small-Rossby-number approximation excludes important equatorial/ageostrophic behavior. It is a research comparator, not a universal planetary model. |
| Small hydrostatic primitive equations | Layer winds and thermal state, surface pressure, humidity; cloud state if needed | Thermal shear, baroclinic weather and broader tropical circulation with physical parameterizations | Greater completeness, not guaranteed beauty. Convection remains parameterized and very few layers may be inadequate. |
| Existing engine, particularly SpeedyWeather | Depends on hierarchy; spectral SW uses vorticity/divergence/height; wet primitive equations add thermal/moist fields and surface pressure | Existing dynamics and physics reduce solver development work; visual quality still requires Terra-specific evaluation | Native grids, precipitation/cloud diagnostics and surface models need auditing and coupling. A wet primitive model is not a supplied moist two-layer SW model. |

| Candidate | Numerical restrictions and work | Determinism, implementation and visible-value decision |
|---|---|---|
| Existing | Transport-only CFL, thermal/rain operators and render-driven outer batches | Repeatable for equal schedules; speed equivalence unproven. Small fixes are low effort but cannot meet the core storm objective. |
| Single-layer SW | Explicit gravity and advective CFL; approximately one layer of flux passes and integrator stages | Straightforward sequential deterministic arrays. Smallest custom test of dynamics; visual value must survive numerical diffusion. |
| Two-layer SW | External and internal wave speeds, shear and interlayer source constraints; about twice the layer work plus coupling | Deterministic in principle; pressure coupling and balanced initialization add substantial work. Require visibly better cyclone growth than one layer. |
| Moist two-layer SW | Above plus phase-transfer timescales, latent feedback and water/energy ledgers | Still deterministic with fixed ordered steps, including seeded subgrid variability. More maintenance; accept only if moisture changes dynamics and improves weather lifecycles. |
| Two-layer QG | Advective/forcing limits plus two PV-to-streamfunction inversions; cost depends on inversion method | Fixed reductions/iterations can repeat. Efficient in its regime, but a second equatorial solver would increase total complexity. |
| Small primitive equations | Fast waves remain unless treated implicitly; layer transports, hydrostatics and parameterizations increase passes | Deterministic CPU configuration is feasible, not automatic. Highest custom implementation risk; escalate only for a demonstrated visible limitation. |
| Existing engine | Semi-implicit spectral solves reduce wave restrictions but transforms/physics/output cost remain | Must pin versions, threads and seeds and test repeatability. Less atmospheric code to own; more deployment and interface code. Prefer it if measured total complexity wins. |

These tables answer VISION's ten comparison questions as hypotheses and known
limits, not experimental rankings. No candidate has yet passed Terra's local
visual, lifecycle, throughput or determinism comparisons.

## Evidence for moist two-layer dynamics

[Lambaerts et al. (2012)](https://journals.ametsoc.org/view/journals/atsc/69/4/jas-d-11-0205.1.xml)
derive their model with constant potential temperature in each layer, a rigid
upper boundary and lower-layer integrated humidity. Condensation drives interlayer
exchange through a moist-enthalpy relation. Their f-plane jet experiment shows
moist amplification and comma-shaped precipitation. It starts from a prepared
unstable jet and perturbation, with no moisture replenishment in that experiment.
This supports a compact instability experiment, not proof of self-sustaining
planetary climate. Its precipitation treatment also does not automatically provide
Terra's persistent cloud-liquid reservoir.

Before adopting this formulation, derive the signs and units of layer transfers,
momentum carried by exchanged mass, stratification, pressure potentials, saturation
and latent coupling. Budget water and the model's energy/enthalpy consistently.
Replacing `g` with a small effective gravity solely to enlarge timesteps would
change deformation scales and instability; compare those consequences explicitly.

Finite-volume metrics and staggered velocity/scalar placement have established
implementations to study in the [MITgcm algorithm documentation](https://mitgcm.readthedocs.io/en/latest/algorithm/algorithm.html).
This is a numerical reference, not a recommendation to copy its ocean
incompressibility constraint into Terra's atmosphere.

For a later primitive-equation comparator, [NCAR's Held–Suarez description](https://www.cesm.ucar.edu/models/simple/held-suarez)
separates dry dynamics from moist physics. [SPEEDY's technical description](https://users.ictp.it/~kucharsk/speedy_description/km_ver41_appendixA.pdf)
provides a semi-implicit spectral, eight-level, T30/96 × 48 example with simplified
parameterizations. [Isca](https://execlim.github.io/Isca/) offers a model hierarchy
using GFDL/FMS infrastructure and a Python interface, under GPL-3.0. Both are
valuable scientific references; neither has a measured Terra runtime or integration
advantage in this assessment. Do not adopt either by association with a benchmark
from a different model.

## SpeedyWeather assessment

The [documented hierarchy](https://speedyweather.github.io/SpeedyWeatherDocumentation/stable/)
includes barotropic, single-layer shallow water, dry primitive and wet primitive
models. Its spectral vorticity/divergence formulation uses spherical transforms;
the [SW algorithm](https://speedyweather.github.io/SpeedyWeatherDocumentation/stable/shallowwater)
treats linear gravity-wave terms semi-implicitly. This is a meaningful competitor
to a hand-written explicit solver, not merely a physics reference. The documented
models do not establish a ready-made moist two-layer SW implementation.

Published CPU resolution-sweep results:

| Configuration | Apple M3 SYPD | Derived simulated days/s |
|---|---:|---:|
| SW T31, 1 layer, 48 rings | 18,732 | 79.1 |
| SW T42, 1 layer, 64 rings | 15,114 | 63.8 |
| SW T63, 1 layer, 96 rings | 3,442 | 14.5 |
| Wet primitive T31, 8 layers | 1,400 | 5.91 |
| Wet primitive T42, 8 layers | 564 | 2.38 |
| Wet primitive T63, 8 layers | 147 | 0.621 |

These [upstream benchmarks](https://speedyweather.github.io/SpeedyWeatherDocumentation/stable/benchmarks)
identify an Apple M3, Julia 1.12.6 and SpeedyWeather v0.21.1+DEV. Runs exclude
initialization and output, last several wall seconds, and warn of ±50% timing
variation. The same table reports x86 wet T32L8 at 856 SYPD, approximately 3.62
days/s. Conversion here is `SYPD × 365 / 86400`; Terra's 30 days/s target requires
about 7,101 SYPD. Spectral truncation and ring counts are not equivalent to Terra's
rectangular cell counts. These results justify a local trial; they are not Terra
performance, long-run stability or coupled rendering measurements.

| Investigation | Finding and remaining work |
|---|---|
| CPU / Apple Silicon | Upstream CPU evidence is concrete. The current main README also advertises Metal; the GPU documentation still calls support a work in progress. Start the comparison on CPU and verify any accelerator separately. [README](https://github.com/SpeedyWeather/SpeedyWeather.jl), [GPU status](https://speedyweather.github.io/SpeedyWeatherDocumentation/stable/architectures_gpu). |
| Moisture / precipitation | The wet model includes convection and condensation. Condensation documents vertically integrated precipitation amounts, not just colors. Audit cloud-liquid availability, re-evaporation, surface stores and budget corrections before adapting fields. [Condensation](https://speedyweather.github.io/SpeedyWeatherDocumentation/stable/large_scale_condensation). |
| Custom geography | `ManualOrography` and fractional land masks are documented. Terra still needs a meter elevation scale, signed geography, spectral smoothing assessment and consistent ocean temperatures. [Orography](https://speedyweather.github.io/SpeedyWeatherDocumentation/stable/orography), [land mask](https://speedyweather.github.io/SpeedyWeatherDocumentation/stable/land_sea_mask). |
| Terrain / hydrology coupling | Replace or explicitly reconcile the engine's surface stores; otherwise two land/soil models would own the same water. Precipitation needs conservative physical-area remapping and interval totals, with feedback at a defined cadence. This adapter has not been built. |
| Snapshots | Callbacks and RAM output provide plausible access points. Use bounded copied fields at a limited cadence, not unbounded all-step archives. Engine serialization retains internal scaling and version assumptions; it is not a Terra-facing protocol. [Callbacks](https://speedyweather.github.io/SpeedyWeatherDocumentation/stable/callbacks), [output](https://speedyweather.github.io/SpeedyWeatherDocumentation/stable/other_output). |
| Multi-year stability | Not established by the short benchmark. Test repeated multi-year wet runs with budgets and bounded statistics before adoption. A stable default Earth run would not establish stability on Terra's custom geography. |
| Determinism | No cross-backend bitwise guarantee established. Pin Julia/package versions and manifests, disable stochastic forcing or seed it, fix BLAS/Julia thread counts, and repeat CPU runs/checkpoints. GPU reductions and changing versions require separate contracts. |
| License / runtime | Repository LICENSE is EUPL-1.2. Main's package file identifies `0.24.0-DEV`, Julia compatibility `1.10`, FFTW, NetCDF, JLD2 and additional dependencies. This differs from the benchmark version; pin a tested release/commit before reproducing. No runtime is added to Terra. [LICENSE](https://github.com/SpeedyWeather/SpeedyWeather.jl/blob/main/LICENSE), [package manifest source](https://github.com/SpeedyWeather/SpeedyWeather.jl/blob/main/SpeedyWeather/Project.toml). |

**Integration recommendation:** trial a separate persistent Julia process before
native embedding. Send batches of simulated steps and receive versioned copied
snapshots; measure process startup, JIT warmup, transfer and remapping separately.
Do not launch a process for every step or equate external-process execution with
free packaging. A two-way surface coupling protocol remains work even with IPC.

[Julia embedding](https://docs.julialang.org/en/v1/manual/embedding/) imposes runtime
initialization, memory-rooting and calling-thread constraints. A JVM adapter would
need a maintained C bridge and ownership rules. [PackageCompiler libraries](https://julialang.github.io/PackageCompiler.jl/stable/libs.html)
can expose C-callable functions, but still bundle Julia libraries and artifacts;
this is not evidence for a standalone zero-runtime native solver. Prefer that
route only if process measurements reveal a concrete need.

Locally, `julia` is absent from PATH. The initial sandbox check exposed arm64 only;
Gate A later identified an Apple M5 Pro / Mac17,9 with 24 GiB RAM under approved
Gradle execution. No external proof of concept was run, and none of the upstream
machine specifications are attributed to this host. The next reference
experiment must record its actual machine, exact package revision, grid-point
count, timestep, precision, enabled physics and output cadence.

## Geometry and timestep constraints

Before a dry solver, review a `PlanetGrid` contract with signed latitude, longitude,
cell areas, face lengths, center distances and variable locations. Use eastward
`u` and northward `v`; current array Y increases southward, including the positive
direction used by the existing meridional face transport. Convert explicitly.
Use `f = 2 Ω sin(latitude)`. Hydrostatic pressure gradients must include
geopotential consistently over terrain; raw surface-pressure differences would
drive spurious winds.

For a latitude–longitude mesh with radius R, longitude width Δλ and latitude edges
φS, φN, cell area is `R² Δλ (sin φN − sin φS)`. Zonal center spacing decreases
approximately as `R cos φ Δλ`. A scalar depth sum is not a global physical water
mass once areas differ. Mass and tracer fluxes must use the same physical faces.

The smallest custom experiment uses a flat doubly periodic **f-plane**, with
physical east/north distances and constant signed Coriolis. It isolates pressure
adjustment and instability without pretending to be a planet. Test the opposite
hemisphere by changing the sign of f; do not put a discontinuous beta-plane on a
periodic Y seam. Separately use SpeedyWeather to inspect spherical behavior.

A subsequent capped ±80° latitude–longitude channel is a possible low-disruption
metric experiment, but omits polar regions and needs closed-boundary assessment.
Full-sphere work may require reduced longitudes, polar aggregation or a separate
mesh. Keep atmosphere geometry independent of rectangular visual tile geometry.

Illustrative explicit wave constraint, not a selected integrator or stability proof:

`Δt ≲ C / ((|u| + c)/Δx + (|v| + c)/Δy)`, with shallow-water `c = sqrt(g H)`.

For an Earth-radius illustrative 283 × 212 full-sphere grid, `g = 9.81 m/s²`,
`H = 10,000 m`, conservative speed bounds `|u|, |v| ≤ 60 m/s`, and `C = 0.8`:

| Location | Approximate Δx | Approximate Δy | Illustrative timestep bound |
|---|---:|---:|---:|
| Equator | 141 km | 94 km | 121 s |
| 80° latitude | 24.6 km | 94 km | 42 s |
| First polar cell center, 89.58° | 1.05 km | 94 km | 2.2 s |

These are calculations, not Terra's existing physical grid or a prediction of its
eventual wave speeds. Atmospheric external modes require their own analysis.
Semi-implicit or split-explicit treatment might relax the fast-wave restriction,
but introduces solve/subcycle costs, accuracy tradeoffs and residual checks.
Advection, rotation, mixing and process coupling still constrain timesteps.

## Work and memory estimates

Let `N = 59,996`, `L` be layers and `S` steps per real second. Work scales at least
as `N L S`, multiplied by passes, stages, transported fields and any solver
iterations. A cell-layer update is not a floating-point operation or a benchmark.

At the hypothetical 600 s step, 30 simulated days per real second requires 4,320
steps/s. The revised VISION's candidate grids give:

| Atmosphere grid | Cells | Two-layer updates/s | Raw state, ten Double fields/layer |
|---|---:|---:|---:|
| 96 × 72 | 6,912 | 59.7 million | 1.11 MB |
| 128 × 96 | 12,288 | 106 million | 1.97 MB |
| 192 × 144 | 27,648 | 239 million | 4.42 MB |
| 283 × 212 | 59,996 | 518 million | 9.60 MB |

One-layer work roughly halves these update counts; a four-layer implementation
roughly doubles them before extra physics. These are not equal-cost cell updates.
Using an illustrative 100–500 arithmetic operations per layer-cell step, the
128 × 96 two-layer case spans 10.6–53.1 billion operations/s before transforms,
iterations and memory traffic. Count actual kernel stages rather than accepting
that assumption as a model forecast.

At 120 s the work is five times larger; at 10 s it is sixty times larger. Under the
same illustrative wave/speed bounds as above, a capped ±80° grid gives near-edge
explicit limits around 120 s for 96 × 72 and 90 s for 128 × 96, even before choosing
a more conservative method-specific CFL. This makes semi-implicit comparison
worthwhile. It does not justify immediately implementing an implicit solver.

The initial periodic 12,000 × 9,000 km experiment has 125 km spacing at 96 × 72 and
93.75 km at 128 × 96. A proposed 10/20/40 s timestep sweep is conservative enough
to investigate with a CFL target of 0.4 under the stated initial bounds, but the
runner must check actual evolving speeds and reject violations. It cannot assume
all those steps remain stable. A region this large on an f-plane is a numerical
laboratory, not geographically accurate planetary dynamics.

## Migration and gates

1. **Current baseline gate:** measure six speed presets and identify correctness
   gaps. A lightweight runner and the existing public interfaces are sufficient
   to start; full hydrology ledger work remains a bounded follow-up before wet
   gameplay replacement. Publish missing evidence honestly.
2. **Reference gate:** run an isolated, pinned SpeedyWeather CPU comparison,
   including snapshot cost. Decide whether its integration burden is competitive
   before investing in a custom core.
3. **Dry prototype gate:** if custom work remains justified, implement one isolated
   periodic f-plane solver at the two candidate grids. Compare numerical error,
   coherent motion and throughput with its reference and unperturbed controls.
   A diffusive solver that erases instability fails the visual goal even if stable.
4. **Cloud visualization / moisture decisions:** either can follow as separately
   reviewed experiments. Fix existing cloud thermodynamics before using it as a
   wet baseline; persistent cloud detail stays downstream of physical water.
   Conservative subgrid precipitation must preserve parent-cell totals.
5. **Second-layer decision:** derive a coherent moist two-layer model, then require
   better growth, organization or lifecycle than the single-layer control at
   acceptable cost. Do not add layers automatically. Escalate to primitive
   equations or an external engine only on evidence of a meaningful limitation.

Keep the validated gameplay solver available throughout. A detailed-weather exact
contract requires equal physical steps and coupling schedules for equal output.
The user-authorized coarse climate mode has a different contract: label its
approximation and validate long-term evolution independently. Mode transitions,
fast-forward equations and worker implementation remain separate bounded phases.
