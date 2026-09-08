# CABrush AVS 0.1.1 — Surface Truth

CABrush uses **AVS (Adaptive Volume Surface)**: the sculpt is a sparse signed-distance field and triangles are disposable rendering cache.

AVS 0.1.1 deliberately freezes feature work and hardens one thing only:

> **field -> surface conversion must stay closed, consistently wound and deterministic under Android back-face culling.**

Runtime remains intentionally tiny:

- sphere
- Clay+
- Clay-
- Size
- Strength
- Reset
- one-finger sculpt
- two-finger orbit
- pinch zoom

No Smooth, Grab, masks, layers, import/export, adaptive detail, character tools or new brushes are added here.

## Surface Truth contract

The field convention is fixed:

- negative SDF = inside
- positive SDF = outside
- zero crossing = visible surface

The renderer contract is fixed:

- generated faces point toward increasing SDF (outside)
- front face = CCW
- cull face = BACK

`SculptRenderer` explicitly sets that OpenGL state. VSS renders its screenshots using the same front/back visibility rule, so a winding regression appears as a hole in CI evidence instead of being hidden by a two-sided debug renderer.

## Extractor

`AvsSurfaceCache` still uses a globally repeated six-tetrahedra Freudenthal decomposition of every grid cube.

AVS 0.1.1 changes the surface conversion rules substantially:

1. **Winding no longer depends on shading normals.**
   Each tetrahedron computes the exact gradient of its linear scalar interpolant. Because AVS uses positive-outside SDF values, that gradient is the deterministic outward direction used to orient every emitted triangle.

2. **Chunk seam interpolation is canonical.**
   Every crossed lattice edge is ordered by global lattice coordinates before interpolation. Neighboring chunks therefore perform the same floating-point expression on the same endpoint values and produce bit-identical seam positions.

3. **Tiny legal triangles are retained.**
   A surface passing extremely close to a quantized lattice sample can legitimately create microscopic triangles. Dropping them created real pinholes. Only truly zero/invalid-area faces are rejected now.

4. **Normals cannot decide topology.**
   Triangle winding comes only from field truth. Shading normals use the SDF gradient when it agrees with the oriented geometric surface and fall back to geometric normals near CSG creases.

The render mesh remains disposable. It can be thrown away and reconstructed from `AvsVolume` without losing the sculpt.

## Surface Truth VSS

Every Android build is hard-gated by `vssVerify`.

The VSS now tests both **field truth** and **surface truth**. Surface verification welds chunk boundaries conceptually and checks:

- boundary edges = 0
- non-manifold edges = 0
- shared directed edges have opposite orientation
- duplicate triangles = 0
- degenerate triangles = 0
- seam positions are bit-identical where the same surface vertex is shared
- signed surface volume has the expected orientation in known closed-solid tests
- SDF inside/outside sampling agrees with face orientation within the tetra-vs-trilinear approximation tolerance
- shading normals do not broadly oppose their triangles

The verifier also contains negative controls: it intentionally flips one triangle and removes one triangle from a known-good sphere and must detect both the winding error and the crack. A broken verifier therefore cannot silently approve itself.

### Stress scenarios

VSS 0.1.1 runs:

- baseline sphere surface truth
- verifier negative controls
- CSG exactly on/near brick planes and corners
- a resolvable thin feature crossing many bricks
- 240 repeated Clay+ operations with culling-on checkpoints
- Clay- carve smoke test
- 2,000 hostile mixed CSG operations with progressive surface audits
- incremental dirty-chunk extraction vs a fresh full rebuild (surface SHA-256 must match)
- bit-exact snapshot restore for both field and extracted surface hashes
- deterministic field + surface replay
- dirty-chunk locality
- repeated destroy/rebuild-equivalent cache construction
- raycast and full-extraction performance smoke tests

## Evidence

VSS emits:

- `dump.json` with schema `cabrush-avs-surface-truth-dump-v2`
- `dump.txt`
- culling-on screenshots for baseline, seam torture, thin features, Clay+, Clay-, and hostile CSG
- `99-contact-sheet.png`

The dump records field hashes, surface hashes, boundary/non-manifold/directed-edge counts, duplicate/degenerate counts, seam mismatches, orientation diagnostics, signed volume, memory and timing telemetry.

## Sparse field

`AvsVolume` remains AVS sculpt truth:

- 8x8x8 samples per brick
- 16-bit signed fixed-point SDF samples
- 512 samples = exactly 1 KiB raw SDF payload per brick
- `VOXEL_SIZE = 0.045`
- missing brick = positive/outside background
- hard 12,000-brick guard for the 32-bit prototype

Clay+ and Clay- modify the field with CSG. They never move triangle vertices.


## Deep runtime diagnostics

PocketSculpt now has an engine-level diagnostic flight recorder for Android development. The master
switch is intentionally one line in root `gradle.properties`:

    cabrush.devMode=on

Flip it to:

    cabrush.devMode=off

for final/production builds. When off, the runtime dump writer, crash hook, automatic anomaly dumps,
DEV Dump button and event recording stay disabled. The build-time Surface Truth VSS still runs.

With dev mode on, the recorder follows input -> GL queue -> screen ray -> AVS hit -> Clay CSG ->
dirty bricks -> surface extraction -> GPU upload -> frame timing. It also records device/memory/
thermal state and can automatically dump on Clay self-extrusion, frame/queue/raycast/rebuild stalls,
GL errors, runaway brick/triangle growth and uncaught exceptions.

See `docs/ENGINE_DIAGNOSTICS.md` for the dump schema and complete coverage.

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

GitHub Actions uploads VSS evidence even when verification fails. The APK artifact is uploaded only when VSS and Android compilation succeed.

## Android / ABI

AVS 0.1.1 is still Java + Android SDK + OpenGL ES 3.0 with no native `.so` libraries, so there is no native ABI split yet.

Any future native acceleration layer must preserve both:

- `armeabi-v7a`
- `arm64-v8a`

## Next gate

Do not tune Clay or add another sculpt feature until:

1. AVS 0.1.1 is green in GitHub Actions.
2. The uploaded Surface Truth screenshots/dump are inspected.
3. The same long extrusion and abusive add/subtract sessions render solid on the real 32-bit Android device with back-face culling enabled.
