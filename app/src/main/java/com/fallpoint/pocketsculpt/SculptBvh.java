package com.fallpoint.pocketsculpt;

import java.util.Arrays;

/**
 * Sculpt-specific BVH for ray picking, coarse brush filtering and dirty-region
 * ownership. It is verified against brute-force triangle tests in VSS.
 */
final class SculptBvh {
    static final int LEAF_FACE_LIMIT = 12;

    static final class RayHit {
        int faceId = MeshKernel.INVALID;
        float t = Float.POSITIVE_INFINITY;
        float x, y, z;
        float nx, ny, nz;

        void clear() {
            faceId = MeshKernel.INVALID;
            t = Float.POSITIVE_INFINITY;
            x = y = z = nx = ny = nz = 0f;
        }

        void set(RayHit other) {
            faceId = other.faceId; t = other.t;
            x = other.x; y = other.y; z = other.z;
            nx = other.nx; ny = other.ny; nz = other.nz;
        }
    }

    private final MeshKernel mesh;
    private int[] faceIds = new int[0];

    private float[] minX = new float[0], minY = new float[0], minZ = new float[0];
    private float[] maxX = new float[0], maxY = new float[0], maxZ = new float[0];
    private int[] left = new int[0], right = new int[0], start = new int[0], count = new int[0];
    private int nodeCount;
    private int leafCount;
    private int root = MeshKernel.INVALID;
    private int[] traversalStack = new int[64];

    private long builtTopologyVersion = Long.MIN_VALUE;
    private long fittedGeometryVersion = Long.MIN_VALUE;

    SculptBvh(MeshKernel mesh) {
        this.mesh = mesh;
        rebuild();
    }

    void ensureCurrent() {
        if (builtTopologyVersion != mesh.topologyVersion) {
            rebuild();
        } else if (fittedGeometryVersion != mesh.geometryVersion) {
            refit();
        }
    }

    void rebuild() {
        faceIds = mesh.activeFaceIds();
        int maxNodes = Math.max(1, faceIds.length * 2);
        ensureNodeCapacity(maxNodes);
        nodeCount = 0;
        leafCount = 0;
        root = faceIds.length == 0 ? MeshKernel.INVALID : buildNode(0, faceIds.length);
        builtTopologyVersion = mesh.topologyVersion;
        fittedGeometryVersion = mesh.geometryVersion;
        ensureTraversalCapacity(nodeCount + 8);
    }

    void refit() {
        if (root != MeshKernel.INVALID) refitNode(root);
        fittedGeometryVersion = mesh.geometryVersion;
    }

    RayHit raycast(float ox, float oy, float oz, float dx, float dy, float dz, RayHit out) {
        ensureCurrent();
        if (out == null) out = new RayHit();
        out.clear();
        if (root == MeshKernel.INVALID) return out;

        int sp = 0;
        traversalStack[sp++] = root;
        while (sp > 0) {
            int node = traversalStack[--sp];
            if (!rayAabb(node, ox, oy, oz, dx, dy, dz, out.t)) continue;
            if (left[node] == MeshKernel.INVALID) {
                int end = start[node] + count[node];
                for (int i = start[node]; i < end; i++) {
                    int face = faceIds[i];
                    float t = intersectFace(mesh, face, ox, oy, oz, dx, dy, dz);
                    if (t > 0f && t < out.t) fillHit(mesh, face, t, ox, oy, oz, dx, dy, dz, out);
                }
            } else {
                ensureTraversalCapacity(sp + 2);
                traversalStack[sp++] = left[node];
                traversalStack[sp++] = right[node];
            }
        }
        return out;
    }

