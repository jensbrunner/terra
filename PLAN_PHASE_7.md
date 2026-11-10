# PLAN_PHASE_7.md — Terra: Adaptive Time Fidelity & Smooth Fast-Forward

**Status:** Steps 0–10 complete, including the bounded acceptance review. Strict 100 FPS, scientific calibration and manual visual acceptance remain unverified/unmet as detailed in `docs/PHASE_7_ACCEPTANCE.md`.  
**Repository:** https://github.com/jensbrunner/terra  
**Inspected revision:** `main` at `3443721f0b856b5a3651672d27f01205aadc466d` (10 November 2026, commit *Phase 6 WIP*).  
**Scope:** Phase 7 only: keep the game responsive at high requested simulation speeds while advancing climate, hydrology, ecology and other slow world state meaningfully.  
**Priority:** Smooth 2D simulation and a believable evolving world **over exact short-term weather** at accelerated speeds. **Simplicity is king.**

> **Important:** Phase 6 is in active development. Before implementing any part of Phase 7, re-read the latest `main`, its `PLAN.md`, and the finished Phase 6 work. This plan records a specific source snapshot, not a promise that subsequent APIs remain unchanged. Do not overwrite concurrent work.
>
> **Implementation freedom:** This is a strong recommendation, not a rigid algorithm mandate. The implementation agent may simplify, modify, reorder, or reject parts of the plan with a short technical justification and evidence that the alternative better achieves responsiveness, continuity and maintainability.

---

## 0. Executive decision

**Implement two simulation engines, not a hierarchy of progressively smaller fluid solvers:**

1. **Detailed weather** — retain the existing 96×72 one-layer moist rotating shallow-water solver, fixed physics timestep, actual winds/clouds/rain and existing water transfers.
2. **Approximate climate** — a single lightweight, area-consistent **climate flux/evolution model**, operating at an **adaptive temporal resolution**, from hours or days at moderate speed to seasons or multi-year intervals at extreme speed. It should advance the real soil, ocean, lake, river and ecology state without performing individual fluid timesteps.

Render both through one game-facing view. The user chooses **time speed**; the system chooses the affordable fidelity automatically and clearly indicates the effective simulation regime.

**Do not implement a full second shallow-water solver at 48×36 or 24×18 by default.** Fewer cells do not eliminate the 20-second gravity-wave timestep. Introducing a second numerical fluid solver, conservative atmosphere-resolution switching, and multiple state transfers is a poor complexity/benefit trade-off unless measurement later proves otherwise.

The climate approximation should use **actual climatic knowledge** where available (Phase 6 seasonal climate statistics, physics-derived spatial patterns) and **deterministic spatially correlated variability** for wet/dry spells and extremes. It must be honest: a representative weather field in fast mode is **not a continuation of the exact resolved storms**.

**Critical distinction:** A target of ~100 rendered FPS is not automatically achievable on a 60/75 Hz display, under VSync, on slow GPUs, or during OS stalls. Treat 100 FPS as a **performance target on capable hardware**, with a **10 ms/frame render budget**, not as a guarantee or a license to falsify FPS counters. If necessary, actual simulation speed may fall below requested speed; the UI must report that rather than freezing or silently losing time.

### What success looks like

- At each selectable speed, panning/zooming, button clicks and visual animation remain responsive; no repeated multi-frame freezes caused by atmospheric integration.
- The calendar advances at or near the **selected** rate when the available CPU permits; otherwise it degrades gracefully and reports **achieved** rate.
- At faster rates, cloud detail and storm trajectories are **representative** rather than exact; the visible world, seasons, water stores and biomes continue changing.
- A speed change or pause does not regenerate the world, reset lakes/soil, lose pending rain, or replay a completed transfer.
- Fast-forward is bounded in CPU consumption, memory use, input latency and work backlog; the game never tries to catch up by doing unbounded work on the render thread.

---

## 1. Current implementation: concrete integration constraints

The following were verified at the inspected revision. Re-check before editing.

| Area / source | Current behavior | Phase 7 implication |
|---|---|---|
| `simulation/SimulationSpeed.kt` | REALTIME, ONE_HOUR, TWELVE_HOURS, ONE_DAY, ONE_WEEK, ONE_MONTH | Preserve preset semantics; add optional year/decade speeds only after the fast path works. |
| `ui/AtlasScene.kt` | One KorGE render updater accumulates `dt.seconds * selectedRate` and invokes the weather advance | **Main source of physics-induced frame stalls.** Separate work budgeting, presentation and simulated-clock ownership. |
| `weather/DynamicalWeather.kt` | `advanceBudgeted(requestedSeconds, maximumSteps=8)` executes whole 20 s steps; snapshots copied after each batch | Existing batch cap protects a frame but limits throughput; presentation snapshots should be rate-limited independently of physics work. |
| `atmosphere/DryAtmosphere.kt` | 96×72 Cartesian shallow-water, signed-latitude channel, periodic X, polar walls; 20 s fixed dt | Preserve detailed integrator; do not enlarge dt beyond numerical stability simply to meet speed labels. |
| `atmosphere/MoistAtmosphere.kt` | 20 s solar/latent/surface/precipitation exchanges; atmosphere elapsed time follows completed step count | A coarse-year jump needs a **separate authoritative world clock**; do not pretend detailed steps occurred. |
| `weather/SeasonalClimate.kt` | Four seasonal bins, initial spin-up year, latest two completed observed years; per-physics-step accumulation | Phase 7 needs **bulk interval ingestion** and persistent climate history; cannot call the 6,912-cell observer millions of times per simulated year. |
| `world/World.kt` | `advanceClimateBiomes(...)` with stable annual climate, uncertainty margins, ~2-year candidate persistence; ~60k tile scan | Preserve ecology timers, but update at climate boundaries rather than on every rendered frame or hourly game update. |
| `weather/TerrainMoistSurface.kt` | Fine-grid soil, rainfall, evaporation, drainage; lake/ocean stores owned by hydrology | Both regimes must change the **same** reservoirs; do not instantiate a fresh `TerrainMoistSurface` with default soil on mode return. |
| `hydrology/HydrologySimulation.kt` | Fine-grid runoff arrays, daily routing cadence, persistent lake storage, finite shared ocean, pending fluxes | Introduce a **coarse flux handoff / batch routing path** that does not do 365 daily full-grid passes for a simulated year. |
| `render/CloudRenderer.kt` | 2× terrain-size cloud image; advection and CPU pixel reconstruction around 10 Hz | Renderer itself can cause >10 ms spikes even when physics is off-thread; treat cloud bitmap work as a separate budgeted stage. |
| `render/TerrainMapView.kt` | Weather and surface-water bitmaps; biome redraws can rebuild terrain map | Publish immutable/consistent states and use dirty-tile redraws or amortized updates where justified. |
| `weather/WeatherModel.kt` | Read-only tile weather facade except lake mask/return hooks; update-period rain and instantaneous weather mixed | Need clearer distinction between **resolved/current** and **representative/aggregated** values in fast mode. |

### Exact step-count problem

The detailed timestep is 20 simulated seconds:

| Requested speed | Required detailed steps per real second | At 100 FPS, average steps per frame |
|---|---:|---:|
| Realtime | 0.05 | 0.0005 |
| 1 hour/s | 180 | 1.8 |
| 12 hours/s | 2,160 | 21.6 |
| 1 day/s | 4,320 | 43.2 |
| 1 week/s | 30,240 | 302.4 |
| 1 month/s (30 days) | 129,600 | 1,296 |
| 1 year/s (365 days) | 1,576,800 | 15,768 |
| 100 years/s | 157,680,000 | 1,576,800 |

Arithmetic only: **not measured throughput** of the newly implemented solver. A fixed maximum of 8 steps per frame at 100 FPS corresponds to just 800 × 20 = 16,000 simulated seconds/s, or **4.44 simulated hours/s**, even with a zero-cost solver. At 60 FPS the corresponding cap is **2.67 hours/s**.

**Conclusion:** The high-speed feature cannot be solved by “run more physics steps each frame,” and increasing the number of frames must never increase the amount of simulated time that is physically affordable.

### Important existing performance warning

Earlier baseline measurements (`docs/experiments/BASELINE_RESULTS.md`, from the *older prescribed model*, not the current dynamical core) already showed that full weather plus rendering failed to sustain requested month/s throughput and caused substantial high-percentile frame time. Do not transfer those exact performance numbers to the new solver. **Measure the current code on the current hardware.**

---

## 2. Architectural invariant: one world and one clock

### One authoritative simulation clock

Introduce or centralize a monotonic **world simulated time**, independent of the detailed atmosphere's `completedSteps`.

