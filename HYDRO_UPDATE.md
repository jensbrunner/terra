# PLAN.md — Terra Hydrology Corrections

## Objective

Correct the identified weaknesses in Terra's existing hydrology implementation without rewriting its architecture.

**Core principle:** Preserve the separation between terrain topology, water accounting, hydrological simulation, and rendering. Favor small, deterministic, efficient corrections over additional simulation complexity.

Based on source review of `jensbrunner/terra`, commit `b30a7352bf31f27a087fd200870204460845a59f`.

## Agent execution rules

1. **Implement exactly ONE unchecked step per invocation, in order, then STOP.**
2. Inspect the latest code before implementing. Existing code takes precedence over this plan.
3. No tests, benchmarks, profiling, diagnostic harnesses, or additional dependencies.
4. Normal compilation is permitted.
5. Keep changes minimal and localized. Do not perform unrelated refactoring.
6. Preserve water conservation and determinism.
7. Do not change atmospheric weather physics, biome classification, or world generation parameters.
8. Do not introduce full fluid dynamics, erosion, groundwater simulation, or new hydrological systems.
9. After completing a step, mark its checkbox, append a concise implementation-log entry, and STOP.
10. If a proposed change conflicts with the actual implementation or introduces an unresolved conservation problem, explain the issue and stop rather than improvising.

## Progress

- [x] Step 1 — Correct rendering layer order and bootstrap updates
- [x] Step 2 — Improve river-width scaling
- [ ] Step 3 — Correct compound lake basin behavior
- [ ] Step 4 — Handle nonphysical uphill river connections
- [ ] Step 5 — Review lake storage scaling
- [ ] Optional Step 6 — Optimize drainage topology construction

---

## Step 1 — Correct rendering order and unnecessary redraws

**Files:** `TerrainMapView.kt`, `AtlasScene.kt`

### Problem

The current rendering order is approximately:

1. Terrain
2. Weather overlays, including clouds
3. Rivers and lakes
4. Selection

Consequently, water appears in front of cloud cover.

Additionally, the surface-water bitmap is approximately 1,698 × 1,272 pixels and is fully cleared and reconstructed for each hydrology update, including during climate bootstrap.

The bootstrap simulates 210 days, causing potentially hundreds of unnecessary full-bitmap redraws before the player can interact with the world.

### Implement

1. Move the surface-water layer directly above terrain and below weather/cloud overlays.
2. Preserve selection borders above all map layers.
3. Continue advancing hydrology during climate bootstrap, but suppress water rendering until bootstrap completes.
4. Render the fully initialized surface-water state once at bootstrap completion.
5. Keep subsequent redraws tied to hydrology revisions rather than weather overlay updates.
6. Use efficient bitmap clearing if available through KorGE, without introducing additional dependencies or rendering abstractions.

### Completion criteria

- Clouds visually cover rivers and lakes.
- Water remains above the base terrain.
- Hydrology continues advancing during bootstrap.
- No surface-water bitmap redraw occurs during intermediate bootstrap steps.
- Existing weather overlays and selection behavior remain intact.

---

## Step 2 — Improve river-width scaling

**Files:** `TerrainMapView.kt`, optionally `HydrologySimulation.kt`

### Problem

Current width calculation:

```kotlin
(sqrt(discharge / 1.0).toInt() + 1)
    .coerceIn(1, 6)
```

A discharge of approximately 25 mm·tiles/day already produces the maximum six-pixel river width.

Because streams require at least 64 upstream land tiles, even relatively minor runoff can create maximum-width rivers.

This eliminates the visual distinction between streams and large rivers.

### Implement

1. Replace the current square-root scaling with a more gradual logarithmic or otherwise strongly compressed mapping.
2. Keep small streams narrow.
3. Reserve the largest widths for substantially higher discharge.
4. Preserve the existing stream-visibility thresholds initially.
5. Keep the relationship monotonic: higher discharge must never produce narrower rivers.
6. Retain the existing six-pixels-per-tile water bitmap resolution unless a compelling reason exists to change it.
7. Avoid per-frame geometry regeneration or additional hydrology state.

