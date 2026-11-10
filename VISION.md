
# VISION.md — Terra: A Beautiful, Emergent Living Atmosphere

**Status:** Long-term architectural vision, not an instruction to rewrite the existing implementation.

**Repository:** https://github.com/jensbrunner/terra

**Baseline:** Main branch, reviewed 10 November 2026. Reinspect the latest repository before making decisions.

**Audience:** An advanced autonomous software engineering and scientific research agent.

---

# 1. The Vision

Terra should become a beautiful, physically grounded, highly emergent simulation of a living planet.

The player observes a strictly **2D, top-down world** in which weather systems form, move, grow, interact, and disappear naturally.

We want to watch:

- High- and low-pressure systems develop spontaneously.
- Winds circulate around pressure systems.
- Large cyclones organize and evolve.
- Weather fronts form between different air masses.
- Cloud bands wrap around storms.
- Small convective clouds grow into localized thunderstorms.
- Rainfall emerges from atmospheric moisture and dynamics.
- Mountains force clouds and precipitation on their windward slopes.
- Dry interiors and rain shadows arise naturally.
- Oceans, continents, seasons, and terrain influence regional climate.
- Rivers, lakes, vegetation, and eventually ecosystems respond to weather.
- The planet develops recognizable climates over many simulated years.

The experience should feel like watching an actual planet through a satellite, with the ability to accelerate time and observe the atmosphere reorganizing itself.

## The most important principle

**Maximize visually compelling, physically meaningful emergence per unit of complexity and computation.**

We are not developing an operational weather forecasting system.

We do not need to predict Earth's actual weather or reproduce every atmospheric process.

We want the smallest, fastest, most elegant system that produces extraordinarily convincing emergent weather.

Prefer a handful of well-coupled physical mechanisms that generate rich behavior over dozens of elaborate, individually scripted effects.

**The goal is not maximum physics. The goal is maximum beauty and emergence from minimal physics.**

---

# 2. Fundamental Design Philosophy

## 2.1 Physics generates weather; rendering makes it beautiful

Separate the system into two fundamentally different responsibilities.

### Atmospheric simulation

Determines the physically meaningful behavior:

- Atmospheric mass and pressure-like fields
- Wind velocity
- Temperature and thermal structure
- Horizontal convergence and vertical exchange
- Water vapor
- Cloud formation
- Condensation and evaporation
- Precipitation
- Surface energy and moisture exchange

These quantities must evolve through coherent equations and interactions.

### Weather visualization

Determines how these quantities appear to the player:

- Detailed cloud silhouettes
- Wispy cloud boundaries
- Fine-scale cumulus clusters
- Cloud holes and variation
- Smooth frontal structures
- Rain bands and showers
- Visual cloud movement
- Storm organization and presentation

The visualization may use procedural detail, coherent noise, advection, interpolation, and other inexpensive techniques.

However, it must remain driven by the physical simulation.

Do not invent physical rainfall, atmospheric water, pressure systems, or storms merely to improve appearance.

**Rendering may enhance physical structures, but must not fabricate the underlying weather.**

This separation is central to achieving the desired performance.

## 2.2 The visible world is strictly 2D

Terra is a top-down, two-dimensional world simulator.

There will be no 3D camera, volumetric cloud rendering, or visible vertical atmospheric geometry.

However, invisible atmospheric layers are acceptable when they provide significant additional emergence.

A simulation with two internal atmospheric layers remains fundamentally 2D from the player's perspective.

Those layers are simply additional arrays associated with the horizontal map.

The number of internal layers should be determined exclusively by their physical value relative to complexity and performance.

**Do not implement extra vertical layers merely because real atmospheric models have them.**

## 2.3 Emergent storms are a first-class objective

A successful implementation must generate coherent, moving weather systems without explicitly spawning them.

In particular, the atmosphere should support:

- Spontaneously growing disturbances
- Rotating low-pressure systems
- Persistent high-pressure regions
- Atmospheric waves and meandering jets
- Organized cloud and precipitation bands
- Interacting storms
- Storm intensification and weakening
- Dissipation through physical mechanisms

Small deterministic perturbations may seed instability.

Prescribed latitude-dependent forcing and idealized initial conditions are acceptable.

However, the long-term dynamics must not depend on hand-scripted storm positions, fixed wave phases, arbitrary pressure noise, or periodic storm spawning.

---

# 3. Recommended Atmospheric Model

## 3.1 Preferred direction: Two-layer moist rotating shallow water

The leading candidate for Terra is a:

**Coarse-resolution, two-layer, moist rotating shallow-water atmospheric model.**

This is a preferred experimental direction, not an irrevocable decision.

