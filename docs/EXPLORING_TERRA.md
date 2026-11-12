# Exploring Terra

[Terrain generation](TERRAIN_GENERATION.md) describes the new crust regions,
mountain belts, build-time erosion, relief shading and extra Elevation controls.

Start the atmospheric simulation with:

```sh
gradle runJvm -PweatherModel=dynamical
```

Without this option, the existing prescribed weather model is still the default.
The Model button opens help with a clearly labeled switch-and-rebuild action.
Switching model resets the world and its clock; switching detailed/fast fidelity
does not.

## Controls

| Action | Control |
|---|---|
| Inspect weather, soil, rivers and climate | Click a tile |
| Pan | Drag the map |
| Zoom about the pointer | Scroll |
| Fit the whole map | Home key or Home button |
| Pause/resume | Space or Pause/Play |
| Slower/faster | Left/Right or −/+ |
| Help | H or Help button |
| Close help/settings | Esc |
| Detailed/fast climate | F, in dynamical mode |
| Automatic fidelity | A |
| Switch weather model and rebuild | M, or the action in Help |

The map has a dedicated viewport between the toolbar and inspector. Home fits
that unobstructed area. Enlarged maps stop panning at their edges; smaller maps
stay centered. Zoom is bounded. Empty margins do not select edge tiles, and a
drag does not intentionally select its release position.

## Map layers

Keys 1–9 follow toolbar order: humidity, clouds, rain, 30-day rain EMA, air
temperature, annual rain EMA, surface temperature, lighting, wind.

One scalar colour overlay can be active at once. Choosing another replaces it;
choosing it again turns it off. Clouds, wind and lighting remain independent.
The legend uses the map's palette, including the square-root rain scale. Clouds
and lighting can tint the result, so click a tile for actual numerical values.
An EMA is a rolling exponential estimate, not the measured annual climate window.

Resolved weather and representative climate estimates remain labeled separately.
Fast climate cloud motion is illustrative; wind is unresolved. Simulation time
and requested/achieved speed come from completed publications, not visual blends.

## Settings without accidental restarts

Elevation and Climate sliders now edit a shared draft. They no longer regenerate
the world repeatedly while dragged. **Apply & rebuild world** commits the draft
once and restarts the world/time using the chosen settings. An unchanged draft
does nothing. Closing a settings panel retains unapplied edits; it does not
apply them. Both menus indicate whether edits are pending.

Rebuilding clears the old tile selection and marks the inspector as rebuilding.
Camera position and overlay preferences remain available. Help closes settings
and appears above them, and its model action explicitly describes the reset.

## Validation and limits of this pass

`gradle compileKotlinJvm` passes, as do two small `terra.ui.MapCameraTest` checks
covering outside-map picking, pan limits, anchored zoom, zoom bounds and Home.
One native dynamical launch ran without a reported runtime exception; existing
startup callback/texture warnings remain. Desktop automation reports the Java
application but cannot attach to it (`Invalid app: net.java.openjdk.java`), so
native visual inspection and interactive slider/help checks remain unverified.
No new performance claim, full-suite run or exhaustive experiment was added.

This is a controls and usability pass. Persistence, seed selection and richer
game objectives remain future work. The simulation's transport, circulation and
energy limitations are described in [CLOUD_REENTRY.md](CLOUD_REENTRY.md).