- Suggested representation: a `Long` for whole simulated seconds plus, if needed, a tiny subsecond remainder. A `Double` with explicit boundary handling is acceptable for the expected lifespan; avoid accumulating floating-point errors across hundreds of years.
- Detailed mode advances the world clock **only for successfully completed physical steps**.
- Fast mode advances it **only for successfully completed and committed climate intervals**.
- Planetary sunlight, year/season, ecology, climate statistics, and UI all observe the same clock.
- Physical time is never advanced by the renderer or by changing the weather label.
- `MoistAtmosphere.elapsedSeconds` may remain a local numerical integration clock; use a deliberate epoch offset or explicit world-time argument for sunlight and other external forcing.
- Do not equate `(completedDetailedSteps * 20s)` with the world's lifetime after fast-forward.

### One authoritative set of slow reservoirs

At any instant there is only one real:

- Soil-water field.
- Lake storage and wet-mask state.
- Shared finite ocean-water reservoir.
- Pending runoff/lake rainfall not yet routed.
- Slow surface thermal state.
- Ecological/biome state and its pending-change timers.
- Climate statistics/history.

The detailed and fast engines must not maintain independent versions of these stores and later “pick a winner.”

**Preferred implementation:** a small shared `WorldSimulationState` / `SurfaceState` held by a simulation owner and used by both model regimes. But if modest modifications to the existing `TerrainMoistSurface` and `HydrologySimulation` achieve the same exclusivity with less code, prefer those. Avoid extracting a large new entity-component architecture.

### One transaction boundary

Each advancement operation is conceptually:

1. Choose an interval `[t0, t1)` and simulation regime.
2. Compute water/thermal/soil/climate effects for exactly that interval.
3. Debit each donor and credit each recipient **once**.
4. Route or retain pending water according to the appropriate time approximation.
5. Commit complete new state and set the clock to `t1`.
6. Publish a consistent snapshot for presentation.

No partially advanced visual world, no double-counted rain, no discarded queued runoff.

### Fidelity is an explicit contract

Define two authoritative regimes:

- `DETAILED`: current physically integrated atmospheric state and real recent weather fluxes.
- `CLIMATE`: approximate long-interval climate fluxes with statistical weather variability, the real slow-world state, and representative display-only weather.

Do not label the fast regime “detailed but lower resolution” unless it really does resolve the intended physics.

---

## 3. Achieving ~100 FPS: render separation is non-negotiable

### Target frame budget

At 100 FPS, a frame is 10 ms wall time. Suggested **initial planning budgets**, subject to measurement:

| Activity on render thread | Target typical wall time |
|---|---:|
| Input, camera, controls, lightweight labels | ≤0.5 ms |
| Acquiring already-completed simulation snapshot | ≤0.3 ms |
| Scene/image updates, uploads and normal drawing | Fit within remaining ~8–9 ms |
| Atmospheric/hydrology step on render thread | **0 ms in final architecture** |
| Full climate/biome scan on render thread | **0 ms** |

These are engineering budgets, not observed measurements or hard per-component guarantees. A GPU/VSync frame can be slower even if the CPU fits.

**Rendering 100 FPS does not require running atmosphere, hydrology, biomes or cloud texture synthesis 100 times/s.** The last complete state can be drawn many times while the visual presentation interpolates or smoothly animates inexpensive effects.

### Preferred thread model

**One simulation worker** owns all mutable simulation state; the KorGE render/UI thread owns graphical objects and GPU uploads.

- No solver call inside the KorGE `addUpdater` once migrated.
- No `World`/`HydrologySimulation` mutation concurrently with render reads.
- No GPU/texture/scene-tree operations on the worker.
- UI sends small commands (`setSpeed`, `pause`, `resume`, `regenerate`, `switchWeatherMode`, `stop`) to the worker.
- Worker publishes timestamped immutable snapshots through a **bounded latest-state mailbox** (max 1 or a few waiting snapshots). An outdated visual snapshot can be dropped; the **simulation state/physical flux cannot**.
- Render thread reuses the latest committed snapshot until the next arrives.
- Publish with a world generation ID and monotonically increasing revision so that old-world snapshots are ignored after terrain regeneration.
- Ensure thread-safe ownership and safe shutdown; only one producer mutates the world.

On JVM, use a single dedicated thread/actor and ordinary thread-safe queues/atomic handoff as appropriate. Avoid a thread pool, actors-per-subsystem, unbounded event histories, busy waiting or a complicated parallel physics engine.

**Fallback:** If a bounded single-thread approach empirically meets the render target at every implemented speed, the agent may keep a simpler single-thread design. But one expensive atmospheric step or one long ecology scan must never create an unavoidable >10 ms render stall. This should be demonstrated, not assumed.

### Snapshot design

The existing `MoistAtmosphere.snapshot()` copies multiple coarse arrays and computes diagnostics; doing this for every tiny worker step wastes time and memory.

- Decouple step frequency from snapshot frequency.
- Publish coarse atmospheric fields only as frequently as the view needs, e.g. 5–15 Hz initially.
- Publish biome/terrain change deltas when their revision changes, not a complete terrain rebuild after every climate tick.
- Publish hydrology visuals at a lower cadence or when visible state changes.
- Prefer flat primitive arrays; bounded copy-on-publish is initially simpler and safer than a fragile mutable shared double buffer.
- Do not reuse or mutate a published buffer until the render thread has finished consuming it.
- If profiling demonstrates snapshot allocations are problematic, implement a small fixed buffer pool with explicit ownership; do not do so prematurely.

### Expensive renderer stages

`CloudRenderer` maintains a 2× tile-size texture (about 566×424 pixels for the current world) and performs many per-pixel samples. Its default refresh cadence is about 10 Hz. A whole texture update can still exceed a 10 ms frame even if simulated weather is fast.

Plan to measure and, if necessary:

1. Rate-limit CPU cloud-field generation separately from drawing.
2. Generate RGBA pixels off-thread from a published physical snapshot, then submit a bounded texture update on the render thread; **or** partition CPU work across frames with a complete-buffer swap.
3. Keep clouds visually smooth between updates through cheap interpolation/transform where possible.
4. Avoid calling many `WeatherModel` virtual/sample methods inside the inner per-pixel loop when the needed coarse fields can be prepared once.
5. Rebuild weather overlays only when their source revision changes or the intended display interval expires.
6. Avoid rebuilding all terrain tiles when only a few biome tiles changed; use dirty tile indices where feasible.

Do not create an elaborate GPU pipeline if a small CPU optimization already meets the target.

### Timekeeping and the overload spiral

The render loop **never** executes “as many physics steps as needed to catch up.” That produces the classic fixed-timestep spiral of death when computation cannot keep up.

Demanded simulated time is a **rate request**, not a command to block rendering. The simulation worker executes affordable batches. A bounded pending-time policy prevents unlimited queued catch-up after slow frames, window minimization, or a one-minute OS pause.

Report separately:

- Requested simulated seconds per wall second.
- Actually completed simulated seconds per wall second.
- Simulation regime and approximate temporal quantum.
- Pending requested simulated time / whether rate is throttled.
- Measured frame p50/p95/p99 and worker time, in diagnostic mode.

Never infer progress from KorGE's possibly clamped frame delta alone; use a monotonic wall clock for measurement and a defined policy for noninteractive suspension.

---

## 4. Automatic fidelity selection: temporal LOD rather than spatial mipmaps

### The simple rule

**Quality decreases as the requested time rate grows**, not because the user zooms the camera away from a storm. The camera view does not own physics.

Only **two models** are necessary. The climate model changes its **integration quantum**, data resolution, and event treatment as speed increases.

Proposed behavior (initial candidates, NOT promises; adapt to measured throughput):

| Requested speed | Regime | Approximate effective quantum | Fast-mode visual meaning |
|---|---|---|---|
| Realtime | Detailed | Fixed 20 s | Actual evolving weather |
| 1 h/s | Detailed if affordable | Fixed 20 s | Actual evolving weather |
| 12 h/s | Climate if detail exceeds worker budget | 1–3 simulated hours | Representative synoptic/cloud changes |
| 1 day/s | Climate | 3–6 simulated hours | Representative daily weather |
| 1 week/s | Climate | 1–2 simulated days | Wet/dry spells, changing weather regions |
| 1 month/s | Climate | 3–7 simulated days | Weekly weather regimes and water storage |
| 1 year/s (new optional preset) | Climate | ~1 simulated month | Seasonal/annual changes |
| 10 years/s (optional) | Climate | ~0.5–2 simulated years | Long-term climate and vegetation |
| 100 years/s (stretch goal) | Climate | ~5–20 simulated years | Statistical climate/biome evolution; no storm detail |

The table deliberately prefers **coarser time** before adding a second lower-resolution fluid model. At extreme speed, several years may be advanced in one analytically approximated update. Rendering interpolates ecological appearance across the visible interval; it does not pretend to show every intervening weather event.