The model should be selected only after comparative experiments demonstrate that it provides the best emergence/performance tradeoff.

### Why shallow water?

Rotating shallow-water equations can produce:

- Dynamically evolving pressure-like gradients
- Fluid acceleration
- Coriolis deflection
- Geostrophic balance
- Rotating vortices
- Atmospheric wave propagation
- Jet instabilities
- Vortex interactions
- Large-scale organized circulation

Unlike the present prescribed wind field, the velocity field evolves dynamically.

The underlying equations are relatively compact.

For a single homogeneous layer, a simplified representation is:

Mass continuity:

    ∂h/∂t + ∇·(h u) = 0

Horizontal momentum:

    Du/Dt + f k×u = -g∇h + F

Where:

- h represents layer thickness or an equivalent mass variable
- u is horizontal velocity
- f is the Coriolis parameter
- g is gravitational acceleration, or an appropriate effective-gravity parameter
- F represents physical forcing, drag, and mixing

These equations are illustrative. The final two-layer formulation must be derived consistently rather than assembled by independently combining these terms.

### Why two layers?

A single-layer rotating shallow-water model can create compelling vortices and other barotropic disturbances.

However, it cannot adequately represent vertical shear and the interactions responsible for baroclinic instability.

Baroclinic instability is one of the principal mechanisms responsible for real midlatitude cyclones.

Two interacting layers can represent a simplified version of this mechanism.

This opens the possibility of:

- Cyclones growing from temperature-related instability
- Storms extracting energy from larger-scale gradients
- Frontal organization
- More realistic storm intensification
- Structured cloud and precipitation regions
- More varied circulation than a single homogeneous layer

Published moist two-layer models have demonstrated growing cyclonic disturbances and comma-shaped precipitation structures.

That is remarkably close to Terra's desired visual behavior.

### Important scientific caveat

A two-layer shallow-water model is not equivalent to a complete two-level hydrostatic primitive-equation atmosphere.

Its layer-thickness, density, pressure, and thermal interpretations must be physically consistent.

The agent must explicitly investigate how:

- Horizontal thermal gradients
- Layer stratification
- Reduced gravity or buoyancy
- Interlayer coupling
- Moist condensation
- Latent heating

produce the desired instabilities.

Do not assume that simply adding a second velocity field generates realistic fronts.

Likewise, a shallow-water height anomaly is not automatically a physically calibrated surface pressure in hectopascals.

Pressure diagnostics must have a defensible physical interpretation.

---

# 4. Mandatory Model Comparison Before Commitment

Evaluate these alternatives.

| Candidate | Advantages | Limitations | Role |
|---|---|---|---|
| Existing prescribed wind model | Very fast, already integrated | Fundamentally limited emergence | Baseline/fallback |
| Single-layer rotating shallow water | Simple, genuine vortices and waves | No baroclinic structure | First prototype |
| Two-layer rotating shallow water | Potential for baroclinic instability | More coupled physics | Preferred target |
| Moist two-layer shallow water | Storms plus organized condensation | Requires sound thermodynamic coupling | Preferred advanced target |
| Two-layer quasi-geostrophic model | Efficient midlatitude cyclone dynamics | Poor near-equatorial/strongly ageostrophic behavior | Research alternative |
| Small hydrostatic primitive-equation model | Greater physical completeness | More variables and numerical complexity | Escalation option |
| Existing atmospheric engine | Mature implementation and physics | Integration and runtime costs | Benchmark/alternative |

The assessment must specifically answer:

1. What visually interesting behavior emerges without scripting?
2. What is the minimum set of prognostic variables?
3. What timestep restrictions apply?
4. What is the computational cost at Terra's target resolution?
5. How well can the model represent tropical and midlatitude weather?
6. Does it produce coherent storms with recognizable lifecycles?
7. Does moisture meaningfully affect circulation?
8. Can the model remain deterministic?
9. How difficult is the implementation and maintenance?
10. Does the extra realism actually improve what a player sees?

Do not automatically favor the most sophisticated model.

**A simpler model producing 90% of the desired visual emergence at 20% of the cost should win.**

The percentages here describe the decision principle, not established measurements.

---

# 5. Investigate SpeedyWeather.jl Seriously

An important alternative is:

https://github.com/SpeedyWeather/SpeedyWeather.jl

SpeedyWeather is a modern atmospheric modeling framework supporting model hierarchies including:

- Barotropic vorticity
- Rotating shallow water
- Hydrostatic primitive equations
- Moist atmospheric processes
- Atmospheric parameterizations
- CPU and GPU implementations

Its published performance claims are promising for accelerated climate simulation.

Before implementing a custom atmospheric solver, investigate whether SpeedyWeather could substantially reduce implementation and maintenance effort.

