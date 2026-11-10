# Proposal: Phase 0a — Measure the current weather model

Status: Reference checklist; sequencing superseded by the revised VISION and
[ATMOSPHERE_EXPERIMENT_PLAN.md](ATMOSPHERE_EXPERIMENT_PLAN.md). Do not execute this
entire instrumentation scope as the default next phase. This full ledger scope
remains unimplemented; the narrower Gate A baseline and its results are tracked
in the new plan.

Basis: [ADR_CLIMATE.md](ADR_CLIMATE.md), local HEAD `41138a5`, reviewed
10 November 2026. Reinspect code and working-tree changes before implementation.

## Outcome and boundary

Produce a repeatable headless run, a complete current-model water ledger, and a
measured performance baseline for the six existing speed presets. This is a
bounded first slice of VISION's observatory phase, not completion of Phase 0.

Preserve weather equations, timestep selection, hydrology cadence, bootstrap,
terrain generation, biomes and rendering behavior. Do not fix clouds, rain memory,
evaporation, topology, speed semantics or threading in this phase. Record their
limitations. No pressure or air-mass field exists; energy has no defined heat
capacity. Report those budgets as unavailable rather than inventing values.

Approval of this proposal would permit the tests, benchmark harness and profiling
below despite the task-specific prohibitions in the deleted historical plans.
It would not authorize their remaining tasks or a dynamical core.

## Exact proposed file scope

Paths below `terra/` are under `src/commonMain/kotlin/terra/` unless shown otherwise.

| File | Planned change |
|---|---|
| `weather/WeatherSimulation.kt` | Optional diagnostic sink; accumulate actual ocean evaporation, ocean rainfall and clamp corrections where transfers occur; expose copied diagnostic state and simulation time. Capture transport minima and sums before corrections. Preserve arithmetic order and default API behavior. |
| `hydrology/HydrologySimulation.kt` | Read-only diagnostics for pending runoff, pending direct lake rain, lake stores, actual returned evaporation and outlet export; copied diagnostic state includes pending elapsed time and wet mask. Do not count throughput arrays as stored water. |
| New `simulation/SimulationDiagnostics.kt` | Immutable diagnostic records, signed water ledger residual and explicit units/availability. No arrays shared mutably with callers. |
| New `src/jvmMain/kotlin/terra/experiments/WeatherExperimentMain.kt` | Headless CLI using existing World/Weather/Hydrology constructors; fixed-duration runs, schedule replay, checksums and CSV output. No KorGE window. |
| New `src/jvmMain/kotlin/terra/experiments/ExperimentMetrics.kt` | Monotonic wall timing, JVM CPU/allocation/heap metrics when supported, environment metadata and deterministic state hashing. Unsupported metrics are explicit. |
| `ui/AtlasScene.kt` | Opt-in frame timing and completed simulation-time counters around current calls. Buffer samples; export outside measured sections. Default UI behavior stays the same. |
| New `src/jvmTest/kotlin/terra/experiments/WaterLedgerTest.kt` | Closed-transfer and coupled-budget checks including pending stores, actual lake evaporation and outlet export. |
| New `src/jvmTest/kotlin/terra/experiments/RepeatabilityTest.kt` | Identical schedule repeatability, diagnostic-on/off equivalence and copied-state isolation. |
| `build.gradle.kts` | Add Kotlin test dependency for JVM tests and a dedicated `weatherExperiment` JavaExec task using the existing JVM runtime. Leave `runJvm` entrypoint unchanged. |
| New `docs/experiments/PHASE_0A_RESULTS.md` | Commands, environment, configurations, raw-output paths, results, failed gates and limitations. |
| `PHASE_0A_PLAN.md` | Record completed scope and gate result; stop. |

Do not edit `VISION.md`, README, deleted plans, topology, world generation, palettes
or terrain art. If exact ledger coverage needs a change outside this list, report
the concrete gap and revise the bounded plan before extending implementation.

## Work sequence