These coarse quanta should be aligned to calendar/season boundaries when convenient, and partial quanta must be supported for speed switches and pause. Do not overshoot the requested world time and later roll back reservoirs.

### Automatic detailed→climate threshold

Estimate sustainable detailed throughput from actual completed step times over a rolling window, with headroom for other worker tasks. For example:

`safeDetailedSimSecondsPerWallSecond = (20 s / meanStepWallSeconds) * safetyFactor`

with initial safety factor perhaps **0.5–0.7**, subject to measurement. Do not use one noisy sample; use a conservative percentile or smoothed estimate and a minimum stable observation window.

Select detailed mode only when requested speed is comfortably sustainable. Do **not** wait for FPS to collapse before switching.

### Hysteresis

Prevent oscillation at the threshold:

- Switch to climate mode promptly if required throughput exceeds a safe detailed budget for a sustained short interval.
- Switch back only when requested throughput has stayed clearly below a lower threshold for a longer interval, or on an explicit user speed change to a supported detailed setting.
- After a switch, apply a short cooldown so mode changes cannot chatter.
- The UI should identify approximate vs resolved weather without interrupting the user.

Avoid “optimize per frame” numerical algorithms that respond chaotically to rendering jitter.

### If selected speed is still unaffordable

If the climate regime cannot meet the target:

1. Increase its climate quantum within justified model limits.
2. Reduce the frequency of nonessential visual updates and full-world ecology scans.
3. Evaluate an aggregate multi-year analytical update instead of iterating every month or every year.
4. If still too expensive, **slow actual calendar advancement and show achieved speed**. Never freeze rendering or silently skip model state transitions.

The goal is approximately 100 FPS, **not unlimited simulated throughput at any cost**.

### Simplest acceptable first implementation

Start with a clear **detailed/climate boundary** at a conservative preset if that is easier, and add measured automatic selection afterward. Do not build an adaptive controller before either engine works.

---

## 5. What the climate model should actually simulate

### Scope: a cheap seasonal flux model, NOT another fluid solver

Define one `FastClimateStepper` (name flexible) with minimal persistent state:

- Absolute simulated world time.
- One coarse grid of seasonal/annual **climate normals** for temperature and precipitation (ideally reuse existing 96×72 climate bins; reducing to 24×18 or 32×24 is optional and should be measured).
- A few **slow spatially correlated anomaly fields** (wet/dry, warm/cold; perhaps 2–3 arrays).
- A small effective atmospheric moisture reservoir, or an equivalent explicitly audited mass-balance representation, so synthetic rain cannot appear from nowhere.
- Reused scratch arrays for climate-to-surface water exchanges.
- Persistent seed/counter for deterministic stochastic climate events.

**Do not prognose pressure, two momentum fields and full cloud microphysics in climate mode by default.** These are the computations being replaced.

### Climate normals: use Phase 6 rather than inventing a parallel ecosystem

`SeasonalClimate` currently accumulates four seasonal bins, discards the first year as spin-up, and keeps the latest two complete observed years. The fast model should consume or extend those statistics:

- Season-dependent mean surface temperature (the existing temperature diagnostic is explicitly a surface/ecology proxy).
- Seasonal precipitation totals.
- Broad wet/dry contrast and, where available, rainfall variability/extreme diagnostics.
- Existing land/ocean heat capacity, latitude, orography and other slow spatial factors.

The statistics should be **continuously defined** even when the detailed solver is dormant. Make the accumulator accept **time-integrated flux/temperature summaries for arbitrary completed intervals** rather than requiring one 20 s observation call per fluid step.

### The no-history problem (important)

Fast-forward must work immediately after starting a new dynamical world—even before the current model has simulated its first full observed year.

Implement a small provisional climate prior derived from **already-available** information: pseudo-latitude and seasonal sunlight, land/ocean mask, mean surface temperature, windward relief, and an inexpensive humidity/precipitation heuristic.

- Label it provisional; do not claim it represents equilibrated climate.
- Blend toward actual observed seasonal statistics as evidence becomes available.
- Do not synchronously simulate two full years of detailed atmospheric timesteps to make fast-forward usable.
- Do not initialize every tile to identical worldwide rainfall.
- Avoid hard-coded permanent rainy/desert zones; geography and forcing should determine the baseline.

If the implementation model finds a simpler existing equilibrium estimator, reuse it rather than writing a second climatology engine.

### Spatial and temporal variability

Without variability, fast mode would distribute average rainfall constantly, erasing droughts, extreme runoff and interesting long-term ecology.

Use **a few smooth, spatially coherent stochastic fields** with persistent time correlations:

- Update on a monthly/seasonal climate clock, not each render frame.
- Preserve broad geographical continuity across longitude wrap.
- Use a stationary, mean-reverting process or a small number of deterministic traveling Fourier-like modes; do not allow an unbounded random walk that makes the whole planet permanently wetter every century.
- Couple temperature and rainfall anomalies loosely (e.g. persistent dry anomalies can promote heat), without constructing a complex atmosphere.
- Preserve equatorial/polar, ocean/land and mountain contrasts supplied by climate normals.
- Seed variation using world seed and integer climate-interval index, not wall-clock time, frame count or thread scheduling.

**No per-tile independent rainfall random numbers.** They yield visual static and physically incoherent pixel noise. Generate anomalies on the coarse climate grid and map them smoothly to fine terrain.

### Precipitation occurrence and intensity

At moderate climate speeds, one of the cheapest useful approaches is a small wet/dry persistence process plus a skewed rain-amount distribution:

- A regional wet/dry state persists across adjacent climate intervals.
- Conditional precipitation amount is nonnegative and occasionally strong.
- Rainfall events within an interval have a duration/concentration measure distinct from the interval's mean total.
- Sample event count/intensity so that the long-run average stays near the applicable climate normal, not arbitrarily above it.
- Wet/dry occurrence and amounts should be influenced by the same large-scale anomaly field.

A Markov wet/dry model with skewed positive amounts is a known economical weather-generator idea; **a fully calibrated WGEN implementation is not required**.

At month/year/decade quanta, **do not materialize every daily event**. Instead compute the interval's total rain, wet fraction, and a compact event/concentration statistic sufficient to estimate runoff and extremes.

### Absolute water budget

The fast model's predicted rain is a **demand**, not an unaccounted source. Every accepted precipitation amount must be transferred from water already held in an atmospheric reservoir or from properly scheduled evaporation that debits soil/ocean/lakes.

For equal-area Terra cells, the closed world-water invariant is conceptually:

`W_atmosphere + W_soil + W_ocean + W_lakes + W_pending = constant`

(up to known external exchanges, none currently required for planetary water mass).

- Evaporation: **debit source**, **credit climate atmospheric moisture**; charge a documented latent-energy effect or approximation.
- Rain: **debit climate atmospheric moisture**, **credit appropriate soil/lake/ocean destination**.
- Soil runoff: **debit soil**, **credit pending routing**.
- Lake/spillwater transfer: debit one store and credit exactly one next store.
- Ocean-bound discharge: credit the shared ocean reservoir (already owned by hydrology in current code).
- Diagnostic totals such as cumulative rain or outlet throughput are **not** additional reservoirs.

If the desired rain exceeds available water, reduce it or use an explicitly time-resolved evaporation/rain circulation within the interval. **Never create an implicit moisture deficit or inject balancing water with an unexplained correction.**

There are two acceptable implementation variants:

**A — Recommended simplest:** Keep an explicit effective climate atmospheric moisture pool on the coarse grid. Perform simplified seasonal evaporation/precipitation transfers in a small number of ordered subperiods; do not model detailed winds or pressure. Keep the pool nonnegative and mass-balanced.

**B — Only if A is too limiting:** A small conservative coarse moisture-transport kernel, using diagnosed/seasonal prevailing flow. This adds complexity and a transport timestep restriction, so justify it with better visible wet/dry geography or better hydrological water balance.

Do **not** add an entire second shallow-water solver merely for atmospheric water conservation.

### Surface temperature and climate heat

Retain slow surface thermal memory, especially oceans:

`T_new = T_equilibrium(season, geography) + (T_old - T_equilibrium) * exp(-Δt/τ)`

where the response time may differ between land and ocean. This is an **idealized thermal relaxation**; its radiative source/sink is external forcing, not internally conserved energy. Document it as such.

Do not instantly reset ocean and land temperatures to their seasonal means when entering fast mode. Do not let a negative/positive multiple-year extrapolation push reservoirs to implausible values.

If fast-mode evaporation imposes latent heat, debit the appropriate thermal store or report the deliberate coarse energy-budget approximation. A **strict water budget is mandatory**; an exact long-term energy budget can be simplified if explicitly identified and stable.

