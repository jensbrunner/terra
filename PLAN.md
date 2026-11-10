# VISION_AND_PLAN.md — Terra: A Living Planet Without Spherical Complexity

**Status:** Proposed architectural direction and implementation roadmap  
**Repository:** https://github.com/jensbrunner/terra  
**Reviewed baseline:** `main` at `2d4028bc193e6c33c39e1636290198249753c73e`, 10 November 2026  
**Guiding principle:** Maximum visible emergence, beauty, and plausible physical behavior for minimum implementation complexity and runtime cost.

---

# Part I — Vision and Design Decisions

## 1. The vision

Terra is a two-dimensional world simulator in which complex, beautiful, and surprising behavior emerges from relatively simple underlying systems.

Imagine opening the world and watching the atmosphere evolve.

A pressure disturbance develops over a warm ocean. Winds organize around it. Moist air converges toward the disturbance. Clouds gradually form, stretch into bands, and move across the sea.

As the system approaches land, the changed surface roughness alters its winds. Moisture reaches a mountain range, producing rainfall along the windward slopes. Beyond the mountains, the air becomes drier.

Rain feeds rivers, rivers fill lakes, and water gradually changes the landscape.

Elsewhere, persistent atmospheric conditions produce drought. During another season, changing sunlight shifts the patterns of heating and rainfall.

Over decades, regions become wetter or drier, vegetation responds, and ecosystems change.

**None of these events should require a script to spawn a storm, select a rainfall location, or declare a drought.**

The atmosphere should create the weather. Weather should drive hydrology. Climate should emerge from weather over time. The landscape should respond.

But Terra is not trying to become a professional numerical weather prediction system.

Its central ambition is to be a **beautiful, computationally economical, emergent world simulator**.

### The most important engineering question

Before adding any equation, subsystem, atmospheric layer, parameter, or numerical method, ask:

**Does this make the simulated world substantially more alive, believable, or beautiful relative to its complexity and performance cost?**

If the answer is no, omit it.

Simplicity is not merely a practical constraint. It is a core design objective.

---

## 2. Confirmed product decisions

The following decisions reflect the user's expressed preferences.

They are defaults, not immutable technical requirements.

| Dimension | Preferred direction |
|---|---|
| World presentation | Entirely 2D, top-down, rectangular world map |
| Global geography | A planet-like world with recognizable hemispheres and polar regions |
| Atmospheric geometry | Flat Cartesian grid, not spherical numerics |
| Longitude | Periodic east–west |
| Latitude | Nonperiodic north–south, approximate polar walls |
| Latitude mapping | Linear pseudo-latitude |
| Atmospheric behavior | Physically meaningful, visually compelling emergence |
| Storms | No scripted storm spawning |
| Cloud visualization | Improved in parallel with physics |
| Seasons | Introduce relatively early |
| Detailed weather speed | Aim for several simulated hours per real second |
| Long-term speed | Eventually support years/s and approximately 10–100 years/s using approximate climate evolution |
| Fast climate | Preserve climate averages **and meaningful weather extremes** |
| Atmospheric layers | Remain single-layer unless experiments justify another |
| Codebase | Retain Kotlin/KorGE and reuse the current atmosphere and hydrology |
| Implementation freedom | The implementation agent may depart from this plan when there is a convincing reason |

### Interpretation of “full planet”

Terra should feel like one continuous planet.

It should have a warm equatorial belt, distinct northern and southern hemispheres, high-latitude climates, seasonal differences, and east–west continuity.

However, **the physical simulation does not need mathematically exact spherical geometry**.

In particular:

- Map cells may retain equal physical area.
- Longitude need not geometrically converge at the poles.
- Atmospheric motion need not cross the poles.
- Exact global angular-momentum behavior is not required.
- True spherical atmospheric waves are outside the initial scope.

These are deliberate approximations.

The result should be described as a **global, latitude-aware, cylindrical-channel approximation**, not a scientifically exact global circulation model.

---

## 3. The chosen physical model

### Preferred model: one-layer thermal moist shallow water on a periodic channel

Keep the newly developed atmospheric dynamics as the foundation.

The atmosphere remains a two-dimensional grid of columns, with each column representing the vertically integrated state of an idealized atmosphere.

The current solver already evolves:

- Atmospheric layer thickness `h`, representing a pressure-like mass field.
- Horizontal momentum `hu` and `hv`.
- An effective atmospheric temperature.
- Atmospheric water vapor.
- Cloud liquid water.

It also includes radiation, surface heat exchange, condensation, precipitation, and latent heating.

This is a much better starting point than building another weather model.

### Target topology

Use a rectangular domain with:

- **Periodic X:** Air leaving the eastern edge reenters at the western edge.
- **Bounded Y:** Air cannot travel through the northern or southern boundaries.
- **Signed latitude:** Northern and southern halves have opposite Coriolis signs.
- **Seasonal sunlight:** Temperature forcing depends on latitude, longitude, time of day, and time of year.
- **Uniform grid spacing:** Preserve straightforward Cartesian finite-volume calculations.

This is a generalized latitude-dependent rotating channel.

It borrows useful behavior from beta-plane atmospheric models but extends the pseudo-latitude approximation across the whole depicted world.

It is not intended to be a rigorous local beta-plane approximation valid at every latitude.

### Why this approach?

It preserves nearly all of Terra's existing numerical infrastructure:

1. Rectangular arrays.
2. Simple cell indexing.
3. Existing finite-volume fluxes.
4. Shared conservative moisture transport.
5. Existing thermal and cloud physics.
6. Current atmosphere-to-terrain mapping.
7. Equal-area hydrology accounting.
8. Existing rendering and UI architecture.

The main changes concern **boundary conditions, Coriolis, external forcing, and geographical feedback**.

That is far preferable to introducing reduced spherical grids, polar-cell connectivity, nonuniform cell areas, and spherical momentum equations.

### What we must not overclaim

Even after these improvements, a one-layer system cannot resolve genuine vertical atmospheric circulation, vertical wind shear, or classical baroclinic instability.

It may produce visually compelling storm systems and meaningful hemispheric differences, but it will not necessarily reproduce Earth's actual Hadley circulation, subtropical jets, frontal dynamics, or polar meteorology.

The implementation must remain honest about these limits.

If the model produces excellent weather, these shortcomings are acceptable.

---

## 4. Architecture: Keep the existing code

### Current repository state

The repository contains a substantial working experimental implementation.

| Existing component | Role | Recommended treatment |
|---|---|---|
| `DryAtmosphere.kt` | Finite-volume shallow-water dynamics | **Keep and extend** |
| `MoistAtmosphere.kt` | Thermal/moist atmospheric physics | **Keep and refine** |
| `MoistColumnTransport.kt` | Transport of mass-weighted scalars | **Keep** |
| `DryScenarios.kt` | Initial jet, vortex, pulse, rest scenarios | Keep as experiment tools |
| `AtmosphereTileMapping.kt` | Coarse/fine area-overlap mapping | Keep equal-area approach |
| `DynamicalWeather.kt` | Adapter between dynamical atmosphere and game | Keep |
| `TerrainMoistSurface.kt` | Surface water, evaporation, land/ocean coupling | Extend carefully |
| `WeatherModel.kt` | Shared game-facing weather interface | Preserve |
| `HydrologyTopology.kt` | Rivers, drainage topology, lake basins | Preserve and adapt topology |
| `HydrologySimulation.kt` | Runoff, lake storage, discharge | Preserve |
| `TerrainMapView.kt` | Terrain, rivers, lakes, weather overlays | Improve cloud renderer |
| `AtlasScene.kt` | World lifecycle, update scheduling, mode switching | Improve speed management |
| `World.kt` | Elevation and terrain generation | Make longitude seamless |
| `ClimateBiomeClassifier.kt` | Temperature/rainfall-derived biomes | Preserve, improve climate input |
| `WeatherSimulation.kt` | Older prescribed weather | Retain temporarily as fallback |

Avoid introducing additional layers of architecture merely to make this table more abstract.

A small shared configuration or geometry helper is appropriate. A large hierarchy of atmospheric interfaces, model factories, grids, and strategies is not automatically desirable.

### Current verified numerical properties

The dynamical prototype uses:

- A 96×72 atmospheric grid.
- A 12,000 km × 9,000 km Cartesian domain.
- An approximately 125 km grid spacing.
- A fixed 20-second physical timestep.
- MUSCL reconstruction.
- Conservative shared-face mass and momentum fluxes.
- A shear-resolving HLL-type flux.
- SSPRK2 integration.
- Exact split Coriolis rotation.
- Reusable primitive-array buffers.
- A coupled thermal pressure closure.
- Conservative mass-weighted tracer transport.

