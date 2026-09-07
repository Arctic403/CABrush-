# CABrush AVS 0.1.1 Architecture — Surface Truth

## Source of truth

The sculpt is `AvsVolume`, a sparse signed-distance field. Render triangles are never user data.

Field convention:

- `SDF < 0`: inside
- `SDF > 0`: outside
- zero crossing: surface

This convention is also the orientation contract: **outward means increasing scalar value**.

## Why Surface Truth exists

AVS 0.1 proved that volume sculpting removes the fixed-topology growth wall, but the first Android test exposed a separate cache failure: a field could remain usable while generated triangles had incorrect winding and disappeared under back-face culling.

That failure is deliberately isolated here. Clay behavior is not the focus of 0.1.1.

## Conforming cell decomposition

Each regular grid cube is decomposed into the same six tetrahedra around corner diagonal `0 -> 7`.

Using the same translated decomposition everywhere ensures neighboring cubes use the same diagonal on their shared face. Marching Tetrahedra therefore avoids the ambiguous face configurations of classic Marching Cubes and produces a deterministic local topology for the sampled field.

Each brick owns cells whose minimum lattice corner lies inside that brick. Cell corners at local coordinate 8 read the neighboring global sample. No cell is duplicated across chunks.

## Canonical edge intersections

A surface vertex lives on an edge whose scalar endpoints have opposite signs.

Before interpolation, AVS orders the two **global lattice coordinates** lexicographically. This means both chunks adjacent to the same physical edge evaluate:

    t = valueA / (valueA - valueB)
    p = A + (B - A) * t

with the same A/B order and the same floating-point operation sequence.

The goal is stronger than "close enough after welding": shared seam positions should be bit-identical.

## Deterministic triangle winding

Shading normals are not geometry truth.

For every tetrahedron AVS reconstructs the gradient of the tetrahedron's exact linear scalar interpolant. Given tetrahedron point `p0` and edge vectors `e1/e2/e3`, the gradient `g` satisfies:

    dot(g, e1) = f1 - f0
    dot(g, e2) = f2 - f0
    dot(g, e3) = f3 - f0

The implementation solves this with reciprocal-basis cross products.

Because AVS defines positive SDF as outside, `g` points toward the outside half-space. Every generated triangle is swapped when necessary so:

    dot(faceNormal, g) > 0

This handles the paired Marching-Tetrahedra sign cases explicitly and does not depend on a noisy central-difference normal after repeated CSG.

## Near-zero samples and tiny triangles

AVS quantization intentionally prevents exact zero lattice samples. A surface can still pass extremely close to a lattice point and produce very small but topologically necessary triangles.

Surface Truth testing found that discarding those triangles by an aggressive area threshold creates pinholes. The extractor therefore rejects only truly zero/non-finite faces and preserves tiny legal closure triangles.

Future quality extraction may regularize these areas, but it must do so without breaking topology.

## Shading normals

After topology and winding are final, the chunk accumulates geometric face normals. At each vertex it also samples the SDF gradient.

If the SDF gradient agrees with the oriented geometric normal, they are blended for smoother cross-chunk shading. Near sharp CSG transitions where the sampled gradient disagrees, the geometric normal wins.

Normals are presentation data only. They never flip a triangle.

## Android raster contract

The runtime explicitly uses:

    glFrontFace(GL_CCW)
    glCullFace(GL_BACK)
    glEnable(GL_CULL_FACE)

VSS screenshots use an equivalent CCW/front-facing camera-space test. This makes CI evidence reproduce the class of failure seen on Android rather than hiding it with two-sided rendering.

## Surface audit

`tools/AvsSurfaceAudit.java` conceptually welds duplicate chunk vertices and validates the output surface.

For each surface it measures:

- boundary edge count
- non-manifold edge count
- same-direction shared edges
- duplicate triangles
- degenerate triangles
- exact seam-position mismatches
- signed volume
- SDF-side orientation agreement
- shading-normal agreement
- deterministic surface SHA-256

For a closed consistently oriented surface every shared undirected edge must occur exactly twice and the two directed occurrences must cancel.

## VSS negative controls

The verification system mutates a known-good baseline surface in two controlled ways:

1. swap two indices of one triangle
2. remove one triangle

The first must trigger winding/orientation evidence. The second must produce boundary edges. This verifies the verifier itself before trusting a green build.

## Incremental cache equivalence

A local sculpt edit is first extracted using the normal dirty-brick path. Then the entire surface cache is discarded and regenerated from the same `AvsVolume`.

The canonical surface hashes must match exactly.

This proves that dirty-cache history cannot change the rendered geometry.

## What 0.1.1 intentionally does not solve

- production Clay feel
- adaptive/multiresolution bricks
- voxel pruning
- Smooth/Grab
- sharp-feature preservation beyond the current sampled field
- micro-detail/displacement tiles
- high-end mesh export topology

Those remain blocked until Surface Truth passes CI and the real 32-bit Android test.