### Cloud/rain display in climate mode

Climate-mode cloud and rain visualization must derive from current approximate climate wetness/anomaly state. It is not the last detailed storm frozen for 100 years, and is not a true instantaneous resolved storm.

Give the presenter an explicit field quality/period label:

- `resolvedNow` for detailed fields.
- `representativeForInterval` for climate imagery.
- `integratedOverInterval` for accumulated rain/evap/runoff.

Display meaningful units. A month of rain divided by a month is a mean mm/h, **not the peak shower rate**. Do not call a synthetic storm image a resolved cyclone.

---

## 6. Coarse soil, runoff, lakes and water: the difficult part

### Why daily hydrology cannot simply remain daily

At 100 years/s, 36,500 simulated daily hydrology routings would be required each wall second. Each routing involves substantial full-world array work. This alone would defeat the target even if the atmosphere were free.

The coarse model needs a **bulk hydrology route** that respects conservation but approximates within-interval timing.

### Preserve fine geography

The static drainage network is already cheap and valuable. Retain it at the existing **283×212** tile resolution:

- Fine catchment topology, river connectivity, lakes and coastline should not be regenerated at coarse temporal resolution.
- Coarse atmospheric/climate fields can be sampled or distributed over fine terrain via the existing equal-area mapping.
- Aggregation is primarily over **time**, not necessarily over terrain space.
- Avoid a second coarser drainage graph and expensive reconciliation between river networks.

### Minimal soil-bucket fast update

Current soil rules have finite capacity (250 mm), approximate field capacity (120 mm), drainage timescale around five days, and evaporation that withdraws actual water.

A naive yearly operation that applies all rainfall first, then caps soil, then evaporates would produce wildly wrong runoff and evaporation.

Instead, for each coarse climate interval:

1. Compute aggregate rain, potential evaporation, effective wet duration, and rain concentration/extreme descriptors.
2. Split only as much as needed into a few representative wet/dry phases **without generating all days**.
3. Apply evaporation limited by actual soil water and thermal/seasonal conditions.
4. Apply rain to soil, capped at capacity; overflow becomes runoff.
5. Use the analytical five-day drainage relaxation when rain/evap forcing is treated as constant over a phase. For example, an excess store `E` above field capacity decays as `E_end = E_start * exp(-Δt/τ)` in a dry phase, with the difference transferred to runoff.
6. Account for the effect of multiple rain events without exploding the number of updates. Use a representative/event-moment approximation if necessary.
7. End with soil in a valid physical range and each water transfer recorded exactly once.

In the presence of continuous forcing, use a consistent semi-analytical bucket equation or a small capped substep count; do not apply the dry relaxation formula blindly while injecting water simultaneously.

For very long multi-year jumps, use **bounded analytical relaxation / statistics**, not a loop over every day or month. Approximation error is acceptable; negative water and unexplained nonconservation are not.

### Event-sensitive runoff (essential)

100 mm over two wet days is not hydrologically equivalent to 100 mm spread uniformly across a month.

At a minimum, compute:

- Total rainfall.
- Fraction of interval with precipitation.
- A measure of intense-event rain or exceedance over infiltration capacity.
- Slow drainage from existing soil water.

When calibrating fast hydrology, compare **runoff/overflow, lake storage, and catchment flow**, not merely annual precipitation totals. Do not use an unconditional runoff coefficient that ignores soil saturation.

### Bulk routing API

Avoid faking a long `WeatherModel.step(year)` and handing its last-update arrays to hydrology as though they represent one daily step.

Introduce a narrow primitive-array flux handoff, e.g. (illustrative, not compulsory):

`acceptClimateFluxBatch(durationSeconds, runoffByTile, directLakeRainByTile, lakeEvaporationByTile, metadata)`

or reuse an equivalent common `WaterFluxBatch` adapter shared by detailed and climate mode.

Requirements:

- Exact ownership transfer and no duplicate acceptance.
- Fine tile runoff routed in upstream topological order once per appropriate coarse segment.
- Lake inflows/spills and current storage correctly debited/credited.
- Return outlet discharge to the finite global ocean store.
- Separate **period throughput** (mm·tiles per interval) from **rate/proxy** (mm·tiles/day).
- Never equate a 10-year aggregate routing with a single day's flood peak.

The existing lake evaporation API is immediate-withdrawal, and its wet mask is updated through hydrology. The fast path must either use this correctly in a few ordered segments or expose a small batch evaporation operation with equally explicit ownership.

### Lake dynamics over long chunks

Applying 10 years of net rain/evap/runoff in one final storage clamp can hide repeated fill/spill/dry cycles and drastically misrepresent actual lake flux.

Prefer several **seasonal representative subperiods** when a lake's net flux can change sign through a year. For extreme multi-year chunks, use a cheap rate-based piecewise reservoir integration with analytic limits and summed overflow, not hundreds of thousands of short hydrology calls.

If future floodplains/snowpack/groundwater are added, they must enter the common slow-state and coarse-step contracts; do not secretly omit their water from the ledger.

### Water budget / numerical diagnostics

Maintain a reconciled total water ledger before and after every completed climate batch. Diagnostic sums may be computed at bounded cadence, not necessarily after every very small step.

Track at least:

- Total atmospheric effective moisture and detailed vapor/cloud when applicable.
- Soil water.
- Finite ocean water.
- Lake storage.
- Pending runoff and lake rain.
- Internal transfers for rain/evap/outlets (diagnostics only).
- Any explicitly introduced external source/sink (prefer none).

**No automatic “fix the residual by adding ocean water” correction**. Fix the accounting path instead.

---

## 7. Extreme fast-forward: make cost scale with output, not with elapsed years

### The 10–100 years/s challenge

An implementation that evaluates 60,000 tiles once per simulated **day** will do 2.19 billion tile visits/s at 100 years/s. That's already prohibitive for a smooth game before hydrology, climate, and rendering.

Even one full tile pass per **month** costs approximately 72 million tile visits/s at 100 years/s, before multiple operators and scene updates. Treat these as arithmetic costs, not benchmarks.

A genuine 100 years/s path must be able to skip explicit intermediate ecological and hydrological daily/monthly states.

### Proposed graduated climate temporal LOD

- **Hours–days/s:** A few hours or one day per fast update, with explicit wet/dry patterns.
- **Weeks–months/s:** Day-to-week chunks, representative events, bounded lake/soil subphases.
- **Years/s:** Monthly or seasonal climate chunks; ecology uses aggregate statistics.
- **Decades/centuries/s:** Annual or **multi-year** chunks, integrated climate means/variances and analytical slow-store relaxations. No explicit storm trajectories or annual per-tile event lists.

The number of completed fast updates per wall second should remain **bounded** (initial target perhaps ~5–20, measured), rather than grow linearly with the selected simulated-time multiplier.

### Multi-year climate variation without iterating each year

For a multi-year chunk, compute:

- Expected seasonal precipitation/temperature over the whole interval.
- Low-frequency persistent anomaly integrated over the interval.
- Approximate frequency and cumulative influence of droughts and extreme rain.
- The analytical/aggregate response of soil, ocean, lakes and ecology.

Use a deterministic, low-dimensional climate-state evolution that can be advanced or summarized over a long interval, rather than drawing hundreds of random events and routing them one by one.

An AR(1)-like anomaly has a closed-form mean reversion over `n` steps (`a^n`); its stochastic aggregate can be sampled deterministically if needed. Avoid requiring exactly the same sequence as `n` short updates; define reproducibility **per selected climate integration schedule**.

### Ecology is a slow system

Phase 6's biome-change timers should be advanced using their genuine eligible duration, not by pretending that the last instantaneous climate field has persisted unchanged through ten simulated years.

For a multi-year chunk:

- Use the interval's climate summaries and persistence/stability evidence.
- Update candidate biome-change duration conservatively.
- If conditions cross classification thresholds during the chunk, approximate the duration of each regime rather than applying the final value to the entire period.
- Apply biome transitions to authoritative world state in bounded batches.
- Throttle visual terrain refresh; don't rebuild a 60k-tile tilemap 100 times a second.

If coarse evolution is too uncertain to support a biome change, **delay** the transition rather than invent certainty.

### Visual continuous change during time jumps

If one fast batch advances five model years, the world state may change discontinuously between published snapshots. Smooth **presentation** using short crossfades or progressive dirty-tile updates, without inventing intermediate physics.

The displayed calendar should show the **actual committed simulated time**, not a fictitious halfway date unless explicitly marked as an animation in progress.

Do not run a hidden full-resolution atmosphere behind the fast-forward just to make clouds move.

---

## 8. Mode switching and preservation of state

The engine switch is the most important correctness boundary.

### Detailed → climate