The moist prototype has reported strong water-conservation results in its short standalone experiments.

Those results are encouraging, but **short, idealized experiments do not establish long-term climatic realism or stable gameplay throughput**.

### Important limitations at the reviewed baseline

The current implementation still has:

1. Periodic boundaries in both dimensions.
2. One constant Coriolis parameter everywhere.
3. Solar forcing without latitude dependence.
4. Initially prescribed perturbed jet conditions rather than demonstrated self-sustaining climatology.
5. No terrain-induced atmospheric uplift.
6. No surface-roughness-dependent momentum drag.
7. No resolved vertical convection.
8. Coarse cloud visualization.
9. Climate-based biome classification after only 30 simulated days in dynamical mode.
10. A 20-second fixed timestep and a maximum of eight steps per frame.
11. A water cycle whose hydrological outlet exports are not consistently returned to the world's ocean reservoir.
12. Terrain and hydrology geometries that do not yet wrap seamlessly in longitude.

These deficiencies should guide implementation priorities.

Do not rewrite subsystems that already work.

---

# Part II — Physical Design

## 5. Latitude and planetary identity

### Minimal latitude mapping

Keep the existing grid unchanged.

Define a signed pseudo-latitude for each atmospheric row.

A suitable starting mapping is:

`latitude = -85° + (y + 0.5) / height * 170°`

where the atmospheric Y coordinate increases northward.

The terrain grid uses the opposite Y convention: terrain Y increases downward, so its mapping must be correspondingly reversed.

Prefer one small shared latitude helper to duplicating the conversion across weather, terrain, and UI code.

### Why stop at ±85°?

The top and bottom rows should behave like high-latitude polar regions rather than mathematical points.

The last few geographical degrees are folded into the represented polar bands.

This avoids pole-crossing rules and singularities entirely.

The exact endpoint, whether ±80°, ±85°, or ±90° as a label, is less important than consistently representing the approximation.

### Domain dimensions

Initially retain the current 12,000 × 9,000 km computational domain.

Do not automatically force the map into Earth's 2:1 longitude-to-latitude aspect ratio.

The existing rectangular world and 283×212 terrain grid are part of Terra's visual identity.

The physical scale and pseudo-latitude scale will therefore not exactly correspond to terrestrial great-circle distances.

That is acceptable, provided the model does not pretend otherwise.

If changing the domain's physical width or height produces substantially better circulation at modest cost, the implementation agent may propose or implement a documented adjustment.

---

## 6. Latitude-dependent Coriolis

The current model uses a constant:

`f = 1e-4 s⁻¹`

Instead, calculate a signed value per atmospheric row:

`f(y) = 2 * rotationRate * sin(latitude(y))`

Start with an Earth-like angular rotation rate:

`rotationRate ≈ 7.2921159e-5 rad/s`

This gives:

- Negative Coriolis in the Southern Hemisphere.
- Approximately zero Coriolis at the equator.
- Positive Coriolis in the Northern Hemisphere.
- Stronger deflection toward high pseudo-latitudes.

Because this remains a Cartesian grid, the sine profile represents an idealized planetary-vorticity gradient, not full spherical mechanics.

### Implementation approach

`DryAtmosphere` currently precomputes constant sine/cosine factors for exact Coriolis rotation.

Replace them with per-row factors.

Do not replace the exact rotational update with an unnecessarily approximate explicit force.

The existing split rotation mechanism is a valuable numerical design and should be retained where applicable.

### Acceptance criteria

- Northern and southern equivalent vortices exhibit opposite rotational tendencies.
- Equatorial motion experiences the expected weak Coriolis deflection.
- Coriolis rotation alone does not artificially create kinetic energy.
- No discontinuity occurs at the equator.
- Existing mass and tracer conservation properties remain intact.

---

## 7. North/south atmospheric boundaries

### Chosen approximation

Use **impermeable, free-slip polar walls**.

Physically:

- No atmospheric mass flows beyond the north or south edge.
- The velocity normal to the boundary is zero.
- Tangential motion is permitted.
- Pressure-like forces can act against the boundary.

The eastern and western edges remain periodic.

### Implementation caution

The current solver connects the final Y row directly to the first.

It is not enough to remove that connection and leave the corresponding finite-volume flux absent.

Correct reflective boundary treatment must also account for normal momentum and pressure forces at the wall.

A reflected ghost state or equivalent numerical wall-flux treatment is appropriate.

Use the simplest approach compatible with the existing HLL flux implementation.

### Potential wall artifacts

Reflecting boundaries can create artificial wave reflections, trapped disturbances, or overly persistent high-latitude circulation.

These effects are not necessarily bugs in the equations; they are consequences of the approximation.

First determine whether they are visually or numerically problematic.

Only if necessary, introduce a **narrow high-latitude sponge zone** that gradually damps excessive velocity or disturbances.

Any damping must be explicit, deterministic, and included in the appropriate energy accounting.

Do not add large artificial damping bands preemptively.

### Explicit limitation

Atmospheric systems cannot travel across the poles.

The visual world can still have meaningful polar regions and polar seasons.

That compromise is accepted.

---

## 8. Planetary sunlight and seasons

This is one of the highest-value improvements.

### Astronomy

Introduce a minimal seasonal solar model using:

- Rotation period.
- Orbital period.
- Axial tilt.
- Current simulated time.
- Pseudo-latitude.
- Longitude or local time.

Suggested defaults:

- Day: 24 simulated hours.
- Year: 365 simulated days.
- Axial tilt: 23.44°.

The solar declination varies smoothly over the year.

For solar zenith angle, use the conventional relationship:

`cosZenith = sin(latitude)*sin(declination) + cos(latitude)*cos(declination)*cos(hourAngle)`

Incoming sunlight is proportional to:

`max(0, cosZenith)`

### Emergent behavior

This should naturally create:

- Warm tropical regions.
- Cold polar regions.
- Day/night differences.
- Seasonal north/south asymmetry.
- Polar summer daylight and winter darkness.
- Changing land/ocean thermal contrasts.
- Seasonal shifts in evaporation and precipitation.

There is no need to prescribe seasonal precipitation directly.

### Energy-accounting rule

The current model already has shortwave heating, longwave cooling, sensible exchange, and latent heating.

**Do not simply add a new temperature-relaxation force on top of everything without accounting for it.**

Two reasonable approaches exist:

1. Prefer the existing physical heat-budget model, replacing its simplified sunlight calculation with the latitude-aware formula.
2. If the existing radiation model cannot maintain plausible thermal gradients, introduce carefully documented idealized thermal relaxation or a limited compensating forcing.

Choose the simplest approach that produces stable, believable temperature fields.

Any external heating or cooling must be represented consistently in the energy ledger.

Do not double-count a radiation process under two different names.

### Seasonal model scope

Use an idealized circular orbit initially.

No orbital eccentricity, precession, evolving axial tilt, or advanced astronomical mechanics are necessary.

These additions would produce little benefit relative to current priorities.

---

## 9. Sustaining atmospheric circulation

A critical question is whether the new atmospheric model can maintain interesting weather indefinitely.

The existing prototype demonstrates evolving vortices from prepared initial conditions.

It does not yet prove that natural forcing maintains a statistically steady population of storms.

### Goal

After sufficiently long simulated time, Terra should continue exhibiting:

- Evolving pressure-like disturbances.
- Moving and interacting vortices.
- Broad regions of organized flow.
- Periodically strengthening and weakening weather systems.
- Differences between tropical, temperate, and polar circulation.
- Persistent but not permanently identical climatic patterns.

### Preferred mechanisms

Use existing physically meaningful processes:

- Differential solar heating.
- Thermal pressure feedback.
- Momentum advection.
- Coriolis forces.
- Radiative cooling.
- Land/ocean heat capacity differences.
- Surface friction.
- Latent heating.
- Small unresolved convection effects.

Small deterministic perturbations are acceptable for initializing or maintaining unresolved variability if physically defensible.

**Do not spawn new cyclones simply because there are too few storms.**

### Important limitation

A single-layer atmosphere may not generate realistic Earth-like midlatitude storm growth even with improved forcing.

Do not spend unlimited effort trying to make one layer reproduce mechanisms it cannot represent.

A visibly compelling but simplified storm climatology is a valid outcome.

---

## 10. Surface friction

Introduce a simple momentum-damping term tied to surface properties.

Possible relative roughness classes:

- Ocean: low drag.
- Open plains and steppe: modest drag.
- Woods: stronger drag.
- Mountains: potentially strong drag and unresolved form resistance.

