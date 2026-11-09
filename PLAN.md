# Terra — Emergent Hydrology (Rivers, Lakes & Runoff)

**Status:** Ready for sequential implementation; all steps initially unchecked.  
**Purpose:** Make streams, rivers and lakes emerge from Terra's *actual terrain and simulated water cycle*, with naturally varying discharge. Do this cheaply and deterministically, without fluid dynamics.  
**Basis:** `HYDROLOGY_REPORT.md` (local-code inspection, 2026-11-09) and the existing Kotlin/KorGE architecture. **Inspect the actual current code before each step.**  
**Primary files:** `src/commonMain/kotlin/terra/weather/WeatherSimulation.kt`, `src/commonMain/kotlin/terra/world/World.kt`, `src/commonMain/kotlin/terra/world/Terrain.kt`, `src/commonMain/kotlin/terra/render/TerrainMapView.kt`, `src/commonMain/kotlin/terra/ui/AtlasScene.kt`. New, narrowly scoped `terra/hydrology/` files are appropriate.  
**World:** Typically 283 × 212 tiles, row-major `y * width + x`, normalized elevation `[0,1]`; world geometry/rendering are **bounded**, although atmospheric transport wraps east–west.  
**Authoring constraint:** No source code was available to the plan author. Exact method names, APIs and capabilities beyond the inspected report are not guaranteed.

## 0. Mandatory execution contract for the coding agent

1. **Implement exactly ONE unchecked required step per invocation, in order, then STOP.** On future invocations, resume at the first unchecked required step. Do not silently implement optional steps. A step includes only the files and functionality needed for that step.
2. Before editing, inspect the real Kotlin source and any existing hydrology code. **The checked-in code takes precedence over this report.** If the design materially conflicts with actual state, report the conflict and STOP, leaving the step unchecked; do not invent a replacement architecture.
3. **NO TESTS, NO PROFILING:** Do not write, modify, run or propose unit/integration/property/snapshot tests, test scripts, test fixtures, benchmarks, telemetry probes, profilers, performance measurements or validation harnesses. Do not add a temporary debug runner. Ordinary source review and, if appropriate, a normal *non-test* compilation are allowed. Do not claim anything was tested.
4. Keep implementation minimal and efficient: primitive arrays, reusable scratch storage, deterministic iteration/order, no per-tile/per-tick object churn, no new dependencies, no unrelated refactors, no backups or redundant documents.
5. Preserve existing weather physics, wind, precipitation, climate, terrain generation, 64×64 artwork, overlays and UI unless the selected step explicitly needs a small compatibility change. Do not change soil drainage constants, rain thresholds, or wind to make rivers more visually dramatic.
6. All physics and accumulation use **simulated time**, not real wall-clock time or frame count. The hydrology state must not depend on overlay refresh cadence. Never make an unbounded loop just to catch up with accelerated time.
7. Do not synthesize river water from cloud cover, humidity, biome color, or rainfall twice. **Track only water actually transferred out of weather's represented stores**, plus correctly transferred direct precipitation onto actual lake water where implemented.
8. Rivers/lakes are **surface-water state**, not new `Terrain` enum biomes. Avoid mutating immutable `Cell`, elevation, ocean placement, or static vegetation just to show water.
9. If generating original artwork later, use **64×64 pixels at Terra's existing native pixel density** and match its existing terrain style. Larger imagery spans native tiles; never upscale a standalone 64×64 tile to masquerade as a larger geographical feature. Do not generate art unless needed for the rendering step.
10. At step completion: check off **only** that step, append **one concise Implementation Log** entry (changed files, key decisions, known limitations), report completion, and STOP. Leave the step unchecked if blocked. Do not claim visual verification or runtime behavior you have not observed.

### Progress

