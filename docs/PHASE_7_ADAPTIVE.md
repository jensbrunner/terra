# Phase 7, Step 8 — adaptive quanta and aggregate years

Dynamical worlds now choose detailed or climate evolution automatically. Speed
controls include requested **1 year/s** and **10 years/s** presets. Minus/plus
buttons cycle the speed without overflowing the toolbar. **F** overrides fidelity
manually; **A** restores automatic selection. Requested and actual progress remain
separate. A 100-years/s control was not added.

## Selection, budgets and overload

`AdaptiveFidelity` uses completed worker transactions, not frame delta or camera
state. Detailed costs use a smoothed mean, at least eight samples and a 0.25-second
observation window. Sustainable detailed rate uses 60% of the measured capacity.
Presets above 1 hour/s conservatively prefer climate; measured overload can also
move slower requests to climate. A sustained 0.25-second overload and 0.5-second
switch cooldown prevent single-sample switching. Return requires a lower 65%
threshold, 1.5 seconds below it and a one-second cooldown; an explicit affordable
low-speed selection can return immediately. Manual detailed mode can still lag
its requested speed rather than silently sacrificing fidelity.

Nominal climate quanta are:

| Requested rate | Climate quantum |
|---|---:|
| Realtime or 1 hour/s, when climate is selected | 1 hour |
| 12 hours/s | 3 hours |
| 1 day/s | 6 hours |
| 1 week/s | 2 days |
| 1 month/s | 7 days |
| 1 year/s | 1/12 orbital year |
| 10 years/s | 1 orbital year |

A smoothed climate cost can enlarge these quanta to retain worker headroom,
up to 20 orbital years per transaction. Reductions have a two-second hysteresis;
small cost fluctuations do not continuously retune the quantum. The worker starts
no more than 20 climate operations per wall second, in addition to its existing
bounded slices and complete-transaction policy. A transaction can finish beyond
its five-millisecond scheduling deadline; it is never interrupted into invalid
physical state. Work remains off the renderer.

Pending request limits follow the current quantum. Pause retains uncommitted
demand; speed/quality changes account trimmed requests as unmet time. Only fully
completed intervals commit time, and a batch never exceeds available requested
time. The direct interval API also accepts partial intervals down to one second.
If the largest supported quantum is still unaffordable, actual progress slows
and unmet demand is reported. Quanta and load-driven coarsening are published.

## Seasonal and multi-year aggregation

Short intervals retain two representative wet/dry phases. Longer intervals through
one year split at orbital quarter boundaries, with bounded phase counts (at most
12 including numerical boundary fragments). Multi-year intervals use exactly
**eight** grouped seasonal wet/dry phases. Exact periodic exposure weights retain
each season's represented duration without iterating intervening years; the
ordering finishes in the final date's season.

The existing regional travelling modes now have closed-form interval means.
The previous monthly endpoint interpolation is replaced by smooth continuous
monthly-phase modes so these means can be evaluated directly. Their seed,
longitude wrap, bounds and time correlations remain deterministic. Integrating
anomalies does not enumerate months or years. This changes seeded representative
trajectories from earlier versions, not conserved water or resolved fluid steps.

Within long phases, accepted rain has a representative wet fraction of 12–30%.
The soil integrator applies rain over that wet duration, then integrates the dry
remainder analytically. This retains concentration-driven saturation/overflow
and a separate representative event peak without generating daily storms. Fine
routing still runs once per phase, and all actual water is funded from the same
stores. Exponential thermal and soil responses remain bounded.

This is an explicit seasonal/event-moment approximation. Grouping many years
cannot recover their actual storm sequence or every lake fill/spill cycle.
Ordered evaporation, global moisture allocation and donor limits can bias very
long-interval climate and storage behavior. Conservation is strict; climatology
and long-interval hydrological fidelity are not established by that fact.

## History and ecology

`SeasonalClimate.observeAggregate()` consumes boundary fragments and constructs
the latest two annual estimates, skipping intermediate completed years
arithmetically. Its work does not scale with the number of skipped years.
Aggregate years are explicitly marked; normal blending identifies them as
`BLENDED_ESTIMATES`, not independent observed evidence of stationarity.

An aggregate estimate cannot establish the annual stability needed for categorical
biome changes. Multi-year intervals grant zero biome persistence credit, retaining
existing cover and candidate timers. Other intervals require annual coverage
already present at their start and grant at most one quarter-year of credit.
Two subsequent nonaggregate completed years can replace estimated annual history
and restore ordinary stability evaluation. This deliberately delays uncertain
biome changes rather than applying a final condition to all skipped years.
Extreme-rate ecology is therefore conservative and may remain visually static;
no fast vegetation realism is claimed.

## Focused checks and one short desktop pass

Compilation and **19 distinct affected checks** passed across focused runs. Four
new policy/aggregation checks cover sustained cost hysteresis, manual overrides,
load coarsening, automatic high/low speed changes, a 20-year transaction and
bounded annual-history skipping. The initial policy check caught a startup switch
before adequate measurement; that was corrected. Only affected checks were rerun
around fixes and the final ecology-credit guard. Existing seasonal, prior,
transition, fast-interval and small routing-reference checks remain passing; no
full-suite or seed/timestep sweep was used.

The tiny 20-year interval uses **eight routing passes**, retains the original one
physical step and returns to resolved integration. Relative interval water error
was **-1.9218e-15**. A separate one-cell 1,000-year history-summary call verifies
arithmetic skipping and estimated-history flags; it is not a physical integration.
An analytic anomaly mean matches a 128-point numerical reference within 0.002.

One desktop pass used the default 283 × 212 surface / 96 × 72 atmosphere, clouds
on, one-second warm-up plus three-second measurement at each of three selected
rates (~12 seconds of cases). Same M5 Pro / 24 GiB / 120 Hz VSync setup as prior
short checks:

| Requested | Observed actual | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| 1 hour/s | 1.00 hours/s | 8.27 | 10.11 | 10.20 |
| 1 year/s | 1.06 years/s | 8.25 | 10.12 | 10.24 |
| 10 years/s | 9.64 years/s | 8.41 | 11.14 | 13.16 |

The high cases used month/year quanta, and measured transaction counts were 32
and 29 over their windows. Load-driven enlargement was not needed in these short
cases. These are exploratory observations with JIT/order effects and 5 Hz clock
publication quantization, which can report slightly above/below a requested rate.
They are not guaranteed throughput or 100 FPS acceptance. GPU/present timing and
manual camera interaction were not isolated; the backend emitted startup
callback/texture warnings and completed normally.

Artifacts are in `build/experiments/phase-7/adaptive-step-8/`. The measured source
fingerprint predates the final ecology-credit restriction; the final fingerprint
is also recorded. That restriction received targeted checks, without repeating
the desktop run. No second performance sweep was performed.

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.weather.AggregateClimateTest' --tests 'terra.simulation.AdaptiveFidelityTest' --tests 'terra.weather.ClimateNormalsTest' --tests 'terra.weather.SeasonalClimateTest' --tests 'terra.weather.FastClimateStepperTest' --tests 'terra.simulation.FidelityTransitionTest' --tests 'terra.weather.CoarseHydrologyTest'

gradle runJvm -PweatherModel=dynamical -PperformanceProfile=true -PprofileSpeeds=ONE_HOUR,ONE_YEAR,TEN_YEARS -PprofileClouds=true -PprofileSeconds=3 -PprofileWarmup=1 -PprofileOutput=build/experiments/phase-7/adaptive-step-8
```

Use a fresh output directory for another live run. Step 9's fast-mode visual
presentation and Step 10's final acceptance remain pending.