Initially, average surface roughness over each atmospheric cell using the existing terrain mapping.

Avoid introducing a separate complex boundary-layer simulation.

### Model

A simple Rayleigh-like damping law is sufficient as a starting point:

`du/dt = -drag * u`

`dv/dt = -drag * v`

An exponential update can preserve numerical stability:

`velocity *= exp(-drag * dt)`

Apply damping to momentum consistently with atmospheric mass.

The parameter should be tied to a plausible response timescale, not chosen solely to create dramatic-looking coastlines.

### Energy treatment

Friction dissipates kinetic energy.

For a more physically consistent closed energy model, the dissipated energy should enter an appropriate heat reservoir.

A simpler game-scale approximation may record it as an explicit dissipative sink.

Do not claim total energy conservation if it is omitted.

### Desired behavior

- Storms can weaken or reorganize over rough land.
- Oceanic winds generally experience less friction.
- Terrain changes influence local airflow.
- Circulation remains active under sustained forcing rather than decaying everywhere.

---

## 11. Mountain uplift and rain shadows

**High priority, high expected visual payoff.**

This feature directly connects atmosphere and geography.

### Fundamental idea

When moist air encounters ascending terrain, it tends to rise and cool.

That promotes cloud formation and precipitation.

Descending air often becomes relatively warmer and drier.

At Terra's coarse atmospheric resolution, this should be parameterized rather than explicitly resolved in a vertical atmospheric grid.

### Suggested approach

Precompute a coarse terrain elevation field from the existing detailed world.

Precompute or cheaply derive local elevation gradients.

Estimate positive windward ascent using:

`uplift = max(0, windX * slopeX + windY * slopeY)`

Use uplift to calculate an approximate cooling or condensation tendency.

### Critical water rule

Additional orographic condensation must convert existing vapor into cloud liquid.

It must not create cloud water or rain from an independent precipitation source.

Latent heating must remain consistent with the existing moist model.

### Critical temperature rule

Avoid repeating the older weather model's problem of condensing vapor at an artificially cooler temperature and immediately evaporating the new cloud at the original warmer temperature.

Choose one consistent thermodynamic state for each phase adjustment.

### Terrain resolution

The atmosphere is much coarser than the world map.

Fine mountain ranges should contribute to aggregate slope and unresolved terrain ruggedness, rather than disappearing simply because their altitude varies within one coarse atmospheric cell.

Do not attempt to represent every individual mountain tile as its own atmospheric obstacle.

### Desired behavior

- Wetter windward mountain slopes.
- Drier leeward terrain.
- Rainfall concentrated over appropriate mountain catchments.
- Clouds and rain patterns changing as wind direction changes.
- No permanently scripted wet or dry side of a mountain.

---

## 12. Convection and precipitation

The atmosphere currently represents a single effective vertical column.

It cannot explicitly simulate rising thermals or vertical cloud structures.

However, much of their visible and hydrological effect can be approximated with a small local model.

### Suggested convective proxy

Convection should become more favorable when:

- The surface is warm relative to the atmospheric column.
- The air contains substantial moisture.
- Atmospheric conditions are sufficiently unstable.
- Surface heating or moisture flux promotes upward transport.

Introduce a small diagnostic uplift or convective-cooling tendency.

Use the existing vapor/cloud/precipitation machinery to process the resulting condensation.

### Avoid overengineering

Do not immediately add:

- Multiple vertical humidity layers.
- Explicit thunderstorm cells.
- Cloud droplet size distributions.
- Detailed ice microphysics.
- Explicit cloud vertical velocities.
- Separate precipitation-particle simulations.

The first version should need very little additional state.

### Rain regimes

The simulation should eventually distinguish, at least qualitatively:

**Stratiform precipitation:** broad, organized, usually associated with larger cloud systems.

**Convective precipitation:** more localized, often stronger, associated with unstable warm/moist regions.

It is acceptable to derive these regimes diagnostically rather than maintaining separate full cloud-water inventories.

### Storm emergence constraint

Parameterized convection is allowed.

Scripted storm placement is not.

---

# Part III — The Surface and Living World

## 13. East–west world continuity

The atmosphere already wraps east–west.

The terrain and hydrology should eventually do the same.

### Terrain generation

The existing `World.kt` elevation generator samples procedural noise using raw rectangular coordinates.

Consequently, terrain is not guaranteed to match across the map seam.

Introduce periodic longitude-aware noise or a simple seamless blending technique.

Prioritize:

- Seamless coastline continuity.
- Seamless mountain ranges.
- Stable deterministic world seeds.
- Minimal changes to existing visual terrain character.

No complex spherical noise implementation is required.

### Hydrology

Update neighbor enumeration in the drainage topology to allow X wrapping.

A river near the right map edge can continue at the corresponding left edge.

The drainage graph must remain acyclic.

The world has no left/right hydrological outlets once longitude becomes periodic.

### Polar land boundaries

The north and south edges are not valid arbitrary water sinks merely because they are map boundaries.

When polar land receives runoff, that water must reach a legitimate ocean outlet or be stored in a closed basin.

The simplest valid solution may involve using existing ocean-connected outlets and explicitly represented endorheic basins.

Do not silently discard water at polar boundaries.

### Implementation order

Perform the terrain-seam and hydrology-topology changes together in a coherent geographical migration.

Atmospheric boundary changes can occur earlier because they are independent of terrain generation.

---

## 14. Preserve Terra's hydrology

The hydrology implementation is already substantial.

It includes:

- Drainage topology.
- Upstream contributing areas.
- Soil runoff and overflow.
- River discharge.
- Persistent lake storage.
- Lake evaporation.
- Lake precipitation.
- Spillover.
- River and lake visualization.

Keep it.

The objective is not to rebuild hydrology every time the atmosphere changes.

### Conservation contract

All water transfers must be counted exactly once:

`atmosphere → rain → soil/lake/ocean → runoff → rivers/lakes → ocean`

Evaporation reverses part of the path.

Where the existing model uses mm·tile quantities, preserve those equal-area units initially.

Do not introduce spherical physical-area corrections.

### Closing the ocean cycle

The dynamical model currently contains finite ocean water stores, while hydrological runoff exported to terminal outlets is tracked separately.

For simulations lasting many years, this requires a coherent long-term interpretation.

Preferred simple option:

**Treat the global ocean as one effectively well-mixed water reservoir for mass accounting, while retaining spatially varying ocean temperatures.**

Rainfall and river inflow replenish this reservoir; evaporation withdraws from it.

The detailed distribution of freshwater within the ocean need not be modeled.

If maintaining explicit per-tile ocean stores proves simpler, that is also acceptable, provided river returns, totals, and long-term behavior remain consistent.

Avoid implementing ocean currents or salinity merely to close the water budget.

---

## 15. Climate and biome evolution

Currently, dynamical weather classifies biomes after approximately 30 simulated days of observed conditions.

This is insufficient as an estimate of stable annual climate.

### Desired approach

Climate should be derived from long-term weather statistics:

- Mean temperature.
- Temperature extremes or seasonal ranges.
- Cumulative precipitation.
- Rainfall seasonality.
- Aridity relative to potential evaporation.
- Possibly soil-water persistence.

### Separate three concepts

**Weather:** Current instantaneous atmospheric conditions.

**Climate statistics:** Running observations of past weather.

**Ecological state:** Slowly changing vegetation and biome characteristics.

Do not treat these as the same mutable field.

### Initial world generation

A full multi-year warm-up may be expensive.

Possible approaches:

- Start with provisional climate-based biomes and gradually refine them.
- Use approximate equilibrium climate estimates for initial terrain appearance.
- Use a bounded initialization period with explicit confidence limits.

The implementation model should choose the simplest approach that avoids severe early misclassification.

### Dynamic vegetation

Long-term terrain appearance should eventually react gradually to climate changes.

For example:

- Plains may become steppe under prolonged drying.
- Steppe may become desert after persistent aridity.
- Woods may gradually expand into newly suitable regions.

This should not occur instantly at a climate-threshold crossing.

Use ecological inertia and hysteresis.

Do not implement complex plant population ecology at this stage.

---

# Part IV — Visual Quality

## 16. Clouds are a first-class feature

**Cloud rendering deserves unusually high priority because Terra is fundamentally a visual world simulator.**

The current atmospheric cloud field is low resolution and its game rendering largely expresses cloud water through opacity.

The underlying dynamical system is already far more interesting than its visual presentation.

### Design direction

Separate:

1. **Physical cloud water:** Produced by the atmosphere; determines precipitation and budgets.
2. **Visual cloud representation:** Higher-detail procedural appearance derived from physical fields.