1. Capture uninstrumented baseline performance and repeatable public-state samples
   from the existing model with the new runner. Keep baseline and instrumented runs
   under the same JVM, build and pacing configurations.
2. Add optional transfer counters and read-only diagnostic snapshots. Prove that
   enabling diagnostics changes neither state nor coupling schedule.
3. Reconcile water in the headless driver; add meaningful checks below. Record
   existing clipping as a model defect if it exceeds tolerance. Do not repair it
   silently as part of instrumentation.
4. Measure instrumented overhead and live frame behavior for every preset. Write
   results with uncertainty, achieved speed and unsupported quantities. Present
   the gate decision and stop before cloud repair.

## Ledger and state contract

Use current equal-tile `mm·tiles` accounting, not kg or m³. Let:

`W = Σ(vapor + cloud + land soil) + Σ(lake storage) + Σ(pending runoff + pending lake rain)`.

Include soil under wet lake tiles because it remains an explicit store. Pending
potential evaporation is a request, not water. `routedMmTiles` is throughput, not
additional storage. Direct rain pending hydrology must be counted exactly once.

The signed physical-transfer residual is:

`R = W(t) − W(0) − ocean evaporation + ocean rainfall + terminal outlet export`.

Soil evaporation, condensation, cloud evaporation, rainfall onto land/lakes and
confirmed lake evaporation are internal transfers. Counters for each aid diagnosis
but must not be added to the global external-source ledger. Report numerical
corrections separately and also report `R − net numerical correction`; the latter
does not excuse a failed physical budget. Include counters over the entire outer
call, not just its final internal step. Verify the headless handoff consumes each
outer call exactly once and includes each hydrology pending reservoir.

Snapshots own copies and include all future-affecting state: vapor/cloud/soil,
thermal fields, rain activation, elapsed time, wet mask, lake stores and hydrology
pending stores/time. Record seed, terrain settings, dimensions, numerical call
schedule, build/model identifier and JVM metadata with checksums. Derived scratch
and observation counters need separate diagnostic hashes, not confusion with
prognostic repeatability. This is diagnostic capture, not save/resume support.

## Scientific checks and proposed thresholds

These are acceptance targets for this slice, not claims that the current model
passes them. Run reductions in a fixed order with compensated summation.

| Check | Experiment and acceptance |
|---|---|
| Repeatability | Two independent constructions, same seed/config and identical outer-step/hydrology schedule: identical state hashes at every checkpoint on the same JVM/build. No cross-JVM bitwise claim. |
| Observation does not alter simulation | Diagnostics disabled/enabled with the same schedule produce identical public-state samples and, where available, full state hashes. Mutating a returned copy cannot alter the simulation. |
| Water transfers | Exercise soil evaporation/drainage/overflow, ocean exchanges, direct lake rain, confirmed lake evaporation and terminal exports. Compare counter deltas against source/destination changes; no negative/nonfinite confirmed transfer. |
| Coupled budget | On 32 × 24 worlds for seeds 31742, 1 and 42, run 10,000 three-hour steps with per-step handoff. Check every step and checkpoints between daily routing: `abs(R) ≤ 1e-9 × max(1 mm·tiles, W(0) + cumulative external input)`. Report cumulative residual and worst interval residual. |
| Nonnegative stores | All water stores finite and ≥ 0 after each step; raw pre-clamp negatives below −1e-12 mm fail positivity. Report count, minimum and total correction for all clipping, including smaller roundoff corrections. No clamp may conceal a failed check. |
| Schedule sensitivity | Compare equal elapsed time submitted as three-hour calls versus recorded interactive batches, including month speed. Quantify differences in rain, stores and lake state; do not require equality or claim render independence. |
| Full-size integration | Default 283 × 212 seed 31742 completes the existing 30+180-day bootstrap with finite state and the same budget tolerance. Record bootstrap cost separately from steady operation. |

