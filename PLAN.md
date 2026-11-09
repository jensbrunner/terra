# Terra — Climate-Driven Terrain & Biomes (`PLAN.md`)

**Status:** Ready for implementation. All required steps start unchecked.  
**Goal:** Replace the legacy, uniform-precipitation moisture heuristic with terrain/biome choices driven by Terra's *actual simulated long-term climate*. Make the resulting world visibly reflect rainfall, rain shadows, prevailing winds, and temperature without turning the game into a full ecology simulator.  
**Primary areas:** `src/commonMain/kotlin/terra/world/World.kt`, `src/commonMain/kotlin/terra/weather/WeatherSimulation.kt`, and the world-generation / scene initialization coordinator.  
**Other areas only when necessary:** terrain/biome type definitions, tile renderer/cache, existing 64×64 tile assets and asset registration.  
**Source of truth:** The implementation agent has local code access and MUST inspect it. The author of this plan does not; type names, initialization order, asset paths, and existing parameter values are intentionally not invented.

## 0. Non-negotiable agent execution contract

1. **Implement exactly ONE unchecked required step per invocation, in numerical order. Then STOP.** On the next invocation, continue with the first unchecked required step. Do not bundle steps. Optional steps need explicit user approval.
2. First inspect the current Kotlin source relevant to that step. Previous weather and wind upgrades have reportedly been implemented; **adapt to the current code, not the original handoff**. If an essential assumption conflicts with the implementation, report it and STOP rather than silently redesigning the system.
3. **NO TESTS:** Do not write, modify, run, or propose unit/integration/property/snapshot tests, test scripts, or custom validation harnesses. **NO PROFILING:** Do not add timers, benchmarks, timing probes, or profiling instrumentation. A normal non-test compilation is permitted if convenient; it must not invoke tests. Source review and normal user observation are sufficient.
4. Do not alter existing wind patterns, advection, saturation, evaporation, condensation, rainout, soil drainage, time stepping, weather visualizations, or climate-rate reporting unless the selected step cannot work without a *minimal* compatibility change. Preserve the currently working weather simulation.
5. Separate **immutable geography** (land versus ocean, elevation, coastline, physical rock/mountain constraints) from **climate-derived ecological cover** (desert, grass, forest, tundra, etc.). Climate must not turn an ocean into land, move elevation, or delete rivers, roads, placed objects, or gameplay structures.
6. A biome describes **long-term climate**, not the current rain, clouds, humidity, instantaneous temperature, or capped soil water. **Do not** use `relativeHumidity`, `cloudCover`, `soilWaterMm/250`, or the 30-day smoothed rain-rate value as the sole biome determinant.
7. All climate measurements use **simulated time**, are independent of render frequency and game-speed settings, and use rainfall **depth** (mm), not a single displayed mm/h sample. No wall-clock randomness. Reuse tile arrays and existing utilities; do not allocate per-tile objects in weather hot loops.
8. Any new constants are named, centralized, and documented as **game-tuning defaults**, not physically calibrated Earth values. Avoid introducing dependencies, complex Koppen classification, seasons, a groundwater model, or vegetation-growth simulation.
9. After the one step: mark only its checkbox `[x]`, append a concise Implementation Log entry, summarize changed files and source-review caveats, and STOP. If blocked, leave unchecked. Do not claim runtime behavior has been observed or validated.
10. **Asset permission:** The user allows generating original **64×64 pixel terrain tiles** consistent with existing art. Create new art only in the designated asset step and only when a visually distinct climate zone cannot be represented convincingly with current assets. Do not bulk-generate biome variants or replace established artwork.

## 1. Why this change is needed

Current/previous architecture described:

- World size: `283 × 212` tiles (~60,000 cells). Elevation is generated with fractal noise; baseline temperature is affected by latitude and altitude.
- `World.kt` originally classified terrain **once**, using a legacy static moisture index based on `baselineAnnualPrecipitationMm` (default `1000`) and a temperature-driven evaporation proxy. That is **not actual precipitation** from the simulated weather model.
- `WeatherSimulation` tracks vapor, clouds, soil moisture, air/surface temperatures, and precipitation. Previous changes reportedly improved rainfall/drainage, and the wind now includes latitude bands plus evolving 2D transport.
- Consequently, the weather can make a mountain windward side wetter than its leeward side while the *biome tiles still ignore that climate*. A one-size annual precipitation estimate cannot reveal emergent rain shadows or ocean-to-land moisture gradients.

**Important distinction:** The new system should infer biome from long-term *measured simulated* precipitation and temperature. Its first goal is climate-informed **world generation**; slow biome evolution during play is explicitly optional (Step 6). Weather continues to vary rapidly even if the underlying biome remains stable.

