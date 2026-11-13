# Multiscale climate implementation

Terra keeps one authoritative world. The detailed `MoistAtmosphere` still owns its
rotating thermal shallow-water state, while fast evolution uses the same surface heat,
atmospheric water, soil, groundwater, snow, lakes and finite ocean. Fast mode no longer
relaxes temperature toward a fixed geographical prior: it integrates a reduced seasonal
energy and water system and hands the actual resulting state back to detailed weather.

The reduced atmosphere is deliberately diagnostic rather than a second weather engine.
It runs at most on a 36×27 climate mesh; every physical reservoir stays on its
authoritative fine tile or atmosphere column. Climate cells therefore own no water and
no heat of their own.

## Seasonal energy and ocean memory

`ClimateEnergyModel` carries a prognostic surface slab and a representative air
temperature:

- daily-mean solar geometry from `SolarConfig`, surface albedo and cloud shading;
- longwave exchange with a humidity/cloud-dependent atmospheric emissivity;
- bulk sensible and latent exchange, with evaporation withdrawn from the actual
  surface store and latent heat returned where condensation occurs;
- land and ocean heat capacities, with a finite slab mixed layer for the ocean;
- sea ice as a freezing-temperature phase buffer with an ice albedo;
- snow albedo, energy-limited snowmelt and a bounded cold-surface floor;
- lateral ocean heat exchange restricted to connected ocean basins, plus an evaluated
  bounded basin-mean transport closure (`OceanTransportMode.BASIN_TRANSPORT`);
- diagnostic horizontal air advection and mixing by the column circulation.

The coupled surface/air equilibrium is solved with a small Newton iteration and then
relaxed with each slab's heat capacity. This replaced the explicit ten-day air update,
which was unstable. Surface albedo and heat capacity are refreshed every few phases;
slower changes are not recomputed for every exchange.

Energy accounting is reported per interval: absorbed shortwave, outgoing longwave,
sensible, latent, snow melt, horizontal air/ocean transport, condensation heating and a
named numerical residual. It remains an approximate game-scale closure, not a
calibrated radiative budget.

## Geography-responsive circulation

`DiagnosticCirculation` diagnoses one column circulation from the evolving thermal
field, rotation, drag, terrain and a constrained pressure/mass balance:

1. smooth representative air and surface temperatures;
2. form a thermal pressure field (warm surface = relative low) plus mountain blocking;
3. compute a geostrophically balanced wind with a smooth equatorial transition;
4. project the balanced part onto a non-divergent streamfunction;
5. add a low-level thermally direct coastal/land breeze and ridge deflection;
6. diagnose convergence and ascent from surface heating, terrain uplift and buoyancy;
7. solve a velocity potential so the low-level divergent part is mass-consistent.

Column moisture transport uses only the non-divergent column wind. Low-level convergence
and the compensating return flow are represented explicitly through ascent, subsidence
and precipitation; a convergent surface wind never advects the whole water column
unexplained. Latitude enters through rotation and insolation, never as a prescribed wind
or rainfall belt. A uniform-temperature ocean has zero diagnosed wind, convergence and
ascent (`ClimateGeographyTest`).

Orographic lift uses directional fine-tile slopes (not the coarse mean elevation), so a
ridge that disappears inside one climate cell still produces upstream rainout and a
leeward supply deficit. This was validated by a flat-versus-ridge counterfactual.

## Moisture, ascent and precipitation

`ClimateMoisture` conserves column water on the reduced mesh:

- upwind finite-volume advection with a positivity-limited flux;
- bounded direction-dependent eddy diffusion set by thermal gradient and storminess;
- precipitation from one combined closure: supersaturation plus diagnosed ascent
  (large-scale convergence, orography and convection), all drawing from the local column;
- re-evaporation of falling rain in dry descending air, returning water to the same
  column;
- conservative prolongation to the fine surface, weighted toward colder/higher
  sub-columns while preserving the total.

Humidity must approach saturation before lift can rain; dry air cannot be wrung out
merely because it ascends. Terrain rainout returns its depletion to the coarse budget,
so a rain shadow cannot be painted on after the transport solve.

## Land water

`TerrainMoistSurface` owns:

- a root zone (rapid plant-accessible storage);
- a slow groundwater store with analytic baseflow recession;
- snow water equivalent with accumulation, energy- and degree-day melt, and albedo;
- surface overflow and direct lake/ocean precipitation.

