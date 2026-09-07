# CABrush Core 0.3 / 0.3.1 Architecture

## Goal

Build the engine underneath sculpting before tuning more brushes.

## Research-derived design choices

Mature sculpt systems separate mesh storage, spatial acceleration, brush runtime, topology operations, and rendering. Blender's sculpt architecture uses different storage backends and a Paint BVH for raycasts, coarse filtering, and selective GPU work. CABrush mirrors the separation without copying Blender's scale or complexity.

Half-edge connectivity is a standard fit for mutable polygon topology because split/collapse/flip operations constantly need edge twins and local adjacency. CABrush stores runtime half-edges in packed primitive arrays to stay friendly to 32-bit Android memory.

Incremental isotropic remeshing literature and CGAL use the sequence split long edges, collapse short edges, flip edges, relax, then reproject. Core 0.3 therefore implements and fuzzes the elementary topology primitives before any DynTopo/remesher is allowed back into the UI.

Android's vertex-data guidance recommends chunking indexed meshes to fit 16-bit index buffers. `RenderChunkBuilder` caps each chunk at 65,535 unique vertices.

## Subsystems

### MeshKernel
Source of truth. Stable vertex/face IDs, packed arrays, free lists, runtime half-edge connectivity, normals, topology/geometry generations, hard resource limits.

### GeometryValidator
Central invariants: finite coordinates, valid references, nondegenerate faces, triangle quality, local edge ratio, duplicate faces, directed-edge duplicates, manifold incidence, and half-edge twin consistency.

### GeometryTransaction
Vertex edits use a compact position journal. Topology edits use `MeshSnapshot`. Invalid edits roll back before callers observe a partial mesh.

### GeometryOps
`splitEdge`, `collapseEdge`, `flipEdge`, `relaxVertex`. No brush owns topology mutation.

### SculptBvh
BVH over live faces. Rebuild after topology changes, refit after geometry changes. VSS compares accelerated ray hits against brute-force reference hits.

### StrokeContext
Reusable per-dab state. Future Clay, Smooth, Grab, Crease, etc. consume the same stroke contract.

### RenderChunkBuilder
Stable-ID CPU mesh is converted to local 16-bit render chunks. This decouples sculpt topology from GPU index constraints.

### MeshSnapshot
Deterministic versioned serialization/hash basis for rollback, future undo/redo, crash recovery, and reproducible VSS.

## Known Core 0.3 boundaries

- topology transactions still use full snapshots; production DynTopo should later use local topology journals
- BVH refit currently refits the tree, not a minimal ancestor path
- normal rebuild is currently global after a committed vertex edit
- render geometry refresh currently updates changed chunks rather than persistent GPU subranges
- edge IDs are runtime half-edge IDs; stable vertex/face IDs are the persisted identity layer
- there is no projection/remeshing policy yet

Those are explicit engine boundaries, not hidden brush behavior.

## Gate before brushes expand

Do not re-add DynTopo, remesh, Smooth, Grab, or character tooling until:
1. VSS stays green.
2. The VSS dump/screenshots are inspected.
3. The real 32-bit target survives long sessions.
4. Kernel memory and p95 timings remain inside acceptable mobile budgets.


## Core 0.3.1 geometry-truth hardening

The Core 0.3 VSS artifact exposed an important verification bug: structural validity is necessary but not sufficient. The topology fuzz remained a closed two-manifold while visibly developing stretched shards, and the Clay smoke consumer could stay structurally valid while building a narrow extrusion.

Core 0.3.1 hardens the architecture around that finding:

- topology edits must pass a geometry-transition validator, not only the final half-edge validator
- edge collapse obeys the closed-manifold link condition
- collapse placement is scored for triangle quality and bounded normal change
- arbitrary long edges are not accepted as remeshing collapse candidates
- flips are quality-filtered and may safely decline
- changed topology is checked for new non-adjacent triangle intersections
- VSS has a known-folded-mesh self-intersection detector test
- topology fuzz is judged against surface area, signed volume, radial envelope, maximum edge growth, and minimum triangle quality
- Clay rejection reasons are emitted into the dump so saturation has an explanation instead of a generic `false`

### Why these guards exist

Surface simplification/remeshing systems commonly separate two questions:

1. **Can this topological mutation preserve manifold connectivity?**
2. **Should this mutation be accepted geometrically?**

A link-condition-safe collapse can still change face normals badly or create a self-intersection. Likewise, a manifold mesh can still be visually unusable. CABrush therefore treats topology validity, geometry quality, and visual-evidence metrics as separate gates.

### Remaining deliberate boundary

Core 0.3.1 still does not claim production self-intersection prevention for every high-frequency Clay vertex edit. Topology transactions get the expensive local changed-face intersection guard. Sculpt vertex transactions get bounded face-normal/area motion and central triangle-quality checks. A future BVH-assisted intersection query can make local sculpt self-intersection checks affordable without turning every dab into an O(brush_faces × mesh_faces) operation.