At a completed detailed physical step:

1. Stop scheduling new detailed steps.
2. Complete or explicitly retain every already-computed rainfall/runoff/evaporation flux, including hydrology's pending daily interval.
3. Capture the authoritative world time and needed atmospheric moisture and thermal values.
4. Initialize the fast-mode effective atmospheric reservoir from the actual detailed vapor + cloud (conservatively mapped); do not use an arbitrary default moisture pool.
5. Preserve soil, ocean, lake, ecology state and climate history without copying them into independently mutable duplicate stores.
6. Seed slow anomaly/representative fields consistently with current season and available climate history.
7. Begin approximate advancement only after this handoff is committed.

**Do not reset/recreate `World`, `HydrologyTopology`, `HydrologySimulation`, or the slow soil arrays simply to change time fidelity.**

### Climate → detailed

After months/years of coarse advancement, the original detailed atmosphere's old wind, vapor/cloud and temperature patterns are stale. It is **not** correct to resume those untouched arrays at the new date.

Instead:

1. Finish the current coarse climate interval; support an end-of-interval partial update if necessary.
2. Preserve the updated soil, ocean, lake, surface thermal, biome and climate-history state.
3. Transfer the climate effective atmospheric moisture back into vapor/cloud inventories without changing total atmospheric water. Keep cloud water nonnegative and temperature/RH physically compatible; record the partition choice.
4. Construct/reinitialize a **numerically balanced** detailed atmospheric state for the current season. It may use a simple latitude-dependent mean circulation, existing stable initializer, and modest seeded perturbations. Avoid abruptly initializing strong unbalanced pressure/wind gradients.
5. Set the detailed radiation/seasonal forcing's absolute world-time epoch correctly; no restart at year zero.
6. Reattach the **same slow reservoirs**, not new defaults.
7. Run a bounded spin-in/re-equilibration if needed for numerical stability. The duration and cost must be explicit and must not freeze the UI or double-advance the slow world.
8. Publish the new resolved-weather snapshot and clear the representative-weather label only when resolved integration has resumed.

Exact continuation of a pre-fast-forward storm is explicitly **not required**. Continuity of conserved water and slow-world evolution **is required**.

### Avoid duplicating the solver state unnecessarily

Prefer a narrow `restoreInitialState(...)`/`initializeFromClimate(...)` entry point where the current `MoistAtmosphere`/`DryAtmosphere` construction can support it safely. Resist implementing a general persistence/serialization framework solely for speed switching.

### Special lifecycle cases

Specify and implement safe behavior for:

- Pausing during a climate chunk or a detailed batch.
- Changing speeds repeatedly near the fidelity threshold.
- Restarting terrain/world while a worker still owns the old generation.
- Switching prescribed versus dynamical weather (the existing M key currently *does* rebuild the world; changing time fidelity must **not** do this).
- Minimize/unfocus or OS suspension for many wall seconds.
- Quitting while a worker is computing.
- User selection/inspection while background simulation updates the world.

World changes should publish a new generation ID. Old pending commands/snapshots must never apply to the new generation.

---

## 9. Fast-mode weather, seasons, and visible time

### Two clocks for different purposes

Maintain:

1. **Authoritative world time** — what the physics, climate, ecology, and calendar have actually completed.
2. **Display animation phase** — a bounded, purely visual clock for interpolating clouds/texture when the calendar advances by months per frame.

Visual animation never changes hydrological fluxes or the physical atmospheric mass.

### Prevent fast-mode temporal aliasing

At month/year/s, painting the instantaneous sun every redraw can make day/night flicker uncontrollably or even appear stationary because many days have elapsed between frames.

Suggested behavior:

- Detailed mode: real computed sun and day/night shading.
- Moderate climate mode: representative cloud/rain/temperature fields at the accelerated interval.
- Extreme climate mode: emphasize **seasonal lighting and landscape evolution**, not rapidly strobing day/night transitions.
- Keep the selected weather overlay visible but label statistics as interval mean/total/representative.
- If showing a sun or cloud animation unrelated to the completed astronomical day phase, mark it as an illustrative visual effect.

### Cloud rendering in fast mode

The current `CloudRenderer` advects procedural texture using differences in `weather.elapsedSeconds` and a bounded single pass. If simulated years elapse between two render updates, raw time jumps will destroy temporal coherence or repeatedly renew the same texture.

Give fast mode a **visual-effective time increment** (for presentation only) and drive the cloud detail from the representative climate/wind fields, not from a giant physical time delta.

At decade speeds, clouds can be replaced with a slower seasonal illustration or hidden behind a climate overlay while the landscape evolves. Avoid wasting CPU trying to animate physically nonexistent minute-by-minute storms.

### Inspector and legends

The inspector must never show a 10-year precipitation total as if it were a “last 20-second physics step.”

Include:

- Simulated date/year.
- Regime: `Detailed weather` or `Approximate climate`.
- Effective climate integration interval.
- For rain: amount and period, mean rate if calculated, and whether an extreme statistic is inferred.
- Whether current cloud/wind is resolved or representative.
- Actual selected and achieved simulation rates when the system is overloaded.

Keep the normal UI compact; the detailed diagnostic ledger can be opt-in.

---

## 10. Accuracy contracts by timescale

The implementation must not promise identical outputs across unlike time modes. Define what is preserved in each.

| Property | Detailed | Moderate climate | Extreme multi-year climate |
|---|---|---|---|
| Atmospheric mass/flow solver | Integrated | Not resolved | Not resolved |
| Individual storm trajectories | Actual model | Representative only | Not represented |
| Vapor/cloud mass | Conserved in model | Effective pool, accounted | Effective pool, accounted |
| Exact rain timing | Based on resolved integration | Statistical approximation | Aggregated moments/expectations |
| Rainfall total | Physical model result | Approximate, water-backed | Approximate, water-backed |
| Fine soil/lake/ocean stores | Authoritative | Authoritative | Authoritative (analytically advanced) |
| Short flood peaks | Approximate by existing hydro | Event-based proxy | Statistical/aggregate only |
| Seasons | Exact chosen solar forcing | Seasonally driven | Seasonal aggregates |
| Climate statistics | Measured from detailed steps | Derived from completed coarse intervals | Derived from weighted coarse summaries |
| Biome persistence | Actual elapsed conditions | Estimated through intervals | Conservative aggregated duration |
| Rendered clouds | Physics-driven | Representative | Representative/seasonal |
| Reproducibility | Fixed seed + identical physical step schedule | Seed + same coarse schedule | Seed + same coarse schedule |

**Do not** compare a coarse-simulation storm's position to detailed-mode storm position after months and mark the approximation defective. Compare **climate and landscape distributions**, water budgets, and visual usefulness.

### Model fidelity calibration

Use the current detailed model as an internal reference over manageable periods (e.g. weeks and months), then compare:

- Rainfall climatological mean, variability and seasonal contrast.
- Length and frequency of wet/dry periods.
- Soil-water distribution.
- Catchment runoff and lake filling/drying.
- Ocean water trend.
- Temperature and wet/dry geographical patterns.

Allow larger relative errors in rare event timing, but do not accept climate mode producing an entirely different biome geography without a documented reason.

At long horizons, absolute detailed comparison may be computationally impractical. Use basic physical budgets, boundedness, spatial coherence, statistical plausibility, and representative short-window comparisons rather than demanding a 100-year detailed baseline.

---

## 11. Observability, acceptance gates, and performance evaluation

### Frame-rate gates

On explicitly identified capable hardware with an actual display/renderer configuration that allows ≥100 FPS:

- **Target**: rendered p50/p95 frame time ≤10 ms at all implemented time presets during ordinary map interaction.
- **Spike guardrail**: aim for p99 ≤16.7 ms, no repeated >33 ms stalls from simulation or render-update work.
- Pan/zoom/control input stays responsive during background simulation.
- One climate year or one expensive overlay update must never block the render thread for its entire CPU cost.

If the monitor is capped below 100 Hz or VSync enforces a slower rate, report the display cap and focus on avoiding added frame stalls. Do not claim 100 FPS from update-callback counts alone.

### Simulation-throughput gates

Measure requested and achieved **simulated days per wall second** separately, for:

- Realtime.
- 1 hour/s.
- 12 hours/s.
- 1 day/s.
- 1 week/s.
- 1 month/s.
- Optional 1 year/s, 10 years/s and 100 years/s.

At each speed, record:

- Actual simulation regime.
- Effective coarse quantum.
- Completed work vs requested progression.
- Worker CPU utilization and time per batch.
- Snapshot publication frequency and cost.
- Frame p50/p95/p99 and worst stalls.
- Allocation/GC spikes where observable.
- Time to react to pause/speed change/regeneration.

