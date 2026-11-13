# Terra: an ambitious, physically coherent fast mode

## Purpose

Build a fast mode that can produce a convincing century of environmental history, then hand that world back to detailed weather. The objective is a landscape whose complexity has understandable causes: maritime and continental climates, shifting seasonal rains, productive interiors, droughts, snow-fed rivers, persistent wetlands, migrating forest margins, and recovery after disturbance.

The governing idea is **a small Earth-system model with an unresolved weather distribution**, coupled to one persistent world. Complexity should come from interacting energy, water, terrain and biological processes. Additional noise, biome types and visual detail cannot substitute for those interactions.

This is a high-level implementation direction, not a claim that the proposed closures already work. The highest-risk physical assumptions must earn their place through small experiments before the architecture is expanded around them.

## 1. What the recent diagnosis changes

The present implementation conserves water and advances time consistently, but its spatial behavior is unacceptable. Its failures establish concrete constraints for the replacement:

- Prescribed meridional winds create geographically rigid convergence and divergence belts. In a flat, uniformly supplied control, rainfall varies from approximately 0.82 mm/day near 30° to 6.3 mm/day near the equator and 60°. Removing that flow restores uniform 3 mm/day rainfall. This identifies the imposed circulation as the cause of those particular bands; it does not imply that a realistic rotating planet should have no latitude dependence.
- On the generated reference world after a century, land between 15° and 45° receives only about 20–28 mm/year on average. Some complete latitude rows receive no rain. A conservative water budget has not prevented an implausible regional climate.
- Cold suppression of plant cover is interpreted as desertification. The diagnostic found 523 cold, wet desert tiles averaging approximately 506 mm/year of rain and 123 mm of soil water.
- All-zero initial tree cover, slow establishment and a categorical display threshold hide much of the ecological response. In the reference run, tropical land has no woods after twenty years, despite many suitable sites.
- Clouds inherit the imposed moisture bands. Improving cloud texture cannot repair the circulation that supplies them.
- Existing tests establish useful invariants, but global conservation, a successful mountain experiment and biome variety are insufficient evidence of geographical realism.

**Do not preserve these closures merely because they are already implemented. Do preserve the useful ownership, conservation, calendar, worker and detailed-solver architecture.**

## 2. The experience to design for

A viewer should be able to infer why a region looks the way it does. An ocean-facing coastal forest, a seasonally dry interior, an alpine snow reservoir and a leeward basin should form a connected story. Regions at the same latitude should differ when their geography differs. Regions with different histories should sometimes differ even when their present annual means are similar.

Over decades, the world should show persistent change and resilience: forests expanding through favorable corridors, drought thinning canopy before exposing bare ground, wet years recharging depleted stores, lakes recovering slowly, and snowmelt sustaining rivers after the rainy season ends. Some regions should remain stable for centuries. Constant visible change is not itself a measure of quality.

On slowing down, detailed weather should begin over the actual resulting soils, vegetation, snow, lakes and thermal state. Its storms need not match an imaginary uninterrupted trajectory, but its moisture, circulation and energy adjustments must be explainable.

Broad tropical, subtropical and polar tendencies are legitimate. Straight, nearly compulsory biome stripes that overwhelm coastline, relief and continentality are a failure. No target biome percentages will be imposed; genuinely arid or forest-dominated worlds remain possible.

## 3. Architectural commitments

### One world, three kinds of state

| Kind | Examples | Treatment |
|---|---|---|
| Persistent physical state | Soil and groundwater, lakes, snow, vegetation, surface heat, atmospheric water | One authoritative owner; conservative exchanges across all modes |
| Climate state | Seasonal thermal contrast, transport statistics, cloud regimes, drought persistence | Small evolving fields; explicitly modeled estimates with uncertainty |
| Instantaneous weather | Individual vortices, cloud geometry, gusts, storm positions | Resolved in detailed mode; statistically represented during fast mode |

Climate means and representative events must never become duplicate physical reservoirs. Temporary remapping buffers must reconcile to the authoritative inventories at every committed boundary. New physical stores such as snow or groundwater become shared stores, rather than fast-mode-only corrections.

### A coupled reduced atmosphere, not a second weather engine

Retain the detailed rotating shallow-water model. Build a separate, cheap statistical atmosphere around energy balance, physically constrained circulation and moisture budgets. It should not integrate synoptic fluid dynamics throughout a century.

