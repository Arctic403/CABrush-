package com.fallpoint.pocketsculpt;

import java.util.Arrays;

/**
 * Packed mutable triangle-mesh kernel for CABrush Core 0.3.
 *
 * Design goals:
 * - primitive arrays only in hot mesh state
 * - stable vertex/face IDs
 * - explicit free lists
 * - half-edge connectivity rebuilt deterministically after topology edits
 * - geometry/topology generation counters
 * - no Android dependencies so the exact kernel can be fuzzed in CI
 */
final class MeshKernel {
    static final int INVALID = -1;

    // 32-bit Android hard guards. These are deliberately below theoretical
    // address-space limits so temporary transactions/BVH/render staging have room.
    static final int MAX_VERTICES = 500_000;
    static final int MAX_FACES = 1_000_000;
    static final int MAX_HALF_EDGES = 3_000_000;

    float[] positions;   // xyz by stable vertex id
    float[] normals;     // xyz by stable vertex id
    byte[] vertexAlive;
    int vertexHighWater;
    int liveVertexCount;
    int[] freeVertices;
    int freeVertexCount;

    int[] faceA;
    int[] faceB;
    int[] faceC;
    byte[] faceAlive;
    int faceHighWater;
    int liveFaceCount;
    int[] freeFaces;
    int freeFaceCount;

    // Rebuilt connectivity. Half-edge IDs are runtime/deterministic, not stable.
    int[] heOrigin = new int[0];
    int[] heDest = new int[0];
    int[] heFace = new int[0];
    int[] heNext = new int[0];
    int[] heTwin = new int[0];
    int halfEdgeCount;
    int[] vertexHalfEdge;
    int[] faceHalfEdge;

    long geometryVersion = 1L;
    long topologyVersion = 1L;
    boolean topologyDirty = true;
    boolean normalsDirty = true;

    private MeshKernel(int vertexCapacity, int faceCapacity) {
        int vc = Math.max(16, vertexCapacity);
        int fc = Math.max(16, faceCapacity);
        positions = new float[vc * 3];
        normals = new float[vc * 3];
        vertexAlive = new byte[vc];
        freeVertices = new int[vc];
        vertexHalfEdge = new int[vc];
        Arrays.fill(vertexHalfEdge, INVALID);

        faceA = new int[fc];
        faceB = new int[fc];
        faceC = new int[fc];
        faceAlive = new byte[fc];
        freeFaces = new int[fc];
        faceHalfEdge = new int[fc];
        Arrays.fill(faceHalfEdge, INVALID);
    }

