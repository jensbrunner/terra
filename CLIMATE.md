# Weather and climate

Terra keeps one detailed thermal shallow-water atmosphere and one small seasonal
surface-energy/moisture model. Both use the same atmosphere, surface temperature,
soil, snow, groundwater, channels, lakes, calendar and vegetation. Fast circulation
uses 48 × 36 cells; detailed dynamics use 96 × 72. There is no second fluid layer.

## Seasonal climate

`ReducedClimate` solves an effective surface slab balance:
`C dT/dt = absorbed sunlight − (100 + 2 Tsea) − LE + horizontal heat exchange`.
The net surface loss includes infrared and sensible exchange with unresolved air;
0.75 clear-sky solar transmission represents absorption above the surface.
These are qualitative simulator coefficients, not an Earth calibration.

Condensation releases energy into the unresolved free atmosphere, reported as
`convectiveHeatExportJoules`. It does not directly heat the ocean/root-zone slab.
The former implementation combined a planetary outgoing-radiation law with local
condensation heating of the surface. Reducing its excessive mixing exposed hot
convergent ocean cells: warmer water attracted rain, which warmed the water again.
The replacement consistently treats a surface budget, with no extra prognostic
thermal state. Detailed weather still puts condensation heat into its actual air
column and pays for evaporation from the surface.