Suggested initial width mapping, subject to source inspection:

| Discharge (mm·tiles/day) | Width |
|---|---|
| 1–10 | 1 px |
| 10–50 | 2 px |
| 50–200 | 3 px |
| 200–1,000 | 4 px |
| 1,000–5,000 | 5 px |
| Above 5,000 | 6 px |

These are visual tuning values, not physical river-width measurements.

### Completion criteria

- Ordinary streams no longer occupy the full tile width.
- Major rivers are visibly wider than tributaries.
- Width changes smoothly or in understandable discrete increments.
- River connections and lake transitions remain intact.

---

## Step 3 — Correct compound lake basin behavior

**Files:** `HydrologyTopology.kt`, `HydrologySimulation.kt`

### Problem

The topology groups connected depressed tiles sharing a filled elevation into lake candidates.

The simulation subsequently treats each candidate as one reservoir with a single water-surface level.

For complicated basins, this can create a nonphysical result: water entering one depression can appear in another depression before enough water exists to cross the separating internal saddle.

The total water quantity may be conserved while its geographic distribution is incorrect.

### Implement

1. Inspect how candidate basins are formed and whether existing lake groups can contain multiple low regions separated by higher internal saddles.
2. Ensure connected reservoir regions are physically valid at their current water levels.
3. Prefer a lightweight basin hierarchy or subdivision at meaningful internal saddles where necessary.
4. Preserve deterministic Priority-Flood-derived outlet routing.
5. Maintain an acyclic drainage graph.
6. Give each independent reservoir its own storage, capacity, level, and spill connection.
7. Transfer water between connected reservoirs only once the supplying reservoir reaches the relevant spill level.
8. Avoid splitting simple, already-valid basins unnecessarily.
9. Keep lake geometry derived from existing elevation, without modifying `World.Cell`.
10. Preserve conservation across reservoir-to-reservoir transfers, lake evaporation, and external outlet discharge.

**Complexity constraint:** Do not introduce general fluid simulation or water movement between arbitrary neighboring cells. If a full basin hierarchy would substantially complicate the implementation, use the smallest deterministic subdivision that corrects the identified disconnected-fill behavior. Do not silently approximate disconnected reservoirs as connected.

### Completion criteria

- A depression cannot fill another disconnected low region prematurely.
- Basin storage and water-surface levels remain consistent with terrain elevation.
- Reservoir spillover follows valid connections.
- No lake water is duplicated or lost.
- Routing remains acyclic and deterministic.

---

## Step 4 — Handle nonphysical uphill river connections

**Files:** `HydrologyTopology.kt`, `TerrainMapView.kt`, optionally `HydrologySimulation.kt`

### Problem

Priority-Flood creates drainage paths through terrain depressions by using spill routes.

This is appropriate for determining how water eventually reaches an outlet, but a route may cross increasing original terrain elevation.

When a depression is too small to become a represented lake, the current renderer may display a river following such a path as though water were flowing uphill.

### Implement

1. Distinguish normal downhill drainage links from virtual spill connections introduced by depression handling.
2. Preserve the existing acyclic drainage network and runoff accounting.
3. Prevent visible river channels from appearing to flow uphill across unchanged terrain.
4. For meaningful depressions, represent storage and overflow using the lake mechanism.
5. For tiny numerical depressions, choose a lightweight, deterministic handling policy:
   - Permit small implicit terrain corrections for routing geometry only, if explicitly documented; or
   - Suppress the nonphysical visible channel while preserving internal runoff routing.
6. Do not create water or modify actual world elevation.
7. Preserve river continuity wherever the terrain and water state permit it.
8. Avoid generating a new lake for every one-tile depression.

### Completion criteria

- Visible rivers follow geographically believable paths.
- No obvious uphill river segments appear merely because Priority-Flood assigned an escape route.
- Runoff continues reaching the correct terminal outlets.
- Small numerical depressions do not generate excessive lakes.