The reduced model may have richer diagnostic vertical structure than the detailed one-layer model. For example, two diagnostic transport streams can describe moist low-level inflow and a drier return flow. That is a possible closure, not a requirement to add a second prognostic fluid layer or another weather solver.

### Different spatial resolutions for different jobs

Use a small climate mesh for broad energy and transport; represent unresolved terrain, surface types and watersheds beneath it. Climate-cell size must not determine the smallest meaningful mountain, coastal strip or ecological boundary.

Retain the current equal-area Cartesian geometry and explicit polar walls initially. Do not smuggle spherical metric factors into only part of the solver. Revisit planetary geometry as a separate, measured decision if it becomes the limiting physical error.

### Physical time and computational scheduling remain separate

The worker may divide or yield an operation without changing event chronology, random choices, ecological eligibility or conservation. A request covering twenty years must visit the important intervening seasons and environmental thresholds. Any acceleration that skips repeated cycles must demonstrate that it preserves their consequences.

## 4. Seasonal energy and ocean memory

Replace temperature relaxation toward a mostly immutable geographical prior with a reduced surface–atmosphere energy model. The prior remains useful for initialization and as a conservative fallback during development.

The essential budget includes seasonal solar geometry, surface albedo, longwave cooling, sensible and latent exchange, land/ocean heat capacities, and horizontal heat transport. Use a modest vertical thermal description sufficient to distinguish surface heat, representative air temperature and lapse-rate effects. Temperature, humidity and circulation must be solved as interacting quantities rather than independent imposed maps.

Ocean heat capacity must create maritime moderation and seasonal lag. Start with a slab ocean and conservative lateral heat exchange restricted by basin connectivity. A more structured ocean heat-transport closure is an ambitious extension, admitted only if it improves coastal climates at acceptable cost. Do not insert an Earth-specific conveyor or arbitrary warm western coasts into every generated planet.

Vegetation, soil dryness, snow and surface water influence albedo and the partition between sensible and latent heat. These feedbacks should be bounded and introduced individually, so a weak rainfall estimate cannot immediately trigger an irreversible hot-desert feedback.

**Desired consequence:** circulation responds to continental heating, ocean moderation and relief. The same latitude can contain different thermal and seasonal environments.

**Energy contract:** internal heat and phase-change transfers reconcile; external radiation and any prescribed boundary fluxes are recorded. Every remaining relaxation or numerical adjustment has a named residual. Energy closure is developed progressively, without presenting an approximate budget as exact.

## 5. Geography-responsive circulation: the critical research gate

The first major prototype must answer a difficult question: can a very small diagnostic atmosphere produce useful, geographically responsive transport without recreating a general circulation model?

### Proposed primary approach

Diagnose broad circulation from evolving thermal gradients, rotation, drag, terrain and a constrained pressure/mass balance. Combine a balanced horizontal component with a bounded overturning and ageostrophic component. Diagnose the ascent, subsidence and return transport associated with the latter.

Latitude enters through rotation and insolation. It must not directly select a rainfall belt, a permanently fixed wind reversal or a prescribed sequence of convergence zones. A zonal reference circulation may supply a weak initial condition or background closure, but geographical forcing must be able to shift, interrupt and redirect it.

The physical distinction between low-level flow and vertically integrated transport is essential. Do not advect the entire atmospheric water column using a convergent near-surface wind and leave the associated air-mass circulation unexplained. Either use mass-consistent column transport or account explicitly for low-level inflow, ascent and compensating return flow within the column closure.

Solve the diagnostic balance at climate timescales, with warm starts and bounded sparse iterations. A balance solve should replace expensive explicit marching through fast atmospheric adjustment; it must not merely hide instability behind large implicit timesteps.

### What the prototype must show

- Moving or rotating a continent changes regional winds and moisture pathways.
- Differential seasonal land heating can produce geographically anchored wet-season inflow and seasonal reversal where the configuration supports it.
- Changing a mountain range affects steering, ascent and downstream supply.
- A thermally uniform control has no unexplained convergence pattern injected by a latitude formula.
- Latitude-dependent climates remain possible without mandatory latitude-wide drought.
- Divergence, vertical exchange and boundary fluxes reconcile with the chosen air-mass interpretation.

### Decision if this is too costly or too fragile

Evaluate one bounded alternative: a simpler mass-consistent background transport with a geographically forced stationary circulation response and explicit transient mixing. Compare physical outcomes and cost on the same small worlds. Do not create multiple competing production engines or spend weeks on an open-ended solver contest.

