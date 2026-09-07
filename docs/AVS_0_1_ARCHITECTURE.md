# CABrush AVS 0.1 Architecture

## Decision

Core 0.3.x is retired as the runtime sculpt architecture.

Its half-edge/BVH/transaction work remains useful research history, but AVS
does not keep a mutable triangle mesh as source of truth.

## Source of truth

`AvsVolume`

A sparse regular-grid signed-distance field.

- brick payload: 8x8x8 signed 16-bit samples
- voxel size: 0.045 world units in AVS 0.1
- fixed-point scale: 8192 quantization units per voxel
- negative: inside
- positive: outside
- zero crossing: visible sculpt surface
- missing brick: constant positive background
- hard brick budget: 12,000

This first milestone stores full bricks around occupied volume plus a working
distance band. It intentionally avoids an octree and multiresolution logic
until the simpler model is proven on the target phone.

## Field operations

Sphere SDF:

    length(p - center) - radius

Clay+:

    field = min(field, brushSdf)

Clay-:

    field = max(field, -brushSdf)

Those are direct volume operations. No edge split/collapse/flip is required.

Brush strength controls overlap depth of the brush volume rather than scaling
triangle displacement.

## Sparse allocation

The initial sphere allocates a compact brick region around the primitive.

A CSG edit allocates the brush working region plus halo before writing samples.
Only changed bricks and their neighbors become dirty.

AVS 0.1 does not prune bricks yet. Allocation is monotonic during a session,
which makes correctness and deterministic snapshots simpler for the proof.

## Picking

Picking reads the implicit field directly.

The CPU raycast:

1. clips the ray to allocated brick bounds
2. samples the field at a sub-voxel step
3. finds positive -> negative sign transition
4. binary-refines the crossing
5. estimates the surface normal from central SDF differences

It does not depend on the render mesh.

## Surface cache

`AvsSurfaceCache`

AVS 0.1 uses Marching Tetrahedra over a Freudenthal six-tetrahedra split of every grid cube. The same translated split is used everywhere, so neighboring cubes agree on shared face diagonals and avoid the ambiguous cube cases that complicated the first Surface Nets experiment.

Each brick owns its 8x8x8 cells and samples a one-point positive boundary from the neighboring brick when needed. Intersection vertices are deduplicated inside each chunk and every chunk uses local 16-bit indices. Generated triangles are cache only.

## Rendering

`SculptRenderer`

The renderer stores GPU/client buffers by stable brick ID and surface-chunk
revision.

A brush edit can rebuild several nearby chunks while untouched chunk buffers
are reused.

EGL loss is harmless to the sculpt because all GPU state can be regenerated
from `AvsVolume`.

## Snapshots

`AvsSnapshot`

A deterministic deep copy of brick coordinates + quantized field samples.

The snapshot format is intentionally simple. It supports:

- reset
- VSS deterministic hashes
- future undo foundation
- future crash-recovery foundation

## Verification philosophy

VSS tests the sculpt representation, not mutable triangle survival.

Mandatory truths:

- field samples remain finite/quantized
- inside/outside signs are correct
- repeated addition keeps growing
- subtraction removes volume
- field replay is deterministic
- local changes stay local
- surface extraction remains closed after welding matching chunk vertices
- raycast still finds the implicit surface after stress
- brick and surface-cache memory stay inside explicit budgets

The generated mesh is allowed to change completely between surface-cache
versions because it is not user data.

## Future adaptive detail

AVS 0.1 is intentionally single-resolution.

The planned path is hierarchical:

- coarse bricks for silhouette/body mass
- finer bricks only near high-detail regions
- surface detail/displacement tiles for micro detail

That later system must preserve a single unambiguous field evaluation across
resolution boundaries before it is allowed into the runtime.