The distinction between surface and atmospheric energy transfers follows
[Trenberth, Fasullo and Kiehl](https://www.cgd.ucar.edu/cas/Trenberth/trenberth.papers/TFK_bams09.pdf).
It does not imply that this reduced model reproduces their measured energy budget.

- Horizontal eddy heat transport has diffusivity 500,000 m²/s (a 1,000 km scale
  takes about 23 days before slab-inertia effects). Exact neighboring fine-cell
  exchanges conserve sensible heat, including at land/ocean contacts. The old
  coarse-cell projection and two-day subgrid homogenization are gone.
- Daily-mean sunlight, land/ocean inertia, albedo, elevation and moisture supply
  determine temperature and seasons. Snowmelt pays the latent heat of fusion.
- A weak prevailing wind represents unresolved overturning. Regional thermal
  anomalies generate rotating, frictional flow, deflected by terrain. There are
  no prescribed pressure belts or rainfall bands.
- Positive finite-volume fluxes transport atmospheric water. Parcel uplift is
  evaluated over at most six hours; a whole day of diagnosed coastal convergence
  previously stripped too much moisture before it could reach inland cells.
  Directional fine slopes retain windward/lee rainfall differences.
- Event intensity uses local humid-column capacity divided by a six-hour turnover
  time. The conserved phase rain volume determines wet duration, capped at 35%.
  This replaces the single onset-cloud rate of 0.293 mm/h. Soil infiltration is
  10 mm/h before slope reduction, rather than treating ordinary showers as almost
  entirely runoff. No rainfall-volume multiplier is used.
- Ecology retains seasonal temperature/rain and moisture exposure, drought,
  recovery, fire and seed history.

## Slow water

Drainage links and basin saddles remain immutable. `SurfaceWater` owns only three
slow stores: groundwater (60-day recession), channels (half-day residence per
reach), and finite lake volumes. Soil drainage recharges groundwater; saturation
and infiltration overflow enter channels. Rain and snowmelt use the same paths in
both atmospheric modes. Groundwater and channels start with explicitly budgeted
baseflow inventories; finite lakes start at 35% capacity.

Routing runs no more often than every six modeled hours. Constant-input reservoir
solutions handle larger climate phases without travel-time substeps. Sorted basin
hypsometry maps volume to lake level and wet area; full lakes spill downstream.
Wet lakes receive rain and supply finite evaporation. Ocean exchange remains an
open boundary. Closed-world basins never implicitly export water.

`atmosphere + soil + snow + groundwater + channels + lakes + signed ocean export`
is conserved, including rain waiting for the next routing update. Published water
maps are immutable, refreshed at a lower cadence than weather; pausing forces a
current inspection. Paths stay fixed, while discharge, width and lake coverage
change.

## Detailed weather and reentry

Reentry conserves water through smooth local moisture exchanges, rebuilds balanced
pressure/winds from evolved thermal geography and seeded synoptic lows, and uses
the same vapor/cloud closure as detailed microphysics. A six-hour closed test
confirms that subsaturated subgrid cloud is an equilibrium of this closure: it
does not evaporate solely because fidelity changed. Initialization energy is
reported. No hidden spin-up or calendar advancement occurs.

Detailed precipitation has hysteresis: rain begins at 0.5 mm condensate, stops
above a retained 0.2 mm cloud floor, and removes only available water. Both latent
heat and water transfers remain accounted for.

Unresolved vertical momentum exchange relaxes eastward flow toward narrow
midlatitude jets over ten days. This supplies shear energy without prescribing
vortices or periodically resetting storms. Its signed kinetic-energy input is
reported separately as `overturningEnergyJoules`; it is not hidden in numerical
energy residuals. Radiation/surface-exchange-disabled and periodic-plane numerical
experiments have no overturning forcing.

The layer can support barotropic disturbances but cannot resolve ordinary
baroclinic storm generation. Weak eddy motion growing from tiny perturbations is
not, by itself, evidence of a convincing self-sustaining storm climate. Long
trajectories and actual cloud/pressure maps remain necessary checks.

## Verification

- `gradle compileKotlinJvm jvmTest`
- `gradle climateAtlas -PclimateAtlasArgs='--years=100 --seed=31742 --output=build/experiments/review-31742'`
- `gradle climateBenchmark -PclimateBenchmarkArgs='--warmup=5 --years=10'`
- `gradle weatherHandoffReview -PhandoffArgs='--years=100'`
- `gradle atmosphericEvolution -PatmosphereArgs='--native --climate-years=100 --days=28'`
- `gradle atmosphericEvolution -PatmosphereArgs='--unseeded --native --days=28'`
- `gradle runJvm -PperformanceProfile=true -PprofileSpeeds=TEN_YEARS -PprofileClouds=true -PprofileSeconds=20 -PprofileWarmup=5`

The worker reports actual speed, backlog and unserved demand. It never truncates
transactions to report the requested speed. Solar geometry is evaluated once per
row, and fixed heat/recession responses are reused rather than recomputed for
every tile.

## Current measurements

All 89 JVM tests pass, including conserved pairwise heat exchange, regional
continent/rain-shadow counterfactuals, seasonal winds, equilibrium cloud survival,
groundwater recession, finite lake drying/refilling/spill, immutable publications
and century-to-detailed transitions.

On the default desktop with clouds enabled, the 20-second measured interval
completed 201 years at 10.05 years/s, 120 frame updates/s, p95 update interval
9.96 ms, and zero unserved demand. These are updater intervals on a vsynced
screen, not GPU presentation measurements. Raw output is in
`build/experiments/architecture-review-live/`.

The real automatic worker returned from a century to eight modeled hours of
detailed weather with evolving clouds (34% visible pixels), pressure RMS 1.37%,
wind RMS 20.8 tiles/day and curl RMS 2.05e-5/s. 81,589 cloud pixels changed
appreciably between the first and eighth returned hours. The same startup review
had 62% visible pixels: the world still need not return with identical cloud cover.
In the bounded detailed-rain fixture, 87.5% of cell/time samples were dry; wet
samples averaged 4.11 mm/h, with 11.1% above 2 mm/h and 7.5% above 5 mm/h.

A 28-day native-resolution uniform-ocean test, initialized with only 0.01 m/s
broadband perturbations and no pressure lows, reached 0.0316 m²/s² eddy kinetic
energy under the 30 m/s jet closure. Its maximum wind reached 21.8 m/s and energy
residual was 2.2e-14. This demonstrates energy input and weak emergent eddies,
not robust storm regeneration; that remains a limitation of this implementation.

Century review maps are in `build/experiments/review-{31742,42,2026}/` (terrain,
rain, annual temperature, soil, vegetation, and actual river/lake coverage):

| Seed | Land rain mm/year | Woodland share | Desert share | Wet lake tiles | Flowing river tiles |
| --- | ---: | ---: | ---: | ---: | ---: |
| 31742 | 423 | 17% | 48% | 290 | 1607 |
| 42 | 160 | 2% | 49% | 682 | 1521 |
| 2026 | 586 | 22% | 20% | 190 | 1824 |

All century water residuals stayed below 1.6e-14. Broad latitude gradients remain,
but moving the controlled continent moves its rainfall anomaly (correlation 1.00),
and reversing wind reverses the controlled mountain rain shadow. In the wider
continent fixture, regional coast/middle/interior rain is 488/38/34 mm/year; the
narrow-continent middle receives 293 mm/year. The controlled seasonal temperature
range is −8.6 to 35.8 °C, with reversed summer/winter coastal wind anomalies.

The native 28-day reentry trajectory (`build/experiments/reentry-100/`) ends with
0.339 mm cloud water, 19.5 m/s maximum wind, water residual −1.0e-13, and 0.132
m²/s² eddy kinetic energy (down from 43.4 at initialization). Pressure RMS falls
from 1.27% to 0.059%. Persistent jets and clouds therefore do **not** establish
self-sustaining rotating storms. Mean-flow energy input is now explicit, but
robust storm regeneration remains unresolved. The surface climate also remains a
qualitative approximation: hot continental cells and request-partition sensitivity
of local vegetation still warrant care when interpreting individual locations.