Research:

- Current capabilities and available model configurations
- Performance on desktop CPUs
- Performance on Apple Silicon
- Simulation throughput at candidate resolutions
- Stability over multi-year simulations
- Moisture and precipitation capabilities
- Initialization from custom planetary geography
- Coupling with external hydrology and terrain
- Deterministic execution
- Licenses and deployment requirements
- Integration with Kotlin/JVM
- Native library versus separate process integration
- Feasibility of providing atmosphere snapshots to Terra

Consider running a small external proof of concept.

However, recognize the costs of introducing Julia into a Kotlin/KorGE game.

Do not adopt a large dependency merely because it already implements more physics.

The best solution is the one with the lowest **total** complexity, including implementation, runtime, packaging, debugging, integration, and ongoing maintenance.

Use SpeedyWeather as a scientific and performance reference even if Terra ultimately implements its own compact solver.

Also investigate SPEEDY and Isca for modeling ideas.

---

# 6. Atmospheric Resolution: Physics and Rendering Must Be Independent

Terra currently uses approximately 60,000 world tiles.

The atmospheric dynamics do not necessarily need that resolution.

Candidate horizontal atmospheric grids:

| Resolution | Cells | Relative to existing map |
|---|---:|---:|
| 96 × 72 | 6,912 | ~11.5% |
| 128 × 96 | 12,288 | ~20.5% |
| 192 × 144 | 27,648 | ~46.1% |
| 283 × 212 | 59,996 | 100% |

The initial benchmark candidates should be:

**96 × 72 and 128 × 96 atmospheric cells.**

These are starting points, not final requirements.

A two-layer 128 × 96 atmosphere has 24,576 horizontal layer-cells.

This is fewer than the existing 59,996 single-layer world cells, although each new cell will require considerably more computation.

Do not infer actual performance from cell counts alone.

### Coarse atmosphere, detailed weather visualization

The physical atmospheric model determines:

- Storm position and size
- Pressure gradients
- Wind
- Cloud water
- Moisture
- Condensation
- Precipitation amounts

A finer visual representation determines:

- Detailed cloud appearance
- Fine boundary variation
- Local cloud clustering
- Visually coherent rain placement

Use interpolation and physically driven procedural refinement.

The rendered cloud structures should move consistently with the underlying wind.

Subgrid precipitation distribution must preserve precipitation integrated over the parent atmospheric cell.

The aggregate water delivered to hydrology must match the amount produced by the weather model.

### Scale matters

Coarse atmospheric cells may fail to resolve narrow mountains, coastlines, and valleys.

Investigate conservative atmosphere-to-terrain exchange and subgrid terrain parameterizations.

Do not destroy physically important rain shadows simply to lower resolution.

A multiresolution design should avoid coupling every atmospheric integration timestep to the full terrain grid.

---

# 7. Cloud Simulation and Rendering

Clouds are among the highest-value visual features in Terra.

Aim for rich, dynamic, recognizable cloud structures rather than uniform white tile overlays.

## 7.1 Distinguish two major cloud regimes

### Stratiform clouds

Associated with:

- Large-scale ascent
- Atmospheric fronts
- Extensive humid air masses
- Organized cyclonic systems

Visual characteristics:

- Broad continuous formations
- Layered cloud shields
- Smooth frontal bands
- Comma-shaped cyclone structures
- Gradual cloud boundaries

### Convective clouds

Associated with:

- Warm moist air
- Atmospheric instability
- Buoyant lifting
- Localized strong ascent

Visual characteristics:

- Fragmented cloud groups
- Distinct cloud clusters
- Growing and dissipating cells
- Localized rain showers
- Organized thunderstorm clusters where conditions support them

These regimes may be represented through simple physical cloud reservoirs or a shared cloud-water field with a separate convective activity diagnostic.

Do not create an elaborate microphysics model prematurely.

## 7.2 Procedural cloud detail

Investigate a lightweight, deterministic, advected cloud visualization system using:

- Coherent multiscale noise
- Wind-driven texture advection
- Smooth interpolation
- Cloud-water-dependent opacity
- Cloud-regime-dependent shapes
- Temporal coherence
- Fine-scale organization inside physical cloud regions

Avoid visibly repetitive procedural textures.

Avoid cloud patterns that boil randomly instead of moving coherently.

Distinguish broad cloud development from small visual detail.

Do not regenerate unrelated noise patterns every frame.

## 7.3 Preserve physical ownership

Atmospheric physics owns the actual water budget.

Rendering owns only visual representation.

The visualization must never change:

- Atmospheric moisture
- Cloud liquid water
- Rainfall totals
- Energy
- Pressure
- Hydrological input

