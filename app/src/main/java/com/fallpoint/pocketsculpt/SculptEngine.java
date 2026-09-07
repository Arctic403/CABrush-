package com.fallpoint.pocketsculpt;

import java.util.Arrays;

/**
 * Minimal sculpt consumer for Core 0.3. The engine infrastructure is the
 * milestone; Clay +/- are intentionally small clients of StrokeContext,
 * SculptBvh, GeometryTransaction and GeometryValidator.
 */
final class SculptEngine {
    private final MeshKernel mesh;
    private final SculptBvh bvh;
    private final GeometryTransaction transaction;

    private int[] candidateFaces = new int[1024];
    private int[] selectedVertices = new int[512];
    private float[] selectedWeights = new float[512];
    private float[] basePositions = new float[512 * 3];
    private int[] vertexStamp;
    private int stampGeneration = 1;

    private long acceptedDabs;
    private long rejectedDabs;
    private final long[] rejectReasons = new long[GeometryValidator.Reason.values().length];

    SculptEngine(MeshKernel mesh, SculptBvh bvh) {
        this.mesh = mesh;
        this.bvh = bvh;
        this.transaction = new GeometryTransaction(mesh);
        this.vertexStamp = new int[Math.max(16, mesh.vertexHighWater)];
    }

    boolean applyClay(StrokeContext stroke) {
        if (stroke == null || stroke.mode == null
                || !(stroke.radius > 0f) || !(stroke.strength > 0f)) {
            reject(GeometryValidator.Reason.UNKNOWN);
            return false;
        }

        ensureScratch();
        float supportRadius = stroke.radius * 1.30f;
        int faceCount = bvh.querySphere(stroke.hitX, stroke.hitY, stroke.hitZ, supportRadius, candidateFaces);
        if (faceCount < 0) {
            candidateFaces = Arrays.copyOf(candidateFaces, Math.max(candidateFaces.length * 2, mesh.liveFaceCount));
            faceCount = bvh.querySphere(stroke.hitX, stroke.hitY, stroke.hitZ, supportRadius, candidateFaces);
        }
        if (faceCount <= 0) {
            reject(GeometryValidator.Reason.UNKNOWN);
            return false;
        }

        int selectedCount = collectVertices(stroke, faceCount, supportRadius);
        if (selectedCount == 0) {
            reject(GeometryValidator.Reason.UNKNOWN);
            return false;
        }

        mesh.ensureNormals();
        float nx = 0f, ny = 0f, nz = 0f, total = 0f;
        for (int i = 0; i < selectedCount; i++) {
            int v = selectedVertices[i];
            float w = selectedWeights[i];
            nx += mesh.nx(v) * w;
            ny += mesh.ny(v) * w;
            nz += mesh.nz(v) * w;
            total += w;
        }
        if (total > 1e-8f) {
            nx /= total; ny /= total; nz /= total;
        } else {
            nx = stroke.normalX; ny = stroke.normalY; nz = stroke.normalZ;
        }
        float nlen = MeshKernel.length(nx, ny, nz);
        if (nlen < 1e-8f) {
            reject(GeometryValidator.Reason.UNKNOWN);
            return false;
        }
        nx /= nlen; ny /= nlen; nz /= nlen;
        float hitDot = nx * stroke.normalX + ny * stroke.normalY + nz * stroke.normalZ;
        if (hitDot < 0f) { nx = -nx; ny = -ny; nz = -nz; }

        float sign = stroke.mode == BrushMode.ADD ? 1f : -1f;
        float step = Math.min(stroke.strength, stroke.radius * 0.085f);
        if (!(step > 0f)) {
            reject(GeometryValidator.Reason.UNKNOWN);
            return false;
        }

        ensureSelectedCapacity(selectedCount);
        for (int i = 0; i < selectedCount; i++) {
            int v = selectedVertices[i];
            int src = v * 3, dst = i * 3;
            basePositions[dst] = mesh.positions[src];
            basePositions[dst + 1] = mesh.positions[src + 1];
            basePositions[dst + 2] = mesh.positions[src + 2];
        }

        GeometryValidator.Report lastFailure = null;
        float lineScale = 1f;
        for (int attempt = 0; attempt < 8; attempt++, lineScale *= 0.5f) {
            transaction.beginVertexEdit(selectedVertices, selectedCount);
            for (int i = 0; i < selectedCount; i++) {
                int v = selectedVertices[i];
                int dst = v * 3, src = i * 3;
                float amount = sign * step * selectedWeights[i] * lineScale;
                mesh.positions[dst] = basePositions[src] + nx * amount;
                mesh.positions[dst + 1] = basePositions[src + 1] + ny * amount;
                mesh.positions[dst + 2] = basePositions[src + 2] + nz * amount;
            }
            GeometryTransaction.Result result = transaction.commitVertexEdit();
            if (result.committed) {
                acceptedDabs++;
                bvh.refit();
                return true;
            }
            lastFailure = new GeometryValidator.Report(false, result.reason, -1, result.detail);
            // rollbackVertexEdit() has already restored the prior state.
        }

        reject(lastFailure == null ? GeometryValidator.Reason.UNKNOWN : lastFailure.reason);
        return false;
    }

