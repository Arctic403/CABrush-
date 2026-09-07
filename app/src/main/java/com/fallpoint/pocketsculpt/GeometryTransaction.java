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
        mesh.geometryVersion++;
        mesh.recomputeNormalsAll();
        vertexCount = 0;
        touchedFaceCount = 0;
        return Result.ok();
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
            GeometryValidator.Report report = GeometryValidator.validate(mesh, requireClosed);
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
