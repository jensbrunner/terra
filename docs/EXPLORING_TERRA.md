# Exploring Terra

```sh
gradle runJvm -PweatherModel=dynamical
```

The existing default without this option remains prescribed weather. The top bar
names the current mode; Help shows the model and its explicit rebuild action.

## Screen

- **Top:** committed calendar, current mode/state, Pause/Resume, exact speed picker,
  Home, Settings and Help. −/+ move one speed preset and stop at the limits.
- **Map:** drag to pan, scroll to zoom about the pointer, click to inspect.
  Home fits the unobstructed map. Empty margins cannot select edge tiles.
- **Layers:** one colour overlay plus independent Clouds, Wind and Light toggles.
  Clear removes the colour overlay. Its legend supplies units and range.
- **Inspector:** Overview shows common conditions. Details contains rainfall
  periods, moisture stores and routing diagnostics. Interval proxies and
  prescribed weather retain distinct source labels.
- **Footer:** Auto / Weather / Climate controls, Model help and actual progress.
  Weather/Climate select fidelity manually; Auto restores automatic selection.
  These controls are disabled for the prescribed model.

The frame-time display is optional under Help; profiling enables it automatically.

## Keys

| Action | Key |
|---|---|
| Pause / resume | Space |
| Slower / faster | Left / Right |
| Fit map | Home |
| Help / close panels | H / Esc |
| Model help; no immediate reset | M |
| Switch detailed / fast fidelity | F |
| Restore automatic fidelity | A |

Keys 1–9 retain their existing mapping; each layer button shows its number:
1 humidity, 2 clouds, 3 rain, 4 30-day rain EMA, 5 air temperature,
6 annual rain EMA, 7 surface temperature, 8 lighting, 9 wind.

Changing fidelity preserves the world and its calendar. Switching weather model
uses the explicitly labeled action in Help and rebuilds the world/time.

## Settings

One dialog groups **Landforms**, **Climate** and **Advanced** controls. All tabs
share a draft. Sliders never rebuild the world while dragged.

**Apply & rebuild world** commits a changed draft once and restarts time.
**Discard edits** restores the last applied settings across all tabs. **Defaults**
edits the draft; it does not apply automatically. Unchanged Apply/Discard buttons
are disabled. Close/Esc retain unapplied edits for later. Rebuilding clears the
old selection and marks the inspector as loading.

[Terrain generation](TERRAIN_GENERATION.md) explains the landform controls.
[UI redesign](UI_REDESIGN.md) records the design, previews and focused validation.

## Reading estimates

An EMA is an exponential rolling estimate, separate from the observed annual
climate window. Fast climate shows representative interval estimates; its cloud
motion is illustrative and wind is unresolved. Map colour can be tinted by
clouds and lighting: inspect a tile for numerical values. Display crossfades
never advance the committed simulation clock.