## 2. Target architecture

```text
Elevation / latitude / land mask (geographic inputs)
                    |
                    v
     Temporary climate-neutral land cover
                    |
                    v
          Existing WeatherSimulation
       (same real dynamics + 2D wind)
                    |
          equilibrate, then observe
                    |
                    v
    Climate snapshot for each land tile
       - mean air temperature (°C)
       - annualized precipitation (mm/year)
       - optional simple dryness ratio
                    |
                    v
      Deterministic biome classifier
                    |
                    v
      Existing terrain tile types + art
       (add ≤3 types only if needed)
```

**No initialization cycle:** Weather needs physical land/ocean/elevation and perhaps a provisional surface classification; final biome needs climate from weather. Keep a **two-phase initialization** instead of making `World` construction recursively require an already-classified biome or constructing multiple worlds.

### Climate data and units

For each tile during a climate sampling interval:

```text
rainDepthMm       += rainDepthProducedThisInternalStepMm
meanTempIntegral  += airTemperatureC * dtSimulatedDays
observedDays      += dtSimulatedDays   // one global elapsed-days value is enough

meanAirTempC            = meanTempIntegral / observedDays
annualPrecipitationMm   = rainDepthMm * 365.0 / observedDays
```

- Prefer the simulation's **already computed rain depth for each internal substep**. If only rate is available, convert correctly: `rainDepthMm = precipitationMmPerHour * dtHours` *for the matching substep*. Never sum the externally displayed average mm/h values as if they were depths.
- `annualPrecipitationMm` is an annualized *rate from a sample*, not a claim that Terra simulates four distinct seasons. Terra currently has no seasons. Do not require calendar-year boundaries.
- Sample air temperature (not just the immutable static baseline), so transported air and cloud shading can influence climate. If code only exposes a different consistent temperature field, document that choice.
- Only **land** needs persistent biome classification, but ocean is required in the weather run to supply moisture. Ocean stays ocean regardless of recorded climate.
- Use per-tile primitive arrays and one global count of `observedDays`; expose a lightweight immutable/read-only snapshot or accessor only when the next step requires it. Do not introduce a large object graph.

### Biome policy: keep the ecological model small

The classifier should combine **mean temperature** with **long-term precipitation relative to temperature-dependent drying potential**. Rainfall alone makes cold and hot land with equal rain look too similar. Use a cheap *potential* evaporation estimate, NOT actual soil-limited evaporation (which would create circular dry-soil feedback).

Suggested initial proxy; these are **tuning defaults**, not validated terrestrial climatology:

```text
potentialEvaporationMmYear = clamp(350 + 45 * max(meanAirTempC, 0), 250, 1800)
aridityRatio = annualPrecipitationMm / max(potentialEvaporationMmYear, 1)

very dry   : ratio < 0.20
semi-arid  : 0.20 .. < 0.55
moderate   : 0.55 .. < 1.10
humid      : >= 1.10

thermal zones (approximate):
very cold  : T < -6°C
cool       : -6°C .. < 6°C
temperate  : 6°C .. < 18°C
warm       : T >= 18°C
```

Use a **small, explicit classification table** rather than continuous probability/noise determining the biome. An illustrative conceptual mapping (choose existing enum values by inspecting the project):

| Thermal zone | Very dry | Semi-arid | Moderate | Humid |
|---|---|---|---|---|
| Very cold | bare rock / cold desert | tundra | tundra | snowy/tundra (ice only if persistently cold) |
| Cool | cold barren | sparse grass/tundra | boreal woodland | boreal forest |
| Temperate | desert/scrub | steppe | grassland/woodland | forest |
| Warm | desert | savanna/steppe | grassland/woodland | warm forest / rainforest |

Use this as a **design guide**, not a mandate to invent 16 terrain classes. Reuse the closest available art/classes first. Prioritize distinct visible **desert ↔ steppe/grassland ↔ forest**, and **cold tundra/snow** where those regions exist. Treat physical mountain/rock constraints as an overriding layer rather than classifying high cliff faces as trees merely because wet. Avoid making snow depend on a single rainy/cold frame; it is separate from actual transient snow cover (not modeled here).

Avoid forcibly normalizing precipitation by the whole world's min/max to manufacture every biome. That would make an entirely wet world look as if it contains deserts, hiding weather-model issues. If the weather generates little spatial climate contrast, report it honestly and do not compensate with visual randomness.

## 3. Ordered implementation checklist

