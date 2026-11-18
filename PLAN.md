# PLAN — Terra tectonics: persistent land, coherent boundaries, quieter plates

## Goal

Make Terra's tectonics **more believable over hundreds of millions of years** without adding a full mantle or continuum-mechanics solver. Preserve genuinely evolving geography, conservation, responsiveness, and simple code. **Split and merge plates less frequently**, for geological reasons rather than population management.

**Starting point:** `main` at `3713b2a` (17 November 2026). Read `AGENTS.md`, `TECTONIS.md` (currently misspelled), and the relevant code before changing it. The source is authoritative. `TECTONIS.md` describes commit `a7e0ddf`; the newer commit only replaces the documentation.

**Known failure:** in the documented default-size coupled run at 500 Myr, exposed land fell **16.29% → 7.62%** while continental inventory rose **11.8%**. Disabling erosion worsened land loss (**4.42%**); disabling local capture left **18.86%** land. Capture plus remapping is the primary suspect, **not** a proven sole cause. Do not target a particular land percentage.

## Rules for the implementation agent

- Follow `AGENTS.md`: minimal code, no speculative abstractions, delete replaced/dead paths, terse communication, verify silently unless failing.
- Implement **one numbered stage at a time**; build and run its focused tests before advancing. Keep changes reviewable. Do not mix physics retuning with a storage rewrite.
- Prioritize **material and geometry correctness → boundary physics → lifecycle → tuning/visual quality**. Keep existing working UI, climate, hydrology, toroidal world, live controls, chronological geological credits, and single-owner simulation worker.
- A disposable raster may approximate geometry, but it must **never become the persistent source of material**. Every deliberate addition/removal must be reflected in the mantle/material ledger; do not create or delete rock just to fix land coverage.
- Do **not** preserve a fixed land fraction, paint elevation, disable erosion, introduce a mantle solver, implement a full fluid/thermal solver, or add dependencies without demonstrated need.
- Record measurements, not just a passing mass test. Stop and fix an unsuccessful stage instead of compensating with unrelated heuristics.

## Invariants and diagnostics

Maintain these throughout:

1. **Inventory:** `rockMass ≈ initialMass + mantleAddedMass − mantleRecycledMass` (relative error < `1e-8` for supported runs); component/provenance accounting is equally explicit.
2. **Transport:** rigid motion, plate-frame changes, capture, and welding **alone** conserve each material inventory, area, weighted age/weakness/relief, and *world position* within floating-point tolerance. No repeated-transfer erosion/dilution of permanent columns.
3. **Geometry:** both axes wrap; no NaN/negative inventories; ownership is connected *for display* without inventing material; no published inactive owner.
4. **Dynamics:** deformation is caused by resolved convergence, divergence, or erosion—not by changing territory labels. Unresolved flux is diagnosed, never silently treated as physical subduction.
5. **Scheduling:** all elapsed geological time is processed; pause, playback speed, regeneration, and historical multiplier credits behave as before.
6. **Reproducibility:** same seed and settings yield identical output; public snapshots are immutable.

Keep diagnostics internal/test-only initially; avoid a new runtime dashboard. At snapshots or diagnostic checkpoints record:

- Effective Myr; land fraction; elevation histogram/hypsometry; p10/p50/p90 continental **thickness** (`continent / area`, with a meaningful area threshold); continental inventory; sediment/basalt inventories.
- Distribution of per-world-tile `totalArea` (empty/undercovered/overcovered), coverage absent from coherent owner, and capture transfer count/volume.
- Physical subducted/shortened/spread area versus requested boundary flux and unresolved remainder; mass-ledger error.
- Plate count, RMS relative speed, split/weld counts and timestamps; geological steps/substeps and elapsed work time.

## Stage 0 — Reproduce and isolate the problem

**Change only diagnostics and focused fixtures.**

