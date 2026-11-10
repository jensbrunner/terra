# Phase 7, Step 2 — snapshot and renderer budget

The simulation worker now generates cloud and surface-water pixels. The renderer
copies completed buffers into its own bitmaps, uploads textures, crossfades cloud
frames, and applies bounded terrain changes. No Bitmap32, scene object or GPU
resource is created or mutated on the worker. Packed RGBA color values are shared
as immutable CPU data; no published pixel array is reused as a writable buffer.

## Publication and visual policy

- Detailed weather publications retain the immutable 96 × 72 atmosphere snapshot
  and copy seven authoritative fine-grid surface fields. Bilinear display queries
  retain the existing periodic longitude and latitude mapping. Sunlight geometry
  is frozen at the publication clock. Derived fields are calculated on demand.
  The prescribed model retains its existing fine-grid adapter.
- The latest-only mailbox normally publishes at 5 Hz, with immediate control,
  generation and cloud-visibility acknowledgements. Unchanged weather views are
  reused. Visible clouds are synthesized for new completed weather views; hidden
  clouds do no synthesis. Pause and enabling clouds while paused are supported.
- Hydrology copies and water rasterization have their own 0.5-second minimum
  refresh interval and run only when the routed revision changes. A new generation
  receives an initial water view immediately. The publication carries the clock
  at which its hydrology view was last checked; this view may intentionally lag
  weather. A pause still allows the final deferred hydrology update to publish.
  All physical transfers continue every transaction, independently of this limit.
- Climate observation retains its existing hourly numerical schedule and immutable
  statistics. Biome snapshots change only on biome revisions. Cumulative dirty
  indices survive dropped intermediate publications. Terrain changes are queued
  without duplicate entries and applied at most 1,024 tiles per render frame;
  pending entries always read the newest complete world snapshot. A large visual
  terrain change can take several frames. Initial map construction still fills
  the whole map once.
- Cloud display crossfades completed buffers over 120 ms, using two render-thread
  bitmaps. This affects appearance only. Wind texture advection follows completed
  simulation time, including skipped or hidden-overlay intervals.
- Other visible overlays refresh only for a new weather view, at most one raster
  per frame. Round-robin scheduling prevents starvation with multiple overlays.
  Visibility, camera/input and the cloud crossfade remain responsive every frame.

At the default 283 × 212 terrain size, fine weather payload falls from 21 double
arrays (~10.08 MB) to seven (~3.36 MB). The retained ten coarse double arrays add
~0.55 MB, already copied by the model snapshot. These are primitive-payload
estimates, not measured heap/GC totals. Cloud pixels add ~0.96 MB per generated
frame; a changed water raster adds ~2.16 MB. Publication remains latest-only,
without an accumulating snapshot history.

## Focused checks and one short desktop pass

`gradle compileKotlinJvm jvmTest` passed all 37 checks. Existing worker coverage now
compares all 21 display fields exactly against the direct detailed model through
one tiny-grid simulated day. It checks independent hydrology refresh/reuse,
unchanged-view reuse, and paused cloud visibility changes. Cloud checks retain
wind/seam/rewind behavior and verify old pixel buffers stay frozen. Two small
fixtures check dirty indices across a skipped biome publication and a sudden
seam-spanning lake change with immutable completed pixels. There was no parameter
sweep or production-grid multi-year run.

One desktop pass repeated the same six presets with clouds off/on: 0.5-second
warm-up and two-second measurement per case, ~30 seconds total, on the same
M5 Pro / 24 GiB / 120 Hz VSync configuration. Tests completed around application
startup; these are short exploratory samples, not isolated benchmark trials.
The following rows have clouds enabled:

| Requested | p50 ms | p95 ms | p99 ms | Achieved hours/s |
|---|---:|---:|---:|---:|
| Realtime | 8.29 | 9.91 | 10.36 | No completed 20-second step in this short window |
| 1 hour/s | 8.31 | 10.01 | 10.38 | 0.97 |
| 12 hours/s | 8.31 | 10.12 | 10.43 | 1.87 |
| 1 day/s | 8.32 | 9.97 | 10.21 | 1.63 |
| 1 week/s | 8.31 | 9.74 | 10.17 | 1.63 |
| 1 month/s | 8.32 | 10.06 | 10.17 | 1.59 |

Month/s cloud-on p99 fell from Step 1's 20.82 ms to 10.17 ms; p95 stayed near
10 ms. The render-thread cloud buffer copy peaked at 0.117 ms across measured
cases, compared with the previous cloud synthesis peak of 16.56 ms. The observed
water copy peak was 0.046 ms, with few water revisions in these short windows.
GPU texture transfer and presentation time are not isolated by those CPU timers.
The desktop backend emitted startup callback/texture warnings; the run completed.

Cloud computation still costs worker time. Month/s cloud-on worker publication
(including synthesis) took 166 ms over the two-second window, and achieved speed
was 1.59 hours/s versus Step 1's 1.79 hours/s. With clouds off, the corresponding
publication total was 3.50 ms and achieved speed 1.79 hours/s. These observations
support reduced renderer stalls, not an increase in detailed simulation speed.
JIT/order, thermal conditions and 5 Hz progress quantization limit comparisons.
No guaranteed 100 FPS, GPU-time result, manual pan/zoom latency measurement or
mature full-size biome stress result is claimed. Abrupt map updates are covered
by small correctness fixtures and bounded work, rather than an expensive run.

Artifacts: `build/experiments/phase-7/renderer-step-2/` contains the CSV,
environment, source fingerprint and completion marker. Reproduce with a fresh
output directory:

```sh
gradle compileKotlinJvm jvmTest runJvm -PweatherModel=dynamical -PperformanceProfile=true -PprofileSeconds=2 -PprofileWarmup=0.5 -PprofileOutput=build/experiments/phase-7/renderer-step-2
```

Step 3's shared slow-state/typed flux work and the approximate climate engine
remain unimplemented.
