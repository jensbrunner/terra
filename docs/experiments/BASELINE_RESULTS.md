# Gate A — Current-model baseline

Measured 10 November 2026. This report accompanies the opt-in baseline runner;
it does not validate a new atmospheric model. Full numerical ledgers and an
observer-free performance comparison remain separate evidence requirements.

The user has explicitly authorized future coarsening at high speeds, targeting
years/s and approximately 10–100 years/s when long-term evolution matters more
than detailed weather. These measurements characterize today's detailed model;
they do not imply that future climate fast-forward must retain its timestep,
resolution, storms or exact trajectory. Climate-mode throughput and approximation
error will require separate benchmarks.

## Reproduce

Compile and run the three focused checks:

```sh
gradle compileKotlinJvm jvmTest
```

Measure the actual desktop game at all six presets. This opens a window, advances
the usual 210-day bootstrap, runs three 30-second trials per preset with 15 seconds
of warmup before each trial, writes results, and closes only its own JVM. Do not
interact with the game while sampling. Use a fresh output directory for each run.

```sh
gradle runJvm -Pbaseline=true -PbaselineOutput=build/experiments/gate-a-live
```

Normal `gradle runJvm` has no observer or automatic preset changes. Neither weather
equations nor hydrology cadence have changed. The small DesktopMain wiring is
necessary because the shared scene cannot directly access JVM management APIs.

A smaller smoke run, not acceptance evidence:

```sh
gradle runJvm -Pbaseline=true -PbaselineWarmup=1 -PbaselineSeconds=2 -PbaselineTrials=1 -PbaselineSpeeds=ONE_MONTH -PbaselineOutput=build/experiments/smoke-live
```

For headless compute capacity, with no KorGE window or visual work:

```sh
gradle baselineExperiment -PbaselineArgs='--speeds=ONE_MONTH --warmup=15 --seconds=30 --trials=3 --output=build/experiments/gate-a-headless'
```

Headless calls use fixed 50 ms batches multiplied by the preset, at maximum compute
throughput. They do not simulate live frame pacing. Omit `--speeds` to measure all
six fixed-batch compute cases. The same constructors, bootstrap and sampled biome
application are used as in gameplay. Construction, bootstrap, hashing and report
writes are outside the steady integration intervals.

Compare equal-duration coupling schedules and cumulative rain:

```sh
gradle baselineExperiment -PbaselineArgs='--mode=sensitivity --days=30 --output=build/experiments/gate-a-sensitivity'
```

Replay the exact durations recorded by a desktop run, including its warmups:

```sh
gradle baselineExperiment -PbaselineArgs='--mode=replay --schedule=build/experiments/gate-a-live/schedule.txt --output=build/experiments/gate-a-replay'
```

The replay command starts from the normal bootstrap; it is not a save/resume
feature. Its checksum covers available public fields, not private rain activation
or pending hydrology stores. `--help` documents options. All floating durations in
the schedule round-trip without deliberately changing integration steps.

For a frame-only timing comparator, repeat the live command in another directory
with `-PbaselinePhases=false`. It still observes frames and completed simulation
steps. It is **not** an observer-free control, so it cannot establish the proposed
≤2% regression threshold for the default null-observer path.

## Reference environment and interpretation

The measured host reports Apple M5 Pro / Mac17,9, 15 available processors and
24 GiB RAM, on macOS 27.2 arm64. Gradle uses Homebrew OpenJDK 27.0.1.0.3 and Gradle
9.8.2; the shell's default `java` version differs. The experiment records the JVM
actually running the game, G1 GC, a 6 GiB maximum heap, and power-source metadata.
The full desktop run began on battery power with the normal power mode.

World: seed 31742, 283 × 212 tiles, default terrain settings, cloud overlay enabled,
1440 × 960 logical viewport in the existing maximized window. No terrain controls
or input were exercised during sampling. Baseline HEAD is `41138a5`; instrumentation
is in the dirty working tree. Each environment file includes a SHA-256 over all
Kotlin sources and the build/configuration files, including untracked additions.

Frame times use monotonic wall time between updater starts. Raw files also contain
KorGE's reported delta. The last frame is included even when it overruns a trial.
Fixed recording capacity is explicit: overflow invalidates percentiles instead of
overwriting the slowest frames. Preset warmup and report I/O are outside trials.

CPU counters cover the JVM process. Allocation counters cover the calling update
thread only; they exclude other rendering, compiler and GC threads. Heap is sampled
at most once per second plus endpoints. RSS is sampled only at trial endpoints.
These memory maxima are sampled values, **not** operating-system peak memory.
Unsupported counters use −1 or NaN. No forced GC is used in measured intervals.

## Results

