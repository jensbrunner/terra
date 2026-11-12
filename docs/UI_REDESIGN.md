# UI redesign — 12 November 2026

The focus is a clear exploration workspace: a larger map, predictable controls,
and common information first. The previous screen mixed nine layer buttons with
playback, used a large bottom diagnostic paragraph, and required a nearly
full-height elevation panel.

## Layout and visual hierarchy

The unobstructed map viewport is now 1064 × 816 beside a 328-pixel sidebar.
At Home, the native 283 × 212 world displays about 36% larger in each dimension
than in the previous height-limited layout. The top bar separates calendar/state
from playback. Layers and their unit legend occupy one card; tile inspection
occupies another. A quiet footer holds fidelity and actual-progress feedback.

Shared rounded cards/buttons use consistent spacing, a deep blue/green surface
palette, teal selected states, readable primary text and subdued secondary labels.
Hover and disabled states are explicit. Stateful controls avoid rebuilding their
appearance when their selected/enabled values have not changed.

The inspector's Overview shows air/surface temperature, humidity, cloud amount,
rain rate, soil moisture, elevation and lake state in aligned rows. Details keeps
rain accumulation periods, rate/EMA values, moisture stores, drainage and routing
metadata available. Climate estimates and prescribed conditions retain distinct
labels. Rebuilding immediately clears the prior tile's values and selection.

## Actions and clarity

- The speed label opens all eight exact presets. −/+ step through them without
  wrapping, and become disabled at their limits. Pause becomes Resume.
- Layer buttons display their existing shortcut numbers. Scalar colours remain
  mutually exclusive; clouds/wind/light remain independent. Clear removes only
  the scalar layer. Legends share the actual palette and rain scale.
- Auto / Weather / Climate are visible footer controls. They change fidelity
  without rebuilding the world and are disabled under prescribed weather.
- Settings is one dialog with Landforms, Climate and Advanced tabs. Shared
  drafts survive tab changes and closing. Defaults edits the draft, Discard
  restores the applied state, and Apply commits once. No-op actions are disabled.
- H/Help and M/Model open the same concise model/control help. M no longer resets
  the world immediately. The explicit model-switch action names the alternative
  and states that it rebuilds the world/time.
- Frame time is optional in Help, enabled automatically for profiling.
  Modal settings suppress global speed/layer keys while editing sliders.

No numerical weather, climate, hydrology or terrain algorithms were changed.

## Focused validation

`gradle compileKotlinJvm` passes. Four small camera/settings-draft checks pass:
outside picking and bounds, anchored zoom/Home, coalesced commit, and discard
across settings fields. No broad numerical suite was run for this UI pass.

An opt-in `atlasUiPreview` task renders actual game views through KorGE rather
than generating a mockup. Nine previews were exported and inspected: overview,
selected tile, details, speed picker, three settings tabs, edited settings, help.
The initial review exposed a Details line below its card; its fit was corrected
and inspected again. These previews use a paused real world; GPU readback time
is not a gameplay frame-rate measurement.

The first preview launch lacked runJvm's module-access JVM flags and the native
OpenGL path aborted. The task now inherits the launcher's flags and subsequent
exports complete. The launch log is kept under the generated preview artifacts.
Existing startup callback/texture warnings remain. This was a preview-launch
configuration issue; normal runJvm retains its existing launcher.

One native smoke used hour/s, clouds and seasonal lighting: 0.5 s warm-up and
2 s measured time, p95 9.73 ms, p99 10.40 ms. Automatic fidelity selected climate
in this short startup window (3600 s quantum), so these numbers are not a
controlled comparison against previous detailed-mode performance. No speed
improvement is claimed. Full manual mouse/keyboard interaction remains unverified;
exported states exercise layout/rendering and the tests exercise draft/camera
logic. No exhaustive tests, parameter sweep or benchmark matrix was added.

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.ui.MapCameraTest' --tests 'terra.ui.TerrainSettingsDraftTest'
gradle atlasUiPreview
gradle runJvm -PweatherModel=dynamical
```

Current previews are at `build/reports/ui/`. Copies, focused reports, source
fingerprints and the native smoke are at `build/experiments/ui-redesign/`.
The layout targets the existing 1440 × 960 virtual canvas; independent panel
resizing, text scaling and keyboard focus navigation are not introduced here.