If fine-scale rain patterns are introduced, use a conservative disaggregation procedure.

This should preserve the coarse-cell integrated rainfall exactly or within a documented numerical tolerance.

---

# 8. Storms and Pressure Systems

This is the most important physics objective.

The model should produce evolving pressure systems because of atmospheric dynamics.

## 8.1 Large-scale storms

The desired emergent behaviors include:

- Pressure disturbances forming and intensifying
- Wind circulating around lows and highs
- Meandering jets and unstable boundaries
- Vortex stretching, deformation and interaction
- Moving storms driven by surrounding circulation
- Cyclone growth and decay
- Cloud and rainfall organization

Prioritize coherent synoptic-scale storms over attempting to resolve tiny atmospheric features.

## 8.2 Midlatitude storms

Baroclinic instability should be investigated as a source of emergent cyclones.

A suitable multilayer model should permit disturbances to gain energy from large-scale stratification and thermal gradients.

The resulting storms should be associated with dynamically understandable wind and thermal patterns.

Do not script weather fronts or comma-shaped clouds.

They should arise from modeled flow and moisture processes where possible.

## 8.3 Tropical storms

Tropical cyclones involve processes that a simple shallow-water model may not fully represent.

In particular:

- Moist convection
- Latent-heat feedback
- Rotational organization
- Surface energy exchange
- Atmospheric stratification
- Coupling with warm ocean surfaces

A two-layer moist model may produce interesting hurricane-like vortices, but this is not guaranteed.

Do not claim realistic hurricane dynamics without appropriate evidence.

First achieve coherent large-scale rotating systems and credible convective organization.

Then determine whether additional physics is worth its cost.

## 8.4 Small-scale thunderstorms

Individual thunderstorms are much smaller than the likely atmospheric grid resolution.

Represent these through inexpensive physical parameterizations or conservative subgrid processes.

They should depend on:

- Available moisture
- Thermal instability
- Local forcing
- Convergence and lifting
- Convective development state

They should not be randomly placed independent of atmospheric conditions.

Deterministic stochastic parameterizations may be acceptable when they represent unresolved physical variability and preserve relevant budgets.

---

# 9. Temperature, Radiation and Energy

The current temperature model uses relaxation toward latitude-dependent targets.

That is useful for a prototype but places a ceiling on emergence.

Gradually introduce a simplified, physically consistent thermal system.

Required long-term interactions include:

- Solar radiation
- Latitude and solar geometry
- Axial tilt and seasons
- Surface absorption and albedo
- Infrared cooling
- Land and ocean heat capacity
- Sensible heat exchange
- Evaporation and latent heat
- Condensation and latent-heat release
- Atmospheric advection of heat
- Atmospheric stability
- Land–ocean temperature contrasts

Prioritize the interactions that materially influence pressure, circulation, clouds and precipitation.

A simplified energy balance is preferable to a complex radiative transfer model.

Ocean temperature may initially be represented by a mixed-layer heat-capacity model without explicit ocean circulation.

Do not attempt to simulate detailed ocean dynamics unless climate behavior demonstrates a substantial need for them.

### Essential physical consistency

Temperature must affect atmospheric dynamics, not merely cloud thresholds and map colors.

Atmospheric circulation must transport thermal properties.

Latent heating should influence dynamics where the selected model supports it.

The coupling must be consistent with the chosen shallow-water or primitive-equation formulation.

Do not attach an unrelated temperature heuristic to the dynamical equations.

---

# 10. Moisture, Condensation, Evaporation and Precipitation

Retain the existing principle of explicitly accounting for water.

The current model already distinguishes:

- Atmospheric vapor
- Cloud water
- Soil moisture
- Precipitation
- Drainage and overflow
- Lake water and evaporation

This is valuable.

However, the physics needs improvement.

## 10.1 Correct existing problems

The current weather implementation can condense lifted vapor at an effectively cooler temperature and then evaporate the resulting cloud water against the original warmer saturation state.

This may immediately cancel physically motivated cloud formation.

Investigate and correct the thermodynamic inconsistency.

Also correct the current evaporation limitations:

- No evaporation at night
- No explicit vapor-pressure-deficit dependence
- No wind-speed influence
- Simplistic temperature dependence
- Inconsistent cloud shading
- Potentially insufficient sustained global evaporation

Do not simply increase evaporation constants until the map looks wetter.

## 10.2 Advect water consistently with air mass

In a dynamical pressure model, air mass can converge and diverge.

The existing divergence-free velocity assumption cannot remain authoritative.

Vapor and cloud species should be represented as physically consistent tracers of atmospheric mass.

A useful formulation transports layer mass times mixing ratio rather than assuming fixed mass per grid cell.