If neither approach passes the geographical tests, revise the atmospheric closure before building more ecology on top of it. Cosmetic perturbations to the current sine winds are not a sufficient replacement.

## 6. Moisture, ascent and precipitation as one regional system

Preserve the central column budget:

`change in atmospheric water = evaporation − precipitation − net horizontal moisture export`.

Give each exchange a physical pathway. Ocean evaporation should enter nearby air, travel inland through diagnosed transport, condense under appropriate ascent and cooling, and leave a depleted downwind supply. Land evapotranspiration should recycle water into that same system.

Represent three complementary sources of precipitation:

1. **Convective adjustment:** thermodynamic instability, available moisture and surface heating support warm-season rain over suitable lowlands. A mountain must not be the only effective route to condensation.
2. **Large-scale ascent:** dynamically diagnosed convergence and transient disturbances support broad precipitation systems.
3. **Orographic ascent:** terrain encountered by moist flow causes cooling and upstream rainout, with downstream drying and possible recovery through mixing and recycling.

Avoid counting the same ascent or latent release in multiple closures. Condensation, re-evaporation and rainout must use compatible parcel/column thermodynamics. Moisture supply and energy constrain rain; a desirable rainfall map does not authorize a water source.

Unresolved eddies should transport heat and moisture according to the climate's gradients and estimated transient activity. Use direction-dependent, bounded mixing where justified. A single constant diffusivity should not be expected to represent storm tracks, trade-wind transport and cross-mountain exchange equally well. Numerical diffusion must be measured separately from the intended physical mixing.

### Terrain below the climate mesh

Precompute a small terrain description for each climate region: elevation distribution, ridge orientation, effective barrier height, coastal exposure and directional connectivity. Preserve both windward ascent and leeward depletion when a narrow ridge disappears from the mean elevation.

Any downscaling of precipitation must account for the upstream water it removes. Allocating coarse rainfall toward mountains after the atmospheric transport solve may improve a picture while giving the wrong downstream budget. Prefer a bounded terrain-aware flux/rainout operation that returns its effects to the coarse budget.

Do not attenuate moisture permanently by distance inland. Productive continental interiors should be possible when circulation and recycling sustain them; isolated interiors should become dry for identifiable reasons.

## 7. Unresolved weather with memory and physical consequences

Introduce a small, spatially coherent weather-regime state: wet/dry persistence, transient activity and a slowly varying moisture/temperature anomaly. Condition regime behavior on the evolving climate and geography. Let this support droughts, unusually wet seasons, moving precipitation corridors and variation in storm intensity.

Use bounded stochastic processes and calendar-indexed randomness. The same seed and physical history must not produce a different drought merely because the worker requested a larger interval. Spatial scales should relate to circulation and terrain rather than screen coordinates.

The generator proposes event timing, duration and intensity. The coupled water and energy calculation determines what can actually occur. Events cannot spend a later storm's water or a future evaporation flux before it exists. Representative events must reconcile exactly to the physical interval budget; they cannot add a second rainfall total on top of it.

Preserve correlations that matter to landscapes: hot dry periods increase stress; repeated wet days promote saturation; intense rain can run off rather than recharge roots; snow changes the timing of liquid input. A model that reproduces annual rainfall while losing those relationships is inadequate.

Do not simulate every historical storm. Use a small number of representative chronological events or wet/dry segments per climate interval, refined only when a threshold or rapid response requires it.

## 8. Land water with storage, access and travel time

The surface should remember more than a single bucket. The intended minimum is shallow soil, root-accessible storage, a slow groundwater/baseflow store, snow water and existing surface water. Keep the number of stores small and give each one a clear role.

- Infiltration and surface runoff depend on intensity, saturation, slope and soil properties.
- Root-zone access sustains vegetation through short dry spells without making it immune to prolonged drought.
- Percolation and groundwater recession provide delayed river flow and basin memory.
- Snow accumulation, melt and frozen-ground effects distinguish cold landscapes from dry ones and produce seasonal river timing.
- Lakes and wetlands buffer inflow and return evaporation through their real inventories. Endorheic basins can fill, shrink or remain dry under different histories.
- Routing includes meaningful travel/storage times at the scales represented. Immediate same-step passage to the ocean should not erase all catchment memory.