The root-zone update solves the constant-rate infiltration/drainage problem exactly
between genuine wet and dry periods, including infiltration capacity, saturation,
local slope, and percolation. Baseflow is an analytic linear-reservoir release with weighted percolation
timing; it preserves river memory without a per-tile exponential inside every substep.
`CLIMATE_BUCKET` substepping was removed. Every branch is mass-derived: overflow,
percolation, baseflow and lake/ocean returns are explicit receivers.

`HydrologicalResponseUnits` groups fine tiles by D8 catchment, elevation band, cover
class and local-slope class. The units own aggregated slow stores: one linear groundwater
reservoir per unit and one snow total per unit. Baseflow is released once per unit and
injected at the unit's downstream outlet tile for routing, so one aggregated response
does not become thousands of duplicate per-tile solves. Root-zone thresholds, snowline
albedo and local saturation remain on the fine tiles; unit melt and release are
distributed back proportionally, which preserves both total mass and the snow/soil
distribution needed by thresholds.

The default world has 21,572 land tiles but only 2,079 response units. The isolated
microbenchmark reports about a 6× saving for the per-tile linear groundwater/snow-melt
updates and about a 7× saving for routing-input injection, while the full climate rate
is dominated by root-zone, transport and routing work. `responseUnitsPreserveAreaGroundwaterSnowAndWholeSurfaceMass` verifies land area,
unit totals, and whole-surface mass before and after a cold snow step and a warm melt step.

Lakes, channels and the finite ocean remain owned by `HydrologySimulation`. Climate
routing accumulates up to a monthly catchment cadence and is published as bounded
throughput diagnostics; it never credits a second water source.

## Vegetation and ecology

`World` owns fractional woody, herbaceous and bare cover plus a compact drought-stress
and seed-bank history. Growth limitation is computed from:

- growing-season warmth in degree-days above 5 °C;
- frost exposure;
- root-zone water and seasonal water deficit;
- exposed rock, cold limitation and persistent drought stress.

Cold limitation is classified before aridity, so cold wet land becomes tundra rather
than a false desert. Establishment, canopy growth, grass response and drought mortality
use separate timescales. Neighbouring seed access and a persistent seed bank allow
corridor migration without making every favorable pixel wait for a distant source.
Continuous canopy, grass and bare fractions are rendered as a vegetation overlay;
categorical terrain labels are derived with hysteresis and are only an inspection aid.

A bounded fire disturbance emerges from fuel, drought stress and a deterministic
calendar-indexed weather opportunity. It reduces woody and grass cover, leaves a scar
age and seed bank, and can produce a lasting grassland or a recovering forest. Fire
changes cover only: the soil, groundwater, snow, lake and atmospheric inventories are
not touched, so it cannot alter the water budget. Fire is deliberately low-frequency
and is an extension, not a substitute for correct baseline ecology.

## Variability, regimes and reentry

`WeatherRegimes` supplies spatially coherent wet/dry and storminess anomalies keyed to
absolute physical time. It proposes event persistence and intensity; the coupled water
budget still determines how much water exists. A larger worker request visits the same
regime sequence because all coefficients are calendar-indexed, not request-indexed.

Reentry rebuilds pressure, winds, thermal structure, vapor and cloud jointly from the
evolved surface and column water. All reservoirs remain authoritative; the handoff
reports its water and initialization energy changes. The following day is validated in
`FidelityTransitionTest`, including a visible-cloud check after twenty hours.

## Verification

Run:

```text
gradle compileKotlinJvm jvmTest
gradle climateBenchmark -PclimateBenchmarkArgs='--warmup=20 --years=10'
gradle climateAtlas -PclimateAtlasArgs='--years=100'
gradle responseUnitsBenchmark
gradle oceanTransportBenchmark
```

Key evidence:

- `CoupledContinentCounterfactualTest`: translating a continent by 16 cells in the full
  fast model makes the latitude-removed rain anomaly cross-correlate best at exactly
  that 16-cell shift (r≈0.99), changes fixed-point low-level wind, and, for a dry
  continent, reverses the summer/winter coastal wind anomaly. The bounded basin-transport
  mode also conserves water and keeps the surface thermal range finite over eight years.
