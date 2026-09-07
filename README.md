# CABrush AVS 0.1

CABrush has pivoted away from mutable-triangle sculpting.

**The mesh is no longer the sculpt.**

AVS (Adaptive Volume Surface) stores the model as a sparse signed-distance
volume. Triangles are disposable render cache generated from that field.

Runtime stays intentionally tiny:

- one sphere
- Clay +
- Clay -
- Size
- Strength
- Reset
- one-finger sculpt
- two-finger orbit
- pinch zoom

No Smooth, Grab, masks, layers, import/export, DynTopo, remesh UI or character
tooling is in this milestone.

## Why AVS

The old Core 0.3.x architecture could keep a triangle mesh structurally valid,
but a sculpt brush still had to fight edge stretch, collapse quality, flips and
topology saturation.

AVS moves that responsibility out of every brush.

The source-of-truth pipeline is:

    touch/ray
      -> sparse signed-distance bricks
      -> local CSG field edit
      -> mark neighboring bricks dirty
      -> conforming volume extractor rebuilds only dirty surface chunks
      -> OpenGL renders disposable chunks

Clay never directly moves a mesh vertex.

## Sparse field

`AvsVolume` uses:

- 8x8x8 samples per brick
- signed 16-bit fixed-point distance samples
- one globally-owned lattice sample per coordinate
- positive background outside allocated bricks
- negative values inside the sculpt
- 0 as the surface
- deterministic primitive-key lookup
- hard 12,000-brick guard for the 32-bit Android prototype

At 512 samples x 2 bytes, raw SDF payload is exactly 1 KiB per allocated brick
before small Java/lookup metadata.

The initial sphere allocates only a compact brick region around its volume.
Clay+ allocates new bricks as the shape grows. Empty world space costs nothing.

## Clay

Clay is volume CSG:

- Clay+ unions a sphere-shaped field into the sculpt
- Clay- subtracts it

A touch ray intersects the SDF itself, not the generated triangles. The brush
sphere is positioned so only a controlled depth overlaps the current surface.
Repeated strokes can therefore keep extending the volume instead of exhausting
an original triangle edge budget.

## Surface cache

`AvsSurfaceCache` uses a globally conforming six-tetrahedra split per grid cell and Marching Tetrahedra.

Each dirty 8-cell brick gets a one-cell ghost apron while meshing. Adjacent
chunks read identical global SDF samples, so their boundary vertices are
computed from the same data.

Each render chunk has its own local 16-bit indices and is far below 65,535
vertices. The renderer tracks chunk revisions by brick ID, so unchanged chunks
do not need to be regenerated after a local brush edit.

The render mesh can be deleted and regenerated without losing the sculpt.

## AVS VSS

Every Android build is gated by the dependency-free AVS verifier.

The test suite checks:

- initial SDF sign and sphere raycast
- generated closed surface after weld-by-position verification
- repeated Clay+ monotonic growth without fixed-topology saturation
- Clay- volume removal
- thousands of deterministic mixed CSG stamps
- sparse brick budget
- dirty-chunk locality
- snapshot bit-exact restoration
- deterministic field replay/hash
- raycast correctness after heavy edits
- surface-chunk seam closure after heavy edits
- extraction/raycast timing telemetry
- raw field/render-cache memory telemetry
- progression screenshots and contact sheet

Artifacts:

- `dump.json` (`cabrush-avs-dump-v1`)
- `dump.txt`
- baseline screenshot
- Clay+ growth checkpoints
- Clay- result
- mixed CSG stress result
- contact sheet

`app:preBuild` depends on `vssVerify`, so an APK is not packaged when AVS
verification fails.

## Build

Requirements:

- JDK 17
- Gradle 8.7
- Android SDK 35
- Android Gradle Plugin 8.6.1

Build:

    gradle assembleDebug

Verification only:

    gradle :app:vssVerify

Artifacts:

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- VSS: `app/build/vss/report/`

## Android / ABI

AVS 0.1 is still Java + Android SDK + OpenGL ES 3.0 and contains no native
`.so` libraries, so the current core has no ABI split.

Any future native acceleration layer must preserve both:

- `armeabi-v7a`
- `arm64-v8a`

## What comes after this proof

AVS 0.1 is deliberately a single-resolution sparse field. It proves the new
source-of-truth model first.

Only after the real 32-bit phone and CI evidence are clean should we add:

1. adaptive brick resolution / detail levels
2. Smooth as an SDF filter
3. Grab as local field warping
4. higher-quality detail extraction / sharp-feature strategy
5. displacement/detail tiles for micro detail

That keeps CABrush's core simple: sculpt the field, regenerate the surface.
