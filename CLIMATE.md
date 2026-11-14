# Weather and climate

Terra has one detailed atmosphere (`DynamicalWeather`, backed by `DryAtmosphere`
and `MoistAtmosphere`) and one reduced seasonal update. Both use the same soil,
snow, surface temperature, atmospheric water, calendar and vegetation.

`ReducedClimate` contains the seasonal physical closures. `FastClimateStepper`
coordinates calendar-aligned phases, local surface exchange and seasonal history.
The climate mesh is at most 36 × 27; terrain-aware rainfall deposition and surface
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

`SurfaceWater` diagnoses river discharge and lake wetness from immutable drainage
terrain, recent rain, soil wetness and evaporative demand when publishing a view.
Its flows and lake footprints are illustrative, not additional water reservoirs.

## Returning to weather

Reentry preserves atmospheric water in each 4 × 4 region, concentrating some of it
into pressure-conditioned moist sectors. It reconstructs balanced pressure/winds
from the evolved temperature and partitions vapor/cloud using the same saturated
parcel closure as detailed microphysics. Initialization energy is explicitly
reported. No physical steps, soil changes or calendar advancement are hidden in
handoff; the rebuilt physical fields are immediately visible, even while paused.

The single thermal shallow-water layer supports moving rotating disturbances but
not resolved baroclinic instability. Initialized eddies decay; cloud persistence
and pressure/curl evolution should be judged over subsequent days, not one image.
Climate cloud fields remain interval statistics rather than resolved storms.

## Review

- `gradle compileKotlinJvm jvmTest`
- `gradle climateAtlas -PclimateAtlasArgs='--years=100 --seed=31742'`
- `gradle climateBenchmark -PclimateBenchmarkArgs='--warmup=5 --years=10'`
- `gradle atmosphericEvolution -PatmosphereArgs='--climate-years=255 --days=3'`
- `gradle runJvm -PperformanceProfile=true -PprofileSpeeds=TEN_YEARS -PprofileClouds=true -PprofileSeconds=20 -PprofileWarmup=5`

The worker chooses detailed/reduced fidelity from measured costs but keeps bounded
calendar-sized climate transactions. It reports actual achieved speed, backlog
and unserved demand. Long climate transactions do not truncate elapsed demand.

## Measured review (default desktop, 283 × 212 world)

The final 20-second desktop trial, after five seconds of warmup with clouds on,
completed 200 simulated years: 9.996 years/s, 120 frame updates/s, p95 frame
interval 10.34 ms, and zero unserved demand during the measured interval. The
120 Hz display was vsynced; this measures updater intervals, not GPU presents.
Raw output is under `build/experiments/simplified-sustained/`.

The matching short climate benchmark (one warmup year, two measured years)
improved from 4.18 to 9.42 years/s. The longer default century atlas ran at
13.21 years/s. After a century it had 47% woodland and 29% desert among land
biomes; moving the continent shifted its rainfall anomaly by the same longitude,
and reversing wind reversed the controlled mountain rain shadow.

After 255 climate years, a three-day detailed trajectory retained clouds,
pressure anomalies and nonzero curl with water residual around 3e-14. Eddy
kinetic energy fell from 19.64 to 1.29 m²/s²: activity survives, but sustained
storm generation remains a limitation. Extreme local orographic rain and the
choice of climate phase can still affect individual cells; monthly versus
annual requests differed by about 20 mm RMS in instantaneous soil water and
2.1 percentage points RMS in canopy after twenty years.