1. Reproduce the documented default seed `31742`, 283×212, terrain defaults, ×10,000, 100-calendar-year geology interval, **normal climate/water coupling**. Record land and thickness/coverage metrics at 0, 25, 100, 300, 500 Myr. If full coupling is too costly, keep a separate reduced/uniform-rain *diagnostic*, clearly labeled; never substitute it for the baseline.
2. Add a small deterministic **repeated-frame-transfer test**: sharp continental/oceanic contacts, no tectonic deformation or erosion; move the same material between translated/rotated plate frames 100 times, including seam crossings; return to its initial world positions. Compare all inventories, area, weighted properties, parcel positions, thickness histogram, and shoreline. The current implementation is expected to expose persistent smoothing.
3. Record split/weld frequency and causes over the baseline (ordinary, population consolidation, idle/watchdog). Measure fresh-world and late-world geological-tick cost.

**Gate:** a reproducible capture-diffusion fixture, baseline numbers, and existing `gradle jvmTest` passing. Do not tune geology yet.

## Stage 1 — Make captured material persistent in space

**Principal architectural change.** Keep the existing physical rules in place to isolate its effect.

1. Represent each `CrustCell` as a **material parcel with continuous, plate-local position** (prefer km offsets from the plate pose). Keep its `area`, rock volumes, age, sediment provenance, relief, and weakness as **authoritative extensive/weighted data**. Use a simple per-plate collection, not a new object hierarchy. Update centroid/bounds/fracture calculations accordingly.
2. Replace the integer lattice `materialKey`/destination-grid merging dependency. For a transfer or weld: transform the **same parcel** through world coordinates into the receiving plate's local frame, preserving world position and **all** material fields. For a split: move existing parcel references; daughter poses initially coincide. Do not bilinearly repartition permanent material on either operation.
3. Project parcels **forward** to the four nearby world raster centers with bilinear cloud-in-cell weights `w >= 0`, `sum(w) = 1`, wrapping both axes. Project `area`, individual inventories, provenance, `area * age`, `area * weakness`, and prepared support consistently. The projected fields are disposable. A parcel's scattered inventory must sum to its stored inventory. Remove the old inverse `visit()`/per-pose footprint normalization **only once** this passes its regression tests.
4. Gather raster event deltas back to contributing parcels using the **same** weights: area changes proportional to contributed area; component removals proportional to contributed component inventory; positive additions to supported material according to an explicit area rule. Accumulate changes and apply **once per parcel per geological operation**. Protect against overdrawing and report residual amounts instead of hiding them with clamping or a fabricated ledger entry. Actual recycled/additional mass must match the actual applied change.
5. New crust is a new parcel at the opening position. Erosion becomes a real inventory transfer; sediment provenance follows it. `spreadRoots()` may use a small physical-radius spatial bucket if needed—do not use an `O(parcels²)` search. Keep initial object count near the old order of magnitude; measure growth from births.
6. Validate forward projection under **translation and arbitrary permitted rotation**. Particle grids can develop coverage holes/noise even when total mass is conserved. Compare raster error, coastlines, overlap distribution, throughput, and memory before deleting the old path. If four-point scatter is visibly or numerically inadequate, try a minimal **finite-footprint** projection; do not reintroduce permanent resampling or arbitrary “fill” rock.

**Gate:** the 100-transfer fixture retains the original permanent material/thickness distribution and world coordinates (relative position errors at roundoff scale; no cumulative dilution); static and moving raster projections conserve inventories; seam and fracture tests pass; normal evolution still produces finite, plausible terrain. Document new raster aliasing honestly. **If this representation worsens coverage or runtime unacceptably, stop and compare a conservative remap alternative before committing to it.**

## Stage 2 — Separate connected ownership from physical capture

