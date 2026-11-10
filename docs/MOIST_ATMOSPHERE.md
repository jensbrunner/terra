# Moist atmosphere prototype

Open the standalone cloud/rain preview:

```sh
gradle moistAtmospherePreview
```

The tabs show clouds/rain, height/vorticity and temperature/humidity. Wind arrows,
scenario/reset buttons, pause and playback controls work as in the dry preview.
The footer reports atmospheric water, accumulated rainfall and the water residual.
To export the same fields as PNGs and an animation:

```sh
gradle moistAtmosphereExperiment -PmoistAtmosphereArgs='--days=8 --frames=25'
```

Output is `build/experiments/moist-atmosphere/{initial.png,final.png,atmosphere.gif,state.txt}`.
Options also include `--scenario=JET|PULSE|VORTEX|REST`, `--width`, `--height`,
`--step` and `--seed`. Dry commands remain available. Playback requests additional
fixed 20-second steps; it does not skip them. Coarse years/s climate advancement
remains later implementation work.

## Main game integration

Launch the terrain map with the dynamical atmosphere:

```sh
gradle runJvm -PweatherModel=dynamical
```

Press **M**, or click the weather status bar, to switch between prescribed and
dynamical weather. Switching restarts the generated world and its water stores.
The default launch retains the prescribed model. All existing weather overlays,
wind arrows, tile inspection, terrain settings, pause and speed controls work with
both models. Dynamical mode starts at 1 simulated hour per second, skips the old
210-day initialization, and samples its own actual rainfall and temperature.
Climate observations refresh hourly; the first 30 simulated days classify the
land biomes once. They are not a claim of equilibrated annual climate.

The 96×72 atmosphere shares a 12,000×9,000 km equal-area plane with the 283×212
terrain. Exact rectangle intersections distribute rainfall and collect evaporation,
including partial coastal cells and grids whose dimensions do not divide evenly.
Atmospheric north maps to the top of the tile map; wind units are converted from
m/s to tiles/day. State fields use bilinear display interpolation; water transfers
use overlap areas rather than interpolation.

Land and ocean have different albedos (0.24/0.08), surface heat capacities
(2/41.8 MJ m⁻² K⁻¹), and initial surface temperatures (284/288 K). Fine tiles own
finite 120 mm initial soil water; hydrology owns one finite ocean reservoir,
initialized to 10,000 mm per ocean tile. Soil capacity is
250 mm, with excess above 120 mm draining on a five-day timescale. Rain enters
soil, ocean, or an already wet lake exactly once. Drainage and overflow enter the
existing hydrology at each completed atmosphere batch. Lake storage remains
owned by hydrology; evaporation withdraws actual available lake water immediately
and pays latent heat from the coupled surface. Lake geometry refreshes after a
batch; routing retains the existing daily cadence.

The water ledger includes vapor, cloud, soil, ocean, lakes and pending routing
water. River discharge replenishes the shared ocean once per routed period;
`outletMmTilesTotal` records this internal transfer and is not another reservoir.
`exportedWaterKg` is zero for the coupled terrain surface. The atmosphere defaults to periodic longitude,
reflective free-slip polar walls,
and signed latitude-dependent Coriolis (see [dry dynamics](DRY_ATMOSPHERE.md)).
Display interpolation stops at the polar rows; conservative remapping is unchanged.
Hydrology wraps longitude and routes to ocean sinks; polar land has no edge outlets. This is a latitude-aware Cartesian
channel coupled to generated geography, not a spherical planet.
Coastal cells share one area-averaged surface temperature and heat capacity; lakes
use their land cell's thermal properties. Terrain slopes now supply diagnostic
orographic uplift; precipitation still comes from the atmosphere's existing water.

At most eight fixed physical steps run per rendered frame. Unfinished requested
time remains visible as pending hours. High speed buttons request time but cannot
yet deliver years/s: resolution changes and coarse climate advancement are still
to implement. There is no long synchronous catch-up call at those speeds.