Evapotranspiration follows available energy, atmospheric demand, vegetation conductance and accessible water. Potential evaporation is no longer an annual temperature-only classifier formula unrelated to the surface exchange calculation.

### Efficient landscape representation

Use hydrological response units grouped by catchment, elevation, soil, exposure and vegetation. These are computational groups for actual surface inventory, not parallel copies of tile water. Define one ownership and mapping strategy before implementation; splitting or merging units must preserve mass and the distributional information needed by thresholds.

Aggregate linear responses analytically where possible. Resolve nonlinear wet/dry and saturation transitions with a few chronological events. Update fine tiles when local events or changed state require it, rather than reconstructing every tile after every atmospheric operation.

This is a major source of the performance headroom needed for better physics. A coarse atmospheric grid alone cannot pay for repeated whole-world surface passes.

## 9. Vegetation as a living continuum

Separate three questions: what can grow, how much currently exists, and why growth is limited.

Represent a small set of functional strategies rather than a large species model: woody cover with contrasting cold/drought tolerance, herbaceous cover and bare ground. A distinction between evergreen and seasonally dormant woody vegetation is valuable if it improves water use and appearance; it need not immediately produce new terrain categories.

Suitability responds to growing-season warmth, frost exposure, root-zone water access, seasonal water deficit and persistent stress. Annual mean temperature and rainfall alone cannot determine the result. Cold limitation, water limitation, disturbance and exposed rock must remain distinguishable even if several produce sparse cover.

Growth, establishment, competition, seed access and mortality act on different timescales. Young forest can grow quickly when resources permit; mature canopy stores ecological memory; repeated drought causes decline before wholesale biome replacement. Migration should follow neighboring habitat and suitable corridors without requiring every favorable pixel to wait for a distant seed indefinitely.

Use a compact stress/mortality history and bounded analytic growth updates. Do not add detailed carbon chemistry, explicit trees or a large age-cohort system unless the simpler model demonstrates a specific missing behavior.

### Initial conditions and visible change

Generate an explicitly defined ecological starting state consistent with the world's intended initial age. A mature-looking world should not silently begin with every forest's biomass set to zero. Any physical spin-up advances the actual calendar and budgets; diagnostic initialization must not masquerade as observed climate history.

Render continuous canopy, grass and exposed-ground fractions within the existing terrain system. Categorical labels remain useful for inspection, but a 54% to 56% cover change should not be the first visible sign of several decades of growth.

### Feedbacks and disturbance

Couple cover back to albedo, roughness, interception, root access and evapotranspiration. Introduce feedbacks gradually and retain experiments that distinguish a genuine alternative equilibrium from an accidental numerical drying loop.

Once the core system is credible and affordable, add low-frequency fire or severe-weather disturbance. Disturbance must emerge from fuel, dryness and weather opportunity, conserve the modeled water/energy terms, and be able to explain a lasting grassland or recovering forest. It is an extension, not an excuse for incorrect baseline vegetation.

## 10. Reentry as a climate-conditioned weather realization

Construct pressure, winds, thermal structure, humidity variability and condensate jointly. Condition the realization on season, geographical circulation, transient activity, current moisture and the evolved surface. Pressure and wind perturbations must remain compatible with the detailed solver's actual thermal pressure law.

Cloud cover should reflect a subgrid humidity distribution and physically supportable saturation, rather than a fixed condensate allowance pasted onto any humid column. Avoid creating cloud water that the first few detailed steps immediately evaporate everywhere. Distinguish the unavoidable loss of individual cloud identity from a systematic collapse of a moist climate's cloud regime.

Preserve all authoritative stores. If a realization redistributes atmospheric moisture, constrain the redistribution to justified regional pathways and reconcile it exactly. Do not globally teleport water to fill selected storms. Record initialization energy changes and bound numerical adjustment, while recognizing that exact conservation alone does not establish a good realization.

Reentry should take a bounded amount of work and require no long hidden physical spin-up. Check its evolution after minutes, hours and a day, including high-latitude clouds where moisture and temperature support them. No polar cloud quota is introduced.

The fast-mode display should show a plausible current representative realization, with explicit semantics, rather than rendering century-mean rainfall as a stationary cloud field.

## 11. Learning from detailed weather without self-confirmation

Keep independent detailed observations separate from reduced-model estimates. Track only the provenance needed to prevent self-calibration and to assess seasonal coverage and spin-up.

