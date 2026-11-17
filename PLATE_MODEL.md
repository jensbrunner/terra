# Active plate toy

Crust is persistent material in plate-local columns. A disposable world raster
projects it for collisions, elevation, drainage, vegetation transport, and display.
A connected territory map governs plate ownership, contacts, and spreading.
Both axes wrap; climate latitude and daylight remain stylized presentation.
There is no mantle-flow solver or target land percentage.

## Motion and lifecycle

- The game defaults to 10,000× geological time with an update every 100 calendar
  simulation years (one million effective years per update). Settings → Simulation
  provides live sliders for 1,000–10,000× speed and 10–100-year update intervals.
  Smaller intervals trade CPU time for finer steps. These controls do not rebuild
  the world, change the calendar playback speed, or advance a paused simulation.
  Pending per-year time credits preserve rate changes chronologically. Regeneration
  keeps the live controls. Direct solver calls retain a 1,000× reference default;
  the running game's scheduler passes its selected effective rate explicitly.
- Max still advances annual steps without a frame budget; snapshots remain 5 Hz
  and the desktop render target remains 100 fps.
- Motion follows a cheap, overdamped boundary-force balance, with a two-million-year
  response. Initial seeded velocities initialize ridges and trench polarity; their
  headings are never randomly rerolled. Fixed basal traction contributes one half
  of the initial 3–9 cm/year translation and one quarter of its spin. Ridge push and cooling-dependent slab pull supply
  the remaining translation and torque. These are velocity-equivalent force proxies,
  not a calibrated mantle-flow calculation. Translation targets are capped at
  12 cm/year and spin at ±0.2°/million years. Area-weighted translation is removed.
- Each touching pair remembers spreading versus subduction and slab polarity.
  Trench slabs start at a proxy depth of 200 km, grow with actually consumed area
  up to 700 km, and lose support over five million years once the entire contact
  becomes continental. Pull acts only on incoming oceanic crust. Five million years
  of sustained convergence can reverse a spreading contact into subduction.
  Continental contacts add viscous resistance, allowing sustained shortening without
  elastic bouncing. Relaxation slows for stiff contacts to avoid alternating velocity
  corrections at million-year steps. Forces and area-dependent drag act about material centroids. Separation or a
  topology-changing merger/fracture resets the affected pair histories.
- Contact creates shortening and roots before any identity merger. Ordinary
  welding requires uninterrupted current contact for 8 million years and
  shortening of 5% of the smaller plate. Separation clears the history. Ordinary
  welds must fit within 80% of each world period and retain at least six plates.
- Opening boundaries accumulate a plate-local tensile tensor. Ordinary fracture
  requires 12% extension and at least 40 million years since creation or merger;
  age alone never causes breakup. Oversized bodies and population/activity recovery
  can fracture sooner. Two rooted growth fronts start at area-weighted
  quarter points along principal tension, falling back to the longest material
  axis. Growth is faster across the plate than along tension. Thick continental
  crust is stronger; seeded material weakness bends the separating interface.
  The main weakness scale is about 850–1,270 km, with a finer second octave.
  Eight growth biases are tried; the most balanced legal connected partition wins.
  Each child retains at least 20% of its parent and 1% of the planet. Cutting
  changes neither columns nor poses. The cached weakness travels with material.
- Daughters inherit basal traction and motion and receive opposite 3 cm/year
  spreading components for 20 million years, long enough to establish an opening
  boundary. Ordinary fractures are at least 8 million years apart.
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

Collision and subduction are resolved before cleanup capture, so overlapping
incoming crust first has an opportunity to shorten or enter the recycling ledger.
Detached columns and material stranded beyond a one-tile boundary fringe are then
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

Rotated material footprints are normalized so each column's interpolation weights
sum to one. The same weights gather inventories and distribute transfers, avoiding
sediment overdraw and artificial mass when a projected column is depleted. Footprint
weights are cached per pose and recalculated only for moved or newly created columns.

The inventory equation is:

`rockMass = initialRockMass + mantleAddedMass - mantleRecycledMass`

Internal diagnostics expose relative RMS drift, shortening, subduction, rifting,
spreading, cumulative locally captured mass, fracture/merger/watchdog counts, and
the latest 256 lifecycle events.
The status bar shows moving/total plates; key 6 retains typed boundaries.

## Live controls and interface

The larger interface uses 18-point control labels, 44-pixel main buttons, higher
contrast secondary text, and a responsive header. The sidebar separates Map layers
from Tile details; selecting a tile opens its inspector. Settings separates live
Simulation controls from Landforms/Advanced drafts that require rebuilding.
A smaller virtual-size baseline keeps text legible at a 1,100×800 window. Rendered
checks cover normal and compact layouts, settings, inspection, and help; slider
interaction was verified against the paused worker without changing generation,
calendar time, or geography revision. Slider tooltips are disabled to prevent
orphan labels over the map. Captures are in `build/reports/ui/`.

## Verification

The JVM suite checks seams, connected territories, capture, rifting, roots,
recycling, chronology, immutable snapshots, and conservative projection. Additional
regressions cover unchanged drive across the former eight-million-year epoch,
absence of age-only fracture, stronger pull from colder slabs, unsupported territory
at a collision, and sustained ocean closure followed by joined continental land and
root growth. A controlled 60×36 fixture retains a land connection for at least 15 of
its final 20 million-year steps and continues shortening after ocean closure.

| Check | Result |
| --- | --- |
| Compile and JVM suite | Passed; 39 tests |
| 10,000×, 100-year interval, seeds 31742/42/123 at 60×44 | 500 million effective years each |
| 10,000× default seed at 283×212 | 300 million effective years |
| Every checked geology tick | Connected territories, 6–10 plates, interface-only boundary colours, continued activity |
| Worst relative mass error | 4.59e-14 (required <1e-8) |
| Default-world RMS relative drift | At least 1.89 cm/effective year; 4.89 cm/year at 300 million years |
| Directional persistence over 40 million years | Net/path ratio 0.90–0.94, versus 0.53–0.57 before |
| Paired three-run median Max calendar throughput | 532 yr/sec before; 644 yr/sec after (21% higher) |
| Geological progress in equal seven-second fresh-worker windows | Median 32 vs 36 million effective years |
| Live worker settings | Pause preserved; changes acknowledged; settings retained after regeneration |
| Terrain and plate images | Inspected at generation, 25, 100 and 300 million years |

Directional persistence uses three 60×44 seeds with ordinary fractures disabled to
isolate the motion policy; normal lifecycle tests keep fractures enabled. Throughput
uses alternating fresh JVM workers on the same machine, with the same default seed,
10,000× multiplier, 100-year interval, unthrottled Max, and seven-second sampling
windows. These include climate/water work and snapshot publication. This is a short
fresh-world benchmark, not a guarantee of the same speed in older configurations.
Detailed logs and terrain/plate images are under `build/reports/tectonics/`.

Large steps remain a deliberate accuracy tradeoff. A separate 500-calendar-year
interval test at 10,000× failed mass accounting, so the live control is bounded
at 100 years, at most one million effective years per update. The accelerated
rate is not a ten-substep reproduction of the reference simulation: collision
and erosion caps affect how deformation is resolved. Grid-scale coastal and
seabed ribbing can remain. Max is unthrottled and rendering stays separate.
