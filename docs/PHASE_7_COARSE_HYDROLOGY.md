# Phase 7, Step 6 — event-sensitive coarse soil and routing

Climate rainfall now enters a constant-forcing soil integration instead of being
dumped into soil before a dry drainage calculation. Each representative phase
routes once on the existing fine catchment graph. Published discharge represents
the combined interval, with its actual duration, rather than only its final half.

## Fine soil, bounded temporal work

`TerrainMoistSurface.receiveClimateRain()` records source-funded rainfall in a
pending fine-tile transfer array. This queued amount is counted in the surface
water inventory until applied, not duplicated in the soil. Ocean rain is returned
to the finite ocean; direct rain on wet lakes retains its existing hydrology
handoff. Detailed rainfall and its physical-step bucket path are unchanged.

`finishClimateStep()` solves each land bucket with constant rainfall and drainage:

`dS/dt = rainRate - max(S - 120 mm, 0) / (5 days)`

Soil capacity remains 250 mm. The solution handles the time below field capacity,
exponential drainage above it, and the exact time saturation starts. Excess above
capacity becomes overflow. Short-duration response terms use stable exponential
forms; bounded roundoff guards retain valid storage. No per-day or per-storm loop
is introduced. Dry phases use the same analytical drainage solution with zero
rainfall, while saturated wet phases can continue to drain and overflow together.

Evaporation remains an ordered finite-store withdrawal before each rain phase,
not an exact simultaneous rain/evaporation solution. Thus the model is still a
representative-phase approximation. It does not replay all events or apply an
unconditional annual runoff coefficient. Soil state, rain concentration and wet
phase duration affect the resulting runoff.

## Routing and lakes

`HydrologySimulation.acceptClimateFluxBatch()` forces one topological routing
pass for the supplied phase, independent of the detailed daily cadence. Existing
pending runoff, lake rain and elapsed duration are included once. Lake capacity,
spill links, wet-mask updates, evaporation clipping and ocean outlet returns use
the existing stores and graph. No new drainage network or second lake model is
created. Detailed/prescribed `acceptFluxBatch()` keeps its daily cadence.

The fast stepper still uses two wet/dry subperiods, so it performs exactly two
routing passes per interval. It accumulates their tile throughput, lake inflow
and outlet diagnostics, then publishes the sum without routing or crediting the
same water again. `completedRoutingPasses` counts physical passes separately
from snapshot revisions.

The reported hydrology duration includes any antecedent pending interval merged
into the first phase. A one-day climate interval following 20 seconds of pending
detailed hydrology therefore represents 86,420 seconds of flow. Later intervals
without antecedent pending time represent their own duration exactly. Discharge
is total mm·tiles divided by represented days, never an instantaneous flood peak.
Snapshots retain both throughput and rate, plus duration/aggregate metadata;
previous snapshots stay frozen. The additional throughput copy is one bounded
fine-grid double array per changed hydrology publication.

The routing API can process a long monotone wet or dry segment in one pass. This
does not justify replacing many alternating lake fill/dry cycles by one net
annual clamp. Representative sign-changing segments remain necessary. Lake
wetness/availability is updated at phase boundaries; within-phase fill timing and
newly inundated soil are not resolved as continuous hydraulic dynamics.

## Rainfall metadata

Climate snapshots expose per-tile wet fraction and intense-rain depth alongside
integrated rainfall, interval mean and representative phase peak. Wet fraction
is time with nonzero rain divided by the interval duration. Intense-rain depth
is the portion above 2 mm/hour within representative phases, a diagnostic tuning
threshold, not an Earth-calibrated infiltration parameter or an additional runoff
source. Tile selection shows these summaries and the represented flow duration.

The existing engine cap remains one week per transaction. The long-segment
routing API is in place for later adaptive quanta; no annual/multi-year climate
speed policy or detailed re-entry is added in this step.

## Focused verification

Compilation and the 15 affected checks passed across focused runs: three new
`CoarseHydrologyTest` checks, three fast-interval checks, four global-hydrology
checks, two shared-state checks and three worker checks. Their reported execution
time was under one second. The first catchment fixture did not exceed the
existing 64-tile stream display cutoff; its shape was made taller with the same
96 tiles. Only the six coarse/fast checks were rerun after that test-only change.
No production display threshold was relaxed.

- One ten-day constant-rain phase was compared with 80 existing detailed
  bucket updates at three-hour spacing on an 8 × 12 catchment. Final soil differed
  by **0.5382 mm**; total outlet flow differed by **0.9391%**. Coarse routing used
  **one** pass versus **ten** daily reference passes. Both closed water within
  1e-8 mm·tiles, including queued rain before bucket integration. The retained
  fine channel passed the existing stream display criterion. This is a bucket/
  routing reference, not an expensive full atmospheric simulation or a 20-second
  forcing-accuracy claim.
- Starting from 240 mm soil, the same 100 mm of rain spread over ten days versus
  two wet days followed by eight dry days produced final soil of **179.4735 mm**
  and **146.2465 mm**, respectively. The concentrated event overflowed; the
  distributed case did not. Both conserved water and retained bounded soil.
- A 10 × 8 seam-spanning basin retained antecedent pending runoff, filled to
  capacity and spilled the excess during one monotone 365-day wet segment.
  A subsequent dry segment requested more evaporation than storage; only actual
  lake water was returned to the receiver. Each segment used **one** routing
  pass, water closed within 1e-9 mm·tiles, and the old wet snapshot stayed frozen.
  These are two small flux batches, not 730 simulated daily calls or a multi-year
  climate integration.
- Existing day/week climate checks retain water closure below 1e-12 relative
  error and no further fluid steps, now also checking two routing passes,
  represented flow duration, wet fractions and representative event intensity.

Reproduce the affected checks:

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.weather.CoarseHydrologyTest' --tests 'terra.weather.FastClimateStepperTest' --tests 'terra.hydrology.GlobalHydrologyTest' --tests 'terra.weather.SharedSlowStateTest' --tests 'terra.simulation.SimulationWorkerTest'
```

No seed/timestep sweep, full-suite rerun, desktop benchmark or long atmospheric
experiment was needed. Energy remains explicitly approximate. Step 7's seamless
detailed/climate handoff is next.