1. Preserve `PlateTopology.repair()` for a connected **territory/display** view, but **never treat an ownership-label change alone as geological deformation or justification to mix rock**.
2. Re-evaluate `captureFragments()`: genuine detached material can be assigned to the neighboring plate without modifying the parcel's rock or position. Before transfer, distinguish (a) material physically crossing/being accreted at a boundary, (b) unresolved collision/subduction, and (c) a tile reassigned only to restore connected display territory. Only (a) warrants a physical membership change; leave (b) for the collision solver and do not mutate material for (c).
3. Continue to allow a small fringe where overlapping plate material remains attached to its source, but measure it in physical space if practical rather than fixed tile rings. Do not force capture just because a zero-coverage tile was given a connected owner.
4. Track true physical capture separately from ownership repair. After any material change, reproject as needed; keep the final published owner connected and active.

**Gate:** an isolated plate moving rigidly through its own territory never changes permanent composition; detached-fragment tests conserve world positions and material; no persistent severe gaps, ghost plate islands, or artificial owner-driven land decline. Compare the full-size baseline with Stage 0 **without** physics retuning.

## Stage 3 — Use one boundary-flux convention for interaction

**Avoid a wholesale solver rewrite. First consolidate duplicated boundary calculations.**

1. Enumerate each distinct neighboring-plate boundary edge once, with plate IDs, center, outward unit normal, physical edge length, local material support, relative normal velocity (including **actual** spin), and divergent/convergent type. Both seams work identically. Use this common geometry for driving-force scans, contact accumulation, classification, and event eligibility; evaluate it at the correct pose rather than silently mixing previous/current interfaces.
2. For edge length `L`, closing/opening speed `v_n` in km/effective year, and `dt`, define requested horizontal area in tile-area units:

   `requestedArea = abs(v_n) * L * dt / (dx * dy)`.

   Convergence consumes/subducts oceanic area or shortens continental area; divergence creates ocean crust or stretches continental material; transform shear creates/removes no area solely from tangential motion.
3. **Interaction amount comes from this flux and available local material**, not mainly from end-of-step overlap. Existing overlap/coverage fields may determine *where* a transfer lands. Track `requested / actually applied / unresolved` quantities. Never resolve a collision by simply transferring an unconsumed continental column to an arbitrary connected owner.
4. Keep the existing inexpensive overdamped basal/ridge/slab drive, but classify **local edge segments** by their own geometry and composition. A pair-wide `Contact` can aggregate history initially; it must not dictate the same subduction polarity across unrelated opposing boundary segments. Add persistent segment history only if required by a reproducible defect.
5. Retain the material-ledger treatment of accretion and arc additions. Account with **applied**, not proposed, material changes. Keep trenches graphical until supported by actual terrain material; do not compensate missing mass with height offsets.

**Gate:** controlled ocean closure builds lasting continental roots; spreading creates crust only at eligible opening boundaries; tangential-only contacts do not generate rock; no boundary flux is silently lost; mass and connectivity regressions pass. Plot timestep sensitivity before and after.

## Stage 4 — Make splitting and merging substantially rarer

**Explicit product requirement:** plate identities should be long-lived; mountain building and continental collisions should often proceed **without welding**. A huge merged plate should not immediately fracture to satisfy a count quota.

