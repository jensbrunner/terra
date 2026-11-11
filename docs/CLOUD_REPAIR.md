# Cloud appearance investigation and repair

**Latest rendering update:** [CLOUD_SMOOTHNESS.md](CLOUD_SMOOTHNESS.md) records the
subsequent 10 Hz cloud cadence, faster sampling and interrupted-fade continuity.
Earlier cadence/timing observations below are historical.

The Phase 7 numerical/performance review did not establish visual acceptance.
The reported empty startup, barely moving clouds and eventual blanket were real
problems. This repair targets satellite-style fronts and circulating systems,
as requested. It does not establish realistic long-term storm climatology.

## Causes found

1. `DynamicalWeather` began with uniform 280 K air, uniform 75% relative humidity
   and **zero cloud water**. No already-developed air masses were present. The
   first visible clouds depended on spin-up, with large areas approaching the
   same condensation regime together.
2. Its starting dynamics used the dry experiment's two strong, straight jets.
   Seeded cloud features were stretched into bands; there was little initial
   rotating circulation associated with cloud systems.
3. The renderer multiplied cloud water by an everywhere-positive noise texture.
   Increasing cloud water therefore painted every pixel; noise varied opacity
   but could not produce distinct clear gaps. Most density became a pale veil.
4. Fast climate derived representative cloud water from **accumulated rain depth**,
   capped at 0.8 mm. Making an interval longer made almost every rainy column hit
   that cap, independently of its actual average rain rate.
5. Representative texture drift used a very small visual increment and only ran
   when the completed physical clock changed. At coarser quanta, it appeared
   frozen between commits. Repeated interpolation also smoothed texture detail.

The original diagnostic reproduced an empty initial sky and an almost complete
month-interval veil. Its small 24 × 18 atmosphere could not resolve the narrower
frontal initialization subsequently explored. The final visual regression uses
the actual **96 × 72 atmosphere**, with a 96 × 72 terrain fixture to keep raster
work small. Before/after pictures are qualitative diagnostics, not a controlled
same-grid numerical benchmark.

## Changes

- `InitialWeather` starts new worlds with seeded moist/dry air masses, frontal
  arcs, cloud water and matching rotating pressure lows in both hemispheres.
  Approximate geostrophic vortices (28 m/s nominal peak individually) sit on a
  weaker balanced jet. The original dry/moist laboratory scenarios keep their
  defaults; `MoistAtmosphere` accepts an optional flow initializer for the world.
  Wider moist air surrounds cloud fronts so they do not immediately evaporate
  through dry entrainment. This is an explicit new initial condition: its water
  and energy enter the initial ledger, not an ongoing source or hidden spin-up.
- `SynopticPattern` shares seed geometry between initial lows/fronts and initial
  display filaments. The renderer then advects those filaments and billows with
  the **actual published wind in detailed mode**. It does not reapply low-pressure
  forcing or recreate storms every update. These are four seeded initial lows,
  not an independently validated population of recurring storms.
- Cloud pixels use water-dependent fractional coverage, clear gaps and optical
  depth. Curled filaments, warped broad structure and smaller billows replace the
  uniformly positive haze. Display coverage is deliberately bounded and remains
  a rendering approximation, not a calibrated cloud-fraction measurement. A
  zero-water column remains transparent; no render water is added to the model.
- Representative cloud amount uses **hourly rain rate** (a 1.5-hour illustrative
  residence proxy), rather than interval rain totals. Rainfall, hydrology and
  water transfers are unchanged by this presentation calculation.
- Fast-mode texture uses a bounded real visual clock and precomputed circulating
  display flow. The worker can publish fresh cloud pixels at its existing 5 Hz
  cadence between unchanged physical commits. Cached weather/world/water views
  are reused. Pause freezes this animation; hidden clouds do no synthesis.
  Synthetic display winds are never fed to the fluid solver or wind inspector.
- Cloud amount eases over about 0.7 real seconds at refreshes instead of jumping
  to each new interval. Existing raster crossfades remain. Texture renewal is
  weaker, and reentry avoids advecting skipped climate years as resolved wind.

Fast-mode motion remains **illustrative**, as labeled in the UI. Detailed cloud
silhouettes include subgrid display structure and are not exact satellite
retrievals. The one-layer model can still merge or dissipate fronts; the initial
pattern is not continually imposed on its numerical state.

## Focused validation

Compilation passed, plus **18 distinct directly affected checks** across focused
runs: cloud renderer (3), real cloud evolution (1), worker animation/pause (1),
fast climate (4), fidelity transitions (2), coupled terrain atmosphere (3) and
moist conservation (4). Only affected checks were repeated around actual changes
and visual findings. No full suite, grid/seed sweep or broad performance matrix.

