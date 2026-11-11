# Phase 7, Step 10 — bounded acceptance review

**Historical measurements:** Subsequent user feedback exposed cloud appearance
defects. [CLOUD_REPAIR.md](CLOUD_REPAIR.md) records the investigation and fixes,
including changed initial weather and representative cloud rendering. Numerical
trajectories/performance below refer to the pre-repair source and do not establish
visual acceptance of the current game.

Phase 7 implementation and its bounded acceptance review are complete. The worker,
shared-water ownership, calendar continuity, fidelity transitions and evolving
coarse world have evidence below. **A strict 100 FPS target at every speed is not
met by this run. Scientific calibration and manual interaction/visual acceptance
are not established.** The highest exposed preset remains 10 years/s, observed
here at 9.48 years/s; 100 years/s is neither exposed nor claimed.

## Scope and cost

Following the request to minimize testing, this step used **eight focused checks**
(two new acceptance cases plus the existing transition, aggregate and render-budget
pairs), about **1.35 seconds of test execution**, and **one desktop pass**: eight
presets, clouds and seasonal lighting on, 0.5-second warm-up + 2-second measurement
each (about 20 seconds of live cases). No full suite, seed/grid sweep, repeated
benchmark matrix, long detailed spin-up or profiler recording was run.

The compile and focused checks passed. The later render-only cleanup was compiled
and exercised by the desktop pass. `git diff --check` passed. Gradle required the
AGENTS.md native-library sandbox workaround. Existing generated-resource,
expect/actual and Gradle deprecation warnings remain. The desktop backend again
emitted startup callback/texture warnings and exited normally.

This step adds opt-in JVM GC, heap occupancy and publication counters to the
existing desktop probe. Ordinary gameplay creates no probe. It removes the
unreachable duplicate day/night and wind branches from the generic overlay loop;
their dedicated paths remain. No solver tuning or runtime gate was added.

## Desktop observations

Same host identified in the baseline: **Apple M5 Pro / Mac17,9 / 24 GiB**. This
run records macOS 27.2 arm64, Java 27.0.1.0.3, 15 JVM processors, 1512 × 982 at
120 Hz, VSync enabled, configured FPS 120 / preferred FPS 60. Default 283 × 212
surface and 96 × 72 atmosphere. State persists between cases; no camera input.

| Selected rate | Observed rate | p50 ms | p95 ms | p99 ms | Worst ms | Worker timed share |
|---|---:|---:|---:|---:|---:|---:|
| Realtime | No 20-second transaction due in window | 8.29 | 10.01 | 10.22 | 12.18 | ~0% |
| 1 hour/s | 0.97 hours/s | 8.32 | 9.75 | 10.15 | 10.24 | 43.1% |
| 12 hours/s | 13.44 hours/s | 8.31 | 10.11 | 10.56 | 12.58 | 16.5% |
| 1 day/s | 1.00 days/s | 8.30 | 10.11 | 10.35 | 10.91 | 10.4% |
| 1 week/s | 6.97 days/s | 8.31 | 10.15 | 10.35 | 11.63 | 9.5% |
| 1 month/s | 31.37 days/s | 8.32 | 9.88 | 10.32 | 10.68 | 13.1% |
| 1 year/s | 1.00 years/s | 8.32 | 10.13 | 10.37 | 10.77 | 20.0% |
| 10 years/s | 9.48 years/s | 8.34 | 12.51 | 17.32 | 19.44 | 40.5% |

These are updater-to-updater intervals, not GPU presentation timestamps. Short
windows, interval commits, 5 Hz publications and case-boundary backlog make actual
rates fluctuate above or below the selected rate; this is not a steady-state
throughput guarantee. The near-year/second rate above is 0.996 before rounding.
Worker timed share is published advance + publication wall time divided by trial
wall time: it includes stalls, excludes some scheduling/control work and is not a
CPU utilization or guaranteed headroom measurement. Detailed one-hour mode uses
about 43% by this estimate; climate cases use about 10–41%. No case needed
load-driven quantum enlargement in this pass.

Publication count was 8–10 per roughly two-second window (about 4–5/s), consistent
with a latest-only mailbox. Across cases, measured render cloud-copy peak was
**0.124 ms**, water-copy peak **0.190 ms**, and other-overlay peak **0.115 ms**.
These timings do not isolate GPU uploads. The decade case included **103 queued
terrain-update calls**, with peak **0.225 ms**; it was not an empty static map.
Initial full terrain construction was outside the measured windows. Existing
small render-budget checks cover skipped biome publications and frozen seam-lake
rasters; they do not replace a mature full-size visual stress test.

JVM GC counters reported one collection per window through month/s, two at
year/s, and **16 collections / 25 ms aggregate collection time** at decade/s.
Heap occupancy at decade/s went from **534 MB to 694 MB** (decimal); across all
recorded endpoints it ranged approximately 256–694 MB. Endpoint occupancy is
neither allocation volume nor retained heap, and aggregate GC time is not maximum
pause duration. The higher allocation/collection pressure at the top preset is a
remaining performance concern. This short pass cannot establish a long-run memory
plateau or exclude rare pauses. No memory-leak claim is made.

