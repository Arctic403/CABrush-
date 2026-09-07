# PocketSculpt V1

A deliberately small Android-native sculpting prototype: a touch-first proof that the core mobile sculpt workflow works before moving the hot path into C++/NDK.

## V1 features

- Real-time OpenGL ES 3.0 shaded mesh
- Procedural sculptable sphere (~1.6k vertices / ~3k triangles)
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
- Normal recalculation after edits
- No native libraries, so V1 is ABI-neutral and runs as one APK on both 32-bit and 64-bit Android devices that support OpenGL ES 3.0

## Build

### Android Studio

1. Open the `PocketSculpt-v1` folder.
2. Let Gradle sync.
3. Install Android SDK Platform 35 if Android Studio asks.
4. Run the `app` configuration on an Android 7.0+ device.

### Command line

With Gradle 8.7 installed:

```bash
gradle assembleDebug
```

The repository also includes a GitHub Actions workflow that installs Gradle 8.7 + Android SDK 35 and uploads the debug APK as an artifact.

The debug APK will be at:

`app/build/outputs/apk/debug/app-debug.apk`

## Controls

- **Clay +**: push the surface outward.
- **Clay -**: carve inward.
- **Smooth**: relax nearby vertices toward their neighbours.
- **Sym X**: mirror brush strokes across the X axis.
- **1 finger**: sculpt.
- **2 fingers drag**: orbit camera.
- **Pinch**: zoom.
- **Undo / Redo**: stroke-level history.

## Architecture

V1 intentionally uses only the Android SDK and Java:

- `MainActivity` — touch-first overlay UI
- `SculptSurfaceView` — gesture routing / GL thread dispatch
- `SculptRenderer` — camera, OpenGL ES rendering, screen-ray generation, history
- `SculptMesh` — topology, ray/triangle intersection, brushes, normals

This keeps the first prototype easy to audit and easy to run. The next performance step is to move `SculptMesh` operations into a C++17 NDK library with both `armeabi-v7a` and `arm64-v8a` outputs while keeping the Android UI/renderer shell.

## Known V1 limits

- Starts from a sphere only; no OBJ/GLB import/export yet.
- Fixed topology; no dynamic topology or voxel remeshing.
- CPU brute-force raycast across all triangles.
- CPU full normal rebuild after each brush sample.
- UV-sphere seam is not welded.
- No masks, layers, materials, alpha brushes, stylus pressure, or autosave yet.

Those are deliberate V2+ items rather than unfinished V1 code.