## Physics implemented

The model carries `h`, two momenta, `h*T`, `h*q_v` and `h*q_c`. Vapor, cloud water
and temperature use the dynamics' actual shared face mass flux and the same RK
stages. Tracers use donor mixing ratios, so they conserve transported quantities
and preserve positivity under an explicitly checked carrier-outflow bound.

Temperature affects the shallow-water pressure flux through
`p_model = g*h²*T/(2*Tref)`. This is a thermal shallow-water/Ripa closure, not a
temperature color attached to prescribed winds. See the conservative equations
in [this Ripa-system paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC5979031/).
The bulk temperature's atmospheric interpretation remains an approximation.

In the standalone preview, each column has a finite ocean-water store and a fixed mixed-layer heat capacity.
`PlanetarySunlight` supplies latitude-aware diurnal and seasonal shortwave forcing.
The circular orbit defaults to a 24-hour day, 365-day year, and 23.44° tilt.
Time zero is the northward equinox; `SolarConfig.initialYearFraction` can select
another initial season. Daylight uses `max(0, sin(latitude)*sin(declination) +
cos(latitude)*cos(declination)*cos(hourAngle))`. Radiation samples the step's
midpoint; the game uses the same geometry at its current clock time, with terrain
Y reversed. The day/night overlay now resolves latitude, polar daylight/darkness,
and a smooth visual twilight. The prescribed fallback keeps its previous lighting.

The normal-beam peak is 1,360 W/m² before model albedo/cloud reflection. The old
1,000 W/m² peak, combined with the new latitude projection, produced overly cold
tropics in the bounded seasonal check. This is a normalization change to the
existing shortwave term, with no extra thermal relaxation or duplicate heat source.
Cloud water reflects some sunlight. A gray atmosphere exchanges longwave radiation with the surface
and space. Wind- and humidity-deficit-dependent bulk evaporation works at night;
its latent energy comes from surface heat. Sensible heat exchange transfers energy
between the surface and atmosphere without overshooting equilibrium.

Condensation and cloud evaporation use the **same** diagnostic parcel temperature
and pressure (the bulk state when uplift is zero). A local moist-enthalpy solve includes latent warming/cooling, followed
by finite-rate relaxation. There is one phase adjustment per step, so fresh
condensate is not immediately tested against an unrelated warmer environment.
Rainout transfers existing cloud liquid to the surface reservoir and cumulative
rain diagnostics. It depends on cloud water, with no ground-fixed activation flag.

The default jet starts with a smooth humid/dry air-mass contrast and no clouds.
That humidity pattern is an initial condition only. Subsequent cloud and rain
patterns come from transport, heat exchange and phase changes; no rainfall or
storms are spawned to fit the visualization. The renderer derives large-scale cloud density from physical cloud water, with
wind-advected visual detail as described below; it changes no water or heat.

## Budgets and approximations

The reference dry column is 8,000 kg/m² at 10,000 m equivalent depth. Atmospheric
water is mass-weighted relative to dry carrier mass; 1 kg/m² liquid equals 1 mm.
The representative saturation calculation uses a stated humid-column fraction
and pressure fraction. No physical surface pressure in hPa is claimed from the
height-anomaly display.

In the standalone preview, total tracked water is vapor + cloud + surface water. Rain and evaporation are
internal transfers; cumulative rain is a diagnostic, not another reservoir.
Only radiation adds/removes model energy from the closed atmosphere/surface system.
Weak bulk momentum drag has a 30-day e-folding time, applied exponentially to both
momenta. Lost kinetic energy heats the same atmospheric column, including its
thermal pressure energy. `frictionHeatingJoules` records this internal transfer;
it is already in total model energy and is not subtracted as an external sink.
Set `MoistPhysicsConfig.momentumDragPerSecond = 0.0` to disable it. Surface-specific
roughness scales this rate as described below; there is no added polar sponge.
The source heat capacity includes both caloric air heat and the temperature-dependent
shallow-water potential energy. This lets latent/sensible transfers pay for their
pressure feedback as well as their temperature change. `state.txt` reports the
remaining energy residual, including numerical transport/mechanical dissipation.
Exact global numerical energy conservation is not claimed.

