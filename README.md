# CABrush Core 0.3

CABrush Core 0.3 is an engine-foundation milestone. Runtime scope intentionally remains tiny: one sphere, Clay+, Clay-, Size, Strength, Reset, orbit, and pinch zoom.

## What changed

Core 0.3 replaces the old fixed-array sculpt prototype with separated subsystems:

- `MeshKernel`: packed primitive-array mutable mesh storage with stable vertex/face IDs, free lists, explicit half-edge connectivity, geometry/topology generations, normals, and 32-bit Android resource guards.
- `GeometryOps`: independently testable edge split, collapse, flip, and tangential relaxation primitives. They exist before DynTopo so topology operations can be proven alone.
- `GeometryTransaction`: compact rollback journals for vertex edits and deterministic full snapshots for topology edits.
- `GeometryValidator`: one central set of geometry/topology invariants. Brushes no longer define private safety rules tied to the original sphere.
- `SculptBvh`: spatial acceleration for ray picking and brush-region filtering, with refit after geometry changes and rebuild after topology changes.
- `StrokeContext`: reusable brush-runtime state so future tools share one stroke pipeline.
- `RenderChunkBuilder`: CPU-to-GPU staging that guarantees at most 65,535 unique vertices per chunk and uses 16-bit indices.
- `MeshSnapshot`: deterministic versioned CPU snapshot/hash foundation for rollback, future undo, and crash recovery.
- `SculptEngine`: deliberately minimal Clay +/- consumer built on the new engine rather than owning topology/picking/validation itself.

The Android renderer now consumes 16-bit render chunks and preserves CPU mesh state across EGL recreation.

## 32-bit Android budgets

The kernel hard-guards:
- 500,000 vertices
- 1,000,000 faces
- 3,000,000 half-edges
- 65,535 unique render vertices per draw chunk

The project still contains no native `.so` libraries, so the current Java build remains ABI-neutral. Any future native layer must preserve both `armeabi-v7a` and `arm64-v8a`.

## VSS v2

Every Android build is blocked behind `vssVerify`.

The verifier now checks:
- baseline kernel/manifold/connectivity
- edge split / flip / collapse independently
- 1,500 mixed topology fuzz operations
- invalid-edit rollback bit-for-bit
- deterministic snapshot round trip
- BVH raycasts against brute-force raycasts
- BVH refit after deformation
- 16-bit render chunk correctness on a 163k-vertex mesh
- deterministic topology replay by SHA-256
- minimal Clay +/- consumer behavior
- scale/memory tiers at ~2.5k, 10k, 41k, and 164k vertices
- p50/p95/p99 timing for BVH raycast, brute raycast, topology transactions, and snapshots

VSS emits `cabrush-core-dump-v2` as `dump.json`, a human-readable `dump.txt`, progression screenshots, topology screenshots, Clay checkpoints, and a contact sheet. The VSS artifact uploads even when verification fails; the APK uploads only after the build passes.

## Build

Requirements:
- JDK 17
- Gradle 8.7
- Android SDK 35
- Android Gradle Plugin 8.6.1

Run:

    gradle assembleDebug

Verification only:

    gradle :app:vssVerify

Artifacts:
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- VSS: `app/build/vss/report/`

## Deliberately not in Core 0.3

No Smooth, Grab, human base, DynTopo UI, remesh UI, masks, layers, import/export, or production brush tuning.

Clay+ and Clay- remain test consumers. The next brush work should happen only after the kernel/BVH/transaction evidence from real 32-bit hardware is clean.