    static MeshKernel createIcoSphere(int subdivisions, float radius) {
        if (subdivisions < 0 || subdivisions > 7) {
            throw new IllegalArgumentException("subdivisions must be in [0, 7]");
        }
        if (!(radius > 0f) || !Float.isFinite(radius)) {
            throw new IllegalArgumentException("radius must be finite and > 0");
        }

        // Counts are exact for an icosphere: V = 10*4^n+2, F = 20*4^n.
        int pow4 = 1;
        for (int i = 0; i < subdivisions; i++) pow4 *= 4;
        int expectedV = 10 * pow4 + 2;
        int expectedF = 20 * pow4;
        if (expectedV > MAX_VERTICES || expectedF > MAX_FACES) {
            throw new IllegalArgumentException("sphere exceeds CABrush 32-bit resource budget");
        }

        MeshKernel mesh = new MeshKernel(expectedV, expectedF);
        final float t = (1f + (float)Math.sqrt(5.0)) * 0.5f;

        int v0 = mesh.addUnitVertex(-1, t, 0, radius);
        int v1 = mesh.addUnitVertex(1, t, 0, radius);
        int v2 = mesh.addUnitVertex(-1, -t, 0, radius);
        int v3 = mesh.addUnitVertex(1, -t, 0, radius);
        int v4 = mesh.addUnitVertex(0, -1, t, radius);
        int v5 = mesh.addUnitVertex(0, 1, t, radius);
        int v6 = mesh.addUnitVertex(0, -1, -t, radius);
        int v7 = mesh.addUnitVertex(0, 1, -t, radius);
        int v8 = mesh.addUnitVertex(t, 0, -1, radius);
        int v9 = mesh.addUnitVertex(t, 0, 1, radius);
        int v10 = mesh.addUnitVertex(-t, 0, -1, radius);
        int v11 = mesh.addUnitVertex(-t, 0, 1, radius);

        int[][] base = {
                {v0,v11,v5}, {v0,v5,v1}, {v0,v1,v7}, {v0,v7,v10}, {v0,v10,v11},
                {v1,v5,v9}, {v5,v11,v4}, {v11,v10,v2}, {v10,v7,v6}, {v7,v1,v8},
                {v3,v9,v4}, {v3,v4,v2}, {v3,v2,v6}, {v3,v6,v8}, {v3,v8,v9},
                {v4,v9,v5}, {v2,v4,v11}, {v6,v2,v10}, {v8,v6,v7}, {v9,v8,v1}
        };
        for (int[] f : base) mesh.addFace(f[0], f[1], f[2]);

        for (int level = 0; level < subdivisions; level++) {
            int[] activeFaces = mesh.activeFaceIds();
            PrimitiveLongIntMap midpoint = new PrimitiveLongIntMap(activeFaces.length * 2);
            for (int faceId : activeFaces) {
                int a = mesh.faceA[faceId];
                int b = mesh.faceB[faceId];
                int c = mesh.faceC[faceId];

                int ab = midpointVertex(mesh, midpoint, a, b, radius);
                int bc = midpointVertex(mesh, midpoint, b, c, radius);
                int ca = midpointVertex(mesh, midpoint, c, a, radius);

                mesh.setFace(faceId, a, ab, ca);
                mesh.addFace(b, bc, ab);
                mesh.addFace(c, ca, bc);
                mesh.addFace(ab, bc, ca);
            }
        }

        mesh.ensureConnectivity();
        mesh.recomputeNormalsAll();
        GeometryValidator.Report report = GeometryValidator.validate(mesh, true);
        if (!report.ok) throw new IllegalStateException("icosphere invalid: " + report.reason);
        return mesh;
    }

    int addVertex(float x, float y, float z) {
        if (!finite(x, y, z)) throw new IllegalArgumentException("non-finite vertex");
        int id;
        if (freeVertexCount > 0) {
            id = freeVertices[--freeVertexCount];
        } else {
            if (vertexHighWater >= MAX_VERTICES) throw new IllegalStateException("vertex budget exceeded");
            ensureVertexCapacity(vertexHighWater + 1);
            id = vertexHighWater++;
        }
        int b = id * 3;
        positions[b] = x;
        positions[b + 1] = y;
        positions[b + 2] = z;
        normals[b] = normals[b + 1] = normals[b + 2] = 0f;
        vertexAlive[id] = 1;
        liveVertexCount++;
        topologyDirty = true;
        normalsDirty = true;
        topologyVersion++;
        geometryVersion++;
        return id;
    }

    void deleteVertex(int id) {
        if (!isVertexAlive(id)) return;
        if (vertexHasLiveFace(id)) {
            throw new IllegalStateException("cannot delete referenced vertex " + id);
        }
        vertexAlive[id] = 0;
        ensureFreeVertexCapacity(freeVertexCount + 1);
        freeVertices[freeVertexCount++] = id;
        liveVertexCount--;
        topologyDirty = true;
        normalsDirty = true;
        topologyVersion++;
        geometryVersion++;
    }

    int addFace(int a, int b, int c) {
        validateVertexRef(a);
        validateVertexRef(b);
        validateVertexRef(c);
        if (a == b || b == c || c == a) throw new IllegalArgumentException("degenerate face ids");
        int id;
        if (freeFaceCount > 0) {
            id = freeFaces[--freeFaceCount];
        } else {
            if (faceHighWater >= MAX_FACES) throw new IllegalStateException("face budget exceeded");
            ensureFaceCapacity(faceHighWater + 1);
            id = faceHighWater++;
        }
        faceA[id] = a; faceB[id] = b; faceC[id] = c;
        faceAlive[id] = 1;
        liveFaceCount++;
        topologyDirty = true;
        normalsDirty = true;
        topologyVersion++;
        geometryVersion++;
        return id;
    }

