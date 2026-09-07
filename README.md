# CABrush Core 0.3.1

CABrush Core 0.3.1 is a **geometry-truth hardening milestone**. Runtime scope stays intentionally tiny: one sphere, Clay+, Clay-, Size, Strength, Reset, orbit, and pinch zoom.

The goal of this patch is not to make more brushes. It is to stop the engine from calling visibly broken geometry "valid".

## What Core 0.3.1 changes

### Geometry truth
`GeometryValidator` now separates structural validity from sculpt-safe geometry.

It still verifies:
- finite coordinates
- valid vertex/face references
- nondegenerate triangles
- duplicate faces / directed edges
- closed two-manifold incidence
- half-edge twin consistency
- 32-bit resource guards

It now also enforces:
- stronger triangle-quality and edge-ratio floors
- bounded face-normal change
- bounded per-topology-edit surface area drift
- bounded per-topology-edit closed-volume drift
- local self-intersection rejection for changed topology
- closed-manifold edge-collapse link condition
- placement-quality / surface-envelope rejection reasons

### Topology operators
`GeometryOps` is now conservative by design.

- `splitEdge()` preserves the piecewise-linear surface and rejects pathological child triangles.
- `collapseEdge()` requires the closed-manifold link condition, only accepts short remeshing-style edges, and scores keep/remove/midpoint placements using local triangle quality and normal preservation.
- `flipEdge()` is allowed to decline. It only commits when the new diagonal preserves or improves local conditioning and stays inside a bounded normal/edge envelope.
- topology transactions run the full structural validator plus the geometry-transition validator before commit.

This follows the important remeshing distinction: topology legality alone is not enough. A mutation also has to preserve the geometric embedding.

### Clay smoke consumer
Clay remains a **test consumer**, not a production brush.

The Core 0.3 screenshots showed repeated Clay+ narrowing into a long extrusion. Core 0.3.1 changes the smoke consumer to a plane-aware Clay buildup:
- tangent-plane footprint instead of raw 3D tip distance
- an offset sculpt plane instead of unlimited Draw-style displacement
- broad surrounding support stays involved as volume accumulates

Fixed topology will still saturate. That is now reported explicitly rather than hidden. Production Clay tuning waits until adaptive topology/remeshing returns on top of this kernel.

## VSS v3

Every Android build is still hard-gated by `vssVerify`.

VSS now checks **geometry quality, not only data-structure survival**:

- baseline kernel/manifold/connectivity
- split / flip-rejection / safe-collapse atomic behavior
- 1,500 topology mutations using remeshing-style long-edge split / short-edge collapse selection
- shape-envelope checks every 50 topology operations
- surface area drift
- signed-volume drift
- radial envelope
- maximum edge growth
- minimum triangle quality
- explicit self-intersection detection with a known folded mesh
- invalid-edit bit-exact rollback
- deterministic snapshot round trip
- BVH raycasts against brute-force reference raycasts
- BVH refit after deformation
- 16-bit render chunk correctness on a 163k-vertex mesh
- deterministic topology replay by SHA-256
- 1,050 Clay +/- smoke operations with progression screenshots and rejection-reason telemetry
- scale/memory tiers at ~2.5k, 10k, 41k, and 164k vertices
- p50/p95/p99 timing for raycasts, topology transactions, and snapshots

VSS emits `cabrush-core-dump-v3` plus screenshots and a contact sheet.

The topology fuzz is now required to stay close to its starting surface. A mesh can no longer pass just because every edge has two incident faces while the visible surface has turned into shards.

## Evidence from the local Core 0.3.1 verification

The final dependency-free Java verification passed all 12 scenarios.

The 1,500-op topology fuzz finished at:
- 187 vertices
- 370 faces
- ~1.5% surface-area drift
- ~3.0% signed-volume drift
- minimum triangle quality ~0.596
- radial range ~0.938 to 1.000
- zero detected self-intersections at verification checkpoints

The 164k-vertex scale tier remains roughly:
- ~30.5 MiB kernel
- ~26.5 MiB BVH
- ~12.1 MiB render staging

That is why 500k vertices remains a **hard guard**, not a normal mobile working target. Around 100k-250k is the intended future working range until real-device evidence justifies more.

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

The current project remains Java + Android SDK + OpenGL ES 3.0 with no native `.so` libraries, so there is no native ABI split yet.

Any future native layer must preserve:
- `armeabi-v7a`
- `arm64-v8a`

## Still deliberately not in the app

No Smooth, Grab, human base, DynTopo UI, remesh UI, masks, layers, import/export, or character tooling yet.

The next feature work should happen only after:
1. Core 0.3.1 VSS is green in GitHub Actions.
2. The VSS screenshots/dump are inspected.
3. The new build survives long sculpt sessions on the real 32-bit phone.
