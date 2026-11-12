# Cloud geography and weather coupling — 12 November 2026

Follow-up: [cloud recovery after centuries of fast climate](CLOUD_REENTRY.md)
corrects atmospheric depletion by rainfall and detailed reconstruction. The
measurements below precede that change; fast rainfall and proxy trajectories now
differ. Rendering and new-world initialization remain as described here.

Implemented the agreed physics-led hybrid repair. Cloud appearance no longer
knows the initial low-pressure locations. New worlds have a planet-wide moisture
background, and fast climate can estimate humid, non-raining cloud. No equatorial
wet belt, subtropical dry strip, new water reservoir or atmospheric layer was added.

## Baseline: separate missing condensate from hidden cloud

The extended existing fixture uses a 96 × 72 terrain and the actual 96 × 72
atmosphere, one seed (31742), six detailed hours, then a climate day and month.
Equal-width latitude bands are centered at 25%, 50% and 75% of map height, each
one-sixth of the map high. Diagnostic JSON records quality, completed time,
rainfall accumulation duration, mean cloud mm, model cover, RH, funded rainfall,
soil mm, rendered opacity and visible fraction (alpha >24/255).

The detailed baseline was genuinely deficient in equatorial condensate, not just
its display. Equatorial cloud water started at 5.598e-7 mm and declined to
3.693e-14 mm after six hours, with no visible cloud in either snapshot. Baseline
fast climate already produced equatorial rainfall/cloud proxies; a permanent dry
band was not present in every mode of this fixture. The repair addresses both
initialization and rendering rather than treating the diagnosis as universal.

| Equatorial metric | Before | After |
|---|---:|---:|
| Initial mean cloud water mm | 0.000000560 | 0.117902 |
| Initial mean RH | 0.6274 | 0.9096 |
| Initial visible fraction | 0 | 0.4143 |
| Detailed hour 6 mean cloud water mm | 3.693e-14 | 0.153814 |
| Detailed hour 6 visible fraction | 0 | 0.4473 |
| Climate day visible fraction | 0.7333 | 0.9349 |
| Climate month visible fraction | 0.6211 | 0.7368 |

These are one trajectory's observations, not coverage targets or climate skill
estimates. Actual cloud water and rendered opacity are reported separately.
Rainfall is the funded amount over the reported update, **not** a cumulative
six-hour total: detailed samples use the last 20-second transaction; climate
samples use their full represented interval. Soil means include ocean tiles.

## Implementation

### New-world moisture and circulation

`InitialWeather` combines 70% broad and 30% finer longitude-periodic noise,
independent of pressure-low positions. `TerrainMoistSurface` provides read-only
initial availability from ocean fraction plus area-weighted normalized soil water.
The initialization is exactly:

```text
m = smoothstep(0.25, 0.65, 0.8 × noise + 0.2 × availability)
RH = 0.55 + 0.45 × m
cloud water = 0.25 mm × smoothstep(0.98, 1.0, m)
```

Air remains 280 K initially; existing 284–288 K land/ocean surface initialization
is retained. The new vapor/cloud distribution establishes a new time-zero
inventory. It is not reapplied during stepping, regeneration-independent updates
or climate-to-detailed reentry. Water and energy are conserved/accounted relative
to that new inventory, rather than silently adjusted to the old initial total.

`SynopticPattern` now holds only temporary initial low positions: two per
hemisphere, longitudes stratified into halves of the map, northward fractions
sampled independently in [0.15, 0.40) and [0.60, 0.85). The existing approximate
geostrophic pressure/velocity construction remains. Four initial disturbances are
still a model choice, not a forecast of recurring cyclone populations. Their
positions no longer define humidity or rendered cloud filaments.

### Geographically neutral rendering

`CloudRenderer` has no dependency on `SynopticPattern`. Broad/fine texture is
seeded periodic correlated noise, with no latitude envelope or fixed storm mask.
Renewal is toward that neutral texture. Coverage comes from
`WeatherModel.cloudCover()`; optical depth comes from its cloud water. The old
renderer-specific competing coverage formula is removed. Local gaps and billows
remain; legitimate overcast is allowed, so the evolution fixture no longer imposes
a global minimum clear fraction or unconditional cloud-visibility quota.

Detailed texture follows published physical wind. Fast texture uses a precomputed
noise streamfunction, finite-difference circulating flow and a modest eastward
drift. The entire field is scaled to a maximum of approximately two terrain tiles
per real visual second (floating-point tolerance). Longitude wraps and polar
samples clamp. This display flow never enters the atmospheric solver or wind
inspector and remains labeled illustrative. The optimized sampler, 10 Hz target
and premultiplied GPU crossfades are retained.

### Fast humidity and non-raining cloud

A read-only atmospheric helper computes saturation capacity using actual column
mass, the existing humid-column fraction, cloud pressure and warm-cloud formula.
Each existing climate phase records effective moisture after evaporation and
after rain withdrawal, using their average. Representative air temperature is
phase-mean surface temperature minus the existing 6°C proxy offset.

```text
RH proxy = clamp(mean moisture / saturation capacity, 0, 1)
humidity cloud = min(mean moisture, 0.25 mm × smoothstep(0.80, 1.00, RH proxy))
rain cloud = min(0.6 mm, hourly funded rain × 1.5 hours)
cloud proxy = min(0.6 mm, max(humidity cloud, rain cloud))
```

