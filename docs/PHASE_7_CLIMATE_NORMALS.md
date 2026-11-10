# Phase 7, Step 4 — provisional seasonal normals and regional anomalies

Historical Step 4 result. Step 8 extends these normals with analytic anomaly means
and explicit aggregate-history provenance; see [PHASE_7_ADAPTIVE.md](PHASE_7_ADAPTIVE.md).

`DynamicalWeather.estimateClimate(atSeconds)` now provides an immutable coarse
climate estimate immediately, using the existing atmosphere grid. Its lazy
`ClimateNormals` instance reuses the current terrain overlap mapping, land/ocean
fractions and relief. It never steps weather, advances the world calendar,
changes climate history or moves water/heat. Step 5 can consume this estimate
through the existing owner; no separate climate world is constructed.

## Provisional geography and seasons

The prior has four orbital-quarter bins for surface temperature in °C and
precipitation demand in mm/day. It uses:

- The generated terrain mean temperatures, averaged with exact existing tile/cell
  area overlaps, retaining latitude and elevation effects.
- The existing pseudo-latitude and solar tilt, with analytical daily-mean
  cosine-zenith input. No hourly/daily sunlight marching is needed.
- A seasonal temperature departure proportional to the seasonal light departure
  from its annual mean: coefficient 30 for land and 10 for ocean, with a small
  ocean phase lag of 0.04 orbital year. This is a provisional forcing normal,
  not an instantaneous reset of the actual surface thermal state.
- Four local smoothing passes on ocean fraction as a coastal moisture-source
  proxy; longitude wraps and latitude stops at the polar walls.
- The existing positive fine-relief uplift calculation and coarse lee descent,
  using a diagnosed broad prevailing east/west flow solely for the relief proxy.
  No pressure or momentum state is introduced.

Rain demand combines light, warmth, coastal source and relief. Prior temperature
is bounded to [-80, 60] °C and prior rain to [0, 20] mm/day. Those coefficients
and limits are game-scale heuristics, not calibrated Earth climate or an
assertion that the detailed model has equilibrated. They create geographic
contrasts without fixed permanent rain/desert zones. The coastal source proxy
is diagnostic geography and is not an atmospheric moisture reservoir.

Seasonal values interpolate cyclically between quarter centers. The configured
initial orbital phase determines which seasons apply at elapsed time zero.
Fine-tile display queries use bilinear interpolation, north-up atmospheric rows,
longitude wrap and clamped latitude. Conservative water transfers must still
use the terrain overlap mapping rather than these display queries.

## Blending observed history

The existing `SeasonalClimateSnapshot` supplies observed surface temperature and
precipitation. Its new seasonal rain-rate accessor divides actual depth by
represented season duration, then converts seconds to days; it does not assume
an Earth-length year. Grid dimensions and orbital-year duration must match.

The prior has zero observed weight until a post-spin-up year is complete. One
observed year contributes 40%; two or more contribute 80%. The 20% prior anchor
retains geographical structure while this idealized model remains uncalibrated.
These are explicit tuning weights, not statistical confidence intervals or proof
of stationarity. Existing ecological coverage/stability gates are unchanged, and
provisional normals are not submitted as measured observations.

Normals carry `PROVISIONAL_PRIOR` or `BLENDED_OBSERVATIONS` source metadata plus
the actual observed weight. Blended arrays are cached by immutable history
identity; a later blend never changes previously returned normals.

## Regional variation

`ClimateAnomalies` has two bounded fields, wet/dry and warm/cold, each made from
three smooth longitude-periodic regional modes. Seed-derived phases and integer
climate-month indices determine endpoints. Endpoint phases repeat on 17-, 29-
and 43-month cycles; smooth interpolation connects adjacent months. There is no
per-tile random draw, advancing RNG state, random walk or catch-up loop through
skipped months. The modes are heuristic persistent variability, not calibrated
storm statistics.

Both fields remain in [-1,1] and have zero longitudinal mean in the continuous
field. Temperature departures are `3 * (0.6 * warm - 0.4 * wet)` °C; rain demand
is multiplied by `1 + 0.65 * wet`, remaining nonnegative. Dry anomalies therefore
loosely promote warmth. These expressions describe instantaneous representative
forcing; Step 5 still needs event timing and Step 6 longer-interval runoff.

An estimate's `atSeconds` is its requested forcing instant, not a claim that the
world has completed that amount of simulation. Predicted rain is demand only.
No atmospheric moisture pool, evaporation/rain transfers, thermal evolution,
mode switching or fast clock advancement is enabled by this step.

## Focused verification

`gradle compileKotlinJvm` passed. Three new `ClimateNormalsTest` checks and the
four existing `SeasonalClimateTest` checks passed together, about 0.055 seconds
of reported test execution. The final timestamp-name clarification was followed
by compilation of production and test sources, without repeating the checks.

Small fixtures cover new-world geographic and hemispheric seasonal contrasts,
land/ocean seasonal amplitude and moisture-source contrast, finite nonnegative
rain demand, immutable estimates, periodic fine-tile mapping and unchanged
physical stores/clocks/history. A short synthetic observation calendar verifies
40%/80% weighting, season-duration units, initial orbital offset and history
grid/year validation. Anomaly checks cover seed reproducibility, query-order
independence, spatial and month-boundary continuity, wrap and bounds.

One direct estimate/anomaly query at year 1000 checks random access; it does not
integrate 1000 years or generate the intervening months. No weather spin-up,
seed sweep, full-suite rerun, desktop benchmark or performance claim was needed.

Reproduce the focused checks:

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.weather.ClimateNormalsTest' --tests 'terra.weather.SeasonalClimateTest'
```

Step 5's first conservative fast-climate interval remains next.