The renderer may add texture.

It may not create physical cloud mass or rainfall.

### Desired cloud regimes

**Large organized cloud systems**

- Broad continuous structures.
- Soft layered edges.
- Wind-aligned bands.
- Comma-like or spiral structures when supported by the flow.

**Local convective clouds**

- Clustered smaller formations.
- More irregular boundaries.
- Higher visual contrast.
- Stronger association with local rain.

**Thin cloud cover**

- Partially transparent.
- Terrain remains visible.
- Not visually identical to dense storm clouds.

### Simple rendering approach

Use:

- Physical cloud amount or coverage as large-scale density.
- Deterministic procedural texture for subgrid variation.
- Advection tied to simulated winds.
- Smooth temporal evolution.
- Possibly one diagnostic cloud-regime parameter.

Start with a small number of texture frequencies.

Avoid particle clouds, volumetric rendering, 3D lighting, and expensive per-frame procedural generation across the entire map.

### Resolution

Keep the atmospheric physics coarse.

Render cloud detail at a higher resolution where useful.

A good-looking cloud system does not require the physical atmosphere to resolve every visible cloud fragment.

### Important limitation

Higher-detail visualization cannot recover physical structures that the coarse atmosphere does not generate.

Do not use procedural texture to fabricate vortices or moving fronts that are inconsistent with the underlying winds and moisture.

The physical state remains authoritative.

---

## 17. Visual debugging and presentation

Preserve useful atmospheric overlays:

- Wind.
- Cloud water.
- Rainfall.
- Temperature.
- Humidity.
- Pressure-like height anomaly.
- Vorticity.

Pressure-like layer thickness should not be labeled directly as calibrated surface pressure in hPa.

Prefer clear, interpretable legends over visually dramatic but misleading colors.

The standard world view should remain beautiful without requiring any diagnostic overlays.

Clouds, terrain, rivers, lakes, and vegetation should have a visually coherent color palette.

---

# Part V — Simulation Speeds and Long-Term Evolution

## 18. Two distinct time regimes

Terra needs two different ways to advance the world.

Trying to make one numerical atmospheric solver perform both jobs is likely to produce a poor complexity/performance trade-off.

### Detailed weather mode

Purpose: Watching actual weather systems evolve.

Includes:

- Dynamical winds.
- Pressure-like disturbances.
- Moisture transport.
- Radiation.
- Cloud formation.
- Rainfall.
- Surface feedback.
- Hydrology.

Preserves the numerical timestep requirements of the fluid solver.

Target: Several simulated hours per real second, subject to measured performance.

This is a target, not a claim about current achieved speed.

### Climate evolution mode

Purpose: Advancing months, years, and eventually decades while preserving meaningful long-term environmental effects.

It should not run every 20-second atmospheric timestep.

Instead, represent:

- Seasonal temperature and precipitation.
- Spatially correlated wet/dry variability.
- Rainfall intermittency.
- Occasional extreme rainfall and drought.
- Evaporation and soil-water balance.
- Lake and river responses.
- Long-term biome and vegetation change.

The climate model is an approximation of aggregate atmospheric behavior, not a faster exact replay of individual storms.

---

## 19. Long-term climate must preserve extremes

Simply distributing monthly rainfall uniformly across all days would be a serious mistake.

Hydrology responds nonlinearly to rainfall intensity.

For example, 100 mm falling over two days can cause runoff and flooding that would not occur if the same 100 mm arrived uniformly over a month.

Therefore, climate fast-forward should represent at least a small amount of event variability.

### Preferred simple approach

At a monthly or seasonal climate step:

1. Determine expected precipitation and temperature for the region.
2. Determine a small set of representative wet/dry intervals or rainfall events.
3. Assign rainfall totals and intensities consistent with the climate statistics.
4. Transfer the resulting water through soil and hydrology without double-counting it.
5. Advance slow reservoirs and ecological state over the interval.

Use deterministic, seed-based variability for reproducibility.

Spatially related regions should experience related weather regimes instead of independently sampled random rain.

### Storm-spawning distinction

The prohibition on scripted storm spawning applies to **detailed weather mode**.

The coarse climate model may statistically represent storms and extremes, because it is explicitly not simulating their detailed trajectories.

It must not pretend those synthetic events are actual resolved storms.

---

## 20. Avoid excessive fast-forward complexity

Do not implement a separate detailed global circulation model merely for climate acceleration.

Candidate climate-mode representations include:

- Coarse seasonal climatology fields.
- Monthly precipitation and temperature anomalies.
- Simple wet/dry event distributions.
- Slow soil and ocean temperature response.
- Aggregate water-budget updates.

The smallest useful approach should be tried first.

It is possible that a handful of persistent seasonal climate fields will provide sufficient long-term behavior.

Only add more detailed weather statistics if the terrain and hydrology require them.

### Performance reality

The current 20-second timestep would require approximately 1.58 million physical steps per simulated year.

At 10–100 years/s, exact detailed advancement is therefore impractical.

Even reducing atmospheric resolution dramatically does not eliminate this temporal cost.

A genuinely separate coarse climate advancement mechanism is necessary.

### Transition between modes

When entering fast-forward:

- Preserve all slow physical reservoirs.
- Preserve current climate statistics.
- Stop resolving individual storm trajectories.
- Advance water and energy using the coarse model.

When returning to detailed weather:

- Preserve the updated surface state.
- Initialize a plausible detailed atmospheric state consistent with the current season and climate.
- Preserve important atmospheric water totals where applicable.
- Allow a bounded re-equilibration period.

Exact continuity of every previously simulated cloud or storm is not required.

Avoid resetting soil, lakes, ocean water, or biomes.

---

## 21. Scheduling and responsiveness

The current `DynamicalWeather.advanceBudgeted()` performs at most eight fixed physical steps per rendered frame.

At 20 seconds per step and 60 FPS, this places a theoretical scheduling ceiling around 2.67 simulated hours per real second, before accounting for compute limits.

Several improvements are possible:

- Decouple simulation progress from rendering frequency.
- Increase the permitted step budget where measured performance allows.
- Investigate a larger stable physical timestep.
- Reduce snapshot and overlay refresh frequency.
- Use a simulation worker only if the current update loop genuinely causes unacceptable frame stalls.

Do not automatically introduce multithreading.

The solver currently uses reusable arrays and a modest grid. A simple single-threaded architecture may remain adequate for detailed weather.

### Timestep changes

The timestep is constrained by the numerical wave speeds and grid spacing, not merely the simulated wind speed.

Do not increase `stepSeconds` arbitrarily to achieve higher speed labels.

If a larger timestep is beneficial, justify it through the existing Courant checks and acceptable numerical behavior.

### UI contract

Display requested speed and actual achieved simulated speed separately when useful.

If the simulation cannot keep up, retain unprocessed simulated time rather than silently skipping physical steps in detailed mode.

Climate mode is explicitly different: it advances approximate aggregate physics.

---

# Part VI — Implementation Roadmap

## 22. General implementation policy

This is a strategic roadmap, not an inflexible recipe.

The implementation agent is **authorized to challenge, reorder, merge, simplify, replace, or discard proposed techniques** when doing so better serves the project's goals.

Such deviations should be explained concisely.

A deviation is particularly welcome when:

- An existing implementation already solves the problem.
- A simpler algorithm achieves comparable visible results.
- A proposed feature would significantly harm performance.
- A mathematical assumption is inconsistent with the actual solver.
- A suggested approximation produces unphysical numerical behavior.
- A different implementation order reduces rework.
- The expected improvement is too small to justify its complexity.

Do not preserve this plan at the expense of the project.

### Suggested working method

Prefer coherent, reviewable implementation increments.

After each meaningful increment:

1. Keep the source buildable.
2. Reuse existing diagnostics where helpful.
3. Inspect conservation and stability implications.
4. Summarize observable improvements and remaining limitations.
5. Update the implementation checklist.
6. Stop at a coherent boundary rather than beginning unrelated features.

Do not create extensive planning documents, benchmark suites, new test infrastructure, or debug scaffolding merely because the project is technically ambitious.

Short, focused checks are more valuable than elaborate machinery.

---

## Phase 0 — Reassess the existing implementation

**Goal:** Establish a reliable starting point.

Tasks:

- [x] Inspect current `main` and any newer changes.
- [x] Review existing atmospheric state and numerical update logic.
- [x] Inspect integration with hydrology and the game.
- [x] Identify which proposed features are already implemented.
- [x] Inspect known conservation and performance results.
- [x] Confirm current numerical limitations and existing diagnostics.
- [x] Decide whether the current solver can be extended without major structural changes.

