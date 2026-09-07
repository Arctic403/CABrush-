package com.fallpoint.pocketsculpt;

import java.util.Arrays;

/**
 * Geometry-quality measurements and local intersection checks used by the
 * Core 0.3.1 verification/transaction layer.
 *
 * This intentionally stays dependency-free. It is not a replacement for a
 * production exact-predicate geometry library; it is a conservative guard for
 * CABrush's own triangle mesh mutations.
 */
final class GeometryQuality {
    private static final float EPS = 1.0e-6f;

    static final class Stats {
        final double area;
        final double signedVolume;
        final float minQuality;
        final float minEdge;
        final float maxEdge;
        final float minRadius;
        final float maxRadius;

        Stats(double area, double signedVolume, float minQuality,
              float minEdge, float maxEdge, float minRadius, float maxRadius) {
            this.area = area;
            this.signedVolume = signedVolume;
            this.minQuality = minQuality;
            this.minEdge = minEdge;
            this.maxEdge = maxEdge;
            this.minRadius = minRadius;
            this.maxRadius = maxRadius;
        }
    }

    private GeometryQuality() {}

    static Stats measure(MeshKernel mesh) {
        double area = 0.0;
        double volume6 = 0.0;
        float minQ = Float.POSITIVE_INFINITY;
        float minEdge = Float.POSITIVE_INFINITY;
        float maxEdge = 0f;
        float minRadius = Float.POSITIVE_INFINITY;
        float maxRadius = 0f;

        for (int v = 0; v < mesh.vertexHighWater; v++) {
            if (!mesh.isVertexAlive(v)) continue;
            float r = MeshKernel.length(mesh.x(v), mesh.y(v), mesh.z(v));
            minRadius = Math.min(minRadius, r);
            maxRadius = Math.max(maxRadius, r);
        }

        for (int f = 0; f < mesh.faceHighWater; f++) {
            if (!mesh.isFaceAlive(f)) continue;
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
            float ab = GeometryValidator.edge(mesh, a, b);
            float bc = GeometryValidator.edge(mesh, b, c);
            float ca = GeometryValidator.edge(mesh, c, a);
            float area2 = GeometryValidator.area2(mesh, a, b, c);
            area += 0.5 * area2;
            minQ = Math.min(minQ, triangleQuality(area2, ab, bc, ca));
            minEdge = Math.min(minEdge, Math.min(ab, Math.min(bc, ca)));
            maxEdge = Math.max(maxEdge, Math.max(ab, Math.max(bc, ca)));

            double ax = mesh.x(a), ay = mesh.y(a), az = mesh.z(a);
            double bx = mesh.x(b), by = mesh.y(b), bz = mesh.z(b);
            double cx = mesh.x(c), cy = mesh.y(c), cz = mesh.z(c);
            volume6 += ax * (by * cz - bz * cy)
                    + ay * (bz * cx - bx * cz)
                    + az * (bx * cy - by * cx);
        }

        if (!Float.isFinite(minQ)) minQ = 0f;
        if (!Float.isFinite(minEdge)) minEdge = 0f;
        if (!Float.isFinite(minRadius)) minRadius = 0f;
        return new Stats(area, volume6 / 6.0, minQ, minEdge, maxEdge, minRadius, maxRadius);
    }

    static float triangleQuality(float area2, float ab, float bc, float ca) {
        float denom = ab * ab + bc * bc + ca * ca;
        return denom > 1.0e-12f ? (2f * 1.7320508f * area2) / denom : 0f;
    }

    static float faceNormalDot(MeshSnapshot before, MeshKernel after, int face) {
        if (face < 0 || face >= before.faceHighWater || face >= after.faceHighWater) return 1f;
        if (before.faceAlive[face] == 0 || !after.isFaceAlive(face)) return 1f;
        if (before.faceA[face] != after.faceA[face]
                || before.faceB[face] != after.faceB[face]
                || before.faceC[face] != after.faceC[face]) {
            return 1f;
        }
        int a = after.faceA[face], b = after.faceB[face], c = after.faceC[face];
        float[] oldN = normal(before.positions, a, b, c);
        float[] newN = normal(after.positions, a, b, c);
        float ol = MeshKernel.length(oldN[0], oldN[1], oldN[2]);
        float nl = MeshKernel.length(newN[0], newN[1], newN[2]);
        if (ol < 1e-12f || nl < 1e-12f) return -1f;
        return (oldN[0] * newN[0] + oldN[1] * newN[1] + oldN[2] * newN[2]) / (ol * nl);
    }