Maintain:

- Conservative transport
- Positivity
- Consistent units
- Explicit water-budget diagnostics
- No artificial moisture sources
- Correct exchange with hydrology

## 10.3 Physically motivated rainfall

Precipitation should emerge from cloud development, condensation, and atmospheric conditions.

The system should distinguish broad frontal precipitation from localized convective rain when justified.

Avoid fixed rain quotas, forced precipitation belts, and arbitrary storm-related rain injection.

Clouds need not always precipitate.

Rainfall should have plausible durations, intensities, intermittency and geographical distributions.

---

# 11. Planetary Geometry and Geography

Terra's current map is rectangular and wraps horizontally for atmospheric transport.

Before building a rotating-fluid solver, define the planet's physical geometry explicitly.

Required concepts:

- Planetary radius
- Rotation rate
- Gravitational acceleration
- Signed latitude
- Longitude
- Physical cell area
- Physical distances
- Coriolis parameter
- Elevation in meaningful physical units
- Surface and atmosphere grid mapping
- Longitude wrapping
- Polar boundary treatment

The present latitude treatment uses absolute latitude for several weather calculations and is not sufficient for signed hemispheric Coriolis behavior.

The visible map may remain rectangular.

Do not redesign the entire game world to accommodate a complicated scientific grid unless necessary.

Investigate practical polar treatments and their effects on numerical stability.

A latitude–longitude grid becomes increasingly restrictive near the poles because the horizontal spacing shrinks.

Options include bounded/capped latitude, reduced grid resolution near the poles, or a different atmospheric grid with remapping.

Favor the least complex scientifically defensible approach.

Hydrology's present map-edge behavior is also not identical to atmospheric longitude wrapping. Resolve this explicitly instead of assuming the two geometries already agree.

---

# 12. Performance: A Hard Design Constraint

Performance is not an optimization phase that happens at the end.

It is one of the primary criteria for choosing the atmospheric model.

Terra should remain responsive and support accelerated time simulation.

Current simulation-speed presets reach one simulated month per real second.

That setting is a desirable product target, not a guarantee that any new physical model can achieve it.

### The timestep problem

For illustration, a model requiring a 10-minute numerical timestep needs:

- 144 steps per simulated day
- 4,320 steps per simulated 30-day month

To simulate one month per real second, the solver would need approximately 4,320 physical steps each real second.

For a two-layer 128 × 96 grid, this corresponds to about 106 million layer-cell updates per second, before accounting for the amount of work performed in each update.

That is not a runtime benchmark.

Actual performance depends heavily on the numerical method, timestep constraints and physical parameterizations.

The implementation must measure these properties rather than guess.

## 12.1 Performance principles

1. Keep the atmospheric simulation independent of KorGE rendering.
2. Use flat primitive arrays and structure-of-arrays storage where appropriate.
3. Reuse temporary buffers.
4. Avoid per-cell allocations and boxing.
5. Precompute static geography and geometric coefficients.
6. Separate fast atmospheric dynamics from slower physical processes where numerically justified.
7. Update visual overlays less frequently than physical integration.
8. Consider a dedicated simulation worker on JVM where appropriate.
9. Preserve deterministic ordering and reproducibility.
10. Benchmark realistic long-duration runs rather than only individual functions.

## 12.2 Numerical integration

Investigate:

- Conservative finite-volume methods
- Staggered Arakawa C-grid arrangements
- Stable momentum and mass advection
- Positivity-preserving tracer transport
- Explicit timestep constraints
- Semi-implicit time integration
- Split-explicit integration
- Appropriate conservative or otherwise budget-controlled semi-Lagrangian approaches
- Energy and potential-enstrophy behavior
- Numerical diffusion
- Balanced initialization

Choose the least complicated method that meets stability, accuracy and throughput requirements.

Do not implement a sophisticated implicit solver without a demonstrated reason.

Equally, do not select a cheap explicit solver if gravity-wave timestep restrictions make accelerated simulation impractical.

## 12.3 Performance benchmarks

Measure:

- Actual simulated days per real second
- Time spent in atmospheric dynamics
- Time spent in cloud/moisture physics
- Time spent in surface/hydrology coupling
- Rendering cost
- Allocation rate
- Memory usage
- Stability over extended runs
- Numerical error under timestep changes

Test several atmospheric resolutions and timestep strategies.

Report performance on explicitly identified hardware.

Never infer that a model is fast enough merely because its grid is small.

## 12.4 Exact versus approximate acceleration

Preserve exact simulation advancement wherever practical.

If one-month-per-second full-physics simulation is infeasible, investigate an explicitly labeled accelerated climate mode.