1. Delete the **12-Myr idle/watchdog fracture trigger** and the **forced consolidation to free a slot at 10 plates**. Remove the strict 6–10 active-plate requirement from the solver/tests. Start with eight plates; let their number change for physical reasons. Keep an upper resource guard only if measured memory/performance requires it; when hit, defer a split rather than merge unrelated plates.
2. Require **recent, sustained positive extension**, a sufficiently large connected candidate, and a mature rift before splitting. The present lifetime `extension` can remain high long after divergence ends: use a short rolling/relaxing strain memory rather than an ever-growing total. Initial *tunable starting values*, **not empirical Earth laws**: at least **80 Myr** since plate formation/last split; recent `extension >= 0.20` (instead of the current lifetime 0.12); and **30 Myr** since any global split. Keep weakness-guided connected daughter partitions and existing mass-preserving separation. A plate's age/size/idle status **alone** must never split it.
3. Weld only after persistent **continental** contact and significant **recent, actually applied shortening**, not merely 8 Myr of touching or historically accumulated shortening after convergence stops. Initial trial: **30 Myr** continuous meaningful convergence and shortening at least **12%** of the smaller plate's material area, with substantial continental contact length. Avoid welding purely oceanic/transform contacts; merging remains optional even when continents meet. Reset inherited tensile tensor **and** obsolete separation state on weld.
4. Repair lifecycle edge cases: no irreversible consolidation if a planned split fails; no use of slot-reused IDs as permanent ancestry; no stored angular velocity contributing boundary motion when rotation is deliberately not applied by `fits()`. Keep event output `fracture`, `weld`, and reason; remove obsolete watchdog/population counters once unused.
5. Compare event counts and spacing on identical seeds/settings before and after. **Aim for a clear reduction** in split/weld frequency while retaining sustained plate motion and occasional strain-driven rifting. Do **not** enforce a quota of events or artificially hold plate count at eight.
6. If rare merging eventually leaves too few independent boundaries to sustain motion, diagnose actual drive/strain. A later optional mechanism is **slow intraplate extension under sustained tectonic tension/thermal weakening**—not an age-only random breakup or a 12-Myr inactivity timer. Do not add it unless the long runs demonstrate a real stall.

**Gate:** the changed lifecycle tests check physical eligibility rather than 6–10 plate count; no watchdog/population-forced events; event counts and intervals show substantially quieter plates across several seeds; still observable ocean closure, mountain building, spreading, and nonzero relative motion where independent plates exist.

## Stage 5 — Make geological timestep and physical scales coherent

1. Keep the user-facing geological interval and chronological calendar-credit semantics. **Inside one geology update only**, subdivide if local relative material displacement (translation + rotation at interfaces) exceeds roughly `0.3 * min(dx, dy)`. Re-evaluate motion/contacts between substeps. Always integrate the entire requested `dt`; never silently clip geological time. Treat `0.3` as an accuracy trial, **not** a mathematically sufficient stability proof.
2. Hold the supplied annual rain/drainage inputs fixed within the outer geological interval; refresh world geography, climate, hydrology, and publish the snapshot at the **existing outer cadence**. Avoid re-running 100 years of coupled climate for every substep. Keep the ordering of event preparation/application sound.
3. Compare 0.1, 0.5, and 1.0 Myr outer updates (×10,000 with calendar intervals 10, 50, 100). Measure convergence in land/thickness histograms, overlap coverage, geological events, and subduction volumes. Do not demand identical nonlinear trajectories. Subdivision should reduce sensitivity, not merely preserve the ledger.
4. Remove easy resolution dependencies: express root-flow radius, capture fringe, and patch widths in km; normalize upstream catchment using physical tile area; ensure the same physical production/erosion quantities at different raster sizes. Preserve the stylized periodic rectangle and initial terrain settings.

**Gate:** no skipped effective time, no new mass errors or chronology bugs, measurably better timestep consistency. Benchmark full-size tick cost and annual/max throughput. If subdivision dominates runtime, profile and optimize the hot projection/boundary passes rather than disabling accuracy everywhere.

## Stage 6 — Improve emergent landforms **after** land preservation works

1. **Arc volcanism:** preserve the existing mass ledger but focus additions near sustained overriding-plate margins. Accumulate rock into real local parcels/columns so some volcanic arcs rise above sea level through the unchanged buoyancy equation. Do not inject arbitrary elevation.
2. **Hotspots, optional second pass:** a few seeded, persistent world/mantle-fixed sources deposit basalt on passing plates; naturally form age-progressive island chains. Count all new rock as mantle addition. No magma reservoirs, eruption particles, or new subsystems unless clearly needed.
3. **Erosion:** retain real rock→sediment→deposition; after resolution scaling is fixed, strengthen erosion if desired. Ensure it can lower old ranges and construct depositional plains/deltas without nonconservative height edits. Do not reduce erosion merely to prevent land loss.
4. Validate that land **can both form and disappear naturally**, including volcanic islands, uplifted continental roots, rifting subsidence, and erosion. No fixed land-percentage objective.

