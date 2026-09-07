package com.fallpoint.pocketsculpt;

import java.util.HashSet;
import java.util.Set;

/** Central geometry/topology invariants. Brushes do not invent their own safety rules. */
final class GeometryValidator {
    static final float MIN_AREA2 = 1.0e-9f;
    static final float MIN_TRIANGLE_QUALITY = 0.025f;
    static final float MAX_LOCAL_EDGE_RATIO = 12.0f;

    enum Reason {
        OK,
        NON_FINITE_VERTEX,
        FACE_REFERENCES_DEAD_VERTEX,
        DEGENERATE_FACE_IDS,
        DEGENERATE_TRIANGLE,
        TRIANGLE_QUALITY,
        EDGE_RATIO,
        DUPLICATE_FACE,
        DIRECTED_EDGE_DUPLICATE,
        NON_MANIFOLD_EDGE,
        BROKEN_HALF_EDGE,
        RESOURCE_LIMIT,
        UNKNOWN
    }

    static final class Report {
        final boolean ok;
        final Reason reason;
        final int elementId;
        final String detail;

        Report(boolean ok, Reason reason, int elementId, String detail) {
            this.ok = ok;
            this.reason = reason;
            this.elementId = elementId;
            this.detail = detail == null ? "" : detail;
        }

        static Report ok() { return new Report(true, Reason.OK, -1, ""); }
        static Report fail(Reason reason, int id, String detail) {
            return new Report(false, reason, id, detail);
        }

        @Override public String toString() {
            return ok ? "OK" : reason + "@" + elementId + (detail.isEmpty() ? "" : ": " + detail);
        }
    }