| Step | Status | Deliverable |
|---|---|---|
| **1 — Climate observations** | [ ] | Collect long-term temperature and rainfall per tile from existing weather updates, with correct simulated-time accounting. No biome changes yet. |
| **2 — Pure climate-to-biome classifier** | [ ] | Introduce a small deterministic mapper from climate snapshot + physical geography to **existing** terrain classes; no generation behavior change yet. |
| **3 — Climate initialization / bootstrap** | [ ] | Drive the existing weather model through a bounded, deterministic climate equilibration-and-sampling phase without advancing gameplay or freezing the UI for one massive loop. |
| **4 — Make terrain climate-driven** | [ ] | Use sampled climate to assign biomes during world initialization; stop using the uniform legacy precipitation heuristic as final biome input. |
| **5 — Fill genuine biome/art gaps** | [ ] | Add only indispensable missing biome type(s) with coherent original 64×64 tiles and full asset wiring; otherwise mark this step `not needed` with reasons. |
| **6 — Optional: slow ecological change** | [ ] | Reevaluate land biomes slowly from multi-year weather climate, with hysteresis; **requires explicit user approval**. |

---

## Step 1 — Capture real long-term climate (REQUIRED)

**Purpose:** Make weather-produced climate data available without changing how the world looks.

- Inspect actual precipitation accounting first: rainfall may be accumulated in each weather substep and then converted to an **outer-update average**. Identify exactly where *depth in mm* is available and avoid counting the same precipitation twice.
- Add a small climate sampler (e.g., primitive arrays for `precipitationSumMm` and `temperatureTimeIntegral`) attached to `WeatherSimulation` or an already-existing world-owned climate subsystem. Prefer the existing data flow over a new architecture.
- Accumulate **only while sampling is enabled**. Expose a clear `beginClimateSampling()` / `endClimateSampling()` or equivalent lifecycle so Step 3 can discard equilibration history and start a fresh observation interval.
- Integrate using the **internal simulated timestep**. A global `sampledDays` counter must increase once per substep, not once per tile. If air temperature is sampled after heating/relaxation, be consistent across steps.
- Support resetting the accumulator for the initialization phase. Do not repurpose or alter the smoothed 30-day precipitation statistic.
- Preserve all existing precipitation outputs, soil water transfers, and wind integration. No new map overlay, logging, test code, or profiling.

**Done by source review:** Actual substep rain depth and time-weighted temperature feed a separate climate accumulator. Unit conversions are explicit and the accumulator can be reset without mutating weather physics.

## Step 2 — One compact climate-to-biome classifier (REQUIRED)

**Purpose:** Centralize biome selection based on climate without yet changing world generation.

- Inspect the existing terrain enum/sealed types, water and mountain rules, rendering constraints, and any semantics tied to terrain (passability, movement cost, resources, generated objects). **Do not rename or reorder existing terrain IDs** just for this task.
- Make one deterministic classifier callable from world generation: conceptually `classifyBiome(physicalTerrain, elevation, annualPrecipitationMm, meanAirTempC): ExistingTerrainType` (adapt signature to the actual code).
- Reuse and respect immutable geographic overrides: water/coastline, impassable rock/cliffs/mountain features, and placed structures. If terrain type conflates rock/elevation and vegetation, use the smallest local precedence rule rather than a sweeping refactor.
- Use the temperature and aridity categories in Section 2 as **initial defaults**. Centralize thresholds. Prefer reusing the current small set of biome classes; map absent conceptual biomes to the closest existing type until Step 5.
- Keep decisions **spatially deterministic**, without per-tile random classification. Existing seeded art variation is fine **after** the biome is selected.
- Keep final biome independent of current humidity, cloud amount, recent rain, and soil saturation. No live climate-driven biome changes yet.
- Leave the old generated terrain path working for now. This step adds a usable classifier, not the initialization rewiring.

**Done by source review:** One clear climate+geography mapping exists, uses existing terrain types, has no weather dependency or assets, and does not change the current startup sequence.

## Step 3 — Obtain a representative climate before finalizing biomes (REQUIRED)

**Purpose:** Avoid classifying the world from the arbitrary first day of a newly initialized atmosphere.

**Preferred initialization plan:**

1. Generate **physical geography first**: elevation, land/ocean mask, geographic temperature baseline, and the minimal *provisional* land classification needed for the existing weather initialization. Do not finalize climate-derived ecological cover yet.
2. Create / use the existing weather simulation on that physical world. **Do not instantiate `World` recursively from `WeatherSimulation`**.
3. Advance weather through approximately **30 simulated days of equilibration** (no climate recording). This allows initial vapor/cloud/soil states to evolve. It is a game-model starting constant, not proof of atmospheric equilibrium.
4. Reset/start climate accumulation and advance weather through approximately **180 simulated days of observation**. Rationale: no seasonal cycle, but the new drifting wind waves operate over multiple-week timescales; a few days or one 30-day EMA is insufficient to characterize them. Keep `EQUILIBRATION_DAYS` and `CLIMATE_SAMPLE_DAYS` named/tunable.
5. Expose the resulting **time-averaged** climate snapshot to the caller for Step 4. Retain a valid evolved weather state; avoid resetting all atmospheric state and accidentally discontinuing the modeled climate.