All six presets have three completed ≥30-second live trials. Short smoke results
are not substituted for the specified trials. The initial six-preset process exited
without automatic completion after three Realtime and two 1h/s trials. Its saved
trials are retained; remaining presets are measured in independent processes.
New live runs write COMPLETE.txt only after all requested trials finish, or
INCOMPLETE.txt when the process shuts down early.

### Live desktop

Each row reports the median achieved rate, the range of individual trial p95s,
the worst frame across all three trials and median calling-thread allocation rate.
Allocation is decimal MB/s. Realtime's requested rate is one simulated second per
wall second, or 1/86,400 simulated days/s.

| Preset | Requested days/s | Achieved days/s, median | Frame p95 range, ms | Worst frame, ms | Allocation, MB/s |
|---|---:|---:|---:|---:|---:|
| Realtime | 0.000011574 | 0.000011575 | 15.36–15.90 | 22.08 | 723.3 |
| 1h/s | 0.041667 | 0.041662 | 14.98–16.42 | 32.22 | 720.3 |
| 12h/s | 0.5 | 0.499948 | 15.12–16.21 | 24.05 | 726.0 |
| 1 day/s | 1 | 0.999928 | 17.20–17.90 | 23.06 | 709.1 |
| 1 week/s | 7 | 7.00221 | 52.95–59.61 | 85.48 | 590.5 |
| 1 month/s | 30 | 9.19782 | 336.14–348.84 | 360.07 | 92.0 |

Realtime through day/s passed the proposed p95 ≤33.3 ms and maximum ≤100 ms
targets in these trials. Week/s failed p95; month/s failed both frame targets and
achieved only about 31% of its nominal simulated-time rate. Small overshoots of a
requested rate in a short trial arise from pending time at its boundaries.

During month trials, every recorded KorGE delta was 0.1 seconds while wall frames
took about 0.33 seconds. Completed time matched the time offered by the update
loop, with zero pending time at boundaries. The small accumulator therefore
conceals unmet wall-clock demand: the simulation does not receive the full
nominal 30 days for every elapsed wall second. This phase records that existing
behavior and changes neither the frame delta nor physics stepping.

Live allocation churn is substantial even at the slower presets, while the
headless physics/hydrology allocation below is tiny. This locates the major churn
outside the standalone model, but does not identify individual allocation sites
without a profile. A lower allocation rate at month speed accompanies fewer frames
and overlay updates, not a more efficient implementation.

The selected Realtime trials come from the interrupted initial sequence. Each
other preset was rerun from a fresh default world/bootstrap in its own process;
the initial two 1h/s trials are excluded. Startup emitted existing KorGE callback
and GL texture warnings. No post-startup exception prevented these completed runs.

Raw-frame intervals reconcile with trial wall time to within 1e-12 seconds, and
`offered + pendingStart − completed − pendingEnd` is within 3.36e-8 simulated
seconds across all 18 trials. No recording buffer overflow occurred. These are
time-accounting checks, not water or energy conservation tests.

### Preserved evidence

- [All 18 live trials](baseline-2026-11-10/live-trials.csv), with source-run names.
- [Raw frame samples](baseline-2026-11-10/frame-samples.zip), including wall and
  KorGE-reported deltas for each selected trial.
- [Headless trials](baseline-2026-11-10/gate-a-headless-trials.csv) and
  [construction/bootstrap timing](baseline-2026-11-10/gate-a-headless-initialization-ONE_MONTH.txt).
- [Schedule sensitivity](baseline-2026-11-10/gate-a-sensitivity-sensitivity.csv)
  and [hydrology totals](baseline-2026-11-10/gate-a-sensitivity-hydrology.txt).
- Per-preset `environment-*.txt`, `bootstrap-*.txt` and `schedule-*.txt` in the
  [evidence directory](baseline-2026-11-10). Realtime uses source fingerprint
  `7eb85be34b8ae09b9ac88054d3a20c2533e54958f1d4bc0bd0d68f056de2d09b`;
  the dedicated runs use `19a342a610d165d81a0e81b8ec850ad3f8599fb950c149fa417984a126cdd9a8`.
  The latter adds completion/early-exit reporting; weather/hydrology equations
  are unchanged. Original working outputs remain under `build/experiments/`.

### Headless compute capacity

At the fixed month-preset batch size (129,600 simulated seconds per outer call),
the three 30-second full-size trials achieved **9.38–9.53 simulated days/s**,
median **9.41**. Weather consumed 99.79–99.80% of the measured wall interval;
hydrology consumed about 0.20%. These are compute-capacity results without
rendering, not achieved desktop speeds or a forecast for a different integrator.

Calling-thread allocation was 11,576 / 1,184 / 1,184 bytes per trial, with no GC
reported during the three intervals. Sampled heap stayed around 34.6 MB, and
endpoint RSS ranged roughly 66–99 MB. Construction costs were 41.5 ms for World,
13.9 ms for weather, 54.8 ms for topology and 1.64 ms for hydrology; the existing
210-day headless bootstrap took 20.77 seconds. Startup/JIT sensitivity remains;
these single construction timings are not a stable microbenchmark.
The allocation deltas also include the small boundary-probe overhead; they do
not establish literally zero allocations in every model path.