Such a mode may use approximations or averaged fast processes.

However:

- Do not silently skip physical timesteps.
- Do not present approximate storm trajectories as exact.
- Do not invent conserved quantities.
- Do not reset climates artificially.
- Distinguish long-term climatological accuracy from individual weather-sequence accuracy.

An approximate climate-fast-forward mode should be optional and independently validated.

---

# 13. Preserve the Existing Game Architecture

Do not rewrite unrelated systems.

Preserve where practical:

- World generation
- Physical terrain and biome classifications
- Hydrology and lake systems
- KorGE rendering
- User input and camera controls
- Weather overlays
- Simulation-speed controls
- Existing public interfaces
- Deterministic world seeds

The weather architecture should evolve toward a clean separation:

    PlanetGeometry / OrbitalForcing
                    |
            AtmosphericDynamics
                    |
          Thermodynamics / Moisture
                    |
          SurfaceExchange / Hydrology
                    |
             ClimateStatistics
                    |
          Game-facing Weather API
                    |
            2D Weather Renderer

This is a conceptual boundary diagram, not a request to create one class or directory for every box.

Avoid premature abstraction.

### Suggested migration strategy

Keep the current `WeatherSimulation` API as an adapter during development.

The old simulation should remain available as a fallback until the new one passes scientific and performance gates.

New physical implementations can initially run in a standalone headless benchmark.

When ready, expose the fields needed by existing gameplay:

- Temperature
- Relative humidity
- Wind
- Cloud coverage
- Precipitation
- Soil-water exchange
- Rainfall accumulation
- Climate statistics

Add pressure, vertical-motion proxies, atmospheric stability and cloud-regime diagnostics where genuinely useful.

Avoid making game rendering depend directly on solver internals.

---

# 14. Implementation Roadmap

The implementation must be incremental.

Each phase should produce measurable scientific or visual value.

Do not undertake all phases automatically.

## Phase 0 — Establish the Baseline

Before modifying the atmosphere:

1. Inspect the latest repository.
2. Identify all active weather, hydrology and climate interfaces.
3. Review the outstanding work in `PLAN.md` and `HYDRO_UPDATE.md`.
4. Measure current performance at every simulation-speed setting.
5. Implement or propose headless diagnostics for conservation and climate behavior.
6. Identify current cloud condensation and evaporation problems.
7. Define the minimum scientific correctness requirements for any replacement.

**Deliverable:** Current-system performance and correctness baseline.

**Acceptance criterion:** We can objectively establish whether a future implementation is better.

Existing task-specific restrictions on tests and benchmarking should not silently carry over into this scientific development program. Resolve the instruction conflict explicitly before implementation.

## Phase 1 — Experimental Atmospheric Dynamics

Implement a minimal dry, single-layer rotating shallow-water solver independently of the game.

Initially focus on a controlled idealized geometry and standard dynamical-core experiments.

Investigate:

- Stable resting atmosphere
- Pressure-gradient acceleration
- Geostrophic balance
- Coriolis direction
- Conserved mass
- Gravity waves
- Vortex motion
- Unstable jets
- Long-duration numerical stability

Compare relevant configurations or benchmarks in SpeedyWeather.

**Visual goal:** Naturally evolving rotating disturbances and vortices.

**Acceptance criterion:** Convincing dynamics at acceptable measured cost.

If this model already produces sufficient atmospheric richness, do not automatically increase complexity.

## Phase 2 — A High-Quality 2D Cloud Visualization

Develop a physically driven cloud-rendering experiment.

Use the simulated or existing atmospheric moisture/cloud fields.

Investigate:

- Smooth moving cloud systems
- Procedural subgrid detail
- Convective versus stratiform appearance
- Cloud opacity and density
- Wind-consistent advection
- Temporal coherence
- Rendering performance

This phase may proceed before the new dynamical core is fully integrated.

**Visual goal:** Beautiful moving clouds rather than uniform white weather tiles.

**Acceptance criterion:** Major visual improvement with little additional physical computation and no changes to the water budget.

## Phase 3 — Moisture and Atmospheric Coupling

Add moisture to the experimental atmospheric solver.

Integrate:

- Conservative atmospheric vapor transport
- Evaporation
- Condensation
- Cloud water
- Rainfall
- A simplified physically consistent latent-heat coupling
- Relevant surface moisture exchange

Avoid introducing many microphysical variables before they are justified.

**Visual goal:** Rain-bearing weather systems and localized cloud development.

**Acceptance criterion:** Physically meaningful cloud and rain distributions that move and evolve with the atmosphere while preserving water budgets.

## Phase 4 — Evaluate Two-Layer Dynamics

Extend the experimental solver to a second dynamically interacting layer.

