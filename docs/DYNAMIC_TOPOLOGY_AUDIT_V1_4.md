# PocketSculpt V1.4 — Dynamic Topology / Remesh Audit

## Goal

V1.4 removes the largest character-sculpting limit from V1.3: brushes no longer have to keep deforming one fixed triangle layout forever.

The implementation is deliberately a **surface adaptive triangle remesher**, not a fake "voxel" button. It follows the same broad family of operations used by established isotropic remeshing systems: refine long edges, remove clearly over-dense short edges, then continue sculpting on the rebuilt surface.

## What changed

### Dynamic topology before the brush

When DynTopo is enabled, the renderer refines the brush region before Clay+, Clay-, Smooth, or the initial Grab capture.

- Detail is brush-relative.
- Small brushes request shorter target edges and therefore more local geometry.
- Large brushes keep coarser topology for faster blockout work.
- Long edges are split before the sculpt deformation so a small brush is not forced to stretch one giant triangle.
- Smooth may also collapse clearly over-dense short edges.
- Symmetry refines both sides when the mirrored brushes are separated enough to need independent topology.

This matches the important behavior of dynamic-topology sculpt systems: **topology adapts before the sculpt operation**.

### Manual Remesh

The new Remesh button runs an adaptive whole-surface pass using the current Size + Detail settings.

It:

1. splits edges that are much longer than the target,
2. safely collapses edges that are much shorter than the target,
3. compacts unused vertices,
4. rejects the entire candidate if the result is not a closed two-manifold,
5. rebuilds adjacency, incident-triangle maps, normals, geometry guard baselines, and GPU buffers.

This is an incremental triangle remesh. A true volumetric/voxel remesher can still be added later for unioning self-intersections and major silhouette resets.

### Topology-safe undo/redo

V1.3 history stored positions only. That would be invalid the instant a remesher changed vertex or index counts.

V1.4 history snapshots now contain:

- vertex positions,
- triangle indices.

Undo/Redo/Reset can therefore cross topology changes safely. Reset restores the pristine chosen base even after many remeshes.

### GPU buffer recreation

Changing topology changes array sizes. V1.4 tracks a topology generation and reallocates position, normal, and index buffers whenever the mesh connectivity changes instead of writing new geometry into stale fixed-size OpenGL buffers.

### Mobile performance controls

The target includes 32-bit Android, so topology growth is bounded:

- maximum 48,000 vertices,
- maximum 96,000 triangles,
- limited topology operations per brush dab,
- non-overlapping long-edge splits are batched from one edge map,
- manual whole-mesh remesh has a finite operation budget.

Batching reduced the dynamic-topology stress-test runtime by roughly half in the patching environment versus rebuilding the entire edge map once per split.

## Brush tuning

The Size slider now reaches substantially smaller world-space radii, which is required for faces and other character detail. Strength also starts lower so small-detail Clay and Smooth work is controllable instead of jumping immediately into blockout-scale displacement.

Clay+, Clay-, Smooth, Grab, symmetry, and the V1.2 transactional geometry guard remain active on top of the new adaptive topology.

## Manifold safety

Long-edge splitting is conforming: both triangles sharing an interior edge are split together, preserving the closed surface.

Short-edge collapse is guarded by a triangle-mesh link condition. The candidate collapse is also checked for local face degeneration/orientation reversal, then the rebuilt mesh must still pass a full two-manifold edge-count test before it can replace the live mesh.

If any topology candidate fails, the live sculpt is left untouched.

## Automated validation

`tools/SculptCoreSelfTest.java` now covers:

- closed/healthy icosphere and humanoid bases,
- Clay+ outward build,
- Clay- inward cut,
- useful Smooth response,
- Grab proportion movement,
- local DynTopo refinement increasing local vertex/triangle density,
- sculpting after a topology change,
- whole-surface remesh,
- topology-aware state restore,
- mixed dynamic-topology character stress with periodic manifold/health checks,
- existing fixed-topology stress/regression coverage.

## Remaining topology work

V1.4 is the first real adaptive-topology version, not the final remesher.

Useful later upgrades:

- quality-driven edge flips,
- tangential relaxation after global remesh,
- closest-surface reprojection for stronger remesh passes,
- spatial acceleration for raycasts and topology queries,
- true voxel remesh for resolving self-intersections / joining overlapping volumes,
- multiresolution sculpting after the basic brush/remesh workflow is proven on-device.

The existing transactional geometry validator should remain the final line of defense around future topology operations.