- [x] **Step 1 — Expose true soil-to-surface water fluxes** (water accounting)
- [x] **Step 2 — Precompute a deterministic, acyclic drainage graph** (geography)
- [ ] **Step 3 — Route runoff and compute time-varying river discharge** (dynamics)
- [ ] **Step 4 — Add real lake-basin storage and spillover** (dynamic lakes)
- [ ] **Step 5 — Render connected rivers and lakes** (graphics)
- [ ] **Step 6 — Wire lifecycle, controls and inspection** (integration)
- [ ] **Optional Step 7 — Add derived floodplain inundation visuals** (**approval required**)

## 1. Ground truth and important corrections

The latest supplied code investigation reports:

- `WeatherSimulation` **already produces rain in mm** and uses a **3-hour maximum internal weather substep**. `rainMmThisUpdate` is a depth aggregated over the outer `weather.step(...)`; `precipitationMmPerHour` is the rate averaged over that outer interval; `peakSubstepPrecipitationMmPerHour` is a separate peak indicator.
- Land soil starts at 125 mm, is capped at 250 mm, and removes excess above 120 mm by a five-day exponential drainage response. **`drainedMm` currently disappears**, and soil-capacity overflow also disappears. Both are valid inputs to hydrology *when intercepted at their point of calculation*.
- Weather stores mm **depth per tile**, not m³. **A tile has no defined physical area**; elevation is normalized, not meters. Do not invent SI volumetric discharges or physical channel widths.
- World `Cell` elevation and terrain are immutable. Ocean is `Terrain.OCEAN` when elevation is strictly below sea level, *not* necessarily a sea-connected component. Terrain geometry does not wrap; atmospheric winds wrapping X must **not** be copied for rivers.
- There are no preexisting river/lake assets or graph structures. Terrain uses 64×64 art scaled to one world coordinate per map tile. Existing diagnostic overlays are 1 pixel per map tile.
- **Source discrepancy to respect:** despite an earlier climate-driven-biomes plan reportedly being implemented, the 2026-11-09 code investigation still found exactly `PLAINS`, `MOUNTAINS`, `WOODS`, `DESERT`, `OCEAN`, with terrain classified once from the legacy moisture proxy. Do not presume the biome upgrade exists. Hydrology must work regardless, and should not become coupled to biome classification.

### Non-negotiable hydrological separation

```
Immutable elevation and ocean / map edge outlets
               |
               v
    One-time drainage / basin topology
               |                         WeatherSimulation
               |                     (rain -> soil -> drainage)
               |                                 |
               |                  explicit drainage + overflow mm
               |                                 |
               +-------------> HydrologySimulation <---+
                                      |
                           rivers, basin storage,
                            outlet losses, water levels
                                      |
                             water visual layer
```

- The **network** is static unless the world is regenerated.
- The **water quantity** is dynamic and derives from weather.
- A **lake** stores inflow and only overflows its spillway after storage reaches capacity; its geometry is constrained by elevation.
- Flooding, if eventually added, should be derived from a real excess-flow condition, not a random terrain-color event.

## 2. Shared units and accounting contract

Use these definitions consistently across steps:

| Quantity | Meaning |
|---|---|
| `drainageMmThisUpdate[i]` | mm of soil water *removed* by the existing 120 mm field-capacity drainage during the latest outer `WeatherSimulation.step(...)`. |
| `overflowMmThisUpdate[i]` | mm of rainfall/soil water above the existing 250 mm storage cap that previously vanished at the clamp. |
| `runoffMmThisUpdate[i]` | `drainage + overflow` if exposed as a convenience getter; **never** add gross rain again. |
| `inputMmTiles[i]` | Per-routing-period water quantity proportional to volume, assuming equal-area tiles: runoff depth (mm) × one tile. |
| `routedMmTiles[i]` | Total runoff amount that passes this tile during the routing interval, including upstream contributions. |
| `dischargeMmTilesPerDay[i]` | `routedMmTiles / elapsedSimulatedDays`; a **relative discharge proxy**, not m³/s. |
| `lakeStorageMmTiles` | Persistent water quantity in a basin, in the same tile-mm-equivalent volume units. |
| `outletMmTiles` | Routed water that leaves the model at an ocean or approved outer-map outlet. |