Do not perform a speculative rewrite.

**Deliverable:** A concise architectural assessment and any necessary adjustments to the roadmap.

---

### Phase 0 assessment — 10 November 2026

**Decision:** Extend the current solver. No major structural changes, new weather
engine, spherical geometry, or additional atmospheric layer are needed for the
next increment. Phase 0 changed documentation only; Phase 1 was unimplemented at this assessment.

**Repository inspected:** Local `main` and local `origin/main` both point to
`2d4028b`, the reviewed baseline; remote freshness was not checked. The working
tree already removes older planning documents, baseline runners, measurements,
and their game hooks. The remaining build/UI changes remove that instrumentation,
not atmospheric equations. Preserve those edits. Historical results below were
read from Git without restoring deleted files.

**Architecture and existing features:**

- `DryAtmosphere` owns `h`, `hu`, and `hv`; reusable MUSCL reconstruction,
  shared HLL-type face fluxes, SSPRK2, and exact split Coriolis rotation are
  already implemented. X and Y neighbors wrap in fluxes, reconstruction, and
  vorticity diagnostics. Coriolis is one scalar, including in potential enstrophy.
- `MoistColumnTransport` shares the actual carrier flux and RK stages for
  `h*T`, vapor, and cloud. `MoistAtmosphere` couples temperature to pressure,
  pays for latent/sensible exchanges, and records net radiation and an energy
  residual. Condensation and cloud evaporation already use one consistent local
  thermal state; the older prescribed model's issue is not present here.
- `AtmosphereTileMapping` already conserves equal-area transfers on non-divisible
  grids and reverses north-up atmosphere Y into south-down terrain Y.
  `TerrainMoistSurface` owns finite soil/ocean stores; hydrology owns lakes and
  pending routing water. Rain, evaporation, drainage, and overflow already cross
  these ownership boundaries through explicit transfers.
- `AtlasScene` hands each completed weather batch to daily-cadence hydrology.
  Dynamical weather is opt-in (`-PweatherModel=dynamical` or M); the default is
  prescribed weather. Switching modes regenerates the world and water stores.
  Dynamical mode skips the prescribed model's 210-day bootstrap and applies
  biomes once after 30 days. Climate statistics exist, but seasonal climate,
  ecological inertia, and a coarse fast-forward contract do not.
- Generated terrain already has a latitude-based initial temperature estimate,
  and prescribed weather has latitude-dependent sunlight. Neither supplies
  latitude or seasons to the dynamical atmosphere. Land/ocean albedo and thermal
  capacity differences are implemented; roughness drag, orographic lifting,
  convective uplift, and longitude-seam terrain/hydrology are absent. Game clouds
  are tile opacity from physical cloud water; there is no advected detail texture.

**Verification and limits:**

- `gradle compileKotlinJvm jvmTest` passed: 5 dry, 4 moist, and 2 integration
  tests, with no failures. This includes analytic constant-f rotation with both
  signs, periodic volume conservation, repeatability, latent-energy transfers,
  non-divisible overlap mapping, and a three-day coupled water ledger. The initial
  sandbox native-library failure was resolved by the elevated rerun per AGENTS.md.
- Fresh default 96×72 dry and moist JET runs each completed 4,320 fixed 20-second
  steps (one day), using the existing experiment runners with `--days=1 --frames=2`.
  Outputs are under `build/experiments/phase-0/{dry,moist}/`. Dry relative volume
  change was −1.19e−16; moist volume change was zero and relative closed-water
  residual −1.18e−16. Final CFL was approximately 0.107 in both runs, below 0.4.
  The moist energy residual was −3.98e17 J after accounting for radiation;
  numerical energy conservation is not guaranteed.
- Existing local outputs report a 24-day dry volume change of −4.74e−16 and an
  eight-day moist water residual of −8.28e−16. Their source provenance was not
  independently verified; the fresh runs above are the current smoke evidence.
- Diagnostics already expose volume, energy, potential enstrophy, minimum depth,
  speed, CFL, vapor/cloud/rain, water/export totals, and radiation/energy residuals.
  Nonfinite/nonpositive dynamics and tracers, excessive CFL, and carrier-outflow
  violations fail explicitly. Dry dynamics validate before committing; the whole
  moist step is not transactional because heat/water exchange follows the flow
  commit. Treat such failures as a stopped run, not a recoverable skipped step.
- Outlet export is included in the water residual but is not returned to ocean
  stores. A closed accounting ledger therefore does not establish a sustainable
  ocean cycle. Existing coupled tests use a smaller grid and cover only three days.
- The historical `2d4028b:docs/experiments/BASELINE_RESULTS.md` measures the older
  prescribed model, not this dynamical solver. Its approximately 9.4 days/s
  headless throughput and rendering allocation findings cannot establish current
  detailed-weather capacity. The surviving exporters include image/I/O work and
  are not throughput benchmarks. No fresh desktop timing or long-term circulation
  validation was performed. The eight-step/frame cap implies at most 2.67 hours/s
  at 60 FPS before compute costs; the game reports elapsed/pending time but no
  achieved-speed metric and accumulates KorGE-provided frame deltas.

**Roadmap adjustments before implementation:**

1. Keep the phase order. Make Phase 1 cover all Y-boundary consumers together:
   face fluxes, reflected reconstruction, thermal wall pressure, CFL, diagnostic
   derivatives, and `DynamicalWeather.sample` (currently wraps Y even for display).
   Wall mass/tracer flux must be zero while normal momentum still feels pressure.
2. Retain an explicit constant-f periodic configuration for the existing analytic
   checks and idealized scenarios. Adapt channel initializers deliberately: the
   jet's current balance assumes constant f, and the vortex divides by f at a
   domain-center location that becomes equatorial. Do not substitute local f into
   that division at the equator. Use off-equatorial or explicitly unbalanced
   channel disturbances while preserving the original experimental cases.
3. Extend focused checks for resting walls, reflection/no polar teleportation,
   closed mass and tracer transport, signed row rotation, and equatorial behavior.
   Exact rotation preserves kinetic energy locally; the full transport scheme is
   dissipative, so do not require exact full-step energy conservation.
4. In Phase 2, share solar geometry between physics and presentation. The current
   `nightOpacity(x)` interface and column-only renderer cannot show seasonal polar
   night; adapt them to latitude as part of that phase. Replace existing sunlight
   in the radiation budget, retaining implemented land/ocean thermal properties.
5. Keep ocean return and hydrology topology in Phase 5, but close that cycle before
   claiming validated multi-year climate in Phase 6. Measure detailed throughput
   with the real coupling/render cadence before changing timesteps or adding a
   worker; no performance rewrite or replacement benchmark framework is needed now.

---

## Phase 1 — Latitude-aware atmospheric channel

**Goal:** Give the existing atmosphere a coherent planet-like topology.

Likely files:

- `DryAtmosphere.kt`
- `DryAtmosphereConfig`
- `DryScenarios.kt`
- Minimal shared latitude/geometry helper

Tasks:

- [x] Preserve periodic X boundaries.
- [x] Implement reflective, impermeable Y boundaries.
- [x] Add signed pseudo-latitude per row.
- [x] Add per-row Coriolis values.
- [x] Adapt split rotation to varying Coriolis.
- [x] Ensure tracer fluxes respect polar walls.
- [x] Adapt vorticity/enstrophy diagnostics and tile display interpolation to bounded Y.
- [x] Retain existing numerical stability checks.
- [x] Keep original constant-f periodic experiments usable; adapt channel initializers without equatorial division by f.
- [x] Evaluate whether wall damping is actually needed.

**Success:** Opposite-hemisphere rotation, no polar mass leakage, no north-to-south teleportation, and stable evolving atmospheric disturbances.

**Do not:** Introduce spherical grids, variable physical cell areas, or a second atmospheric layer.

---

### Phase 1 result — 10 November 2026

The default atmosphere is now a periodic-X, reflective-Y latitude channel.
`PlanetLatitude` supplies consistent north-up and terrain-down row mappings.
Exact split rotations use precomputed row factors for `f = 2Ω sin(latitude)`.
Reflected HLL wall fluxes retain normal pressure and CFL wave speeds while
transferring identically zero mass, tracer, or tangential momentum. Reconstruction,
vorticity/enstrophy, and tile display interpolation respect the bounded Y domain.
The existing constant-f periodic configuration remains selectable for experiments;
channel jets use integrated latitude-dependent balance and vortices form an
opposite-hemisphere pair without equatorial division by f.