Use JVM test fixtures that directly exercise the existing weather/hydrology handoff;
if rare transfer paths need controlled private state, use a narrowly scoped internal
fixture initializer with explicit units, not public gameplay controls or reflection.
Keep any such initializer in the already listed simulation files. Tests must
exercise production transfer code rather than duplicate the equations.

Aquaplanet and flat all-land fixtures can use existing TerrainGenerationSettings
with zero terrain-noise strengths and constant elevation below/above sea level.
Do not modify World to create fixtures. Mountain-barrier and custom jet/rest
experiments, isolated uniform-tracer tests, physical area weighting, air/energy
closure, and diagnostic map overlays remain later observatory work.

## Benchmark protocol and performance gates

- Record machine model/CPU, core count, RAM, OS, power mode, JVM/Gradle versions,
  heap/GC flags, source revision, dirty diff identifier, seed, world dimensions,
  overlays, viewport and all timing parameters. Name the actual reference machine
  in the results; do not infer hardware from the repository.
- Headless: separate world/topology construction and bootstrap from steady runs.
  Warm up for at least 15 wall seconds, then collect three 30-second trials. Measure
  actual completed simulated days/s, process/thread CPU time, allocated bytes per
  simulated day, GC time and peak heap/process memory where available. Keep output
  and snapshot copying outside timed kernels and report their costs separately.
- Live desktop: use the default world, viewport and cloud overlay, no input during
  sampling, and each of Realtime, 1h/s, 12h/s, 1 day/s, 1 week/s and 1 month/s.
  After bootstrap and 15 seconds of warmup, capture three 30-second trials per
  preset. Include the terminal stalled frame if a synchronous update overruns the
  sampling window. Record frame p50/p95/max, CPU, allocation, memory, offered time,
  completed time and pending time. A headless replay is not a UI frame benchmark.
- Compare before/after distributions on the same machine. Proposed instrumentation
  budgets: ≤5% loss of achieved throughput and ≤5% increase in frame p95 with
  diagnostics enabled; disabled path ≤2% regression. Overlapping/noisy trials
  require reporting uncertainty, not declaring a small regression significant.
- Track whether steady p95 ≤33.3 ms and maximum frame time ≤100 ms. These are
  proposed responsiveness targets, not promises that the baseline passes. Existing
  failures remain explicit baseline defects; no worker or timestep change here.
- When a preset is too expensive, report achieved speed and lag. Never omit steps,
  cap requested simulated time invisibly or modify the numerical schedule to make
  the benchmark pass. In a ten-minute steady run at the highest sustainable preset,
  compare post-GC live heap in the first/last two-minute windows; unexplained growth
  over 5% fails bounded-memory acceptance.

Use JVM management APIs and, if useful, JFR from the installed JDK; add no profiling
library. State whether allocation is thread-local or process-wide. If tooling or
display access prevents a measurement, mark that evidence missing and leave its
gate open. Normal compilation alone cannot close this phase.

## Validation, rollback and estimate

Required implementation checks: `gradle compileKotlinJvm`, `gradle jvmTest`, the
headless experiment task, and live `gradle runJvm` measurements. Follow AGENTS.md's
elevated-access retry if Gradle cannot load `libnative-platform.dylib`. No build or
benchmark is needed for this documentation-only proposal.

Estimated complexity: medium, roughly 3–5 engineering days including measurement
and analysis; lake pending-store coverage and reproducible UI timings are the main
uncertainties. This excludes cloud repair, worker migration and dry dynamics.

Rollback is limited to this phase's instrumentation, runner, tests, build wiring
and measurement hook. Keep counters opt-in and preserve the original entrypoint.
Retain the baseline artifacts; do not revert terrain/hydrology work or unrelated
local changes. No new gameplay solver requires migration or rollback.

At the gate, report numerical passes/failures, clipping, schedule sensitivity,
unsupported air/energy budgets, performance versus baseline and missing evidence.
Recommend the next bounded action using those results. Stop for an explicit
decision before cloud physics, geometry or dynamics work.