### Validation and captured-schedule replay

`gradle compileKotlinJvm jvmTest` passed with three tests and no failures. The
tests cover identical-schedule public-state repeatability across bootstrap and
hydrology routing, rain observation without state changes, and frame recording
that preserves terminal stalls and exposes overflow.

The completed 1h/s desktop run recorded 2,702 post-bootstrap outer calls totaling
486,367.2 simulated seconds, including warmups. Two independent full-size JVM
replays produced the same observable checksum:

`d1fb3635c249c1a8d0b3cf23f09e65c91e7bc28ef3adcf3a95d4866764e3f774`.

The [first](baseline-2026-11-10/gate-a-replay-1-replay.txt) and
[second](baseline-2026-11-10/gate-a-replay-2-replay.txt) replay records and their
environment/initialization files are preserved with the other evidence. This
verifies repeatability of the public headless state under the captured call
schedule; it does not compare a captured private live state or promise equivalent
results under a different schedule.

### Equal-duration schedule comparison

Two independent default worlds produced the identical observable bootstrap hash
`a9c4580f0bbee6c5c149a7c88cd64c755df99c7d29432f92ebc092221a96c8eb`.
The full-size headless benchmark had the same initial hash. Both sensitivity runs
then advanced exactly 30 days with three-hour internal weather steps: one handed
off hydrology after each three hours, the other after each 36-hour outer batch.

| Quantity | Three-hour outer calls | 36-hour outer calls | Mean absolute tile difference | Maximum tile difference |
|---|---:|---:|---:|---:|
| Cumulative rainfall, summed mm·tiles | 833,785.66 | 833,966.69 | 2.939 mm | 153.470 mm |
| Vapor, summed mm·tiles | 570,999.53 | 570,984.44 | 0.0431 mm | 1.931 mm |
| Cloud water, summed mm·tiles | 19,768.50 | 19,575.82 | 0.0530 mm | 1.759 mm |
| Soil water, summed mm·tiles | 2,956,358.95 | 2,958,450.70 | 0.793 mm | 63.003 mm |

Air temperature differed by a mean absolute 0.0471 °C and maximum 1.986 °C.
Final lake storage was 17,086.74 versus 17,049.30 mm·tiles. Reported cumulative
outlet export includes bootstrap and was 3,739,234.70 versus 3,738,989.58 mm·tiles.
Small differences in global totals conceal much larger local differences. This
experiment changes coupling cadence while retaining identical internal weather
step sizes; it is evidence against assuming speed-independent output from the
existing outer-call interface. It is not a water-conservation residual.

## Scientific limits and next decision

- The same-schedule public-state tests cover bootstrap, mixed outer durations,
  diagnostic observation and hydrology routing. They do not prove complete hidden
  state equality, cross-JVM determinism, or equal output under different schedules.
- The current model still condenses at a lifted effective temperature, then tests
  cloud evaporation against warmer ambient saturation in the same substep. The
  source can therefore undo the new condensate; this phase records the issue and
  changes no microphysics. See `WeatherSimulation.advanceSubstep`.
- Transport still clamps negative destinations without a complete correction
  ledger. Positive public snapshots do not demonstrate pre-clamp positivity or
  water conservation. A complete budget must also include pending runoff and lake
  rain, confirmed lake evaporation and ocean/outlet exchange.
- Air mass and physical energy are not prognostic conserved quantities in this
  model. Their budgets remain unavailable. The weather method named
  `transportAirMass` transports vapor, cloud and temperature, not evolving air mass.
- Preset labels are requested rates, not guaranteed achieved simulation speeds.
  Report elapsed wall time, completed time and KorGE-offered time separately; a
  small pending accumulator does not prove that full wall-clock demand was met.
- No matched observer-free original-game measurement is available. Enabling timing
  does not change physics calls within an identical supplied schedule, but the
  live loop itself is timing dependent. The ≤5% observer-overhead and ≤2% disabled
  regression gates must not be declared passed from source inspection.

Gate A now has an implemented runner and a measured current-system baseline.
The responsiveness and nominal-speed failures are baseline findings, not failed
new-solver changes. Instrumentation overhead and disabled-path regression remain
unmeasured against a matched observer-free control, and complete physical ledgers
remain outstanding. This report does not declare every proposed gate passed.

Recommend the separately bounded SpeedyWeather reference experiment next, while
retaining detailed weather versus coarse climate evolution as separate product
contracts. Use a later profile to decide whether render allocation or timestep/
worker changes offer the best payoff. Do not automatically start the next phase,
cloud repair, rendering optimization or worker migration.
