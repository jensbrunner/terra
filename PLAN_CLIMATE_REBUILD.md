# TERRA — A New Climate Engine From Scratch

**Scope:** the long-timescale climatic simulation and its effect on the world. Preserve detailed weather as a separate, optional short-timescale solver.  
**Canonical repository:** https://github.com/jensbrunner/terra  
**Code inspected:** `8ba3e3071e45074a55335b1f2c031f534171a1d1` (inspect latest HEAD before implementation).

## 1. The decision

**Do not build another reduced atmospheric dynamics solver.** Build a *seasonal climate atlas* and a *minimal land-evolution integrator*.

An atlas maps the current geography into 12 monthly fields: mean air/surface temperature (°C), rainfall (mm/month), prevailing wind (m/s), and potential evapotranspiration (mm/month). Those fields are calculated from simple, geography-sensitive mechanisms. Annual rainfall is the sum of the 12 monthly depths, so the displayed number has a clear definition and a defensible order of magnitude.

The atlas is *diagnostic climatology*, not a forecast of individual weather events. If geography remains stable, reuse it; there is no value in recomputing the same mean rain shadow for every simulated month. The fast-forward loop samples month-specific climate and advances soil, snow, drought and vegetation. If elevation, sea level or land cover of the *geographic* kind changes, rebuild the atlas. Optional annual weather variability is a small coherent multiplier on the monthly forcing, not a second atmospheric simulation.

This intentionally gives up exact conservation of atmospheric vapor and time-dependent pressure physics in climate mode. There is **no fake atmospheric vapor reservoir** and **no claim that these maps are meteorological predictions**. Local water stores can still be accurately accounted for given the supplied rain and evapotranspiration fluxes. In detailed mode, continue to use the existing real fluid solver, with an explicit climatological initialization at transition.

**Success:** believable, geographically varied forests/grassland/desert/tundra; intuitively sensible rain shadows, coasts, continental interiors, wet seasons and temperatures; immediate meaningful mm/year overlays; responsiveness to terrain edits; sustained ~10 simulated years per real second near 100 FPS on the target desktop.

## 2. Minimal model and data ownership

Four concepts, ideally no more than ~3–4 cohesive climate classes:

1. **`ClimateGeography`** — immutable snapshot of actual current elevation in meters, ocean/land mask, coast distance, slopes and basic land/soil properties. It has a **`geometryRevision`**. This is not the current displayed biome enum. A desert becoming forest does not change the elevation or ocean mask.
2. **`SeasonalClimateAtlas` / `ClimateAtlasBuilder`** — a deterministic, side-effect-free calculation of 12 monthly maps. These are targets given geography, orbital settings and a small set of coefficients. Use one climate grid, initially **96 × 72** (the existing detailed-atmosphere resolution) if affordable; test 48 × 36 as an explicitly supported performance fallback. The resulting fields can be interpolated onto the 283 × 212 map. Do not introduce a second climate-specific physics state merely to exchange between grids.
3. **`ClimateEvolution`** — one lightweight monthly update over physical map tiles: rain/snow, soil water, potential versus actual evapotranspiration, runoff, drought history and vegetative suitability. It does not integrate pressure or perform wind advection. Prefer reusing a clean subset of `TerrainMoistSurface` and the current ecological stores.
4. **`ClimatePresentation`** — clear monthly rain, annual rain, temperature, wind, aridity and biome overlays. A displayed annual mm/year value is sourced from the monthly atlas, not a short-term 30-day rate multiplied by 365.

Use separate **geometry** (ocean, mountain/elevation, soil substrate) and **land cover** (forest, grassland, shrubland, desert, tundra). Terra currently uses `Terrain` for physical and ecological classification; cleanly separate these roles so changes in vegetation cannot accidentally invalidate geography or change ocean handling. Keep this refactor as small as possible.

## 3. Build the 12 monthly atlases

### 3.1 Temperature: geography first, no heat PDE

For each grid location and month:

