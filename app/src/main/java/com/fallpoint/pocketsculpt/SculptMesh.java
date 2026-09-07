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

    private static final float MAX_EDGE_STRETCH = 2.20f;
    private static final float MIN_EDGE_COMPRESSION = 0.42f;
    private static final float MIN_REST_AREA_RATIO = 0.18f;
    private static final float MIN_STEP_AREA_RATIO = 0.50f;
    private static final float MIN_STEP_NORMAL_DOT = 0.30f;
    private static final float MIN_TRIANGLE_QUALITY = 0.08f;
    private static final int MAX_LINE_SEARCH_STEPS = 8;

    private final float[] resetPositions;
    private final int[][] neighbors;
    private final int[][] incidentTriangles;
    private final float[] restTriangleArea2;

    private final float[] beforeScratch;
    private final float[] candidateScratch;
    private final float[] deltaScratch;
    private final int[] selectionVertices;
    private final float[] selectionWeights;
    private final int[] traversalQueue;
    private final int[] traversalStamp;
    private final boolean[] touchedMask;
    private final int[] touchedTriangles;
    private int traversalGeneration = 1;

    static final class Hit {
        final float x, y, z, nx, ny, nz, t;
        Hit(float x, float y, float z, float nx, float ny, float nz, float t) {
            this.x = x; this.y = y; this.z = z;
            this.nx = nx; this.ny = ny; this.nz = nz; this.t = t;
        }
    }

    static final class Metrics {
        final float minEdge, maxEdge, minArea2, minQuality, maxRadius;
        Metrics(float minEdge, float maxEdge, float minArea2, float minQuality, float maxRadius) {
            this.minEdge = minEdge; this.maxEdge = maxEdge; this.minArea2 = minArea2;
            this.minQuality = minQuality; this.maxRadius = maxRadius;
        }
    }

    private SculptMesh(float[] positions, int[] indices) {
        this.positions = positions;
        this.indices = indices;
        this.resetPositions = positions.clone();
        this.normals = new float[positions.length];

        int vertexCount = positions.length / 3;
        int triangleCount = indices.length / 3;
        neighbors = buildNeighbors(vertexCount, indices);
        incidentTriangles = buildIncidentTriangles(vertexCount, indices);
        restTriangleArea2 = new float[triangleCount];

        beforeScratch = new float[positions.length];
        candidateScratch = new float[positions.length];
        deltaScratch = new float[positions.length];
        selectionVertices = new int[vertexCount];
        selectionWeights = new float[vertexCount];
        traversalQueue = new int[vertexCount];
        traversalStamp = new int[vertexCount];
        touchedMask = new boolean[triangleCount];
        touchedTriangles = new int[triangleCount];

        for (int t = 0; t < triangleCount; t++) {
            restTriangleArea2[t] = triangleArea2(resetPositions, t);
        }
        recalculateNormals();
    }

    static SculptMesh createSphere() {
        return createIcoSphere(4, 1f);
    }

    static SculptMesh createIcoSphere(int subdivisions, float radius) {
        if (subdivisions < 0 || subdivisions > 6) {
            throw new IllegalArgumentException("subdivisions must be in [0, 6]");
        }
        if (!(radius > 0f) || !Float.isFinite(radius)) {
            throw new IllegalArgumentException("radius must be finite and > 0");
        }

        final float t = (1f + (float)Math.sqrt(5.0)) * 0.5f;
        List<float[]> vertices = new ArrayList<>();
        addUnit(vertices, -1, t, 0); addUnit(vertices, 1, t, 0);
        addUnit(vertices, -1, -t, 0); addUnit(vertices, 1, -t, 0);
        addUnit(vertices, 0, -1, t); addUnit(vertices, 0, 1, t);
        addUnit(vertices, 0, -1, -t); addUnit(vertices, 0, 1, -t);
        addUnit(vertices, t, 0, -1); addUnit(vertices, t, 0, 1);
        addUnit(vertices, -t, 0, -1); addUnit(vertices, -t, 0, 1);

        int[][] baseFaces = {
                {0,11,5}, {0,5,1}, {0,1,7}, {0,7,10}, {0,10,11},
                {1,5,9}, {5,11,4}, {11,10,2}, {10,7,6}, {7,1,8},
                {3,9,4}, {3,4,2}, {3,2,6}, {3,6,8}, {3,8,9},
                {4,9,5}, {2,4,11}, {6,2,10}, {8,6,7}, {9,8,1}
        };
        List<int[]> faces = new ArrayList<>(Arrays.asList(baseFaces));

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
                int tmp = b; b = c; c = tmp;
            }
            outIndices[k++] = a; outIndices[k++] = b; outIndices[k++] = c;
        }
        return new SculptMesh(outPositions, outIndices);
    }

    boolean applyClay(
            float cx, float cy, float cz,
            float hitNx, float hitNy, float hitNz,
            float[] viewDirection,
            float radius,
            float strength,
            BrushMode mode
    ) {
        if (mode == null || !(radius > 0f) || !(strength > 0f)) return false;
        if (!allFinite(cx, cy, cz, hitNx, hitNy, hitNz, radius, strength)) return false;

        Selection selection = collectSelection(cx, cy, cz, radius, viewDirection);
        if (selection.count == 0) return false;

        System.arraycopy(positions, 0, beforeScratch, 0, positions.length);
        Arrays.fill(deltaScratch, 0f);

        float nx = 0f, ny = 0f, nz = 0f, weightSum = 0f;
        for (int s = 0; s < selection.count; s++) {
            int v = selectionVertices[s] * 3;
            float w = selectionWeights[s];
            nx += normals[v] * w;
            ny += normals[v + 1] * w;
            nz += normals[v + 2] * w;
            weightSum += w;
        }
        if (weightSum > 1e-6f) {
            nx /= weightSum; ny /= weightSum; nz /= weightSum;
        } else {
            nx = hitNx; ny = hitNy; nz = hitNz;
        }
        float nLen = length(nx, ny, nz);
        if (nLen < 1e-6f) return false;
        nx /= nLen; ny /= nLen; nz /= nLen;

        float hLen = length(hitNx, hitNy, hitNz);
        if (hLen > 1e-6f) {
            float dot = nx * (hitNx / hLen) + ny * (hitNy / hLen) + nz * (hitNz / hLen);
            if (dot < 0f) { nx = -nx; ny = -ny; nz = -nz; }
        }

        float sign = mode == BrushMode.ADD ? 1f : -1f;
        float baseStep = Math.min(strength, radius * 0.10f);
        boolean any = false;

        for (int s = 0; s < selection.count; s++) {
            int vertex = selectionVertices[s];
            int base = vertex * 3;
            float w = selectionWeights[s];

            float shaped = w * w * (3f - 2f * w);
            float amount = sign * baseStep * shaped;

            float restEdge = minNeighborEdgeLength(resetPositions, vertex);
            float currentEdge = minNeighborEdgeLength(beforeScratch, vertex);
            float maxMove = Math.max(0.0003f, Math.min(restEdge * 0.13f, currentEdge * 0.11f));
            amount = clamp(amount, -maxMove, maxMove);

            deltaScratch[base] = nx * amount;
            deltaScratch[base + 1] = ny * amount;
            deltaScratch[base + 2] = nz * amount;
            any |= Math.abs(amount) > 1e-10f;
        }

        if (!any) return false;
        boolean changed = commitTransactional(selection, beforeScratch, deltaScratch);
        if (changed) recalculateNormals();
        return changed;
    }

    void reset() {
        System.arraycopy(resetPositions, 0, positions, 0, positions.length);
        recalculateNormals();
    }

    Hit raycast(float[] origin, float[] direction) {
        if (origin == null || direction == null || origin.length < 3 || direction.length < 3) return null;
        float closest = Float.POSITIVE_INFINITY;
        Hit best = null;

        for (int i = 0; i < indices.length; i += 3) {
            int a = indices[i] * 3, b = indices[i + 1] * 3, c = indices[i + 2] * 3;
            float t = intersectTriangle(
                    origin, direction,
                    positions[a], positions[a + 1], positions[a + 2],
                    positions[b], positions[b + 1], positions[b + 2],
                    positions[c], positions[c + 1], positions[c + 2]
            );
            if (t <= 0f || t >= closest) continue;

            float abx = positions[b] - positions[a];
            float aby = positions[b + 1] - positions[a + 1];
            float abz = positions[b + 2] - positions[a + 2];
            float acx = positions[c] - positions[a];
            float acy = positions[c + 1] - positions[a + 1];
            float acz = positions[c + 2] - positions[a + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float len = length(nx, ny, nz);
            if (len < 1e-8f) continue;
            nx /= len; ny /= len; nz /= len;
            if (nx * direction[0] + ny * direction[1] + nz * direction[2] > 0f) {
                nx = -nx; ny = -ny; nz = -nz;
            }
            closest = t;
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
            int a = indices[i] * 3, b = indices[i + 1] * 3, c = indices[i + 2] * 3;
            float abx = positions[b] - positions[a];
            float aby = positions[b + 1] - positions[a + 1];
            float abz = positions[b + 2] - positions[a + 2];
            float acx = positions[c] - positions[a];
            float acy = positions[c + 1] - positions[a + 1];
            float acz = positions[c + 2] - positions[a + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            normals[a] += nx; normals[a + 1] += ny; normals[a + 2] += nz;
            normals[b] += nx; normals[b + 1] += ny; normals[b + 2] += nz;
            normals[c] += nx; normals[c + 1] += ny; normals[c + 2] += nz;
        }
        for (int i = 0; i < normals.length; i += 3) {
            float len = length(normals[i], normals[i + 1], normals[i + 2]);
            if (len > 1e-8f) {
                normals[i] /= len; normals[i + 1] /= len; normals[i + 2] /= len;
            }
        }
    }

    boolean isHealthy() {
        if (!isClosedTwoManifold()) return false;
        for (float v : positions) if (!Float.isFinite(v)) return false;
        for (int t = 0; t < indices.length / 3; t++) {
            if (!validateTriangle(resetPositions, positions, t, false)) return false;
        }
        return true;
    }

    boolean isClosedTwoManifold() {
        Map<Long, Integer> counts = new HashMap<>();
        for (int i = 0; i < indices.length; i += 3) {
            addEdgeCount(counts, indices[i], indices[i + 1]);
            addEdgeCount(counts, indices[i + 1], indices[i + 2]);
            addEdgeCount(counts, indices[i + 2], indices[i]);
        }
        for (int count : counts.values()) if (count != 2) return false;
        return true;
    }

    Metrics metrics() {
        float minEdge = Float.POSITIVE_INFINITY, maxEdge = 0f;
        float minArea2 = Float.POSITIVE_INFINITY, minQuality = Float.POSITIVE_INFINITY;
        float maxRadius = 0f;
        for (int v = 0; v < positions.length / 3; v++) {
            int b = v * 3;
            maxRadius = Math.max(maxRadius, length(positions[b], positions[b + 1], positions[b + 2]));
        }
        for (int t = 0; t < indices.length / 3; t++) {
            int a = indices[t * 3], b = indices[t * 3 + 1], c = indices[t * 3 + 2];
            float ab = edgeLength(positions, a, b), bc = edgeLength(positions, b, c), ca = edgeLength(positions, c, a);
            minEdge = Math.min(minEdge, Math.min(ab, Math.min(bc, ca)));
            maxEdge = Math.max(maxEdge, Math.max(ab, Math.max(bc, ca)));
            float area2 = triangleArea2(positions, t);
            minArea2 = Math.min(minArea2, area2);
            minQuality = Math.min(minQuality, triangleQuality(area2, ab, bc, ca));
        }
        return new Metrics(minEdge, maxEdge, minArea2, minQuality, maxRadius);
    }

    float[] copyPositions() {
        return positions.clone();
    }

    private Selection collectSelection(float cx, float cy, float cz, float radius, float[] viewDirection) {
        float radiusSq = radius * radius;
        int seed = -1;
        float best = Float.POSITIVE_INFINITY;
        int vertexCount = positions.length / 3;

        for (int v = 0; v < vertexCount; v++) {
            int b = v * 3;
            float dx = positions[b] - cx, dy = positions[b + 1] - cy, dz = positions[b + 2] - cz;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > radiusSq) continue;
            if (!isFrontFacing(v, viewDirection)) continue;
            if (d2 < best) { best = d2; seed = v; }
        }
        if (seed < 0) return new Selection(0);

        int gen = nextTraversalGeneration();
        int head = 0, tail = 0, count = 0;
        traversalQueue[tail++] = seed;
        traversalStamp[seed] = gen;

        while (head < tail) {
            int v = traversalQueue[head++];
            int b = v * 3;
            float dx = positions[b] - cx, dy = positions[b + 1] - cy, dz = positions[b + 2] - cz;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > radiusSq || !isFrontFacing(v, viewDirection)) continue;

            float u = clamp(1f - (float)Math.sqrt(d2) / radius, 0f, 1f);
            float w = u * u * (3f - 2f * u);
            selectionVertices[count] = v;
            selectionWeights[count] = w;
            count++;

            for (int nb : neighbors[v]) {
                if (traversalStamp[nb] != gen) {
                    traversalStamp[nb] = gen;
                    traversalQueue[tail++] = nb;
                }
            }
        }
        return new Selection(count);
    }

    private boolean isFrontFacing(int vertex, float[] viewDirection) {
        if (viewDirection == null || viewDirection.length < 3) return true;
        int b = vertex * 3;
        float facing = -(normals[b] * viewDirection[0] + normals[b + 1] * viewDirection[1] + normals[b + 2] * viewDirection[2]);
        return facing > 0.02f;
    }

    private boolean commitTransactional(Selection selection, float[] before, float[] delta) {
        int touchedCount = 0;
        for (int s = 0; s < selection.count; s++) {
            for (int t : incidentTriangles[selectionVertices[s]]) {
                if (!touchedMask[t]) {
                    touchedMask[t] = true;
                    touchedTriangles[touchedCount++] = t;
                }
            }
        }

        float scale = 1f;
        for (int attempt = 0; attempt < MAX_LINE_SEARCH_STEPS; attempt++) {
            System.arraycopy(before, 0, candidateScratch, 0, before.length);
            for (int s = 0; s < selection.count; s++) {
                int b = selectionVertices[s] * 3;
                candidateScratch[b] = before[b] + delta[b] * scale;
                candidateScratch[b + 1] = before[b + 1] + delta[b + 1] * scale;
                candidateScratch[b + 2] = before[b + 2] + delta[b + 2] * scale;
            }

            boolean valid = true;
            for (int i = 0; i < touchedCount; i++) {
                if (!validateTriangle(before, candidateScratch, touchedTriangles[i], true)) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                System.arraycopy(candidateScratch, 0, positions, 0, positions.length);
                clearTouched(touchedCount);
                return true;
            }
            scale *= 0.5f;
        }

        clearTouched(touchedCount);
        return false;
    }

    private void clearTouched(int count) {
        for (int i = 0; i < count; i++) touchedMask[touchedTriangles[i]] = false;
    }

    private boolean validateTriangle(float[] before, float[] candidate, int triangle, boolean step) {
        int a = indices[triangle * 3], b = indices[triangle * 3 + 1], c = indices[triangle * 3 + 2];
        if (!finiteVertex(candidate, a) || !finiteVertex(candidate, b) || !finiteVertex(candidate, c)) return false;

        float oldArea2 = triangleArea2(before, triangle);
        float newArea2 = triangleArea2(candidate, triangle);
        float restArea2 = restTriangleArea2[triangle];
        if (!(newArea2 > 1e-8f) || newArea2 < restArea2 * MIN_REST_AREA_RATIO) return false;
        if (step && newArea2 < oldArea2 * MIN_STEP_AREA_RATIO) return false;

        if (step) {
            float[] oldN = triangleNormal(before, a, b, c);
            float[] newN = triangleNormal(candidate, a, b, c);
            float oldLen = length(oldN[0], oldN[1], oldN[2]);
            float newLen = length(newN[0], newN[1], newN[2]);
            if (oldLen > 1e-8f && newLen > 1e-8f) {
                float dot = (oldN[0]*newN[0] + oldN[1]*newN[1] + oldN[2]*newN[2]) / (oldLen * newLen);
                if (!Float.isFinite(dot) || dot < MIN_STEP_NORMAL_DOT) return false;
            }
        }

        float ab = edgeLength(candidate, a, b), bc = edgeLength(candidate, b, c), ca = edgeLength(candidate, c, a);
        float rab = edgeLength(resetPositions, a, b), rbc = edgeLength(resetPositions, b, c), rca = edgeLength(resetPositions, c, a);
        if (!withinRestBudget(ab, rab) || !withinRestBudget(bc, rbc) || !withinRestBudget(ca, rca)) return false;

        float quality = triangleQuality(newArea2, ab, bc, ca);
        return Float.isFinite(quality) && quality >= MIN_TRIANGLE_QUALITY;
    }

    private static boolean withinRestBudget(float edge, float rest) {
        return edge >= rest * MIN_EDGE_COMPRESSION && edge <= rest * MAX_EDGE_STRETCH;
    }

    private float minNeighborEdgeLength(float[] source, int vertex) {
        float min = Float.POSITIVE_INFINITY;
        int b = vertex * 3;
        for (int nb : neighbors[vertex]) {
            int n = nb * 3;
            float len = length(source[n] - source[b], source[n + 1] - source[b + 1], source[n + 2] - source[b + 2]);
            if (len > 1e-8f && len < min) min = len;
        }
        return Float.isFinite(min) ? min : 0.01f;
    }

    private int nextTraversalGeneration() {
        if (traversalGeneration == Integer.MAX_VALUE) {
            Arrays.fill(traversalStamp, 0);
            traversalGeneration = 1;
        }
        return traversalGeneration++;
    }

    private static int[][] buildNeighbors(int vertexCount, int[] indices) {
        List<Set<Integer>> sets = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) sets.add(new HashSet<>());
        for (int i = 0; i < indices.length; i += 3) {
            int a = indices[i], b = indices[i + 1], c = indices[i + 2];
            sets.get(a).add(b); sets.get(a).add(c);
            sets.get(b).add(a); sets.get(b).add(c);
            sets.get(c).add(a); sets.get(c).add(b);
        }
        int[][] result = new int[vertexCount][];
        for (int i = 0; i < vertexCount; i++) {
            result[i] = sets.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
        return result;
    }

    private static int[][] buildIncidentTriangles(int vertexCount, int[] indices) {
        List<List<Integer>> lists = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) lists.add(new ArrayList<>());
        for (int i = 0; i < indices.length; i += 3) {
            int t = i / 3;
            lists.get(indices[i]).add(t);
            lists.get(indices[i + 1]).add(t);
            lists.get(indices[i + 2]).add(t);
        }
        int[][] result = new int[vertexCount][];
        for (int i = 0; i < vertexCount; i++) {
            result[i] = lists.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
        return result;
    }

    private static int midpoint(List<float[]> vertices, Map<Long, Integer> cache, int a, int b) {
        int min = Math.min(a, b), max = Math.max(a, b);
        long key = (((long)min) << 32) | (max & 0xffffffffL);
        Integer cached = cache.get(key);
        if (cached != null) return cached;
        float[] va = vertices.get(a), vb = vertices.get(b);
        float x = (va[0] + vb[0]) * 0.5f, y = (va[1] + vb[1]) * 0.5f, z = (va[2] + vb[2]) * 0.5f;
        float len = length(x, y, z);
        int index = vertices.size();
        vertices.add(new float[]{x / len, y / len, z / len});
        cache.put(key, index);
        return index;
    }

    private static void addUnit(List<float[]> vertices, float x, float y, float z) {
        float len = length(x, y, z);
        vertices.add(new float[]{x / len, y / len, z / len});
    }

    private static boolean isOutward(float[] p, int a, int b, int c) {
        float[] n = triangleNormal(p, a, b, c);
        int ia = a * 3, ib = b * 3, ic = c * 3;
        float mx = (p[ia] + p[ib] + p[ic]) / 3f;
        float my = (p[ia + 1] + p[ib + 1] + p[ic + 1]) / 3f;
        float mz = (p[ia + 2] + p[ib + 2] + p[ic + 2]) / 3f;
        return n[0] * mx + n[1] * my + n[2] * mz > 0f;
    }

    private float triangleArea2(float[] source, int triangle) {
        int a = indices[triangle * 3], b = indices[triangle * 3 + 1], c = indices[triangle * 3 + 2];
        float[] n = triangleNormal(source, a, b, c);
        return length(n[0], n[1], n[2]);
    }

    private static float[] triangleNormal(float[] source, int aVertex, int bVertex, int cVertex) {
        int a = aVertex * 3, b = bVertex * 3, c = cVertex * 3;
        float abx = source[b] - source[a], aby = source[b + 1] - source[a + 1], abz = source[b + 2] - source[a + 2];
        float acx = source[c] - source[a], acy = source[c + 1] - source[a + 1], acz = source[c + 2] - source[a + 2];
        return new float[]{
                aby * acz - abz * acy,
                abz * acx - abx * acz,
                abx * acy - aby * acx
        };
    }

    private static float edgeLength(float[] source, int aVertex, int bVertex) {
        int a = aVertex * 3, b = bVertex * 3;
        return length(source[b] - source[a], source[b + 1] - source[a + 1], source[b + 2] - source[a + 2]);
    }

    private static float triangleQuality(float area2, float ab, float bc, float ca) {
        float denom = ab * ab + bc * bc + ca * ca;
        return denom > 1e-12f ? (2f * 1.7320508f * area2) / denom : 0f;
    }

    private static boolean finiteVertex(float[] source, int vertex) {
        int b = vertex * 3;
        return Float.isFinite(source[b]) && Float.isFinite(source[b + 1]) && Float.isFinite(source[b + 2]);
    }

    private static void addEdgeCount(Map<Long, Integer> counts, int a, int b) {
        int min = Math.min(a, b), max = Math.max(a, b);
        long key = (((long)min) << 32) | (max & 0xffffffffL);
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static float intersectTriangle(
            float[] origin, float[] direction,
            float v0x, float v0y, float v0z,
            float v1x, float v1y, float v1z,
            float v2x, float v2y, float v2z
    ) {
        final float eps = 1e-7f;
        float e1x = v1x - v0x, e1y = v1y - v0y, e1z = v1z - v0z;
        float e2x = v2x - v0x, e2y = v2y - v0y, e2z = v2z - v0z;
        float px = direction[1] * e2z - direction[2] * e2y;
        float py = direction[2] * e2x - direction[0] * e2z;
        float pz = direction[0] * e2y - direction[1] * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < eps) return -1f;
        float invDet = 1f / det;
        float tx = origin[0] - v0x, ty = origin[1] - v0y, tz = origin[2] - v0z;
        float u = (tx * px + ty * py + tz * pz) * invDet;
        if (u < 0f || u > 1f) return -1f;
        float qx = ty * e1z - tz * e1y;
        float qy = tz * e1x - tx * e1z;
        float qz = tx * e1y - ty * e1x;
        float v = (direction[0] * qx + direction[1] * qy + direction[2] * qz) * invDet;
        if (v < 0f || u + v > 1f) return -1f;
        float t = (e2x * qx + e2y * qy + e2z * qz) * invDet;
        return t > eps ? t : -1f;
    }

    private static boolean allFinite(float... values) {
        for (float v : values) if (!Float.isFinite(v)) return false;
        return true;
    }

    private static float length(float x, float y, float z) {
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class Selection {
        final int count;
        Selection(int count) { this.count = count; }
    }
}
