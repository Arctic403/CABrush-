# PocketSculpt V1.3 Character Sculpt Audit

Date: 2026-09-07

Scope: Android Java/OpenGL ES sculpt prototype, with special attention to actual character blockout on a 32-bit-capable mobile path.

## Product correction

The previous builds proved touch sculpting, ray picking, symmetry, undo/redo and geometry safety, but the product target was still wrong: a fixed icosphere plus normal displacement is a deformation demo, not a practical character sculpt workflow.

V1.3 therefore changes the first usable workflow around four requirements:

1. the starting mesh should already be a character blockout,
2. Clay + and Clay - should behave like volume-building/removing clay rather than generic vertex inflation,
3. Smooth must have an obvious visible effect,
4. Grab must exist for proportions and silhouette.

## External research checked

### Blender common sculpt brushes

Blender’s sculpt documentation describes:

- Clay Strips as a broad-shape / volume-building brush,
- Grab as a brush for moving geometry across the screen for general shaping,
- Smooth as a brush for smoothing and shrinking surfaces,
- Draw as generic add/subtract.

The Grab documentation specifically calls Grab an essential brush for building shapes and adjusting proportions.

References:

- https://docs.blender.org/manual/en/latest/sculpt_paint/sculpting/introduction/brush.html
- https://docs.blender.org/manual/en/latest/sculpt_paint/sculpting/brushes/grab.html

### Nomad topology workflow

Nomad’s topology documentation distinguishes:

- multiresolution,
- voxel remeshing for uniform density,
- dynamic topology for adding/removing faces locally while sculpting.

Nomad explicitly recommends voxel remeshing while blocking out when polygons become too stretched, and dynamic topology for local adaptive detail.

References:

- https://nomadsculpt.com/manual/topology
- https://nomadsculpt.com/manual/tips
- https://nomadsculpt.com/manual/stroke

### Isotropic remeshing

CGAL’s documented isotropic remeshing sequence performs:

1. edge splitting,
2. edge collapsing,
3. edge flipping,
4. tangential relaxation,
5. reprojection.

That remains the architecture target for PocketSculpt’s future topology mutation layer.

Reference:

- https://doc.cgal.org/6.1.1/Polygon_mesh_processing/group__PMP__meshing__grp.html

## Character base

### Why not start from the sphere

A sphere is fine for proving the brush engine and can be acceptable for a head, but it forces the user to spend most of the early session inventing basic anatomy and limb silhouette before they can make a game character.

V1.3 starts from a neutral humanoid blockout instead.

### Generation

The character is generated locally with a signed implicit field made from smoothly blended anatomical blockout volumes:

- head
- neck
- chest / torso
- pelvis
- upper/lower arms
- hands
- upper/lower legs
- feet

The field is polygonized using marching tetrahedra over a deliberately modest mobile grid.

Properties validated in the core self-test:

- one connected surface
- closed two-manifold topology
- healthy finite coordinates
- mobile-scale vertex/triangle budget

The polygonizer caches intersections by grid-edge ID so adjacent tetrahedra share vertices rather than creating unwelded seams.

Iso interpolation is clamped away from exact grid corners. This avoids extremely short edges and pathological sliver triangles that otherwise appear when the iso-value passes almost exactly through a grid sample.

## Clay + / Clay -

### Old behavior

The old brush moved every selected vertex along one averaged normal by:

    strength * falloff

That is stable enough for a bump brush but poor for broad anatomy because it mostly inflates a round mound.

### V1.3 behavior

The brush now creates a local plane from the ray hit and averaged brush normal.

For each selected vertex:

1. measure signed distance to the local plane,
2. choose an offset target plane for Add or Subtract,
3. move only toward that target,
4. apply a double-softened rim falloff,
5. clamp the move to local/current and rest edge scale,
6. submit the complete dab to the transactional geometry validator.

Result:

- Clay + builds and levels broad masses,
- Clay - cuts/recesses broad forms,
- repeated strokes accumulate,
- the perimeter blends into surrounding anatomy instead of producing a hard ring.

## Smooth

### Failure in V1.2