This is one thermal layer on a periodic-X, bounded-Y channel, with warm-liquid microphysics and
an idealized ocean in the standalone preview, or the terrain surface in game mode.
Water is a dilute tracer: condensate loading, ice, precipitation
fall energy and water's sensible heat capacity are omitted. There is no resolved
vertical convection; mountains and convection use the diagnostic ascent below. The ocean heat
capacity stays fixed as its water store changes. Evaporation cannot spend heat
below the liquid freezing threshold. These are explicit prototype approximations.

Compilation and focused checks cover closed-column latent heat, nighttime
evaporation, rain return, water transport and thermal pressure acceleration.
The existing dry numerical checks remain applicable to the uncoupled dry mode.
Focused integration checks cover non-divisible grid overlap and three simulated
days of the coupled water ledger, including routing handoffs.

The original eight-day periodic-f-plane example completed all 34,560 steps with mean accumulated
rainfall 21.0 mm, mean vapor 29.5 mm and mean cloud liquid 0.253 mm. Its relative
closed-water residual was −8.28 × 10⁻¹⁶. The uniform ocean forcing tends toward
broad stratiform cloud and light rain; individual thunderstorms and realistic
frontal structures are not claimed. First-order tracer transport also diffuses
fine moisture filaments. The interactive preview was launched successfully.

Phase 1 channel validation used one default-grid day (4,320 steps): zero relative
volume change, −1.18 × 10⁻¹⁶ relative water residual, and final CFL 0.1069.
Outputs are under `build/experiments/phase-1/moist-channel/`. Focused checks also
cover closed channel moisture transport and polar display interpolation, alongside
the existing three-day coupled water ledger. These bounded checks do not establish
long-term circulation or climatic realism.

## Phase 2 bounded seasonal check

At Phase 2, compilation and all 19 JVM tests passed, including solar geometry and polar
presentation, radiation accounting/surface thermal inertia, and friction heat
return. Existing land/ocean albedo and heat-capacity weighting are preserved.

One 16×12 JET experiment, repeated once after correcting shortwave normalization,
advanced 180 days with fixed 180-second steps and three exported frames. This
coarse experiment timestep is CFL-checked and does not change the production
96×72 grid's 20-second step. Reproduce with:

```sh
gradle moistAtmosphereExperiment -PmoistAtmosphereArgs='--days=180 --width=16 --height=12 --step=180 --frames=3 --output=build/experiments/phase-2/seasonal-channel'
```

Final relative volume change was 8.30e−16, water residual 1.06e−15, and CFL 0.144.
Mean tropical (|latitude| < 30°) surface temperature was 277.95 K versus 251.99 K
in polar rows (|latitude| > 60°). The effective air column averaged 247.23 K;
these are not calibrated Earth or near-surface climate temperatures. In particular,
the current biome classifier should not be considered validated by this run.
Net radiation was −1.39e23 J, internal friction heating 1.15e20 J, and numerical
energy residual 3.16e18 J. Numerical energy conservation is not claimed.

Maximum wind remained 13.44 m/s, but non-zonal RMS wind fell from 0.244 to
0.048 m/s at day 90 and 0.056 m/s at day 180. The final field is predominantly
zonal with broad stratiform clouds. Seasons sustain circulation; this run does
not demonstrate persistent storm generation. Uniform ocean geography, coarse
numerical diffusion, bulk drag, and single-layer limitations cannot be separated
with this single configuration. No parameter sweep, production-grid multi-month
run, random storm source, or extra atmospheric layer was introduced. Terrain
feedback is the next planned increment; storm persistence remains an open criterion.

