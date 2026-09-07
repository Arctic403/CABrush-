# PocketSculpt V1.3 — Character Blockout

PocketSculpt is a small Android-native sculpting prototype aimed at character creation on real phones, including 32-bit Android devices. V1.3 changes the target from “deform a sphere safely” to “block out a usable game character.”

## V1.3 character sculpt patch

### Character base instead of a bowling ball

PocketSculpt now starts from a neutral, watertight humanoid blockout generated locally at startup.

- one connected closed surface
- head, neck, torso, pelvis, arms, hands, legs and feet
- generated from a smooth implicit field and polygonized with marching tetrahedra
- approximately 4.2k vertices / 8.4k triangles on the current grid
- no external model asset required
- Reset returns to the clean humanoid base

The older watertight icosphere generator remains in the core for regression tests and future primitive workflows.

### Clay + / Clay -

Clay is now plane-based rather than generic normal displacement.

Each dab:
1. finds a connected front-facing brush region,
2. computes a weighted average surface normal,
3. builds a local sculpt plane from the ray hit,
4. adds or removes volume toward that plane,
5. blends the rim with a smooth falloff,
6. commits only when the complete candidate deformation passes the transactional geometry guard.

This gives broad anatomy-building behavior instead of only making round bumps.

### Smooth

The previous near-cancelling two-pass Taubin fairing made the Smooth tool feel almost inactive under a finger.

V1.3 Smooth now performs two controlled positive Laplacian relaxations per accepted dab. It intentionally removes local bumps/noise and may shrink the surface slightly, which is expected for a normal sculpt Smooth brush. It still uses the transactional geometry validator.

### Grab

Grab is now a real brush mode for character proportions.

- captures the affected vertices at stroke start
- drags them in the screen-facing plane
- keeps the original brush falloff throughout the stroke
- supports X symmetry
- runs through the same geometry safety transaction
- useful for skull shape, jaw width, shoulders, hips, limbs and silhouette changes

## Current tools

- **Clay +** — build broad volume
- **Clay -** — carve volume away
- **Smooth** — visibly relax rough surface
- **Grab** — move a captured region for proportions/silhouette
- **Sym X** — mirror sculpting across X
- adjustable Size / Strength
- stroke-level Undo / Redo
- Reset to humanoid base
- one-finger sculpt
- two-finger orbit
- pinch zoom

## Geometry safety

V1.2’s transactional guard remains active.

Brushes do not mutate the live mesh one vertex at a time. Each operation is assembled into a candidate, affected triangles are validated, and the entire operation is either committed at a safe scale or rejected.

Checks include:

- finite coordinates
- triangle area
- face orientation changes
- edge compression/stretch
- per-step edge change
- triangle quality

The humanoid generator also clamps iso-surface edge interpolation away from exact grid corners to avoid starting with pathological sliver triangles.

## Character workflow in V1.3

A practical blockout flow is now:

1. Start from the humanoid base.
2. Use **Grab + Sym X** for overall proportions and silhouette.
3. Use **Clay +** for skull masses, chest, shoulders, muscle groups and other broad forms.
4. Use **Clay -** for eye sockets, neck transitions and broad recesses.
5. Use **Smooth** repeatedly to blend blockout planes and remove unwanted lumps.
6. Rotate frequently and work from several views.

This is now a character blockout tool, but it is not yet a full ZBrush/Nomad replacement.

## Why dynamic topology is still next

V1.3 deliberately does not fake unlimited free-form sculpting on fixed topology.

For extreme limb pulls, fingers, ears, noses and high-detail anatomy, the mesh eventually needs new topology. The next major geometry milestone remains remeshing/dynamic topology:

1. split long edges,
2. collapse short edges,
3. flip edges when triangulation improves,
4. relax vertices tangentially,
5. preserve/reproject the sculpted surface.

The existing geometry guard should remain as the final safety layer around those topology edits.

## Build

### Android Studio

1. Open the repository.
2. Let Gradle sync.
3. Install Android SDK Platform 35 if requested.
4. Run the `app` configuration on Android 7.0+.

### GitHub Actions / command line

The workflow uses:

- JDK 17
- Gradle 8.7
- Android Gradle Plugin 8.6.1
- compileSdk / targetSdk 35

Before assembling the APK, CI compiles and runs `tools/SculptCoreSelfTest.java`.

With Gradle 8.7 installed:

    gradle assembleDebug

APK:

    app/build/outputs/apk/debug/app-debug.apk

## Android / ABI support

V1.3 is still Java + Android SDK + OpenGL ES 3.0. It contains no native `.so` libraries, so there is currently no native ABI split and the app remains usable on supported 32-bit and 64-bit Android devices.

When the hot path later moves to the NDK, ship both:

- `armeabi-v7a`
- `arm64-v8a`

## V1.3 automated validation

The dependency-free sculpt-core test now checks:

- watertight icosphere regression path
- humanoid base generation
- humanoid vertex/triangle mobile budget
- closed two-manifold topology
- one connected humanoid surface
- healthy starting geometry
- Clay + produces outward volume
- Clay - produces inward carving
- Smooth produces measurable movement and reduces Laplacian roughness
- Grab produces useful proportion movement
- mixed character brush stress remains healthy
- post-stress ray picking still works

Android-facing Java is also syntax/signature checked during development against API stubs when the Android SDK is unavailable in the patching environment.

## Remaining limits

- no dynamic topology / voxel remesh yet
- fixed topology still imposes a hard deformation budget
- CPU brute-force raycast over the current triangles
- CPU full normal rebuild after accepted brush operations
- no masks / layers / crease / inflate / flatten yet
- no OBJ/GLB import/export yet
- no stylus pressure yet
- no persistent autosave after process death yet

## Research direction

V1.3 follows the same broad sculpting concepts documented by established tools:

- Blender’s common sculpt workflow uses Clay Strips for broad volume, Grab for proportions, Smooth for cleanup, and Draw for generic add/subtract.
- Blender describes Grab as an essential shape/proportion brush.
- Nomad recommends voxel remeshing or dynamic topology when stretched polygons need fresh density.
- Isotropic remeshing literature and CGAL’s implementation use split → collapse → flip → relax → reproject.

See `docs/CHARACTER_SCULPT_AUDIT_V1_3.md` for the detailed audit.
