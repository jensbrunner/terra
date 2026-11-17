# Keeping continents continental

## Implemented on `feat/plates`

The material model now replaces the original height-only prototype. Crust is stored in persistent plate-local cells; each geological tick projects those cells into world space without sampling the previous global elevation raster. Cells retain continental rock, basalt, sediment, area, age, and plate-local relief. Plate translation and rotation move the material itself.

Elevation follows a simple Airy-style buoyancy calculation using continental/basalt/sediment densities of 2,800/3,000/2,400 kg/m³ against 3,300 kg/m³ mantle, including ocean water loading and age-dependent ocean cooling. Initial column thickness/relief reproduces the generated geography. Continental interiors have their own buoyant roots instead of depending on repeated boundary uplift.

Boundary operations now have material budgets:

- Continental collision removes horizontal area while retaining rock, thickens roots across a 300 km belt, and spreads over-thick roots laterally. Linear and angular collision impulses resist continued penetration.
- Oceanic subduction consumes oceanic area and basalt; buoyant fragments and sediment accrete onto the overriding margin. Older oceanic material descends when both sides are oceanic. Trench bending lies seaward of the margin, while volcanic additions are placed landward.
- Openings stretch existing continental material into thinner columns while conserving that rock. Once those columns are too thin to bridge the opening, fresh oceanic basalt fills it. New area is limited by the planet's actual area deficit after shortening/subduction, preventing interpolation undershoots from manufacturing crust.
- Transform slip has no intrinsic rock source, sink, or vertical forcing.
- Sustained boundary opening supplies a coarse tensile-strain proxy. Sufficiently extended plates can split along its principal direction; sustained continental contacts can weld. Consumed plates retire, and inactive storage slots are reused. The active-plate limit bounds computational cost rather than prescribing land area.
- Rainfall, slope, and drainage catchment affect erosion. Eroded material becomes sediment with the correct density conversion, follows downhill drainage, and deposits in lowlands/basins/shelves. Both bedrock loss and sediment loading affect buoyancy.

Rock mass is accounted for as continental + basalt + sediment mass. The exact accounting relation is `current = initial + mantle additions - recycled basalt`; continental accretion and sediment movement are transfers within that budget. There is no target land percentage.

The plate overlay (key **6**) distinguishes collision, subduction, spreading, rift, and transform zones. Climate and hydrology still rebuild after geography changes. Static climate noise is cached, and plate projections allocate field storage only for covered cells to keep memory and update costs bounded.

### Verification of this implementation

The existing 13-test suite passes. Additional diagnostic harnesses verified:

- 1,000 rigid-drift ticks preserve continental material exactly, with about 2e-16 elevation error against a single interpolation of the initial relief.
- Controlled collisions, rifts, subduction polarity, trench/arc supply, transform slip, splitting, welding, and density-aware erosion/deposition obey their material budgets.
- Three 85 × 63 worlds remain continental after 100 million effective geological years: continental-rock volume is approximately 99–100% of its initial value; land area retains approximately 87–97%. Mass-budget error remains below 1e-9 relative to initial mass, including explicit mantle sources/sinks.
- The default 283 × 212 world also passes 100 million effective geological years: land area changes from 21,572 to 20,435 cells (94.7% retained), continental rock retains 99.6% of its initial volume, and the material budget stays within 1e-9 relative error. Geometry diagnostics are in `build/reports/plates/initial-geography.png` and `build/reports/plates/geography-100Ma.png`.
- Time batching is deterministic; older snapshots stay immutable; land masks, climate, and rebuilt drainage agree. Max pause and regeneration work. The full default-size worker, including annual ecology/water and regular snapshots, measured approximately 570 simulated years/sec.

This remains a coarse 2D kinematic model with empirical deformation, accretion, and fracture rules. It does not solve mantle convection or spherical plate dynamics. Hotspots are optional future work. The original diagnosis and design requirements below are retained as historical context, rather than a description of the replacement model.


## Diagnosis of the original prototype

The initial terrain and the evolving terrain follow different rules. `TerrainGenerator` builds broad continental regions, then adds blended ranges and noise. `PlateTectonics` subsequently transports the resulting elevation raster and applies boundary height changes. It does not preserve the generator's continental blocks as material objects.

The screenshots are consistent with that mismatch: broad continental interiors disappear while narrow, elevated boundary belts survive.

### What already exists

- Moving plate centres and a warped, nearest-centre plate partition.
- Relative normal velocity to distinguish convergence from divergence.
- Collision uplift, an ocean/continent overriding heuristic, trench lowering, and ocean/ocean arc uplift.
- Rift lowering and replacement with young oceanic crust.
- Age-dependent ocean cooling, local erosion, and sediment deposition.
- Climate, vegetation, and drainage updates after geography changes.

Adding another generic convergence multiplier would reinforce the surviving ribbons. The existing mechanisms need better material accounting and spatial structure.

### Why the continents disappear

