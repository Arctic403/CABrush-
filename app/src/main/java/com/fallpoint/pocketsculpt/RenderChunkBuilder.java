package com.fallpoint.pocketsculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mobile render staging. Every chunk uses local 16-bit indices and never exceeds
 * 65,535 unique vertices. CPU mesh state remains stable-ID based.
 */
final class RenderChunkBuilder {
    static final int MAX_CHUNK_VERTICES = 65_535;

    static final class Chunk {
        final int[] stableVertexIds;
        final short[] indices;
        final float[] positions;
        final float[] normals;
        float minX, minY, minZ, maxX, maxY, maxZ;

        Chunk(int[] stableVertexIds, short[] indices) {
            this.stableVertexIds = stableVertexIds;
            this.indices = indices;
            this.positions = new float[stableVertexIds.length * 3];
            this.normals = new float[stableVertexIds.length * 3];
        }

        long estimatedBytes() {
            return (long)stableVertexIds.length * 4L
                    + (long)indices.length * 2L
                    + (long)(positions.length + normals.length) * 4L + 24L;
        }
    }

    static final class RenderPlan {
        Chunk[] chunks = new Chunk[0];
        long topologyVersion = Long.MIN_VALUE;
        long geometryVersion = Long.MIN_VALUE;
        long uploadBytesPrepared;

        int totalTriangles() {
            int total = 0;
            for (Chunk c : chunks) total += c.indices.length / 3;
            return total;
        }

        long estimatedBytes() {
            long bytes = 0;
            for (Chunk c : chunks) bytes += c.estimatedBytes();
            return bytes;
        }
    }

    private final MeshKernel mesh;
    private final RenderPlan plan = new RenderPlan();

    RenderChunkBuilder(MeshKernel mesh) {
        this.mesh = mesh;
    }

    RenderPlan currentPlan() {
        if (plan.topologyVersion != mesh.topologyVersion) {
            rebuild();
        } else if (plan.geometryVersion != mesh.geometryVersion) {
            refreshGeometry();
        }
        return plan;
    }

    void rebuild() {
        int[] localIndex = new int[Math.max(1, mesh.vertexHighWater)];
        Arrays.fill(localIndex, -1);
        IntList usedVertices = new IntList(4096);
        ShortList indexList = new ShortList(8192);
        List<Chunk> chunks = new ArrayList<>();

        for (int f = 0; f < mesh.faceHighWater; f++) {
            if (!mesh.isFaceAlive(f)) continue;
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];

            int extra = (localIndex[a] < 0 ? 1 : 0)
                    + (localIndex[b] < 0 ? 1 : 0)
                    + (localIndex[c] < 0 ? 1 : 0);
            if (usedVertices.size + extra > MAX_CHUNK_VERTICES && indexList.size > 0) {
                chunks.add(finishChunk(usedVertices, indexList));
                for (int i = 0; i < usedVertices.size; i++) localIndex[usedVertices.data[i]] = -1;
                usedVertices.clear();
                indexList.clear();
            }

            int la = mapVertex(a, localIndex, usedVertices);
            int lb = mapVertex(b, localIndex, usedVertices);
            int lc = mapVertex(c, localIndex, usedVertices);
            indexList.add((short)la);
            indexList.add((short)lb);
            indexList.add((short)lc);
        }

        if (indexList.size > 0) chunks.add(finishChunk(usedVertices, indexList));
        plan.chunks = chunks.toArray(new Chunk[0]);
        plan.topologyVersion = mesh.topologyVersion;
        refreshGeometry();
    }

    void refreshGeometry() {
        mesh.ensureNormals();
        long bytes = 0L;
        for (Chunk chunk : plan.chunks) {
            chunk.minX = chunk.minY = chunk.minZ = Float.POSITIVE_INFINITY;
            chunk.maxX = chunk.maxY = chunk.maxZ = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < chunk.stableVertexIds.length; i++) {
                int stable = chunk.stableVertexIds[i];
                int src = stable * 3, dst = i * 3;
                float x = mesh.positions[src], y = mesh.positions[src + 1], z = mesh.positions[src + 2];
                chunk.positions[dst] = x;
                chunk.positions[dst + 1] = y;
                chunk.positions[dst + 2] = z;
                chunk.normals[dst] = mesh.normals[src];
                chunk.normals[dst + 1] = mesh.normals[src + 1];
                chunk.normals[dst + 2] = mesh.normals[src + 2];
                chunk.minX = Math.min(chunk.minX, x); chunk.maxX = Math.max(chunk.maxX, x);
                chunk.minY = Math.min(chunk.minY, y); chunk.maxY = Math.max(chunk.maxY, y);
                chunk.minZ = Math.min(chunk.minZ, z); chunk.maxZ = Math.max(chunk.maxZ, z);
            }
            bytes += (long)(chunk.positions.length + chunk.normals.length) * 4L;
        }
        plan.uploadBytesPrepared = bytes;
        plan.geometryVersion = mesh.geometryVersion;
    }

    private Chunk finishChunk(IntList vertices, ShortList indices) {
        return new Chunk(
                Arrays.copyOf(vertices.data, vertices.size),
                Arrays.copyOf(indices.data, indices.size)
        );
    }

    private static int mapVertex(int stable, int[] localIndex, IntList used) {
        int mapped = localIndex[stable];
        if (mapped >= 0) return mapped;
        mapped = used.size;
        if (mapped >= MAX_CHUNK_VERTICES) throw new IllegalStateException("16-bit render chunk overflow");
        localIndex[stable] = mapped;
        used.add(stable);
        return mapped;
    }

    private static final class IntList {
        int[] data;
        int size;
        IntList(int initial) { data = new int[Math.max(16, initial)]; }
        void add(int v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length + data.length / 2 + 16);
            data[size++] = v;
        }
        void clear() { size = 0; }
    }

    private static final class ShortList {
        short[] data;
        int size;
        ShortList(int initial) { data = new short[Math.max(16, initial)]; }
        void add(short v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length + data.length / 2 + 16);
            data[size++] = v;
        }
        void clear() { size = 0; }
    }
}