The real evolution case takes 1,080 detailed steps (six hours), then one day and
one month of climate. It writes a six-panel preview with committed dates and a
final panel after six **visual** seconds at unchanged climate time. Inspected
panels show initial curved cloud structures, changing detailed features and
continued representative motion. Fractions below use pixel alpha <24/255 as a
clear gap; they are not meteorological cloud-cover measurements:

| Panel | Clear fraction | Mean pixel alpha /255 |
|---|---:|---:|
| Initial | 92.7% | 0.053 |
| Detailed hour 1 | 93.1% | 0.046 |
| Detailed hour 6 | 89.3% | 0.075 |
| Climate day | 61.6% | 0.240 |
| Climate month | 73.4% | 0.140 |
| Month + six visual seconds, no physical advance | 74.1% | 0.137 |

The duration regression compares mean representative cloud columns for a first
one-hour versus first one-day transaction: **0.167472 vs 0.167420 mm**, ratio
**0.999685**, rather than cloudiness multiplying with the accumulation window.
The worker regression verifies a different cloud raster while committed time and
weather time stay identical, then verifies raster identity and time stay frozen
when paused. Existing renderer checks retain clear air, deterministic restart,
immutable frames and wind-following transport through the longitude seam.

The coupled three-day terrain check and moist conservation cases pass. The
3.28-year climate/reentry check finishes with relative water residual
**1.666e-14**; initialized circulation does not change the ownership contract.

One native desktop smoke used only the two reported speed regimes, with clouds
and day/season light enabled: 0.5 s warm-up + 3 s measured each, about **seven
seconds of cases**. Default 283 × 212 surface / 96 × 72 atmosphere, same M5 Pro /
120 Hz VSync host as the Phase 7 review. The game completed and exited normally;
existing startup callback/texture warnings remain.

| Selected | Short-window observed | p95 ms | p99 ms | Cloud-copy peak ms |
|---|---:|---:|---:|---:|
| 1 hour/s | 1.048 hours/s | 9.77 | 10.64 | 0.141 |
| 12 hours/s | 13.00 hours/s | 9.69 | 10.69 | 0.115 |

Actual rates fluctuate with commits and publication windows. This is a smoke,
not a GPU-present measurement, long-run memory evaluation or 100 FPS guarantee.
Visual inspection covered generated raster previews, not manual camera/input
assessment of the running game. No aesthetic acceptance is claimed on the user's
behalf.

## Artifacts and use

- `build/reports/clouds/evolution-current.png`: final rendered trajectory preview.
- `build/reports/clouds/evolution-before.png`: original diagnostic (different
  coarse grid/time panels, retained only for qualitative context).
- `build/experiments/phase-7/cloud-repair/`: native CSV/environment, final source
  fingerprint and final focused-test XML. Build artifacts are not versioned.

Restart/regenerate a world to see the new starting weather. Existing running
worlds cannot acquire a new initial condition through a source edit. Launch with
`gradle runJvm -PweatherModel=dynamical`.

Optional focused reproduction:

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.render.CloudEvolutionTest' --tests 'terra.render.CloudRendererTest' --tests 'terra.simulation.CloudAnimationWorkerTest' --tests 'terra.atmosphere.TerrainAtmosphereTest' --tests 'terra.atmosphere.MoistAtmosphereTest' --tests 'terra.weather.FastClimateStepperTest' --tests 'terra.simulation.FidelityTransitionTest'
```


## Follow-up: flashing during raster transitions

The two-image source-over fade reduced combined opacity during every update:
for an unchanged pixel with alpha A, its midpoint alpha was A - A²/4. An opaque
cloud therefore fell to 75% opacity and returned, repeating at publication rate.
Identical-raster skipping masked the problem in static tests, but moving clouds
kept triggering it. Straight RGBA pixels were also being uploaded as if already
premultiplied.

`RasterBlendImage` now mixes both textures in a single shader, then composites
once over the map. `LayerFade.complete` converts incoming straight pixels to
premultiplied values once per publication, before texture filtering. The shared
path fixes clouds, weather overlays and water. Animation does not blend pixel
arrays on every display frame. Direct bitmap texture lookup also avoids a stale
slice-cache interaction when multiple revisions render in one frame.

`gradle compileKotlinJvm rasterBlendCheck` passed **15 actual GPU pixel samples**:
unchanged semi-transparent clouds, near-identical changed rasters and transparent
to opaque interpolation, at five transition fractions each. The tiny native check
uses the actual `LayerFade` upload/conversion path and compares rendered pixels
over a known background; it simulates no weather and is opt-in, outside default
tests/game startup. It needs the same JVM module access flags as `runJvm`.

One 2.5-second default-map smoke at 1 hour/s with clouds and daylight enabled
completed: p95 10.10 ms, p99 11.42 ms, cloud upload/conversion peak 0.215 ms.
Startup backend warnings remain. This is not a manual visual acceptance or an FPS
guarantee. No numerical suite or preset sweep was repeated. CSV/environment and
source fingerprint are in `build/experiments/phase-7/cloud-fade-fix/`.