Validation was deliberately bounded: compilation and all 16 JVM tests passed
(the existing 11 plus four channel checks and one polar display check). The added
checks cover exact row rotation/kinetic energy, equatorial behavior, resting and
free-slip walls, CFL rejection, wave reflection, periodic X, no polar teleportation,
and closed volume/moisture transport. The existing three-day reduced-grid coupled
water-ledger check now exercises the default channel too.

One full-grid moist JET experiment ran for one simulated day (4,320 steps, two
exported frames), using `gradle moistAtmosphereExperiment
-PmoistAtmosphereArgs='--days=1 --frames=2 --output=build/experiments/phase-1/moist-channel'`.
Volume change was zero, relative water residual −1.18e−16, final CFL 0.1069,
minimum depth 9,749.76 m, and maximum wind 39.47 m/s. The final image showed no
obvious boundary instability; reflected waves are intentional. No sponge damping
was justified by this limited evidence, so none was added. Long-running polar
artifacts and sustained storm activity remain unverified. No exhaustive scenario
sweep, throughput benchmark, or additional atmospheric layer was introduced.

---

## Phase 2 — Planetary heating and seasons

**Goal:** Create a meaningful, persistent thermal geography.

Likely files:

- `MoistAtmosphere.kt`
- Atmospheric configuration
- `DynamicalWeather.kt`
- Existing surface-exchange implementation

Tasks:

- [x] Add latitude-aware diurnal sunlight.
- [x] Add annual solar declination and axial tilt.
- [x] Share solar geometry with the game view and make day/night rendering latitude-aware.
- [x] Preserve land/ocean heat-capacity differences.
- [x] Confirm energy accounting for external heating/cooling.
- [x] Add minimal momentum damping where appropriate.
- [x] Assess sustained atmospheric circulation.
- [x] Avoid duplicate radiation or thermal-relaxation processes.

**Success:** Warm tropics, cold polar regions, seasons, changing thermal contrasts, and persistent atmospheric activity.

**Critical question:** Do storms continue developing after the initially perturbed flow has evolved for many simulated months?

If not, identify whether the issue is insufficient forcing, excessive damping, numerical diffusion, or the limits of single-layer dynamics.

Do not introduce random storms as a shortcut.

---

### Phase 2 result — 10 November 2026

Implemented `SolarConfig` and shared, cached `PlanetarySunlight` geometry with a
24-hour day, 365-day circular orbit, 23.44° tilt, signed pseudo-latitude, and an
explicit initial seasonal phase. Replaced the existing shortwave factor in the
radiation budget; no extra relaxation or independent heat source was added.
The game uses the same geometry, and `nightOpacity(x, y)` now supports polar
summer/winter. Existing land/ocean albedos and heat capacities are unchanged.
Weak exponential bulk drag (30-day e-folding) returns dissipated kinetic energy
to column heat, including the thermal-pressure contribution. Its cumulative
heating is an internal-transfer diagnostic, not an external energy term.

**Bounded validation:** `gradle compileKotlinJvm jvmTest` passes all 19 tests.
Three added tests cover seasonal/equinoctial geometry, polar day/night and map
orientation, radiation/surface inertia, and conservative friction heating; the
existing game adapter test also checks solstice lighting. Existing coupled water
checks pass. One 16×12, 180-day JET run at a CFL-checked 180-second timestep was
repeated once to correct overly cold tropics: the normal-beam shortwave peak is
now 1,360 W/m² before albedo/cloud reflection, replacing the old surface-like
1,000 W/m² normalization. Production resolution and timestep are unchanged.
No exhaustive or expensive production-grid experiment was run.

The final reduced-grid run completed 86,400 steps with relative volume change
8.30e−16, water residual 1.06e−15, and final CFL 0.144. Tropical surface rows averaged
277.95 K versus 251.99 K at |latitude| > 60°. Effective-column air averaged 247.23 K;
absolute temperatures and the use of column temperature for biomes need later
climate assessment. Net radiation was −1.39e23 J and friction heating 1.15e20 J;
remaining numerical energy residual was 3.16e18 J. Results and images are under
`build/experiments/phase-2/seasonal-channel/`; the reproducible command is in
`docs/MOIST_ATMOSPHERE.md`.

**Open acceptance criterion:** Circulation persists (maximum final wind 13.44 m/s),
but non-zonal RMS wind decreases from 0.244 m/s initially to 0.048 at day 90 and
0.056 at day 180. The final flow is predominantly zonal, so persistent storm
regeneration is not established. The cold bias at the old solar normalization was
observed and improved; numerical diffusion on this coarse grid, uniform-ocean
forcing, weak bulk damping, and single-layer limits remain possible contributors
to low variability. This bounded run cannot isolate their relative importance.
Keep the one-layer model, proceed with planned terrain feedback, and retain storm
persistence as an unresolved criterion. Do not infer that compilation or surviving
jets validate a storm climatology. No scripted storms or additional layer was added.

---

## Phase 3 — Terrain feedback and realistic precipitation

**Goal:** Make clouds and rainfall respond to geography.

Likely files:

- `MoistAtmosphere.kt`
- `TerrainMoistSurface.kt`
- `AtmosphereTileMapping.kt`
- Minimal topographic preprocessing

Tasks:

- [x] Compute coarse terrain elevation and slopes.
- [x] Introduce physically motivated orographic lifting.
- [x] Preserve consistent vapor/cloud phase transfer.
- [x] Introduce basic unresolved convective uplift.
- [x] Differentiate surface roughness and drag.
- [x] Maintain water and latent-energy accounting.
- [x] Avoid creating rainfall from an arbitrary precipitation source.

**Success:** Windward rainfall, rain shadows, warm-region convection, and weather systems interacting visibly with coastlines and mountains.

**Complexity gate:** If a proposed convection or orography mechanism requires numerous new prognostic variables, first consider a simpler diagnostic parameterization.

---

### Phase 3 result — 10 November 2026

Added static `AtmosphereTerrain` preprocessing: overlap-weighted coarse elevation
and slopes, plus fine north-up slopes whose positive wind-driven ascent is averaged
before coarsening. This preserves unresolved ridges. Atmospheric relief uses
10,000 m per normalized elevation unit above the world's configured sea level;
hydrological storage scales are unchanged. Derivatives stop at the current terrain
edges; seamless X derivatives remain part of Phase 5.

A small buoyancy proxy uses surface warmth relative to a dry-adiabatic mixed-column
reference, not surface warmth alone. Terrain and convective velocities determine a
bounded diagnostic parcel ascent. One latent-enthalpy solve uses the same lifted
parcel temperature/pressure for both condensation and evaporation; only actual
phase transfer changes bulk heat. No independent rainfall, second cloud inventory,
external cooling source, or new prognostic atmospheric variable was introduced.
Reversible vertical work is not resolved; this is a diagnostic parcel approximation.

Surface-dependent drag now averages ocean/open-land/woods/mountain roughness over
the existing overlaps and refreshes after biome changes. Exponential damping still
returns kinetic energy to atmospheric heat, including thermal pressure feedback.
Existing soil, ocean, lake, runoff, and precipitation handoffs are retained.

**Focused verification:** `gradle compileKotlinJvm jvmTest` passed all 23 tests.
Four added checks exercise unresolved ridges and wind reversal, windward cloud/rain
and vapor loss with closed water/latent-energy accounting, convection requiring
both instability and moisture, and stronger roughness drag with energy returned as
heat. The existing three-day reduced-grid coupled water-budget test also passes.
The test suite completed in under a second; compilation/build took about two
seconds. No separate experiment, long run, or exhaustive testing was performed.

**Limits:** The checks demonstrate local windward rain and moisture removal, not
map-scale rain-shadow climate or a sustained convective storm population. The
Phase 2 storm-persistence criterion remains unresolved. Bulk relative humidity is
not the lifted parcel's humidity, so lifted clouds may occur in bulk-subsaturated
air. Lake roughness currently follows underlying land, like its thermal properties.
Detailed equations, parameters, and disable switches are in `docs/MOIST_ATMOSPHERE.md`.

---

## Phase 4 — Improved cloud visualization

**Goal:** Make the atmosphere significantly more beautiful without adding substantial physical computation.

Likely files:

- `TerrainMapView.kt`
- Existing weather overlay code
- Optional small cloud-rendering helper

Tasks:

- [x] Render broad, coherent large-scale cloud systems.
- [x] Add lightweight wind-advected subgrid texture.
- [x] Distinguish thin clouds from dense storm clouds.
- [x] Add finer structure around convective regions.
- [x] Preserve temporal coherence.
- [x] Keep the physical moisture/rain fields authoritative.
- [x] Avoid expensive full-resolution updates every frame.