    int querySphere(float cx, float cy, float cz, float radius, int[] outFaces) {
        ensureCurrent();
        if (root == MeshKernel.INVALID || !(radius >= 0f)) return 0;
        float r2 = radius * radius;
        int written = 0;
        int sp = 0;
        traversalStack[sp++] = root;
        while (sp > 0) {
            int node = traversalStack[--sp];
            if (!sphereAabb(node, cx, cy, cz, r2)) continue;
            if (left[node] == MeshKernel.INVALID) {
                int end = start[node] + count[node];
                for (int i = start[node]; i < end; i++) {
                    if (written >= outFaces.length) return -written - 1;
                    outFaces[written++] = faceIds[i];
                }
            } else {
                ensureTraversalCapacity(sp + 2);
                traversalStack[sp++] = left[node];
                traversalStack[sp++] = right[node];
            }
        }
        return written;
    }

    static RayHit bruteRaycast(MeshKernel mesh, float ox, float oy, float oz,
            float dx, float dy, float dz, RayHit out) {
        if (out == null) out = new RayHit();
        out.clear();
        for (int f = 0; f < mesh.faceHighWater; f++) {
            if (!mesh.isFaceAlive(f)) continue;
            float t = intersectFace(mesh, f, ox, oy, oz, dx, dy, dz);
            if (t > 0f && t < out.t) fillHit(mesh, f, t, ox, oy, oz, dx, dy, dz, out);
        }
        return out;
    }

    int nodeCount() { return nodeCount; }
    int leafCount() { return leafCount; }

    long estimatedBytes() {
        return (long)faceIds.length * 4L
                + (long)(minX.length + minY.length + minZ.length + maxX.length + maxY.length + maxZ.length) * 4L
                + (long)(left.length + right.length + start.length + count.length + traversalStack.length) * 4L;
    }

    private int buildNode(int rangeStart, int rangeCount) {
        int node = nodeCount++;
        left[node] = right[node] = MeshKernel.INVALID;
        start[node] = rangeStart;
        count[node] = rangeCount;
        computeBounds(node, rangeStart, rangeCount);

        if (rangeCount <= LEAF_FACE_LIMIT) {
            leafCount++;
            return node;
        }

        float cminX = Float.POSITIVE_INFINITY, cminY = Float.POSITIVE_INFINITY, cminZ = Float.POSITIVE_INFINITY;
        float cmaxX = Float.NEGATIVE_INFINITY, cmaxY = Float.NEGATIVE_INFINITY, cmaxZ = Float.NEGATIVE_INFINITY;
        for (int i = rangeStart, end = rangeStart + rangeCount; i < end; i++) {
            int f = faceIds[i];
            float cx = centroid(mesh, f, 0), cy = centroid(mesh, f, 1), cz = centroid(mesh, f, 2);
            cminX = Math.min(cminX, cx); cmaxX = Math.max(cmaxX, cx);
            cminY = Math.min(cminY, cy); cmaxY = Math.max(cmaxY, cy);
            cminZ = Math.min(cminZ, cz); cmaxZ = Math.max(cmaxZ, cz);
        }
        float ex = cmaxX - cminX, ey = cmaxY - cminY, ez = cmaxZ - cminZ;
        int axis = ex >= ey && ex >= ez ? 0 : (ey >= ez ? 1 : 2);
        quickSort(rangeStart, rangeStart + rangeCount - 1, axis);

        int leftCount = rangeCount / 2;
        int rightCount = rangeCount - leftCount;
        left[node] = buildNode(rangeStart, leftCount);
        right[node] = buildNode(rangeStart + leftCount, rightCount);
        count[node] = 0;
        return node;
    }

    private void refitNode(int node) {
        if (left[node] == MeshKernel.INVALID) {
            computeBounds(node, start[node], count[node]);
            return;
        }
        refitNode(left[node]);
        refitNode(right[node]);
        int l = left[node], r = right[node];
        minX[node] = Math.min(minX[l], minX[r]);
        minY[node] = Math.min(minY[l], minY[r]);
        minZ[node] = Math.min(minZ[l], minZ[r]);
        maxX[node] = Math.max(maxX[l], maxX[r]);
        maxY[node] = Math.max(maxY[l], maxY[r]);
        maxZ[node] = Math.max(maxZ[l], maxZ[r]);
    }

