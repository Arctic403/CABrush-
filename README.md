# PocketSculpt V1.1

A deliberately small Android-native sculpting prototype focused on making the first sculpt core stable on real phones before moving the hot path into C++/NDK.

## V1.1 hardening patch

This patch fixes the topology failure seen in V1 where repeated strokes could pull the sphere into giant sheets, spikes, and apparent holes.

- Replaced the unwelded UV sphere with a watertight subdivided icosphere.
- 2,562 shared vertices / 5,120 triangles at startup.
- No duplicated longitude seam and no collapsed UV-sphere pole rows.
- Brush dabs are spaced by brush radius instead of applying once for every raw Android MOVE event.
- Clay Add/Subtract use one averaged local surface normal instead of pushing every vertex along a diverging per-vertex normal.
- Primary strokes filter to the front-facing, topologically connected patch around the ray hit.
- Per-vertex displacement is clamped against local edge length.
- Candidate moves are rejected if they would nearly collapse or strongly flip an incident triangle.
- Smooth now uses a two-pass Taubin-style relaxation to reduce the shrinkage of the old one-pass Laplacian smooth.
- X symmetry remains supported; the mirrored dab receives the same topology safety checks.
- Normal rebuilding remains CPU-side after accepted brush dabs.

## Features

- Real-time OpenGL ES 3.0 shaded mesh
- Clay Add brush
- Clay Subtract brush
- Smooth brush
- Adjustable brush size and strength
- X symmetry
- 20-step undo/redo history
- Reset mesh
- One-finger sculpting
- Two-finger orbit
- Pinch zoom
- CPU triangle ray-picking against the deformed mesh
- Stroke-level undo snapshots
- No native libraries, so V1.1 remains ABI-neutral and runs as one APK on both 32-bit and 64-bit Android devices that support OpenGL ES 3.0

## Build

### Android Studio

1. Open the repository.
2. Let Gradle sync.
3. Install Android SDK Platform 35 if Android Studio asks.
4. Run the `app` configuration on an Android 7.0+ device.

### Command line

With Gradle 8.7 installed:

```bash
gradle assembleDebug
```

The GitHub Actions workflow installs Gradle 8.7 + Android SDK 35 and uploads the debug APK artifact.

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## Controls

- **Clay +**: push the surface outward.
- **Clay -**: carve inward.
- **Smooth**: relax nearby vertices with reduced shrinkage.
- **Sym X**: mirror brush strokes across the X axis.
- **1 finger**: sculpt.
- **2 fingers drag**: orbit camera.
- **Pinch**: zoom.
- **Undo / Redo**: stroke-level history.

## Architecture

V1.1 intentionally remains Android SDK + Java:

- `MainActivity` — touch-first overlay UI
- `SculptSurfaceView` — gesture routing / GL thread dispatch
- `SculptRenderer` — camera, OpenGL ES rendering, ray generation, stroke spacing, history
- `SculptMesh` — watertight icosphere topology, ray/triangle intersection, connected brush selection, safe deformation, smoothing, normals

Keeping V1.1 Java-only preserves the current ABI-neutral APK. When the sculpt hot path moves to C++/NDK, build both `armeabi-v7a` and `arm64-v8a`.

## Validation performed for this patch

The pure Java sculpt core was compiled and smoke-tested outside Android:

- Verified the level-4 icosphere has 2,562 vertices and 5,120 triangles.
- Verified every undirected edge belongs to exactly two triangles.
- Verified there are no degenerate starting triangles.
- Applied 600 mixed Add/Subtract/Smooth brush operations.
- Verified all positions remained finite and no triangle collapsed to zero area.

## Remaining limits

- Fixed topology; no dynamic topology or voxel remeshing yet.
- CPU brute-force raycast across all triangles.
- CPU full normal rebuild after each accepted dab.
- No OBJ/GLB import/export yet.
- No masks, layers, materials, alpha brushes, stylus pressure, or autosave yet.
- The topology safety checks intentionally prefer rejecting an extreme vertex move over allowing an invalid triangle.

Dynamic remeshing is the next major geometry step after this stability patch is proven on-device.
