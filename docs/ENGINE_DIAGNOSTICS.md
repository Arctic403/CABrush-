# PocketSculpt Deep Engine Diagnostics

PocketSculpt carries an engine-level diagnostic flight recorder designed for AVS, sculpt-input,
surface-extraction, GPU and Android failures that are difficult to reproduce from a screenshot.

## Master switch

The entire runtime diagnostic system is controlled by one property in the root `gradle.properties`:

    cabrush.devMode=on

Development:

    cabrush.devMode=on

Final/production release:

    cabrush.devMode=off

`app/build.gradle` converts this to the compile-time `BuildConfig.ENGINE_DIAGNOSTICS` constant.
When it is off:

- the diagnostic writer is not created
- the uncaught-exception dump hook is not installed
- the Dump UI button is absent
- no automatic dump listener is installed
- engine diagnostic record/counter/gauge/state calls return immediately
- no diagnostic files are written

The AVS VSS remains independent and still runs at build time. Turning runtime diagnostics off does
not weaken the build-time Surface Truth gate.

## What is recorded

The in-process recorder retains the newest 65,536 events and also maintains cumulative counters,
latest gauges and current state.

### Android / process

- app version and diagnostic mode
- device manufacturer/model/device
- Android API and release
- supported ABIs
- display size and density
- Java heap used/total/max
- native heap allocated
- system available-memory/low-memory state
- thermal status on supported Android versions
- process uptime
- lifecycle transitions
- all Java thread stacks at dump time
- uncaught exception stack trace

### Touch / GL queue

- every MotionEvent action
- pointer ids/count
- x/y
- pressure and touch-size values
- historical sample count
- event/down timestamps
- pinch scale/focus/span
- every queued GL command
- UI -> GL queue wait time
- automatic queue-stall detection

### Stroke sampler

- stroke id
- begin/end field hashes
- brush mode/radius/strength
- raw screen samples
- brush spacing
- resampled segment distance and step count
- every dab screen coordinate
- every screen ray origin/direction
- every AVS surface hit position/normal/t
- movement from the previous hit
- hit-t change
- normal excursion from the first hit
- changed/unchanged dab result
- changed/tested samples
- touched/allocated bricks
- effective clay depth
- per-dab duration
- ray misses
- automatic Clay+ self-extrusion detection

### AVS field / CSG

- sphere creation timing
- field version
- brick count/budget
- brick allocations
- raycast calls, march steps and refinement steps
- raycast hit/miss reason and duration
- ellipsoid and sphere CSG operations
- tested/candidate/changed sample counts
- touched/allocated bricks
- CSG timing
- snapshot restore timing
- automatic slow-raycast, slow-brush, sample-spike and brick-growth detection

### Surface extraction

- dirty brick count before rebuild
- every rebuilt chunk coordinate/id
- per-chunk vertices/triangles/estimated bytes/timing
- total chunks/vertices/triangles/estimated bytes
- rebuild pass timing
- rebuilt bytes
- field/surface versions
- automatic slow-rebuild and triangle-growth detection

### Renderer / GPU

- GL vendor/renderer/version/GLSL version
- viewport
- front-face/cull contract
- frame count
- frame render and interval timing
- 30-frame average/max timing
- draw calls and indices drawn
- GPU chunk upload count/bytes/timing
- GL error codes/stages
- camera orbit/zoom state
- matrix inversion failures
- automatic frame-stall and slow-GPU-sync detection

## Automatic dumps

Anomalies request a dump automatically. Repeated instances of the same anomaly are rate-limited so
one bad frame cannot create hundreds of folders.

Current automatic triggers include:

- Clay+ self-extrusion
- raycast taking too long
- brush/CSG taking too long
- excessive sample mutation
- rapid AVS brick growth / budget exhaustion
- surface extraction taking too long
- extreme triangle growth
- GL command queue stalls
- frame stalls
- GPU sync stalls
- OpenGL errors
- matrix inversion failure
- uncaught Java exceptions

The DEV top bar also exposes `Dump` for a manual capture.

## Dump location and contents

Runtime dumps are written under the app-specific external files directory when available, with an
internal-files fallback:

    diagnostics/PocketSculpt-<timestamp>-<reason>/

Each dump contains:

- `dump.json` — machine-readable summary, counters, gauges and current engine state
- `events.jsonl` — chronological retained flight-recorder events
- `threads.txt` — every Java thread and stack at capture time
- `system.txt` — readable state/counter/gauge summary
- `crash.txt` — full exception stack for crash-triggered dumps only

`diagnostics/latest.txt` points to the newest completed dump.

## Design rule

Diagnostics observes the engine; it does not change sculpt output. In particular, the
`clay_self_extrusion` detector records and dumps the exact ray/dab sequence but does not clamp,
smooth, reproject or otherwise hide the underlying Clay+ behavior. That keeps a captured failure
useful for fixing the real engine path rather than the diagnostic layer masking it.
