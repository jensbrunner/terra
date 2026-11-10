# Phase 7, Step 3 — shared slow state and bulk transfers

Detailed and future climate evolution can now reach the same surface stores
without constructing another world or copying authoritative water/thermal state.
This step provides the transfer/statistics interfaces; it does not enable fast
climate advancement in the game.

## Ownership and access

| State | Sole owner / access |
|---|---|
| Fine soil and surface exchanges not yet accepted by routing | Existing `TerrainMoistSurface`, reached through `DynamicalWeather.surfaceState` on the owner thread |
| Finite ocean, lakes, pending runoff/rain/evaporation demand and routing duration | Existing `HydrologySimulation`; `waterInventory()` reports amounts from these stores |
| Coarse surface temperature | `SurfaceThermalState`, whose array is the same array used by `MoistAtmosphere` |
| Seasonal climate history | Existing `SeasonalClimate`, reached through `DynamicalWeather.climateHistory` |
| World calendar and ecological timers | Existing `WorldSimulation` and `World`; no replacement introduced |

`SurfaceThermalState` exposes owner-only validated temperature writes and read
access. The detailed solver still uses the same values and heat capacities.
There is no new thermal evolution, heat source or energy correction. A future
climate relaxation/latent-heat approximation must account for its forcing; a
prescribed temperature write alone is not an energy-conserving transfer.

Surface inventory includes soil plus unaccepted drainage, overflow and direct
lake rain, then hydrology's stored/pending water. After successful acceptance,
only hydrology counts those transferred amounts. Potential lake evaporation is
reported separately as a demand and is never counted as stored water.

## One routing handoff

`WaterFluxBatch` names interval duration in seconds, runoff depth by fine tile,
direct lake rainfall depth, and potential lake evaporation depth. With unit tile
area for hydrology accounting, a tile's mm depth is its mm·tiles contribution.
It carries `HydrologyExchange` callbacks for lake geometry, confirmed evaporation
and successful acceptance, without requiring a weather simulation to be stepped.

`HydrologySimulation.acceptFluxBatch()` is the common implementation. It retains
the existing daily cadence and once-per-overshoot routing behavior. It does not
add Step 6's event-sensitive long-interval routing. Ocean outlet returns, lake
storage/capacity and throughput-versus-rate calculations use the existing code.

A batch can be claimed only once, including across hydrology instances. Failed
routing is not replayable. Copied batches validate and copy caller arrays; callers
must already have debited runoff/rain donors. Detailed surface batches borrow
owner-thread arrays without allocating/copying full fine grids every 20 seconds.
A source epoch guards borrowed data, and a new coupled surface update cannot
clear unaccepted water. Sealing verifies the interval duration and prevents
subsequent rain/evaporation writes to that batch's source arrays.

`acceptWeatherStep()` remains compatible and delegates to this implementation.
Both built-in weather models provide the current typed batch, so repeating the
same completed handoff is rejected, including a duration mismatch. Custom
`WeatherModel` implementations without a batch retain a synchronous compatibility
adapter; they must supply a persistent batch themselves if they need duplicate
call detection. Prescribed standalone stepping retains its previous behavior;
the coupled world owner continues accepting every update before advancing again.
Immutable presentation models expose no pending mutable batch.

## Integrated climate history

`ClimateIntervalSummary` copies integrated surface temperature in °C·seconds,
actual precipitation depth in mm, extrema and peak rainfall rate. It represents
a completed interval with an explicit start and duration. `observeInterval()`
requires that start to match the existing observation clock, preventing overlap,
gaps or replay before history is changed. It retains the first-year spin-up,
season/year splitting, two-observed-year coverage and existing ecological rules.

Temperature/rain integrals are apportioned uniformly within an interval when it
crosses a season boundary. Supplied extrema conservatively apply to all bins it
overlaps, rather than replacing event peaks with interval means. Producers should
split seasonally heterogeneous forcing into representative segments. Work scales
with season/year boundaries, not 20-second fluid steps. This is not a claim that
an annual average reconstructs real seasonal events. Bulk history ingestion does
not itself advance the world calendar; a future completed climate transaction
must commit both consistently.

## Focused verification

Compilation and 16 affected checks passed: `SharedSlowStateTest` (2),
`SeasonalClimateTest` (4), `SimulationWorkerTest` (3), `GlobalHydrologyTest` (4),
and `TerrainAtmosphereTest` (3), about 1.25 seconds of reported test execution.
The two shared-state checks were rerun after adding the final surface-duration
guard; the seasonal checks were rerun after tightening input validation.
An initial new fixture used fewer columns than hydrology permits; it was corrected.

The controlled 10 × 8 basin fixture starts with 19,480 mm·tiles in the existing
soil/ocean stores. It moves actual ocean water into an 80 mm·tiles lake and
establishes six hours of pending runoff. A synthetic twelve-hour interval then
withdraws finite soil/lake/ocean water, returns exactly that amount as rain,
and produces runoff and direct lake rain. Total water agrees within 1e-9
mm·tiles before/after acceptance; existing pending water remains until the next
six-hour segment completes the routing day. The temporary evaporation ledger is
an in-transit transfer amount in the fixture, not a second atmospheric model.
A subsequent detailed step uses those same stores and closes the model water
residual below 1e-12 relative error. No balancing water is injected.

Other assertions cover shared thermal storage, sealed/duplicate/duration guards,
copied flux input, and a mixed detailed/bulk climate calendar with offset seasonal
boundaries. Bulk temperature/rain means match a small uniform fine reference;
recorded extrema/peaks survive, spin-up is discarded and overlapping history is
rejected. Existing worker checks retain direct detailed-solver agreement.

Reproduce the affected checks:

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.weather.SharedSlowStateTest' --tests 'terra.weather.SeasonalClimateTest' --tests 'terra.simulation.SimulationWorkerTest' --tests 'terra.hydrology.GlobalHydrologyTest' --tests 'terra.atmosphere.TerrainAtmosphereTest'
```

No full-suite rerun, desktop benchmark, seed sweep or new long simulation was
needed for this interface increment. Climate priors, a conservative atmospheric
moisture pool, coarse thermal evolution, automatic mode transitions and fast
calendar advancement remain later steps. Step 4 is next.
