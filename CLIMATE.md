# Multiscale climate implementation

Detailed weather retains the thermal, rotating shallow-water solver. Fast evolution
uses the same surface temperatures, atmospheric water, soil, lakes and finite ocean.
It advances chronological calendar steps of at most ten days, split at year boundaries.
Work now scales with elapsed years rather than grouping many winters ahead of summers.

`ClimateMoistureTransport` restricts column water conservatively to at most 48×36
climate cells. Seasonal trades/westerlies and meridional overturning advect moisture;
symmetric neighbor exchange represents unresolved eddies. Positive finite-volume
substeps obey a transport CFL bound. Supersaturation relative to lifted parcel
capacities rains out locally on a two-day timescale. Orographic lift uses local winds,
terrain slopes and a bounded cell transit time. Mountains remove water upstream;
there is no global rainfall allocation. Prolongation conserves the remaining water
and weights subcell precipitation toward colder, lifted terrain. These temporary
arrays are numerical workspace, not additional persistent reservoirs.

Surface temperature retains the geographical, seasonally illuminated equilibrium
prior and land/ocean relaxation times. PET is a bounded thermal proxy (maximum
4.5 mm/day, about 130 W/m² latent heat). Actual soil evaporation declines with soil
water, using an exponential withdrawal capped by PET. Half withdrawals surround
the rain-plus-drainage bucket integration; event wet fractions control infiltration,
overflow and dry-period drainage. Hydrology routes each chronological step and
returns river outlets to the finite ocean. Energy remains approximate and its surface
relaxation adjustment is reported; this is not a closed radiative energy budget.

Spatially correlated monthly anomalies modify temperature, lifting and rain-event
duration. Rain still comes entirely from transported column water. Modeled seasonal
years are marked as estimates, including intervals crossing annual boundaries.
Fast mode freezes any independent detailed calibration at entry and never trains
its precipitation forcing on its own rain history.

Trees and grass have persistent fractional cover. Annual temperature, precipitation
and integrated soil-water availability determine targets. Neighboring trees accelerate
establishment; growth takes decades, mortality several years, and grass responds
faster. Rendering classes use cover thresholds with hysteresis. Ecology advances at
intervening annual boundaries inside long transactions rather than applying the
final climate retrospectively to the entire requested duration.

Reentry preserves all water and surface stores. Thermal-gradient-conditioned waves
perturb the reconstructed pressure shape; depth and wind are derived together from
the actual thermal shallow-water pressure law and bounded together. Moist columns
can contain a small subgrid cloud fraction. The atmospheric energy adjustment is
reported, and there is no hidden physical spin-up.

## Verification and limitations

Run `gradle compileKotlinJvm jvmTest`. `ReducedClimateTest` compares upstream rainout,
inland transport, twenty-year partitioning, and a native-grid century with reentry.
Its ignored `build/experiments/climate-century.png` shows terrain, annual rainfall,
final soil water, and tree cover from left to right. Existing cloud/reentry tests also
export actual cloud textures under `build/reports/clouds/`.

This is a game-scale intermediate-complexity climate, not calibrated Earth climatology.
Circulation and energy are diagnostic closures; numerical moisture diffusion and
250-km climate mixing blur small rain shadows. Snow, ice, deep groundwater, ocean
heat transport, fire and species competition remain absent. Geography is static;
changing coastlines requires rebuilding the world/hydrology topology and climate
geography together. Land-cover changes already affect detailed surface drag.

The architectural inspiration is the separation of statistical atmosphere and slow
surface evolution in [CLIMBER-X](https://gmd.copernicus.org/articles/15/5905/2022/),
and distinct ecological response times in
[LPJ-GUESS/LSM](https://gmd.copernicus.org/articles/15/6709/2022/).
Neither scientific model is reproduced here.