## Terrain feedback and unresolved convection

`AtmosphereTerrain` precomputes coarse mean elevation/slopes and fine slopes using
the existing equal-area overlap mapping. Terrain Y is reversed into north-up
slopes. Positive `wind dot slope` is averaged over fine intersections before
coarsening, so narrow ridges contribute even when their coarse central slope is
zero. Only elevation above the configured sea level contributes; one normalized
elevation unit represents 10,000 m of atmospheric relief. This atmospheric scale
does not change the existing game-scale hydrological lake capacities. Longitude derivatives now wrap across the seamless terrain; latitude derivatives
remain bounded at the polar edges.

A buoyancy proxy activates only when the surface is warmer than a dry-adiabatic
mixed-column reference: `excess = max(0, Ts - T - g/cp * mixingDepth)`.
Convective velocity is `0.02 * sqrt(g * mixingDepth * excess / T)`, capped at
1 m/s, with a default 1,500 m mixing depth. Moisture availability and saturation
still determine whether this creates any condensate; dry hot air creates no rain.

Combined terrain/convective ascent over a 600-second diagnostic residence time
sets parcel lift, capped at 1,500 m. The parcel uses `Tp = T - g/cp * lift` and a
hydrostatic pressure reduction. This is an unresolved parcel diagnostic, not a
second layer or an external bulk cooling term. The existing single moist-enthalpy
adjustment uses that same parcel state for condensation AND cloud evaporation,
including latent warming in its solve. Actual phase transfer heats/cools the
bulk column and its thermal pressure energy. No second ambient-temperature cloud
adjustment follows. When ascent stops, existing cloud may evaporate normally.
Reversible vertical work and a separate parcel potential-energy store are not
resolved. Bulk relative humidity in the game remains a bulk-column diagnostic;
subgrid lifted clouds can coexist with bulk subsaturation.

Rainout still withdraws existing cloud water, vapor still supplies condensation,
and latent/sensible/surface fluxes retain their existing accounting. Windward
vapor removal and transport can produce a dry lee; no side of a mountain has a
permanent rainfall flag. There is no new rainfall generator or independent cloud
inventory. Set `maximumParcelLiftMeters = 0.0` to disable both ascent effects, or
`convectiveVelocityCoefficient = 0.0` to disable only convection.

Drag multipliers are overlap-averaged: ocean 1, desert/tundra 1.5, steppe 1.8,
plains 2, woods 4, mountains 6. With the default base drag, these represent
30-day to 5-day column momentum e-folding times. Roughness refreshes when climate
biomes are applied; lake tiles currently retain their underlying land roughness,
consistent with the existing land-cell thermal approximation. Dissipation still
returns to column heat through the existing friction ledger.

Phase 3 validation used `gradle compileKotlinJvm jvmTest`: all 23 tests passed.
The four added checks cover fine ridges, north-up slopes and reversed wind,
windward rain/vapor depletion and closed water/latent-energy transfers,
instability/moisture-dependent convection, and roughness-dependent drag with heat
return. The existing three-day reduced-grid terrain/hydrology test also passes.
No separate experiment or exhaustive parameter sweep was run. These checks establish
local mechanisms and accounting, not a validated map-scale rain-shadow climate,
convective storm climatology, or recovery of sustained storms from Phase 2.

## Cloud presentation (Phase 4)

The game cloud layer now uses `CloudRenderer`, a smoothed bitmap at two pixels
per terrain tile (566×424 for the default map). Physical cloud water is sampled
once per tile on each refresh, then bilinearly interpolated into the visual layer.
Two seeded, reusable texture fields add broad structure and fine billows within
that physical envelope. Zero interpolated cloud water produces zero opacity;
texture never adds atmospheric water or rainfall, or places independent storms.
Thin clouds remain translucent, while dense rain clouds have cooler shaded cores
and subtle highlights. Rain intensity and surface/air temperature contrast provide
a visual fine-detail proxy; this is not a new physical convection diagnosis.