**No unverified 100 years/s promise.** It is a stretch target. If multi-year analytical advancement cannot meet it on the reference machine, report the measured achievable rate and the specific bottleneck without sacrificing responsiveness or physical bookkeeping.

### Water-conservation gates

For a closed-world climate run, after a completed interval:

`Δ(atmospheric moisture + soil + ocean + lakes + pending water) ≈ 0`

Track the residual both absolutely and relative to relevant total/circulated water. Floating-point roundoff is acceptable; unrecorded systematic gains/losses are not.

Before the weather-quality gate, verify:

- No negative soil, lake, ocean, vapor/cloud/effective atmosphere storage.
- No double-credited rain or evaporation.
- No re-routing the same pending flux on speed switch.
- No water lost at map wrap or polar boundary.
- No accidental reset of old ecological state.

Do not hide errors with mass-correction hacks.

### State-transition gates

Verify sequences such as:

- Detailed → 12h/s climate → detailed at current season.
- Detailed → month/s → week/s → month/s → detailed.
- Fast-forward multiple years → pause → inspect lake/biome → resume.
- Fast-forward 10–100 years (as affordable) → detailed and continue stable atmospheric integration.
- Terrain regeneration during active fast-forward.
- Same seed, same speed-command schedule, same final authoritative state (within the defined platform/determinism contract).
- No events applied to a world generation that is already replaced.

### Climate-quality gates

Compare with detailed reference and geography-aware expectations:

- Seasonal wet/dry cycle should persist.
- Ocean/land thermal contrasts remain.
- Arid regions do not all become uniformly wet from synthetic precipitation.
- Rivers and lakes react to intermittency rather than only annual totals.
- Biomes require sufficient persistent evidence and never flip on a single arbitrary climate batch.
- Long runs do not inexorably empty the ocean, fill all lakes, or drain all soils merely because of the time-stepping approximation.

Do not invent strict universal percentage thresholds before looking at the existing model's climate variability; establish measured tolerances from short reference runs and physical invariants.

---

## 12. Step-by-step implementation sequence

**Agent contract:** Implement in small coherent increments; each selected step should leave Terra runnable. Prefer one phase/substep per invocation if that is how the agent is orchestrated. Update checkboxes and a short implementation log. Reinspect the repository before each step, because Phase 6 and other code may continue changing.

### [x] Step 0 — Phase 6 reconciliation and minimal baseline

- Read the final Phase 6 code and climate/biome contracts. Confirm `SeasonalClimate` and `World.advanceClimateBiomes` are stable and which initial warm-up rules are intended.
- Measure current p50/p95/p99 frame time at all existing presets with and without cloud overlay; measure actual completed weather throughput.
- Separate CPU costs of detailed integration, climate observation, hydrology, biome scans, snapshots, cloud bitmap generation, GPU uploads.
- Report the actual hardware and display/VSync configuration.
- Make only essential instrumentation changes; avoid reviving the former large experimental-report infrastructure.

**Done when:** There is a concise, trustworthy throughput/frame baseline, and current Phase 6 interfaces are documented for Phase 7.

### [x] Step 1 — Centralize world clock, ownership and scheduled work

- Establish a single authoritative time owner and an explicit selected vs achieved rate.
- Move numerical advancement out of the KorGE render frame's synchronous call path, preferably onto one JVM simulation worker.
- Make simulation commands, generation changes and pause/resume safe.
- Keep worker work and backlog bounded; no unbounded catch-up loop.
- Preserve existing detailed weather and hydrology behavior as much as possible.
- Ensure scene rendering observes only complete snapshots.

**Done when:** Running the existing detailed weather no longer blocks the render thread; the game can draw/input while simulation lags, and reports the lag honestly.

### [x] Step 2 — Snapshot and renderer budget

- Publish coarse physics fields and climate/hydrology/biome changes at independent bounded frequencies.
- Avoid full cloud rebuild and terrain redraw on every simulation batch.
- Keep GPU uploads and KorGE UI mutations on the renderer thread.
- Implement small dirty tile updates or amortized raster publication if measurements show a need.
- Preserve visual temporal coherence with render-only interpolation.

**Done when:** The renderer remains responsive during frequent detailed steps and abrupt hydrology/biome changes, with no major CPU spikes from snapshot publishing.

### [x] Step 3 — Shared slow-state and bulk flux interface

- Identify the minimal safe way for detailed and climate mode to access the same soil/ocean/lake/thermal state.
- Add a typed, unit-explicit batch flux handoff usable by climate mode and reconcile it with the existing `WeatherModel` handoff.
- Record/preserve pending runoff and lake water and all authoritative water stores.
- Do not introduce a second soil/ocean implementation.
- Make whole-interval climate-stat ingestion possible without per-20s observations.

**Done when:** A synthetic **small, controlled** climate interval can be applied to the existing terrain/hydrology and reconciles water balances; no independent world state is created.

### [x] Step 4 — Minimal climate normals and anomaly field

- Build a seasonal climate prior from available physical/geographical information for immediate fast-forward on new worlds.
- Consume the Phase 6 observed seasonal statistics when available and blend them appropriately.
- Introduce only a few deterministic spatially correlated warm/cold and wet/dry anomaly fields.
- Preserve longitude wrap and broad geographic wet/dry gradients.
- Do not build full pressure/wind dynamics for the climate mode.

**Done when:** Climate estimates are nonuniform, seasonally sensible, reproducible and available without years of detailed spin-up.

### [x] Step 5 — First conservative fast-climate interval

- Add `FastClimateStepper` or equivalently small climate-mode implementation.
- Implement evaporation→effective atmospheric moisture→rain→soil/lake/ocean transfers for a short interval, ensuring exact water ownership.
- Advance surface temperature with a stable, documented slow relaxation/energy approximation.
- Create moderate-speed representative wet/dry events, rather than uniform rain per interval.
- Publish explicit aggregated weather-field semantics.

**Done when:** The model can advance a day or week without any detailed 20s fluid steps, preserving water and plausible surface behavior.

### [x] Step 6 — Event-sensitive coarse runoff and lakes

- Implement fine-grid soil bucket response over representative wet/dry phases using bounded work.
- Create a hydrology coarse routing method without daily full-grid routing for long intervals.
- Integrate lake capacity, spill, evaporation and ocean returns correctly.
- Make last-interval discharge and rainfall metadata truthful.
- Compare short coarse reference intervals against detailed hydrology under similar forcing.

**Done when:** Fast climate produces streams, wet/dry soil, and lake responses without losing/creating water or using 365 individual hydrology updates per year.

### [x] Step 7 — Seamless detailed ↔ climate transitions

- Implement the forward transfer at a clean completed-step boundary.
- Preserve shared slow reservoirs, climate history and calendar.
- Add a narrow way to initialize the detailed solver from present season, effective moisture and balanced mean flow after coarse time.
- Handle epoch/time offsets correctly and avoid double-applying surface fluxes.
- Rebuild resolved weather without resetting the world.
- Provide a short nonblocking visual handoff/quality label.

**Done when:** Multi-year fast-forward can return to stable detailed weather with the same terrain, soil, ocean, lakes, climate history and biome state.

### [x] Step 8 — Adaptive temporal quanta and extreme rates

- Implement supported climate quanta from hours/days to monthly, annual and multi-year.
- Keep the number of worker operations per wall second bounded.
- Use analytic or aggregate updates for slow soil/lake/biome state when skipping years.
- Preserve seasonal effects and rainfall-extreme influence without generating every day.
- Add measured quality hysteresis and overload fallback.
- Add optional 1yr/s, 10yr/s and 100yr/s controls only when behavior is sufficiently defined.

**Done when:** Higher selected rates increase approximate modeled time substantially without proportional increase in tiny physical steps, and the renderer stays responsive.

### [x] Step 9 — Fast-mode visual presentation and honest UI

- Add regime indicator and requested/achieved speed indicators without clutter.
- Use representative climate/cloud rendering with separate bounded visual animation time.
- Avoid day/night strobing at extreme speeds.
- Make rain, cloud, climate and flow labels correctly describe their integration period.
- Animate ecological/map changes smoothly without changing committed physical state.

**Done when:** Speed changes look smooth and intentional; no misleading “actual rainstorm now” claims in coarse climate mode.

### [x] Step 10 — Performance and scientific acceptance (bounded review; limits recorded)

- Measure all presets on an identified capable machine and monitor.
- Evaluate FPS with realistic cloud/hydrology/biome updates, not an empty static screen.
- Assess worker headroom, GC, snapshot churn, terrain rebuild cost and pause latency.
- Compare short climate-vs-detailed runs statistically and hydrologically.
- Run longer coarse world evolution to check ecological transitions and water drift.
- Remove unnecessary instrumentation and obsolete duplicate code.
- Explicitly document the achievable top speed and approximation limits.