**Responsiveness:** Do **not** execute thousands of full-grid substeps in one blocking constructor/UI frame. Work out the actual KorGE initialization lifecycle and advance the finite weather warm-up in **bounded batches across frames/coroutines** (or reuse an existing non-blocking generation job). It is acceptable to display a simple existing loading/provisional state until climate is ready. The batch limit should be a small named constant; do not add an adaptive profiler or performance measurement system. Maintain the existing weather integration substep cap, not a special inaccurate coarse climate shortcut.

**Simulation clock:** Warm-up is **weather-only**. It must not age characters, advance the main world/game calendar, trigger game events, or charge players for elapsed time. Keep solar time coherent (prefer whole-day warm-up/sample intervals) and keep the wind's accumulated simulated phase continuous. Document any necessary initial weather-age offset in source.

**Cyclic dependencies:** If weather currently uses biome-dependent coefficients, use its existing neutral/provisional land coefficients for bootstrap, then finalize biomes once. Do not repeatedly iterate world generation and weather until convergence or add climate feedback loops in this step.

**Scope stop condition:** If the project cannot accept non-blocking weather-only advancement without a substantial engine rewrite, STOP, document the concrete initialization coupling, and ask for a design decision rather than silently blocking startup for a long period.

**Done by source review:** There is a deterministic, bounded, weather-only initialization lifecycle that produces a climate snapshot; gameplay time is not advanced; startup work is incremental/non-blocking; previous wind/weather physics remain untouched.

## Step 4 — Replace the legacy static moisture choice (REQUIRED)

**Purpose:** Turn emergent climate into visible, authoritative terrain.

- Locate the current `World.kt` logic that turns `baselineAnnualPrecipitationMm` and a temperature-based evaporation proxy into the final biome/moisture terrain. **Stop using that synthetic uniform precipitation for final land biome classification.** Leave the setting only if it has an unrelated legitimate purpose; otherwise remove obsolete callers/configuration rather than silently maintaining two competing sources of truth.
- Connect Step 3's climate snapshot to Step 2's classifier. For each **land** tile, choose climate-derived ecological terrain **once** after climate observations become available. Keep geographic terrain constraints untouched.
- Update **the authoritative tile state actually used by the renderer and gameplay**, not a debug-only overlay. Invalidate or rebuild only the necessary terrain tile caches / chunk meshes / sprites if the existing renderer caches classification. Do not inadvertently reset soil water, cloud/wind state, or climate statistics when rebuilding visuals.
- Preserve deterministic terrain seed behavior: the same seed, parameters, and deterministic weather bootstrap should produce the same biomes. If the world has a serialization/save format, maintain it or carefully extend it to retain both finalized terrain and any needed initialization state; do not silently break old saves.
- Ensure areas can legitimately be **cloudy but dry**, **rainy but sparsely vegetated**, or **wet with trees**, depending on the **long-term average**. Do not make tile type react to a single current weather condition.
- Keep the weather running normally after bootstrap, independent of the current game-speed setting. Terrain remains stable after the initial choice unless the optional Step 6 is later approved.
- Avoid large climate-dependent noise overlays, arbitrary forest patches, or a forced global histogram. Preserve the existing terrain's visual style and any seeded within-biome appearance variation.

**Done by source review:** The final biome is determined by actual simulated long-term temperature/precipitation; the uniform precipitation heuristic is no longer authoritative; land/ocean/topography and weather state survive the transition; renderer uses the new result.

## Step 5 — Only add terrain types and 64×64 sprites that the map needs (REQUIRED, CAN BE NO-OP)

**Purpose:** Distinguish ecologically meaningful climate regions without bloating the terrain palette.