Investigate:

- Vertical shear
- Layer coupling
- Thermal stratification
- Baroclinic instability
- Cyclone formation
- Frontal organization
- Moisture-related intensification

Compare against the single-layer version.

**Visual goal:** More coherent, organized, and varied storm structures.

**Acceptance criterion:** The additional layer produces clearly better emergent weather at an acceptable measured complexity and performance cost.

**Go/no-go decision:** If the second layer does not produce sufficient benefit, retain the simpler model.

If two-layer shallow water proves unsuitable, reconsider a small hydrostatic primitive-equation model or an external atmospheric engine.

## Phase 5 — Geography and Surface Feedbacks

Couple atmospheric dynamics with Terra's actual world.

Integrate:

- Real terrain elevations
- Orographic forcing
- Ocean and land thermal differences
- Surface friction
- Soil moisture
- Existing lakes
- Evaporation and sensible heat
- Existing hydrology

Use conservative remapping when atmosphere and surface resolutions differ.

**Visual goal:** Clouds and storms respond meaningfully to terrain, oceans and continents.

**Acceptance criterion:** Rain shadows, coastal contrasts and other geographical effects arise causally.

## Phase 6 — Seasonal Climate

Introduce:

- Axial tilt
- Seasonal solar forcing
- Seasonal thermal changes
- Changing land/ocean contrasts
- Seasonal shifts in atmospheric circulation
- Long-term climate sampling
- Climate-based biome classification

Add vegetation/albedo/roughness feedback only when justified.

**Visual goal:** The planet changes meaningfully throughout the year.

**Acceptance criterion:** Stable, recognizable annual climate cycles and believable long-term wet/dry regions.

## Phase 7 — Optimize, Calibrate, and Consolidate

At this point, select the simplest proven atmospheric implementation.

Evaluate:

- Removing unnecessary state variables
- Reducing atmospheric resolution
- Increasing stable timestep
- Reducing slow-physics update frequency
- Improving numerical kernels
- Optimizing data layout
- SIMD or parallel execution only where useful
- Removing obsolete heuristic weather code

Validate multi-year climate behavior.

**Final acceptance criterion:** A stable, visually compelling, emergent climate simulator that remains performant in the actual game.

---

# 15. Scientific Validation

A visually impressive simulation is not automatically physically credible.

Likewise, a physically sophisticated model is not automatically visually interesting.

Measure both.

## Physical validation

Investigate:

- Mass conservation
- Water conservation
- Energy behavior
- Stable balanced flow
- Expected Coriolis effects
- Vortex formation and propagation
- Gravity-wave behavior
- Layer coupling
- Moisture-dependent circulation
- Atmospheric equilibrium
- Long-term numerical stability

Use recognized idealized benchmarks where applicable.

## Climate validation

Compare relevant emergent distributions with Earth observations.

Useful quantities include:

- Global area-weighted precipitation
- Evaporation and precipitation balance
- Latitude-dependent precipitation
- Land versus ocean rainfall
- Wet and dry regions
- Storm frequency and lifespan
- Storm size and intensity
- Rainfall intermittency
- Dry-spell duration
- Cloud coverage
- Seasonal variability
- Mean temperature
- Prevailing wind patterns
- Pressure-system statistics

Reference datasets may include ERA5, GPCP, and IPCC climate assessments.

Terra's generated geography will differ from Earth's.

Therefore, prioritize physically meaningful statistical comparisons rather than attempting exact geographical matches.

## Visual validation

Evaluate whether storms and clouds:

- Form naturally
- Exhibit coherent movement
- Develop recognizable structures
- Merge or interact plausibly
- Intensify and weaken
- Display varied scales
- Avoid unnatural fixed patterns
- Avoid visible grid artifacts
- Maintain temporal coherence
- Produce a compelling satellite-like appearance

A model can pass scientific numerical tests and still look dull.

Visual emergence is an explicit product requirement.

---

# 16. Explicit Non-Goals

Do not implement the following unless a compelling, measured need emerges:

- Full three-dimensional Navier–Stokes simulation
- Nonhydrostatic cloud-resolving dynamics
- Individual cloud-droplet tracking
- Detailed aerosol chemistry
- Full cloud ice microphysics
- Explicit turbulent eddies
- High-resolution ocean fluid simulation
- Kilometer-scale atmospheric meshes
- Operational weather-forecasting infrastructure
- Complex GPU infrastructure before CPU profiling
- A large generic numerical-physics framework
- Atmospheric state variables with no demonstrated value

Avoid complexity for its own sake.

Where unresolved physical processes matter, prefer inexpensive, well-understood parameterizations.

Where fine visual details matter, prefer physically constrained rendering.

