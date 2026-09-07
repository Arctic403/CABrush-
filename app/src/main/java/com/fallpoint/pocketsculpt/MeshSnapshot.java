package com.fallpoint.pocketsculpt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

/** Deterministic CPU mesh snapshot used by rollback, VSS and future undo/crash recovery. */
final class MeshSnapshot {
    static final int SCHEMA_VERSION = 1;
    private static final int MAGIC = 0x43414233; // CAB3

    final int vertexHighWater;
    final int liveVertexCount;
    final float[] positions;
    final byte[] vertexAlive;
    final int[] freeVertices;
    final int freeVertexCount;

    final int faceHighWater;
    final int liveFaceCount;
    final int[] faceA, faceB, faceC;
    final byte[] faceAlive;
    final int[] freeFaces;
    final int freeFaceCount;

    final long geometryVersion;
    final long topologyVersion;

    private MeshSnapshot(
            int vertexHighWater, int liveVertexCount, float[] positions, byte[] vertexAlive,
            int[] freeVertices, int freeVertexCount,
            int faceHighWater, int liveFaceCount, int[] faceA, int[] faceB, int[] faceC,
            byte[] faceAlive, int[] freeFaces, int freeFaceCount,
            long geometryVersion, long topologyVersion
    ) {
        this.vertexHighWater = vertexHighWater;
        this.liveVertexCount = liveVertexCount;
        this.positions = positions;
        this.vertexAlive = vertexAlive;
        this.freeVertices = freeVertices;
        this.freeVertexCount = freeVertexCount;
        this.faceHighWater = faceHighWater;
        this.liveFaceCount = liveFaceCount;
        this.faceA = faceA;
        this.faceB = faceB;
        this.faceC = faceC;
        this.faceAlive = faceAlive;
        this.freeFaces = freeFaces;
        this.freeFaceCount = freeFaceCount;
        this.geometryVersion = geometryVersion;
        this.topologyVersion = topologyVersion;
    }

    static MeshSnapshot capture(MeshKernel mesh) {
        return new MeshSnapshot(
                mesh.vertexHighWater,
                mesh.liveVertexCount,
                Arrays.copyOf(mesh.positions, mesh.vertexHighWater * 3),
                Arrays.copyOf(mesh.vertexAlive, mesh.vertexHighWater),
                Arrays.copyOf(mesh.freeVertices, mesh.freeVertexCount),
                mesh.freeVertexCount,
                mesh.faceHighWater,
                mesh.liveFaceCount,
                Arrays.copyOf(mesh.faceA, mesh.faceHighWater),
                Arrays.copyOf(mesh.faceB, mesh.faceHighWater),
                Arrays.copyOf(mesh.faceC, mesh.faceHighWater),
                Arrays.copyOf(mesh.faceAlive, mesh.faceHighWater),
                Arrays.copyOf(mesh.freeFaces, mesh.freeFaceCount),
                mesh.freeFaceCount,
                mesh.geometryVersion,
                mesh.topologyVersion
        );
    }

    void restoreInto(MeshKernel mesh) {
        int vertexCapacity = Math.max(16, vertexHighWater);
        int faceCapacity = Math.max(16, faceHighWater);

        mesh.positions = Arrays.copyOf(positions, vertexCapacity * 3);
        mesh.normals = new float[vertexCapacity * 3];
        mesh.vertexAlive = Arrays.copyOf(vertexAlive, vertexCapacity);
        mesh.freeVertices = new int[vertexCapacity];
        System.arraycopy(freeVertices, 0, mesh.freeVertices, 0, freeVertexCount);
        mesh.freeVertexCount = freeVertexCount;
        mesh.vertexHighWater = vertexHighWater;
        mesh.liveVertexCount = liveVertexCount;
        mesh.vertexHalfEdge = new int[vertexCapacity];
        Arrays.fill(mesh.vertexHalfEdge, MeshKernel.INVALID);

        mesh.faceA = Arrays.copyOf(faceA, faceCapacity);
        mesh.faceB = Arrays.copyOf(faceB, faceCapacity);
        mesh.faceC = Arrays.copyOf(faceC, faceCapacity);
        mesh.faceAlive = Arrays.copyOf(faceAlive, faceCapacity);
        mesh.freeFaces = new int[faceCapacity];
        System.arraycopy(freeFaces, 0, mesh.freeFaces, 0, freeFaceCount);
        mesh.freeFaceCount = freeFaceCount;
        mesh.faceHighWater = faceHighWater;
        mesh.liveFaceCount = liveFaceCount;
        mesh.faceHalfEdge = new int[faceCapacity];
        Arrays.fill(mesh.faceHalfEdge, MeshKernel.INVALID);

        mesh.heOrigin = new int[0];
        mesh.heDest = new int[0];
        mesh.heFace = new int[0];
        mesh.heNext = new int[0];
        mesh.heTwin = new int[0];
        mesh.halfEdgeCount = 0;

        // Bump generations rather than rewinding them so all dependent caches
        // become stale after a rollback/restore.
        mesh.geometryVersion = Math.max(mesh.geometryVersion, geometryVersion) + 1L;
        mesh.topologyVersion = Math.max(mesh.topologyVersion, topologyVersion) + 1L;
        mesh.topologyDirty = true;
        mesh.normalsDirty = true;
        mesh.ensureConnectivity();
        mesh.recomputeNormalsAll();
    }

    byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(SCHEMA_VERSION);
            out.writeInt(vertexHighWater);
            out.writeInt(liveVertexCount);
            out.writeInt(freeVertexCount);
            for (int i = 0; i < vertexHighWater; i++) {
                out.writeByte(vertexAlive[i]);
                int b = i * 3;
                out.writeInt(Float.floatToRawIntBits(positions[b]));
                out.writeInt(Float.floatToRawIntBits(positions[b + 1]));
                out.writeInt(Float.floatToRawIntBits(positions[b + 2]));
            }
            for (int i = 0; i < freeVertexCount; i++) out.writeInt(freeVertices[i]);

            out.writeInt(faceHighWater);
            out.writeInt(liveFaceCount);
            out.writeInt(freeFaceCount);
            for (int i = 0; i < faceHighWater; i++) {
                out.writeByte(faceAlive[i]);
                out.writeInt(faceA[i]);
                out.writeInt(faceB[i]);
                out.writeInt(faceC[i]);
            }
            for (int i = 0; i < freeFaceCount; i++) out.writeInt(freeFaces[i]);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    String sha256() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(toBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    long estimatedBytes() {
        return (long)positions.length * 4L + vertexAlive.length + (long)freeVertices.length * 4L
                + (long)(faceA.length + faceB.length + faceC.length + freeFaces.length) * 4L
                + faceAlive.length + 64L;
    }
}
