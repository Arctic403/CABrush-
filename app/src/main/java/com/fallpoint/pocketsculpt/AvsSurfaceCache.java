package com.fallpoint.pocketsculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Disposable AVS render cache.
 *
 * Surface Truth 0.1.1 rules:
 * - every grid cube uses the same six-tetrahedra Freudenthal split
 * - adjacent cubes therefore share the same face diagonals
 * - edge intersections are computed from canonically ordered global lattice
 *   endpoints, so a seam vertex is bit-identical even when rebuilt by a
 *   neighboring chunk
 * - triangle winding is derived from the exact linear scalar gradient of the
 *   tetrahedron, never from noisy post-CSG shading normals
 * - positive SDF means outside, so triangle geometric normals always point in
 *   the direction of increasing field value
 * - render normals are shading data only and can never decide topology/winding
 *
 * The signed-distance volume remains the only sculpt truth. These chunks may be
 * dropped and rebuilt at any time.
 */
final class AvsSurfaceCache {
    static final class Chunk {
        final int brickId;
        final int bx, by, bz;
        final long revision;
        final float[] positions;
        final float[] normals;
        final short[] indices;
        final float minX, minY, minZ, maxX, maxY, maxZ;

        Chunk(int brickId, int bx, int by, int bz, long revision,
              float[] positions, float[] normals, short[] indices,
              float minX, float minY, float minZ,
              float maxX, float maxY, float maxZ) {
            this.brickId = brickId;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.revision = revision;
            this.positions = positions;
            this.normals = normals;
            this.indices = indices;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        int triangleCount() { return indices.length / 3; }

        long estimatedBytes() {
            return (long) (positions.length + normals.length) * 4L
                    + (long) indices.length * 2L + 64L;
        }
    }

    static final class RenderPlan {
        Chunk[] chunks = new Chunk[0];
        long fieldVersion = Long.MIN_VALUE;
        long surfaceVersion;
        int rebuiltChunks;
        long rebuiltBytes;
        long rebuildNanos;
        int dirtyBefore;
        int totalTriangles;
        int totalVertices;

        long estimatedBytes() {
            long n = 0;
            for (Chunk c : chunks) n += c.estimatedBytes();
            return n;
        }
    }

    // Cube corner index = x + 2*y + 4*z. All six tetrahedra share 0 -> 7.
    // This is the same split in every cell, which guarantees matching face
    // diagonals on neighboring cubes/chunks.
    private static final int[][] TETS = {
            {0, 1, 3, 7},
            {0, 1, 5, 7},
            {0, 2, 3, 7},
            {0, 2, 6, 7},
            {0, 4, 5, 7},
            {0, 4, 6, 7}
    };

    private static final float FACE_AREA_EPS = 1.0e-14f;
    private static final float GRADIENT_EPS = 1.0e-10f;

    private final AvsVolume volume;
    private Chunk[] chunkByBrick = new Chunk[256];
    private final RenderPlan plan = new RenderPlan();
    private long nextRevision = 1L;
    private final float[] gradientScratch = new float[3];

    AvsSurfaceCache(AvsVolume volume) {
        this.volume = volume;
    }

    RenderPlan currentPlan() {
        if (plan.fieldVersion != volume.fieldVersion || volume.dirtyBrickCount() > 0) {
            rebuildDirty();
        }
        return plan;
    }

    void rebuildAll() {
        volume.markAllDirty();
        rebuildDirty();
    }