---

## Step 5 — Review lake storage scaling

**Files:** `HydrologySimulation.kt`

### Problem

Lake capacity uses the conversion:

```kotlin
VERTICAL_MILLIMETERS_PER_ELEVATION_UNIT = 1000.0
```

This converts normalized elevation differences into model water-storage depth.

The conversion is necessarily artificial because Terra has no physical tile area or elevation scale.

Small lake candidates can have capacities much smaller than the daily runoff of their upstream catchments.

As a result, lakes may fill rapidly, remain at their spill level, and exhibit little meaningful variation.

### Implement

1. Review the existing relationship between basin capacity, contributing catchment size, drainage input, and the current conversion factor.
2. Preserve the existing storage and spillover equations.
3. If necessary, adjust the single conversion constant rather than adding new water reservoirs or hidden capacity multipliers.
4. Keep the conversion documented as a game-scale approximation.
5. Ensure the same conversion is used consistently for capacity and water-surface elevation calculations.
6. Do not impose artificial minimum lake levels or create additional initial water.
7. Do not modify rainfall, soil drainage, or evaporation merely to force particular lake appearances.

**Important:** Without observed lake behavior or measured hydrological distributions, there is insufficient evidence to select a uniquely correct replacement constant. Prefer leaving it unchanged if source inspection provides no defensible reason to adjust it.

### Completion criteria

- Lake storage and water levels remain mathematically consistent.
- Any adjustment is made through one clearly documented scale parameter.
- Lake filling, evaporation, and spillover continue conserving water.
- No unrelated hydrological behavior changes.

---

## Optional Step 6 — Optimize drainage topology construction

**Requires explicit approval.**

**File:** `HydrologyTopology.kt`

The lake-exit search repeatedly follows downstream chains to reject invalid escape connections.

This can become unnecessarily expensive for complex basin networks.

Only address this if there is a demonstrated architectural need.

Possible improvements include caching downstream basin membership, precomputing outlet ancestry, or selecting spill connections during Priority-Flood traversal.

Preserve deterministic tie-breaking, route correctness, and acyclicity. Do not introduce broad complexity for a speculative performance improvement.

---

## Architectural constraints

Throughout implementation:

- **Geography determines possible drainage paths.**
- **Weather determines water input.**
- **Hydrology determines storage and discharge.**
- **Rendering depicts actual hydrology state.**

Never allow rendering requirements to create additional water or alter runoff quantities.

Keep using mm·tiles and mm·tiles/day as relative water quantities. Do not present them as physical cubic meters or cubic meters per second.

Preserve the existing hydrology timestep, weather integration, climate bootstrap, and deterministic world generation unless explicitly required by the active step.

The atmospheric transport and rainfall corrections are tracked separately and must not be folded into this hydrology plan.

## Implementation log

Append one concise entry per completed step, including:

- Step number and completion status
- Files changed
- Important implementation choices
- Known limitations
- Whether normal compilation succeeded, failed, or was not attempted

Do not proceed to another step automatically.

- Step 1 — DONE (2026-11-10): Moved water between terrain and weather overlays, suppressed surface-water rendering until bootstrap completes while continuing hydrology, redrew at completion/revision changes, and used `Bitmap32.fill` to clear. Files: `TerrainMapView.kt`, `AtlasScene.kt`, `HYDRO_UPDATE.md`. Notes: `updateWorld` no longer clears separately; both callers immediately redraw. `gradle compileKotlinJvm` succeeded after elevated-access retry; no tests.
- Step 2 — DONE (2026-11-10): Replaced square-root width scaling with monotonic 1/2/3-pixel bands at 10 and 200 mm·tiles/day. Files: `TerrainMapView.kt`, `HYDRO_UPDATE.md`. Notes: Kept the actual current 3-pixel-per-tile bitmap resolution despite the plan's 6-pixel assumption; existing stream thresholds unchanged. `gradle compileKotlinJvm` succeeded after elevated-access retry; no tests.

**End of plan.**