**Conservation rule for soil:** For each land tile and substep, write the balance *before* applying any clamp:

```
rawSoil = max(0, oldSoil - actualSoilEvapMm + rainToSoilMm)
overflowMm = max(0, rawSoil - 250)
soilAfterCapacity = min(rawSoil, 250)
drainageMm = min(soilAfterCapacity, max(0, soilAfterCapacity - 120) * drainageFraction)
newSoil = soilAfterCapacity - drainageMm
```

The existing actual code may perform equivalent operations in a different order; preserve its physically intended ordering and amounts. **Never create extra runoff by retaining overflow and also storing it in soil.** Do not count rain in the hydrology pipeline and count its later drainage again. Ignore ocean soil routing entirely; ocean receives routed water as an outlet.

**Water budgets:** Weather's full world budget already has implicit unlimited ocean water and omitted ocean storage. Hydrology nevertheless needs explicit bookkeeping for its *own* inputs, stored lake water, lake evaporation where implemented, and outlet exports. No negative inventories. Numerical tiny residuals may be clamped consistently and accounted for.

## 3. Required steps (one per agent invocation)

### Step 1 — Expose true soil-to-surface water fluxes

**Goal:** Export the **exact water amounts already leaving soil**, without altering weather or land evaporation/precipitation dynamics.

1. Inspect the current soil update around `WeatherSimulation.advanceSubstep()` and the outer `step(seconds)` reset/aggregation logic.
2. Add reusable per-tile `DoubleArray` accumulators for drainage and overflow **across all internal substeps** of the current outer call. Reset at the beginning of `weather.step`, not at each substep. Keep ocean entries zero.
3. At the precise soil-capacity operation, capture the over-capacity amount *before* it is clamped. Capture the existing `drainedMm` immediately before it is removed from soil. These amounts must exactly match existing water removal; do not change `250`, `120`, or the five-day response.
4. Expose simple read-only scalar accessors or equivalent allocation-free indexed access for the hydrology subsystem, using the existing accessor conventions. Optional combined `runoffMmThisUpdate` may be computed from drainage + overflow but must not double-add them.
5. Document in code whether each quantity is **mm per outer call**, not mm/h. Ensure last outer call's values do not survive into the next.

**Completion criteria (source inspection only):** Any land water lost at the two previously discarded paths appears in exactly one recorded quantity, and weather still performs the same updates with no extra water injected into atmosphere/soil. No hydrology routing or display work in this step.

### Step 2 — Build a bounded drainage graph and identify depressions

**Goal:** Precompute where water *could* flow, before considering how much water exists.

1. Add a compact geography object, e.g. `HydrologyTopology(world)`, built once per generated world. Store `downstreamIndex: IntArray` (`-1` = terminal outlet), a **verified acyclic processing order**, a filled/spill elevation view or equivalent, and minimal lake-candidate metadata. Use row-major indexing and **8-neighbor** connectivity initially.
2. Use a deterministic **Priority-Flood-style** traversal from existing `Terrain.OCEAN` tiles and the **bounded map perimeter** as valid terminal sinks. This handles arbitrary procedural sinks and flats. No X wrap, no Y wrap, and no diagonal reference beyond array bounds.
3. Record the first valid downstream predecessor during flood traversal (or an equivalent deterministic drainage-parent graph). Make actual downhill routes preferred where possible; if a pit requires a virtual spill route, preserve that fact as *depression/lake metadata*. Do **not** modify `World` elevation to fake a trench or claim an uphill dry channel is physically flowing.
4. Stable ties must be resolved by a documented rule, e.g. priority/effective elevation followed by linear tile index. Derive processing order from the actual directed graph, not arbitrary row order; every nonterminal link must point to a node processed later in downstream routing.
5. Distinguish **small noise pits** from **meaningful lake-capable depressions**. For candidate depressions retain at minimum a set of basin tile indices/ID, spill elevation, a unique exit connection, and a deterministic set of contributing land cells or entry links. Use a small, named minimum-size/depth policy rather than calling every noise pixel a lake.
6. A candidate lake outlet should follow the true lowest escape/spill route; interior depression cells need not be permanently underwater. At this stage only record *potential* basin geometry; **do not fabricate filled lakes or change existing terrain tiles**.
7. When no ocean exists, perimeter outlets still guarantee termination. When an ocean tile is disconnected from the ocean elsewhere, treat it as ocean as long as `World` does; do not silently reclassify it.