    private void rebuildDirty() {
        long diagStart = EngineDiagnostics.nowNanos();
        ensureChunkCapacity(volume.brickCount());
        int rebuilt = 0;
        long bytes = 0;
        int dirtyBefore = volume.dirtyBrickCount();

        for (int id = 0; id < volume.brickCount(); id++) {
            if (!volume.isBrickDirty(id)) continue;
            long chunkStart = EngineDiagnostics.nowNanos();
            Chunk c = buildChunk(id);
            if (EngineDiagnostics.isEnabled()) {
                long chunkNs = chunkStart == 0L ? 0L : System.nanoTime() - chunkStart;
                EngineDiagnostics.counter("surface.chunks_rebuilt", 1L);
                EngineDiagnostics.gauge("surface.last_chunk_ms", chunkNs / 1_000_000.0);
                EngineDiagnostics.record("surface", "chunk_rebuild",
                        "brick_id=" + id
                                + " coord=" + volume.brick(id).bx + ","
                                + volume.brick(id).by + "," + volume.brick(id).bz
                                + " vertices=" + (c == null ? 0 : c.positions.length / 3)
                                + " triangles=" + (c == null ? 0 : c.indices.length / 3)
                                + " bytes=" + (c == null ? 0 : c.estimatedBytes())
                                + " duration_ns=" + chunkNs);
            }
            chunkByBrick[id] = c;
            volume.clearBrickDirty(id);
            rebuilt++;
            if (c != null) bytes += c.estimatedBytes();
        }

        List<Chunk> live = new ArrayList<>();
        int triangles = 0;
        int vertices = 0;
        for (int i = 0; i < volume.brickCount(); i++) {
            Chunk c = chunkByBrick[i];
            if (c == null || c.indices.length == 0) continue;
            live.add(c);
            triangles += c.indices.length / 3;
            vertices += c.positions.length / 3;
        }

        plan.chunks = live.toArray(new Chunk[0]);
        plan.fieldVersion = volume.fieldVersion;
        plan.surfaceVersion++;
        plan.rebuiltChunks = rebuilt;
        plan.rebuiltBytes = bytes;
        plan.rebuildNanos = diagStart == 0L ? 0L : System.nanoTime() - diagStart;
        plan.dirtyBefore = dirtyBefore;
        plan.totalTriangles = triangles;
        plan.totalVertices = vertices;

        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.counter("surface.rebuild_passes", 1L);
            EngineDiagnostics.counter("surface.rebuilt_bytes", bytes);
            EngineDiagnostics.gauge("surface.rebuild.last_ms", plan.rebuildNanos / 1_000_000.0);
            EngineDiagnostics.gauge("surface.total_triangles", triangles);
            EngineDiagnostics.gauge("surface.total_vertices", vertices);
            EngineDiagnostics.gauge("surface.estimated_mb", plan.estimatedBytes() / 1048576.0);
            EngineDiagnostics.state("surface.field_version", String.valueOf(plan.fieldVersion));
            EngineDiagnostics.state("surface.surface_version", String.valueOf(plan.surfaceVersion));
            EngineDiagnostics.record("surface", "rebuild_pass",
                    "dirty_before=" + dirtyBefore
                            + " rebuilt=" + rebuilt
                            + " rebuilt_bytes=" + bytes
                            + " chunks=" + plan.chunks.length
                            + " vertices=" + vertices
                            + " triangles=" + triangles
                            + " estimated_bytes=" + plan.estimatedBytes()
                            + " duration_ns=" + plan.rebuildNanos);
            if (plan.rebuildNanos > 200_000_000L) {
                EngineDiagnostics.anomaly("slow_surface_rebuild",
                        "duration_ns=" + plan.rebuildNanos
                                + " rebuilt=" + rebuilt
                                + " triangles=" + triangles);
            }
            if (triangles > 1_000_000) {
                EngineDiagnostics.anomaly("triangle_growth",
                        "triangles=" + triangles + " vertices=" + vertices
                                + " chunks=" + plan.chunks.length);
            }
        }
    }

    private Chunk buildChunk(int brickId) {
        AvsVolume.Brick b = volume.brick(brickId);
        if (b == null) return null;

        int baseX = b.bx * AvsVolume.BRICK_SIZE;
        int baseY = b.by * AvsVolume.BRICK_SIZE;
        int baseZ = b.bz * AvsVolume.BRICK_SIZE;

        FloatList positions = new FloatList(4096);
        ShortList indices = new ShortList(8192);
        PrimitiveLongIntMap edgeVertex = new PrimitiveLongIntMap(2048);

        int[] pointId = new int[8];
        int[] gx = new int[8];
        int[] gy = new int[8];
        int[] gz = new int[8];
        float[] value = new float[8];
        float[] tetGradient = new float[3];

        for (int lz = 0; lz < AvsVolume.BRICK_SIZE; lz++) {
            for (int ly = 0; ly < AvsVolume.BRICK_SIZE; ly++) {
                for (int lx = 0; lx < AvsVolume.BRICK_SIZE; lx++) {
                    boolean negative = false;
                    boolean positive = false;

                    for (int c = 0; c < 8; c++) {
                        int cx = c & 1;
                        int cy = (c >> 1) & 1;
                        int cz = (c >> 2) & 1;
                        int plx = lx + cx;
                        int ply = ly + cy;
                        int plz = lz + cz;
                        pointId[c] = plx + 9 * (ply + 9 * plz);
                        gx[c] = baseX + plx;
                        gy[c] = baseY + ply;
                        gz[c] = baseZ + plz;
                        value[c] = volume.sampleGrid(gx[c], gy[c], gz[c]);
                        if (value[c] < 0f) negative = true;
                        else positive = true;
                    }

                    if (!(negative && positive)) continue;
                    for (int[] tet : TETS) {
                        polygonizeTet(tet, pointId, gx, gy, gz, value,
                                tetGradient, edgeVertex, positions, indices);
                    }
                }
            }
        }

        if (indices.size == 0) {
            return new Chunk(brickId, b.bx, b.by, b.bz, nextRevision++,
                    new float[0], new float[0], new short[0],
                    0f, 0f, 0f, 0f, 0f, 0f);
        }

        float[] p = Arrays.copyOf(positions.data, positions.size);
        short[] ii = Arrays.copyOf(indices.data, indices.size);
        float[] n = buildNormals(p, ii);

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < p.length; i += 3) {
            minX = Math.min(minX, p[i]);
            minY = Math.min(minY, p[i + 1]);
            minZ = Math.min(minZ, p[i + 2]);
            maxX = Math.max(maxX, p[i]);
            maxY = Math.max(maxY, p[i + 1]);
            maxZ = Math.max(maxZ, p[i + 2]);
        }

