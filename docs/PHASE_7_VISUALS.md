# Phase 7, Step 9 — fast presentation and clear periods

**Subsequent correction:** [CLOUD_REPAIR.md](CLOUD_REPAIR.md) supersedes the cloud
startup, opacity and animation behavior described here. Climate clouds now animate
at the existing publication cadence between physical commits, with rate-based
representative cloud amount and seeded frontal texture. Step 9 did not establish
user visual acceptance.

Climate mode now uses day-averaged seasonal light and bounded illustrative cloud
motion, with crossfades for weather and water rasters. The status and inspector
identify completed dates, represented periods and approximate fields. These
changes do not alter simulation scheduling, reservoirs, climate history or the
committed calendar.

## Seasonal light instead of astronomical strobing

The Day/night overlay becomes **Season** for representative climate fields.
`SeasonalLighting` calculates a bounded day-average from the existing solar
geometry. It has no moving longitude terminator. Short intervals use eight
seasonal samples; full years use a fixed annual average, with a separately
weighted remainder. Work does not grow with skipped years. This is a visual
shade proxy, not a replacement radiative forcing or an exact energy integral.

Detailed weather still displays the actual night mask for the committed date.
Climate-to-detailed handoff retains representative lighting until resolved steps
resume. Solar configuration is carried with the immutable world publication;
seasonal shading never updates the physics model's sunlight object.

Seasonal alpha is constant across each latitude row, so its bitmap uses direct
row fills. Detailed night shading caches its tint instead of parsing a color for
every pixel. Identical raster updates bypass crossfading so an unchanged annual
shade does not pulse from repeated alpha transitions.

## Cloud illustration and map transitions

Cloud masks still derive from the published representative climate rain/wetness
proxy. Detailed texture advection follows physical completed time as before.
Representative texture advection uses at most 0.25 real seconds of visual
increment per publication, mapped to a slow 300-second visual day and a fixed
illustrative eastward drift. A year-sized calendar jump therefore cannot renew or
advect cloud texture by a year in one repaint. This drift is labeled illustrative
and is not exposed as a physical wind or water transfer.

There is no hidden detailed atmosphere behind fast clouds. Synthesis stays on
the simulation worker, only for visible clouds and changed completed weather;
unchanged paused observations do no synthesis. At sparse extreme-rate updates,
clouds remain a slow seasonal illustration between committed fields, not an
animation claiming to resolve every intervening storm. Visual time never advances
the displayed calendar.

Weather overlays and surface water now use 180 ms render-thread alpha crossfades
between completed rasters. Extra previous-image buffers are bounded by map/layer
size; no snapshot history accumulates. Bitmap copies, comparisons and GPU mutation
remain on the renderer. Only one ordinary weather overlay is rasterized per
frame, cloud updates use complete worker pixels, and terrain changes retain the
1,024-tile-per-frame queue. Generation resets hide obsolete weather/water rasters.
These are presentation transitions over the latest committed state; selection
and the calendar are not interpolated into fictitious physical states.

## Status and inspector

The status uses two compact lines beside the terrain menu controls. It reports:

- Committed model year/day/hour/minute, selected regime, requested rate and actual
  rate in appropriate seconds/hours/days/years per second.
- Effective interval, automatic/manual mode, pause/load status and queued demand.
- Resolved, representative or aggregate field quality; illustrative cloud drift,
  day-averaged lighting and unresolved climate wind when those layers are active.

F/A retain manual/automatic fidelity controls; M remains explicit model/reset.
Clicking the status text no longer accidentally regenerates the world.

The inspector labels each data period, integrated rainfall and drainage totals,
whole-period mean rate and inferred wet-phase mean peak. It distinguishes proxy
air/humidity/cloud fields and effective atmospheric water from resolved fields.
Flow reports its averaging period, and lake/water inspection includes the checked
hydrology date because those visuals can intentionally lag weather publication.
Aggregate annual history is identified as estimated. The panel has enough height
for these lines. Initial states are labeled initial, rather than assigned an
invented 20-second rainfall period.

## Focused verification

Compilation and **seven focused checks** passed: the existing clear-air and
wind/seam/rewind cloud checks, one new visual-clock cloud check, two new seasonal
lighting checks and two existing fidelity transition checks. Reported test time
was about 1.1 seconds. A one-day and a twenty-year representative jump produce
identical cloud pixels for the same visual increment; texture changes without
changing weather time, and a repeated paused observation does no work. Lighting
changes little under a half-day phase shift, retains hemispheric seasonality,
and has the same full-year/decade mean across starting dates. Old light/cloud
frames stay frozen. Existing handoff water/calendar checks still pass.

One short desktop smoke used clouds plus the Day/night/Season overlay at 1 hour/s
and 10 years/s (0.5-second warm-up + two-second measurement each). It completed,
but found an ordinary lighting redraw peak of 9.401 ms. After the direct row-fill
optimization, **only the fast case** was repeated: its overlay redraw peak was
**0.068 ms**, cloud-copy peak **0.124 ms**, and water-copy peak **0.089 ms**. The
last case observed p50/p95/p99 frame intervals of **8.14/12.02/14.25 ms**. This is a
rendering-path smoke, not final 100 FPS acceptance or an isolated causal benchmark.
Backend startup callback/texture warnings were emitted as in earlier runs.

Same M5 Pro / 24 GiB / 120 Hz VSync environment as prior short checks. There was
no full-suite run, seed sweep, manual GUI assessment or broad performance matrix.
The row-fill optimization changed rendering only and was compiled/exercised by
the targeted desktop rerun, without repeating numerical checks. Final visual and
performance acceptance remains Step 10.

Artifacts and source fingerprints:

- `build/experiments/phase-7/visual-step-9/` — initial two-case smoke.
- `build/experiments/phase-7/visual-step-9-row-fill/` — targeted optimized fast case.

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.render.CloudRendererTest' --tests 'terra.render.SeasonalLightingTest' --tests 'terra.simulation.FidelityTransitionTest'

gradle runJvm -PweatherModel=dynamical -PperformanceProfile=true -PprofileSpeeds=TEN_YEARS -PprofileClouds=true -PprofileDayNight=true -PprofileSeconds=2 -PprofileWarmup=0.5 -PprofileOutput=build/experiments/phase-7/visual-step-9-row-fill
```

Use a fresh output directory when repeating the smoke.
