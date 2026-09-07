# PocketSculpt V1.2 Sculpt Core Audit

Date: 2026-09-07

Scope: current Android Java/OpenGL ES prototype only. This audit intentionally avoids adding unrelated product features.

## Failure reproduced

The V1.1 geometry guard prevented immediate zero-area triangles but it still validated vertices one at a time and compared each accepted move to the already-modified mesh.

That allowed a repeated stroke to accumulate enormous edge stretch without any single step looking catastrophic. A concentrated stress reproduction grew the local radius and edge length continuously while all triangles remained technically non-zero-area. That matches the on-device "sheet / fin / spike" failure much better than the original UV-sphere seam explanation.

## V1.2 correction

Brush edits are now transactional:

1. snapshot the current positions into a reusable buffer,
2. compute the complete dab displacement without mutating the live mesh,
3. build the candidate mesh in another reusable buffer,
4. validate every triangle touched by the selection,
5. commit the complete candidate only when all touched triangles pass,
6. otherwise halve the dab scale and retry,
7. reject the dab when no safe scale can be found.

The validator checks:

- finite vertex coordinates,
- minimum triangle area versus the original icosphere,
- minimum area versus the immediately previous state,
- face-normal orientation change per dab,
- edge compression/stretch versus original topology,
- edge change versus the immediately previous state,
- a normalized triangle-quality metric.

This prevents the old "many individually legal moves become one giant fin" failure while still allowing normal local sculpting.

## Brush selection and direction

The Draw/Clay direction remains based on the weighted average of normals inside the active brush area. This is consistent with the broad behavior documented for Blender's Draw brush.

The selection remains topology-connected and radius-limited. V1.2 adds a wide normal-compatibility cone around the hit surface to reduce the chance that a nearby folded sheet is picked up simply because it happens to fall inside the same 3D radius.

Front-facing selection is now slightly stricter.

X symmetry keeps the same object-space mirror, but overlapping center-line brushes reduce their effective strength so the same vertices are not unintentionally hit twice at full strength.

## Smoothing

Two-pass Taubin-style smoothing remains in place to reduce the shrinkage associated with a single positive Laplacian pass.

The smoothing pass now uses the same transactional geometry validator as Clay Add/Subtract instead of moving vertices sequentially.

## Android input and lifecycle audit

Android may batch several pointer samples into one `ACTION_MOVE`. V1.2 consumes historical samples in order before the current sample, preserving curved finger paths instead of replacing them with one straight interpolation chord.

`GLSurfaceView.onPause()` now closes an active stroke before pausing the GL thread. This prevents `strokeActive` from remaining latched after app switching.

The CPU sculpt mesh is no longer recreated every time `onSurfaceCreated()` runs. Android documents that an EGL context may be lost/recreated even when preservation is requested, so V1.2 recreates GPU state while retaining the CPU mesh/history.

`RENDERMODE_WHEN_DIRTY` remains appropriate for this prototype because it avoids continuous GPU work while the mesh/camera are idle.

## Build / Android compatibility audit

Current project pairing:

- Android Gradle Plugin: 8.6.1
- Gradle: 8.7
- JDK: 17
- compileSdk / targetSdk: 35
- minSdk: 24

Android's AGP 8.6 compatibility documentation lists Gradle 8.7, JDK 17, and API 35 support, so no build-version change was needed.

The app still contains no native `.so` libraries. V1.2 therefore remains ABI-neutral at this stage and does not exclude 32-bit ARM devices because of an NDK ABI split. When the sculpt core moves to native code, both `armeabi-v7a` and `arm64-v8a` must be produced.

## CI regression guard

`tools/SculptCoreSelfTest.java` is dependency-free and runs before the APK build.

It verifies:

- expected icosphere counts,
- closed two-manifold starting topology,
- initial health,
- concentrated repeated Add strokes,
- concentrated repeated Subtract strokes,
- mixed randomized Add/Subtract/Smooth strokes,
- mirrored brush operations,
- finite coordinates,
- edge-stretch budget,
- post-stress ray picking.

This is intentionally a stress/regression test, not a replacement for Android instrumentation tests.

## What is deliberately not patched yet

### Dynamic topology

Fixed topology cannot support unlimited extrusion/detail. The correct long-term path is local remeshing.

Established isotropic remeshing workflows use a sequence of edge splitting, edge collapsing, edge flipping, vertex relaxation, and projection/preservation of the intended surface. V1.2 stops deformation safely when the current topology runs out of room rather than shipping a rushed topology mutation system.

### Raycast acceleration

Ray picking is still brute-force over 5,120 triangles. That is acceptable for the current mesh density but will not scale to higher-density sculpting. A BVH/spatial acceleration structure becomes necessary before substantially increasing topology.

### Partial normal rebuild

Normals are still fully rebuilt after accepted dabs. This is simple and safe at the current mesh size. Once profiling shows it matters, rebuild only the touched one-ring / affected triangles.

### Allocation cleanup

Selection traversal still allocates temporary arrays per dab. The larger transaction buffers are now reused, but the traversal path should be profiled on the actual 32-bit phone before further optimization.

### Persistent sculpt recovery

The CPU mesh survives an EGL context recreation, but it is not serialized to disk. Android can still kill the app process. Autosave/crash recovery belongs in a later persistence milestone.

## Research references

- Blender Manual — Draw sculpt brush:
  https://docs.blender.org/manual/en/latest/sculpt_paint/sculpting/brushes/draw.html
- Blender Manual — sculpt brush settings / Front Faces Only / Area Plane:
  https://docs.blender.org/manual/en/dev/sculpt_paint/brush/brush_settings.html
- CGAL Polygon Mesh Processing — isotropic remeshing:
  https://doc.cgal.org/6.0/Polygon_mesh_processing/index.html
- Gabriel Taubin — A Signal Processing Approach to Fair Surface Design:
  https://graphics.stanford.edu/courses/cs164-10-spring/Handouts/taubin.pdf
- Android `MotionEvent` batching:
  https://developer.android.com/reference/android/view/MotionEvent.html
- Android `GLSurfaceView` lifecycle and EGL context preservation:
  https://developer.android.com/reference/android/opengl/GLSurfaceView.html
- Android Gradle Plugin 8.6 compatibility:
  https://developer.android.com/build/releases/agp-8-6-0-release-notes