1. Inspect **existing** terrain type names, rendering assets, sprite dimensions, color palette, patterns, and tile registration conventions. First decide whether existing classes sufficiently distinguish dry, grassy, forested, and very cold land.
2. If important distinctions are genuinely absent, prefer **at most three** of these conceptual additions, in this order: **steppe / dry grassland**, **tundra / cold sparse ground**, **savanna / warm scattered vegetation**. Add only types actually needed by the climate ranges Terra produces; do not add all three automatically. A new hot rainforest class is justified only if the existing forest artwork visibly fails for warm humid climates.
3. For each added type, generate an **original 64×64 pixel tile**, visually matching existing tile style (camera angle, pixel density, contrast, palette, vegetation scale, outlines/shading). Reuse the local asset-generation approach if already available. Avoid incompatible stock imagery, blurred upscaling, noisy textures, or flat placeholder colors. Use existing conventions for transparent backgrounds and edge tiling.
4. Register assets using the existing packaging system; update the enum/classifier/renderer and relevant terrain-dependent rules coherently. A tile type must not resolve to a missing sprite or an incorrect fallback after packaging. Preserve seeded visual variations if already supported; do not add an art pipeline.
5. If no new type is needed, do **not** generate art. Mark Step 5 `[x]` and log **"No new biome types/assets needed"** with one-sentence rationale.

**Done by source review:** Each new type, if any, has a correctly wired 64×64 sprite and classifier mapping; established art remains intact. No need to create tests, previews, or benchmarking utilities.

## Step 6 — OPTIONAL: Slow biome adaptation during simulation (USER APPROVAL REQUIRED)

**Why optional:** A biome is a climate/ecology outcome, not a rain overlay. With no seasons and rapidly accelerated in-game time, immediate reclassification would flicker and could invalidate existing gameplay assumptions. The core feature is complete after Step 5.

If expressly approved later:

- Maintain a **multi-year climate estimate** (e.g., exponential response time of ~2 simulated years) based on actual incoming rain depths and temperature, rather than cumulative all-time means or 30-day rainfall smoothing.
- Reassess classification **infrequently** (e.g., every 1 simulated year). Require a new candidate to remain favored across multiple evaluations or to exceed category thresholds by a material margin (hysteresis); never toggle terrain on individual weather ticks.
- Change only ecological ground cover/vegetation and update dependent render caches. Preserve ocean, elevation, infrastructure, placed objects, and invalidated gameplay rules. Avoid turning mature forests into deserts overnight.
- Support saved worlds and keep changes deterministic under time acceleration.
- Treat this as a separate feature; do not implement it while completing Steps 1–5.

## 4. Design cautions (read before improvising)

- **An observed climate is not instantaneous weather.** `30-dayRainMmPerHour` (if present) is a weather/climate display smoothing value, not a robust biome classifier over drifting atmospheric patterns.
- **The world may have no seasons.** Do not invent seasonal desert/monsoon or snowpack semantics. Annualized rainfall is simply a common-scale reporting unit derived from the observed period.
- **Rainfall geography might still be weak.** If the new map shows little climatic variation, the correct follow-up is to understand weather moisture/transport, not to fake rain shadows by adding noise or normalize every map to include deserts.
- **Cold terrain needs care.** Mean annual temperature alone is insufficient for real-world permafrost or year-round snow, but it can provide a simple game approximation. Prefer cold/tundra visuals over widespread ice unless conditions are persistently freezing.
- **Potential evaporation is an approximation.** Use it only as a broad moisture/temperature comparator. Do not derive dryness from *actual* soil-limited evaporation, which would over-reward dry regions with artificially low evapotranspiration.
- **Climate cannot change coastlines.** Water, elevation, rocks, cliffs, and any procedural rivers remain geographic; climate chooses biological/land-cover appearance on suitable land.
- **Conserve water and separate responsibilities.** Recording climate must not consume precipitation, move atmospheric water, or change weather physics. Weather bootstrap should use the implemented simulation, not a second bespoke precipitation model.
- **Generation cost is real.** A 210-simulated-day weather spin-up is many internal updates on 60k tiles. Prefer bounded incremental work to a synchronous blocking constructor; no profiling or benchmarking is requested or authorized.
- **Backward compatibility:** If existing saves cannot be safely migrated in the selected step, preserve their prior terrain and only use climate-first generation for new worlds; state the limitation clearly in the Implementation Log.
- **Keep it a game.** Do not add realistic soil chemistry, plant life cycles, climate layers, pressure solvers, moisture diffusion, or seasonal climate calculations here.

## 5. Completion protocol

At the end of **exactly one required step**:

1. Update only the step's `[ ]` to `[x]` in the checklist (Step 5 may be completed as a documented no-op).
2. Append one entry to the Implementation Log below specifying the files touched and any deviations from the default constants or architecture.
3. Report what changed and what remains unimplemented.
4. **STOP.** Do not start the next step in the same invocation. Optional Step 6 requires separate explicit user approval.

### Implementation Log

<!-- Append entries in this format, without removing prior entries:
- Step 1 — DONE (YYYY-MM-DD): ... Files: ... Notes: ...
-->