    void setFace(int id, int a, int b, int c) {
        if (!isFaceAlive(id)) throw new IllegalArgumentException("dead face " + id);
        validateVertexRef(a); validateVertexRef(b); validateVertexRef(c);
        if (a == b || b == c || c == a) throw new IllegalArgumentException("degenerate face ids");
        faceA[id] = a; faceB[id] = b; faceC[id] = c;
        topologyDirty = true;
        normalsDirty = true;
        topologyVersion++;
        geometryVersion++;
    }

    void deleteFace(int id) {
        if (!isFaceAlive(id)) return;
        faceAlive[id] = 0;
        ensureFreeFaceCapacity(freeFaceCount + 1);
        freeFaces[freeFaceCount++] = id;
        liveFaceCount--;
        topologyDirty = true;
        normalsDirty = true;
        topologyVersion++;
        geometryVersion++;
    }

    boolean isVertexAlive(int id) {
        return id >= 0 && id < vertexHighWater && vertexAlive[id] != 0;
    }

    boolean isFaceAlive(int id) {
        return id >= 0 && id < faceHighWater && faceAlive[id] != 0;
    }

    void setVertexPosition(int id, float x, float y, float z) {
        validateVertexRef(id);
        if (!finite(x, y, z)) throw new IllegalArgumentException("non-finite position");
        int b = id * 3;
        positions[b] = x; positions[b + 1] = y; positions[b + 2] = z;
        normalsDirty = true;
        geometryVersion++;
    }

    float x(int id) { return positions[id * 3]; }
    float y(int id) { return positions[id * 3 + 1]; }
    float z(int id) { return positions[id * 3 + 2]; }
    float nx(int id) { ensureNormals(); return normals[id * 3]; }
    float ny(int id) { ensureNormals(); return normals[id * 3 + 1]; }
    float nz(int id) { ensureNormals(); return normals[id * 3 + 2]; }

    int[] activeVertexIds() {
        int[] ids = new int[liveVertexCount];
        int k = 0;
        for (int i = 0; i < vertexHighWater; i++) if (vertexAlive[i] != 0) ids[k++] = i;
        return ids;
    }

    int[] activeFaceIds() {
        int[] ids = new int[liveFaceCount];
        int k = 0;
        for (int i = 0; i < faceHighWater; i++) if (faceAlive[i] != 0) ids[k++] = i;
        return ids;
    }

    void ensureConnectivity() {
        if (!topologyDirty) return;
        rebuildConnectivity();
    }

    void ensureNormals() {
        if (normalsDirty) recomputeNormalsAll();
    }

    void rebuildConnectivity() {
        if (liveFaceCount > MAX_HALF_EDGES / 3) throw new IllegalStateException("half-edge budget exceeded");
        halfEdgeCount = liveFaceCount * 3;
        heOrigin = ensureIntCapacity(heOrigin, halfEdgeCount);
        heDest = ensureIntCapacity(heDest, halfEdgeCount);
        heFace = ensureIntCapacity(heFace, halfEdgeCount);
        heNext = ensureIntCapacity(heNext, halfEdgeCount);
        heTwin = ensureIntCapacity(heTwin, halfEdgeCount);
        Arrays.fill(heTwin, 0, halfEdgeCount, INVALID);

        ensureVertexAuxCapacity(vertexHighWater);
        ensureFaceAuxCapacity(faceHighWater);
        Arrays.fill(vertexHalfEdge, 0, vertexHighWater, INVALID);
        Arrays.fill(faceHalfEdge, 0, faceHighWater, INVALID);

        PrimitiveLongIntMap directed = new PrimitiveLongIntMap(Math.max(16, halfEdgeCount * 2));
        int h = 0;
        for (int f = 0; f < faceHighWater; f++) {
            if (faceAlive[f] == 0) continue;
            int a = faceA[f], b = faceB[f], c = faceC[f];
            int h0 = h, h1 = h + 1, h2 = h + 2;
            setHalfEdge(h0, a, b, f, h1);
            setHalfEdge(h1, b, c, f, h2);
            setHalfEdge(h2, c, a, f, h0);
            faceHalfEdge[f] = h0;
            if (vertexHalfEdge[a] == INVALID) vertexHalfEdge[a] = h0;
            if (vertexHalfEdge[b] == INVALID) vertexHalfEdge[b] = h1;
            if (vertexHalfEdge[c] == INVALID) vertexHalfEdge[c] = h2;
            directed.put(directedKey(a, b), h0, INVALID);
            directed.put(directedKey(b, c), h1, INVALID);
            directed.put(directedKey(c, a), h2, INVALID);
            h += 3;
        }
        for (int i = 0; i < halfEdgeCount; i++) {
            heTwin[i] = directed.get(directedKey(heDest[i], heOrigin[i]), INVALID);
        }
        topologyDirty = false;
    }