    long acceptedDabs() { return acceptedDabs; }
    long rejectedDabs() { return rejectedDabs; }
    long rejectionCount(GeometryValidator.Reason reason) { return rejectReasons[reason.ordinal()]; }

    private int collectVertices(StrokeContext stroke, int faceCount, float supportRadius) {
        int gen = nextStamp();
        int count = 0;
        float supportSq = supportRadius * supportRadius;
        for (int i = 0; i < faceCount; i++) {
            int f = candidateFaces[i];
            if (!mesh.isFaceAlive(f)) continue;
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
            count = maybeAddVertex(stroke, supportRadius, supportSq, gen, a, count);
            count = maybeAddVertex(stroke, supportRadius, supportSq, gen, b, count);
            count = maybeAddVertex(stroke, supportRadius, supportSq, gen, c, count);
        }
        return count;
    }

    private int maybeAddVertex(StrokeContext stroke, float supportRadius, float supportSq,
            int gen, int vertex, int count) {
        if (vertexStamp[vertex] == gen) return count;
        vertexStamp[vertex] = gen;

        int b = vertex * 3;
        float dx = mesh.positions[b] - stroke.hitX;
        float dy = mesh.positions[b + 1] - stroke.hitY;
        float dz = mesh.positions[b + 2] - stroke.hitZ;
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > supportSq) return count;

        mesh.ensureNormals();
        float facing = -(mesh.normals[b] * stroke.viewX
                + mesh.normals[b + 1] * stroke.viewY
                + mesh.normals[b + 2] * stroke.viewZ);
        if (facing <= 0.015f) return count;

        float d = (float)Math.sqrt(d2);
        float u = Math.max(0f, Math.min(1f, 1f - d / supportRadius));
        float smooth = u * u * (3f - 2f * u);
        // Keep a soft support ring outside the nominal radius so nearby
        // topology travels with the buildup instead of the center stretching
        // away from a static boundary.
        float nominal = stroke.radius / supportRadius;
        float supportScale = d <= stroke.radius ? 1f : 0.42f;
        float weight = smooth * supportScale;
        if (weight <= 1e-5f) return count;

        ensureSelectedCapacity(count + 1);
        selectedVertices[count] = vertex;
        selectedWeights[count] = weight;
        return count + 1;
    }

    private void reject(GeometryValidator.Reason reason) {
        rejectedDabs++;
        rejectReasons[reason.ordinal()]++;
    }

    private void ensureScratch() {
        if (candidateFaces.length < mesh.liveFaceCount) {
            candidateFaces = Arrays.copyOf(candidateFaces, mesh.liveFaceCount);
        }
        if (vertexStamp.length < mesh.vertexHighWater) {
            vertexStamp = Arrays.copyOf(vertexStamp, mesh.vertexHighWater);
        }
    }

    private void ensureSelectedCapacity(int needed) {
        if (selectedVertices.length >= needed) return;
        int cap = selectedVertices.length;
        while (cap < needed) cap = cap + cap / 2 + 32;
        selectedVertices = Arrays.copyOf(selectedVertices, cap);
        selectedWeights = Arrays.copyOf(selectedWeights, cap);
        basePositions = Arrays.copyOf(basePositions, cap * 3);
    }

    private int nextStamp() {
        if (stampGeneration == Integer.MAX_VALUE) {
            Arrays.fill(vertexStamp, 0);
            stampGeneration = 1;
        }
        return stampGeneration++;
    }
}