Detailed runs may refine a small number of interpretable quantities: effective mixing, precipitation efficiency, surface exchange or disturbance statistics. Bound corrections, require representative coverage and avoid fitting transient storms or compensating one faulty process with another.

The simplified detailed atmosphere is evidence, not an infallible climatological reference. Compare useful statistics and physical relationships; do not force the fast climate to reproduce every weakness of the one-layer solver. Optional calibration bursts must have an explicit compute budget and must not secretly advance or modify the visible world's authoritative state.

## 12. Performance envelope and numerical strategy

### Target and honest fallback

Use the default 283×212 world and the existing desktop hardware as the reference configuration. Record warmup, seeds, settings, UI publication and the exact benchmark revision.

Aim for approximately **100 ms per simulated year** of sustained end-to-end climate work: ten years per real second with a responsive interface. The currently measured 120–150 ms/year range is an acceptable transitional envelope if the new solution delivers a substantial quality improvement. The user's acceptance of missing ten years/s is not permission for unbounded cost growth.

Treat the following as provisional design allocations, not measured performance claims:

| Work | Aspirational wall-time allowance per simulated year |
|---|---:|
| Climate energy, circulation and atmospheric moisture | 25 ms |
| Soil, snow, groundwater and event integration | 20 ms |
| Catchment routing, lakes and surface exchange | 12 ms |
| Vegetation and disturbance | 5 ms |
| Geography/cache upkeep, amortized | 5 ms |
| Snapshot preparation and climate presentation | 8 ms |
| Runtime/coordination overhead | 5 ms |
| Headroom for variability and future refinement | 20 ms |
| **Total** | **100 ms** |

Measure compute and rendering separately as well as together. Renderer frame time must not be used as a proxy for climate cost. Interactive frame stability, control latency and tail transaction times matter as much as average simulated years/s.

### Spend work where it changes outcomes

- Begin with roughly 24–48 climate cells across each major horizontal direction; choose resolution using physical and performance comparisons, not a fixed aesthetic preference.
- Update atmospheric balance on monthly or adaptive seasonal intervals, with cheaper internal moisture/event work where necessary. Do not require daily global atmospheric solves by default.
- Use stable conservative transport or implicit budget solves appropriate to climate intervals. Long steps must not jump past barriers, change inland residence times arbitrarily or erase rain shadows.
- Cache stationary geometry, sparse operators, directional terrain data and spatial anomaly bases. Recompute dynamic responses when their dependencies change.
- Use analytic reservoir and vegetation updates between genuine events. Preserve nonlinear season ordering rather than applying years of accumulated forcing at once.
- Restrict fine-grid reconstruction and rendering to visible publication cadence and dirty regions. Keep rendering state separate from authoritative physical integration.
- Profile allocations, expensive scalar functions, repeated mapping and whole-world scans before changing numerical fidelity.
- Keep memory bounded with simulated age. A thousand-year run must not accumulate a thousand years of full-resolution snapshots or weather history.

Do not introduce GPU compute, heavy dependencies or mandatory parallel infrastructure as the first solution. Consider them only after profiling a compact CPU design. A single-core budget keeps the result usable and leaves the rest of the application responsive.

### Responsive long advances

Yield worker control at deterministic safe boundaries, with an aspirational 10–25 ms work slice and control acknowledgement below about 100 ms. A one-year transaction must be interruptible without committing a half-updated world, losing its event sequence or resetting the physical integrator. Longer requested intervals increase batching efficiency, not the length of an unresponsive critical section.

### Fidelity reduction hierarchy

If over budget, reduce snapshot/cloud publication first, then optional diagnostic/calibration work, then unnecessary spatial detail in smooth climate fields. Simplify optional disturbance or ocean refinements before essential seasonal water chronology. Any lower numerical fidelity must pass the same geography and partition comparisons. Neither biomass growth nor water conservation may be disabled because the selected speed is high.

## 13. Verification through a small collection of worlds

Use a compact visual/physical review set, reused throughout development. Avoid a large formal acceptance framework. A few good counterfactuals should carry more weight than hundreds of shallow assertions.