**Success:** Clouds look richly structured, dynamic, and visually consistent with the underlying weather.

This phase may proceed in parallel with Phases 2–3.

---

### Phase 4 result — 10 November 2026

Added a reusable `CloudRenderer` for the game at two pixels per terrain tile,
with smooth physical-cloud interpolation, broad and fine seeded texture,
translucent thin clouds, and shaded dense rain-cloud cores. Local rainfall and
surface/air thermal contrast guide finer billows as a presentation proxy.
Large-scale cloud placement and opacity remain gated by physical cloud water;
no independent cloud mass, precipitation, or synthetic storm system is introduced.

Both weather implementations expose completed simulation time for presentation.
Texture follows the local wind with periodic X and bounded Y, pauses with the
model, and resets deterministically on world/model replacement or clock rewind.
Weak noise renewal offsets interpolation diffusion. One bounded pass handles
large hidden-overlay/fast-forward gaps; unseen detailed trajectories are not replayed.
The game caps scheduled cloud redraws at 10 Hz, reuses primitive buffers and the
bitmap, and leaves physics and other overlay cadences unchanged.

**Focused verification:** Compilation passed, as did the existing 23 checks and
two new renderer checks (physical support/read-only clock, wind/seam advection,
pause, and deterministic rewind). The clear-air assertion was adjusted to respect
valid cloud interpolation across the X seam; only the two cloud checks were rerun.
A small controlled before/after image was inspected at
`build/reports/clouds/review.png`. No atmospheric experiment, exhaustive visual
suite, production-frame benchmark, or new dependency was added.

**Limits:** Fine texture is decorative, not recovered physical resolution or a
validated convection classification. It cannot supply the sustained storms still
unproven in Phase 2. The small visual fixture establishes appearance and timing
behavior; desktop responsiveness under all speed presets remains unmeasured.
The standalone atmosphere exports remain scientific field previews.

---

## Phase 5 — Seamless global terrain and hydrology

**Goal:** Make east–west wrap consistent throughout the world.

Likely files:

- `World.kt`
- `HydrologyTopology.kt`
- `HydrologySimulation.kt`
- `TerrainMapView.kt`

Tasks:

- [x] Make procedural elevation seamless in longitude.
- [x] Preserve deterministic generation.
- [x] Connect drainage neighbors across the X seam.
- [x] Remove artificial longitude-edge outlets.
- [x] Define correct handling of polar land boundaries.
- [x] Preserve lake basin and outlet acyclicity.
- [x] Reconcile river export with ocean water storage.
- [x] Ensure water rendering remains seamless.

**Success:** A river and coastline can continue naturally across the world seam, without water disappearing or duplicated drainage paths.

**Important:** No need for spherical area weighting.

---

### Phase 5 result — 10 November 2026

Procedural elevation now uses a quintic blend of translated samples of the existing
seeded noise, continuous in value and slope around longitude. Cell-center sampling
avoids a duplicated seam column. Tile access and atmospheric terrain derivatives
wrap X; latitude remains bounded. Generation is repeatable, but geography for an
old seed changes as expected from this migration.

All hydrological neighbor searches now wrap longitude, including Priority-Flood,
depression grouping, saddle connections, basin trees, and entries. Only ocean cells
seed terminal outlets; no longitude or polar land edge exports water. With no ocean,
a deterministic global-minimum root anchors an explicit nonspilling whole-world
basin. Water storage stays finite and its wet footprint grows with level; capacity
is unbounded. This deliberately simple all-land fallback does not resolve separate
endorheic sub-basins. Ordinary ocean-bearing maps keep the existing compound-basin
algorithm and acyclicity checks.

Hydrology now owns one finite global ocean reservoir. Ocean rain and river discharge
replenish it once, and dynamical evaporation withdraws actual available water.
Spatial ocean temperatures and existing heat capacities remain unchanged. Tracked
water includes the ocean once; cumulative outlet throughput is an internal-transfer
diagnostic, and the terrain surface reports zero exported water. The prescribed
fallback's older unbudgeted water physics remains outside this closed-ledger claim.

Lake bank neighbors and river raster brushes wrap X. River segments use the shortest
longitude displacement, so a seam crossing draws on both map edges. Artificial map
edge outlet segments were removed; polar rendering remains bounded.

**Focused verification:** Compilation and all 29 checks passed. Four additions cover
seed repeatability and smooth elevation/terrain slopes, seam drainage and finite
single-counted ocean return, seam-spanning lake fill/spill and graph order, and
all-land runoff storage/evaporation. One default 283×212 topology construction passed
acyclicity and legitimate-terminal checks; only the four hydrology checks were rerun
for that addition. The existing three-day reduced-grid coupled water ledger passes.
No seed sweep, long atmospheric experiment, or exhaustive testing was performed.

**Limits:** Water raster changes were inspected in code, without a new desktop
visual/performance benchmark. These checks establish topology and accounting;
repeated-year climate and ocean/soil/lake behavior remain Phase 6 work.

---

## Phase 6 — Long-running climate and biomes

**Goal:** Make the world evolve meaningfully across seasons and years.

Tasks:

- [ ] Establish reliable seasonal and long-term climate statistics.
- [ ] Replace premature 30-day biome assumptions.
- [ ] Separate temporary weather from ecological state.
- [ ] Add slow vegetation/biome response only after climate is credible.
- [ ] Preserve desert, steppe, plains, woods, tundra, ocean, and mountain distinctions.
- [ ] Evaluate long-term ocean, lake, and soil-water behavior.
- [ ] Ensure repeated years do not produce unexplained water or energy drift.

**Success:** Recognizable annual cycles, different wet/dry climates, and gradual ecological change.

Avoid adding fully simulated ecosystems prematurely. Climate stability comes first.

---

## Phase 7 — Coarse climate fast-forward

**Goal:** Enable all time speeds at 100fps and eventually decades and even centuries per real second.

Tasks:

- [ ] Introduce a mechanism that gradually coarsens the simulation or sacrifices detail and correctness at high simulation speeds. This follows a similar approach as mipmapping conceptually: If we're far away (or if time is supposed to pass really quickly) we don't need to simulate everything close up and in detail. Nevertheless, we will want to be able to follow the world changing, for example tile biomes changing due do weather patterns etc. Feel free to adjust the following checklist accordingly.
- [ ] Establish seasonal climate aggregates from the detailed model or a simpler equilibrium representation.
- [ ] Include meaningful precipitation variability and extremes.
- [ ] Update soil, lakes, and rivers using consistent aggregate fluxes.
- [ ] Preserve slow-world state between simulation modes.
- [ ] Implement deterministic mode transitions.
- [ ] Evaluate monthly versus seasonal advancement.
- [ ] Select the cheapest approach that maintains credible long-term behavior.

**Success:** The planet can evolve across many years without resolving millions of individual atmospheric timesteps.

**Do not:** Claim that climate fast-forward preserves exact detailed weather trajectories.

---

## Phase 8 — Decide whether more atmospheric complexity is justified

**Goal:** Stop when the model is good enough.

After the previous phases, reassess:

- Are storms visually varied?
- Do pressure-like regions develop naturally?
- Do cloud systems organize convincingly?
- Does climate vary with geography and season?
- Do rivers and lakes respond meaningfully?
- Can the user accelerate time adequately?
- Is the implementation understandable and maintainable?

If the answer is broadly yes, **do not introduce additional atmospheric layers**.

If one-layer physics demonstrably prevents the most important visual behaviors, consider a small isolated two-layer experiment on the same flat geometry.

The second layer must provide unmistakable improvements in frontal organization, cyclone evolution, or weather variability to justify its additional cost.

A full spherical solver or primitive-equation atmosphere is not part of the default roadmap.

---

# Part VII — Acceptance Criteria and Trade-offs

## 23. What success actually looks like

The following outcomes matter more than scientific sophistication.

### Atmosphere

- Weather continuously evolves without scripted storms.
- Both hemispheres behave differently where physically expected.
- Storms can organize, intensify, weaken, and interact.
- High-pressure-like regions can remain relatively clear.
- Atmospheric patterns respond to seasonal forcing.

### Clouds and precipitation

- Clouds are visually interesting at multiple scales.
- Tropical regions can produce substantial convection.
- Orographic rainfall and rain shadows are visible.
- Precipitation is geographically uneven but not arbitrarily confined.
- Cloud cover and rainfall remain distinct phenomena.

### Surface

- Rain feeds realistic catchment runoff.
- Rivers and lakes respond to changing conditions.
- Ocean water and land water budgets remain coherent.
- Terrain is geographically continuous across the longitude seam.
- Biomes reflect long-term climate rather than recent weather alone.