        return new Chunk(brickId, b.bx, b.by, b.bz, nextRevision++,
                p, n, ii, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void polygonizeTet(int[] tet,
                               int[] pointId,
                               int[] gx, int[] gy, int[] gz,
                               float[] value,
                               float[] gradient,
                               PrimitiveLongIntMap edgeVertex,
                               FloatList positions,
                               ShortList indices) {
        int[] inside = new int[4];
        int[] outside = new int[4];
        int insideCount = 0;
        int outsideCount = 0;

        for (int q = 0; q < 4; q++) {
            int c = tet[q];
            if (value[c] < 0f) inside[insideCount++] = c;
            else outside[outsideCount++] = c;
        }
        if (insideCount == 0 || insideCount == 4) return;

        tetraGradient(tet, gx, gy, gz, value, gradient);
        if (lengthSquared(gradient[0], gradient[1], gradient[2]) < GRADIENT_EPS) {
            // A sign-changing tetrahedron should have a usable linear gradient,
            // but fixed-point fields can get extremely flat. The inside->outside
            // centroid direction is a deterministic fallback with the same sign
            // meaning: positive SDF is outside.
            centroidDirection(inside, insideCount, outside, outsideCount,
                    gx, gy, gz, gradient);
        }

        if (insideCount == 1) {
            int a = inside[0];
            int v0 = edgeVertex(a, outside[0], pointId, gx, gy, gz, value, edgeVertex, positions);
            int v1 = edgeVertex(a, outside[1], pointId, gx, gy, gz, value, edgeVertex, positions);
            int v2 = edgeVertex(a, outside[2], pointId, gx, gy, gz, value, edgeVertex, positions);
            emitOutward(v0, v1, v2, gradient, positions, indices);
        } else if (insideCount == 3) {
            int o = outside[0];
            int v0 = edgeVertex(o, inside[0], pointId, gx, gy, gz, value, edgeVertex, positions);
            int v1 = edgeVertex(o, inside[1], pointId, gx, gy, gz, value, edgeVertex, positions);
            int v2 = edgeVertex(o, inside[2], pointId, gx, gy, gz, value, edgeVertex, positions);
            emitOutward(v0, v1, v2, gradient, positions, indices);
        } else {
            int a = inside[0];
            int b = inside[1];
            int c = outside[0];
            int d = outside[1];
            int p0 = edgeVertex(a, c, pointId, gx, gy, gz, value, edgeVertex, positions);
            int p1 = edgeVertex(a, d, pointId, gx, gy, gz, value, edgeVertex, positions);
            int p2 = edgeVertex(b, c, pointId, gx, gy, gz, value, edgeVertex, positions);
            int p3 = edgeVertex(b, d, pointId, gx, gy, gz, value, edgeVertex, positions);
            // Cyclic quad order is p0 -> p1 -> p3 -> p2. Both triangles are
            // then oriented from negative SDF (inside) toward positive SDF.
            emitOutward(p0, p1, p3, gradient, positions, indices);
            emitOutward(p0, p3, p2, gradient, positions, indices);
        }
    }

    /** Exact gradient of the tetrahedron's linear scalar interpolant. */
    private static void tetraGradient(int[] tet,
                                      int[] gx, int[] gy, int[] gz,
                                      float[] value,
                                      float[] out) {
        int a = tet[0], b = tet[1], c = tet[2], d = tet[3];
        float e1x = gx[b] - gx[a], e1y = gy[b] - gy[a], e1z = gz[b] - gz[a];
        float e2x = gx[c] - gx[a], e2y = gy[c] - gy[a], e2z = gz[c] - gz[a];
        float e3x = gx[d] - gx[a], e3y = gy[d] - gy[a], e3z = gz[d] - gz[a];
        float f1 = value[b] - value[a];
        float f2 = value[c] - value[a];
        float f3 = value[d] - value[a];

        float c23x = e2y * e3z - e2z * e3y;
        float c23y = e2z * e3x - e2x * e3z;
        float c23z = e2x * e3y - e2y * e3x;
        float c31x = e3y * e1z - e3z * e1y;
        float c31y = e3z * e1x - e3x * e1z;
        float c31z = e3x * e1y - e3y * e1x;
        float c12x = e1y * e2z - e1z * e2y;
        float c12y = e1z * e2x - e1x * e2z;
        float c12z = e1x * e2y - e1y * e2x;
        float det = e1x * c23x + e1y * c23y + e1z * c23z;

        if (Math.abs(det) < 1.0e-12f) {
            out[0] = out[1] = out[2] = 0f;
            return;
        }
        float inv = 1f / det;
        out[0] = (f1 * c23x + f2 * c31x + f3 * c12x) * inv;
        out[1] = (f1 * c23y + f2 * c31y + f3 * c12y) * inv;
        out[2] = (f1 * c23z + f2 * c31z + f3 * c12z) * inv;
    }

    private static void centroidDirection(int[] inside, int ni,
                                          int[] outside, int no,
                                          int[] gx, int[] gy, int[] gz,
                                          float[] out) {
        float ix = 0f, iy = 0f, iz = 0f;
        float ox = 0f, oy = 0f, oz = 0f;
        for (int i = 0; i < ni; i++) {
            int c = inside[i];
            ix += gx[c]; iy += gy[c]; iz += gz[c];
        }
        for (int i = 0; i < no; i++) {
            int c = outside[i];
            ox += gx[c]; oy += gy[c]; oz += gz[c];
        }
        ix /= ni; iy /= ni; iz /= ni;
        ox /= no; oy /= no; oz /= no;
        out[0] = ox - ix;
        out[1] = oy - iy;
        out[2] = oz - iz;
    }

    private int edgeVertex(int ca, int cb,
                           int[] pointId,
                           int[] gx, int[] gy, int[] gz,
                           float[] value,
                           PrimitiveLongIntMap map,
                           FloatList positions) {
        int pa = pointId[ca];
        int pb = pointId[cb];
        int lo = Math.min(pa, pb);
        int hi = Math.max(pa, pb);
        long key = ((long) lo << 32) | (hi & 0xffffffffL);
        int old = map.get(key, -1);
        if (old >= 0) return old;

        // Canonical endpoint ordering makes the interpolation operation itself
        // identical on both sides of a chunk boundary, not merely numerically
        // close after welding.
        int a = ca;
        int b = cb;
        if (compareLattice(gx[ca], gy[ca], gz[ca], gx[cb], gy[cb], gz[cb]) > 0) {
            a = cb;
            b = ca;
        }

        float va = value[a];
        float vb = value[b];
        float denominator = va - vb;
        float t = Math.abs(denominator) < 1e-20f ? 0.5f : va / denominator;
        t = Math.max(0f, Math.min(1f, t));

        float x = (gx[a] + (gx[b] - gx[a]) * t) * AvsVolume.VOXEL_SIZE;
        float y = (gy[a] + (gy[b] - gy[a]) * t) * AvsVolume.VOXEL_SIZE;
        float z = (gz[a] + (gz[b] - gz[a]) * t) * AvsVolume.VOXEL_SIZE;

        int id = positions.size / 3;
        if (id >= 65535) {
            throw new IllegalStateException("AVS chunk exceeds 16-bit vertex range");
        }
        positions.add(x);
        positions.add(y);
        positions.add(z);
        map.put(key, id, -1);
        return id;
    }

    private static int compareLattice(int ax, int ay, int az, int bx, int by, int bz) {
        if (ax != bx) return Integer.compare(ax, bx);
        if (ay != by) return Integer.compare(ay, by);
        return Integer.compare(az, bz);
    }

    private static void emitOutward(int a, int b, int c,
                                    float[] gradient,
                                    FloatList positions,
                                    ShortList indices) {
        if (a == b || b == c || c == a) return;
        int ia = a * 3, ib = b * 3, ic = c * 3;
        float abx = positions.data[ib] - positions.data[ia];
        float aby = positions.data[ib + 1] - positions.data[ia + 1];
        float abz = positions.data[ib + 2] - positions.data[ia + 2];
        float acx = positions.data[ic] - positions.data[ia];
        float acy = positions.data[ic + 1] - positions.data[ia + 1];
        float acz = positions.data[ic + 2] - positions.data[ia + 2];
        float fx = aby * acz - abz * acy;
        float fy = abz * acx - abx * acz;
        float fz = abx * acy - aby * acx;
        float area2 = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (!(area2 > FACE_AREA_EPS) || !Float.isFinite(area2)) return;

        // SDF convention is negative-inside / positive-outside. The exact
        // tetrahedral scalar gradient therefore *is* the outward direction.
        if (fx * gradient[0] + fy * gradient[1] + fz * gradient[2] < 0f) {
            int tmp = b;
            b = c;
            c = tmp;
        }
        indices.add((short) a);
        indices.add((short) b);
        indices.add((short) c);
    }

    private float[] buildNormals(float[] p, short[] indices) {
        float[] geometric = new float[p.length];
        for (int i = 0; i < indices.length; i += 3) {
            int a = (indices[i] & 0xffff) * 3;
            int b = (indices[i + 1] & 0xffff) * 3;
            int c = (indices[i + 2] & 0xffff) * 3;
            float abx = p[b] - p[a], aby = p[b + 1] - p[a + 1], abz = p[b + 2] - p[a + 2];
            float acx = p[c] - p[a], acy = p[c + 1] - p[a + 1], acz = p[c + 2] - p[a + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            geometric[a] += nx; geometric[a + 1] += ny; geometric[a + 2] += nz;
            geometric[b] += nx; geometric[b + 1] += ny; geometric[b + 2] += nz;
            geometric[c] += nx; geometric[c + 1] += ny; geometric[c + 2] += nz;
        }

        float[] normals = new float[p.length];
        for (int i = 0; i < p.length; i += 3) {
            float gx = geometric[i], gy = geometric[i + 1], gz = geometric[i + 2];
            float gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            if (gl > 1e-12f) {
                gx /= gl; gy /= gl; gz /= gl;
            } else {
                gx = 0f; gy = 0f; gz = 1f;
            }

            volume.gradient(p[i], p[i + 1], p[i + 2], gradientScratch);
            float sx = gradientScratch[0], sy = gradientScratch[1], sz = gradientScratch[2];
            float dot = sx * gx + sy * gy + sz * gz;

            // Cross-chunk shading uses the SDF gradient when it agrees with the
            // oriented surface. Near CSG creases the sampled gradient can be
            // unstable, so geometric orientation is the safe fallback.
            if (Float.isFinite(dot) && dot > 0.20f) {
                float nx = sx + gx * 0.35f;
                float ny = sy + gy * 0.35f;
                float nz = sz + gz * 0.35f;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 1e-12f) {
                    normals[i] = nx / nl;
                    normals[i + 1] = ny / nl;
                    normals[i + 2] = nz / nl;
                    continue;
                }
            }
            normals[i] = gx;
            normals[i + 1] = gy;
            normals[i + 2] = gz;
        }
        return normals;
    }

    private void ensureChunkCapacity(int needed) {
        if (chunkByBrick.length >= needed) return;
        int cap = chunkByBrick.length;
        while (cap < needed) cap = cap + cap / 2 + 64;
        chunkByBrick = Arrays.copyOf(chunkByBrick, cap);
    }

    private static float lengthSquared(float x, float y, float z) {
        return x * x + y * y + z * z;
    }

    private static final class FloatList {
        float[] data;
        int size;
        FloatList(int capacity) { data = new float[Math.max(16, capacity)]; }
        void add(float v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length + data.length / 2 + 64);
            data[size++] = v;
        }
    }

    private static final class ShortList {
        short[] data;
        int size;
        ShortList(int capacity) { data = new short[Math.max(16, capacity)]; }
        void add(short v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length + data.length / 2 + 64);
            data[size++] = v;
        }
    }
}