Texture follows local winds through a bounded semi-Lagrangian update using the
completed simulation clock exposed by `WeatherModel`. Longitude wraps and latitude
stops at the polar rows. Deterministic weak renewal restores contrast lost to
interpolation. It is visual noise renewal, not a physical moisture source.
Repeated paused observations do no work; model replacement, clock rewind, and
world regeneration reset the detail deterministically. Hidden-overlay or large
fast-forward intervals use one bounded advection/renewal pass, so the renderer does
not attempt to replay unseen weather history.

Cloud redraws are capped at 10 Hz by the overlay scheduler; other overlays keep
their existing cadence. The renderer reuses its bitmap, physical-field arrays,
and advection buffers. Simulation resolution, timestep, water/energy ledgers, and
microphysics are unchanged. Full desktop throughput has not been benchmarked.

Verification used compilation, the existing 23 checks, and two small renderer
checks for physical support, wind/seam movement, pause, and clock reset. A controlled
before/after field was visually inspected at `build/reports/clouds/review.png`.
It is a presentation fixture, not evidence of new physical storms. Recreate it with:

```sh
gradle jvmTest --tests 'terra.render.CloudRendererTest'
```

The game uses this renderer in both prescribed and dynamical weather modes.
Standalone atmosphere experiment images retain their diagnostic field rendering.
No long simulation, exhaustive visual suite, or new rendering dependency was added.

## Seamless terrain and the ocean cycle (Phase 5)

World generation blends two translated samples of the existing seeded 2D noise
with a quintic weight. Values and derivatives match around longitude, and the
terrain grid samples cell centers without duplicating an endpoint. Noise scales,
octaves, and determinism are retained; old seeds now produce different geography.
`World` tile access wraps X, and atmospheric elevation derivatives wrap too.

All D8 neighbor searches now wrap X, including depression grouping, saddle/MST
selection, basin routing, and basin entries. Priority-Flood seeds ocean cells only;
latitude walls and longitude edges no longer export runoff. The existing graph
order verification still rejects cycles. On an all-land map, the deterministic
global minimum anchors one explicit nonspilling basin with finite water storage
and unbounded capacity. Its wet footprint grows with water level. This is a simple
closed-world fallback, not a model of separate endorheic sub-basins on an oceanless
planet; ordinary ocean-bearing maps retain the existing compound lake basins.

`HydrologySimulation` owns a finite, well-mixed ocean in mm·tiles. Ocean rain and
river discharge replenish it, and the dynamical surface withdraws actual available
water for evaporation. Ocean temperature and heat capacity remain spatially varying
on atmospheric cells; no ocean currents or salinity are modeled. The tracked water
sum includes the shared ocean exactly once, along with soil, vapor/cloud, lakes,
and pending transfers. Cumulative river-to-ocean discharge is diagnostic only.
The older prescribed fallback retains its existing unbudgeted evaporation model;
the closed coupled water-ledger claim applies to dynamical weather.

Lake banks query wrapped neighbors. River segments use the shortest X displacement
and raster brushes wrap pixels, so seam links draw at both edges instead of across
the middle of the map. Polar rendering stays bounded; no artificial edge outlet
segments remain. These raster changes were reviewed in code, without a desktop
visual or throughput benchmark.

Compilation and all 29 checks passed. Four focused additions cover deterministic
smooth periodic elevation/terrain slopes, seam drainage and finite ocean returns,
a seam-spanning lake filling and spilling once, and all-land storage/evaporation.
One ordinary 283×212 topology construction also passed its acyclicity and terminal
checks. The existing three-day reduced-grid coupled water ledger passes with
internal ocean return. Only the four hydrology checks were rerun for that default-map
construction. No seed sweep, multi-year run, or expensive atmospheric experiment
was added. Repeated-year climate and reservoir behavior remain Phase 6 work.
