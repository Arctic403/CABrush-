package com.fallpoint.pocketsculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SculptMesh {
    final float[] positions;
    final float[] normals;
    final int[] indices;

    // Fixed-topology guard rails. These deliberately trade extreme deformation
    // for a mesh that remains usable until dynamic remeshing is implemented.
    private static final float MAX_EDGE_STRETCH = 2.35f;
    private static final float MIN_EDGE_COMPRESSION = 0.38f;
    private static final float MIN_REST_AREA_RATIO = 0.16f;
    private static final float MIN_STEP_AREA_RATIO = 0.52f;
    private static final float MIN_STEP_NORMAL_DOT = 0.32f;
    private static final float MIN_TRIANGLE_QUALITY = 0.10f;
    private static final float MAX_STEP_EDGE_GROWTH = 1.28f;
    private static final float MIN_STEP_EDGE_SHRINK = 0.76f;
    private static final int MAX_LINE_SEARCH_STEPS = 7;

    private final float[] originalPositions;
    private final int[][] neighbors;
    private final int[][] incidentTriangles;
    private final float[] restTriangleArea2;

    // Reused transaction buffers keep the safety pass from creating several
    // full-mesh garbage objects for every brush dab on low-memory phones.
    private final float[] beforeScratch;
    private final float[] candidateScratch;
    private final float[] deltaScratch;
    private final boolean[] touchedTriangleMask;
    private final int[] touchedTriangles;
    private final int[] traversalStamp;
    private final int[] traversalQueue;
    private final int[] selectionVerticesScratch;
    private final float[] selectionWeightsScratch;
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

    private SculptMesh(float[] positions, int[] indices) {
        this.positions = positions;
        this.originalPositions = positions.clone();
        this.indices = indices;
        this.normals = new float[positions.length];
        this.neighbors = buildNeighbors(positions.length / 3, indices);
        this.incidentTriangles = buildIncidentTriangles(positions.length / 3, indices);
        this.restTriangleArea2 = new float[indices.length / 3];

        this.beforeScratch = new float[positions.length];
        this.candidateScratch = new float[positions.length];
        this.deltaScratch = new float[positions.length];
        this.touchedTriangleMask = new boolean[indices.length / 3];
        this.touchedTriangles = new int[indices.length / 3];
        int vertexCount = positions.length / 3;
        this.traversalStamp = new int[vertexCount];
        this.traversalQueue = new int[vertexCount];
        this.selectionVerticesScratch = new int[vertexCount];
        this.selectionWeightsScratch = new float[vertexCount];

        for (int triangle = 0; triangle < restTriangleArea2.length; triangle++) {
            restTriangleArea2[triangle] = triangleArea2(originalPositions, triangle);
        }
        recalculateNormals();
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

    boolean applyBrush(
            float cx, float cy, float cz,
            float hitNx, float hitNy, float hitNz,
            float[] viewDirection,
            float radius,
            float strength,
            BrushMode mode,
            boolean frontFaceOnly
    ) {
        if (!(radius > 0f) || !(strength > 0f)) return false;
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
            return applyTaubinSmooth(selection, strength);
        }

        System.arraycopy(positions, 0, beforeScratch, 0, positions.length);
        Arrays.fill(deltaScratch, 0f);

        // Blender's Draw brush direction is based on the average normal in the
        // active area. Use the same broad behavior, but keep it oriented with
        // the ray-hit face so a damaged/stale vertex normal cannot reverse it.
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
        if (nLen < 1e-6f) return false;
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

        float sign = mode == BrushMode.ADD ? 1f : -1f;
        boolean anyDelta = false;

        for (int s = 0; s < selection.count; s++) {
            int vertex = selection.vertices[s];
            int base = vertex * 3;

            float amount = strength * selection.weights[s] * sign;
            float currentEdge = minNeighborEdgeLengthFrom(beforeScratch, vertex);
            float restEdge = minNeighborEdgeLengthFrom(originalPositions, vertex);
            float maxMove = Math.max(
                    0.00035f,
                    Math.min(currentEdge * 0.11f, restEdge * 0.14f)
            );
            amount = clamp(amount, -maxMove, maxMove);

            deltaScratch[base] = nx * amount;
            deltaScratch[base + 1] = ny * amount;
            deltaScratch[base + 2] = nz * amount;
            anyDelta |= Math.abs(amount) > 1e-9f;
        }

        return anyDelta && commitTransactional(selection, beforeScratch, deltaScratch);
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
        System.arraycopy(originalPositions, 0, positions, 0, positions.length);
    }

    boolean isHealthy() {
        for (float value : positions) {
            if (!Float.isFinite(value)) return false;
        }
        for (int triangle = 0; triangle < indices.length / 3; triangle++) {
            if (!validateTriangle(originalPositions, positions, triangle, false)) return false;
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

    private boolean applyTaubinSmooth(Selection selection, float strength) {
        float lambda = clamp(strength * 8f, 0.035f, 0.30f);
        float mu = -lambda * 1.035f;

        boolean first = smoothPass(selection, lambda);
        boolean second = smoothPass(selection, mu);
        return first || second;
    }

    private boolean smoothPass(Selection selection, float factor) {
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
            float scale = factor * selection.weights[s];
            float dx = (ax - beforeScratch[base]) * scale;
            float dy = (ay - beforeScratch[base + 1]) * scale;
            float dz = (az - beforeScratch[base + 2]) * scale;

            float moveLen = length(dx, dy, dz);
            float currentEdge = minNeighborEdgeLengthFrom(beforeScratch, vertex);
            float restEdge = minNeighborEdgeLengthFrom(originalPositions, vertex);
            float maxMove = Math.max(
                    0.00035f,
                    Math.min(currentEdge * 0.09f, restEdge * 0.12f)
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

        return anyDelta && commitTransactional(selection, beforeScratch, deltaScratch);
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
        float restAb = edgeLength(originalPositions, a, b);
        float restBc = edgeLength(originalPositions, b, c);
        float restCa = edgeLength(originalPositions, c, a);

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
