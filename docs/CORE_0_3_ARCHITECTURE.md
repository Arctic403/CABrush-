# CABrush Core 0.3 Architecture

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
