package com.fallpoint.pocketsculpt;

import java.util.Arrays;

/**
 * Atomic geometry edits. Vertex-only sculpt edits use a compact rollback journal;
 * topology edits use a deterministic MeshSnapshot.
 */
final class GeometryTransaction {
    interface TopologyMutation {
        boolean apply(MeshKernel mesh);
    }

    static final class Result {
        final boolean committed;
        final GeometryValidator.Reason reason;
        final String detail;

        Result(boolean committed, GeometryValidator.Reason reason, String detail) {
            this.committed = committed;
            this.reason = reason;
            this.detail = detail == null ? "" : detail;
        }

        static Result ok() {
            return new Result(true, GeometryValidator.Reason.OK, "");
        }

        static Result fail(GeometryValidator.Report report) {
            return new Result(false, report.reason, report.toString());
        }
    }

    private final MeshKernel mesh;
    private int[] vertices = new int[64];
    private float[] oldPositions = new float[64 * 3];
    private int vertexCount;

    private int[] touchedFaces = new int[128];
    private int touchedFaceCount;
    private int[] faceStamp = new int[128];
    private int stampGeneration = 1;

    GeometryTransaction(MeshKernel mesh) {
        this.mesh = mesh;
    }

    void beginVertexEdit(int[] ids, int count) {
        if (count < 0 || count > ids.length) throw new IllegalArgumentException("count");
        ensureVertexJournal(count);
        vertexCount = count;
        for (int i = 0; i < count; i++) {
            int v = ids[i];
            if (!mesh.isVertexAlive(v)) throw new IllegalArgumentException("dead vertex " + v);
            vertices[i] = v;
            int src = v * 3, dst = i * 3;
            oldPositions[dst] = mesh.positions[src];
            oldPositions[dst + 1] = mesh.positions[src + 1];
            oldPositions[dst + 2] = mesh.positions[src + 2];
        }
        collectTouchedFaces();
    }

    Result commitVertexEdit() {
        mesh.normalsDirty = true;
        GeometryValidator.Report local = GeometryValidator.validateFaces(mesh, touchedFaces, touchedFaceCount);
        if (!local.ok) {
            rollbackVertexEdit();
            return Result.fail(local);
        }

        // A face can remain topologically legal while turning almost inside-out.
        // Compare each touched face against the exact pre-edit vertex journal.
        GeometryValidator.Report motion = validateTouchedFaceMotion();
        if (!motion.ok) {
            rollbackVertexEdit();
            return Result.fail(motion);
        }

        mesh.geometryVersion++;
        mesh.recomputeNormalsAll();
        vertexCount = 0;
        touchedFaceCount = 0;
        return Result.ok();
    }

    private GeometryValidator.Report validateTouchedFaceMotion() {
        for (int i = 0; i < touchedFaceCount; i++) {
            int face = touchedFaces[i];
            if (!mesh.isFaceAlive(face)) continue;
            int a = mesh.faceA[face], b = mesh.faceB[face], c = mesh.faceC[face];

            float[] oldA = oldOrCurrent(a);
            float[] oldB = oldOrCurrent(b);
            float[] oldC = oldOrCurrent(c);
            float[] newA = {mesh.x(a), mesh.y(a), mesh.z(a)};
            float[] newB = {mesh.x(b), mesh.y(b), mesh.z(b)};
            float[] newC = {mesh.x(c), mesh.y(c), mesh.z(c)};

            float[] oldN = cross(sub(oldB, oldA), sub(oldC, oldA));
            float[] newN = cross(sub(newB, newA), sub(newC, newA));
            float oldLen = MeshKernel.length(oldN[0], oldN[1], oldN[2]);
            float newLen = MeshKernel.length(newN[0], newN[1], newN[2]);
            if (oldLen < 1e-10f || newLen < 1e-10f) {
                return GeometryValidator.Report.fail(GeometryValidator.Reason.DEGENERATE_TRIANGLE, face, "motion area");
            }
            float dot = (oldN[0]*newN[0] + oldN[1]*newN[1] + oldN[2]*newN[2]) / (oldLen*newLen);
            if (!Float.isFinite(dot) || dot < 0.20f) {
                return GeometryValidator.Report.fail(GeometryValidator.Reason.NORMAL_CHANGE, face, "dot=" + dot);
            }
            float ratio = newLen / oldLen;
            if (ratio < 0.45f || ratio > 2.20f) {
                return GeometryValidator.Report.fail(GeometryValidator.Reason.LOCAL_AREA_DRIFT, face, "faceAreaRatio=" + ratio);
            }
        }
        return GeometryValidator.Report.ok();
    }

