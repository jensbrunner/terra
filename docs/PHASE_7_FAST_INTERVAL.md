# Phase 7, Step 5 — first conservative fast-climate interval

Historical Step 5 result. Step 6 replaces its pulse soil update, cadence-based
coarse routing and final-subperiod flow view; see
[PHASE_7_COARSE_HYDROLOGY.md](PHASE_7_COARSE_HYDROLOGY.md).

`WorldSimulation.advanceClimateInterval()` now commits an explicit short climate
transaction using the same world, water stores, thermal state and seasonal
history as detailed weather. A day or week advances without another fluid step.
This is an internal owner entry point: normal gameplay still selects detailed
weather. Automatic speed selection and safe detailed re-entry remain Step 7.

## Water ownership and bounded work

`FastClimateStepper` accepts intervals from one second through seven days. It
uses exactly two representative subperiods, with one wet and one dry phase per
coarse region. The persistent regional wet anomaly determines their ordering;
wet-phase demand is concentrated at twice the applicable normal rate. This gives
a distinct representative event intensity while retaining a normal-scale interval
rain demand. It does not yet generate realistic multi-day dry spells or floods.

The existing detailed column vapor plus cloud water serves as the effective
climate moisture pool. It is **not copied into an additional reservoir**, and no
initial water is injected on climate entry. Detailed momentum/pressure integration
is dormant. Each subperiod:

1. Relaxes the existing surface temperature toward the climate estimate.
2. Requests temperature-dependent evaporation from the existing fine soil, finite
   ocean and wet lakes. Only actual store withdrawals are credited to column
   moisture, using the existing area-overlap mapping.
3. Sums representative rain demand and withdraws at most the available column
   moisture, proportionally across columns. This global allocation approximates
   rapid atmospheric mixing and allows ocean evaporation to fund land rain.
4. Distributes that actual withdrawn budget among wet regions in proportion to
   demand, including the final funded remainder. No deficit or balancing source
   is created when demand exceeds available water.
5. Returns rain through `TerrainMoistSurface`, drains the soil and accepts the
   existing typed hydrology batch once. Pending runoff/lake rain is retained or
   routed by the existing cadence; outlet water returns to the finite ocean.
6. Ingests actual rain and integrated surface temperature into the existing
   seasonal history.

There are two surface scans/routing opportunities per interval, rather than one
per detailed 20-second step or one per simulated day. Fine transfers still use
exact overlaps; display interpolation never allocates physical water. The check
at transaction completion compares actual atmosphere + soil + unaccepted surface
water + hydrology stores/pending water, without any corrective mass adjustment.

Existing hydrology still routes an overshooting subperiod once over its actual
duration. The last discharge is therefore a period-average proxy, not a daily
flood peak. Rain/runoff can be delayed within a subperiod, and lake wetness changes
are resolved at these boundaries. Step 6 must improve event-sensitive soil/lake
routing before longer intervals are supported.

## Thermal approximation and history

Surface temperature follows stable exponential relaxation toward a bounded
seasonal target. Response time interpolates from 10 days over land to 90 days
over ocean; the target is bounded to [-80, 60] °C. Existing thermal memory is
retained, with no reset on entry. The temperature integral uses the analytical
exponential solution rather than replaying physical timesteps.

The diagnostic reports the actual surface energy change caused by relaxation as
an external coarse forcing. Latent heating/cooling, detailed air heat exchange,
phase microphysics and a closed atmospheric energy budget are deliberately
unresolved here. `energyApproximate` is explicit. No detailed energy residual is
corrected or rebased; climate publications omit the detailed energy-diagnostics
object and provide `ClimateIntervalDiagnostics` instead. The old detailed air
climate integral is also withheld because it no longer represents the interval.

The authoritative world clock commits after the climate operation and ecological
update; observation history and published weather use that completed time.
Existing ecology gates and candidate timers remain in place. An unaccepted
physical transfer blocks entry. Detailed stepping after climate entry is rejected
until Step 7 implements a deliberate calendar/atmosphere re-entry handoff.

## Published semantics

Snapshots explicitly identify `REPRESENTATIVE_FOR_INTERVAL`, interval start,
duration and approximate energy. Rain, drainage, overflow and direct lake rain
are integrated across both subperiods, with copied soil/EMA views. Reported rain
rate is total rain divided by the whole interval. Peak is the representative
wet-subperiod mean, never a claimed 20-second shower intensity. Previous snapshots
remain frozen across later intervals.

Cloud opacity derives from accepted interval rain as a display-only cloud-water
proxy; it is not an extra physical reservoir or a frozen detailed storm. Air
presentation is a surface-minus-6 °C proxy, relative humidity is a bounded
moisture proxy, and representative winds are zero. Actual displayed atmospheric
water is the remaining effective column pool. These are explicit initial coarse
visual approximations; richer representative weather remains later work.

The scene labels interval means, representative wet-phase peaks and cloud proxies
when such snapshots are shown. GPU/bitmap ownership remains unchanged. No new
mode button or automatic speed policy was added.

## Small controlled verification

Compilation and 11 focused checks passed: three new `FastClimateStepperTest`
checks plus the affected shared-state, climate-prior and worker checks, about
0.67 seconds of reported test execution. Final generic snapshot metadata
propagation and comments were compiled with production and test sources, without
repeating the numerical checks.

- A 15 × 11 world with an 8 × 6 atmosphere first takes one detailed transaction,
  then a day and a week of climate evolution. Both climate intervals use two
  subperiods; the detailed completed-step counter stays at one. Water agrees
  within 1e-12 relative error, soil remains within capacity, interval means and
  event peaks differ, and prior snapshots remain unchanged. The clock reaches
  eight days plus the original 20 seconds.
- An ocean-only world with exhausted ocean, zero soil and zero atmospheric water
  has positive climate rain demand but exactly zero accepted rain/evaporation and
  zero final water. There is no implicit donor or moisture injection.
- Identical worlds and interval schedules reproduce exactly. Entering before the
  prior detailed transfer is accepted is rejected without advancing the clock.
  The shared seasonal clock agrees with the completed climate clock.

Reproduce the affected checks:

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.weather.FastClimateStepperTest' --tests 'terra.weather.SharedSlowStateTest' --tests 'terra.weather.ClimateNormalsTest' --tests 'terra.simulation.SimulationWorkerTest'
```

No desktop benchmark, seed/timestep sweep, full-suite rerun or long climate run
was needed. These establish a conservative short-interval path, not production
throughput, calibrated climate, multi-year fidelity or a 100 FPS guarantee.
Step 6's event-sensitive coarse runoff/lakes is next.