**Implementation caution:** A filled elevation DEM with ties does **not** by itself guarantee an acyclic D8 route. Guarantee the directed graph's acyclicity by construction (e.g. flood visitation order / parent links and topological order). Never create equal-height loops. When contracting a lake later, its spill outlet must be outside its own basin and the contracted graph must remain acyclic.

**Completion criteria:** Geography can identify every land tile's eventual terminal sink and potential depression/spill without loops or map wrapping. The graph is deterministic for an unchanged seed/elevation. No weather flux consumption, per-tick routing or drawing in this step.

### Step 3 — Add live runoff accumulation and river discharge

**Goal:** Produce a weather-driven discharge field on the fixed network, without moving droplets or solving fluid equations.

1. Add `HydrologySimulation` to own all mutable water state. Do not put it in immutable `World.Cell`. Accept incremental input from Step 1 and elapsed **simulated** time.
2. Choose a coarse hydrology cadence (initial target: **one simulated day**); accumulate each weather outer update's two runoff arrays until processing. One external weather call can represent ~1.5 simulated days at month speed: if a pending batch spans multiple days, **process its whole amount over its actual elapsed time**. Do not pretend it was a sequence of known hourly rainfall events, and do not do an unbounded catch-up iteration.
3. On each hydrology update, initialize a reusable `DoubleArray` with **actual positive runoff depths** converted to tile-mm amounts. Walk the static topological order and add each tile's accumulated amount to its downstream successor. Sum, never overwrite. Ocean/perimeter outlets receive the exported quantity, not another land-cell storage. Use an outlet counter for reporting/accounting only.
4. Publish (a) latest routed amount for the processing period and (b) `dischargeMmTilesPerDay` using its *actual duration*. Optionally maintain an exponential **display** smoothing response in simulated days so river widths do not flicker. If smoothing is visual only, it must not create/destroy routed water.
5. Require a meaningful **upstream contributing area** as well as a nonzero flow threshold for visually classifying a cell as a stream. Otherwise every soil-draining field would appear as a tiny river. Record area at topology build time (upstream cell count) and use named, easily adjustable thresholds; do not normalize per map in a way that forces every dry map to contain large rivers.
6. For this step only, lake candidates may behave as explicitly provisional *pass-through* routing nodes. No water may be stored or displayed as a real lake until Step 4. Avoid designing the pass-through in a way that makes lake interception impossible to add next.

**Expected behavior:** Tributaries combine into larger downstream flows. Dry areas can lack rivers, rainy catchments can support larger rivers, and changing weather changes their discharge. Soil's five-day drainage is already a delayed source; **do not add an unrelated groundwater model or extra rain-to-river injection here**.

**Completion criteria:** One tile's drainage is represented once upstream and again as throughput at downstream nodes, but never counted twice as a new source. Aggregate source water exits or remains accounted for; hydrology is independent of rendering, per-frame updates and static biome labels. No visualization in this step.

### Step 4 — Add meaningful lake basins, storage, evaporation and spillover

**Goal:** Real, geographically constrained lakes that fill from upstream water and can release water when full. Do not make all priority-filled pits permanently blue.