- `ClimateGeographyTest`: uniform control has no injected convergence/rain belt; a
  ridge increases windward rainout and depletes downstream water; rotating a ridge
  rotates the windward rainout axis; reversing a land/ocean thermal contrast reverses
  the low-level coastal breeze.
- `CoupledContinentCounterfactualTest`: a wide continent retains a productive maritime
  interior while a narrow continent has a different interior supply.
- `SeasonalClimateTest`: cold wet land becomes tundra, not desert; mature forest thins
  under repeated drought and recovers without losing its history; fractional cover
  persists through a single dry year; dry fuel-rich land eventually burns and leaves a
  young fire scar.
- `ReducedClimateTest`: twenty-year calendar steps conserve water, produce a varied
  biome distribution, and return to detailed weather. `build/experiments/climate-century.png`
  shows terrain, annual rain, soil and canopy.
- `CoarseHydrologyTest`: analytic buckets and routing agree with short detailed steps;
  cold precipitation is stored as snow, recharges the catchment when warm and sustains
  delayed baseflow; wet-season rain keeps rivers flowing while groundwater recedes in
  the dry season; response units preserve area, groundwater/snow totals and whole-surface
  mass.
- `responseUnitsBenchmark`: 2,079 units replace 21,572 slow per-tile updates, with
  roughly 6× isolated saving for groundwater/snow updates and 7× for routing-input
  injection.
- `oceanTransportBenchmark`: compares lateral-only and bounded basin-mean transport on
  identical worlds; both remain ~110 ms/year, so the extension is not enabled by default.
- `AggregateClimateTest` / `FidelityTransitionTest`: long transactions conserve water,
  bounded memory, and reentry remains moist and resolved.

A one-thousand-year atlas run reports a maximum interval water residual below 6×10⁻¹³
and stable biome fractions, with no growing retained state. A century atlas reports
bounded low-frequency fire scars (~288 over 21,572 land tiles in the canonical run). `build/experiments/climate-benchmark.txt`
records wall time, water residual, wind, biomes and the detailed handoff. `build/experiments/climate-atlas.png` and
`climate-atlas.txt` are the compact visual review set with units.

## Measured performance and limitations

On the default 283×212 world and the development Mac, a warm-started benchmark reports
about **100–105 ms per simulated year** for sustained one-year climate transactions and
a similar cost for a single long batch. The target is about 100 ms/year; the measured
range is inside the plan's transitional 120–150 ms envelope but does not yet reach the
target. Presentation snapshots and the detailed handoff are measured separately and are
not included in that climate rate.

The structured basin-transport closure is implemented as a bounded relaxation toward
each connected basin's mean ocean temperature, with an efficiency based on basin size.
A same-forcing comparison against lateral-only mixing reports comparable cost
(~103–105 ms/year), a small global cooling and no clear reduction in mid-latitude
coastal seasonal range. It is therefore retained as an evaluated option, not made the
production default; the plan's rule is to admit an extension only when it improves the
visible climate at acceptable cost.

This remains a game-scale intermediate-complexity climate, not calibrated Earth
climatology. Circulation and energy are diagnostic closures. Snow/sea-ice mass and
phase-change energy are approximate; sea ice is a thermal/cover buffer rather than a
prognostic ice-thickness model. Deep groundwater, a dynamic ocean and species
competition are absent. Fire is a bounded cover-only disturbance, not a coupled
combustion/emissions model. Geography is static; changing coastlines would require
rebuilding world/hydrology topology and climate connectivity together.

## Research grounding

The architecture borrows principles, not a full scientific model:

- [CLIMBER-X](https://gmd.copernicus.org/articles/15/5905/2022/): reduced energy and
  moisture budgets with diagnosed circulation and parameterized transient transport can
  support long integrations. Terra still has to demonstrate its own geographically
  responsive closure.
- [LPJ-GUESS/LSM](https://gmd.copernicus.org/articles/15/6709/2022/): accessible soil
  water, surface exchange and distinct fast/slow vegetation timescales.
- [WAM2layers v3](https://gmd.copernicus.org/articles/18/4335/2025/index.html): context
  for conservative column-moisture transport and vertically consistent budget
  interpretation.
- [Virtual Rain v1.0](https://gmd.copernicus.org/articles/19/9489/2026/): stochastic
  rainfall generation and temporal disaggregation; Terra additionally constrains events
  by finite coupled inventories.

All constants and closures remain Terra-specific engineering hypotheses, not calibrated
Earth climatology.