    private void computeBounds(int node, int rangeStart, int rangeCount) {
        float mnx = Float.POSITIVE_INFINITY, mny = Float.POSITIVE_INFINITY, mnz = Float.POSITIVE_INFINITY;
        float mxx = Float.NEGATIVE_INFINITY, mxy = Float.NEGATIVE_INFINITY, mxz = Float.NEGATIVE_INFINITY;
        for (int i = rangeStart, end = rangeStart + rangeCount; i < end; i++) {
            int f = faceIds[i];
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];

            float x = mesh.x(a), y = mesh.y(a), z = mesh.z(a);
            mnx = Math.min(mnx, x); mny = Math.min(mny, y); mnz = Math.min(mnz, z);
            mxx = Math.max(mxx, x); mxy = Math.max(mxy, y); mxz = Math.max(mxz, z);

            x = mesh.x(b); y = mesh.y(b); z = mesh.z(b);
            mnx = Math.min(mnx, x); mny = Math.min(mny, y); mnz = Math.min(mnz, z);
            mxx = Math.max(mxx, x); mxy = Math.max(mxy, y); mxz = Math.max(mxz, z);

            x = mesh.x(c); y = mesh.y(c); z = mesh.z(c);
            mnx = Math.min(mnx, x); mny = Math.min(mny, y); mnz = Math.min(mnz, z);
            mxx = Math.max(mxx, x); mxy = Math.max(mxy, y); mxz = Math.max(mxz, z);
        }
        minX[node] = mnx; minY[node] = mny; minZ[node] = mnz;
        maxX[node] = mxx; maxY[node] = mxy; maxZ[node] = mxz;
    }

    private boolean rayAabb(int node, float ox, float oy, float oz, float dx, float dy, float dz, float best) {
        float tmin = 0f, tmax = best;

        if (Math.abs(dx) < 1e-12f) {
            if (ox < minX[node] || ox > maxX[node]) return false;
        } else {
            float inv = 1f / dx;
            float t1 = (minX[node] - ox) * inv, t2 = (maxX[node] - ox) * inv;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmax < tmin) return false;
        }

        if (Math.abs(dy) < 1e-12f) {
            if (oy < minY[node] || oy > maxY[node]) return false;
        } else {
            float inv = 1f / dy;
            float t1 = (minY[node] - oy) * inv, t2 = (maxY[node] - oy) * inv;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmax < tmin) return false;
        }

        if (Math.abs(dz) < 1e-12f) {
            if (oz < minZ[node] || oz > maxZ[node]) return false;
        } else {
            float inv = 1f / dz;
            float t1 = (minZ[node] - oz) * inv, t2 = (maxZ[node] - oz) * inv;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmax < tmin) return false;
        }
        return true;
    }

    private boolean sphereAabb(int node, float x, float y, float z, float radiusSq) {
        float d2 = 0f;
        if (x < minX[node]) { float d = minX[node] - x; d2 += d * d; }
        else if (x > maxX[node]) { float d = x - maxX[node]; d2 += d * d; }
        if (y < minY[node]) { float d = minY[node] - y; d2 += d * d; }
        else if (y > maxY[node]) { float d = y - maxY[node]; d2 += d * d; }
        if (z < minZ[node]) { float d = minZ[node] - z; d2 += d * d; }
        else if (z > maxZ[node]) { float d = z - maxZ[node]; d2 += d * d; }
        return d2 <= radiusSq;
    }

    private void quickSort(int lo, int hi, int axis) {
        int i = lo, j = hi;
        float pivot = centroid(mesh, faceIds[(lo + hi) >>> 1], axis);
        while (i <= j) {
            while (centroid(mesh, faceIds[i], axis) < pivot) i++;
            while (centroid(mesh, faceIds[j], axis) > pivot) j--;
            if (i <= j) {
                int tmp = faceIds[i]; faceIds[i] = faceIds[j]; faceIds[j] = tmp;
                i++; j--;
            }
        }
        if (lo < j) quickSort(lo, j, axis);
        if (i < hi) quickSort(i, hi, axis);
    }

    private static float centroid(MeshKernel mesh, int face, int axis) {
        int a = mesh.faceA[face], b = mesh.faceB[face], c = mesh.faceC[face];
        if (axis == 0) return (mesh.x(a) + mesh.x(b) + mesh.x(c)) / 3f;
        if (axis == 1) return (mesh.y(a) + mesh.y(b) + mesh.y(c)) / 3f;
        return (mesh.z(a) + mesh.z(b) + mesh.z(c)) / 3f;
    }

    private static float intersectFace(MeshKernel mesh, int face, float ox, float oy, float oz,
            float dx, float dy, float dz) {
        int a = mesh.faceA[face], b = mesh.faceB[face], c = mesh.faceC[face];
        int ia = a * 3, ib = b * 3, ic = c * 3;
        float v0x = mesh.positions[ia], v0y = mesh.positions[ia + 1], v0z = mesh.positions[ia + 2];
        float e1x = mesh.positions[ib] - v0x, e1y = mesh.positions[ib + 1] - v0y, e1z = mesh.positions[ib + 2] - v0z;
        float e2x = mesh.positions[ic] - v0x, e2y = mesh.positions[ic + 1] - v0y, e2z = mesh.positions[ic + 2] - v0z;
        float px = dy * e2z - dz * e2y;
        float py = dz * e2x - dx * e2z;
        float pz = dx * e2y - dy * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-8f) return -1f;
        float inv = 1f / det;
        float tx = ox - v0x, ty = oy - v0y, tz = oz - v0z;
        float u = (tx * px + ty * py + tz * pz) * inv;
        if (u < 0f || u > 1f) return -1f;
        float qx = ty * e1z - tz * e1y;
        float qy = tz * e1x - tx * e1z;
        float qz = tx * e1y - ty * e1x;
        float v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < 0f || u + v > 1f) return -1f;
        float t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return t > 1e-7f ? t : -1f;
    }

    private static void fillHit(MeshKernel mesh, int face, float t,
            float ox, float oy, float oz, float dx, float dy, float dz, RayHit out) {
        int a = mesh.faceA[face], b = mesh.faceB[face], c = mesh.faceC[face];
        int ia = a * 3, ib = b * 3, ic = c * 3;
        float abx = mesh.positions[ib] - mesh.positions[ia];
        float aby = mesh.positions[ib + 1] - mesh.positions[ia + 1];
        float abz = mesh.positions[ib + 2] - mesh.positions[ia + 2];
        float acx = mesh.positions[ic] - mesh.positions[ia];
        float acy = mesh.positions[ic + 1] - mesh.positions[ia + 1];
        float acz = mesh.positions[ic + 2] - mesh.positions[ia + 2];
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        float len = MeshKernel.length(nx, ny, nz);
        if (len > 1e-12f) { nx /= len; ny /= len; nz /= len; }
        if (nx * dx + ny * dy + nz * dz > 0f) { nx = -nx; ny = -ny; nz = -nz; }

        out.faceId = face;
        out.t = t;
        out.x = ox + dx * t; out.y = oy + dy * t; out.z = oz + dz * t;
        out.nx = nx; out.ny = ny; out.nz = nz;
    }

    private void ensureNodeCapacity(int needed) {
        if (minX.length >= needed) return;
        minX = new float[needed]; minY = new float[needed]; minZ = new float[needed];
        maxX = new float[needed]; maxY = new float[needed]; maxZ = new float[needed];
        left = new int[needed]; right = new int[needed]; start = new int[needed]; count = new int[needed];
    }

    private void ensureTraversalCapacity(int needed) {
        if (traversalStack.length >= needed) return;
        traversalStack = Arrays.copyOf(traversalStack, Math.max(needed, traversalStack.length * 2));
    }
}
