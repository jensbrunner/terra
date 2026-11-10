# Phase 7, Step 0 — short live baseline

Measured 10 November 2026 against `3443721` plus the finished Phase 6 working tree
and opt-in timing probe. This is an exploratory baseline, not final performance
acceptance. No numerical equations, simulation step sizes, or reservoirs changed.

## Reproduce

```sh
gradle runJvm -PweatherModel=dynamical -PperformanceProfile=true -PprofileSeconds=2 -PprofileWarmup=0.5 -PprofileOutput=build/experiments/phase-7/baseline
```

The game opens, measures six presets with clouds off/on, and exits its own JVM.
Do not interact during this run. Ordinary launches create no probe. One trial per
case takes about 30 seconds total. Local artifacts are `trials.csv`,
`environment.txt`, `source.txt`, and `COMPLETE.txt` in the output directory.
Only a directory with `COMPLETE.txt` represents the full pass; use a fresh output
directory for later comparisons. Recorded source SHA-256:
`6197e8a6ce53086b6fe64736ffcbada301e6faeb9733cb3d942832057e7dbe2a`.

Host: Apple M5 Pro, Mac17,9, 24 GiB memory, 15 JVM processors; macOS 27.2 arm64,
OpenJDK 27.0.1.0.3. AWT reports a 1512×982 display at 120 Hz; KorGE reports VSync
on, effective configured FPS 120 and preferred FPS 60. The window is maximized;
scene constants are 1440×960. World 283×212, atmosphere 96×72, fixed 20-second steps.
GPU model and GPU/upload timings were not independently captured.

## Observed frame intervals and completed time

Frames are monotonic updater-to-updater intervals, including terminal stalls,
not GPU-present timestamps. Numbers below are milliseconds. Achieved rate is
completed model time per actual wall time, with clouds enabled.

| Requested | Clouds on p50 | p95 | p99 | Clouds off p95 | Achieved hours/s, clouds on |
|---|---:|---:|---:|---:|---:|
| Realtime | 8.30 | 9.98 | 10.25 | 10.09 | See note |
| 1 hour/s | 8.28 | 18.05 | 18.93 | 10.02 | 1.00 |
| 12 hours/s | 22.27 | 35.98 | 42.41 | 20.52 | 1.86 |
| 1 day/s | 24.81 | 40.37 | 46.63 | 25.47 | 1.52 |
| 1 week/s | 24.41 | 42.36 | 43.88 | 25.61 | 1.51 |
| 1 month/s | 25.29 | 44.30 | 45.19 | 26.03 | 1.45 |

Realtime completed zero physical steps inside these two-second windows: a fixed
20-second step needs 20 seconds of accumulated demand at that rate. This is
quantization, not evidence that realtime cannot keep up. Unfinished demand remains
pending. Across all trials, `pendingStart + offered − completed − pendingEnd`
reconciles within 6.99e−10 simulated seconds. Offered time uses KorGE's delta;
requested wall-time demand and achieved wall-time rate remain separate quantities.

## CPU-path observations

Pooled event costs from this pass (elapsed wall time around each CPU operation):

| Operation | Calls | Mean ms | Maximum ms |
|---|---:|---:|---:|
| Detailed dynamics + moist/surface exchanges | 5,839 | 2.083 | 5.413 |
| Fine soil drainage/rain EMA | 5,839 | 0.059 | 1.218 |
| Climate observation + historical temperature integral | 5,839 | 0.024 | 0.106 |
| Atmospheric snapshot + diagnostics | 1,100 | 0.329 | 1.443 |
| Hydrology handoff/routing | 1,100 | 0.044 | 2.951 |
| Climate presentation snapshot | 32 | 0.893 | 16.848 |
| Cloud bitmap update attempts | 120 | 11.830 | 18.559 |
| Water raster refresh | 1 | 2.724 | 2.724 |

Hydrology means include inexpensive non-routing handoffs; they are not a daily
routing benchmark. Cloud counts include no-op attempts with unchanged model time.
Mature biome scans and terrain rebuilds did not occur in measured windows; their
cost is unmeasured. GPU uploads, scene rendering, VSync waits and OS stalls are
not separable from the remaining frame time. No matched observer-free control,
allocation profile, or steady-state parameter/seed sweep was run. Short warmups,
JIT and case order limit statistical comparisons; the world keeps evolving between
cases. Only unprocessed demand resets per isolated profiling case, never physical
state. Normal speed changes retain their existing backlog policy.

## Phase 6 reconciliation and next increment

`SeasonalClimate.observe(seconds, temperatureCallback, rainDepthCallback)` already
accepts intervals and splits seasonal/year boundaries, but assumes a constant
sample within each input interval; long-interval extrema/event moments need an
explicit contract. It discards one year and retains two completed observed years.
`World.advanceClimateBiomes` uses surface-proxy climate, coverage/stationarity,
uncertainty margins, and two further eligible years. Preserve its pending timers.

Soil belongs to `TerrainMoistSurface`; finite ocean, lake and pending routing water
belong to hydrology; slow surface temperature still belongs to `MoistAtmosphere`.
The Phase 6 energy ledger separately attributes numerical transport error. A world
clock, shared thermal-state handoff, snapshot ownership and coarse event ingestion
are still needed; do not create replacement reservoirs on fidelity switches.

The measurements support Step 1's single simulation owner/worker and Step 2's
separate rendering budget. Eight average integration steps alone exceed 16 ms;
cloud synthesis alone averages more than the 10 ms target. Raising the frame step
cap cannot deliver high presets. A worker is justified, but will not by itself fix
cloud stalls or supply climate fast-forward. No 100 FPS/years-per-second claim is
made. Compilation and this single live pass were the validation for Step 0; no new
physics tests or long simulation were added.
