package com.fallpoint.pocketsculpt;

import java.util.ArrayDeque;
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

    private final float[] originalPositions;
    private final int[][] neighbors;
    private final int[][] incidentTriangles;

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
        recalculateNormals();
    }

    static SculptMesh createIcoSphere(int subdivisions, float radius) {
        if (subdivisions < 0 || subdivisions > 6) {
            throw new IllegalArgumentException("subdivisions must be in [0, 6]");
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

    void applyBrush(
            float cx, float cy, float cz,
            float hitNx, float hitNy, float hitNz,
            float[] viewDirection,
            float radius,
            float strength,
            BrushMode mode,
            boolean frontFaceOnly
    ) {
        if (radius <= 0f || strength <= 0f) return;

        Selection selection = collectSelection(
                cx, cy, cz,
                radius,
                viewDirection,
                frontFaceOnly
        );
        if (selection.count == 0) return;

        if (mode == BrushMode.SMOOTH) {
            applyTaubinSmooth(selection, strength);
            return;
        }

        float nx = 0f, ny = 0f, nz = 0f, total = 0f;
        for (int i = 0; i < selection.vertices.length; i++) {
            int vertex = selection.vertices[i];
            if (vertex < 0) break;
            float w = selection.weights[i];
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
        if (nLen < 1e-6f) return;
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

        for (int i = 0; i < selection.vertices.length; i++) {
            int vertex = selection.vertices[i];
            if (vertex < 0) break;

            float amount = strength * selection.weights[i] * sign;
            float maxMove = Math.max(0.0005f, minNeighborEdgeLength(vertex) * 0.18f);
            amount = clamp(amount, -maxMove, maxMove);

            moveVertexSafely(
                    vertex,
                    nx * amount,
                    ny * amount,
                    nz * amount
            );
        }
    }

    Hit raycast(float[] origin, float[] direction) {
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
            if (len > 1e-8f) {
                normals[i] = x / len;
                normals[i + 1] = y / len;
                normals[i + 2] = z / len;
            }
        }
    }

    float[] copyPositions() {
        return positions.clone();
    }

    void setPositions(float[] snapshot) {
        if (snapshot.length != positions.length) return;
        System.arraycopy(snapshot, 0, positions, 0, positions.length);
    }

    void reset() {
        System.arraycopy(originalPositions, 0, positions, 0, positions.length);
    }

    private Selection collectSelection(
            float cx, float cy, float cz,
            float radius,
            float[] viewDirection,
            boolean frontFaceOnly
    ) {
        int count = positions.length / 3;
        float radiusSq = radius * radius;
        int seed = -1;
        float bestDistanceSq = Float.POSITIVE_INFINITY;

        for (int i = 0; i < count; i++) {
            int base = i * 3;
            float dx = positions[base] - cx;
            float dy = positions[base + 1] - cy;
            float dz = positions[base + 2] - cz;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > radiusSq) continue;
            if (frontFaceOnly && !isFrontFacing(i, viewDirection)) continue;

            if (d2 < bestDistanceSq) {
                bestDistanceSq = d2;
                seed = i;
            }
        }

        if (seed < 0) return Selection.empty();

        boolean[] visited = new boolean[count];
        boolean[] selected = new boolean[count];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(seed);
        visited[seed] = true;

        int selectedCount = 0;
        while (!queue.isEmpty()) {
            int i = queue.removeFirst();
            int base = i * 3;
            float dx = positions[base] - cx;
            float dy = positions[base + 1] - cy;
            float dz = positions[base + 2] - cz;
            float d2 = dx * dx + dy * dy + dz * dz;

            if (d2 > radiusSq) continue;
            if (frontFaceOnly && !isFrontFacing(i, viewDirection)) continue;

            selected[i] = true;
            selectedCount++;

            for (int nb : neighbors[i]) {
                if (!visited[nb]) {
                    visited[nb] = true;
                    queue.addLast(nb);
                }
            }
        }

        if (selectedCount == 0) return Selection.empty();

        int[] vertices = new int[selectedCount];
        float[] weights = new float[selectedCount];
        int out = 0;
        for (int i = 0; i < count; i++) {
            if (!selected[i]) continue;
            int base = i * 3;
            float dx = positions[base] - cx;
            float dy = positions[base + 1] - cy;
            float dz = positions[base + 2] - cz;
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float u = clamp(1f - distance / radius, 0f, 1f);
            float falloff = u * u * (3f - 2f * u);
            vertices[out] = i;
            weights[out] = falloff;
            out++;
        }

        return new Selection(vertices, weights, selectedCount);
    }

    private boolean isFrontFacing(int vertex, float[] viewDirection) {
        if (viewDirection == null || viewDirection.length < 3) return true;
        int base = vertex * 3;
        float facing = -(normals[base] * viewDirection[0]
                + normals[base + 1] * viewDirection[1]
                + normals[base + 2] * viewDirection[2]);
        return facing > -0.08f;
    }

    private void applyTaubinSmooth(Selection selection, float strength) {
        float lambda = clamp(strength * 8f, 0.04f, 0.35f);
        float mu = -lambda * 1.02f;
        smoothPass(selection, lambda);
        smoothPass(selection, mu);
    }

    private void smoothPass(Selection selection, float factor) {
        float[] snapshot = positions.clone();
        float[] dx = new float[selection.count];
        float[] dy = new float[selection.count];
        float[] dz = new float[selection.count];

        for (int s = 0; s < selection.count; s++) {
            int vertex = selection.vertices[s];
            int[] adjacent = neighbors[vertex];
            if (adjacent.length == 0) continue;

            float ax = 0f, ay = 0f, az = 0f;
            for (int nb : adjacent) {
                int n = nb * 3;
                ax += snapshot[n];
                ay += snapshot[n + 1];
                az += snapshot[n + 2];
            }

            float inv = 1f / adjacent.length;
            ax *= inv;
            ay *= inv;
            az *= inv;

            int base = vertex * 3;
            float scale = factor * selection.weights[s];
            dx[s] = (ax - snapshot[base]) * scale;
            dy[s] = (ay - snapshot[base + 1]) * scale;
            dz[s] = (az - snapshot[base + 2]) * scale;

            float moveLen = length(dx[s], dy[s], dz[s]);
            float maxMove = Math.max(0.0005f, minNeighborEdgeLengthFrom(snapshot, vertex) * 0.14f);
            if (moveLen > maxMove && moveLen > 1e-8f) {
                float k = maxMove / moveLen;
                dx[s] *= k;
                dy[s] *= k;
                dz[s] *= k;
            }
        }

        for (int s = 0; s < selection.count; s++) {
            moveVertexSafely(selection.vertices[s], dx[s], dy[s], dz[s]);
        }
    }

    private boolean moveVertexSafely(int vertex, float dx, float dy, float dz) {
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < 1e-10f) return true;

        int base = vertex * 3;
        float oldX = positions[base];
        float oldY = positions[base + 1];
        float oldZ = positions[base + 2];

        float newX = oldX + dx;
        float newY = oldY + dy;
        float newZ = oldZ + dz;

        for (int triangle : incidentTriangles[vertex]) {
            int iaVertex = indices[triangle * 3];
            int ibVertex = indices[triangle * 3 + 1];
            int icVertex = indices[triangle * 3 + 2];

            float[] oldNormal = faceNormal(iaVertex, ibVertex, icVertex, -1, 0f, 0f, 0f);
            float oldLen = length(oldNormal[0], oldNormal[1], oldNormal[2]);
            if (oldLen < 1e-8f) return false;

            float[] newNormal = faceNormal(
                    iaVertex, ibVertex, icVertex,
                    vertex, newX, newY, newZ
            );
            float newLen = length(newNormal[0], newNormal[1], newNormal[2]);

            if (newLen < oldLen * 0.12f || newLen < 1e-8f) return false;

            float orientation = (
                    oldNormal[0] * newNormal[0]
                            + oldNormal[1] * newNormal[1]
                            + oldNormal[2] * newNormal[2]
            ) / (oldLen * newLen);

            if (orientation < 0.18f) return false;
        }

        positions[base] = newX;
        positions[base + 1] = newY;
        positions[base + 2] = newZ;
        return true;
    }

    private float[] faceNormal(
            int aVertex, int bVertex, int cVertex,
            int overrideVertex,
            float overrideX, float overrideY, float overrideZ
    ) {
        float ax, ay, az, bx, by, bz, cx, cy, cz;

        if (aVertex == overrideVertex) {
            ax = overrideX; ay = overrideY; az = overrideZ;
        } else {
            int a = aVertex * 3;
            ax = positions[a]; ay = positions[a + 1]; az = positions[a + 2];
        }

        if (bVertex == overrideVertex) {
            bx = overrideX; by = overrideY; bz = overrideZ;
        } else {
            int b = bVertex * 3;
            bx = positions[b]; by = positions[b + 1]; bz = positions[b + 2];
        }

        if (cVertex == overrideVertex) {
            cx = overrideX; cy = overrideY; cz = overrideZ;
        } else {
            int c = cVertex * 3;
            cx = positions[c]; cy = positions[c + 1]; cz = positions[c + 2];
        }

        float abx = bx - ax;
        float aby = by - ay;
        float abz = bz - az;
        float acx = cx - ax;
        float acy = cy - ay;
        float acz = cz - az;

        return new float[]{
                aby * acz - abz * acy,
                abz * acx - abx * acz,
                abx * acy - aby * acx
        };
    }

    private float minNeighborEdgeLength(int vertex) {
        return minNeighborEdgeLengthFrom(positions, vertex);
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