1. From Step 2's meaningful depression candidates, construct **lake reservoir nodes** within the routing graph. All flow entering a candidate basin goes to its lake reservoir **once**. A lake's single spill outlet returns water to the proper downstream network after the basin fills. Treat a lake as one node for routing order; do not send its inflow repeatedly through every member tile and count it several times.
2. Define a single documented **game-scale conversion** `verticalMillimetersPerElevationUnit` to map normalized terrain-elevation differences into approximate water depths. Terra has no real-world altitude or tile area. Compute lake volume capacity from basin geometry using `sum((spillLevel - tileElevation).coerceAtLeast(0) * scale)` over candidate lake tiles (mm·tiles). Choose one conservative tuning default and keep it isolated; do not label resulting volumes as real m³.
3. Maintain `lakeStorageMmTiles >= 0`. During each hydrology interval, update each reservoir with routed inflow, direct precipitation on genuinely wet lake tiles (if implemented via weather integration below), minus actual evaporated water. Water below the spill-capacity threshold remains stored. **Only the excess above spill capacity leaves through the predetermined spill outlet**; this water is removed from storage as it is routed onward.
4. Determine the current lake water-surface elevation from storage and the basin's elevation/area relation (sort basin elevations once; avoid per-update full sorts). Mark tiles wet only where the water surface reaches their physical elevation. A partially filled basin therefore draws a *smaller* lake than its maximum footprint. Do not modify immutable terrain.
5. **Minimal but consistent lake evaporation / direct-rain coupling:** Once a tile is covered by lake water, its rainfall belongs to the lake, not simultaneously to land soil. Likewise, do not evaporate the same surface as both ordinary soil and open lake water. Prefer a small, explicit weather↔hydrology flux contract: weather records wet-lake direct rain and *potential* lake evaporation; hydrology caps actual lake evaporation by available basin water and returns the confirmed amount to atmospheric vapor once, if the current architecture safely permits it. Update the wet-lake mask at the hydrology cadence. Keep all quantities per simulated interval. **Do not blindly add lake evaporation as an unaccounted loss or credit vapor before checking water availability.** If this exchange cannot be integrated cleanly, report the block and STOP rather than silently building nonconserving or double-counted evaporation.
6. Handle the edge cases: no suitable lakes; isolated pits; nested or neighboring candidate basins; empty basins; fully spilling basins; runoff through very small pits (no persistent storage); and ocean/perimeter spill exits. Filter tiny noise pits rather than creating thousands of reservoirs.
7. Use no lakes that are merely `Terrain.OCEAN` under another name. An inland lake is a persistent hydrology entity and its basin is determined by topography. **Do not make an arbitrary deep lake merely because cloud cover is high.**

**Completion criteria:** Inflow raises lake storage and level, drought/evaporation can reduce it, overflow exports precisely the water exceeding capacity, and the lake inflow is not counted more than once. Actual water-covered tiles are distinguishable from dry potential lake footprint tiles. Rendering still deferred to Step 5.

### Step 5 — Draw connected rivers and water-filled lakes

**Goal:** Make the actual hydrological state easy to see in Terra's existing map without replacing biome tiles.

1. Inspect current `TerrainMapView`, overlay bitmap scaling, palette, and `AtlasScene` layer ordering. Add a dedicated **surface-water layer** above the biome tile map but below selection/UI. Rivers/lakes should normally be visible as geography, not only when a precipitation diagnostic is selected.
2. Draw river segments as **connected center-to-center links** following actual downstream topology, merging tributaries naturally. No disconnected single-pixel specks, random water strips or channel links across the X seam. Keep river geometry stable; adapt width/color gently to actual or smoothed discharge. Headwaters are thin; major confluences/rivers are wider; channels can be intermittent where flow becomes too small.
3. Draw lake water only for the currently wet lake footprint from Step 4, with clean banks following tile-edge/pixel geometry. River entry, exit, and spillway should connect naturally to the lake at a meaningful location. Ocean remains the existing ocean terrain/visual; a river mouth terminates into it.
4. **Avoid a full 64×64×(world width × world height) dynamic RGBA image.** Prefer existing map-overlay infrastructure at a modest resolution (e.g. a few pixels per tile), sparse procedural lines, or reusable cached segment sprites/tile combinations. Refresh water imagery only when hydrology changes enough, not unconditionally at 20 Hz; no per-frame art regeneration.
5. Match existing pixel-art style. **Original 64×64 transparent water assets are allowed but optional** for river surface/banks/lake edge decoration when the procedural result looks too schematic. Reuse small numbers of textures, do not author 256+ handcrafted adjacency tiles, and do not alter 64×64 native pixel density. Do not add a new `Terrain.RIVER` / `Terrain.LAKE` just to draw them.
6. Keep the surface-water layer independent of humidity, rain, clouds, annualized precipitation, wind, temperature and biome map overlays. Existing overlays and selection must continue working.