1. **Plate boundaries do not follow crust.** Each tick reconstructs ownership from moving centres. A tile can change plate because a Voronoi region expands, without an explicit collision, subduction, or spreading event. Centres continue through each other; there is no plate consumption or collision response.
2. **Transport mixes continental material into oceanic material.** Elevation, the continental indicator, and age are repeatedly bilinearly sampled from the previous global raster. Sampling can cross plate boundaries. This smooths coastlines and does not conserve continental volume under the discontinuous velocity field. Relief is resampled every tick rather than remaining attached to a rigid plate.
3. **Rifting has a one-way continental sink.** Divergence multiplies the continental indicator by `1 - replaced` and pulls elevation toward a submerged target. It acts across a broad boundary band, without tracking cumulative stretching or waiting for an actual opening. There is no corresponding crustal thickening or continental accretion budget.
4. **Uplift changes height, not crustal thickness.** Convergence can keep a thin strip above sea level, but does not build a broad, buoyant continental root. Ocean/ocean overriding uses plate ID rather than age or density. Trench and arc effects share the same boundary envelope instead of occupying distinct positions.
5. **Continental buoyancy is missing.** Height is an independent field. The continental indicator modulates cooling, but does not determine a stable continental elevation through thickness and density. Once an interior is lowered, there is little restoring mechanism.
6. **Erosion is limited, but is not the main culprit.** It transfers material to one lower neighbour, without explicit sediment thickness or isostatic unloading. It can flatten relief, but removing erosion did not preserve broad continents in the experiments below.

## Controlled experiments

Seed 31742, 141 × 106 cells, uniform 1,000 mm/year precipitation, 1,000 geology ticks at the old 100× rate: one million simulated years, or 100 million effective geological years. Each variant starts from the same generated world. These are mechanism-isolation experiments, not predictions of real continental area.

| Variant | Land area retained relative to start | Continental-indicator sum retained |
| --- | ---: | ---: |
| All current mechanisms | 62% | 32% |
| Erosion disabled | 55% | 32% |
| Boundary deformation disabled | 91% | 83% |
| Raster transport disabled | 99% | 50% |

The baseline also has about one third of the land-area-to-coast-edge ratio of the no-boundary-forcing variant: substantially thinner land shapes. The indicator sum is not a physical mass measurement—the absence of such a budget is part of the problem. The results implicate the interaction between transport and boundary deformation, rather than excessive erosion alone.

## Original design requirements

### 1. Preserve material and buoyancy first

Keep continental and oceanic crust as persistent material attached to plate-local coordinates. Translate/rotate those coordinates and rasterize for display, rather than repeatedly resampling the previous display raster. Resolve overlaps and gaps explicitly. A plate may contain both crust types.

Track crustal thickness/volume, oceanic age, and a separate sediment layer. Derive broad elevation from thickness, density, and thermal buoyancy, with smaller relief superimposed. Erosion redistributes bedrock into sediment and permits isostatic rebound; it should not silently turn continental crust into oceanic crust.

This is the highest-value change for preserving coherent continents. It does not require simulating mantle convection or imposing a target land percentage.

### 2. Give each boundary a specific material rule

- **Continent–continent convergence:** retain buoyant continental material, shorten and thicken it, and spread deformation into a broad orogenic belt. Slow or redirect relative motion rather than allowing centres to pass through one another indefinitely.
- **Ocean–continent subduction:** consume incoming oceanic crust; place a trench on that side, an accretionary wedge near the margin, and a volcanic arc farther into the overriding plate. Continental interiors remain buoyant.
- **Ocean–ocean subduction:** choose the descending side using age/density; allow volcanic additions to build island arcs and eventually accreted continental material.
- **Continental rifting:** conserve material as the crust stretches and thins. After rupture, create young oceanic crust in the actual opening, with rift shoulders and passive margins on the surviving fragments.
- **Ocean spreading:** fill newly opened area with young basalt, then let it cool and subside as it moves away from the ridge.
- **Transform boundaries:** offset existing material tangentially, without a generic crust source, sink, or uplift term.

Boundary widths and offsets must use physical distance and resolution, rather than a fixed centre-distance falloff or a three-cell probe. At the default resolution, some narrow features will need to be represented by a combined marginal belt.

### 3. Add plate lifecycle and landscape finishing

Consume exhausted oceanic plates, merge/accrete appropriate fragments, and split plates when sustained extension creates a new spreading system. Add coarse collision feedback to velocities. These can remain infrequent kinematic updates; a full force or mantle solver is unnecessary for this toy.

Then improve drainage-guided erosion, sediment delivery to basins and continental shelves, and optional hotspots. Adding more random height noise before fixing material transport would hide the failure rather than fix it.

## Verification gates

- Pure rigid drift preserves continental volume and outline, apart from rasterization error.
- Continental collision thickens crust rather than deleting continental material.
- Subduction consumes oceanic material and places trench/arc effects on the correct sides.
- Spreading creates only the area actually opened; transform motion creates none.
- Erosion/deposition conserves sediment and produces the expected buoyancy response.
- Long multi-seed runs retain broad continental interiors without prescribing a land-area quota.
- Compare morphology at equal effective geological ages and multiple raster resolutions, alongside Max throughput and snapshot costs.

## Speed change implemented now

The multiplier is now **1,000×**, with **100 simulated years per geology tick**. Previously it was 100× with 1,000-year ticks. Both configurations advance 100,000 effective geological years per tick, so the toy evolves ten times faster per calendar year without increasing each integration jump. All UI labels were updated. The material-model replacement is now implemented as described above.

## Geological references

- [USGS: Understanding plate motions](https://pubs.usgs.gov/gip/dynamic/understanding.html) — spreading, subduction, continental collision, and transform boundaries.
- [NPS: Convergent plate boundaries](https://www.nps.gov/subjects/geology/plate-tectonics-convergent-plate-boundaries.htm) — trenches, accretionary wedges, volcanic arcs, and broad collisional ranges.
- [NPS: Passive continental margins](https://www.nps.gov/subjects/geology/plate-tectonics-passive-continental-margins.htm) — crustal thickness and continental/oceanic elevation differences.
