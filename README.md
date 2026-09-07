# PocketSculpt V1.2

PocketSculpt is a deliberately small Android-native sculpting prototype. V1.2 focuses on making the fixed-topology sculpt core fail safely on real phones before the hot path moves to C++/NDK and before dynamic remeshing is introduced.

## V1.2 stability patch

The V1.1 screenshots exposed a second geometry failure after the UV-sphere seam was fixed: individual vertex moves were checked one at a time and only against the immediately previous triangle shape. Repeated legal moves could therefore accumulate into extremely stretched triangles, fins, and folded sheets without ever creating a zero-area triangle.

V1.2 changes the sculpt operation from "move vertices and hope" to a transaction:

- Every brush dab is computed into scratch buffers first.
- All affected triangles are validated before the dab is committed.
- Failed candidates are retried with progressively smaller displacement.
- A dab is rejected completely if no safe displacement can be found.
- Triangle area, orientation change, shape quality, finite coordinates, per-step edge change, and edge stretch/compression versus the original mesh are checked.
- The fixed topology now has a hard deformation budget. When a region reaches that budget, the brush stops pushing it farther instead of turning it into a spike.
- The transaction buffers are reused to avoid full-mesh garbage allocation on every dab.

Additional hardening:

- Clay Add/Subtract continue to use an averaged local surface normal, matching the broad behavior used by established sculpt tools.
- Brush selection is front-facing and topologically connected and now also rejects nearby folded sheets whose normals strongly disagree with the hit surface.
- X symmetry reduces overlapping center-line contributions so the same area is not accidentally hit at double strength.
- Normals are refreshed before the mirrored symmetry transaction when the primary side changed.
- Android batched touch history is consumed in order so curved finger paths are not replaced by long straight chords on slower devices.
- An interrupted stroke is closed when the `GLSurfaceView` pauses, preventing a stuck `strokeActive` state after app switching.
- CPU mesh/history survive an OpenGL context recreation; only GPU program/buffer state is recreated.
- Undo/redo history is bounded on both stacks and Reset no longer adds a no-op history entry.
- Non-finite input/snapshot values are rejected defensively.

## Current features

- Real-time OpenGL ES 3.0 shaded mesh
- Watertight level-4 icosphere: 2,562 shared vertices / 5,120 triangles
- Clay Add brush
- Clay Subtract brush
- Smooth brush with two-pass Taubin-style relaxation
- Adjustable brush size and strength
- X symmetry
- 20-step undo/redo history
- Reset mesh
- One-finger sculpting
- Two-finger orbit
- Pinch zoom
- CPU triangle ray-picking against the deformed mesh
- Radius-based stroke spacing
- Front-facing/topology-aware brush selection
- Transactional fixed-topology geometry guard
- No native libraries, so V1.2 remains ABI-neutral and runs as one APK on both 32-bit and 64-bit Android devices that support OpenGL ES 3.0

## Build

### Android Studio

1. Open the repository.
2. Let Gradle sync.
3. Install Android SDK Platform 35 if Android Studio asks.
4. Run the `app` configuration on an Android 7.0+ device.

### GitHub Actions / command line

The workflow uses JDK 17, Gradle 8.7, Android SDK 35, and AGP 8.6.1. Before assembling the APK it compiles and runs a pure-Java sculpt-core stress test.

With Gradle 8.7 installed:

    gradle assembleDebug

APK output:

    app/build/outputs/apk/debug/app-debug.apk

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

V1.2 intentionally remains Android SDK + Java:

- `MainActivity` — touch-first overlay UI
- `SculptSurfaceView` — gesture routing, batched touch history, GL-thread dispatch, lifecycle cleanup
- `SculptRenderer` — camera, OpenGL ES rendering, screen-ray generation, stroke spacing, symmetry, history
- `SculptMesh` — watertight topology, ray/triangle intersection, connected brush selection, transactional deformation, geometry validation, smoothing, normals
- `tools/SculptCoreSelfTest.java` — dependency-free regression/stress test run by CI

Keeping the hot path Java-only for this stage preserves the current ABI-neutral APK. When native code is introduced, ship both `armeabi-v7a` and `arm64-v8a`.

## Validation performed for V1.2

The pure Java core was compiled and stress-tested independently of Android:

- Verified 2,562 vertices and 5,120 triangles.
- Verified every undirected starting edge belongs to exactly two triangles.
- Verified the fresh mesh passes the same runtime health rules used by the deformation guard.
- Repeatedly hammered one region with hundreds of Add strokes, then hundreds of Subtract strokes.
- Ran a mixed randomized Add/Subtract/Smooth workload including mirrored operations.
- Verified positions stay finite and the mesh remains inside the fixed-topology edge/area/quality budget.
- Verified ray-picking still succeeds after the stress workload.
- Compiled `SculptRenderer` and `SculptSurfaceView` against Android API stubs to catch Java signature/syntax regressions in the edited renderer/input code.
- Parsed all Android resource XML successfully.

The previous concentrated V1.1 stress case could grow a local edge to many times its starting length while remaining technically non-degenerate. With V1.2 the same class of test saturates at the configured fixed-topology budget instead of continuing to stretch.

## Important fixed-topology limit

V1.2 intentionally does **not** pretend fixed topology can support unlimited sculpting.

Once local triangles reach the deformation budget, further extreme displacement is rejected. This is the correct safe behavior for this version. The next major geometry step is local dynamic remeshing so the app can add/remove topology as forms are stretched or compressed.

A production remeshing path should use the established sequence:

1. split edges that are too long,
2. collapse edges that are too short,
3. flip edges where it improves triangulation,
4. relax vertices tangentially,
5. preserve/reproject the intended surface.

Do not remove the V1.2 geometry guard when dynamic remeshing arrives; it should remain the last line of defense around topology edits.

## Remaining limits / next audit targets

- Fixed topology; no edge split/collapse/flip yet.
- CPU brute-force raycast across all 5,120 triangles.
- CPU full normal rebuild after accepted dabs.
- Brush selection still allocates temporary traversal arrays.
- No spatial acceleration structure yet.
- No OBJ/GLB import/export yet.
- No masks, layers, materials, alpha brushes, stylus pressure, autosave, or crash recovery yet.
- Sculpt state is kept in CPU memory but is not persisted to disk across a process kill.

The next performance milestone should be profiling on the actual 32-bit target before moving hot loops to C++/NDK.