**Completion criteria:** River networks are visually connected, narrow upstream, larger downstream, and aligned with drainage. Lakes occupy actual wet basins and connect to their inputs/outlets. No visible fake sea-wrap or dry uphill river paths. Existing terrains remain recognizable.

### Step 6 — Integrate hydrology into world lifecycle, UI and tile inspection

**Goal:** Ensure everything updates coherently with world generation, speed controls and selection, without bundling extra features.

1. Construct topology once after a `World` is available; construct/reset hydrology with the matching `WeatherSimulation`. On terrain-settings regeneration, rebuild graph, reservoirs, scratch arrays, and visuals. Never retain old-world data or stale tile dimensions.
2. After every `weather.step(...)`, hand off exactly that outer call's runoff and (after Step 4) any lake-specific water flux to hydrology. Hydrology must aggregate under its own simulated-time cadence; the overlay consumes hydrology state, never controls physics. Ensure input is delivered **exactly once** for each weather call.
3. If Terra actually has a weather/climate bootstrap/warmup phase, decide deliberately whether hydrology advances during it. Prefer using the *same emitted water fluxes* during bootstrap rather than creating an independent fictitious initial lake fill. If no such bootstrap exists, new lakes start empty and fill through subsequent simulated weather. No expensive synchronous new warmup loop in this step.
4. Expose a compact, user-readable selected-tile section when practical: upstream drainage area (tile count), current discharge (mm·tiles/day **proxy**), local soil drainage and overflow (mm/latest weather update), optional lake ID, lake storage/level, and whether the tile is actually under lake water. Do not label discharge m³/s or claim lake altitude in meters. Keep existing inspector styling intact.
5. Make dynamic rivers/lakes visible by default and, only if consistent with existing UI, add one unobtrusive toggle for the water layer. Avoid a new settings panel or numerous unexplained hydrological sliders.
6. Preserve pause, regeneration, speed switching, and weather diagnostic overlays. The plan ends when *simple meaningful water movement is visible*; do not add a separate climate model, groundwater simulation, erosion, seasons, or flood solver.

**Completion criteria:** Water state is wired once through initialization and update ownership; source fluxes are not lost or replayed, and selecting a river/lake tile describes values in correct units. No extra generation phase or unrelated climate/biome changes.

## 4. Optional work — explicitly outside the required plan

### Optional Step 7 — Simple river flooding / wet floodplain tint **(requires user approval)**

Only if the real river and lake behavior is already satisfactory:

- Use excess actual river discharge over a named `bankfullDischarge` proxy as a **trigger** for a temporary floodplain wet/inundated overlay on nearby *low-elevation, topologically connected* tiles. Consider localized distance-to-channel and small elevation-above-channel thresholds; do not flood high ridges or entire biome regions.
- **Important honesty:** If this is only a derived flood *visual*, say so in the source and UI. It is **not** a fully volume-conserving flood-water storage model. Do not create new stored floodwater or claim it returns to rivers. A real volumetric flood solver would be a separate design.
- Flood tints fade over simulated time after river discharge drops. Do not introduce erosion, vegetation deaths, dynamic reclassification, or extra water transport.
- If volume-conserving floods are required instead, STOP and request a new plan rather than silently adding an ad hoc second water store.

## 5. Architectural guardrails and decisions to preserve

