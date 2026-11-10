# Phase 7, Step 7 — detailed/climate handoff

Historical Step 7 result. Step 8 adds automatic selection, adaptive quanta and A
to restore automatic mode; see [PHASE_7_ADAPTIVE.md](PHASE_7_ADAPTIVE.md).

Press **F** in dynamical mode to switch fidelity without regenerating the world.
Speed and pause remain separate controls. Climate mode currently uses a fixed
one-day quantum; automatic fidelity selection and adaptive quanta remain later
work. **M** retains its existing prescribed/dynamical switch and world reset.
Camera and selection state are unaffected by F.

## Entering climate

Fidelity commands are coalesced with the worker's speed/pause controls and applied
between complete transactions. Entry rejects an unaccepted numerical surface
transfer. The existing detailed vapor + cloud arrays already form the effective
climate pool, so no second reservoir is initialized or filled. Soil, finite ocean,
lakes, pending routing water, world generation, biome candidates and seasonal
history remain owned by the same objects.

Changing fidelity does not advance time. The next complete climate transaction
advances the shared calendar through the existing conservative fast path. A
pending detailed routing interval is retained and merged exactly once into the
next coarse phase, with the duration metadata introduced in Step 6.

## Returning to detailed weather

`MoistAtmosphere.initializeFromClimate()` and a narrow dry rest initializer rebuild
the atmosphere in place. No world, topology, hydrology, soil array, thermal array
or history is constructed or reset. The initialization:

- Retains each coarse column's total effective water.
- Preserves the carrier's mean depth, makes height uniform and sets winds to zero.
- Uses one uniform air temperature from the current mean surface temperature
  minus 8 K, bounded to [240, 300] K. Uniform height/temperature with zero wind
  is a mechanically balanced rest state; current seasonal/geographic surface
  forcing subsequently develops weather.
- Partitions water into vapor up to 90% saturation at that temperature/pressure;
  all remaining water becomes cloud. `AtmosphereHandoff` records the chosen
  humidity limit, vapor/cloud amounts, air temperature and water before/after.
- Leaves surface temperature, soil, ocean, lake geometry/storage, pending routing,
  ecology timers and climate history unchanged.

The pre-fast-forward storm is not resumed. There is no hidden spin-in and no
extra surface exchange during reconstruction: `spinInSteps` is zero. Only normal
subsequent resolved steps advance time and apply water/heat exchanges. The tiny
validation case needed no spin-in; this is not a stability guarantee across all
seeds, resolutions and extreme climates.

The initialization changes kinetic/air/latent energy. Its actual energy difference
is recorded explicitly, and diagnostics retain `includesApproximateHistory`
after climate use. Original energy references and numerical-error counters are
not reset or adjusted to manufacture closure. Raw energy residuals include the
approximate history; they must not be interpreted as a closed physical energy
budget after fast evolution. Detailed cumulative rain/evaporation counters remain
numerical-model counters; climate transfers use interval diagnostics and the
shared seasonal history. The obsolete detailed-only air climate integral remains
withheld after a coarse epoch.

## Calendar, presentation and lifecycle

The solver retains its lifetime physical-step count and local integration time.
An absolute world epoch maps that clock to the committed calendar. Detailed
radiation uses the absolute midpoint time; sunlight, seasonal observations and
presentation use the same current date rather than restarting at year zero.
The moist snapshot carries both the dry numerical clock and absolute world time.

After reconstruction, the last representative cloud/rain view stays labeled as
such until a new detailed step completes. This also works when returning while
paused: the status shows **Detailed pending**, without an invented advancement.
Existing 120 ms cloud crossfades provide the visual handoff; no scene/GPU objects
are touched by the simulation worker.

World publications carry selected fidelity, fidelity revision, pending-resolution
status and the last handoff diagnostics. Revision checks force a new immutable
view even for a paused mode change at the same clock. A metadata-only publication
cannot accidentally retain an obsolete fidelity acknowledgement. Old snapshots
remain frozen and world generation guards still reject obsolete worlds.

Demand uses the selected integration quantum when bounding requested backlog.
Changing from a one-day to a 20-second quantum retains physical state and accounts
any trimmed uncommitted demand as unmet requested time. Speed/pause updates keep
the selected fidelity. Regeneration, shutdown and coalesced commands retain their
existing transaction boundaries. F is disabled in prescribed mode; switching
models with M explicitly returns the requested fidelity to detailed.

## Focused verification

Compilation and 11 affected checks passed: `FidelityTransitionTest` (2), existing
worker checks (3), fast-climate checks (3), and coupled terrain/atmosphere checks
(3). Reported test execution was about 1.36 seconds. A final diagnostic-only
humidity-partition field was followed by production/test compilation without
repeating numerical checks.

One 15 × 11 world with an 8 × 6 atmosphere took one detailed step, **171 seven-day
climate intervals** (3.28 real 365-day model years), then returned to detailed for
**100 physical steps**. The transition itself retained the exact clock, world
and hydrology snapshot identities, seasonal history, soil and surface temperature.
Atmospheric water agreed within 1e-12 relative tolerance. After resolved steps,
the lifetime water residual was **3.4593e-15 relative** and all observed fields
remained finite/nonnegative. The physical step count at handoff stayed at one;
there were no hidden detailed steps during the 171 climate intervals. Absolute
solar/twilight views matched the current season and differed from a restarted
numerical-date view. This tiny coarse schedule took a fraction of a second; it
was not a production-grid multi-year fluid experiment.

The worker check switches while paused, advances climate, coalesces repeated
fidelity requests, and returns to resolved integration in the same generation.
It verifies unchanged paused clock/soil and representative labeling until actual
resolved progress resumes. Existing owner/routing/direct-solver checks still pass.

Reproduce the focused checks:

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.simulation.FidelityTransitionTest' --tests 'terra.simulation.SimulationWorkerTest' --tests 'terra.weather.FastClimateStepperTest' --tests 'terra.atmosphere.TerrainAtmosphereTest'
```

No full-suite rerun, parameter sweep, new desktop benchmark or manual GUI test
was performed. Full-resolution handoff latency, broader stability and visual
acceptance remain unmeasured. Step 8's adaptive quanta and extreme rates are next.
