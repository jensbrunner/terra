# Climate and landscape architecture

World creation follows `terrain → twelve-month climate atlas → surface and vegetation → detailed atmosphere`.
The cached atlas supplies the environmental baseline. Fast-forward evolves persistent
soil, snow, groundwater, channels, lakes and vegetation monthly without running the
detailed atmospheric solver.

## Climate

Temperature follows planetary sunlight, axial tilt, elevation and the different
thermal response of land and ocean. Diagnostic winds follow thermal pressure
gradients, rotation and friction. There are no prescribed latitude wind belts or
tropical rainfall enhancements.

Rainfall uses steady upwind moisture transport, ocean evaporation, weak unresolved
mixing, convergence, cooling and terrain ascent. Rainout removes moisture before
it reaches the lee. Limited land recycling returns a fraction of rain. The solve
stops on equation residual and change, with a bounded iteration cap. Terrain-based
downscaling preserves coarse-cell rainfall totals; it adds no rainfall noise.

## Detailed weather

Startup and return from fast-forward call the same initializer, using the current
atlas month's temperature, relative humidity and winds. Pressure is compatible with
the atlas thermal tendency: for pressure `P = g h² T / (2 Tref)`, the initializer
chooses `h = C / sqrt(T) - 2 K Tref / g`, preserving total carrier volume and giving
`dP/dT = -K h`. Persistent surface stores survive reentry. Atmospheric reconstruction
is an explicit handoff, rather than a claim of conserved historical atmospheric water.

InitialWeather, SynopticPattern, their seeded vortices and the ongoing latitude jet
forcing have been removed. Numerical jet fixtures remain isolated tests. Clouds
form from the initialized humidity and subsequent dynamics. Native-grid startup
and century reentry runs remain stable and close their water budgets. Larger
eddies weaken over the observed first day: this one-layer model has not demonstrated
self-sustaining strong storm activity. There is no replacement artificial forcing.

## Persistent landscapes

New surfaces receive four diagnostic climate years of water equilibration, then
advance to the starting orbital phase without advancing the world clock. This
funds their stores from rain and snow rather than initially filling lakes.
Root-zone capacity varies with fixed substrate and slope. Infiltration, snowmelt,
actual evapotranspiration and drainage share the same stores in both modes.
Groundwater and sufficiently established channels can supply roots through bounded
withdrawals. Existing drainage networks route upstream runoff into rivers and lakes;
lake evaporation consumes actual stored water.

Vegetation responds to temperature, rainfall/PET, growing-season water and drought.
Neighboring trees assist recruitment, grass competes with seedlings, and drought
accelerates loss. Continuous cover retains terrain and substrate variation. The
century control retains wet windward forest and dry leeward cover, with runoff and
bounded soils; drought reduces canopy and restored water permits recovery.

## Validation — 14 November 2026

- `gradle compileKotlinJvm jvmTest climateAtlas climateBenchmark`: 85 tests pass.
- Three generated seeds, islands and controlled ridge/flat/reversed-wind maps
  exported under `build/experiments/seasonal-atlas`. The ridge receives approximately
  3,491 mm/year windward and 323 mm/year leeward, with 71% versus 0% tree cover.
- The numerical default-world benchmark reaches 137 simulated years/second;
  surface-water relative residual is approximately `5.1e-15`.
- Actual desktop `runJvm` profile at ten years/second: 361 frames over 3.008 seconds
  (120 FPS), p95 9.92 ms, 10.3 achieved years/second including pending work. Clouds
  and wind overlays were disabled for this fast-forward profile.
- Actual overview, wind, inspector, details and annual rainfall screens exported
  and inspected under `build/reports/ui`.

These measurements describe the tested worlds and short desktop run, not a promise
of identical results on every machine or terrain. Terrain rebuilding expansion,
year-to-year climate variation and additional atmospheric physics remain deferred.
