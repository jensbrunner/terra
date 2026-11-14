# Seasonal climate and detailed weather

Geography determines a deterministic twelve-month climate atlas. `ClimateGeography`
freezes elevation in meters, physical ocean membership, coast distance and directional
slopes. Its geometry revision is independent of the ecological revision. Land-cover
labels never decide whether a tile is ocean or how high its mountains are.

`ClimateAtlasBuilder` uses the 96 × 72 grid by default; passing a 48 × 36 atmosphere
configuration explicitly supports the cheaper fallback. Temperature uses a fitted
latitude gradient, 6 °C/km lapse rate, axial tilt, continental seasonal amplitude and
maritime lag. Smooth latitude winds transport diagnostic humidity from warm oceans.
160 alternating upwind fixed-point sweeps per month solve ocean replenishment,
bounded rainfall recycling and directional uplift rainout. No atmosphere, pressure,
heat, clouds or persistent vapor are integrated in climate mode. This diagnostic
moisture calculation is not a mass-conservative atmospheric prediction.

Monthly rainfall is mm/month (twelve equal climatological months of 365/12 days).
Annual rainfall is exactly their sum. Bilinear terrain reconstruction and bounded
slope weights are normalized with geometric overlaps so downscaling preserves the
regional rainfall total. Temperature is corrected for individual tile elevations.
PET is a bounded temperature/daylight empirical estimate. Zero tilt eliminates
thermal seasons. Orbital settings are immutable per simulation owner; regeneration
builds a new atlas for a new configuration.

`ClimateEvolution` visits physical tiles in chronological calendar months, splitting
only at request and month boundaries. Rain/snow, degree-day melt, soil-limited actual
ET, drainage, runoff and drought are the evolving stores/fluxes. Soil, snow and
hydrology persist across fidelity switches. Its water residual accounts for supplied
land rain minus actual evaporation and exported runoff; diagnostic humidity is
excluded. Monthly evolution allocates no per-tile objects. Publication copies mutable
surface fields; atlas arrays remain frozen until replacement.

At creation, ecology starts near a continuous warmth/aridity/substrate suitability.
Tree and grass cover persist; annual growth, drought decline, fire and recovery evolve
over years. Cold growing seasons support tundra; geological alpine terrain takes
precedence. Coherent bounded texture affects cover, never rainfall.

Physical edits use `World.setElevation` or `SimulationWorker.editTerrain`. The worker
coalesces edits, snapshots current geometry, builds all twelve months off the render
thread, and publishes the new atlas with a new geometry revision. Existing land
retains soil, snow and cover. Land/sea conversions reset soil, snow and vegetation;
new land starts empty. Drainage topology is rebuilt, preserving tile water inventories;
old lake water is redistributed to former lake tiles, and water now on ocean is
exported. Vegetation responds gradually after the replacement. A renderer can keep
using the previous immutable publication throughout rebuilding.

Returning to detailed weather initializes a new physical atmospheric realization
from the current atlas temperature, prevailing winds and climatological RH. The
handoff reports its introduced/removed atmospheric water and energy. This is an
explicit initialization flux, not an exact historical vapor inventory. The detailed
solver then maintains its own physical water/energy accounting from that boundary.

Controls: 4 monthly rain (mm/month), 6 annual rain (mm/year), Q aridity (rain/PET),
T annual mean temperature, 5/7 current monthly air/surface temperature, 9 prevailing
wind. Detailed weather retains resolved precipitation, dynamics and clouds. Climate
mode exposes monthly means and no fabricated storm peaks, event frequency or clouds.

## Review and measurement

- `gradle compileKotlinJvm` compiles the game.
- `gradle jvmTest` checks climate mechanisms and existing detailed functionality.
- `gradle climateAtlas` writes three seeds, ocean/continent, reversible ridge and
  edited-ridge maps under `build/experiments/seasonal-atlas/`.
- `gradle climateBenchmark` measures startup, monthly cost, years/second, surface
  water closure and detailed reentry on the default world.
- `gradle runJvm -PperformanceProfile=true -PprofileSpeeds=TEN_YEARS` measures the
  actual desktop loop. Targets remain 10 years/second near 100 FPS; numerical-only
  throughput is not a frame-rate measurement.

The model is qualitative game climatology. Coefficients are interpretable calibration
choices, not Earth forecast parameters. Regional winds are idealized; moisture access,
terrain and wind direction control the spatial precipitation response.

Measured on the target desktop during this rebuild: the default world sustained
9.99–10.00 years/second at about 120 FPS (360 frames in each three-second sample),
with 95th-percentile frame times of 10.08 ms with clouds disabled and 11.38 ms with
the clouds toggle enabled. These are short warmed desktop samples, not a guarantee
for every view or machine. The numerical benchmark separately exceeded 140 years/s;
atlas builds after warmup were roughly 0.1–0.3 seconds.