A separate single full-size worker observation, clouds enabled and fast climate
already advancing, acknowledged pause in **62.49 ms**. Its queued demand remained
within its reported bound and committed time stayed fixed during a subsequent
30 ms check. The test has a generous five-second failure deadline, not a brittle
62 ms performance assertion. This is worker acknowledgement, not end-to-end mouse
input/display latency. Mode-transition checks also passed in paused and resumed
states without resetting the generation or water stores.

## Short cross-fidelity comparison

One deterministic 15 × 11 surface / 8 × 6 atmosphere fixture starts both owners
from the same initial state. Detailed mode takes 4,320 × 20-second transactions;
climate mode takes one day. No spin-up or fitted forcing is used.

| Spatial statistic at/over day 1 | Detailed | Climate |
|---|---:|---:|
| Endpoint mean surface temperature °C | 14.482 | 14.360 |
| Mean accumulated rain mm | 0.00859 | 2.98311 |
| Endpoint mean soil water mm | 9.30087 | 9.43857 |
| Sum of routed link throughput mm·tiles | 0 | 0.04314 |

Endpoint temperature RMSE is **0.424°C**. Detailed space/time-mean surface
temperature is 14.437°C; the climate endpoint is not an interval-mean substitute.
Both water residuals pass 1e-10 relative tolerance. The routed-link sum counts
water again along successive links and is not basin outlet yield.

The rainfall difference is large (roughly 347-fold): the provisional climate
prior rains immediately, while detailed weather is starting from its initial
fields. Thus this comparison supports continuity and finite conservative states,
**not statistical rainfall equivalence or validated climate skill**. A mature
seasonal comparison would require substantially more detailed integration and is
deferred under the testing-cost constraint. Existing controlled hydrology
reference checks in `PHASE_7_COARSE_HYDROLOGY.md` separately document bucket/outlet
agreement under matched forcing; they do not calibrate the atmosphere.

## Longer coarse evolution and transitions

The same small climate world then advances to **20 total years**, using 240
approximately monthly intervals after its first day: **241 transactions total**.
The maximum absolute interval relative water residual is **4.613e-15** and total
relative water drift is **-8.840e-15**. No mass correction is applied. All sampled
surface temperatures stay finite, soil stays in [0, 250] mm, and atmospheric water
is nonnegative. History contains 19 completed post-spin-up years.

**Six tiles change biome**, world biome revision becomes 1; this tiny fixture's
land cover moves from 100% plains to approximately 53% plains / 46% tundra
(summary percentages truncate). This demonstrates delayed categorical response
under seasonal evidence, not a realistic tundra prediction. The fixture has very
little land and supplies no validation of continental ecological patterns.

The existing 20-year aggregate case also passes: eight seasonal routing passes,
no invented detailed steps and relative interval water residual -1.922e-15.
Multi-year aggregate history grants no biome persistence credit. Existing
3.28-year climate-to-detailed checks retain surface/soil/history and absolute
solar date, resume 100 physical steps without spin-in, and finish with relative
water residual 1.012e-14. Reentry's initialized atmospheric energy delta stays
explicit; climate energy remains approximate.

## Acceptance boundary and remaining limits

- Demonstrated within these bounded cases: worker/render separation, bounded
  publication/backlog behavior, pause and fidelity continuity, conservative water
  ownership, finite long coarse evolution and delayed biome change.
- **100 FPS at every preset is not accepted**: p99 exceeds 10 ms throughout, with
  a 17.32 ms p99 at decade/s. Median intervals are about 8.3 ms. This is a useful
  improvement over the baseline, not an all-frame guarantee.
- The highest measured exposed rate is **9.48 years/s on this host**. The selected
  rate remains a request. Quantization, coarsening and slower hardware can reduce
  achieved speed. The 100-years/s preset stays withheld.
- Climate uses heuristic priors, global moisture mixing, approximate energy and
  inferred rain-event moments. Large quanta cannot resolve storm tracks or
  reproduce daily river peaks. Aggregate-year ecology deliberately waits for
  independent seasonal evidence and can remain visually static.
- Manual panning/zooming, aesthetic flicker assessment, GPU present latency,
  mature full-size ecological extremes, steady-state climate calibration and
  long-duration allocation behavior remain unverified. No exhaustive run is
  proposed or silently added as a startup/CI gate.

Step 10 completes the evidence review with these limits explicitly open; it does
not convert unverified targets into guarantees.

## Reproduction and artifacts

Artifacts: `build/experiments/phase-7/acceptance-step-10/` contains environment,
raw CSV, completion marker, focused-test XML copies and source fingerprint.
`build/` is generated and not versioned; this document preserves the observations.

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.simulation.Phase7AcceptanceTest' --tests 'terra.simulation.FidelityTransitionTest' --tests 'terra.weather.AggregateClimateTest' --tests 'terra.render.PresentationBudgetTest'
gradle compileKotlinJvm runJvm -PweatherModel=dynamical -PperformanceProfile=true -PprofileClouds=true -PprofileDayNight=true -PprofileSeconds=2 -PprofileWarmup=0.5 -PprofileOutput=build/experiments/phase-7/acceptance-step-10
```

Use a fresh output directory for another trial. These commands are optional
reproduction, not a requirement to rerun after every edit.