    private float[] oldOrCurrent(int vertex) {
        for (int i = 0; i < vertexCount; i++) {
            if (vertices[i] != vertex) continue;
            int b = i * 3;
            return new float[]{oldPositions[b], oldPositions[b + 1], oldPositions[b + 2]};
        }
        return new float[]{mesh.x(vertex), mesh.y(vertex), mesh.z(vertex)};
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1]*b[2]-a[2]*b[1],
                a[2]*b[0]-a[0]*b[2],
                a[0]*b[1]-a[1]*b[0]
        };
    }

    void rollbackVertexEdit() {
        for (int i = 0; i < vertexCount; i++) {
            int v = vertices[i];
            int src = i * 3, dst = v * 3;
            mesh.positions[dst] = oldPositions[src];
            mesh.positions[dst + 1] = oldPositions[src + 1];
            mesh.positions[dst + 2] = oldPositions[src + 2];
        }
        mesh.normalsDirty = true;
        mesh.recomputeNormalsAll();
        vertexCount = 0;
        touchedFaceCount = 0;
    }

    static Result runTopology(MeshKernel mesh, TopologyMutation mutation, boolean requireClosed) {
        MeshSnapshot before = MeshSnapshot.capture(mesh);
        try {
            if (!mutation.apply(mesh)) {
                before.restoreInto(mesh);
                return new Result(false, GeometryValidator.Reason.UNKNOWN, "mutation declined");
            }
            mesh.ensureConnectivity();
            mesh.recomputeNormalsAll();
            GeometryValidator.Report report =
                    GeometryValidator.validateTopologyTransition(before, mesh, requireClosed);
            if (!report.ok) {
                before.restoreInto(mesh);
                return Result.fail(report);
            }
            return Result.ok();
        } catch (Throwable t) {
            before.restoreInto(mesh);
            return new Result(false, GeometryValidator.Reason.UNKNOWN,
                    t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private void collectTouchedFaces() {
        mesh.ensureConnectivity();
        ensureFaceStamp(mesh.faceHighWater);
        int gen = nextStamp();
        touchedFaceCount = 0;

        for (int i = 0; i < vertexCount; i++) {
            int v = vertices[i];
            for (int h = 0; h < mesh.halfEdgeCount; h++) {
                if (mesh.heOrigin[h] != v && mesh.heDest[h] != v) continue;
                int face = mesh.heFace[h];
                if (faceStamp[face] == gen) continue;
                faceStamp[face] = gen;
                ensureTouchedFaces(touchedFaceCount + 1);
                touchedFaces[touchedFaceCount++] = face;
            }
        }
    }

    private int nextStamp() {
        if (stampGeneration == Integer.MAX_VALUE) {
            Arrays.fill(faceStamp, 0);
            stampGeneration = 1;
        }
        return stampGeneration++;
    }

    private void ensureVertexJournal(int needed) {
        if (vertices.length >= needed) return;
        int cap = vertices.length;
        while (cap < needed) cap = cap + cap / 2 + 16;
        vertices = Arrays.copyOf(vertices, cap);
        oldPositions = Arrays.copyOf(oldPositions, cap * 3);
    }

    private void ensureTouchedFaces(int needed) {
        if (touchedFaces.length >= needed) return;
        int cap = touchedFaces.length;
        while (cap < needed) cap = cap + cap / 2 + 16;
        touchedFaces = Arrays.copyOf(touchedFaces, cap);
    }

    private void ensureFaceStamp(int needed) {
        if (faceStamp.length >= needed) return;
        faceStamp = Arrays.copyOf(faceStamp, Math.max(needed, faceStamp.length * 2));
    }
}