    static int[] changedFaces(MeshSnapshot before, MeshKernel after) {
        boolean[] changedVertex = new boolean[Math.max(before.vertexHighWater, after.vertexHighWater)];
        int maxV = changedVertex.length;
        for (int v = 0; v < maxV; v++) {
            boolean oldAlive = v < before.vertexHighWater && before.vertexAlive[v] != 0;
            boolean newAlive = after.isVertexAlive(v);
            if (oldAlive != newAlive) {
                changedVertex[v] = true;
                continue;
            }
            if (!oldAlive) continue;
            int b = v * 3;
            if (Float.floatToRawIntBits(before.positions[b]) != Float.floatToRawIntBits(after.positions[b])
                    || Float.floatToRawIntBits(before.positions[b + 1]) != Float.floatToRawIntBits(after.positions[b + 1])
                    || Float.floatToRawIntBits(before.positions[b + 2]) != Float.floatToRawIntBits(after.positions[b + 2])) {
                changedVertex[v] = true;
            }
        }

        int cap = Math.max(16, after.liveFaceCount);
        int[] faces = new int[cap];
        int count = 0;
        for (int f = 0; f < after.faceHighWater; f++) {
            if (!after.isFaceAlive(f)) continue;
            boolean changed = f >= before.faceHighWater || before.faceAlive[f] == 0;
            if (!changed && (before.faceA[f] != after.faceA[f]
                    || before.faceB[f] != after.faceB[f]
                    || before.faceC[f] != after.faceC[f])) changed = true;
            if (!changed) {
                int a = after.faceA[f], b = after.faceB[f], c = after.faceC[f];
                changed = (a < changedVertex.length && changedVertex[a])
                        || (b < changedVertex.length && changedVertex[b])
                        || (c < changedVertex.length && changedVertex[c]);
            }
            if (changed) {
                if (count == faces.length) faces = Arrays.copyOf(faces, faces.length * 2);
                faces[count++] = f;
            }
        }
        return Arrays.copyOf(faces, count);
    }

    /**
     * Conservative local self-intersection check: only newly changed faces are
     * tested against the live mesh, which keeps topology transactions practical.
     * Adjacent faces sharing a vertex are intentionally ignored.
     */
    static boolean hasNewSelfIntersection(MeshSnapshot before, MeshKernel after) {
        int[] changed = changedFaces(before, after);
        return hasSelfIntersectionForFaces(after, changed, changed.length);
    }

    static boolean hasSelfIntersectionForFaces(MeshKernel mesh, int[] faces, int count) {
        for (int i = 0; i < count; i++) {
            int f = faces[i];
            if (!mesh.isFaceAlive(f)) continue;
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
            float[] boxA = aabb(mesh, a, b, c);
            for (int g = 0; g < mesh.faceHighWater; g++) {
                if (g == f || !mesh.isFaceAlive(g)) continue;
                int d = mesh.faceA[g], e = mesh.faceB[g], h = mesh.faceC[g];
                if (sharesVertex(a, b, c, d, e, h)) continue;
                float[] boxB = aabb(mesh, d, e, h);
                if (!aabbOverlap(boxA, boxB)) continue;
                if (trianglesIntersect(mesh, a, b, c, d, e, h)) return true;
            }
        }
        return false;
    }

    static int countSelfIntersections(MeshKernel mesh, int maxPairs) {
        int found = 0;
        for (int f = 0; f < mesh.faceHighWater; f++) {
            if (!mesh.isFaceAlive(f)) continue;
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
            float[] boxA = aabb(mesh, a, b, c);
            for (int g = f + 1; g < mesh.faceHighWater; g++) {
                if (!mesh.isFaceAlive(g)) continue;
                int d = mesh.faceA[g], e = mesh.faceB[g], h = mesh.faceC[g];
                if (sharesVertex(a, b, c, d, e, h)) continue;
                float[] boxB = aabb(mesh, d, e, h);
                if (!aabbOverlap(boxA, boxB)) continue;
                if (trianglesIntersect(mesh, a, b, c, d, e, h)) {
                    found++;
                    if (found >= maxPairs) return found;
                }
            }
        }
        return found;
    }

    private static boolean trianglesIntersect(MeshKernel m,
            int a, int b, int c, int d, int e, int f) {
        float[] A = point(m, a), B = point(m, b), C = point(m, c);
        float[] D = point(m, d), E = point(m, e), F = point(m, f);

        float[] n1 = cross(sub(B, A), sub(C, A));
        float[] n2 = cross(sub(E, D), sub(F, D));
        float l1 = len(n1), l2 = len(n2);
        if (l1 < EPS || l2 < EPS) return true;

        float sd = dot(n1, sub(D, A));
        float se = dot(n1, sub(E, A));
        float sf = dot(n1, sub(F, A));
        if (sameStrictSign(sd, se, sf)) return false;

        float sa = dot(n2, sub(A, D));
        float sb = dot(n2, sub(B, D));
        float sc = dot(n2, sub(C, D));
        if (sameStrictSign(sa, sb, sc)) return false;

        float parallel = len(cross(n1, n2)) / (l1 * l2);
        if (parallel < 1e-5f && Math.abs(sd) / l1 < EPS
                && Math.abs(se) / l1 < EPS && Math.abs(sf) / l1 < EPS) {
            return coplanarOverlap(A, B, C, D, E, F, n1);
        }

        return segmentTriangle(A, B, D, E, F)
                || segmentTriangle(B, C, D, E, F)
                || segmentTriangle(C, A, D, E, F)
                || segmentTriangle(D, E, A, B, C)
                || segmentTriangle(E, F, A, B, C)
                || segmentTriangle(F, D, A, B, C);
    }

