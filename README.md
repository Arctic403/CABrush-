# PocketSculpt V1.4 — Dynamic Character Sculpt

PocketSculpt is a small Android-native sculpting prototype aimed at real character creation on phones, including 32-bit Android devices.

V1.4 adds the major capability V1.3 was missing: **adaptive topology/remeshing**. Clay+, Clay-, Smooth and Grab can now work on geometry that gains detail as the sculpt requires it instead of permanently stretching one fixed triangle layout.

## V1.4 highlights

### Dynamic Topology

**DynTopo is enabled by default.** Before a normal sculpt dab, PocketSculpt can adapt the touched region to the current brush detail:

- long edges are split locally before deformation,
- small brushes create finer local triangles,
- large brushes stay coarser and faster,
- Smooth may collapse clearly over-dense short edges,
- X symmetry can refine both sides,
- topology growth is capped for mobile memory/performance.

The current mobile caps are 48,000 vertices / 96,000 triangles.

### Remesh

The **Remesh** button runs a bounded whole-surface adaptive remesh using the current Size and Detail settings.

The pass safely splits long edges and collapses short edges, then verifies the result is still a closed two-manifold before installing it.

This is an incremental triangle remesher. A separate true voxel remesher can be added later for self-intersection cleanup and volume union workflows.

### Sphere + Human starts

Both workflows are available:

- **Sphere** — freeform sculpt-from-a-ball workflow.
- **Human** — neutral connected humanoid blockout for faster character work.

Reset returns to whichever pristine base was used to create the current sculpt.

### Core brushes

- **Clay +** — plane-based volume buildup.
- **Clay -** — matching volume carve.
- **Smooth** — visible local relaxation.
- **Grab** — proportion/silhouette movement.
- **Sym X** — mirrored character sculpting.

Size now reaches a substantially smaller minimum radius for face/detail work, and the Strength range starts lower for finer control.

## Topology-aware undo / redo

Topology changes invalidate position-only history. V1.4 snapshots both vertex positions and triangle indices, so Undo/Redo can cross DynTopo and Remesh operations safely.

OpenGL buffers are also recreated automatically when vertex/index counts change.

## Character workflow

A practical workflow is now:

1. Start from **Sphere** for freeform work or **Human** for a faster body blockout.
2. Leave **DynTopo** enabled.
3. Use **Grab + Sym X** for silhouette/proportions.
4. Use large **Clay+ / Clay-** for broad anatomy.
5. Reduce **Size** and increase **Detail** as you move into the head and smaller forms.
6. Use **Smooth** to blend noisy transitions.
7. Tap **Remesh** when you want a more globally even triangle distribution.
8. Continue refining instead of hitting the old fixed-topology deformation wall.

## Why this matters

V1.3 could make a character blockout but fixed topology still imposed a hard deformation budget. V1.4 can create new local geometry before sculpting and remove overly dense geometry during remesh/smoothing. That is the architectural step required before finer anatomy tools such as Crease, Inflate, Flatten and masks become genuinely useful.

## Build

The project remains Android SDK + Java + OpenGL ES 3.0.

- JDK 17
- Gradle 8.7
- Android Gradle Plugin 8.6.1
- compileSdk / targetSdk 35
- minSdk 24

Command line:

    gradle assembleDebug

APK:

    app/build/outputs/apk/debug/app-debug.apk

GitHub Actions runs the dependency-free sculpt-core tests before building the APK.

## Android / ABI support

V1.4 still contains no native `.so` libraries, so there is no native ABI split yet. The same APK remains usable on supported 32-bit and 64-bit Android devices with OpenGL ES 3.0.

When the sculpt hot path later moves to the NDK, build both:

- `armeabi-v7a`
- `arm64-v8a`

## Automated validation

The V1.4 core test covers:

- watertight icosphere regression,
- watertight connected humanoid base,
- Clay+ / Clay- direction,
- useful Smooth behavior,
- Grab proportion edits,
- local DynTopo edge refinement,
- sculpting after topology changes,
- global adaptive Remesh,
- topology-aware state restore,
- mixed character + DynTopo stress,
- manifold and finite-coordinate checks throughout.

See `docs/DYNAMIC_TOPOLOGY_AUDIT_V1_4.md` for implementation details and remaining remeshing work.
