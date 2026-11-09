# PLAN.md — Terra Atmospheric Transport & Rainfall Corrections

**Scope:** Fix artificial moisture concentration, persistently dry regions, and misleading cloud/rain displays in Terra's lightweight Kotlin/KorGE weather system.

**Basis:** Source review of [`jensbrunner/terra`](https://github.com/jensbrunner/terra), `main` at commit `56fbf29c5acbd891067c5b7c5959b6bb1a469644` (9 November 2026). **Re-inspect the current repository before editing**: subsequent commits may have changed the implementation.

**Goal:** Plausible, changing rain belts, tropical showers, windward mountain rain, and meaningful dry regions **without** full fluid dynamics, pressure simulation, or arbitrary rain injection.

## Agent execution rules

1. **Implement exactly the first unchecked required step, then STOP.** Do not proceed to another step without a new user instruction.
2. Inspect the actual current source and existing architecture first. If a step is already implemented, mark it complete with a brief explanation; do not duplicate it.
3. Make minimal, coherent changes. Preserve existing public APIs and weather/hydrology integrations where feasible. Do not add dependencies or broad refactors.
4. **Do not create, modify, or run tests; do not profile or benchmark.** Do not add test frameworks, diagnostic harnesses, or performance-measurement scripts.
5. A normal build/compile is allowed to catch compilation errors; do not claim a feature is empirically validated merely because it compiles. No requirement to run the game.
6. Avoid unphysical water creation: convection/orography transfer **existing atmospheric vapor** into cloud water. Atmospheric transport should not create or destroy atmospheric water.
7. At the end of each invocation, update the checkbox and the **Implementation log** in this file. Summarize files changed, algorithmic decisions, uncertainties, and any user-visible effects; then stop.
8. Do not overwrite previous hydrology work, change biome rules, add seasons, or start unrelated features. **Optional work requires explicit approval.**

## Current architecture — verified baseline

- `src/commonMain/kotlin/terra/weather/WeatherSimulation.kt`: `283 × 212` flat arrays, 3-simulated-hour maximum weather substeps, vapor/cloud/air-temperature transport, condensation, rain hysteresis, evaporation, wind field, and climate sampling.
- `transportAirMass()` (approximately lines 309–373) **forward-scatters** each source's vapor and cloud water into four destination cells using bilinear fractions. Total transported water is designed to be conserved, but destination weight sums are not constrained to one for spatially varying winds: even a spatially uniform tracer can develop artificial peaks and deficits.
- `updateWindField()` (approximately lines 375–407) combines latitude-dependent belts with two streamfunction-derived meanders. The *continuous* perturbation field is divergence-free; the **discrete forward scatter** does not guarantee uniform-concentration preservation.
- At the present 3-hour transport interval, the base temperate wind alone moves about **4.5 tiles per transport pass**. Earlier independent reproductions of the source equations showed large artificial concentration changes after 30 simulated days; treat these figures as motivation, not as a newly verified runtime measurement.
- Cloud opacity reaches full cover at **0.8 mm** cloud water; rain starts at **2.0 mm**, stops at **0.5 mm**, and uses a 2-hour active rainout response. `rainActive` is attached to ground tiles rather than being transported with clouds.
- Condensation occurs only when vapor exceeds the saturation capacity calculated from local air temperature. There is no explicit convection or windward orographic-lift process.
- Ocean evaporation is multiplied by instantaneous sunlight and warmth, is zero at night, and ignores the vapor deficit. Land evaporation uses soil-water fraction. Existing climate setup samples 180 simulated days after a 30-day equilibration.
- `annualizedRainMmPerYear` is a **30-day EMA × hours/year**, not a measured annual total. `ClimateSnapshot` separately records **actual rainfall over the 180-day sampling interval**, then annualizes it.
- `src/commonMain/kotlin/terra/render/TerrainMapView.kt`: cloud overlay uses opaque white tinted by `cloudCover()`. `src/commonMain/kotlin/terra/render/WeatherPalette.kt`: precipitation palette is discrete, despite a square-root scaling. `src/commonMain/kotlin/terra/ui/AtlasScene.kt`: inspector and overlays.

**Engineering priority:** Correct numerical transport *before* tuning rainfall/evaporation. Otherwise new moisture sources can amplify an artificial accumulation mechanism.

---

## Required implementation steps

### [x] Step 1 — Conservative, uniform-preserving 2D atmospheric advection (P0)

**Problem:** Current forward scatter conserves the global sum but does not preserve a uniform concentration in a spatially varying wind field. It may create false wet/dry zones that feed directly into condensation and rainfall.

**Implement:**

1. Inspect the current `updateWindField()` and `transportAirMass()` and the available wind representation. Retain the existing latitude belts and evolving meanders unless a narrow correction is essential.
2. Replace scatter-to-destination transport with a **conservative, face-based finite-volume flux** scheme for vapor and cloud water: a transfer across one shared cell face must be subtracted from the donor and added to the receiver exactly once.
3. Derive a **discretely divergence-free face-wind field** from the same underlying streamfunction/belt representation (or an equivalent discrete construction). Preserving a uniform tracer is a required property; simply sampling center velocities and assuming zero discrete divergence is not sufficient.
4. Respect the existing **periodic X boundary** and **bounded Y boundary**. Ensure zero normal water flux through the top/bottom edges. Do not change the physical terrain world's edge rules.
5. Bound transport-specific timesteps using an explicit **CFL condition** appropriate for the chosen upwind fluxes (e.g., account for the sum of outgoing face Courant numbers). Split transport internally as needed without shortening every thermal/rainfall operation or multiplying unrelated full-weather passes.
6. Keep transported concentrations nonnegative; handle zero or near-zero values safely. A first-order donor-cell/upwind flux is acceptable initially. Prefer correctness and simplicity over high-order reconstruction.
7. Transport air temperature in a consistent manner (e.g., conservative mixing of a uniform-density thermal tracer), without accumulating spurious temperature extremes. Preserve legitimate changes from local thermal relaxation after transport.
8. Reuse arrays; do not allocate new full-world scratch buffers per substep. Preserve the established water budget and callers.

**Completion criteria (reasoning + source inspection only):** Every face exchange has an equal and opposite update; a constant tracer would remain constant under the discrete face field and closed/periodic boundaries; nonnegative vapor and cloud stores are maintained; total atmospheric vapor-plus-cloud changes only through the existing physical source/sink processes. No humidity/rain tuning in this step.

**Avoid:** Normalizing transported water by inbound bilinear weights alone: it can destroy global water conservation. Avoid a general-purpose fluid solver.

### [x] Step 2 — Make climate rainfall diagnostics truthful (P1)

**Problem:** The "Annual rain" overlay is based on a short EMA, which cannot tell whether a location received no rain across a year.

**Implement:**

1. Inspect `ClimateBootstrap`, `ClimateSnapshot`, `WeatherSimulation`, `WeatherPalette`, and `AtlasScene`.
2. Clearly relabel the existing smoothed value as a **30-day EMA annualized estimate**, not observed annual precipitation.
3. Expose the climate bootstrap's **actual observed 180-day precipitation total** and/or correctly annualized *sample-period mean* in the inspector/overlay (label the sample interval explicitly).
4. If the UI needs rainfall since interactive simulation start, add a minimal cumulative per-tile rainfall depth and elapsed simulated time. Label it **"Since start"** or **"Observed mean annualized"**; do not call an incomplete period a full annual total. Avoid an elaborate rolling-year storage system.
5. Retain per-update average and peak-substep rates; use enough decimal precision to distinguish light rain from exactly zero.

**Completion criteria:** The user can distinguish a genuinely rainless tile from one that rained earlier but now has a low 30-day EMA. No precipitation physics changed.

### [x] Step 3 — Add minimal moist-convective condensation (P2)

**Problem:** Warm, moist regions have little mechanism to condense vapor unless horizontal transport brings them into colder air.

**Implement:**

1. Introduce a small **convective lifting / effective cooling** proxy using existing fields: surface-to-air temperature contrast, atmospheric vapor availability / relative humidity, and optional sunlight. Keep it bounded and smooth.
2. Evaluate saturation at a slightly cooler **effective lifted-air temperature** for the convective portion of condensation. Transfer any newly condensed amount from `atmosphericWaterMm` to `cloudWaterMm` (never inject new cloud water).
3. Prevent repeated over-condensation in one substep and respect the existing condensation relaxation and cloud evaporation ordering.
4. Name and isolate a few initial tuning constants. Do not hardcode geographically defined rain belts, minimum rain quotas, or random per-tile showers.
5. Allow convection over sufficiently warm, humid ocean and land; do not force every sunny tile to rain.

**Completion criteria:** Warm humid regions have a physically motivated path to cloud development while dry air remains moisture-limited; vapor-to-cloud transfer is water-conserving. No separate storm or pressure system.

### [x] Step 4 — Add simple windward mountain uplift (P3)

**Problem:** Local air temperature relaxes on a one-day timescale, so fast winds can cross high terrain without enough condensation. Cold mountain surface temperature is not a substitute for forced ascent.

**Implement:**

1. Estimate the local **upwind terrain rise** or positive wind-direction slope from neighboring world elevations and the current wind vector; account for world X wrapping carefully and clamp/no-flux behavior at Y edges. Avoid a world-scale hydrology/terrain change.
2. Translate **positive uphill motion** into a bounded effective condensation cooling/lift term. Use only existing vapor as the cloud-water source. Do not apply the same enhancement downhill.
3. Combine with Step 3 cleanly, with explicit bounds to avoid unrealistic condensation when both effects are strong.
4. Keep this independent of immutable biome labels, including `Terrain.MOUNTAINS`.

**Completion criteria:** Windward slopes can generate enhanced cloud formation; leeward areas are not given an artificial rain boost. No erosion or rainfall directly injected into soil.

### [ ] Step 5 — Remove ground-anchored rain activation (P4)

**Problem:** `rainActive` stays on the map tile while cloud water is advected, so rain history is not tied to the moving cloud mass.

**Implement:**

1. Revisit the existing 2.0 mm start / 0.5 mm stop hysteresis after Steps 1–4. Preserve distinct rain events if possible.
2. Choose **one** minimal coherent strategy:
   - A lightweight **advected rain-development scalar** that uses the same conservative transport as cloud water, with bounded activation/deactivation; **or**
   - A stateless cloud-water-based nonlinear rainout rule, if the simpler continuous behavior is acceptable.
3. Do not transport a raw Boolean with nearest-neighbor jumps or leave stale fixed-location rain state behind.
4. Keep precipitation amounts limited by available cloud water; preserve correct reporting and hydrology input amounts.

**Completion criteria:** Rain follows atmospheric/cloud evolution rather than a lingering map-cell flag. No separate large-scale storm simulation.

### [ ] Step 6 — Improve evaporation's moisture and day/night behavior (P5)

**Problem:** Ocean evaporation is zero at night and insensitive to air humidity; ocean moisture supply can be weak, while fully saturated areas can receive continued evaporation.

**Implement:**

1. Replace the purely instantaneous-solar ocean evaporation multiplier with a small, tunable **nonzero day/night baseline** and a **vapor saturation-deficit factor**; optionally include existing wind speed.
2. Keep ocean as an implicit unlimited water reservoir. Do not invent a closed-ocean inventory.
3. Keep land evaporation limited by available soil water; where practical, make its solar dependence consistent with the cloud shading applied to surface heating.
4. Change only a few named parameters and avoid excessive global evaporation. Inspect the new vapor/rain behavior conceptually before further tuning.

**Completion criteria:** A dry ocean air mass can gain moisture at night; evaporation decreases as air approaches saturation; no negative soil water or arbitrary moisture creation over land.

### [ ] Step 7 — Reduce misleading cloud opacity and improve precipitation display (P6)

**Problem:** `cloudCover()` reaches full opacity at 0.8 mm, far below the 2.0 mm rain-start threshold, and all clouds render as the same near-white color.

**Implement:**

1. Retain cloud cover as a meaningful distinct variable, but make its **rendered alpha** less aggressive and/or differentiate thin overcast from dense cloud water.
2. Improve cloud transitions at zoomed-out map scales without expensive new rendering. Preserve the current aesthetic and existing layer toggles.
3. Ensure precipitation overlay and inspector make light rain distinguishable from zero, with legends/labels consistent with units and averaging period.
4. Do **not** use the renderer to hide unresolved physical rain deficits.

**Completion criteria:** Light and thick cloud masses no longer look equally opaque; clouds can still appear without rain; existing terrain remains legible.

---

## Optional work — only with explicit user approval

### [ ] Optional Step 8 — Address residual numerical cloud diffusion

Only if cloud-water bands still look overly smeared **after Step 1**. Consider a conservative monotone second-order reconstruction/flux limiter for vapor and cloud water. Do not introduce overshoots, negative moisture, or nonconservation. Do not tackle this before the baseline face-flux scheme is coherent.

### [ ] Optional Step 9 — Introduce traveling weather disturbances

Only if the corrected circulation, convection, and mountain uplift still yield unnaturally stationary cloud/rain zones. Add a small number of smooth moving uplift disturbances, not scripted rainfall or a full pressure solver. Avoid duplicating the existing two traveling wind waves without clear benefit.

---

## Boundaries and interpretation

- **Do not** add fake rainfall floors, fixed wet zones, or random precipitation unrelated to atmospheric water.
- Distinguish **water conservation** from **climate realism**: exact water transfer alone cannot create tropical convection or storm systems.
- The previous numerical concentration figures were a reproduction of the formulas, **not** measurements from the running Kotlin game. Do not represent them as current live diagnostics.
- A real full-year rainfall total cannot be inferred from a 30-day EMA or a 180-day climate sample. Name each timescale correctly.
- Climate-derived biome initialization and future hydrology should receive correctly calculated precipitation; avoid changing their contracts except where necessary to correct existing diagnostics.
- This plan is not a request for tests, profiles, benchmarks, or new unrelated documentation.

## Implementation log

_Append one concise entry per completed invocation, then stop._

| Step | Status / date | Files changed | Key decisions / caveats |
|---|---|---|---|
| 1 | Complete / 2026-11-09 | `WeatherSimulation.kt`, `PLAN.md` | Replaced scatter with shared upwind face fluxes, discrete streamfunction face winds with periodic X and zero-flow Y edges, and transport-only CFL substeps (0.8 outgoing Courant limit). Temperature uses the same conservative mixing flux; no physics tuning. Compile is the only planned check; no tests run. |
| 2 | Complete / 2026-11-09 | `WeatherSimulation.kt`, `AtlasScene.kt`, `PLAN.md` | Relabeled the 30-day EMA annualization, exposed actual sampled-period rainfall total and duration in the inspector, clarified rain-rate overlay labels, and raised rain readouts to three decimals. No precipitation physics changed. |
| 3 | Complete / 2026-11-10 | `WeatherSimulation.kt`, `PLAN.md` | Added bounded surface-air-contrast lift, humidity gating, and a six-hour condensation response. Convective condensate is limited by vapor remaining after ordinary condensation; no water is injected. Compile is the only planned check; no tests run. |
| 4 | Complete / 2026-11-10 | `WeatherSimulation.kt`, `PLAN.md` | Added one-tile upwind bilinear elevation sampling with periodic X and clamped Y, a bounded positive-rise cooling term, and a 7°C cap shared with convection. Terrain uplift only changes saturation for existing vapor; no biome or hydrology rules changed. Compile is the only planned check; no tests run. |
