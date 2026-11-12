# Clouds after centuries of fast climate — 12 November 2026

The reported cloudless return to detailed mode was reproducible. This repair
changes coarse rain funding and atmosphere reconstruction, without adding a
moisture refill, a cloud quota or hidden integration.

## Diagnosis

Coarse rainfall demand could consume the entire atmospheric water pool, including
unsaturated vapor. Total planetary water remained conserved: water had moved into
surface stores. Conservation alone missed a physically poor atmospheric state.

In the regression world, 255 annual climate transactions left **zero atmospheric
water**. The interval cloud estimate was still 0.0484 mm because it included rain
that had already reached the ground. After twenty detailed hours, evaporation had
returned only 1.120 mm to the air, with mean RH 0.059 and no clouds.

Reentry also replaced local air temperatures with one global mean surface
temperature minus 8 K and reset winds. The old transition check covered about
33 detailed minutes after 3.28 climate years and checked conservation, calendar
and finite fields, rather than weather recovery.

## Changes

Fast rainfall draws only from water above saturation at the phase's endpoint air
temperature proxy (local surface temperature minus 6 K):

```kotlin
precipitable[c] = max(0.0, effectiveMoisture[c] - saturationCapacity[c])
fraction = if (available > 0.0) min(1.0, requestedRain / available) else 0.0
withdraw(c, precipitable[c] * fraction)
```

The existing warm-cloud formula supplies this capacity. This bulk coarse closure
protects existing unsaturated vapor; it does not fill dry columns or resolve
lifting. Rainfall and subsequent ecological trajectories can change because
precipitation is now subject to this additional moisture limit.

Detailed reconstruction uses each local surface temperature minus the same 6 K
offset used by climate presentation. Carrier depth is proportional to
`1 / sqrt(airTemperature)`, normalized to preserve total carrier volume. The
model's thermal pressure, proportional to `height² × temperature`, initially
agrees across columns. The finite-volume scheme can still diffuse thermal
contacts; this is not an exactly preserved numerical equilibrium.

Every column retains its water, partitioned into vapor up to saturation and
excess condensate. The former 90% vapor cap is removed. Winds start from rest;
old storms are not revived. Soil, surface temperature, ocean/lake stores, history
and calendar remain unchanged during reconstruction. No spin-in, reseeding or
surface water withdrawal is performed.

Reconstruction's measured energy change remains explicit in handoff diagnostics,
along with new minimum/maximum air temperatures. Raw residuals are not silently
corrected to imply a closed energy budget across approximate climate history.

## Focused evidence

One 48 × 36 world / 24 × 18 atmosphere takes one detailed step, 255 annual climate
transactions, then twenty detailed hours. This regression failed before the fix.

| State | Atmospheric water mm | Cloud water mm | Visible fraction |
|---|---:|---:|---:|
| End of climate, new closure | 34.677 | 0.207 representative | 47.6% |
| Detailed hour 1 | 34.593 | 0.086 resolved | 23.1% |
| Detailed hour 6 | 34.080 | 0.171 resolved | 40.8% |
| Detailed hour 20 | 32.635 | 0.187 resolved | 41.9% |

Visibility uses alpha >24/255 and fresh renderer instances, so stale climate
textures cannot hide missing condensate. This wet fixture must recover clouds
within one hour; this is not a quota for naturally dry worlds. Lifetime relative
water residual is -6.62e-13. Reconstruction retained 3.74515e15 kg of atmospheric
water, with zero hidden steps and an explicit energy change of -7.34122e21 J.

Twenty-four distinct affected checks pass across focused runs, covering climate
funding/aggregation, moisture/energy closure, reconstruction, transitions, terrain
coupling and animation/pause. A new test initially omitted the explicit
reconstruction energy change; its assertion was corrected and the five moist
atmosphere checks rerun. Production energy residuals were not redefined.

Two short native observations used hour/s and month/s with clouds and seasonal
lighting enabled, each with 0.5 s warm-up and 2 s measured time. Mean worker
publication costs were **7.97 ms** and **7.94 ms** (19 publications each), retaining
the earlier roughly 7–8 ms cost and 10 Hz target. Frame p99 was 10.26/10.86 ms.
The initial two-case run ended after its hour case without a completion marker;
the missing month case was run separately and completed. Existing startup
callback/texture warnings remain. These are brief smoke observations, not a
controlled performance comparison or manual aesthetic acceptance. No full suite,
seed/grid sweep or benchmark matrix was run.

Artifacts are under `build/experiments/cloud-reentry-before/` and
`build/experiments/cloud-reentry-after/`. Current JSON and five preview images are
also under `build/reports/clouds/reentry-*`. Each preview shows rendered clouds
above raw cloud water (grayscale optical scale 0.15 mm). The paused handoff view
remains representative until a detailed step completes.

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.simulation.FidelityTransitionTest' --tests 'terra.weather.FastClimateStepperTest' --tests 'terra.weather.AggregateClimateTest' --tests 'terra.atmosphere.MoistAtmosphereTest' --tests 'terra.atmosphere.TerrainAtmosphereTest' --tests 'terra.atmosphere.TerrainFeedbackTest' --tests 'terra.simulation.CloudAnimationWorkerTest'
gradle runJvm -PweatherModel=dynamical
```

## Remaining criticism

Fast rainfall still redistributes funded supply globally: source and rain
locations are not connected by physical winds. Interval estimates must not be
mistaken for endpoint state. Local moisture transport would be a more substantial
next improvement.

Detailed reentry reconstructs an approximate atmosphere with initially weak winds;
it cannot preserve individual systems through centuries of coarse evolution.
The one-layer solver has limited vertical circulation. The inspected recovery
preview still contains broad zonal structure: cloud recovery does not establish
the desired satellite-style fronts and swirling systems. Further work should
target physical organization rather than latitude masks or moisture injection.
Approximate climate energy and reconstruction energy remain limitations.

An already depleted atmosphere is not refilled by toggling fidelity: this fix
prevents future depletion and lets actual evaporation supply water. Restart the
application to load the changed model; new runs exercise the corrected path.