**Gate:** a deterministic volcanic build-up fixture produces exposed land **only after accounted rock addition**; erosion decreases old relief while conserving transferred mass; long-run geography remains structurally varied.

## Stage 7 — Consolidate code, verify, document

- Delete replaced inverse-projection, coordinate-key, frame-resampling, forced-population, watchdog, and unused event/diagnostic code. Keep only abstractions that remove real duplication. A useful separation is `PlateMaterial` (persistent state), `PlateTopology` (connected ownership/partitioning), and `PlateTectonics` (orchestration, physical operations); split further only where a file has a genuinely independent responsibility.
- Keep one definition each for material/world transforms, periodic indexing, boundary enumeration, and mass/provenance conversion. Avoid separate algorithms for equivalent physical events.
- Run `gradle compileKotlinJvm` and `gradle jvmTest` after every stage. Before completion, run default-size long coupled simulations (at least seed 31742 through 500 Myr and additional seeds where feasible), seam/rotation fixtures, timestep/resolution comparisons, and the same-hardware throughput benchmark. Examine terrain and plate overlays at **0 / 25 / 100 / 300 / 500 Myr**.
- Preserve snapshot immutability, existing map layers/inspector, pause/speed controls, Max playback, and worker isolation.
- Rename `TECTONIS.md` → **`TECTONICS.md`** and update it to describe **implemented** behavior, experiments, limitations, and observed metrics. Do not report a proposed feature as complete. Keep `AGENTS.md` unchanged.

## Final acceptance checklist

- [ ] No persistent smoothing from repeated capture/merge/fracture frame changes; every material component and location preserved in the isolated test.
- [ ] Conservation at least as strong as the old `1e-8` relative requirement, with **actual** applied material flux and provenance reconciled.
- [ ] Ocean closure forms mountains; divergence forms ocean crust; detached pieces and toroidal seams behave correctly; world ownership remains connected for display.
- [ ] Splits and welds are demonstrably less frequent; no inactivity split or population-forced consolidation. Tectonic activity is not artificially reintroduced on a timer.
- [ ] Full-size land, thickness, coverage, and elevation diagnostics improve versus the documented baseline **without** imposing a target land fraction.
- [ ] Geological timestep changes no longer cause large unexplained changes in rock transfer; performance remains practical and measured.
- [ ] Volcanic land creation and stronger erosion, if implemented, modify accounted rock—not painted height.
- [ ] All focused tests/builds pass; outdated pathways are removed; documentation matches code.

## Research informing design choices

These sources justify *methods and cautions*, **not** the trial thresholds chosen for this toy world:

1. van Zelst et al., **101 geodynamic modelling** (2022): Lagrangian markers track discontinuous composition with low advection diffusion, but can introduce raster noise and conservation issues. https://se.copernicus.org/articles/13/583/2022/
2. Tackley, **Tracer nudging** (2025): uneven marker densities and empty raster cells are real hazards; avoid assuming markers solve all geometric problems. https://gmd.copernicus.org/articles/18/8651/2025/
3. Taylor, **Truly conserving with conservative remapping** (2024): remap extensive quantities before computing ratios; conserving a scalar total does not guarantee material-field fidelity. https://gmd.copernicus.org/articles/17/415/2024/
4. GPlately, **Working with plate-tectonic statistics**: boundary-normal convergence/divergence provides an appropriate simple basis for crustal production/destruction rate. https://gplates.github.io/gplately/latest/notebook-html/07-WorkingWithPlateTectonicStats.html
5. Brune et al., **Geodynamics of continental rift initiation and evolution** (2023): rifting is a progressive, sometimes aborted process, not automatic age-based splitting. https://www.nature.com/articles/s43017-023-00391-3
6. ANU **Computational Geodynamics Primer — numerical methods**: travel distance relative to grid size motivates timestep sensitivity checks; the exact bound depends on the solver. https://anu-rses-education.github.io/Computational-Geodynamics-Primer/book/DraftMaterial/NumericalMethods2.html
