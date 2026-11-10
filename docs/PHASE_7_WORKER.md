# Phase 7, Step 1 — simulation ownership and clock

Historical Step 1 result. Step 2 replaces the full fine-grid weather copy and
render-thread cloud synthesis; see [PHASE_7_RENDERER.md](PHASE_7_RENDERER.md).

One dedicated JVM `SimulationWorker` now owns `WorldSimulation`: world generation,
detailed/prescribed weather, initialization, hydrology, climate observations, and
biome timers. `AtlasScene` never calls their numerical advancement. It owns graphics,
input and camera state and reads immutable published views. No graphics or GPU
objects are created on the worker.

## Clock, commands, and bounded work

`WorldSimulation.completedSeconds` is the authoritative calendar. It commits after
a completed weather/hydrology/ecology transaction; the detailed solver's local
clock currently agrees, but no longer drives the UI directly. A future climate
stepper will need the separate epoch/thermal-state handoff described in later steps.
Prescribed initialization retains its existing 210-day procedure on the worker.
Detailed fluid dt stays 20 seconds; weather/hydrology now hand off at each physical
step, rather than after a render-dependent batch of up to eight steps. Identical
physical/handoff schedules remain reproducible; exact equality to the previous
frame-dependent gameplay trajectory is not claimed.

Speed/pause controls and regeneration requests use coalesced atomic slots, not an
unbounded command queue. Controls apply between complete transactions; published
progress acknowledges their revision. World generations are monotonically tagged;
obsolete work cannot be applied by the scene. `close()` signals the daemon worker
without blocking the UI; scene destruction closes it. Failed transactions stop
that generation and expose an error; they are never silently retried or published
partly complete. Regeneration remains available.

Requested demand uses monotonic worker wall time, independently of render delta.
A work slice permits at most four complete transactions and starts no further
transaction after five milliseconds; a transaction may finish past that deadline.
The worker yields between slices and wakes for commands. Pending demand is capped
at `step + max(step, requestedRate * 0.25)`. Wall-clock suspension gaps accrue at
most 0.25 seconds of active demand. Excess, including trimming on speed changes,
is recorded as **unserved requested time**, not added to the physical calendar.
The UI reports requested/achieved rate, pending demand and unmet demand. This is
explicit throttling of uncommitted rate requests; no completed physical step,
reservoir transfer, or pending runoff is discarded. Pause retains physical state
and bounded pending demand; regeneration intentionally starts a new world.

## Publication and presentation

A latest-only atomic mailbox publishes at about 5 Hz, plus control/generation
acknowledgements. Terrain cells are copied only on regeneration/biome revision;
private static hydrology geography is copied once. Weather fields, discharge,
lake masks/storage and climate references form a complete immutable view. Dropping
an old visual publication does not drop physical fluxes. When the calendar has not
changed (including pause), only small progress metadata is republished.

The initial safe weather adapter copies 21 fine-grid fields. This is intentionally
simple, not the final optimal snapshot representation: a full default weather copy
contains about 10 MB of primitive data. Coarse-field publication and renderer work
remain Step 2. A stable presentation identity prevents each new snapshot from
resetting advected cloud texture. Camera/input and bitmap/GPU updates remain on the
render thread. The displayed frame counter now uses monotonic frame intervals.

## Focused validation and short desktop comparison

Compilation and the 35-test suite passed. Three added checks cover bounded/accounted
demand and pause, immutable views plus exact detailed transfers against the direct
model, and worker pause/regeneration/shutdown. The direct comparison crosses one
daily hydrology handoff on a tiny grid. Prescribed bootstrap is also exercised.
The existing cloud check now uses a replacement publication with stable identity.
Only the worker class was rerun after scheduling/cache changes and the daily-handoff
extension; no new parameter sweep or long simulation was used.

The same short desktop pass as Step 0 ran on the same M5 Pro / 24 GiB / 120 Hz
VSync configuration (0.5 s warm-up + 2 s measurement per case, six presets,
clouds off/on). Each row below uses clouds enabled:

| Requested | p50 ms | p95 ms | p99 ms | Achieved hours/s |
|---|---:|---:|---:|---:|
| Realtime | 8.30 | 9.82 | 10.10 | Quantized: no 20 s step in short window |
| 1 hour/s | 8.29 | 9.95 | 13.00 | 0.97 |
| 12 hours/s | 8.30 | 10.18 | 14.68 | 1.97 |
| 1 day/s | 8.32 | 10.27 | 18.25 | 1.77 |
| 1 week/s | 8.30 | 10.36 | 19.89 | 1.81 |
| 1 month/s | 8.32 | 10.04 | 20.82 | 1.79 |

Month/s p95 improved from the Step 0 observation of 44.30 ms to 10.04 ms. The live
probe recorded zero render-thread numerical advancement calls. Worker counters
are copied into publications; render-thread timing arrays are not shared across
threads. Full snapshot publication consumed approximately 5% of measured wall time
in these trials. Cloud CPU updates still reached 16.56 ms and p99 spikes remain;
GPU/upload time, pan/zoom interaction latency, and mature biome redraw cost were
not isolated. Short cases, JIT/order and 5 Hz progress quantization limit precision;
this is evidence for responsiveness improvement, not a guaranteed 100 FPS result.

Local artifacts: `build/experiments/phase-7/worker-step-1/` (`trials.csv`, environment,
source fingerprint, completion marker). Reproduce into a fresh directory:

```sh
gradle runJvm -PweatherModel=dynamical -PperformanceProfile=true -PprofileSeconds=2 -PprofileWarmup=0.5 -PprofileOutput=build/experiments/phase-7/worker-step-1
```

High presets are still detailed weather and cannot meet their selected rates.
No climate engine, fidelity switch, or years/s speed was introduced in this step.