\[
T_m(x,y) = T_{annual}(latitude) + A(latitude, continentality)\,\mathrm{season}(m, hemisphere, oceanLag) - \Gamma\,h_{km}(x,y) + small\_local\_correction.
\]

- Derive the broad annual latitudinal thermal gradient and month of maximum heating from `PlanetarySunlight` / axial tilt. A modest fitted latitude-to-°C curve is fine; this is a *game climatology*, not radiation transfer.
- Elevation lapse-rate starting point: **6 °C/km**. Mountains should be cold because they are high, not because a biome label says `MOUNTAINS`.
- Ocean and coasts have small seasonal amplitude and thermal lag; inland continental locations have larger summer/winter swings. **Continentality** can come from a cheap distance-to-ocean transform, recalculated with the geography.
- If desired, smooth the resulting field very lightly to avoid individual tile spikes, **not** enough to erase coastal/mountain contrasts. Absolutely no multi-sweep global heat homogenization.
- Do not prognostically evolve cloud radiative feedback, vapor emissivity, surface/ocean heat stores or a separate air layer in this first implementation.

A simple formula is preferable to an elaborate one whose constants cannot be interpreted. Initial constants are *starting points for visual calibration*, not physical truth.

### 3.2 Wind: one simple, smooth field

Use a broad latitude-dependent circulation that transitions smoothly between trade easterlies, midlatitude westerlies and polar easterlies. Displace the convergence region north/south with season. Typical speed: a few m/s, and cap extremes.

Add, if it materially improves maps, a **weak local summer-onshore / winter-offshore component** from the coast-facing gradient of land/ocean thermal contrast. This makes monsoon-like seasonal reversals possible without a pressure PDE. Orographic precipitation uses actual wind direction. Do **not** impose prescribed regional *rainfall* bands or Gaussian pressure-belt fields. Winds may be idealized; rainfall must still depend on geography and moisture supply.

No Poisson projection, pressure prognostic, vortex perturbations, geostrophic reconstruction or viscosity tuning in fast climate.

### 3.3 Moisture and rainfall: a small steady-state wind-fetch solver

This is the only nontrivial numerical stage. Do it **once per month of the atlas**, not once per day of every simulated year.

Represent **diagnostic column humidity** `q(x,y)` as mm of liquid-water equivalent. It is a mean moisture availability variable, *not* a persistent atmospheric store. Warm ocean water supplies moisture; land can recycle a bounded fraction of prior rainfall; moving air loses moisture via background rainout, convective/convergence rain and orographic lift.

Choose one easy-to-understand **upwind fixed-point** algorithm instead of explicitly advancing daily air flow:

