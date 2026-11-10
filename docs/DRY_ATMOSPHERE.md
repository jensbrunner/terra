# Dry atmosphere prototype

A [moist thermal extension](MOIST_ATMOSPHERE.md) is also available through
`gradle moistAtmospherePreview`; dry mode remains independent.

Open the interactive 96 × 72 simulation:

```sh
gradle dryAtmospherePreview
```

The left panel shows layer-height anomalies, the model's pressure-like field.
The right panel shows relative vorticity; arrows show wind, with north upward.
Use the scenario buttons, Pause/Resume, Reset, speed selector and wind toggle.
The status line reports simulated time, actual achieved speed and pending time.

The simulation runs on one worker with fixed 20-second physics steps. Swing draws
copied snapshots at 10 Hz. Increasing playback speed requests more steps; it does
not enlarge or skip them. If the worker falls behind, pending time is visible.
The future coarse climate mode for years/s is a separate implementation.

The starting conditions are:

- **Perturbed jet:** two eastward midlatitude Bickley jets, with thickness
  approximately balanced by integrating `dh/dy = -f(y)*u(y)/g`, plus small
  deterministic velocity disturbances. Wind evolves dynamically after initialization.
- **Pressure pulse:** a smooth initial height excess that launches gravity waves
  and adjusts under rotation.
- **Vortex:** a pair of midlatitude height depressions with opposite circulating
  winds, approximately balanced using each center's Coriolis value. No division
  by equatorial Coriolis is used.
- **Rest:** uniform thickness and zero wind.

Export an animated GIF and initial/final PNGs without opening a window:

```sh
gradle dryAtmosphereExperiment
gradle dryAtmosphereExperiment -PdryAtmosphereArgs='--scenario=PULSE --days=2 --output=build/experiments/dry-pulse'
gradle dryAtmosphereExperiment -PdryAtmosphereArgs='--days=24 --frames=49 --output=build/experiments/dry-jet'
```

Default exports go to `build/experiments/dry-atmosphere/`. The runner supports
`--width`, `--height`, `--step`, `--seed`, `--days`, `--frames` and `--scenario`.
It reports actual completed time if the requested duration needs a final whole step.

The core lives in [DryAtmosphere.kt](../src/commonMain/kotlin/terra/atmosphere/DryAtmosphere.kt).
It integrates thickness and two horizontal momenta on a 12,000 × 9,000 km
Cartesian channel, with gravity 9.81 m/s² and mean thickness 10,000 m. Longitude
is periodic; the north/south edges are impermeable free-slip walls. Cell-centered
pseudo-latitude spans −85° to +85° with north-up array Y. Row Coriolis is
`2 * 7.2921159e-5 * sin(latitude)` s⁻¹. The map keeps uniform physical cell areas;
it is a cylindrical-channel approximation, not spherical mechanics.

Reflected ghost states reverse normal momentum and preserve thickness,
temperature, and tangential momentum. Their HLL wall flux has zero mass,
tracer, and tangential-momentum transfer but includes normal pressure and
wave speed in the CFL check. Interior finite-volume fluxes conserve equivalent-fluid
volume. Vorticity uses even tangential ghosts at walls, and potential enstrophy
uses row Coriolis. No polar sponge damping is applied. MUSCL reconstruction,
shear-resolving HLL fluxes, SSPRK2 and split rotation use reusable buffers. Nonpositive/nonfinite states or excessive wave CFL stop the
step before state is committed; there are no physical-state clamps.

This is a dry single-layer dynamics prototype. Height is not calibrated surface
pressure in hPa. The plane is not a spherical planet. There is no moisture,
thermodynamic forcing or terrain coupling yet; numerical diffusion dissipates
energy. The ordinary `gradle runJvm` game defaults to prescribed weather; use
`-PweatherModel=dynamical` for the coupled moist channel.

The flux transports transverse momentum with the shared mass flux, preserving
stationary shear contacts instead of diffusing them at gravity-wave speed. The
[BASEMENT reference manual, §2.3.3.3](https://people.ee.ethz.ch/~basement/baseweb/download/documentation/BMdoc_Reference_Manual_BASEHPC_v4-2-0.pdf)
describes the role of shear-wave resolution in shallow-water Riemann solvers.

The original constant-f, doubly periodic model remains available as
`DryAtmosphereConfig(geometry = AtmosphereGeometry.PERIODIC_F_PLANE)`. Export it
with `--geometry=PERIODIC_F_PLANE` in `-PdryAtmosphereArgs` (or
`-PmoistAtmosphereArgs`); use `-PatmosphereGeometry=PERIODIC_F_PLANE` for the
standalone previews. This preserves the original opposed jets and centered
geostrophic vortex. The game continues to use the channel.

Compilation and the focused dry/channel checks pass. Channel checks cover signed
rotation and its local kinetic-energy invariant, equatorial flow, resting and
free-slip walls, reflected waves, no polar teleportation, periodic X, and volume
conservation. No long-term absence of wall artifacts is claimed; a sponge remains
deferred until observed behavior justifies it.

The following results describe the **original periodic f-plane**, not the channel.
The original five dry checks pass. They cover stationary shear,
rest, unstable-step rejection,
analytic rotation and its hemisphere reversal, pressure-gradient direction,
periodic volume conservation and repeatability under snapshot sampling. The
eight-day example completes 34,560 fixed steps and develops distinct rotating
vortices from the initial shear. Relative volume change was 1.19 × 10⁻¹⁶; final
minimum thickness was 9,650 m. Energy decreased by about 7.1% through numerical
dissipation; energy conservation is not claimed. The desktop preview was also
launched successfully.