Cloud and humidity estimates are phase-duration weighted. The result owns private
immutable coarse arrays; the owner and read-only presentation adapters both use
them. This replaces the old effective-water/20 humidity approximation. Scratch
storage is reused on the worker; no daily loops were added to long intervals.

These estimates do not move water, create physical condensate, release latent
heat, change rainfall or feed biome history. A rain-linked interval cloud estimate
may describe water that has already rained out; it is not a current conserved
reservoir. Existing approximate/representative UI labels and public WeatherModel
signatures are preserved. No new configuration or save migration was introduced.

## Focused acceptance and cost

**21 distinct affected checks passed across focused runs**, with renderer-only
retries around the neutrality fixture. These cover clear air, wind/seam transport,
immutable frames, animation/pause, initialization reproducibility and variable low
latitudes, bounded initial humidity, humid zero-rain cloud estimates, dry/waterless
zero-cloud estimates, accumulation-window units, frozen humidity/cloud snapshots,
convection/orography/roughness, coupled water/energy and fidelity reentry.

The existing moist-unstable, stable and dry-column convection checks pass. The
approximately 14.7°C default instability threshold and conservative phase/latent
heat closure were not retuned. The tiny 3.28-year climate/reentry fixture finishes
with relative water residual **-9.219e-15** and no hidden spin-in or reseeding.
The representative first-day/first-hour mean cloud ratio is **0.97344**, rather
than scaling cloud amount with the rain accumulation duration.

Uniform 0.2 mm cloud fields use two fixed seeds (31742, 42), at the game's native
283 × 212 map size, for both resolved and representative quality. Aggregate mean
opacity is north **0.39244**, equator **0.49486**, south **0.47399**. Equatorial
opacity is **14.23% above the average of the adjacent bands**, satisfying the
planned ±25% aggregate criterion; it also has no >25% deficit against either band.
The initial test incorrectly applied a symmetric ±25% limit to each neighbor
separately. That stricter interpretation failed due to regional texture variation,
including at native size (equator was 26.1% *above* the northern band). The final
test explicitly checks the aggregate criterion and retains individual means and
per-band deficit checks. This is a targeted regression, not an all-seed statistical
proof. No latitude normalization was added to force the test to pass.

One native desktop smoke used hour/s and month/s, clouds and seasonal lighting
on, 0.5-second warm-up plus two measured seconds each: **five seconds of cases**.
Default 283 × 212 surface / 96 × 72 atmosphere, M5 Pro host, recorded 120 Hz display
and VSync enabled.

| Requested | Mean worker publication | Publications/window | p95 frame ms | p99 frame ms |
|---|---:|---:|---:|---:|
| 1 hour/s | 7.83 ms | 19 | 10.43 | 11.29 |
| 1 month/s | 7.77 ms | 20 | 10.41 | 11.32 |

The recent ~7–8 ms publication budget and roughly 10 Hz cadence are retained.
This functional repair claims no frame-time improvement: p99 is somewhat higher
than the preceding short smoothness trial, and short windows are not controlled
benchmarks. Observed rates were 0.997 hours/s and 31.50 days/s; quantized clock
publications can transiently report more/less than requested. Main-thread cloud
upload/conversion maxima were 0.897/1.122 ms, including fade continuity work.
Startup callback/texture and existing compiler/Gradle warnings remain; the run
completed normally. No full suite, seed/grid sweep or lengthy spin-up was used.

The baseline diagnostic initially rejected legitimate resolved supersaturation;
that assertion was corrected before baseline capture. One compile exposed the
private soil-capacity constant; availability calculation now resides in its owning
surface class. An automatic permission-review timeout was resolved by submitting
the short Gradle command separately.

## Artifacts, reproduction and limits

Before: `build/experiments/cloud-belt-before/` contains latitude JSON and raw/rendered
panels. After: `build/experiments/cloud-belt-after/` contains those files plus the
native CSV/environment, source fingerprint and focused-check summary. Generated
artifacts are not versioned. Current previews are also at:

- `build/reports/clouds/evolution-current.png` — rendered cloud appearance.
- `build/reports/clouds/evolution-raw.png` — resolved condensate or representative
  cloud proxy, grayscale 1 − exp(−water/0.15 mm), with no decorative texture.
- `build/reports/clouds/latitude-diagnostics.jsonl` — band statistics and units.

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.render.CloudRendererTest' --tests 'terra.weather.InitialWeatherTest' --tests 'terra.weather.FastClimateStepperTest' --tests 'terra.render.CloudEvolutionTest' --tests 'terra.atmosphere.TerrainFeedbackTest' --tests 'terra.atmosphere.TerrainAtmosphereTest' --tests 'terra.simulation.FidelityTransitionTest' --tests 'terra.simulation.CloudAnimationWorkerTest'
gradle runJvm -PweatherModel=dynamical
```

Regenerate/restart for the new initial moisture and low positions. Rendering and
future fast-climate estimates do not require reseeding an ongoing model. Raw and
rendered previews were inspected; manual aesthetic acceptance remains separate.
This one-layer detailed solver and globally mixed fast moisture allocation are
still approximations. Natural dry regions and seed-dependent weather differences
remain permissible; removal of the artificial latitude preference is not a promise
of Earth-like circulation or universally cloudy equatorial climates.