    static Report validate(MeshKernel mesh, boolean requireClosedTwoManifold) {
        if (mesh.liveVertexCount > MeshKernel.MAX_VERTICES
                || mesh.liveFaceCount > MeshKernel.MAX_FACES
                || mesh.liveFaceCount * 3L > MeshKernel.MAX_HALF_EDGES) {
            return Report.fail(Reason.RESOURCE_LIMIT, -1, "CABrush 32-bit resource budget exceeded");
        }

        for (int v = 0; v < mesh.vertexHighWater; v++) {
            if (mesh.vertexAlive[v] == 0) continue;
            int b = v * 3;
            if (!MeshKernel.finite(mesh.positions[b], mesh.positions[b + 1], mesh.positions[b + 2])) {
                return Report.fail(Reason.NON_FINITE_VERTEX, v, "");
            }
        }

        PrimitiveLongIntMap directed = new PrimitiveLongIntMap(Math.max(16, mesh.liveFaceCount * 6));
        PrimitiveLongIntMap undirectedCounts = new PrimitiveLongIntMap(Math.max(16, mesh.liveFaceCount * 6));
        Set<FaceKey> faces = new HashSet<>(Math.max(16, mesh.liveFaceCount * 2));

        for (int f = 0; f < mesh.faceHighWater; f++) {
            if (mesh.faceAlive[f] == 0) continue;
            Report faceReport = validateFaceGeometry(mesh, f);
            if (!faceReport.ok) return faceReport;

            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
            FaceKey key = new FaceKey(a, b, c);
            if (!faces.add(key)) return Report.fail(Reason.DUPLICATE_FACE, f, key.toString());

            Report r;
            r = addDirected(directed, a, b, f); if (!r.ok) return r;
            r = addDirected(directed, b, c, f); if (!r.ok) return r;
            r = addDirected(directed, c, a, f); if (!r.ok) return r;
            undirectedCounts.increment(MeshKernel.undirectedKey(a, b));
            undirectedCounts.increment(MeshKernel.undirectedKey(b, c));
            undirectedCounts.increment(MeshKernel.undirectedKey(c, a));
        }

        if (requireClosedTwoManifold) {
            long[] keys = undirectedCounts.rawKeys();
            int[] values = undirectedCounts.rawValues();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != 0L && values[i] != 2) {
                    return Report.fail(Reason.NON_MANIFOLD_EDGE, -1, "edge incidence=" + values[i]);
                }
            }
        }

        mesh.ensureConnectivity();
        if (mesh.halfEdgeCount != mesh.liveFaceCount * 3) {
            return Report.fail(Reason.BROKEN_HALF_EDGE, -1, "half-edge count");
        }
        for (int h = 0; h < mesh.halfEdgeCount; h++) {
            int face = mesh.heFace[h];
            if (!mesh.isFaceAlive(face)) return Report.fail(Reason.BROKEN_HALF_EDGE, h, "dead face");
            int next = mesh.heNext[h];
            if (next < 0 || next >= mesh.halfEdgeCount) return Report.fail(Reason.BROKEN_HALF_EDGE, h, "next");
            if (requireClosedTwoManifold) {
                int twin = mesh.heTwin[h];
                if (twin < 0 || twin >= mesh.halfEdgeCount) {
                    return Report.fail(Reason.BROKEN_HALF_EDGE, h, "missing twin");
                }
                if (mesh.heTwin[twin] != h
                        || mesh.heOrigin[h] != mesh.heDest[twin]
                        || mesh.heDest[h] != mesh.heOrigin[twin]) {
                    return Report.fail(Reason.BROKEN_HALF_EDGE, h, "twin mismatch");
                }
            }
        }
        return Report.ok();
    }

    static Report validateFaces(MeshKernel mesh, int[] faceIds, int count) {
        for (int i = 0; i < count; i++) {
            int face = faceIds[i];
            if (!mesh.isFaceAlive(face)) continue;
            Report r = validateFaceGeometry(mesh, face);
            if (!r.ok) return r;
        }
        return Report.ok();
    }

    private static Report validateFaceGeometry(MeshKernel mesh, int f) {
        int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
        if (!mesh.isVertexAlive(a) || !mesh.isVertexAlive(b) || !mesh.isVertexAlive(c)) {
            return Report.fail(Reason.FACE_REFERENCES_DEAD_VERTEX, f, "");
        }
        if (a == b || b == c || c == a) return Report.fail(Reason.DEGENERATE_FACE_IDS, f, "");

        float ab = edge(mesh, a, b);
        float bc = edge(mesh, b, c);
        float ca = edge(mesh, c, a);
        float max = Math.max(ab, Math.max(bc, ca));
        float min = Math.min(ab, Math.min(bc, ca));
        if (!(min > 1.0e-8f) || !Float.isFinite(max)) {
            return Report.fail(Reason.DEGENERATE_TRIANGLE, f, "edge");
        }
        if (max / min > MAX_LOCAL_EDGE_RATIO) {
            return Report.fail(Reason.EDGE_RATIO, f, "ratio=" + (max / min));
        }

        float area2 = area2(mesh, a, b, c);
        if (!(area2 > MIN_AREA2) || !Float.isFinite(area2)) {
            return Report.fail(Reason.DEGENERATE_TRIANGLE, f, "area2=" + area2);
        }
        float denom = ab * ab + bc * bc + ca * ca;
        float quality = denom > 1.0e-12f ? (2f * 1.7320508f * area2) / denom : 0f;
        if (!Float.isFinite(quality) || quality < MIN_TRIANGLE_QUALITY) {
            return Report.fail(Reason.TRIANGLE_QUALITY, f, "q=" + quality);
        }
        return Report.ok();
    }

    private static Report addDirected(PrimitiveLongIntMap map, int a, int b, int face) {
        int old = map.put(MeshKernel.directedKey(a, b), face, MeshKernel.INVALID);
        if (old != MeshKernel.INVALID) {
            return Report.fail(Reason.DIRECTED_EDGE_DUPLICATE, face, a + "->" + b + " also in " + old);
        }
        return Report.ok();
    }

    static float edge(MeshKernel mesh, int a, int b) {
        int ia = a * 3, ib = b * 3;
        return MeshKernel.length(
                mesh.positions[ib] - mesh.positions[ia],
                mesh.positions[ib + 1] - mesh.positions[ia + 1],
                mesh.positions[ib + 2] - mesh.positions[ia + 2]
        );
    }

    static float area2(MeshKernel mesh, int a, int b, int c) {
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
        return MeshKernel.length(nx, ny, nz);
    }

    private static final class FaceKey {
        final int a, b, c;
        FaceKey(int x, int y, int z) {
            int lo = Math.min(x, Math.min(y, z));
            int hi = Math.max(x, Math.max(y, z));
            int mid = x + y + z - lo - hi;
            a = lo; b = mid; c = hi;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof FaceKey)) return false;
            FaceKey k = (FaceKey)o;
            return a == k.a && b == k.b && c == k.c;
        }
        @Override public int hashCode() {
            int h = a * 73856093;
            h ^= b * 19349663;
            h ^= c * 83492791;
            return h;
        }
        @Override public String toString() { return a + "," + b + "," + c; }
    }
}