| World or intervention | What it establishes |
|---|---|
| Flat uniform source and temperature; then seasonally heated ocean world | Separates imposed numerical structure from physically forced latitude dependence |
| Ocean–coast–ridge–interior; rotate or move the ridge | Tests actual upstream removal, steering and geographically responsive rain shadows |
| Wide continent with connected maritime pathways; compare a different coastline at the same latitude | Tests inland supply, continentality and the absence of compulsory desert rows |
| Strong seasonal land/ocean contrast with a river basin and enclosed lake | Tests wet-season reversal, dry spells, recharge, runoff and lake memory |
| Cold humid land with elevation bands and a snow-fed catchment | Distinguishes cold sparse vegetation from aridity and tests delayed meltwater |
| Established forest exposed to a prolonged dry interval and subsequent recovery | Tests resilience, mortality, regrowth and state continuity across simulation speeds |

For each, inspect seasonal rainfall and evaporation, moisture flux arrows and convergence, temperature, root-zone water, river/lake changes, vegetation fractions and their limiting factors. Show actual units. Keep the visual atlas small enough to understand at a glance.

Counterfactual changes must produce the expected causal response. Moving a mountain should move its effect; moving a coastline should alter supply. Noise that merely obscures a fixed latitude pattern does not pass.

Compare equivalent durations partitioned into different worker requests and physical integration intervals. Exact agreement is appropriate for deterministic bookkeeping and identical event schedules; statistically comparable outcomes are appropriate when the numerical approximation changes. Choose tolerances from sensitivity and ecological significance rather than convenient round thresholds.

Conservation checks remain mandatory, including newly introduced stores. Add local/catchment and seasonal checks so a closed global budget cannot conceal implausible redistribution. Track energy residuals and momentum/air-mass assumptions appropriate to each closure.

Use several contrasting procedural seeds only after the canonical failures are understood. Review a century and a millennium for secular drift, bounded memory, ecological saturation and persistent artifacts. Do not assert a prescribed forest or desert percentage.

## 14. Delivery sequence and stop conditions

### Stage A — Establish the causal baseline

Preserve the recent band diagnosis and a compact reference atlas. Profile current climate, surface, routing and publication costs with ordinary gameplay settings. Define authoritative ownership for added water/energy stores and the meanings of climate statistics.

**Exit:** a reproducible account of the present failures, numerical budgets and measured cost. No cosmetic band suppression.

### Stage B — Prove a better atmosphere before broad integration

Prototype the coupled seasonal energy and geography-responsive circulation closure. Use conservative moisture transport with simple surfaces. Include convective and large-scale ascent, an explicit air-mass interpretation and terrain-aware upstream rainout.

**Exit:** the circulation counterfactuals work, continental rainfall is credible, and the prototype fits a plausible atmospheric budget. If this fails, revisit the closure; do not proceed by tuning vegetation thresholds.

### Stage C — Make seasonal land water excellent

Integrate root-zone storage, groundwater/baseflow, snow, event chronology, lakes and catchment responses. Introduce response units or another ownership-preserving aggregation that demonstrably saves full-resolution work. Close water and phase-change accounting.

**Exit:** wet/dry chronology and river/lake responses remain believable across request partitions, cold wet land stays hydrologically distinct from desert, and performance headroom survives the added stores.

### Stage D — Deliver visible ecological continuity

Implement growing-season suitability, persistent fractional cover, stress and differentiated growth/mortality. Establish sensible initial ecological state. Render gradual canopy/grass/bare-ground change and explain its limiting factors in inspection views. Couple the first bounded land-cover feedbacks.

**Exit:** forest establishment and decline are visible throughout the transition, cold limitation cannot trigger a false arid biome, and repeated drought/recovery produces credible historical differences.

### Stage E — Add coherent variability and close the weather handoff

Condition cheap regimes/events on the new atmosphere and couple their consequences to land water. Build a climate-conditioned detailed realization and validate its first day. Audit modeled-versus-resolved history and initialization adjustments.

**Exit:** variability changes hydrology and ecology in meaningful ways, climate switching preserves the world, and cloud/pressure evolution is physically supported rather than cosmetically injected.

### Stage F — Optimize the integrated experience

Profile the actual running desktop game with clouds, map publication and speed changes. Bound worker slices, allocation and memory. Revisit atmospheric resolution, cached operators and response-unit activity using measured error/cost comparisons.

**Exit:** sustained performance is near the stated envelope, control remains responsive, and numerical simplification has not restored the original geographical defects. Report any unmet budget explicitly.

### Stage G — Admit only high-value extensions

With the core complete, evaluate basin-dependent ocean heat transport, additional vegetation strategies, fire and richer wetland dynamics one at a time. Each extension must explain a visible missing phenomenon, have a conserved/accounted physical interpretation, and pay for its cost from measured headroom.