    void recomputeNormalsAll() {
        Arrays.fill(normals, 0, vertexHighWater * 3, 0f);
        for (int f = 0; f < faceHighWater; f++) {
            if (faceAlive[f] == 0) continue;
            int a = faceA[f], b = faceB[f], c = faceC[f];
            int ia = a * 3, ib = b * 3, ic = c * 3;
            float abx = positions[ib] - positions[ia];
            float aby = positions[ib + 1] - positions[ia + 1];
            float abz = positions[ib + 2] - positions[ia + 2];
            float acx = positions[ic] - positions[ia];
            float acy = positions[ic + 1] - positions[ia + 1];
            float acz = positions[ic + 2] - positions[ia + 2];
            float fnx = aby * acz - abz * acy;
            float fny = abz * acx - abx * acz;
            float fnz = abx * acy - aby * acx;
            normals[ia] += fnx; normals[ia + 1] += fny; normals[ia + 2] += fnz;
            normals[ib] += fnx; normals[ib + 1] += fny; normals[ib + 2] += fnz;
            normals[ic] += fnx; normals[ic + 1] += fny; normals[ic + 2] += fnz;
        }
        for (int v = 0; v < vertexHighWater; v++) {
            if (vertexAlive[v] == 0) continue;
            int b = v * 3;
            float len = length(normals[b], normals[b + 1], normals[b + 2]);
            if (len > 1e-12f) {
                normals[b] /= len; normals[b + 1] /= len; normals[b + 2] /= len;
            }
        }
        normalsDirty = false;
    }

    int findHalfEdge(int origin, int dest) {
        ensureConnectivity();
        if (!isVertexAlive(origin) || !isVertexAlive(dest)) return INVALID;
        int start = vertexHalfEdge[origin];
        if (start == INVALID) return INVALID;
        // Connectivity arrays are compact and degrees are small. Scan is
        // deterministic and avoids a permanent hash table in runtime state.
        for (int h = 0; h < halfEdgeCount; h++) {
            if (heOrigin[h] == origin && heDest[h] == dest) return h;
        }
        return INVALID;
    }

    int oppositeVertex(int face, int a, int b) {
        if (!isFaceAlive(face)) return INVALID;
        int x = faceA[face], y = faceB[face], z = faceC[face];
        if (x != a && x != b) return x;
        if (y != a && y != b) return y;
        if (z != a && z != b) return z;
        return INVALID;
    }

    int orientedEdgeIndex(int face, int a, int b) {
        if (!isFaceAlive(face)) return INVALID;
        if (faceA[face] == a && faceB[face] == b) return 0;
        if (faceB[face] == a && faceC[face] == b) return 1;
        if (faceC[face] == a && faceA[face] == b) return 2;
        return INVALID;
    }

    boolean vertexHasLiveFace(int vertex) {
        for (int f = 0; f < faceHighWater; f++) {
            if (faceAlive[f] == 0) continue;
            if (faceA[f] == vertex || faceB[f] == vertex || faceC[f] == vertex) return true;
        }
        return false;
    }

    long estimatedCoreBytes() {
        long bytes = 0;
        bytes += (long)positions.length * 4L + (long)normals.length * 4L;
        bytes += vertexAlive.length + (long)freeVertices.length * 4L + (long)vertexHalfEdge.length * 4L;
        bytes += (long)(faceA.length + faceB.length + faceC.length + freeFaces.length + faceHalfEdge.length) * 4L;
        bytes += faceAlive.length;
        bytes += (long)(heOrigin.length + heDest.length + heFace.length + heNext.length + heTwin.length) * 4L;
        return bytes;
    }

    private int addUnitVertex(float x, float y, float z, float radius) {
        float len = length(x, y, z);
        return addVertex(x / len * radius, y / len * radius, z / len * radius);
    }

