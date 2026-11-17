# Active plate toy

Crust is persistent material in plate-local columns. A disposable world raster
projects it for collisions, elevation, drainage, vegetation transport, and display.
A connected territory map governs plate ownership, contacts, and spreading.
Both axes wrap; climate latitude and daylight remain stylized presentation.
There is no mantle-flow solver or target land percentage.

## Motion and lifecycle

- Geological updates run every 100 simulation years at 1,000× effective time.
  Max still advances annual steps without a frame budget; snapshots remain 5 Hz
  and the desktop render target remains 100 fps.
- Seed, persistent plate identity, and 8-million-year epochs determine translation
  targets of 3–9 cm/year and spin targets within ±0.2°/million years. Actual motion
  eases toward the drive over 2 million years. Its area-weighted translation is
  removed each update. Collision impulses temporarily resist the actual motion.
- Contact creates shortening and roots before any identity merger. Ordinary
  welding requires uninterrupted current contact for 8 million years and
  shortening of 5% of the smaller plate. Separation clears the history. Ordinary
  welds must fit within 80% of each world period and retain at least six plates.
- Opening boundaries accumulate a plate-local tensile tensor. Fracture candidates
  have 12% extension, are 40 million years old, or are oversized; ages above
  60 million years take priority. Two rooted growth fronts start at area-weighted
  quarter points along principal tension, falling back to the longest material
  axis. Growth is faster across the plate than along tension. Thick continental
  crust is stronger; seeded material weakness bends the separating interface.
  The main weakness scale is about 850–1,270 km, with a finer second octave.
  Eight growth biases are tried; the most balanced legal connected partition wins.
  Each child retains at least 20% of its parent and 1% of the planet. Cutting
  changes neither columns nor poses. The cached weakness travels with material.
- Daughters inherit motion and receive opposite 3 cm/year spreading components
  for 8 million years. Ordinary fractures are at least 8 million years apart.
  After 12 million years without meaningful deformation or fracture, a legal
  fracture receives priority. Population recovery can fracture sooner below six.
- Every regenerated world starts with eight seeded sites on a jittered 4×2
  periodic grid. Plate count is independent of the continent-scale slider, which
  still controls terrain noise and warping. Evolution allows 6–10 active plates.
  At the limit, fracture makes room by conservatively consolidating the smallest
  touching oceanic plate, otherwise the smallest touching pair. This population
  operation is recorded separately from welding.
  Oversized bodies continue translating; rotations whose bounding boxes could
  overlap periodic images are deferred pending breakup.

## Connected territories and capture

Projection initially chooses the strongest area contribution at each tile. For
each plate, keep its largest four-connected toroidal component as a core. Grow
all cores simultaneously through detached patches and uncovered tiles, preferring
shortest distance, then greater local projected coverage, then stable plate ID.
Every acquired tile attaches to an existing region, so territories stay connected.

Detached columns and material stranded beyond a one-tile boundary fringe are
captured locally. Collect transfers before applying them, changing each column's
frame once per batch. Continental rock, basalt, sediment, provenance, area, and
area-weighted age, relief, and weakness are preserved. Reprojection after capture
retains the connected territory that authorized the transfer; it does not
immediately choose new ownership from the changed interpolation weights.
Exhausted plates retire through the population lifecycle. This local material
capture is an intentional exception to whole-plate weld timing and has no mantle
ledger entry. It uses the existing grid-scale resampling approximation.

New crust belongs to its coherent birth territory. Creation is restricted to
opening interfaces and adjacent deficit tiles; unused area budget remains pending.
Internal deformation events remain available for diagnostics. The published
boundary layer is rebuilt only at current west/north ownership interfaces. At a
junction, the incident interface with greatest normal relative speed sets its type;
west wins an exact tie. This keeps the overlay thin and avoids colouring interiors.

## Rock accounting

Densities are 2,800 kg/m³ for continental rock, 3,000 for basalt, 2,400 for
sediment, and 3,300 for mantle. Buoyancy includes ocean cooling and water loading.
Shortening builds crustal roots; lateral root flow redistributes thick crust.
Rifting redistributes continental rock before exposing mantle-derived basalt.

Subduction accretes 90% of incoming continental fragments and 50% of sediment.
The rest, plus consumed basalt, is recycled. Arc additions equal 15% continental
and 25% basalt mass of consumed basalt. These additions and recycling are explicit
ledger entries. Sediment tracks its continental/basalt provenance and consolidates
back into that rock over 20 million years, with density conversion preserving mass.
Rainfall/slope/catchment erosion is three times the previous rate; it transfers
rock into sediment and deposits it downstream rather than fading elevations.

The inventory equation is:

`rockMass = initialRockMass + mantleAddedMass - mantleRecycledMass`

Internal diagnostics expose relative RMS drift, shortening, subduction, rifting,
spreading, cumulative locally captured mass, fracture/merger/watchdog counts, and
the latest 256 lifecycle events.
The status bar shows moving/total plates; key 6 retains typed boundaries.

## Verification

Regression coverage includes periodic seams and corners, conservative roots,
erosion, recycling, time batching, immutable snapshots, weld timing, daughter
separation, and inactivity recovery. Added checks cover detached-patch capture
and preserved material moments, stable gap ownership, pending spreading budgets,
deterministic weakness-sensitive jagged cuts, connected territories, and colours
restricted to actual interfaces.

| Check | Result |
| --- | --- |
| Compile and JVM suite | Passed; 31 tests |
| Seeds 31742, 42, 123 at 60×44 | 500 million effective years each |
| Default seed 31742 at 283×212 | 300 million years; 37 fractures, 35 mergers/consolidations |
| Every checked geology tick | One connected territory per active plate; 6–10 plates; zero off-interface boundary colours |
| Activity | No inactive 20-million-year interval; continued relative motion |
| Worst relative mass error | 1.43e-13 (required <1e-8) |
| Minimum default-world relative RMS drift | 2.32 cm/year |
| Short Max worker benchmark, median of three runs | 661 vs 673 years/sec before (1.8% lower; limit 20%) |
| Coupled time batching, immutable snapshots, pause and regeneration | Passed |
| Terrain and overlay images | Inspected separately at generation, 25, 100, and 300 million years |

Long-run checks inspect connectivity, population, interface colours, and the mass
ledger every tick, plus activity in every 20-million-year window. Preview rasters
are in `build/reports/tectonics/`. Benchmarks include normal worker snapshots;
throughput varies with seed and geological age. Max remains unthrottled, snapshot
publication remains separate, and the desktop render target remains 100 fps.

This is a driven material toy with raster collisions and approximate strength.
Broad terrain structures and coherent plate regions persist in the inspected runs.
Some grid-scale coastal and seabed ribbing remains; jagged two-way rifts are the
fracture model for this iteration.