V1.2 used a Taubin-style positive pass followed by a nearly equal negative pass. That is useful for low-shrinkage fairing, but under the existing deformation clamps it could visually cancel most of a finger stroke.

That matches the report that Smooth “doesn’t work.”

### V1.3 correction

Smooth now uses two controlled positive Laplacian relaxation passes per dab.

This intentionally allows some local shrink, consistent with a normal sculpt Smooth tool, and makes the effect obvious enough for touch use.

The same transactional geometry validator is used, so Smooth cannot silently destroy triangle health while attempting a strong relaxation.

The automated test now builds a localized bump, records Laplacian roughness, applies Smooth, and requires:

- measurable vertex motion,
- lower roughness afterward,
- healthy geometry.

## Grab

Grab captures its affected vertices at the start of the stroke and keeps that selection/falloff for the entire drag.

The renderer:

1. ray-picks the initial hit,
2. builds a plane facing the camera through that hit,
3. projects later touch rays onto that plane,
4. computes screen/world drag displacement,
5. asks the mesh to move the captured region toward that target.

The mesh keeps the captured base positions and applies the drag through the transactional validator.

X symmetry captures a mirrored region at stroke start and mirrors the X component of the drag. Center-overlap suppression avoids obvious double transforms on the symmetry seam.

This gives PocketSculpt the proportion tool needed for:

- skull silhouette
- jaw width
- cheek/temple mass
- shoulders
- torso width
- hips
- arm/leg silhouette
- broad hand/foot placement

## Safety / geometry audit

The V1.2 transactional guard remains intact.

Each candidate still checks:

- finite positions,
- rest-area floor,
- per-step area floor,
- per-step normal orientation,
- rest edge compression/stretch,
- per-step edge growth/shrink,
- normalized triangle quality.

The character-base generator is constructed to satisfy those same runtime health rules at startup.

## Input / rendering audit

The existing V1.2 Android hardening remains appropriate:

- historical MotionEvent samples are consumed,
- interrupted strokes end on pause,
- CPU sculpt state survives EGL context recreation,
- RENDERMODE_WHEN_DIRTY avoids continuous rendering while idle.

Grab uses the same historical touch path, so proportion drags do not depend only on the latest batched sample.

The human base increases the default triangle count over the old icosphere but remains well inside the current small mobile prototype range. Ray picking is still brute force and should be profiled on the physical 32-bit device before increasing density substantially.

## Automated regression coverage

`tools/SculptCoreSelfTest.java` now verifies:

- original icosphere regression path
- concentrated fixed-topology clay stress
- humanoid generation
- humanoid mobile poly budget
- watertight/two-manifold topology
- single connected component
- healthy base
- Clay + outward deformation
- Clay - inward deformation
- Smooth measurable motion
- Smooth roughness reduction
- Grab proportion displacement
- mixed character brush stress
- post-stress raycast

The edited Android-facing Java sources were also compiled against local API stubs in the patching environment to catch Java signature/syntax errors when a real Android SDK was unavailable.

## What this does and does not solve

V1.3 is enough to begin useful character blockout instead of sculpting a sphere into anatomy from nothing.

It still does not provide unlimited ZBrush/Nomad-style free-form topology.

Fixed topology will eventually hit the V1.2 stretch/compression budget during:

- long limb pulls,
- fingers,
- ears,
- sharp noses,
- deep folds,
- very small facial features.

At that point the correct fix is topology creation/remeshing, not simply loosening the geometry guard.

## Next geometry milestone

Implement local remeshing behind the existing validator.

Minimum safe order:

1. detect edges outside the target detail band,
2. split overly long edges,
3. collapse overly short edges,
4. flip edges when valence/triangle quality improves,
5. relax tangentially,
6. reproject/preserve the sculpted surface,
7. rebuild adjacency/incidence/spatial acceleration,
8. validate manifoldness and geometry health before committing topology.

For the first mobile version, a user-triggered low-resolution voxel remesh may be an easier and safer milestone than full continuous Dyntopo.

## ABI note

V1.3 remains Java-only. There are still no native `.so` libraries, so the current build has no NDK ABI exclusion. When native acceleration is introduced, both `armeabi-v7a` and `arm64-v8a` must be built.
