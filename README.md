# CABrush Core 0.2

CABrush has been deliberately reset to a tiny verified sculpting core.

## Runtime scope

The app contains only:

- one watertight subdivided icosphere
- **Clay +**
- **Clay -**
- brush Size
- brush Strength
- Reset
- one-finger sculpt
- two-finger orbit
- pinch zoom

There is no human base, Smooth, Grab, symmetry, dynamic topology, remeshing, masks, layers, import/export, or other sculpt feature in this milestone.

The point of Core 0.2 is to make the first two brushes boringly reliable before anything is layered back on.

## Sphere

The startup mesh is a level-4 icosphere:

- 2,562 vertices
- 5,120 triangles
- shared/watertight topology
- every undirected edge has exactly two incident triangles

## Clay core

Clay + and Clay - use the same deformation path with opposite signs.

Each brush dab:

1. ray-picks the visible surface
2. finds a connected front-facing brush patch
3. calculates one weighted local surface normal
4. applies a smooth radial falloff
5. clamps displacement against local/rest edge length
6. builds the entire candidate dab in scratch memory
7. validates every affected triangle
8. line-searches to a smaller safe displacement if necessary
9. commits the whole dab atomically or rejects it

The validator checks finite coordinates, rest-area retention, per-step area loss, face orientation, rest-edge stretch/compression, and triangle quality.

## VSS — Verification Snapshot System

Every Android build is gated by VSS.

`app:preBuild` depends on `vssVerify`, so Gradle will not compile/package the APK unless the sculpt core survives verification first.

VSS is dependency-free Java and performs deterministic stress scenarios:

- baseline topology verification
- 2,500 concentrated Clay + operations
- 2,500 concentrated Clay - operations
- 5,000 randomized alternating + / - operations
- 2,000 maximum-strength abuse operations

That is **12,000 sculpt operations** per verification run, with geometry-health checks throughout.

VSS verifies:

- expected sphere vertex/triangle counts
- closed two-manifold connectivity
- finite positions
- triangle area floor
- triangle quality floor
- no edge runaway beyond the fixed-topology safety budget
- Clay + produces measurable outward form
- Clay - does not create outward runaway
- every scenario finishes with a healthy mesh

## Dump + screenshots

Each VSS run writes to:

`app/build/vss/report/`

Artifacts include:

- `dump.json` — machine-readable verification dump
- `dump.txt` — human-readable verification dump
- `00-baseline.png`
- `01-clay-add-hammer.png`
- `02-clay-subtract-hammer.png`
- `03-alternating-random.png`
- `04-max-strength-abuse.png`
- `99-contact-sheet.png`

The screenshots are generated from the actual resulting mesh data using a headless Java rasterizer. They make brush tuning visually inspectable instead of relying only on "the triangles technically survived."

GitHub Actions uploads the complete VSS report even when verification fails. The APK artifact is uploaded only when the build passes.

## Build

Requirements:

- JDK 17
- Gradle 8.7
- Android SDK 35
- Android Gradle Plugin 8.6.1

Run:

    gradle assembleDebug

You can run verification without building Android:

    gradle :app:vssVerify

APK:

    app/build/outputs/apk/debug/app-debug.apk

VSS evidence:

    app/build/vss/report/

## Android / ABI

Core 0.2 remains Java + Android SDK + OpenGL ES 3.0 with no native `.so` libraries, so there is no native ABI split at this stage.

If/when native code returns later, support both:

- `armeabi-v7a`
- `arm64-v8a`

## Rule for adding features back

Nothing gets reintroduced until Clay + and Clay - are proven stable on the real 32-bit target and the VSS evidence stays clean.

Future features should be added one at a time, with a dedicated stress scenario and screenshot evidence before they are allowed into the normal build.
