# Cloud rendering smoothness — 11 November 2026

This pass increases the active cloud publication target from 5 Hz to **10 Hz**
and makes each publication cheaper. It also preserves the displayed image when
another cloud update interrupts a fade. Scientific forcing, water ownership and
physical timestep sizes are unchanged by this pass.

## Implementation

`CloudRenderer` precomputes the fixed 2× raster's tile-sampling coordinates.
Cloud water, rain, instability and winds reuse them. The two advected texture
fields share one backtrace/interpolation coordinate calculation per pixel; the
usual interior-map path avoids modulo/floor. Integer highlight samples use a
direct neighbor lookup. The appearance equations, cloud coverage and seeded
filaments remain the same; no lower resolution or optical approximation is used.

The JVM worker targets a 100 ms publication period while clouds are visible and
the simulation is running; hidden/paused publication remains 200 ms. The period
and visual elapsed time include synthesis time, avoiding cadence drift and lost
visual time. This remains one owner, one latest-only mailbox and whole numerical
transactions. This change does publish/copy weather views more often while the
physical clock advances; it is not a second simulation loop.

Cloud fades span 100 ms, matching the target cadence. Other layers retain 180 ms.
An early publication captures the current premultiplied mixture before beginning
the next fade, rather than jumping to the unfinished target. This exceptional
path blends CPU pixels once at publication, never every display frame. Finished
fades use the standard single-texture draw, bypassing the two-texture shader and
its uniforms. Unchanged blend fractions no longer invalidate the view repeatedly.
Status-string formatting runs at 10 Hz instead of display refresh rate.

## Focused evidence

Compile + **seven focused checks** passed (cloud renderer 3, real cloud evolution
1, worker animation/pause 1, fidelity transitions 2), about **3.8 s** of test time.
The six-hour/day/month raster preview has an **identical PNG SHA-256** before and
after the sampling optimization:
`322d2dad1c048a942e6de69fe21027f892c9848688e4ce432a075e661d74aa1d`.
This checks sampled appearances; it is not a complete proof for all states.

The opt-in native `rasterBlendCheck` passed **16 GPU samples**, including the new
interrupted-fade continuity case and the single-texture endpoints. Existing
pause/resume, seam transport, immutable raster and climate-to-detailed water/time
checks also pass. Reentry relative water residual remains 1.666e-14. No full suite
or seed/grid sweep was run.

One short before/after desktop pass each used only hour/s and month/s, clouds and
light enabled: 0.5 s warm-up + 2 s measured per case (five seconds of cases per
pass). Default 283 × 212 surface / 96 × 72 atmosphere, M5 Pro / 120 Hz VSync host.

| Case | Mean worker publication ms, before → after | Publications/window | Frame p95 ms | Frame p99 ms |
|---|---:|---:|---:|---:|
| 1 hour/s | 19.13 → 6.98 | 9 → 19 | 10.30 → 9.81 | 11.29 → 10.18 |
| 1 month/s | 20.58 → 7.57 | 9 → 19 | 10.33 → 9.35 | 10.41 → 9.90 |

Worker publication includes view copies/raster work, so this does not isolate
cloud synthesis alone. The improvement is approximately **2.7×** per publication
in these windows while observed publications increase from roughly 4.5/s to 9.5/s.
Short-window actual rates were 1.01 → 0.97 hours/s and 31.34 → 31.50 days/s;
commit quantization and run order prevent a strong throughput comparison.

The main-thread cloud-copy/conversion maximum rose from about 0.20–0.23 ms to
**1.05–1.06 ms**, including early-fade capture. That path trades occasional CPU
work for continuity. Overall p95/p99 improved here, but the local maximum is a
remaining tuning opportunity. No all-frame 100 FPS or long-run memory claim.
Measurements are updater-to-updater intervals, not GPU present timestamps; JIT,
OS scheduling and ordering are uncontrolled. Existing backend startup warnings
remain, and both measured runs completed normally.

A further normal game launch was attempted for manual inspection. The desktop
API listed the Java process but could not attach to it by either reported bundle
ID or name; that launch was stopped. Therefore manual motion/panning assessment
was **not completed**. The appearance preview and actual GPU interpolation checks
are the available visual evidence, not user aesthetic acceptance.

Artifacts are in `build/experiments/cloud-smooth-before/` and
`build/experiments/cloud-smooth-after/` (CSV/environment, source fingerprint and
focused results). Build artifacts are not versioned. The checks are optional
reproduction, not additional runtime/startup gates:

```sh
gradle compileKotlinJvm jvmTest --tests 'terra.render.CloudRendererTest' --tests 'terra.render.CloudEvolutionTest' --tests 'terra.simulation.CloudAnimationWorkerTest' --tests 'terra.simulation.FidelityTransitionTest' rasterBlendCheck
gradle runJvm -PweatherModel=dynamical
```
