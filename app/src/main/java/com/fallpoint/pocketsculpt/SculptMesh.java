package com.fallpoint.pocketsculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SculptMesh {
    float[] positions;
    float[] normals;
    int[] indices;

    // Runtime topology is intentionally mutable from V1.4 onward. The reset
    // mesh is kept separately so Remesh/DynTopo can rebuild connectivity while
    // Reset still returns to the chosen pristine primitive/base.
    private static final float MAX_EDGE_STRETCH = 2.35f;
    private static final float MIN_EDGE_COMPRESSION = 0.38f;
    private static final float MIN_REST_AREA_RATIO = 0.16f;
    private static final float MIN_STEP_AREA_RATIO = 0.52f;
    private static final float MIN_STEP_NORMAL_DOT = 0.32f;
    private static final float MIN_TRIANGLE_QUALITY = 0.07f;
    private static final float MAX_STEP_EDGE_GROWTH = 1.28f;
    private static final float MIN_STEP_EDGE_SHRINK = 0.76f;
    private static final int MAX_LINE_SEARCH_STEPS = 7;

    private final float[] resetPositions;
    private final int[] resetIndices;
    private float[] restPositions;
    private int[][] neighbors;
    private int[][] incidentTriangles;
    private float[] restTriangleArea2;

    // Reused transaction buffers are rebuilt only when topology changes.
    private float[] beforeScratch;
    private float[] candidateScratch;
    private float[] deltaScratch;
    private boolean[] touchedTriangleMask;
    private int[] touchedTriangles;
    private int[] traversalStamp;
    private int[] traversalQueue;
    private int[] selectionVerticesScratch;
    private float[] selectionWeightsScratch;
    private long topologyVersion = 1L;
    private final float[] normalScratchOld = new float[3];
    private final float[] normalScratchNew = new float[3];
    private int traversalGeneration = 1;

    static final class Hit {
        final float x, y, z;
        final float nx, ny, nz;
        final float t;

        Hit(float x, float y, float z, float nx, float ny, float nz, float t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.t = t;
        }
    }

    static final class GrabHandle {
        final int[] vertices;
        final float[] weights;
        final float[] basePositions;
        final int count;

        GrabHandle(int[] vertices, float[] weights, float[] basePositions, int count) {
            this.vertices = vertices;
            this.weights = weights;
            this.basePositions = basePositions;
            this.count = count;
        }
    }

    private SculptMesh(float[] positions, int[] indices) {
        this.resetPositions = positions.clone();
        this.resetIndices = indices.clone();
        installTopology(positions.clone(), indices.clone(), true);
    }

    static final class MeshState {
        final float[] positions;
        final int[] indices;

        MeshState(float[] positions, int[] indices) {
            this.positions = positions;
            this.indices = indices;
        }
    }

    MeshState captureState() {
        return new MeshState(positions.clone(), indices.clone());
    }

    boolean restoreState(MeshState state) {
        if (state == null || state.positions == null || state.indices == null) return false;
        if (state.positions.length < 9 || state.positions.length % 3 != 0
                || state.indices.length < 3 || state.indices.length % 3 != 0) return false;
        for (float value : state.positions) if (!Float.isFinite(value)) return false;
        int vertexCount = state.positions.length / 3;
        for (int index : state.indices) if (index < 0 || index >= vertexCount) return false;
        installTopology(state.positions.clone(), state.indices.clone(), true);
        return true;
    }

    long topologyVersion() {
        return topologyVersion;
    }

    int vertexCount() {
        return positions.length / 3;
    }

    int triangleCount() {
        return indices.length / 3;
    }

    private void installTopology(float[] newPositions, int[] newIndices, boolean resetRest) {
        this.positions = newPositions;
        this.indices = newIndices;
        this.normals = new float[newPositions.length];
        this.neighbors = buildNeighbors(newPositions.length / 3, newIndices);
        this.incidentTriangles = buildIncidentTriangles(newPositions.length / 3, newIndices);

        if (resetRest || restPositions == null || restPositions.length != newPositions.length) {
            this.restPositions = newPositions.clone();
        }
        this.restTriangleArea2 = new float[newIndices.length / 3];

        this.beforeScratch = new float[newPositions.length];
        this.candidateScratch = new float[newPositions.length];
        this.deltaScratch = new float[newPositions.length];
        this.touchedTriangleMask = new boolean[newIndices.length / 3];
        this.touchedTriangles = new int[newIndices.length / 3];
        int vertexCount = newPositions.length / 3;
        this.traversalStamp = new int[vertexCount];
        this.traversalQueue = new int[vertexCount];
        this.selectionVerticesScratch = new int[vertexCount];
        this.selectionWeightsScratch = new float[vertexCount];
        this.traversalGeneration = 1;

        for (int triangle = 0; triangle < restTriangleArea2.length; triangle++) {
            restTriangleArea2[triangle] = triangleArea2(restPositions, triangle);
        }
        recalculateNormals();
        topologyVersion++;
    }

    static SculptMesh createIcoSphere(int subdivisions, float radius) {
        if (subdivisions < 0 || subdivisions > 6) {
            throw new IllegalArgumentException("subdivisions must be in [0, 6]");
        }
        if (!(radius > 0f) || !Float.isFinite(radius)) {
            throw new IllegalArgumentException("radius must be finite and > 0");
        }

        final float t = (1f + (float) Math.sqrt(5.0)) * 0.5f;
        List<float[]> vertices = new ArrayList<>();
        addUnit(vertices, -1,  t,  0);
        addUnit(vertices,  1,  t,  0);
        addUnit(vertices, -1, -t,  0);
        addUnit(vertices,  1, -t,  0);
        addUnit(vertices,  0, -1,  t);
        addUnit(vertices,  0,  1,  t);
        addUnit(vertices,  0, -1, -t);
        addUnit(vertices,  0,  1, -t);
        addUnit(vertices,  t,  0, -1);
        addUnit(vertices,  t,  0,  1);
        addUnit(vertices, -t,  0, -1);
        addUnit(vertices, -t,  0,  1);

        List<int[]> faces = new ArrayList<>();
        int[][] baseFaces = {
                {0,11,5}, {0,5,1}, {0,1,7}, {0,7,10}, {0,10,11},
                {1,5,9}, {5,11,4}, {11,10,2}, {10,7,6}, {7,1,8},
                {3,9,4}, {3,4,2}, {3,2,6}, {3,6,8}, {3,8,9},
                {4,9,5}, {2,4,11}, {6,2,10}, {8,6,7}, {9,8,1}
        };
        faces.addAll(Arrays.asList(baseFaces));

        for (int level = 0; level < subdivisions; level++) {
            Map<Long, Integer> midpointCache = new HashMap<>();
            List<int[]> refined = new ArrayList<>(faces.size() * 4);
            for (int[] f : faces) {
                int a = midpoint(vertices, midpointCache, f[0], f[1]);
                int b = midpoint(vertices, midpointCache, f[1], f[2]);
                int c = midpoint(vertices, midpointCache, f[2], f[0]);
                refined.add(new int[]{f[0], a, c});
                refined.add(new int[]{f[1], b, a});
                refined.add(new int[]{f[2], c, b});
                refined.add(new int[]{a, b, c});
            }
            faces = refined;
        }

        float[] outPositions = new float[vertices.size() * 3];
        for (int i = 0; i < vertices.size(); i++) {
            float[] p = vertices.get(i);
            outPositions[i * 3] = p[0] * radius;
            outPositions[i * 3 + 1] = p[1] * radius;
            outPositions[i * 3 + 2] = p[2] * radius;
        }

        int[] outIndices = new int[faces.size() * 3];
        int k = 0;
        for (int[] f : faces) {
            int a = f[0], b = f[1], c = f[2];
            if (!isOutward(outPositions, a, b, c)) {
                int tmp = b;
                b = c;
                c = tmp;
            }
            outIndices[k++] = a;
            outIndices[k++] = b;
            outIndices[k++] = c;
        }

        return new SculptMesh(outPositions, outIndices);
    }

    static SculptMesh createHumanBase() {
        // Low-resolution watertight character blockout generated from a smooth
        // implicit union. Marching tetrahedra gives us one connected triangle
        // surface with no intersecting "primitive shells" inside the body.
        final int nx = 22;
        final int ny = 32;
        final int nz = 16;
        final float xmin = -1.05f, xmax = 1.05f;
        final float ymin = -1.68f, ymax = 1.72f;
        final float zmin = -0.65f, zmax = 0.65f;
        final int pointCount = nx * ny * nz;

        float[] gridPositions = new float[pointCount * 3];
        float[] field = new float[pointCount];

        for (int k = 0; k < nz; k++) {
            float z = lerp(zmin, zmax, k / (float) (nz - 1));
            for (int j = 0; j < ny; j++) {
                float y = lerp(ymin, ymax, j / (float) (ny - 1));
                for (int i = 0; i < nx; i++) {
                    float x = lerp(xmin, xmax, i / (float) (nx - 1));
                    int id = gridId(i, j, k, nx, ny);
                    int base = id * 3;
                    gridPositions[base] = x;
                    gridPositions[base + 1] = y;
                    gridPositions[base + 2] = z;
                    field[id] = humanField(x, y, z);
                }
            }
        }

        final int[][] cubeCorners = {
                {0,0,0}, {1,0,0}, {1,1,0}, {0,1,0},
                {0,0,1}, {1,0,1}, {1,1,1}, {0,1,1}
        };
        final int[][] tetrahedra = {
                {0,1,2,6}, {0,2,3,6}, {0,3,7,6},
                {0,7,4,6}, {0,4,5,6}, {0,5,1,6}
        };

        List<float[]> vertices = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        Map<Long, Integer> edgeCache = new HashMap<>();

        int[] cube = new int[8];
        int[] tet = new int[4];

        for (int k = 0; k < nz - 1; k++) {
            for (int j = 0; j < ny - 1; j++) {
                for (int i = 0; i < nx - 1; i++) {
                    for (int c = 0; c < 8; c++) {
                        int[] o = cubeCorners[c];
                        cube[c] = gridId(i + o[0], j + o[1], k + o[2], nx, ny);
                    }

                    for (int[] tetra : tetrahedra) {
                        for (int q = 0; q < 4; q++) tet[q] = cube[tetra[q]];
                        polygonizeTetra(
                                tet,
                                gridPositions,
                                field,
                                edgeCache,
                                vertices,
                                faces
                        );
                    }
                }
            }
        }

        if (vertices.isEmpty() || faces.isEmpty()) {
            throw new IllegalStateException("Human base polygonization produced no surface");
        }

        float[] outPositions = new float[vertices.size() * 3];
        for (int i = 0; i < vertices.size(); i++) {
            float[] p = vertices.get(i);
            outPositions[i * 3] = p[0];
            outPositions[i * 3 + 1] = p[1];
            outPositions[i * 3 + 2] = p[2];
        }

        int[] outIndices = new int[faces.size() * 3];
        int out = 0;
        for (int[] face : faces) {
            outIndices[out++] = face[0];
            outIndices[out++] = face[1];
            outIndices[out++] = face[2];
        }

        SculptMesh mesh = new SculptMesh(outPositions, outIndices);
        if (!mesh.isClosedTwoManifold()) {
            throw new IllegalStateException("Human base must be a closed two-manifold");
        }
        if (!mesh.isHealthy()) {
            throw new IllegalStateException("Human base failed geometry health validation");
        }
        return mesh;
    }


    boolean applyBrush(
            float cx, float cy, float cz,
            float hitNx, float hitNy, float hitNz,
            float[] viewDirection,
            float radius,
            float strength,
            BrushMode mode,
            boolean frontFaceOnly
    ) {
        if (!(radius > 0f) || !(strength > 0f) || mode == null) return false;
        if (mode == BrushMode.GRAB) return false;
        if (!allFinite(cx, cy, cz, hitNx, hitNy, hitNz, radius, strength)) return false;

        Selection selection = collectSelection(
                cx, cy, cz,
                hitNx, hitNy, hitNz,
                radius,
                viewDirection,
                frontFaceOnly
        );
        if (selection.count == 0) return false;

        if (mode == BrushMode.SMOOTH) {
            return applySmooth(selection, strength);
        }

        System.arraycopy(positions, 0, beforeScratch, 0, positions.length);
        Arrays.fill(deltaScratch, 0f);

        float[] brushNormal = averagedSelectionNormal(selection, hitNx, hitNy, hitNz);
        float nx = brushNormal[0];
        float ny = brushNormal[1];
        float nz = brushNormal[2];
        if (length(nx, ny, nz) < 1e-6f) return false;

        // Clay is plane-based rather than a generic "move every point along a
        // normal" brush. This both builds/removes volume and gently levels the
        // active patch, which is far more useful for anatomy blockout.
        float clayStep = Math.min(radius * 0.14f, strength * 1.35f);
        clayStep = Math.max(clayStep, radius * 0.012f);
        float targetPlane = mode == BrushMode.ADD ? clayStep : -clayStep;
        boolean anyDelta = false;

        for (int s = 0; s < selection.count; s++) {
            int vertex = selection.vertices[s];
            int base = vertex * 3;

            float relX = beforeScratch[base] - cx;
            float relY = beforeScratch[base + 1] - cy;
            float relZ = beforeScratch[base + 2] - cz;
            float signedDistance = relX * nx + relY * ny + relZ * nz;

            float raw;
            if (mode == BrushMode.ADD) {
                raw = Math.max(0f, targetPlane - signedDistance);
            } else {
                raw = Math.min(0f, targetPlane - signedDistance);
            }

            // Weight twice: once for the normal clay falloff and once again at
            // the rim so large brushes blend into surrounding anatomy instead
            // of leaving a hard circular ledge.
            float w = selection.weights[s];
            float amount = raw * w * (0.35f + 0.65f * w);

            float currentEdge = minNeighborEdgeLengthFrom(beforeScratch, vertex);
            float restEdge = minNeighborEdgeLengthFrom(restPositions, vertex);
            float maxMove = Math.max(
                    0.00035f,
                    Math.min(currentEdge * 0.12f, restEdge * 0.16f)
            );
            amount = clamp(amount, -maxMove, maxMove);

            deltaScratch[base] = nx * amount;
            deltaScratch[base + 1] = ny * amount;
            deltaScratch[base + 2] = nz * amount;
            anyDelta |= Math.abs(amount) > 1e-9f;
        }

        return anyDelta && commitTransactional(selection, beforeScratch, deltaScratch);
    }

    GrabHandle beginGrab(
            float cx, float cy, float cz,
            float hitNx, float hitNy, float hitNz,
            float[] viewDirection,
            float radius,
            boolean frontFaceOnly
    ) {
        if (!(radius > 0f) || !allFinite(cx, cy, cz, hitNx, hitNy, hitNz, radius)) return null;
        Selection selection = collectSelection(
                cx, cy, cz,
                hitNx, hitNy, hitNz,
                radius,
                viewDirection,
                frontFaceOnly
        );
        if (selection.count == 0) return null;

        int[] selected = Arrays.copyOf(selection.vertices, selection.count);
        float[] weights = Arrays.copyOf(selection.weights, selection.count);
        float[] base = new float[selection.count * 3];
        for (int s = 0; s < selection.count; s++) {
            int vertexBase = selected[s] * 3;
            base[s * 3] = positions[vertexBase];
            base[s * 3 + 1] = positions[vertexBase + 1];
            base[s * 3 + 2] = positions[vertexBase + 2];
        }
        return new GrabHandle(selected, weights, base, selection.count);
    }

    boolean applyGrab(GrabHandle handle, float dx, float dy, float dz, float strength) {
        if (handle == null || handle.count == 0) return false;
        if (!allFinite(dx, dy, dz, strength)) return false;

        float dragLength = length(dx, dy, dz);
        if (dragLength < 1e-7f) return false;

        // Grab is a proportion tool, not a tiny normal-displacement brush.
        // Preserve a meaningful response even when the clay strength slider is low.
        float response = clamp(0.28f + strength * 22f, 0.35f, 1.15f);

        System.arraycopy(positions, 0, beforeScratch, 0, positions.length);
        Arrays.fill(deltaScratch, 0f);

        int[] vertices = handle.vertices;
        float[] weights = handle.weights;
        for (int s = 0; s < handle.count; s++) {
            int vertex = vertices[s];
            int base = vertex * 3;
            int hb = s * 3;
            float w = weights[s];
            float targetX = handle.basePositions[hb] + dx * w * response;
            float targetY = handle.basePositions[hb + 1] + dy * w * response;
            float targetZ = handle.basePositions[hb + 2] + dz * w * response;

            float moveX = targetX - beforeScratch[base];
            float moveY = targetY - beforeScratch[base + 1];
            float moveZ = targetZ - beforeScratch[base + 2];

            float currentEdge = minNeighborEdgeLengthFrom(beforeScratch, vertex);
            float restEdge = minNeighborEdgeLengthFrom(restPositions, vertex);
            float maxMove = Math.max(
                    0.0005f,
                    Math.min(currentEdge * 0.22f, restEdge * 0.30f)
            );
            float moveLength = length(moveX, moveY, moveZ);
            if (moveLength > maxMove && moveLength > 1e-8f) {
                float k = maxMove / moveLength;
                moveX *= k;
                moveY *= k;
                moveZ *= k;
            }

            deltaScratch[base] = moveX;
            deltaScratch[base + 1] = moveY;
            deltaScratch[base + 2] = moveZ;
        }

        Selection stable = new Selection(vertices, weights, handle.count);
        return commitTransactional(stable, beforeScratch, deltaScratch);
    }

    private float[] averagedSelectionNormal(
            Selection selection,
            float hitNx,
            float hitNy,
            float hitNz
    ) {
        float nx = 0f, ny = 0f, nz = 0f, total = 0f;
        for (int s = 0; s < selection.count; s++) {
            int vertex = selection.vertices[s];
            float w = selection.weights[s];
            int base = vertex * 3;
            nx += normals[base] * w;
            ny += normals[base + 1] * w;
            nz += normals[base + 2] * w;
            total += w;
        }

        if (total > 1e-6f) {
            nx /= total;
            ny /= total;
            nz /= total;
        } else {
            nx = hitNx;
            ny = hitNy;
            nz = hitNz;
        }

        float nLen = length(nx, ny, nz);
        if (nLen < 1e-6f) {
            nx = hitNx;
            ny = hitNy;
            nz = hitNz;
            nLen = length(nx, ny, nz);
        }
        if (nLen < 1e-6f) return new float[]{0f, 0f, 0f};

        nx /= nLen;
        ny /= nLen;
        nz /= nLen;

        float hitLen = length(hitNx, hitNy, hitNz);
        if (hitLen > 1e-6f) {
            float hnx = hitNx / hitLen;
            float hny = hitNy / hitLen;
            float hnz = hitNz / hitLen;
            if (nx * hnx + ny * hny + nz * hnz < 0f) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
        }
        return new float[]{nx, ny, nz};
    }


    // --- V1.4 adaptive triangle remeshing ---------------------------------
    // This is deliberately an incremental surface remesher rather than a
    // voxelizer. Long edges are split before sculpting so small brushes gain
    // real geometry; short edges can be collapsed to keep density bounded.
    // Every topology edit is assembled off-mesh and only installed if the
    // result remains a closed two-manifold.
    private static final int MAX_DYNAMIC_VERTICES = 48_000;
    private static final int MAX_DYNAMIC_TRIANGLES = 96_000;

    boolean remeshBrushRegion(
            float cx, float cy, float cz,
            float radius,
            float targetEdge,
            int operationBudget,
            boolean allowCollapse
    ) {
        if (!allFinite(cx, cy, cz, radius, targetEdge)) return false;
        if (!(radius > 0f) || !(targetEdge > 0f) || operationBudget <= 0) return false;
        return remeshAdaptive(
                cx, cy, cz,
                radius * 1.18f,
                clamp(targetEdge, 0.018f, 0.24f),
                Math.min(48, operationBudget),
                allowCollapse,
                true
        );
    }

    boolean remeshUniform(float targetEdge, int operationBudget) {
        if (!(targetEdge > 0f) || !Float.isFinite(targetEdge) || operationBudget <= 0) return false;
        return remeshAdaptive(
                0f, 0f, 0f,
                Float.POSITIVE_INFINITY,
                clamp(targetEdge, 0.018f, 0.24f),
                Math.min(900, operationBudget),
                true,
                false
        );
    }

    private boolean remeshAdaptive(
            float cx, float cy, float cz,
            float regionRadius,
            float targetEdge,
            int operationBudget,
            boolean allowCollapse,
            boolean local
    ) {
        MutableTopology mutable = MutableTopology.fromArrays(positions, indices);
        int remaining = operationBudget;
        int changed = 0;

        // Split first. Refinement runs before the sculpt dab so a small brush
        // never has to pull one huge triangle into a character feature. Batch
        // non-overlapping edge splits from one edge map to keep this usable on
        // 32-bit phones instead of rebuilding the full edge map per split.
        int splitCount = splitLongEdgesBatched(
                mutable,
                cx, cy, cz,
                regionRadius,
                targetEdge * 1.34f,
                remaining,
                local
        );
        changed += splitCount;
        remaining -= splitCount;

        // Collapse only clearly over-dense edges. The link-condition check in
        // collapseEdge prevents non-manifold pinches and duplicate local fans.
        if (allowCollapse) {
            int collapseBudget = Math.max(1, operationBudget / 3);
            while (remaining > 0 && collapseBudget-- > 0) {
                EdgeRecord edge = findShortestEdge(
                        mutable, cx, cy, cz, regionRadius,
                        targetEdge * 0.58f, local
                );
                if (edge == null) break;
                if (collapseEdge(mutable, edge)) {
                    changed++;
                    remaining--;
                } else {
                    edge.blocked = true;
                    if (!hasCollapsibleShortEdge(
                            mutable, cx, cy, cz, regionRadius,
                            targetEdge * 0.58f, local, edge.key
                    )) break;
                    // Avoid getting stuck selecting the same rejected edge.
                    if (!collapseFirstOtherShortEdge(
                            mutable, cx, cy, cz, regionRadius,
                            targetEdge * 0.58f, local, edge.key
                    )) break;
                    changed++;
                    remaining--;
                }
            }
        }

        if (changed == 0) return false;

        MeshArrays compact = mutable.compact();
        if (compact.positions.length / 3 > MAX_DYNAMIC_VERTICES
                || compact.indices.length / 3 > MAX_DYNAMIC_TRIANGLES) return false;
        if (!closedTwoManifold(compact.indices)) return false;
        if (!allFiniteArray(compact.positions)) return false;

        float[] oldPositions = positions.clone();
        int[] oldIndices = indices.clone();
        installTopology(compact.positions, compact.indices, true);
        if (!isHealthy() || !isClosedTwoManifold()) {
            installTopology(oldPositions, oldIndices, true);
            return false;
        }
        return true;
    }

    private static int splitLongEdgesBatched(
            MutableTopology mesh,
            float cx, float cy, float cz,
            float radius,
            float threshold,
            int budget,
            boolean local
    ) {
        int total = 0;
        int passes = 0;
        while (budget > 0
                && passes++ < 4
                && mesh.vertices.size() < MAX_DYNAMIC_VERTICES
                && mesh.faces.size() < MAX_DYNAMIC_TRIANGLES) {
            Map<Long, EdgeRecord> edges = buildEdgeRecords(mesh.faces);
            List<EdgeCandidate> candidates = new ArrayList<>();
            for (EdgeRecord edge : edges.values()) {
                if (edge.count != 2) continue;
                float[] a = mesh.vertices.get(edge.a);
                float[] b = mesh.vertices.get(edge.b);
                if (a == null || b == null) continue;
                float mx = (a[0] + b[0]) * 0.5f;
                float my = (a[1] + b[1]) * 0.5f;
                float mz = (a[2] + b[2]) * 0.5f;
                if (local && distanceSq(mx, my, mz, cx, cy, cz) > radius * radius) continue;
                float len = length(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
                if (len > threshold) candidates.add(new EdgeCandidate(edge, len));
            }
            if (candidates.isEmpty()) break;
            candidates.sort((left, right) -> Float.compare(right.length, left.length));

            int faceCountAtPassStart = mesh.faces.size();
            boolean[] usedFace = new boolean[faceCountAtPassStart];
            int passChanges = 0;
            for (EdgeCandidate candidate : candidates) {
                if (budget <= 0
                        || mesh.vertices.size() >= MAX_DYNAMIC_VERTICES
                        || mesh.faces.size() >= MAX_DYNAMIC_TRIANGLES) break;
                EdgeRecord edge = candidate.edge;
                if (edge.face0 < 0 || edge.face1 < 0
                        || edge.face0 >= faceCountAtPassStart
                        || edge.face1 >= faceCountAtPassStart) continue;
                if (usedFace[edge.face0] || usedFace[edge.face1]) continue;
                if (splitEdge(mesh, edge)) {
                    usedFace[edge.face0] = true;
                    usedFace[edge.face1] = true;
                    total++;
                    passChanges++;
                    budget--;
                }
            }
            if (passChanges == 0) break;
        }
        return total;
    }

    private static EdgeRecord findLongestEdge(
            MutableTopology mesh,
            float cx, float cy, float cz,
            float radius,
            float threshold,
            boolean local
    ) {
        Map<Long, EdgeRecord> edges = buildEdgeRecords(mesh.faces);
        EdgeRecord best = null;
        float bestLength = threshold;
        for (EdgeRecord edge : edges.values()) {
            if (edge.count != 2) continue;
            float[] a = mesh.vertices.get(edge.a);
            float[] b = mesh.vertices.get(edge.b);
            if (a == null || b == null) continue;
            float mx = (a[0] + b[0]) * 0.5f;
            float my = (a[1] + b[1]) * 0.5f;
            float mz = (a[2] + b[2]) * 0.5f;
            if (local && distanceSq(mx, my, mz, cx, cy, cz) > radius * radius) continue;
            float length = length(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
            if (length > bestLength) {
                bestLength = length;
                best = edge;
            }
        }
        return best;
    }

    private static EdgeRecord findShortestEdge(
            MutableTopology mesh,
            float cx, float cy, float cz,
            float radius,
            float threshold,
            boolean local
    ) {
        Map<Long, EdgeRecord> edges = buildEdgeRecords(mesh.faces);
        EdgeRecord best = null;
        float bestLength = threshold;
        for (EdgeRecord edge : edges.values()) {
            if (edge.count != 2) continue;
            float[] a = mesh.vertices.get(edge.a);
            float[] b = mesh.vertices.get(edge.b);
            if (a == null || b == null) continue;
            float mx = (a[0] + b[0]) * 0.5f;
            float my = (a[1] + b[1]) * 0.5f;
            float mz = (a[2] + b[2]) * 0.5f;
            if (local && distanceSq(mx, my, mz, cx, cy, cz) > radius * radius) continue;
            float len = length(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
            if (len < bestLength) {
                bestLength = len;
                best = edge;
            }
        }
        return best;
    }

    private static boolean splitEdge(MutableTopology mesh, EdgeRecord edge) {
        if (edge == null || edge.count != 2) return false;
        if (edge.face0 < 0 || edge.face1 < 0) return false;
        float[] a = mesh.vertices.get(edge.a);
        float[] b = mesh.vertices.get(edge.b);
        if (a == null || b == null) return false;

        int midpoint = mesh.vertices.size();
        mesh.vertices.add(new float[]{
                (a[0] + b[0]) * 0.5f,
                (a[1] + b[1]) * 0.5f,
                (a[2] + b[2]) * 0.5f
        });

        int[] f0 = mesh.faces.get(edge.face0);
        int[] f1 = mesh.faces.get(edge.face1);
        int[][] s0 = splitFace(f0, edge.a, edge.b, midpoint);
        int[][] s1 = splitFace(f1, edge.a, edge.b, midpoint);
        if (s0 == null || s1 == null) {
            mesh.vertices.remove(mesh.vertices.size() - 1);
            return false;
        }

        mesh.faces.set(edge.face0, s0[0]);
        mesh.faces.set(edge.face1, s1[0]);
        mesh.faces.add(s0[1]);
        mesh.faces.add(s1[1]);
        return true;
    }

    private static int[][] splitFace(int[] face, int a, int b, int midpoint) {
        for (int i = 0; i < 3; i++) {
            int u = face[i];
            int v = face[(i + 1) % 3];
            int w = face[(i + 2) % 3];
            if (u == a && v == b) {
                return new int[][]{
                        {a, midpoint, w},
                        {midpoint, b, w}
                };
            }
            if (u == b && v == a) {
                return new int[][]{
                        {b, midpoint, w},
                        {midpoint, a, w}
                };
            }
        }
        return null;
    }

    private static boolean collapseEdge(MutableTopology mesh, EdgeRecord edge) {
        if (edge == null || edge.count != 2) return false;
        int keep = edge.a;
        int remove = edge.b;
        float[] a = mesh.vertices.get(keep);
        float[] b = mesh.vertices.get(remove);
        if (a == null || b == null) return false;
        if (!collapseLinkCondition(mesh, edge)) return false;

        float[] midpoint = new float[]{
                (a[0] + b[0]) * 0.5f,
                (a[1] + b[1]) * 0.5f,
                (a[2] + b[2]) * 0.5f
        };

        // Validate all faces in the one-ring before mutating connectivity.
        for (int[] face : mesh.faces) {
            boolean touches = contains(face, keep) || contains(face, remove);
            if (!touches) continue;
            int na = face[0] == remove ? keep : face[0];
            int nb = face[1] == remove ? keep : face[1];
            int nc = face[2] == remove ? keep : face[2];
            if (na == nb || nb == nc || nc == na) continue; // the two edge faces disappear

            float[] oldNormal = faceNormal(mesh.vertices, face[0], face[1], face[2], -1, null);
            float[] newNormal = faceNormal(mesh.vertices, na, nb, nc, keep, midpoint);
            float oldLen = length(oldNormal[0], oldNormal[1], oldNormal[2]);
            float newLen = length(newNormal[0], newNormal[1], newNormal[2]);
            if (!(newLen > 1e-8f) || !(oldLen > 1e-8f)) return false;
            float dot = (oldNormal[0] * newNormal[0]
                    + oldNormal[1] * newNormal[1]
                    + oldNormal[2] * newNormal[2]) / (oldLen * newLen);
            if (!Float.isFinite(dot) || dot < 0.32f) return false;
        }

        List<int[]> rebuilt = new ArrayList<>(mesh.faces.size() - 2);
        for (int[] face : mesh.faces) {
            int x = face[0] == remove ? keep : face[0];
            int y = face[1] == remove ? keep : face[1];
            int z = face[2] == remove ? keep : face[2];
            if (x == y || y == z || z == x) continue;
            rebuilt.add(new int[]{x, y, z});
        }
        float[] oldKeep = mesh.vertices.get(keep);
        float[] oldRemove = mesh.vertices.get(remove);
        mesh.vertices.set(keep, midpoint);
        mesh.vertices.set(remove, null);
        List<int[]> oldFaces = mesh.faces;
        mesh.faces = rebuilt;

        if (!mutableClosedTwoManifold(mesh.faces)) {
            mesh.vertices.set(keep, oldKeep);
            mesh.vertices.set(remove, oldRemove);
            mesh.faces = oldFaces;
            return false;
        }
        return true;
    }

    private static boolean collapseLinkCondition(MutableTopology mesh, EdgeRecord edge) {
        Set<Integer> aNeighbors = new HashSet<>();
        Set<Integer> bNeighbors = new HashSet<>();
        for (int[] face : mesh.faces) {
            if (contains(face, edge.a)) for (int v : face) if (v != edge.a) aNeighbors.add(v);
            if (contains(face, edge.b)) for (int v : face) if (v != edge.b) bNeighbors.add(v);
        }
        aNeighbors.retainAll(bNeighbors);
        aNeighbors.remove(edge.a);
        aNeighbors.remove(edge.b);
        if (aNeighbors.size() != 2) return false;

        int opposite0 = oppositeVertex(mesh.faces.get(edge.face0), edge.a, edge.b);
        int opposite1 = oppositeVertex(mesh.faces.get(edge.face1), edge.a, edge.b);
        return opposite0 >= 0 && opposite1 >= 0 && opposite0 != opposite1
                && aNeighbors.contains(opposite0) && aNeighbors.contains(opposite1);
    }

    private static int oppositeVertex(int[] face, int a, int b) {
        for (int v : face) if (v != a && v != b) return v;
        return -1;
    }

    private static boolean contains(int[] face, int vertex) {
        return face[0] == vertex || face[1] == vertex || face[2] == vertex;
    }

    private static float[] faceNormal(
            List<float[]> vertices,
            int a, int b, int c,
            int overrideVertex,
            float[] overridePosition
    ) {
        float[] pa = a == overrideVertex ? overridePosition : vertices.get(a);
        float[] pb = b == overrideVertex ? overridePosition : vertices.get(b);
        float[] pc = c == overrideVertex ? overridePosition : vertices.get(c);
        float abx = pb[0] - pa[0];
        float aby = pb[1] - pa[1];
        float abz = pb[2] - pa[2];
        float acx = pc[0] - pa[0];
        float acy = pc[1] - pa[1];
        float acz = pc[2] - pa[2];
        return new float[]{
                aby * acz - abz * acy,
                abz * acx - abx * acz,
                abx * acy - aby * acx
        };
    }

    private static Map<Long, EdgeRecord> buildEdgeRecords(List<int[]> faces) {
        Map<Long, EdgeRecord> edges = new HashMap<>(faces.size() * 2);
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            int[] face = faces.get(faceIndex);
            addEdgeRecord(edges, face[0], face[1], faceIndex);
            addEdgeRecord(edges, face[1], face[2], faceIndex);
            addEdgeRecord(edges, face[2], face[0], faceIndex);
        }
        return edges;
    }

    private static void addEdgeRecord(Map<Long, EdgeRecord> edges, int a, int b, int face) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        long key = (((long) min) << 32) | (max & 0xffffffffL);
        EdgeRecord record = edges.get(key);
        if (record == null) {
            record = new EdgeRecord(key, min, max);
            edges.put(key, record);
        }
        if (record.count == 0) record.face0 = face;
        else if (record.count == 1) record.face1 = face;
        record.count++;
    }

    private static boolean mutableClosedTwoManifold(List<int[]> faces) {
        Map<Long, Integer> counts = new HashMap<>(faces.size() * 2);
        for (int[] face : faces) {
            addEdgeCount(counts, face[0], face[1]);
            addEdgeCount(counts, face[1], face[2]);
            addEdgeCount(counts, face[2], face[0]);
        }
        if (counts.isEmpty()) return false;
        for (int count : counts.values()) if (count != 2) return false;
        return true;
    }

    private static boolean closedTwoManifold(int[] meshIndices) {
        Map<Long, Integer> counts = new HashMap<>(meshIndices.length);
        for (int i = 0; i < meshIndices.length; i += 3) {
            addEdgeCount(counts, meshIndices[i], meshIndices[i + 1]);
            addEdgeCount(counts, meshIndices[i + 1], meshIndices[i + 2]);
            addEdgeCount(counts, meshIndices[i + 2], meshIndices[i]);
        }
        if (counts.isEmpty()) return false;
        for (int count : counts.values()) if (count != 2) return false;
        return true;
    }

    private static boolean allFiniteArray(float[] values) {
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static float distanceSq(
            float ax, float ay, float az,
            float bx, float by, float bz
    ) {
        float dx = ax - bx;
        float dy = ay - by;
        float dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean hasCollapsibleShortEdge(
            MutableTopology mesh,
            float cx, float cy, float cz,
            float radius,
            float threshold,
            boolean local,
            long excludedKey
    ) {
        Map<Long, EdgeRecord> edges = buildEdgeRecords(mesh.faces);
        for (EdgeRecord edge : edges.values()) {
            if (edge.key == excludedKey || edge.count != 2) continue;
            float[] a = mesh.vertices.get(edge.a);
            float[] b = mesh.vertices.get(edge.b);
            if (a == null || b == null) continue;
            float mx = (a[0] + b[0]) * 0.5f;
            float my = (a[1] + b[1]) * 0.5f;
            float mz = (a[2] + b[2]) * 0.5f;
            if (local && distanceSq(mx, my, mz, cx, cy, cz) > radius * radius) continue;
            if (length(b[0] - a[0], b[1] - a[1], b[2] - a[2]) < threshold) return true;
        }
        return false;
    }

    private static boolean collapseFirstOtherShortEdge(
            MutableTopology mesh,
            float cx, float cy, float cz,
            float radius,
            float threshold,
            boolean local,
            long excludedKey
    ) {
        Map<Long, EdgeRecord> edges = buildEdgeRecords(mesh.faces);
        List<EdgeRecord> candidates = new ArrayList<>();
        for (EdgeRecord edge : edges.values()) {
            if (edge.key == excludedKey || edge.count != 2) continue;
            float[] a = mesh.vertices.get(edge.a);
            float[] b = mesh.vertices.get(edge.b);
            if (a == null || b == null) continue;
            float mx = (a[0] + b[0]) * 0.5f;
            float my = (a[1] + b[1]) * 0.5f;
            float mz = (a[2] + b[2]) * 0.5f;
            if (local && distanceSq(mx, my, mz, cx, cy, cz) > radius * radius) continue;
            float len = length(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
            if (len < threshold) candidates.add(edge);
        }
        candidates.sort((left, right) -> {
            float[] la = mesh.vertices.get(left.a), lb = mesh.vertices.get(left.b);
            float[] ra = mesh.vertices.get(right.a), rb = mesh.vertices.get(right.b);
            float ll = length(lb[0] - la[0], lb[1] - la[1], lb[2] - la[2]);
            float rl = length(rb[0] - ra[0], rb[1] - ra[1], rb[2] - ra[2]);
            return Float.compare(ll, rl);
        });
        for (EdgeRecord candidate : candidates) if (collapseEdge(mesh, candidate)) return true;
        return false;
    }

    private static final class EdgeCandidate {
        final EdgeRecord edge;
        final float length;

        EdgeCandidate(EdgeRecord edge, float length) {
            this.edge = edge;
            this.length = length;
        }
    }

    private static final class EdgeRecord {
        final long key;
        final int a;
        final int b;
        int face0 = -1;
        int face1 = -1;
        int count = 0;
        boolean blocked = false;

        EdgeRecord(long key, int a, int b) {
            this.key = key;
            this.a = a;
            this.b = b;
        }
    }

    private static final class MeshArrays {
        final float[] positions;
        final int[] indices;

        MeshArrays(float[] positions, int[] indices) {
            this.positions = positions;
            this.indices = indices;
        }
    }

    private static final class MutableTopology {
        final List<float[]> vertices;
        List<int[]> faces;

        MutableTopology(List<float[]> vertices, List<int[]> faces) {
            this.vertices = vertices;
            this.faces = faces;
        }

        static MutableTopology fromArrays(float[] positions, int[] indices) {
            List<float[]> vertices = new ArrayList<>(positions.length / 3);
            for (int i = 0; i < positions.length; i += 3) {
                vertices.add(new float[]{positions[i], positions[i + 1], positions[i + 2]});
            }
            List<int[]> faces = new ArrayList<>(indices.length / 3);
            for (int i = 0; i < indices.length; i += 3) {
                faces.add(new int[]{indices[i], indices[i + 1], indices[i + 2]});
            }
            return new MutableTopology(vertices, faces);
        }

        MeshArrays compact() {
            boolean[] used = new boolean[vertices.size()];
            for (int[] face : faces) {
                used[face[0]] = true;
                used[face[1]] = true;
                used[face[2]] = true;
            }
            int[] remap = new int[vertices.size()];
            Arrays.fill(remap, -1);
            int count = 0;
            for (int i = 0; i < vertices.size(); i++) {
                if (used[i] && vertices.get(i) != null) remap[i] = count++;
            }
            float[] outPositions = new float[count * 3];
            for (int i = 0; i < vertices.size(); i++) {
                int mapped = remap[i];
                if (mapped < 0) continue;
                float[] p = vertices.get(i);
                outPositions[mapped * 3] = p[0];
                outPositions[mapped * 3 + 1] = p[1];
                outPositions[mapped * 3 + 2] = p[2];
            }
            int[] outIndices = new int[faces.size() * 3];
            int out = 0;
            for (int[] face : faces) {
                outIndices[out++] = remap[face[0]];
                outIndices[out++] = remap[face[1]];
                outIndices[out++] = remap[face[2]];
            }
            return new MeshArrays(outPositions, outIndices);
        }
    }


    Hit raycast(float[] origin, float[] direction) {
        if (origin == null || direction == null || origin.length < 3 || direction.length < 3) return null;
        if (!allFinite(origin[0], origin[1], origin[2], direction[0], direction[1], direction[2])) return null;

        float closestT = Float.POSITIVE_INFINITY;
        Hit best = null;

        for (int i = 0; i < indices.length; i += 3) {
            int i0 = indices[i] * 3;
            int i1 = indices[i + 1] * 3;
            int i2 = indices[i + 2] * 3;

            float t = intersectTriangle(
                    origin, direction,
                    positions[i0], positions[i0 + 1], positions[i0 + 2],
                    positions[i1], positions[i1 + 1], positions[i1 + 2],
                    positions[i2], positions[i2 + 1], positions[i2 + 2]
            );

            if (t <= 0f || t >= closestT) continue;

            float abx = positions[i1] - positions[i0];
            float aby = positions[i1 + 1] - positions[i0 + 1];
            float abz = positions[i1 + 2] - positions[i0 + 2];
            float acx = positions[i2] - positions[i0];
            float acy = positions[i2 + 1] - positions[i0 + 1];
            float acz = positions[i2 + 2] - positions[i0 + 2];

            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float len = length(nx, ny, nz);
            if (len < 1e-10f) continue;
            nx /= len;
            ny /= len;
            nz /= len;

            // The nearest visible hit should already be outward-facing on a
            // healthy closed mesh. Orient defensively toward the camera ray.
            if (nx * direction[0] + ny * direction[1] + nz * direction[2] > 0f) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }

            closestT = t;
            best = new Hit(
                    origin[0] + direction[0] * t,
                    origin[1] + direction[1] * t,
                    origin[2] + direction[2] * t,
                    nx, ny, nz, t
            );
        }

        return best;
    }

    void recalculateNormals() {
        Arrays.fill(normals, 0f);

        for (int i = 0; i < indices.length; i += 3) {
            int ia = indices[i] * 3;
            int ib = indices[i + 1] * 3;
            int ic = indices[i + 2] * 3;

            float abx = positions[ib] - positions[ia];
            float aby = positions[ib + 1] - positions[ia + 1];
            float abz = positions[ib + 2] - positions[ia + 2];
            float acx = positions[ic] - positions[ia];
            float acy = positions[ic + 1] - positions[ia + 1];
            float acz = positions[ic + 2] - positions[ia + 2];

            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;

            normals[ia] += nx; normals[ia + 1] += ny; normals[ia + 2] += nz;
            normals[ib] += nx; normals[ib + 1] += ny; normals[ib + 2] += nz;
            normals[ic] += nx; normals[ic + 1] += ny; normals[ic + 2] += nz;
        }

        for (int i = 0; i < normals.length; i += 3) {
            float x = normals[i];
            float y = normals[i + 1];
            float z = normals[i + 2];
            float len = length(x, y, z);
            if (len > 1e-8f && Float.isFinite(len)) {
                normals[i] = x / len;
                normals[i + 1] = y / len;
                normals[i + 2] = z / len;
            } else {
                normals[i] = 0f;
                normals[i + 1] = 0f;
                normals[i + 2] = 0f;
            }
        }
    }

    float[] copyPositions() {
        return positions.clone();
    }

    void setPositions(float[] snapshot) {
        if (snapshot == null || snapshot.length != positions.length) return;
        for (float value : snapshot) {
            if (!Float.isFinite(value)) return;
        }
        System.arraycopy(snapshot, 0, positions, 0, positions.length);
    }

    void reset() {
        installTopology(resetPositions.clone(), resetIndices.clone(), true);
    }

    boolean isHealthy() {
        for (float value : positions) {
            if (!Float.isFinite(value)) return false;
        }
        for (int triangle = 0; triangle < indices.length / 3; triangle++) {
            if (!validateTriangle(restPositions, positions, triangle, false)) return false;
        }
        return true;
    }

    private Selection collectSelection(
            float cx, float cy, float cz,
            float hitNx, float hitNy, float hitNz,
            float radius,
            float[] viewDirection,
            boolean frontFaceOnly
    ) {
        int count = positions.length / 3;
        float radiusSq = radius * radius;
        int seed = -1;
        float bestDistanceSq = Float.POSITIVE_INFINITY;

        float hitLen = length(hitNx, hitNy, hitNz);
        float hnx = hitLen > 1e-6f ? hitNx / hitLen : 0f;
        float hny = hitLen > 1e-6f ? hitNy / hitLen : 0f;
        float hnz = hitLen > 1e-6f ? hitNz / hitLen : 0f;

        for (int i = 0; i < count; i++) {
            int base = i * 3;
            float dx = positions[base] - cx;
            float dy = positions[base + 1] - cy;
            float dz = positions[base + 2] - cz;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > radiusSq) continue;
            if (frontFaceOnly && !isFrontFacing(i, viewDirection)) continue;
            if (hitLen > 1e-6f && !normalCompatible(i, hnx, hny, hnz)) continue;

            if (d2 < bestDistanceSq) {
                bestDistanceSq = d2;
                seed = i;
            }
        }

        if (seed < 0) return Selection.empty();

        int generation = nextTraversalGeneration();
        int head = 0;
        int tail = 0;
        traversalQueue[tail++] = seed;
        traversalStamp[seed] = generation;

        int selectedCount = 0;
        while (head < tail) {
            int i = traversalQueue[head++];
            int base = i * 3;
            float dx = positions[base] - cx;
            float dy = positions[base + 1] - cy;
            float dz = positions[base + 2] - cz;
            float d2 = dx * dx + dy * dy + dz * dz;

            if (d2 > radiusSq) continue;
            if (frontFaceOnly && !isFrontFacing(i, viewDirection)) continue;
            if (hitLen > 1e-6f && !normalCompatible(i, hnx, hny, hnz)) continue;

            float distance = (float) Math.sqrt(d2);
            float u = clamp(1f - distance / radius, 0f, 1f);
            float falloff = u * u * (3f - 2f * u);
            selectionVerticesScratch[selectedCount] = i;
            selectionWeightsScratch[selectedCount] = falloff;
            selectedCount++;

            for (int nb : neighbors[i]) {
                if (traversalStamp[nb] != generation) {
                    traversalStamp[nb] = generation;
                    traversalQueue[tail++] = nb;
                }
            }
        }

        if (selectedCount == 0) return Selection.empty();
        return new Selection(selectionVerticesScratch, selectionWeightsScratch, selectedCount);
    }

    private int nextTraversalGeneration() {
        if (traversalGeneration == Integer.MAX_VALUE) {
            Arrays.fill(traversalStamp, 0);
            traversalGeneration = 1;
        }
        return traversalGeneration++;
    }

    private boolean isFrontFacing(int vertex, float[] viewDirection) {
        if (viewDirection == null || viewDirection.length < 3) return true;
        int base = vertex * 3;
        float facing = -(normals[base] * viewDirection[0]
                + normals[base + 1] * viewDirection[1]
                + normals[base + 2] * viewDirection[2]);
        return facing > 0.02f;
    }

    private boolean normalCompatible(int vertex, float hnx, float hny, float hnz) {
        int base = vertex * 3;
        float dot = normals[base] * hnx + normals[base + 1] * hny + normals[base + 2] * hnz;
        // A wide cone still follows curved surfaces, but avoids grabbing a
        // nearby sheet whose normals point mostly the other way after folding.
        return dot > -0.20f;
    }

    private boolean applySmooth(Selection selection, float strength) {
        // Sculpt "Smooth" should visibly relax the surface. The old nearly
        // cancelling Taubin +/- pair was useful as a fairing filter but made a
        // finger stroke feel inert. Use two positive Laplacian relaxations at
        // controlled strength; a little local shrink is expected for this tool.
        float factor = clamp(strength * 18f, 0.10f, 0.62f);
        boolean changed = false;
        for (int pass = 0; pass < 2; pass++) {
            System.arraycopy(positions, 0, beforeScratch, 0, positions.length);
            Arrays.fill(deltaScratch, 0f);

            boolean anyDelta = false;
            for (int s = 0; s < selection.count; s++) {
                int vertex = selection.vertices[s];
                int[] adjacent = neighbors[vertex];
                if (adjacent.length == 0) continue;

                float ax = 0f, ay = 0f, az = 0f;
                for (int nb : adjacent) {
                    int n = nb * 3;
                    ax += beforeScratch[n];
                    ay += beforeScratch[n + 1];
                    az += beforeScratch[n + 2];
                }
                float inv = 1f / adjacent.length;
                ax *= inv;
                ay *= inv;
                az *= inv;

                int base = vertex * 3;
                float localFactor = factor * selection.weights[s] * 0.5f;
                float dx = (ax - beforeScratch[base]) * localFactor;
                float dy = (ay - beforeScratch[base + 1]) * localFactor;
                float dz = (az - beforeScratch[base + 2]) * localFactor;

                float moveLen = length(dx, dy, dz);
                float currentEdge = minNeighborEdgeLengthFrom(beforeScratch, vertex);
                float restEdge = minNeighborEdgeLengthFrom(restPositions, vertex);
                float maxMove = Math.max(
                        0.00035f,
                        Math.min(currentEdge * 0.13f, restEdge * 0.16f)
                );
                if (moveLen > maxMove && moveLen > 1e-8f) {
                    float k = maxMove / moveLen;
                    dx *= k;
                    dy *= k;
                    dz *= k;
                }

                deltaScratch[base] = dx;
                deltaScratch[base + 1] = dy;
                deltaScratch[base + 2] = dz;
                anyDelta |= Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1e-9f;
            }

            if (anyDelta && commitTransactional(selection, beforeScratch, deltaScratch)) {
                changed = true;
                recalculateNormals();
            } else {
                break;
            }
        }
        return changed;
    }



    private boolean commitTransactional(Selection selection, float[] before, float[] delta) {
        int touchedCount = collectTouchedTriangles(selection);
        if (touchedCount == 0) return false;

        float scale = 1f;
        for (int attempt = 0; attempt < MAX_LINE_SEARCH_STEPS; attempt++) {
            System.arraycopy(before, 0, candidateScratch, 0, before.length);

            for (int s = 0; s < selection.count; s++) {
                int base = selection.vertices[s] * 3;
                candidateScratch[base] = before[base] + delta[base] * scale;
                candidateScratch[base + 1] = before[base + 1] + delta[base + 1] * scale;
                candidateScratch[base + 2] = before[base + 2] + delta[base + 2] * scale;
            }

            if (validateTouchedTriangles(before, candidateScratch, touchedCount)) {
                System.arraycopy(candidateScratch, 0, positions, 0, positions.length);
                clearTouchedTriangles(touchedCount);
                return true;
            }
            scale *= 0.5f;
        }

        clearTouchedTriangles(touchedCount);
        return false;
    }

    private int collectTouchedTriangles(Selection selection) {
        int count = 0;
        for (int s = 0; s < selection.count; s++) {
            int vertex = selection.vertices[s];
            for (int triangle : incidentTriangles[vertex]) {
                if (!touchedTriangleMask[triangle]) {
                    touchedTriangleMask[triangle] = true;
                    touchedTriangles[count++] = triangle;
                }
            }
        }
        return count;
    }

    private void clearTouchedTriangles(int count) {
        for (int i = 0; i < count; i++) {
            touchedTriangleMask[touchedTriangles[i]] = false;
        }
    }

    private boolean validateTouchedTriangles(float[] before, float[] candidate, int touchedCount) {
        for (int i = 0; i < touchedCount; i++) {
            if (!validateTriangle(before, candidate, touchedTriangles[i], true)) return false;
        }
        return true;
    }

    private boolean validateTriangle(float[] before, float[] candidate, int triangle, boolean checkStep) {
        int a = indices[triangle * 3];
        int b = indices[triangle * 3 + 1];
        int c = indices[triangle * 3 + 2];

        if (!finiteVertex(candidate, a) || !finiteVertex(candidate, b) || !finiteVertex(candidate, c)) {
            return false;
        }

        triangleNormal(before, a, b, c, normalScratchOld);
        triangleNormal(candidate, a, b, c, normalScratchNew);
        float oldArea2 = length(normalScratchOld[0], normalScratchOld[1], normalScratchOld[2]);
        float newArea2 = length(normalScratchNew[0], normalScratchNew[1], normalScratchNew[2]);
        float restArea2 = restTriangleArea2[triangle];

        if (!(newArea2 > 1e-8f)) return false;
        if (newArea2 < restArea2 * MIN_REST_AREA_RATIO) return false;
        if (checkStep && newArea2 < oldArea2 * MIN_STEP_AREA_RATIO) return false;

        if (checkStep && oldArea2 > 1e-8f) {
            float dot = (
                    normalScratchOld[0] * normalScratchNew[0]
                            + normalScratchOld[1] * normalScratchNew[1]
                            + normalScratchOld[2] * normalScratchNew[2]
            ) / (oldArea2 * newArea2);
            if (!Float.isFinite(dot) || dot < MIN_STEP_NORMAL_DOT) return false;
        }

        float ab = edgeLength(candidate, a, b);
        float bc = edgeLength(candidate, b, c);
        float ca = edgeLength(candidate, c, a);
        float restAb = edgeLength(restPositions, a, b);
        float restBc = edgeLength(restPositions, b, c);
        float restCa = edgeLength(restPositions, c, a);

        if (!edgeWithinRestBudget(ab, restAb)
                || !edgeWithinRestBudget(bc, restBc)
                || !edgeWithinRestBudget(ca, restCa)) {
            return false;
        }

        if (checkStep) {
            float oldAb = edgeLength(before, a, b);
            float oldBc = edgeLength(before, b, c);
            float oldCa = edgeLength(before, c, a);
            if (!edgeWithinStepBudget(ab, oldAb)
                    || !edgeWithinStepBudget(bc, oldBc)
                    || !edgeWithinStepBudget(ca, oldCa)) {
                return false;
            }
        }

        float quality = triangleQuality(newArea2, ab, bc, ca);
        return Float.isFinite(quality) && quality >= MIN_TRIANGLE_QUALITY;
    }

    private boolean edgeWithinRestBudget(float edge, float rest) {
        if (!(edge > 1e-8f) || !(rest > 1e-8f)) return false;
        return edge >= rest * MIN_EDGE_COMPRESSION && edge <= rest * MAX_EDGE_STRETCH;
    }

    private boolean edgeWithinStepBudget(float edge, float oldEdge) {
        if (!(oldEdge > 1e-8f)) return false;
        return edge >= oldEdge * MIN_STEP_EDGE_SHRINK && edge <= oldEdge * MAX_STEP_EDGE_GROWTH;
    }

    private static float triangleQuality(float area2, float ab, float bc, float ca) {
        float denominator = ab * ab + bc * bc + ca * ca;
        if (!(denominator > 1e-12f)) return 0f;
        // 4*sqrt(3)*A/sum(l^2); area2 = 2A, so factor is 2*sqrt(3).
        return (2f * 1.7320508f * area2) / denominator;
    }

    private float minNeighborEdgeLengthFrom(float[] source, int vertex) {
        int base = vertex * 3;
        float min = Float.POSITIVE_INFINITY;
        for (int nb : neighbors[vertex]) {
            int n = nb * 3;
            float dx = source[n] - source[base];
            float dy = source[n + 1] - source[base + 1];
            float dz = source[n + 2] - source[base + 2];
            float len = length(dx, dy, dz);
            if (len > 1e-8f && len < min) min = len;
        }
        return Float.isFinite(min) ? min : 0.01f;
    }

    private float triangleArea2(float[] source, int triangle) {
        int a = indices[triangle * 3];
        int b = indices[triangle * 3 + 1];
        int c = indices[triangle * 3 + 2];
        triangleNormal(source, a, b, c, normalScratchOld);
        return length(normalScratchOld[0], normalScratchOld[1], normalScratchOld[2]);
    }

    private static void triangleNormal(
            float[] source,
            int aVertex,
            int bVertex,
            int cVertex,
            float[] out
    ) {
        int a = aVertex * 3;
        int b = bVertex * 3;
        int c = cVertex * 3;

        float abx = source[b] - source[a];
        float aby = source[b + 1] - source[a + 1];
        float abz = source[b + 2] - source[a + 2];
        float acx = source[c] - source[a];
        float acy = source[c + 1] - source[a + 1];
        float acz = source[c + 2] - source[a + 2];

        out[0] = aby * acz - abz * acy;
        out[1] = abz * acx - abx * acz;
        out[2] = abx * acy - aby * acx;
    }

    private static float edgeLength(float[] source, int aVertex, int bVertex) {
        int a = aVertex * 3;
        int b = bVertex * 3;
        return length(
                source[b] - source[a],
                source[b + 1] - source[a + 1],
                source[b + 2] - source[a + 2]
        );
    }

    private static boolean finiteVertex(float[] source, int vertex) {
        int base = vertex * 3;
        return Float.isFinite(source[base])
                && Float.isFinite(source[base + 1])
                && Float.isFinite(source[base + 2]);
    }

    private static float intersectTriangle(
            float[] origin, float[] direction,
            float v0x, float v0y, float v0z,
            float v1x, float v1y, float v1z,
            float v2x, float v2y, float v2z
    ) {
        final float epsilon = 1e-7f;
        float e1x = v1x - v0x;
        float e1y = v1y - v0y;
        float e1z = v1z - v0z;
        float e2x = v2x - v0x;
        float e2y = v2y - v0y;
        float e2z = v2z - v0z;

        float px = direction[1] * e2z - direction[2] * e2y;
        float py = direction[2] * e2x - direction[0] * e2z;
        float pz = direction[0] * e2y - direction[1] * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < epsilon) return -1f;

        float invDet = 1f / det;
        float tx = origin[0] - v0x;
        float ty = origin[1] - v0y;
        float tz = origin[2] - v0z;
        float u = (tx * px + ty * py + tz * pz) * invDet;
        if (u < 0f || u > 1f) return -1f;

        float qx = ty * e1z - tz * e1y;
        float qy = tz * e1x - tx * e1z;
        float qz = tx * e1y - ty * e1x;
        float v = (direction[0] * qx + direction[1] * qy + direction[2] * qz) * invDet;
        if (v < 0f || u + v > 1f) return -1f;

        float t = (e2x * qx + e2y * qy + e2z * qz) * invDet;
        return t > epsilon ? t : -1f;
    }

    boolean isClosedTwoManifold() {
        Map<Long, Integer> counts = new HashMap<>();
        for (int i = 0; i < indices.length; i += 3) {
            addEdgeCount(counts, indices[i], indices[i + 1]);
            addEdgeCount(counts, indices[i + 1], indices[i + 2]);
            addEdgeCount(counts, indices[i + 2], indices[i]);
        }
        for (int count : counts.values()) {
            if (count != 2) return false;
        }
        return !counts.isEmpty();
    }

    private static void addEdgeCount(Map<Long, Integer> counts, int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        long key = (((long) min) << 32) | (max & 0xffffffffL);
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static int gridId(int i, int j, int k, int nx, int ny) {
        return (k * ny + j) * nx + i;
    }

    private static void polygonizeTetra(
            int[] tet,
            float[] gridPositions,
            float[] field,
            Map<Long, Integer> edgeCache,
            List<float[]> vertices,
            List<int[]> faces
    ) {
        boolean[] inside = new boolean[4];
        int insideCount = 0;
        for (int i = 0; i < 4; i++) {
            inside[i] = field[tet[i]] < 0f;
            if (inside[i]) insideCount++;
        }
        if (insideCount == 0 || insideCount == 4) return;

        if (insideCount == 1 || insideCount == 3) {
            boolean seekInside = insideCount == 1;
            int single = -1;
            int[] others = new int[3];
            int out = 0;
            for (int i = 0; i < 4; i++) {
                if (inside[i] == seekInside) single = i;
                else others[out++] = i;
            }

            int a = humanEdgeVertex(tet[single], tet[others[0]], gridPositions, field, edgeCache, vertices);
            int b = humanEdgeVertex(tet[single], tet[others[1]], gridPositions, field, edgeCache, vertices);
            int c = humanEdgeVertex(tet[single], tet[others[2]], gridPositions, field, edgeCache, vertices);
            addHumanOrientedFace(vertices, faces, a, b, c);
            return;
        }

        int[] ins = new int[2];
        int[] outs = new int[2];
        int inCount = 0;
        int outCount = 0;
        for (int i = 0; i < 4; i++) {
            if (inside[i]) ins[inCount++] = i;
            else outs[outCount++] = i;
        }

        int a = humanEdgeVertex(tet[ins[0]], tet[outs[0]], gridPositions, field, edgeCache, vertices);
        int b = humanEdgeVertex(tet[ins[0]], tet[outs[1]], gridPositions, field, edgeCache, vertices);
        int c = humanEdgeVertex(tet[ins[1]], tet[outs[0]], gridPositions, field, edgeCache, vertices);
        int d = humanEdgeVertex(tet[ins[1]], tet[outs[1]], gridPositions, field, edgeCache, vertices);
        addHumanOrientedFace(vertices, faces, a, c, b);
        addHumanOrientedFace(vertices, faces, b, c, d);
    }

    private static int humanEdgeVertex(
            int a,
            int b,
            float[] gridPositions,
            float[] field,
            Map<Long, Integer> cache,
            List<float[]> vertices
    ) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        long key = (((long) min) << 32) | (max & 0xffffffffL);
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        float fa = field[a];
        float fb = field[b];
        float denominator = fa - fb;
        float t = Math.abs(denominator) > 1e-10f ? fa / denominator : 0.5f;

        // Avoid pathological sliver triangles when the iso-surface passes
        // numerically almost through a grid corner. This slightly quantizes
        // the blockout surface but gives the sculpt core much healthier
        // starting triangles on a low-resolution mobile grid.
        t = clamp(t, 0.14f, 0.86f);

        int pa = a * 3;
        int pb = b * 3;
        float x = lerp(gridPositions[pa], gridPositions[pb], t);
        float y = lerp(gridPositions[pa + 1], gridPositions[pb + 1], t);
        float z = lerp(gridPositions[pa + 2], gridPositions[pb + 2], t);

        int index = vertices.size();
        vertices.add(new float[]{x, y, z});
        cache.put(key, index);
        return index;
    }

    private static void addHumanOrientedFace(
            List<float[]> vertices,
            List<int[]> faces,
            int a,
            int b,
            int c
    ) {
        if (a == b || b == c || c == a) return;
        float[] pa = vertices.get(a);
        float[] pb = vertices.get(b);
        float[] pc = vertices.get(c);

        float abx = pb[0] - pa[0];
        float aby = pb[1] - pa[1];
        float abz = pb[2] - pa[2];
        float acx = pc[0] - pa[0];
        float acy = pc[1] - pa[1];
        float acz = pc[2] - pa[2];

        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        if (length(nx, ny, nz) < 1e-8f) return;

        float cx = (pa[0] + pb[0] + pc[0]) / 3f;
        float cy = (pa[1] + pb[1] + pc[1]) / 3f;
        float cz = (pa[2] + pb[2] + pc[2]) / 3f;
        float[] gradient = humanFieldGradient(cx, cy, cz);

        if (nx * gradient[0] + ny * gradient[1] + nz * gradient[2] < 0f) {
            faces.add(new int[]{a, c, b});
        } else {
            faces.add(new int[]{a, b, c});
        }
    }

    private static float[] humanFieldGradient(float x, float y, float z) {
        final float e = 0.004f;
        return new float[]{
                humanField(x + e, y, z) - humanField(x - e, y, z),
                humanField(x, y + e, z) - humanField(x, y - e, z),
                humanField(x, y, z + e) - humanField(x, y, z - e)
        };
    }

    private static float humanField(float x, float y, float z) {
        // Neutral game-character blockout: chest, pelvis, head, neck, arms,
        // legs, hands and feet blended into one closed surface.
        float d = sdEllipsoid(x, y, z, 0f, 0.28f, 0f, 0.47f, 0.72f, 0.28f);
        d = smoothMin(d, sdEllipsoid(x, y, z, 0f, -0.42f, 0f, 0.42f, 0.38f, 0.30f), 0.10f);
        d = smoothMin(d, sdCapsule(x, y, z, 0f, 0.72f, 0f, 0f, 0.93f, 0f, 0.18f), 0.10f);
        d = smoothMin(d, sdEllipsoid(x, y, z, 0f, 1.25f, 0f, 0.33f, 0.42f, 0.32f), 0.10f);

        for (int side = -1; side <= 1; side += 2) {
            float s = side;
            d = smoothMin(d, sdCapsule(
                    x, y, z,
                    s * 0.42f, 0.58f, 0f,
                    s * 0.72f, -0.18f, 0f,
                    0.14f
            ), 0.10f);
            d = smoothMin(d, sdCapsule(
                    x, y, z,
                    s * 0.72f, -0.18f, 0f,
                    s * 0.68f, -0.70f, 0.02f,
                    0.115f
            ), 0.09f);
            d = smoothMin(d, sdEllipsoid(
                    x, y, z,
                    s * 0.68f, -0.82f, 0.03f,
                    0.13f, 0.20f, 0.11f
            ), 0.08f);
            d = smoothMin(d, sdCapsule(
                    x, y, z,
                    s * 0.20f, -0.56f, 0f,
                    s * 0.24f, -1.28f, 0f,
                    0.20f
            ), 0.10f);
            d = smoothMin(d, sdEllipsoid(
                    x, y, z,
                    s * 0.24f, -1.43f, 0.08f,
                    0.20f, 0.19f, 0.32f
            ), 0.08f);
        }
        return d;
    }

    private static float sdEllipsoid(
            float x, float y, float z,
            float cx, float cy, float cz,
            float rx, float ry, float rz
    ) {
        float px = (x - cx) / rx;
        float py = (y - cy) / ry;
        float pz = (z - cz) / rz;
        return (length(px, py, pz) - 1f) * Math.min(rx, Math.min(ry, rz));
    }

    private static float sdCapsule(
            float x, float y, float z,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float radius
    ) {
        float pax = x - ax;
        float pay = y - ay;
        float paz = z - az;
        float bax = bx - ax;
        float bay = by - ay;
        float baz = bz - az;
        float denom = bax * bax + bay * bay + baz * baz;
        float h = denom > 1e-10f
                ? clamp((pax * bax + pay * bay + paz * baz) / denom, 0f, 1f)
                : 0f;
        return length(
                pax - bax * h,
                pay - bay * h,
                paz - baz * h
        ) - radius;
    }

    private static float smoothMin(float a, float b, float k) {
        if (!(k > 0f)) return Math.min(a, b);
        float h = clamp(0.5f + 0.5f * (b - a) / k, 0f, 1f);
        return lerp(b, a, h) - k * h * (1f - h);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int[][] buildNeighbors(int vertexCount, int[] indices) {
        List<Set<Integer>> sets = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) sets.add(new HashSet<>());

        for (int i = 0; i < indices.length; i += 3) {
            int a = indices[i];
            int b = indices[i + 1];
            int c = indices[i + 2];

            sets.get(a).add(b); sets.get(a).add(c);
            sets.get(b).add(a); sets.get(b).add(c);
            sets.get(c).add(a); sets.get(c).add(b);
        }

        int[][] result = new int[vertexCount][];
        for (int i = 0; i < vertexCount; i++) {
            Set<Integer> set = sets.get(i);
            int[] values = new int[set.size()];
            int k = 0;
            for (int value : set) values[k++] = value;
            result[i] = values;
        }
        return result;
    }

    private static int[][] buildIncidentTriangles(int vertexCount, int[] indices) {
        List<List<Integer>> incident = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) incident.add(new ArrayList<>());

        for (int i = 0; i < indices.length; i += 3) {
            int tri = i / 3;
            incident.get(indices[i]).add(tri);
            incident.get(indices[i + 1]).add(tri);
            incident.get(indices[i + 2]).add(tri);
        }

        int[][] result = new int[vertexCount][];
        for (int i = 0; i < vertexCount; i++) {
            List<Integer> list = incident.get(i);
            int[] values = new int[list.size()];
            for (int j = 0; j < list.size(); j++) values[j] = list.get(j);
            result[i] = values;
        }
        return result;
    }

    private static int midpoint(
            List<float[]> vertices,
            Map<Long, Integer> cache,
            int a,
            int b
    ) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        long key = (((long) min) << 32) | (max & 0xffffffffL);

        Integer cached = cache.get(key);
        if (cached != null) return cached;

        float[] va = vertices.get(a);
        float[] vb = vertices.get(b);
        float x = (va[0] + vb[0]) * 0.5f;
        float y = (va[1] + vb[1]) * 0.5f;
        float z = (va[2] + vb[2]) * 0.5f;
        float len = length(x, y, z);
        if (len < 1e-8f) throw new IllegalStateException("Icosphere midpoint collapsed");
        x /= len;
        y /= len;
        z /= len;

        int index = vertices.size();
        vertices.add(new float[]{x, y, z});
        cache.put(key, index);
        return index;
    }

    private static void addUnit(List<float[]> vertices, float x, float y, float z) {
        float len = length(x, y, z);
        if (len < 1e-8f) throw new IllegalArgumentException("zero-length vertex");
        vertices.add(new float[]{x / len, y / len, z / len});
    }

    private static boolean isOutward(float[] positions, int a, int b, int c) {
        int ia = a * 3;
        int ib = b * 3;
        int ic = c * 3;

        float abx = positions[ib] - positions[ia];
        float aby = positions[ib + 1] - positions[ia + 1];
        float abz = positions[ib + 2] - positions[ia + 2];
        float acx = positions[ic] - positions[ia];
        float acy = positions[ic + 1] - positions[ia + 1];
        float acz = positions[ic + 2] - positions[ia + 2];

        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;

        float mx = (positions[ia] + positions[ib] + positions[ic]) / 3f;
        float my = (positions[ia + 1] + positions[ib + 1] + positions[ic + 1]) / 3f;
        float mz = (positions[ia + 2] + positions[ib + 2] + positions[ic + 2]) / 3f;

        return nx * mx + ny * my + nz * mz > 0f;
    }

    private static boolean allFinite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Selection {
        final int[] vertices;
        final float[] weights;
        final int count;

        Selection(int[] vertices, float[] weights, int count) {
            this.vertices = vertices;
            this.weights = weights;
            this.count = count;
        }

        static Selection empty() {
            return new Selection(new int[0], new float[0], 0);
        }
    }
}