Full tectonics, a 3D atmosphere, explicit trees, a global carbon cycle and a fully dynamic ocean are outside this plan. They are not prerequisites for a remarkably convincing evolving world.

## 15. Repository evolution and maintainability

Retain `WorldSimulation`, `SimulationWorker`, the detailed atmosphere, conservative surface/hydrology transfers and immutable presentation snapshots. Evolve their contracts only where shared new state or safe scheduling requires it.

Expect to replace the physical roles currently concentrated in `ClimateNormals` and `ClimateMoistureTransport`; retain their useful initialization, calendar and mapping functions where appropriate. Turn `FastClimateStepper` into a coordinator of coherent budgets and events rather than another location for unrelated correction factors.

`TerrainMoistSurface` and hydrology should share clear inventory/exchange contracts for roots, snow and baseflow. `World` should own ecological state or delegate it to one compact owner; rendering categories must be derived from that state. Use small modules organized by physical process, not an extensible framework with dozens of policies and provenance types.

Give geography an explicit revision/invalidation contract: elevation, coastlines, soil properties, canopy and water extent invalidate the appropriate derived operators. Dynamic land cover should not force rebuilding static terrain data. A future coastline change must update hydrology topology and climate connectivity as one coherent world change.

Migrate in reviewable vertical slices, keeping one production owner for each store. Diagnostic comparison implementations may exist temporarily; obsolete production closures should be removed once their replacements are validated. No hidden runtime toggle should leave two incompatible world-evolution systems indefinitely supported.

## 16. Principal risks and how to confront them

| Risk | Required response |
|---|---|
| A cheap diagnostic circulation cannot reproduce useful regional climates | Test it first; keep the bounded alternative available; simplify elsewhere before concealing failure with noise |
| Coarse transport or downscaling produces attractive but incorrect rain shadows | Measure barrier crossing, upstream rainout and downwind budgets; refine terrain/flux treatment rather than repainting rain |
| New land stores exceed the performance budget | Prove response-unit ownership and analytic/event integration before adding more stores |
| Vegetation–rainfall feedback locks large regions into artificial dry states | Introduce feedbacks incrementally, perturb initial conditions, inspect recovery and distinguish physical persistence from numerical traps |
| Representative events change results with transaction size | Key events to physical time and integrate them chronologically with funded budgets |
| Cold wet regions still look like deserts | Keep limitation cause explicit and render cold sparse cover independently from arid bare ground |
| Fast and detailed weather agree only at the instant of switching | Inspect the following day and diagnose systematic moisture/thermal relaxation, not just handoff totals |
| Ambition turns into endless tuning or a huge framework | Require causal improvements from each stage, keep the reference atlas small, and ship coherent vertical slices |

## 17. Research grounding

The proposal draws on a few principles rather than attempting to reproduce a scientific model wholesale:

- [CLIMBER-X climate description](https://gmd.copernicus.org/articles/15/5905/2022/): reduced energy/moisture budgets, diagnosed circulation and parameterized transient transport can support long-timescale climate integration. Terra must still demonstrate its own geographically responsive approximation.
- [LPJ-GUESS/LSM](https://gmd.copernicus.org/articles/15/6709/2022/): vegetation, accessible soil water and surface exchange interact; separate fast physiological/water responses from slower establishment and structural change.
- [WAM2layers v3](https://gmd.copernicus.org/articles/18/4335/2025/index.html): useful context for conservative moisture transport and the importance of interpreting vertical exchange and column budgets consistently. This is not a proposal to embed that model.
- [Virtual Rain v1.0](https://gmd.copernicus.org/articles/19/9489/2026/): reference material for stochastic rainfall generation and temporal disaggregation. Terra's events must additionally respect its finite, coupled world inventories.

All equations, closure strengths, numerical choices and compute allocations proposed above remain Terra-specific engineering hypotheses until validated.

## Completion standard

The result should withstand inspection of both its beautiful regions and its awkward ones. A viewer should be able to follow water from an ocean through weather, mountains, soil, vegetation, groundwater and rivers, across many seasons and decades, without encountering a latitude formula or mode switch that breaks the story.

Success is a varied world with credible causes, meaningful environmental memory, bounded numerical error and an honest measured compute cost. More detail is valuable only when it strengthens those qualities.