**Done when:** Responsiveness, water ownership, mode transitions, and credible climate evolution are demonstrated; remaining limits are stated rather than hidden.

---

## 13. Compact target interface (illustrative, not a prescription)

Use the smallest structure consistent with existing `WeatherModel`/`DynamicalWeather` APIs.

```kotlin
// Shape only; rename/merge as appropriate for Terra.
enum class Fidelity { DETAILED, CLIMATE }

data class SimulationProgress(
    val completedSimulatedSeconds: Double,
    val selectedSimSecondsPerWallSecond: Double,
    val achievedSimSecondsPerWallSecond: Double,
    val fidelity: Fidelity,
    val climateQuantumSeconds: Double,
    val generation: Long,
    val revision: Long,
)

// The worker owns mutable state. Renderer sees only complete snapshots.
interface WorldStepper {
    fun advanceUpTo(simulatedDemandSeconds: Double, workBudgetNanos: Long): SimulationProgress
    fun latestSnapshot(): WorldPresentationSnapshot
}
```

**Caution:** `WorldStepper` might be better represented as one concrete controller plus existing model types. Do not create this interface solely to match the example. Worker budgets should refer to actual wall time and yield between bounded transactions; they must not interrupt a numerical step halfway through an invalid state.

Conceptual main-loop split:

```text
UI/render thread (~100 FPS):
    read latest completed snapshot
    update camera/input + cheap interpolated animation
    upload only due/changed visual data
    render

Simulation worker (bounded, independent):
    accept desired speed, pause, and world-generation commands
    accumulate bounded requested simulated time
    select detail or climate model with hysteresis
    choose an affordable time quantum
    advance one complete transaction
    update water/season/ecology as appropriate
    publish a bounded immutable snapshot
    yield / throttle / repeat
```

There must be no hidden time advancement inside `CloudRenderer`, texture updates, or scene UI.

---

## 14. Explicit non-goals

- No spherical atmosphere or variable-area world grid.
- No second shallow-water solver at a different resolution by default.
- No attempt to retain exact individual storms across multi-year fast-forward.
- No detailed ocean circulation, ice microphysics, full ecological food webs, or new erosion/flood solver.
- No massive high-frequency snapshot history or event log.
- No per-tile random rainfall each frame.
- No unbounded “process all pending time” loop on the render thread.
- No false frame-rate counters or claims that GPU/VSync limits can be bypassed.
- No reset of long-lived soil/lake/ocean/ecology state when changing fidelity.
- No silent atmospheric water creation or numerical mass correction to hide losses.
- No promise of exact cross-fidelity deterministic equivalence.

---

## 15. Risk register and decisions the implementer should revisit

| Risk | Impact | Preferred mitigation |
|---|---|---|
| Render thread still does large cloud/terrain work | 100 FPS fails despite background physics | Measure and budget bitmap synthesis/upload and dirty tile updates separately. |
| New worker creates data races in `World`/hydrology | Corruption, nondeterminism | Single owner, immutable snapshots, generation/revision IDs. |
| New climate water model duplicates ocean/soil | Water discontinuity on switch | Shared reservoir ownership, conservative transfer contract. |
| Huge climate quanta erase hydrological extremes | Dry lakes or constant rivers | Representative event moments, seasonal subphases, bounded nonlinear bucket response. |
| Fast climate depends on years of prior detailed simulation | High-speed unusable on new worlds | Immediate provisional physically based prior with observable confidence. |
| Multi-year ecology wrongly assumes unchanged climate | Unrealistic instantaneous biome change | Integrated climate evidence and conservative persistence timers. |
| Returning to detailed weather reuses stale storms | Invalid fields, unstable solver | Balanced reinitialization at new absolute time, conserve moisture, preserve surface. |
| Cloud/night rendering aliases when time races | Flicker, frozen cloud pattern | Separate visual animation time; aggregate seasonal presentation. |
| Exact climate speed cannot match selected rate | User expectation conflict | Time coarsening, bounded work, honest achieved-speed indicator; FPS wins. |
| Excessive abstract classes/subsystems | Maintenance burden | Two modes, one climate model, primitive arrays, few adapters, remove unused abstractions. |
| Agent concurrently changes Phase 6 climate API | Lost work/conflicts | Reinspect latest source and reconcile before editing. |

### Questions the agent may decide with evidence

1. Is a separate JVM worker actually required to meet ~100 FPS, or can the current loop be safely kept inside a strict, measured small per-frame budget? **Default: worker.**
2. Should coarse climate operate on the existing 96×72 grid or a 24×18/32×24 grid? **Default: reuse 96×72 seasonal statistics until measurement proves smaller is needed.**
3. Can Phase 6's `SeasonalClimate` be extended for bulk intervals, or should a small independent climate-statistics owner be extracted? **Default: extend existing contract.**
4. Which aggregate soil/lake equations retain enough wet/dry dynamics at 10–100 years/s? Choose measured/simple analytic approximations over per-day loops.
5. Is a short climatological spin-in on returning to detailed necessary? If yes, keep it bounded and off the render thread; avoid double-updating slow stores.

---

## 16. Research pointers (inspiration, not dependencies)

- Glenn Fiedler, *Fix Your Timestep!* — fixed-step simulation, render decoupling, and spiral-of-death control: https://gafferongames.com/post/fix_your_timestep/
- Richardson (1981), *Stochastic simulation of daily precipitation, temperature, and solar radiation* — economical seasonal wet/dry weather generation: https://doi.org/10.1029/WR017i001p00182
- Extended WGEN/Richardson weather generator — first-order wet/dry persistence and skewed rainfall amounts: https://journals.ametsoc.org/view/journals/apme/39/5/1520-0450-39.5.610.xml
- ETH Zurich AWE-GEN-2d — precedent for spatially structured stochastic/physical climate forcing: https://emeritus.hyd.ifu.ethz.ch/research-data-models/awe-gen-2d.html
- Terra's own `PLAN.md`, Phase 6, `docs/MOIST_ATMOSPHERE.md`, and existing atmosphere/hydrology source are the primary integration references.

Do not implement these published models wholesale. Borrow only the small ideas Terra needs.

---

## 17. Agent completion protocol

For each selected step:

1. Re-read current relevant source and detect newly implemented/concurrent features.
2. Follow the smallest coherent implementation consistent with this plan's invariants.
3. Compile and use focused numerical/performance checks where they materially establish correctness. Avoid sprawling new infrastructure.
4. Update the relevant checkbox and append **one concise log entry** below.
5. State actual outcomes versus planned estimates; do not claim 100 FPS or 100 years/s without measurement.
6. Explain any justified departure from this document.
7. Stop at a coherent implementation boundary. Do not start several unrequested subsystems at once.

### Implementation log