### Performance

- Detailed weather remains responsive.
- Several simulated hours per real second is a meaningful target.
- Rendering does not require full atmospheric-grid rebuilds every frame.
- The hydrology system does not become unnecessarily expensive.
- Climate mode makes genuine multi-year advancement possible.

### Maintainability

- One authoritative weather state.
- One authoritative surface-water state.
- No independent hidden rainfall generator in detailed mode.
- No duplicated climate logic.
- No unnecessary scientific infrastructure.
- Clear numerical approximations and units.

---

## 24. Fidelity versus complexity

| Feature | Decision | Reason |
|---|---|---|
| Conservative atmospheric transport | Keep | Fundamental correctness |
| Dynamic momentum and pressure-like gradients | Keep | Essential for emergence |
| Signed Coriolis | Add | Strong payoff for tiny complexity |
| Seasonal sunlight | Add | Very high visual and climatic payoff |
| Polar walls | Accept | Avoids substantial numerical complexity |
| Equal-area flat cells | Accept | Preserves simple existing architecture |
| Exact spherical distances | Omit | Limited visible benefit |
| Two atmospheric layers | Defer | High complexity, unproven additional value |
| Mountain uplift proxy | Add | High hydrological and visual value |
| Unresolved convection | Add minimally | Necessary for plausible warm-region rainfall |
| Detailed cloud microphysics | Omit | Poor complexity/value ratio |
| Procedural cloud texture | Add | Exceptional visual payoff |
| Ocean currents | Omit | Outside the atmospheric goal |
| Complex orbital mechanics | Omit | Axial tilt and seasons suffice |
| Full fluid flooding | Omit | Hydrology already has an economical model |
| Coarse climate mode | Add eventually | Required for years/s |
| Scripted storms | Reject | Violates the central emergence goal |

---

## 25. Important engineering risks

### Risk A — One-layer weather becomes repetitive

The model might settle into stationary or overly regular circulation.

Response:

- Investigate sustained thermal forcing and physical instability.
- Inspect friction and numerical diffusion.
- Allow small, justified unresolved variability.
- Consider two-layer dynamics only if there is a demonstrated structural limitation.

Do not hide the problem through scripted storm spawning.

### Risk B — Polar walls create unrealistic artifacts

Response:

- First ensure mathematically correct impermeable wall fluxes.
- Inspect high-latitude wave reflections.
- Introduce a small absorbing band only if warranted.
- Keep polar regions approximate.

### Risk C — Atmospheric pressure and temperature feedback become unstable

The current thermal shallow-water closure is an approximation.

Response:

- Preserve a physically interpretable energy budget.
- Avoid independently adding temperature forces and pressure corrections without reconciling them.
- Prefer modest modifications over a wholesale new thermodynamic model.
- Treat numerical stability as a hard requirement.

### Risk D — More rain is produced by creating water

Response:

- Use explicit vapor-to-cloud, cloud-to-rain, and surface-to-vapor transfers.
- Maintain existing conservative remapping.
- Preserve lake evaporation and hydrological storage bookkeeping.
- Account for genuine water sources, sinks, and reservoir exchanges.

### Risk E — Long-term climate becomes excessively uniform

Response:

- Preserve seasonal variability.
- Represent precipitation intermittency and extremes.
- Avoid applying monthly averages as identical daily rainfall.
- Ensure the surface responds to the variability it receives.

### Risk F — The code grows too complicated

This is a major project risk.

Response:

- Prefer modifying existing code over building new frameworks.
- Keep model configuration compact.
- Avoid speculative abstractions.
- Build one coherent feature at a time.
- Remove obsolete experimental complexity once a replacement is genuinely established.
- Reconsider whether every new subsystem earns its maintenance cost.

---

# Part VIII — Instructions to the Implementation Agent

## 26. Your responsibility

You are not being asked to mechanically implement this document line by line.

You are being asked to develop the **simplest compelling, physically meaningful, emergent atmosphere for Terra**.

The roadmap is a carefully considered starting hypothesis.

You should inspect the real repository and use your technical judgment.

### You may change the plan

You are explicitly permitted to:

- Reorder milestones.
- Simplify algorithms.
- Combine closely related changes.
- Replace proposed equations with better alternatives.
- Reject a proposed feature.
- Change numerical parameter choices.
- Adjust the integration architecture.
- Propose a more efficient way to achieve the same visual outcome.
- Depart from the preferred grid or boundary treatment if a clearly superior simple solution exists.

Explain significant departures and their consequences.

Do not make substantial changes merely for theoretical elegance.

### What takes precedence?

In order:

1. Correctness of the implemented physical and numerical system.
2. The user's goal of a beautiful emergent world.
3. Simplicity and maintainability.
4. Performance and responsiveness.
5. The specific implementation suggestions in this document.

Performance is a hard practical constraint, not an excuse to silently violate conservation.

A solution that is mathematically elaborate but visually unremarkable is not a success.

### Keep implementation focused

Avoid:

- Unrelated refactoring.
- New dependencies without strong justification.
- Unnecessary backups.
- Duplicated documentation.
- Multiple competing production weather implementations.
- Excessive testing frameworks or permanent diagnostic machinery.
- Recreating scientific software infrastructure already available in the codebase.

Reuse the existing numerical checks and experiment facilities where helpful.

Add focused validation only where necessary to establish a materially important property.

### Implementation protocol

Before making a major change:

1. Inspect the current relevant source.
2. Identify the simplest implementation.
3. Check its interaction with existing conserved quantities.
4. Determine whether it should be an isolated change or part of a small vertical slice.
5. Implement it without unrelated work.
6. Confirm the project compiles where practical.
7. Summarize what changed and why.

For changes to numerical physics, distinguish:

- Mathematical expectations.
- What the code actually guarantees.
- What has been empirically observed.
- What remains uncertain.

Never claim physical realism or validated numerical stability merely because code compiles.

### Preferred first implementation task

**Start with the latitude-aware Cartesian atmosphere.**

Inspect the present `DryAtmosphere` solver and design the smallest changes needed to:

- Preserve east–west periodicity.
- Make north–south boundaries impermeable.
- Introduce per-row signed Coriolis.
- Retain conservative mass and tracer transport.
- Preserve or appropriately adapt existing numerical checks.

Then move toward latitude-dependent solar forcing.

Do not begin by implementing spherical geometry, a second atmospheric layer, or a new weather engine.

If you identify a cleaner implementation sequence, explain it and proceed accordingly.

---

# Part IX — Research and Technical References

These references provide relevant ideas, not mandatory algorithms or dependencies.

1. **Terra's existing research and implementation**
   - `VISION.md`
   - `ADR_CLIMATE.md`
   - `docs/DRY_ATMOSPHERE.md`
   - `docs/MOIST_ATMOSPHERE.md`
   - `docs/experiments/BASELINE_RESULTS.md`

2. **Rotating shallow-water fundamentals**  
   https://pyrsw.readthedocs.io/en/latest/sw_intro.html

3. **Equatorial beta-plane dynamics and wave behavior**  
   https://climate.usu.edu/people/yoshi/teaching/psc6125/part2/6_1-free_eqwaves/

4. **Emergent jets in shallow-water channel models with varying Coriolis and boundary damping**  
   https://journals.ametsoc.org/view/journals/atsc/66/10/2009jas2941.1.xml

5. **Held–Suarez idealized atmospheric forcing and Rayleigh friction**  
   https://mitgcm.readthedocs.io/en/latest/examples/held_suarez_cs/held_suarez_cs.html

These sources do not establish that Terra's chosen flat global approximation is an accurate Earth simulator. Their value is in demonstrating useful physical and numerical mechanisms that can be adapted economically.

---

# Final Direction

**Build a planet that feels alive, not a planet that requires a supercomputer.**

The preferred architecture is:

**A latitude-aware, east–west-periodic, north–south-bounded, single-layer moist rotating atmosphere, coupled to Terra's existing terrain and hydrology.**

It should have:

- Dynamically evolving winds and pressure-like systems.
- Meaningful northern and southern hemispheres.
- Seasonal and geographical thermal differences.
- Convective and orographic precipitation.
- Rich, physically guided cloud visuals.
- A consistent water cycle.
- Gradual climate-driven landscape evolution.
- A separate economical climate fast-forward mode.

The implementation should remain simple enough to understand, maintain, and extend.

The governing standard for every decision is:

**Choose the smallest model that produces the most convincing emergent world.**

If the current single-layer model eventually meets that standard, stop adding atmospheric complexity.

If it does not, investigate the precise missing mechanism before introducing an entire new layer or numerical framework.

**Simplicity is king.**