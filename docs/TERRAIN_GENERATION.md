# Terrain generation — 12 November 2026

This replaces the three-noise-field height map with tectonic-inspired crust
regions, inherited ranges, spatially varying roughness and build-time erosion.
It changes newly generated worlds, including the geography associated with old
seeds. It is a procedural landscape model, not a plate-tectonics simulation.

## Assessment of the previous terrain

The old generator added continental, regional and fine simplex fractals to a
constant base elevation. Longitude was already periodic and the hydrology already
had valid priority-flood drainage and explicit lake basins. Those were strengths
to retain.

The weaknesses were structural: high ground was mostly the upper tail of noise,
mountains appeared as isolated patches, and similar roughness covered lowlands
and uplands. Small requested grids sampled a small corner of the same tile-scale
noise rather than the whole planet. Adding octaves could not supply mountain
belts, coherent interiors or erosion shaped by catchment area. The normal terrain
textures also hid much of the continuous elevation field.

Three native-size baselines (seeds 31742, 42, 2026) had 46–49% land, only 0.8–1.0%
mountain tiles, and many short drainage fragments. These are observations, not
Earth calibration targets.

## Generation pipeline

1. **Crust regions.** Seeded, jittered sites carry oceanic/continental membership
   and a synthetic motion direction. Continent scale controls site density.
   Longitude distances wrap. Smooth noise warps the coordinates, while soft
   membership blends crust types into curved coastal transitions and interiors.
2. **Convergence.** Pairwise blended relative motion toward neighboring sites produces localized
   uplift. Oceanic uplift can reach sea level and form broken island arcs.
   Ridged regional detail roughens ranges rather than every lowland.
3. **Inherited ranges.** Continental regions receive seeded, curved, tapered
   ridge segments. These represent older orogeny away from current convergence.
   They are geometric proxies, not reconstructed geological history. An early
   contour-noise version produced too many closed ridge loops and was replaced
   after preview inspection.
4. **Lowlands and detail.** Broad fractal variation remains, but fine roughness
   is reduced outside uplift. Base elevation, sea level, noise scales/octaves and
   strengths still control the resulting world.
5. **Talus relaxation.** Three bounded local passes move material down steep
   slopes and deposit it at adjacent cells. This stage conserves summed height;
   its small transfer coefficient prevents overshoot even at maximum strength.
6. **Stream incision.** A provisional priority-flood topology supplies catchment
   area and drainage order. An implicit downstream-first stream-power-inspired
   filter lowers downhill land, bounded by sea level and a maximum incision.
   Virtual uphill spill links are not carved. This stage does not conserve rock
   volume or model sediment deposition, rainfall, elapsed geological time or water.
   The simulation builds its ordinary final topology from the resulting terrain.

The canonical coordinate domain is 283 × 212, retaining the existing sliders'
native-map units. Other grid resolutions represent the same whole planet.
Un-eroded coincident sample points agree; erosion remains a grid approximation.
`World.sampleElevation()` now interpolates the final eroded raster with a periodic
cubic surface, so it agrees with actual tile-center heights instead of sampling
a different pre-erosion field. Latitude clamps rather than wraps.

The generated surface is finite and bounded, deterministic by seed/settings, and
still supports flat worlds when all three noise strengths are zero. Continental
strength also scales associated uplift, so those existing laboratory fixtures do
not unexpectedly acquire mountains.

## Rendering and controls

The normal terrain view adds a subtle single-raster relief layer below surface
water and weather. It is constructed on generation changes, not synthesized on
every frame. It uses immutable elevation differences and never feeds temperature,
rainfall, biomes or physical sunlight. This stylized topographic light is separate
from the existing current-date/seasonal lighting layer.

Elevation settings now include **Mountain relief** (default 0.32) and **Erosion
strength** (default 0.45). Setting either to zero disables its contribution.
The existing **Mountain cutoff** controls categorical classification, not uplift.
Changes remain drafts until **Apply & rebuild world**; rebuilding restarts time.

## Focused results and cost

| Seed | Land before → after | Mountain tiles before → after | Generation before → after |
|---|---:|---:|---:|
| 31742 | 46.0% → 36.0% | 1.01% → 2.94% | 54.7 → 102.7 ms |
| 42 | 47.9% → 62.6% | 0.82% → 15.54% | 45.8 → 106.2 ms |
| 2026 | 49.2% → 33.6% | 0.92% → 3.37% | 48.0 → 97.8 ms |

Mountain percentages use all tiles, not only land. All timings are short JVM
observations, not controlled benchmarks. The approximately doubled default
generation cost is paid once per rebuild on the simulation worker; there is no
new per-step geology or per-frame elevation synthesis. Very small continent-scale
settings create more sites and can cost more; no exhaustive settings sweep ran.

The previews show broader lowlands, long elevated ranges, island arcs and larger
catchments. They also expose a remaining weakness: enclosed basin land increased
from 4–5% to roughly 10–24%, despite fewer qualifying lake candidates in these
three worlds. Basin counts are not a realism score. Large basins remain possible,
and this version does not force all rivers into open ocean or guarantee a desired
continent count. Final lake candidates are not automatically filled with water.

**19 focused checks pass**, including three terrain checks, longitude continuity,
drainage order/ocean terminals, coupled water/energy closure, fast-climate funding
and the 255-year detailed reentry regression. No assertions were weakened for the
new geography. Cloud recovery remains visible after one detailed hour in that
fixture, and its lifetime relative water residual is -4.69e-13.

Two brief native smokes used the same hour/s case around the final junction
refinement: clouds and seasonal lighting, 0.5 s warm-up and 2 s measured each.
The final run completed with 20 publications averaging **6.48 ms**, frame p99
**10.44 ms** and no reported runtime exception. Existing startup callback/texture
warnings remain. This does not establish a speed improvement or manual aesthetic
acceptance. Native desktop automation cannot attach to the Java app; exported
terrain/drainage previews were visually inspected instead.

Validation used a baseline and a few revisions of the same three images and
directly affected checks. No full suite, seed sweep, long weather spin-up or
benchmark matrix was added.

## Reproduce and inspect

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.world.TerrainGenerationTest' --tests 'terra.hydrology.GlobalHydrologyTest' --tests 'terra.atmosphere.TerrainAtmosphereTest' --tests 'terra.weather.FastClimateStepperTest' --tests 'terra.simulation.FidelityTransitionTest'
gradle runJvm -PweatherModel=dynamical
```

`build/experiments/terrain-before/` and `terrain-after/` hold comparison panels,
metrics and validation records. The current panel is also at
`build/reports/terrain/terrain-current.png`: columns are seeds 31742, 42, 2026;
the top row is shaded hypsometric elevation and the bottom adds potential
drainage paths with at least 18 upstream land tiles. These are topology previews,
not rainfall-funded live rivers. The colors are diagnostic, not game biomes.

Important next work would be sediment-aware valley floors/deltas, spatially
varying erodibility and better treatment of large closed basins. The current map
is a longitude-periodic latitude channel, not a spherical tectonic mesh, and does
not model plate ages, mantle flow, glaciation or active volcanism.