    private static boolean segmentTriangle(float[] p0, float[] p1,
            float[] a, float[] b, float[] c) {
        float[] dir = sub(p1, p0);
        float[] e1 = sub(b, a), e2 = sub(c, a);
        float[] p = cross(dir, e2);
        float det = dot(e1, p);
        if (Math.abs(det) < 1e-8f) return false;
        float inv = 1f / det;
        float[] t = sub(p0, a);
        float u = dot(t, p) * inv;
        if (u <= EPS || u >= 1f - EPS) return false;
        float[] q = cross(t, e1);
        float v = dot(dir, q) * inv;
        if (v <= EPS || u + v >= 1f - EPS) return false;
        float rayT = dot(e2, q) * inv;
        return rayT > EPS && rayT < 1f - EPS;
    }

    private static boolean coplanarOverlap(float[] A, float[] B, float[] C,
            float[] D, float[] E, float[] F, float[] n) {
        int drop = 0;
        float ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        if (ay > ax && ay >= az) drop = 1;
        else if (az > ax && az > ay) drop = 2;
        float[][] t1 = {project(A, drop), project(B, drop), project(C, drop)};
        float[][] t2 = {project(D, drop), project(E, drop), project(F, drop)};
        for (int i = 0; i < 3; i++) {
            float[] p = t1[i], q = t1[(i + 1) % 3];
            for (int j = 0; j < 3; j++) {
                float[] r = t2[j], s = t2[(j + 1) % 3];
                if (segments2d(p, q, r, s)) return true;
            }
        }
        return pointInTri2d(t1[0], t2[0], t2[1], t2[2])
                || pointInTri2d(t2[0], t1[0], t1[1], t1[2]);
    }

    private static boolean segments2d(float[] a, float[] b, float[] c, float[] d) {
        float o1 = orient2d(a, b, c), o2 = orient2d(a, b, d);
        float o3 = orient2d(c, d, a), o4 = orient2d(c, d, b);
        return o1 * o2 < -EPS && o3 * o4 < -EPS;
    }

    private static boolean pointInTri2d(float[] p, float[] a, float[] b, float[] c) {
        float o1 = orient2d(a, b, p), o2 = orient2d(b, c, p), o3 = orient2d(c, a, p);
        boolean neg = o1 < -EPS || o2 < -EPS || o3 < -EPS;
        boolean pos = o1 > EPS || o2 > EPS || o3 > EPS;
        return !(neg && pos);
    }

    private static float orient2d(float[] a, float[] b, float[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static float[] project(float[] p, int drop) {
        if (drop == 0) return new float[]{p[1], p[2]};
        if (drop == 1) return new float[]{p[0], p[2]};
        return new float[]{p[0], p[1]};
    }

    private static boolean sameStrictSign(float a, float b, float c) {
        return (a > EPS && b > EPS && c > EPS) || (a < -EPS && b < -EPS && c < -EPS);
    }

    private static boolean sharesVertex(int a, int b, int c, int d, int e, int f) {
        return a == d || a == e || a == f || b == d || b == e || b == f
                || c == d || c == e || c == f;
    }

    private static float[] aabb(MeshKernel m, int a, int b, int c) {
        float minX = Math.min(m.x(a), Math.min(m.x(b), m.x(c)));
        float minY = Math.min(m.y(a), Math.min(m.y(b), m.y(c)));
        float minZ = Math.min(m.z(a), Math.min(m.z(b), m.z(c)));
        float maxX = Math.max(m.x(a), Math.max(m.x(b), m.x(c)));
        float maxY = Math.max(m.y(a), Math.max(m.y(b), m.y(c)));
        float maxZ = Math.max(m.z(a), Math.max(m.z(b), m.z(c)));
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static boolean aabbOverlap(float[] a, float[] b) {
        return a[0] <= b[3] + EPS && a[3] + EPS >= b[0]
                && a[1] <= b[4] + EPS && a[4] + EPS >= b[1]
                && a[2] <= b[5] + EPS && a[5] + EPS >= b[2];
    }

    private static float[] point(MeshKernel m, int v) {
        return new float[]{m.x(v), m.y(v), m.z(v)};
    }

    private static float[] normal(float[] p, int a, int b, int c) {
        int ia = a * 3, ib = b * 3, ic = c * 3;
        return cross(
                new float[]{p[ib] - p[ia], p[ib + 1] - p[ia + 1], p[ib + 2] - p[ia + 2]},
                new float[]{p[ic] - p[ia], p[ic + 1] - p[ia + 1], p[ic + 2] - p[ia + 2]}
        );
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float len(float[] a) {
        return MeshKernel.length(a[0], a[1], a[2]);
    }
}