1. **The topology and discharge are not the same thing.** A dry channel can exist geographically while current water is zero; climate determines how often and how strongly it flows.
2. **Do not use weather's periodic X boundary for rivers.** Ocean and outer edges are legal hydrology outlets; map edges are not adjacent to opposite edges. Ensure all OCEAN-classified tiles are treated consistently even when inland.
3. **Do not turn every depression into a lake.** A Priority-Flood fill is a *routing tool* and potential storage geometry, not proof that the basin is currently filled with water.
4. **No physically calibrated flow speed yet.** Normalized elevation and unknown tile area do not justify m³/s, meters, or physically realistic travel times. Soil drainage already produces a multi-day response. Route accumulated water in topological order and optionally smooth *display* discharge only; document that channel travel time is approximated as immediate at the hydrology cadence.
5. **Water balance before appearance.** Never boost rivers using generic precipitation estimates in addition to already-accounted drainage/overflow. If rivers prove too small or too constant, identify whether soil actually generates runoff; any future infiltrated-rain / quick-runoff partition must remove water from soil first and requires its own approved change.
6. **One water source, one sink.** Explicitly distinguish soil-to-channel flux, lake precipitation, lake evaporation, in-transit water and outlet export; a channel's downstream flow is throughput, not a new source.
7. **Use primitive storage.** Typical grid ~60k cells; reuse arrays and topo orders. Build priority queues/sorted basin geometry once at generation. No extra external libs, real-time fluid simulation, per-particle water, or per-frame full-map geometry reconstruction.
8. **Avoid imaginary seasons.** There is no verified axial tilt / snowpack system. No seasonally melting rivers, frozen lakes, or glacier source until those are actually implemented.
9. **No biome dependency.** This hydrology plan can be implemented whether the climate-driven biome plan is present or not. The rivers/lakes are geographical overlays, not terrain-type replacements.
10. **Be conservative with code churn.** Prefer a small `HydrologyTopology` + `HydrologySimulation` pair and a narrow renderer integration over scattered water logic in `World`, renderer, scene and weather.

## 6. Handoff/completion protocol

At the end of one invocation:

1. Inspect the selected step's source dependencies.
2. Implement **only** that step.
3. Mark only its checkbox `[x]`, or leave `[ ]` if blocked.
4. Append exactly one short entry to the log below, including the files changed, constants chosen, known limitations, and whether compilation was attempted (if it was, no tests).
5. Explain which next step is now available. **STOP and await a new invocation.** Do not begin the next step.

### Implementation Log

<!-- Append entries; do not remove old entries. Example:
- Step 1 — DONE (YYYY-MM-DD): Exposed drained and overflow water in mm per outer weather call. Files: ... Notes: ...
-->
- Step 1 — DONE (2026-11-09): Exposed soil drainage and pre-capacity overflow as mm per outer weather call. Files: `WeatherSimulation.kt`, `PLAN.md`. Notes: Fluxes aggregate across internal substeps, ocean stays zero, and the existing 250 mm capacity / 120 mm field capacity remain unchanged; compilation attempted with no tests.
- Step 2 — DONE (2026-11-09): Added bounded deterministic D8 Priority-Flood topology and acyclic upstream-first order. Files: `HydrologyTopology.kt`, `PLAN.md`. Notes: Ocean and perimeter are outlets; candidate basins use minimum 4 tiles and 0.015 normalized depth, with one selected spill connection; compile attempted, no tests. No routing or water storage yet.

## Reference

- Internal source: `HYDROLOGY_REPORT.md` (Terra Hydrology Architecture Investigation, inspected 2026-11-09).
- Algorithm reference (optional background, no dependency required): Barnes, Lehman & Mulla, *Priority-Flood: An optimal depression-filling and watershed-labeling algorithm for digital elevation models*, Computers & Geosciences 62 (2014), https://doi.org/10.1016/j.cageo.2013.04.024. Priority-Flood is a preprocessing technique; it does **not** imply that all filled depressions are physically wet lakes.