Where neither meaningfully improves the game, do nothing.

---

# 17. Research References

Important model references:

### SpeedyWeather
https://github.com/SpeedyWeather/SpeedyWeather.jl

A useful modern implementation and benchmark candidate for several atmospheric model hierarchies.

### Isca
https://github.com/ExeClim/Isca

A flexible hierarchy of idealized atmospheric models and physical parameterizations.

### SPEEDY

An intermediate-complexity atmospheric general circulation model demonstrating that useful global climate behavior does not necessarily require an operational forecasting system.

### Moist Two-Layer Baroclinic Instability

"Moist versus Dry Baroclinic Instability in a Simplified Two-Layer Atmospheric Model."

https://doi.org/10.1175/JAS-D-11-0205.1

Particularly relevant for the interaction of moisture, cyclones and organized precipitation in a compact atmospheric model.

### Rotating Shallow-Water Benchmarks

Williamson et al., standard shallow-water test cases.

https://doi.org/10.1016/S0021-9991(05)80016-6

### Held–Suarez Dry Atmospheric Benchmark

A standard reference for evaluating idealized atmospheric dynamical cores.

### ECMWF / OpenIFS

Reference for atmospheric numerics, hydrostatic primitive equations, and semi-implicit or semi-Lagrangian numerical techniques.

### Climate observations

ERA5:

https://climate.copernicus.eu/climate-reanalysis

IPCC AR6:

https://www.ipcc.ch/report/ar6/wg1/

Use these sources to guide investigation, not as instructions to reproduce their entire model architectures.

Verify the latest model capabilities, documentation, benchmark settings and licenses before adopting any external tool.

---

# 18. Instructions to the Autonomous Coding Agent

You are responsible for critically investigating and progressively realizing this vision.

Do not interpret this document as a rigid implementation specification.

You are expected to challenge its conclusions.

## First task

Before modifying the code:

1. Reinspect the complete current Terra repository.
2. Understand the existing weather and hydrology implementations.
3. Identify any work already completed.
4. Research the candidate atmospheric models and relevant numerical methods.
5. Investigate SpeedyWeather as an implementation or benchmark alternative.
6. Estimate computational requirements for the candidate resolutions.
7. Identify the smallest experimental implementation capable of testing the most important architectural assumptions.
8. Produce an evidence-based architecture recommendation.

Specifically assess whether two-layer moist rotating shallow water is genuinely the best balance of:

- Visual beauty
- Physical emergence
- Storm and pressure-system behavior
- Implementation complexity
- Performance
- Numerical stability
- Integration costs
- Long-term maintainability

Do not accept the proposed model merely because this document recommends it.

## Engineering expectations

- Prefer small coherent changes.
- Preserve working game behavior.
- Maintain determinism.
- Preserve physical conservation.
- Test numerical invariants.
- Benchmark performance.
- Document scientific approximations.
- Avoid unnecessary dependencies.
- Avoid excessive abstraction.
- Do not add features with negligible visual or physical value.
- Keep the system understandable and maintainable.

A numerical model that is technically impressive but makes Terra dramatically slower or less visually interesting is not a successful improvement.

## Planning and authorization

Treat `VISION.md` as a strategic north star.

Do not immediately rewrite the entire weather system.

For the first invocation, produce a focused architecture decision and implementation plan, then stop unless separately instructed to proceed.

Each subsequent implementation phase requires an explicit decision based on the previous phase's evidence.

The preferred outcome of the first experimental phase is a small working prototype that reveals whether spontaneous pressure systems and storms can be generated economically.

---

# 19. Definition of Success

Imagine opening Terra, accelerating the simulation, and simply watching the planet.

A low-pressure system begins forming over an ocean.

Surrounding winds gradually organize around it.

A broad region of humid air moves toward the disturbance.

Clouds begin to form along an atmospheric boundary.

The system develops into a large rotating storm, drawing cloud bands around its center.

Rainfall intensifies in certain regions.

As the storm approaches a continent, the coastline and mountains reshape its clouds and rainfall.

Rain falls into rivers and lakes.

The storm weakens inland, while new atmospheric disturbances develop elsewhere.

Meanwhile, distant high-pressure regions remain relatively clear.

The entire system continually changes.

Nothing needed to explicitly spawn that storm, draw its spiral, or command it to rain.

**The atmosphere produced the behavior itself.**

And the game remained smooth and responsive throughout.

That is Terra's vision.

## Final engineering principle

When deciding whether to add another layer, equation, variable, parameterization, or dependency, ask:

**Does this create substantially more beautiful, believable, emergent behavior than the complexity and performance it costs?**

If not, don't add it.

Build the smallest world that feels astonishingly alive.