1. Initialize ocean q near a temperature-dependent moist equilibrium; initialize land q by interpolation or a few warmup sweeps.
2. For each cell, sample `q_upwind` from the prevailing wind's source direction (longitude wraps; latitude boundaries do not).
3. Estimate the time for wind to traverse the local cell: `travelDays = cellWidth / max(minWind, |wind|) / 86400` (use the correct projected distance for diagonal motion).
4. Over ocean, relax moisture toward `q_ocean(T)` with a replenishment length/time scale. Over land, add a limited recycled-vapor source tied to already modeled rain (e.g. a capped fraction of previous iteration's local rain); this must never become an independent unlimited source.
5. Compute a local **rainout rate** `lambda` (1/day) from small, interpretable terms:
   - background precipitation/convective climate, moderately influenced by latitude and seasonal convergence;
   - directional uplift proportional to `max(0, wind · gradient(elevation))`;
   - optional local convergence and cooling/saturation limit.
6. Obtain a stable local moisture/rain pair. A useful equilibrium relation to investigate is:

\[
q_i = \frac{q_{up,i} + \tau_i (E_{land,i} + k_{ocean,i} q_{ocean,i})}
              {1 + \tau_i (\lambda_i + k_{ocean,i})}, \qquad
P_i = \lambda_i q_i \quad \text{mm/day}.
\]

Here `k_ocean` is nonzero only over water and all terms have consistent day-based units. If local temperature drops below the humidity capacity, excess q can be condensed with an explicitly bounded additional rain term. This formula is **a design candidate**, not a claim of validated physics; tune or simplify it after looking at maps.

7. Run a small, fixed number of whole-grid sweeps, preferably warm-started from the previous month, until the map is visually and numerically stable. Evaluate convergence and cap iteration costs. Recompute the 12 months cyclically once or a few times if needed to remove the arbitrary initial-month bias.
8. Store `P_month = P_day × daysInMonth`. Sum monthly totals for mm/year. Do not add independent noise to precipitation amounts merely to make the map less striped.

For an even simpler alternative, a *backward wind-trajectory / moisture-fetch integration* is acceptable if it produces better results with fewer moving parts. The agent should pick one approach, not retain competing transport models.

**Why this is elegant:** ocean humidity provides real geographical moisture sources; transported moisture can reach inland; mountain uplift can deplete it before the leeward side; wet tropics and dry subtropics are modest tendencies rather than complete map templates. Wind reversal must reverse rain shadows. The solver needs no live cloud field, storms, pressure or fine-timestep advection.

**Important limitation:** this diagnostic solver is not strictly mass-conservative across the atmospheric map, especially at branching/converging flow. Do not claim otherwise. The objective is credible seasonal rain climatology in correct units, not an atmospheric water accounting theorem.

### 3.4 Small-scale terrain detail, without inventing rain

A 96 × 72 climate grid is about 125 km per cell on Terra's default 12,000 × 9,000 km domain; terrain tiles are about 42 km. Treat fine terrain as a **downscaling** problem:

- Assign each tile its parent atmospheric cell's monthly rainfall and temperature.
- Split **the parent-cell rainfall total** across its tiles with bounded windward/lee weights derived from local directional slopes and short downwind sheltering. Normalize weights within each parent cell so the parent mean is preserved.
- Adjust tile temperature for its own height relative to mean coarse elevation.
- Avoid visible rectangular climate-cell edges with conservative interpolation or softly blended weights. Use one consistent representation when publishing mm/year.

Small isolated mountains can affect within-cell rainfall location but cannot generate unlimited additional precipitation when the regional moisture solver has none. If small-ridge rain shadows prove visually weak, improve the directional regional moisture path or increase climate resolution before adding artificial dry masks.

## 4. Make the world visually good *immediately*

A new world should not require hundreds of simulated years before showing interesting forests and deserts.

At world creation, use the twelve-month atlas to compute an **equilibrium ecological suitability**:

- Annual rainfall `P = Σ_m P_m` (mm/year).
- Annual/seasonal temperature, frost months and growing-season warmth.
- Potential evapotranspiration `PET` from month temperature and sunlight (simple bounded empirical formula); calculate an aridity index `P / max(PET, epsilon)`.
- Soil substrate/rock slope and optional drainage/wetness, independent of climate.

Use continuous ecological suitability or soft classification inspired by Whittaker/Holdridge rather than a dozen rigid rainfall thresholds:

- **Desert:** sustained moisture deficit, sparse vegetation; a cold dry region should not necessarily look like a hot sandy desert.
- **Steppe/grassland:** seasonal/subannual moisture with sufficient growth warmth.
- **Forest:** adequate growing-season warmth and moisture balance, with gradual transitions.
- **Tundra:** cold growing season, regardless of whether annual precipitation is low or moderate.
- **Bare alpine terrain/snow:** elevation, cold and substrate take precedence.

Initialize vegetation near these plausible suitability values, with spatially coherent structural variation from the terrain/soil seed. Retain separate *persistent* tree/grass/shrub cover and allow fire, disturbance, drought and succession to move the world away from equilibrium and back again. Geological terrain must not be overwritten by biome labels.

Avoid using procedural noise to fake rainfall geography. It is fine to use bounded noise for soils, forest patchiness and local cover texture; these vary *within* a credible regional climate.

## 5. Monthly evolution at 10 years per second

The hot path is intentionally boring:

```kotlin
fun advanceOneMonth(month: Int, year: Long) {
    val climate = atlas[month]  // tempC, rainMm, petMm, wind
    applyRainAndSnow(climate.rainMm, climate.tempC, optionalYearAnomaly(year))
    updateSoilWaterAndEvapotranspiration(climate.petMm)
    updateSnowMeltAndRunoff(climate.tempC)
    accumulateGrowingSeasonAndDrought(climate)
    if (monthIsYearBoundary(month)) evolveVegetationOnce()
}
```

Only surface reservoirs and ecology evolve. No atmosphere transport and no full 12-month atlas rebuilding in the normal month loop. If year-to-year variability is wanted, use a seeded *spatially coherent* wet/dry anomaly varying smoothly over 2–10-year timescales, e.g. multiplicative ±10–25% rainfall and ±1–2 °C, with no independent pixel noise. These anomalies are optional, should preserve plausible regional structure, and should not alter the displayed long-term climatological rainfall amount.

At **10 simulated years per wall second**, there are **120 monthly updates per wall second**, not thousands of daily climate physics steps. On the default 283 × 212 terrain, one visit to every tile per month is about **7.2 million tile visits/second**, plus ten vegetation updates per second. This is a modest baseline workload for a tuned Kotlin/JVM engine; profile it rather than promising a benchmark result in advance.

Preallocate primitive arrays, avoid per-tile object allocation, and keep published snapshots separate from mutable owner-thread state. Process full months in chronological order. When time speeds are slower, the same atlas provides climate forcing without pretending monthly means are detailed weather.

## 6. Truly changing terrain

Treat terrain modifications as real inputs, not an afterthought:

- A geometry edit updates elevation/ocean membership/soil substrate and increments **`geometryRevision`**. A mere biome-color change increments a separate ecology revision.
- The atlas is tagged with the geometry revision and orbital/settings revision from which it was built.
- On geometry change, rebuild coast distance, slopes, and **all 12 climate months**; publish the replacement atlas atomically. If full rebuilding causes a visible frame stall, distribute one or two months' build work across worker transactions or use a dedicated immutable builder snapshot. Coalesce successive edits; don't proliferate incremental special cases initially.
- Preserve soil/snow/vegetation inventories on surviving land. On land↔sea conversion, use a documented straightforward migration or reset rule; don't silently reinterpret a forest tile as an ocean with 250 mm of soil.
- Changes in terrain should predictably alter downstream climate: raise a ridge → new windward/lee contrast; open a sea passage → more maritime influence; remove a mountain → altered rain shadow.
- The year-scale ecological response should be smooth even when the atmospheric climatology changes abruptly. Existing forest cover doesn't instantaneously transform into sand.

## 7. Integration with Terra and deliberate deletion

Current code has a 48 × 36 finite-volume climate transport solver that takes 10-day phases, mixes sensible heat, and carries effective water through `MoistAtmosphere`. It is well implemented for its own objective, but that is the **wrong abstraction** for this new design. Do not recreate it under a new class name.

Replace climate-mode ownership in `DynamicalWeather` / `WorldSimulation` with the atlas and monthly evolution. Reuse good code such as `PlanetarySunlight`, terrain geometry, world/vegetation state, map presentation primitives and the detailed `DryAtmosphere`/`MoistAtmosphere` solver. Consider deleting or rewriting:

- `ReducedClimate.kt` (energy balance, diagnostic winds and vapor PDE),
- `FastClimateStepper.kt` and `ClimatePhasePlan.kt` (10-day climate transactions),
- climate-only parts of `TerrainMoistSurface` and climate flux ledgers,
- climate-only atmosphere coupling that treats instantaneous vapor as a shared centuries-long inventory,
- duplicated climatological observation and aggregate estimate paths,
- unused climate transport grids/helpers, experiments and tests.

Do **not** delete working detailed-weather functionality because climate no longer needs it. Instead, make the mode boundary clear. The atlas can provide current `T`, mean q/RH, winds and rain tendency to initialize a plausible **detailed atmospheric realization** when the user returns to slow speed. The detailed solver then uses its own proper physical state. Do not pretend that the atlas's diagnostic q is an exact historical vapor inventory; report the conceptual distinction clearly.

The deliverable should leave one authoritative long-time climate implementation, not both the old and new engines.

## 8. Visual calibration, not test bureaucracy

Generate climate and biome maps for a few controlled worlds and compare consequences:

1. **Ocean + continent:** coast more maritime, interior more seasonal; precipitation should not be a pure latitude gradient.
2. **East–west winds + north–south ridge:** clear windward wet/lee dry pattern; reverse wind and the contrast reverses.
3. **Tropical/subtropical world:** humid tropical forests and genuinely dry subtropical regions, when ocean access/geography permit.
4. **Mountain plateau/cold pole:** dry or wet cold tundra/alpine cover, not arbitrary desert colors.
5. **Terrain change at year 50:** precipitation responds rapidly; forests/deserts respond over years rather than instantly.
6. **Two/three seeds:** irregular spatial structure but stable regional environmental causality.

Plausibility bands **for calibration**, not rigid tests: dry desert often <250 mm/year; typical temperate regions on the order of ~500–1500 mm/year; some tropical windward/mountain regions >1500–2500 mm/year. Cold and hot regions with equal rain should often support different vegetation because evapotranspiration differs. Rain shadows should follow *wind*, not fixed north/south/east directions.

Display maps for annual precipitation (mm/year), one chosen month's precipitation (mm/month), mean temperature (°C), aridity/PET ratio and biome. Ensure seams are smooth and changing terrain does not expose climate-cell rectangles.

Track performance on the actual desktop: FPS, achieved years/second, atlas rebuild latency and per-month update cost. **Keep the 10y/s and ~100fps targets**, but profile and adjust grid resolution or solver sweeps rather than adding caches and abstractions preemptively.

## 9. Sequence of implementation

1. **Prototype the atlas in isolation** with current terrain and 12 climate maps. Verify compelling visuals before touching existing mode switching. Adjust the small parameter set using controlled geography.
2. **Add minimal surface evolution** (soil water, snow and vegetation suitability), and initialize attractive but grounded vegetation at world creation.
3. **Wire it into fast-forward** at monthly resolution. Expose honest rain/temperature units and a simple detailed-weather initialization boundary.
4. **Support terrain edits** using geometry revisions and full atlas rebuild; start with a simple solution and add amortization only if profiling shows stalls.
5. **Delete superseded climate code**, dead adapters, flags, tests, reports and experiments. Keep concise up-to-date architectural documentation.
6. **Measure the real experience** across seeds, geography changes and 10y/s. Tweak the few dominant parameters, not more systems.

The coding agent may substitute a more elegant moisture-equilibrium method if warranted. The constraints are **small model, interpretable rules, directional geographical precipitation, real units, evolving terrain support, and sustained fast-forward**.

## Bottom line

**Geography determines the climate; climate determines available water; available water and temperature determine vegetation; terrain edits can change the whole chain.**

No pressure solver, clouds, explicit atmospheric energy PDEs, daily weather transport, or millions of substeps are required to get the visual and systemic outcome we actually want.

The best model is the one whose precipitation and biome maps make immediate geographical sense *and* that can evolve a century without the user seeing a performance penalty.

### Research basis

- Budyko/Sellers-style simple energy-balance models (motivation for low-dimensional temperature forcing): https://scripts.guz.uni-tuebingen.de/spacy/lowebms/models.html
- Smith & Barstad (2004), **A Linear Theory of Orographic Precipitation** (upwind slope, downstream fallout and rain shadows; not a mandate to implement the full theory): https://doi.org/10.1175/1520-0469(2004)061%3C1377:ALTOOP%3E2.0.CO;2
- Holdridge/Whittaker biome system comparison (temperature, rain and PET/aridity as major biome axes): https://www.nature.com/articles/s41597-025-04387-0
- Dwarf Fortress advanced world generation (pragmatic geography-driven initial climate maps): https://dwarffortresswiki.org/index.php/Advanced_world_generation
