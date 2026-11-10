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
finite 120 mm initial soil water or 10,000 mm initial ocean water. Soil capacity is
250 mm, with excess above 120 mm draining on a five-day timescale. Rain enters
soil, ocean, or an already wet lake exactly once. Drainage and overflow enter the
existing hydrology at each completed atmosphere batch. Lake storage remains
owned by hydrology; evaporation withdraws actual available lake water immediately
and pays latent heat from the coupled surface. Lake geometry refreshes after a
batch; routing retains the existing daily cadence.

The water ledger includes vapor, cloud, soil, ocean, lakes and pending routing
water. Outlet exports are included when calculating the residual; they are not
silently recycled into the ocean. The atmosphere remains doubly periodic with
constant Coriolis, while hydrology retains its bounded edge/ocean outlets. This is
an experimental plane coupled to generated geography, not a spherical planet.
Coastal cells share one area-averaged surface temperature and heat capacity; lakes
use their land cell's thermal properties. Terrain elevation does not yet force
orographic ascent or rain shadows.

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
An idealized moving sun provides diurnal shortwave forcing. Cloud water reflects
some sunlight. A gray atmosphere exchanges longwave radiation with the surface
and space. Wind- and humidity-deficit-dependent bulk evaporation works at night;
its latent energy comes from surface heat. Sensible heat exchange transfers energy
between the surface and atmosphere without overshooting equilibrium.

Condensation and cloud evaporation use the **same** represented local temperature
and pressure. A local moist-enthalpy solve includes latent warming/cooling, followed
by finite-rate relaxation. There is one phase adjustment per step, so fresh
condensate is not immediately tested against an unrelated warmer environment.
Rainout transfers existing cloud liquid to the surface reservoir and cumulative
rain diagnostics. It depends on cloud water, with no ground-fixed activation flag.

The default jet starts with a smooth humid/dry air-mass contrast and no clouds.
That humidity pattern is an initial condition only. Subsequent cloud and rain
patterns come from transport, heat exchange and phase changes; no rainfall or
storms are spawned to fit the visualization. The renderer draws the physical
fields; its color normalization changes no water or heat.

## Budgets and approximations

The reference dry column is 8,000 kg/m² at 10,000 m equivalent depth. Atmospheric
water is mass-weighted relative to dry carrier mass; 1 kg/m² liquid equals 1 mm.
The representative saturation calculation uses a stated humid-column fraction
and pressure fraction. No physical surface pressure in hPa is claimed from the
height-anomaly display.

In the standalone preview, total tracked water is vapor + cloud + surface water. Rain and evaporation are
internal transfers; cumulative rain is a diagnostic, not another reservoir.
Only radiation adds/removes model energy from the closed atmosphere/surface system.
The source heat capacity includes both caloric air heat and the temperature-dependent
shallow-water potential energy. This lets latent/sensible transfers pay for their
pressure feedback as well as their temperature change. `state.txt` reports the
remaining energy residual, including numerical transport/mechanical dissipation.
Exact global numerical energy conservation is not claimed.

This is one thermal layer on a periodic plane, with warm-liquid microphysics and
an idealized ocean in the standalone preview, or the terrain surface in game mode.
Water is a dilute tracer: condensate loading, ice, precipitation
fall energy and water's sensible heat capacity are omitted. There is no resolved
vertical convection or mountain forcing yet. The ocean heat
capacity stays fixed as its water store changes. Evaporation cannot spend heat
below the liquid freezing threshold. These are explicit prototype approximations.

Compilation and focused checks cover closed-column latent heat, nighttime
evaporation, rain return, water transport and thermal pressure acceleration.
The existing dry numerical checks remain applicable to the uncoupled dry mode.
Focused integration checks cover non-divisible grid overlap and three simulated
days of the coupled water ledger, including routing handoffs.

The eight-day default example completed all 34,560 steps with mean accumulated
rainfall 21.0 mm, mean vapor 29.5 mm and mean cloud liquid 0.253 mm. Its relative
closed-water residual was −8.28 × 10⁻¹⁶. The uniform ocean forcing tends toward
broad stratiform cloud and light rain; individual thunderstorms and realistic
frontal structures are not claimed. First-order tracer transport also diffuses
fine moisture filaments. The interactive preview was launched successfully.
