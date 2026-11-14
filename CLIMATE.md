# Weather and climate

Terra has one detailed atmosphere (`DynamicalWeather`, backed by `DryAtmosphere`
and `MoistAtmosphere`) and one reduced seasonal update. Both use the same soil,
snow, surface temperature, atmospheric water, calendar and vegetation.

`ReducedClimate` contains the seasonal physical closures. `FastClimateStepper`
coordinates calendar-aligned phases, local surface exchange and seasonal history.
The climate mesh is at most 48 × 36; terrain-aware rainfall deposition and surface
state retain the atmospheric and terrain grids.

- Daily-mean sunlight drives an effective slab with land/ocean thermal inertia,
  surface albedo, elevation cooling, evaporation and condensation heat.
  Outgoing radiation is the effective linear law `203 + 3 Tsea` W/m².
- Conservative neighbour heat exchange and local unresolved mixing use exact
  two-store relaxation. There is no independent prognostic climate air layer.
- A weak prevailing zonal wind represents unresolved vertical overturning.
  Horizontal thermal anomalies generate rotating, frictional flow; terrain blocks
  uphill flow. There are no prescribed pressure belts or Poisson projection.
- Positivity-preserving finite-volume face fluxes carry actual atmospheric water. CFL substeps
  cover the entire requested duration. Cooling, ascent and convergence reduce
  saturation capacity; latent warming feeds back during condensation.
- Fine directional slopes weight rain toward windward terrain. Submerged relief
  is excluded. Rainfall intensity and wet duration affect infiltration and runoff.
- Vegetation sees seasonal temperature/rain statistics and accumulated annual soil
  moisture exposure, retaining drought, recovery, seed and disturbance history.

The energy balance follows the reduced-model approach described by
[North, Cahalan and Coakley](https://eisenman.ucsd.edu/courses/2024-sioc224/files/north-1981.pdf).
Directional uplift and transport motivate the terrain closure; this is not a full
[Smith–Barstad orographic model](https://doi.org/10.1175/1520-0469(2004)061%3C1377:ALTOOP%3E2.0.CO;2).
Coefficients are qualitative world-simulator choices, not an Earth calibration.

## Surface water

Soil and snow are real local stores. Groundwater, finite ocean inventories,
channel storage and time-stepped lake/river routing have been removed. Open ocean
is a moisture boundary; surplus soil water is runoff to that boundary. A signed,
compensated exchange ledger keeps the remaining water budget auditable:
`atmosphere + soil + snow + net boundary export` is conserved.

Rivers and lakes are fixed geography generated once from drainage terrain.
Upstream catchment area determines river width; basin spill heights determine
lake footprints. Weather and vegetation never change their paths, widths or
coverage, and the immutable water raster is reused throughout the world's life.

## Returning to weather

Reentry conserves atmospheric water through smooth local exchanges that concentrate
moisture in low-pressure sectors. There are no rectangular redistribution regions.
It shares startup's seeded synoptic lows, conditioned on evolved temperatures,
with rotating gradient-balanced winds. Vapor/cloud partition uses the same humid
parcel closure as detailed microphysics; nonprecipitating cloud can remain below
the rain threshold. Initialization energy is explicitly reported. No physical
steps, soil changes or calendar advancement are hidden in handoff. Rebuilt fields
are immediately visible while paused; mode changes reset decorative cloud texture
and clear the completed climate interval's rain/animation duration.

The single thermal shallow-water layer supports moving rotating disturbances but
not resolved baroclinic instability. Initialized eddies decay; cloud persistence
and pressure/curl evolution should be judged over subsequent days, not one image.
Climate cloud fields remain interval statistics rather than resolved storms.

## Review

- `gradle compileKotlinJvm jvmTest`
- `gradle climateAtlas -PclimateAtlasArgs='--years=100 --seed=31742'`
- `gradle climateBenchmark -PclimateBenchmarkArgs='--warmup=5 --years=10'`
- `gradle weatherHandoffReview -PhandoffArgs='--years=100'`
- `gradle atmosphericEvolution -PatmosphereArgs='--climate-years=255 --days=3'`
- `gradle runJvm -PperformanceProfile=true -PprofileSpeeds=TEN_YEARS -PprofileClouds=true -PprofileSeconds=20 -PprofileWarmup=5`

The worker chooses detailed/reduced fidelity from measured costs but keeps bounded
calendar-sized climate transactions. It reports actual achieved speed, backlog
and unserved demand. Long climate transactions do not truncate elapsed demand.

## Measured review

Measurements below use the default 283 × 212 world and the finer climate mesh.
The mesh has 1,728 cells, compared with the previous 972 (78% more).

The clouds-enabled desktop trial completed 200 simulated years in 20.006 seconds
(9.997 years/s), with 120 frame updates/s, p95 update interval 11.17 ms, and zero
unserved demand. These are updater intervals on a vsynced display, not GPU
presentation measurements. Raw output is in
`build/experiments/higher-resolution-live/`. The grid aligns two by two with the
96 × 72 atmosphere, avoiding fractional overlap overhead. Larger 60 × 45 and
72 × 54 trials achieved only 9.35 and 7.90 years/s respectively.

All 84 JVM tests pass. The default-world automatic worker review advanced a
century, returned to detailed weather, and retained evolving pressure and clouds
for eight modeled hours: pressure RMS was 1.44% versus 1.64% at startup, curl RMS
1.92e-5/s versus 2.23e-5/s, and visible cloud pixels covered 20% versus 53%.
54,841 cloud pixels changed appreciably between the first and eighth returned
hours. This is active weather, with less cloud than the deliberately moist startup.
A separate 48 × 36 atmosphere trajectory retained 0.164 mm cloud, 0.668% pressure
RMS and 16.5 m/s maximum wind after three days; water residual was 1.3e-13.
The native-world 255-year transition test also checks fixed lake footprints,
static raster identity, conserved water and evolving resolved pressure.

Detailed weather still uses one thermal shallow-water layer. It can evolve seeded
rotating disturbances but cannot resolve baroclinic storm generation. Cloud cover
also depends on the evolved moisture inventory, so startup and century-old worlds
need not have identical coverage.