    private static int midpointVertex(MeshKernel mesh, PrimitiveLongIntMap cache, int a, int b, float radius) {
        long key = undirectedKey(a, b);
        int existing = cache.get(key, INVALID);
        if (existing != INVALID) return existing;
        float x = (mesh.x(a) + mesh.x(b)) * 0.5f;
        float y = (mesh.y(a) + mesh.y(b)) * 0.5f;
        float z = (mesh.z(a) + mesh.z(b)) * 0.5f;
        float len = length(x, y, z);
        int id = mesh.addVertex(x / len * radius, y / len * radius, z / len * radius);
        cache.put(key, id, INVALID);
        return id;
    }

    private void setHalfEdge(int h, int origin, int dest, int face, int next) {
        heOrigin[h] = origin; heDest[h] = dest; heFace[h] = face; heNext[h] = next;
    }

    private void validateVertexRef(int id) {
        if (!isVertexAlive(id)) throw new IllegalArgumentException("invalid vertex " + id);
    }

    private void ensureVertexCapacity(int needed) {
        if (needed <= vertexAlive.length) return;
        int old = vertexAlive.length;
        int cap = growCapacity(old, needed, MAX_VERTICES);
        positions = Arrays.copyOf(positions, cap * 3);
        normals = Arrays.copyOf(normals, cap * 3);
        vertexAlive = Arrays.copyOf(vertexAlive, cap);
        freeVertices = Arrays.copyOf(freeVertices, cap);
        vertexHalfEdge = Arrays.copyOf(vertexHalfEdge, cap);
        Arrays.fill(vertexHalfEdge, old, cap, INVALID);
    }

    private void ensureFaceCapacity(int needed) {
        if (needed <= faceAlive.length) return;
        int old = faceAlive.length;
        int cap = growCapacity(old, needed, MAX_FACES);
        faceA = Arrays.copyOf(faceA, cap);
        faceB = Arrays.copyOf(faceB, cap);
        faceC = Arrays.copyOf(faceC, cap);
        faceAlive = Arrays.copyOf(faceAlive, cap);
        freeFaces = Arrays.copyOf(freeFaces, cap);
        faceHalfEdge = Arrays.copyOf(faceHalfEdge, cap);
        Arrays.fill(faceHalfEdge, old, cap, INVALID);
    }

    private void ensureFreeVertexCapacity(int needed) {
        if (needed > freeVertices.length) freeVertices = Arrays.copyOf(freeVertices, Math.min(MAX_VERTICES, freeVertices.length * 2));
    }

    private void ensureFreeFaceCapacity(int needed) {
        if (needed > freeFaces.length) freeFaces = Arrays.copyOf(freeFaces, Math.min(MAX_FACES, freeFaces.length * 2));
    }

    private void ensureVertexAuxCapacity(int needed) {
        if (vertexHalfEdge.length >= needed) return;
        int old = vertexHalfEdge.length;
        vertexHalfEdge = Arrays.copyOf(vertexHalfEdge, needed);
        Arrays.fill(vertexHalfEdge, old, needed, INVALID);
    }

    private void ensureFaceAuxCapacity(int needed) {
        if (faceHalfEdge.length >= needed) return;
        int old = faceHalfEdge.length;
        faceHalfEdge = Arrays.copyOf(faceHalfEdge, needed);
        Arrays.fill(faceHalfEdge, old, needed, INVALID);
    }

    private static int[] ensureIntCapacity(int[] src, int needed) {
        if (src.length >= needed) return src;
        return new int[Math.max(needed, Math.max(16, src.length * 2))];
    }

    private static int growCapacity(int old, int needed, int max) {
        int cap = Math.max(16, old);
        while (cap < needed) {
            int next = cap < 1024 ? cap * 2 : cap + cap / 2;
            if (next <= cap || next > max) { cap = max; break; }
            cap = next;
        }
        if (cap < needed) throw new IllegalStateException("resource budget exceeded");
        return cap;
    }

    static long directedKey(int a, int b) {
        return (((long)a) << 32) ^ (b & 0xffffffffL);
    }

    static long undirectedKey(int a, int b) {
        int min = Math.min(a, b), max = Math.max(a, b);
        return (((long)min) << 32) ^ (max & 0xffffffffL);
    }

    static float length(float x, float y, float z) {
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    static boolean finite(float... values) {
        for (float v : values) if (!Float.isFinite(v)) return false;
        return true;
    }
}