| Step | State | Changed files | Key decisions and verified observations |
|---|---|---|---|
| 0 | Complete, exploratory baseline | `PerformanceProbe.kt`, `DesktopPerformanceProbe.kt`, timing seams in scene/weather/map, desktop/build opt-in, `docs/PHASE_7_BASELINE.md` | Reconciled finished Phase 6 working tree. One ~30-second live pass, all six presets with clouds off/on; 120 Hz display/VSync on. Month/s achieved 1.45 h/s with p95 44.30 ms; detailed step mean 2.083 ms; cloud update mean 11.830 ms. Compile/live checks passed. Mature biome scans and GPU/upload cost not isolated; short trials are not final acceptance. Proceed next with Step 1 worker/clock ownership; no worker or climate engine is implemented yet. |
| 1 | Complete | `WorldSimulation`/JVM `SimulationWorker`, immutable world/weather/hydrology views, scene/map/cloud adapters, probe, focused tests, `docs/PHASE_7_WORKER.md` | One owner/clock, bounded demand with explicit unmet-time accounting, coalesced controls, generation/revision guards, latest-only immutable snapshots. No numerical advancement on render thread. Full 35 tests passed; focused worker checks rerun after adjustments. One short desktop pass: month/s cloud-on p95 10.04 ms vs baseline 44.30 ms, p99 20.82 ms, actual 1.79 h/s. Cloud spikes and snapshot-copy cost remain Step 2; no fast climate or 100 FPS guarantee. |
| 2 | Complete | Coarse `DynamicalWeatherPresentation`, `WorldSimulation`/worker publication, CPU `RasterFrame`/cloud/water generation, dirty world snapshots, map/scene adapters, focused checks, `docs/PHASE_7_RENDERER.md` | Retain coarse atmosphere and copy seven fine fields; independent 0.5 s hydrology views, revision-based biome views, hidden-cloud suppression. Worker generates frozen cloud/water pixels; renderer owns bitmap/GPU work, 120 ms cloud crossfade, ≤1 other overlay/frame and ≤1,024 queued terrain tiles/frame. Compile + all 37 checks passed; one short desktop pass: month/s cloud-on p95 10.06 ms, p99 10.17 ms vs Step 1 20.82 ms; cloud copy max 0.117 ms across cases. Actual speed 1.59 h/s vs 1.79; cloud cost moved to worker. Small abrupt-change fixtures, no mature full-size stress run or GPU isolation; no 100 FPS guarantee. Step 3 remains pending. |
| 3 | Complete | `WaterFluxBatch`/`HydrologyExchange`, shared hydrology handoff/inventory, `TerrainMoistSurface` and weather adapters, `SurfaceThermalState`, `ClimateIntervalSummary`/`SeasonalClimate`, focused checks, `docs/PHASE_7_SHARED_STATE.md` | Reuse existing soil/ocean/lake/pending stores and the same thermal array/history; one-use unit-explicit batches, guarded borrowed detailed buffers, copied bulk inputs, existing routing cadence. Integrated climate history accepts contiguous intervals with extrema and unchanged spin-up/coverage rules. Compile + 16 affected checks passed (~1.25 s test time); targeted guards rerun after final validation changes. One 10×8 synthetic 12 h interval preserves a pre-existing lake and 6 h pending runoff, closes water within 1e-9 mm·tiles, then routes the combined day and resumes the same detailed owner. No desktop/sweep/new long run; no fast climate, energy correction or calendar switch introduced. Step 4 remains pending. |
| 4 | Complete | `ClimateNormals`/immutable estimates, `ClimateAnomalies`, existing relief access, seasonal rain-rate accessor, lazy `DynamicalWeather` integration, `ClimateNormalsTest`, `docs/PHASE_7_CLIMATE_NORMALS.md` | Immediate provisional four-season geography/solar prior on the existing coarse grid; observed temperature/rain blends at 40%/80% after one/two post-spin-up years, preserving source metadata and proper season-duration units. Two bounded, seed/month-driven smooth regional anomaly fields with longitude wrap and no skipped-month iteration. Compile + 7 focused checks passed (~0.055 s test time); final timestamp naming compiled. Small fixtures establish seasons, geographic contrasts, units, orbital offset, continuity/reproducibility and unchanged stores/clocks; year-1000 queries are random access, not integrations. No spin-up, sweep or desktop benchmark. Rain is still demand; Step 5 conservative evolution remains pending. |
| 5 | Complete | `FastClimateStepper`/interval diagnostics, existing column-moisture access, owner/dynamical entry and guards, representative field semantics/adapters/scene labels, `FastClimateStepperTest`, `docs/PHASE_7_FAST_INTERVAL.md` | Reuse existing column vapor/cloud as the effective pool: finite-store evaporation, global proportional moisture allocation, funded regional wet/dry rain, two typed surface/hydrology subperiods and shared history. Stable land/ocean thermal relaxation is explicitly approximate, without latent-energy closure or residual correction. Owner commits the calendar and publishes frozen integrated totals/means and representative peaks/clouds. Compile + 11 focused checks passed (~0.67 s test time): tiny-grid day/week retain one detailed step and close water within 1e-12 relative error; exhausted world accepts zero rain; fixed schedules reproduce. No sweep, desktop or long run. Explicit owner entry only; existing overshoot routing and detailed re-entry limits are documented. Step 6 remains pending. |
| 6 | Complete | `TerrainMoistSurface` constant-forcing bucket/queued rain, climate routing/aggregate throughput and hydrology snapshot metadata, fast rain-event summaries/scene labels, `CoarseHydrologyTest`, extended interval checks, `docs/PHASE_7_COARSE_HYDROLOGY.md` | Analytical rain-plus-drainage and saturation timing replace pulse-then-dry treatment. Route each representative phase once, retain antecedent pending water/time, and publish summed interval throughput/rate without moving water again. Wet fraction/intense-rain diagnostics are explicit. Compile + 15 affected checks passed across focused runs; corrected same-size catchment fixture and six checks rerun. One 96-tile ten-day reference: soil error 0.5382 mm, outlet error 0.9391%, 1 vs 10 routing passes. Concentrated rain increases overflow; seam lake fill/spill/dry and long monotone segments conserve water with one pass each. No sweep/desktop/long atmosphere run; one-week engine cap remains until adaptive quanta. Step 7 handoff remains pending. |
| 7 | Complete | Narrow dry/moist rest initialization, absolute radiation epoch and handoff/energy provenance, dynamical/owner transition state, coalesced worker fidelity and publication revisions, F key/status, `FidelityTransitionTest`, `docs/PHASE_7_TRANSITIONS.md` | In-place balanced rest initialization preserves per-column water and shared slow stores/history/calendar; 90% saturation vapor cap with excess cloud, explicit initialization-energy delta, no hidden spin-in or energy-reference reset. Keep representative fields until a real detailed step; paused commands publish correct fidelity revisions. F switches fidelity without regeneration, with fixed one-day climate quanta; automatic policy is deferred. Compile + 11 focused checks passed (~1.36 s): one tiny 3.28-year coarse schedule returns for 100 detailed steps, water residual 3.4593e-15, preserved stores/history/date and current-season views. Worker pause/coalescing/same-generation checks pass. No sweep, full-suite or desktop run. Step 8 remains pending. |
| 8 | Complete | `AdaptiveFidelity`, worker/owner quantum policy and diagnostics, `ClimatePhasePlan`, analytic anomaly means, aggregate seasonal history/estimated flags, concentrated rain integration, year-rate controls/probe filters, focused tests, `docs/PHASE_7_ADAPTIVE.md` | Automatic measured selection/hysteresis, manual F and automatic A, nominal hour-to-year quanta with load coarsening to 20 years, ≤20 climate starts/s and bounded backlog. Calendar-aligned phases through one year; eight grouped seasonal phases beyond it, analytic history/anomaly skipping and conservative biome deferral. Compile + 19 distinct targeted checks passed across focused runs; 20-year tiny-grid interval uses eight routes and closes water at -1.9218e-15. One ~12 s selected desktop pass, clouds on: actual 1.06 and 9.64 years/s for year/10-year requests, p99 10.24/13.16 ms. Final ecology-credit restriction checked without another benchmark. Aggregate climate/storage fidelity and 100 FPS are not guaranteed; 100-years/s preset withheld. Step 9 remains pending. |
| 9 | Complete | `SeasonalLighting`/solar daily-mean helper, bounded representative cloud clock, shared render-layer/water crossfades, snapshot solar/period metadata, `WeatherDisplayText` and scene/inspector labels, focused checks/daylight smoke, `docs/PHASE_7_VISUALS.md` | Day-averaged seasonal climate lighting replaces jumping night masks; representative cloud texture uses bounded real visual increments and labeled illustrative drift. Completed weather/water rasters fade over 180 ms; identical rasters skip fading and terrain retains bounded progressive updates. Committed dates/rates/periods, proxy/inferred fields and water-view age are explicit; status clicks no longer reset the world. Compile + 7 focused checks passed (~1.1 s). Two-case daylight/cloud smoke found a 9.401 ms lighting redraw; only the optimized fast case rerun, with row-fill redraw peak 0.068 ms and frame p99 14.25 ms. No broad matrix/full suite/manual visual assessment; final acceptance remains Step 10. |
| 10 | Complete, bounded review with limits | `Phase7AcceptanceTest`, optional desktop GC/heap/publication counters, generic overlay cleanup, `docs/PHASE_7_ACCEPTANCE.md` | Compile + 8 focused checks passed (~1.35 s); one ~20 s all-eight-preset cloud/light desktop pass. Top preset achieved 9.48 years/s, p99 17.32 ms; strict 100 FPS target not met. Worker timed share ~10–43%, publication ~4–5/s, queued terrain peak 0.225 ms; decade case 16 collections/25 ms GC, heap endpoint 694 MB. One full-size pause acknowledgement 62.49 ms. Tiny 20-year monthly evolution changes 6 biomes, total relative water drift -8.840e-15; existing aggregate/reentry checks pass. Short day comparison temperature RMSE 0.424°C but rain 0.00859 vs 2.98311 mm: no rainfall equivalence/calibration claim. No full suite/sweep/manual interaction/long memory run; 100-years/s remains withheld. |

---

## Final engineering principle

**Render frequently. Simulate only the detail that time scale can support. Preserve the world.**

At low speed, the player should see actual fluid dynamics and emergent storms. At high speed, the player should see a living planet changing through seasons, drought, rainfall, rivers, lakes and ecosystems—not a frozen game laboring through millions of unnecessary 20-second steps.

The goal is not to make detailed weather artificially fast. The goal is to make **time itself scalable** without sacrificing the world that time is supposed to change.
