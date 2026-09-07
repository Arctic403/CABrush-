package com.fallpoint.pocketsculpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * CABrush AVS 0.1 source of truth.
 *
 * The sculpt is a sparse signed-distance field stored in 8x8x8 sample bricks.
 * Triangles are disposable render cache and never own sculpt state.
 */
final class AvsVolume {
    static final int BRICK_SIZE = 8;
    static final int SAMPLES_PER_BRICK = BRICK_SIZE * BRICK_SIZE * BRICK_SIZE;
    static final int MAX_BRICKS = 12_000;

    static final float VOXEL_SIZE = 0.045f;
    static final float Q_PER_VOXEL = 8192.0f;
    static final short FAR_OUTSIDE = (short)32760;
    static final float BAND_WORLD = (32760.0f / Q_PER_VOXEL) * VOXEL_SIZE;

    static final class Brick {
        final int bx, by, bz;
        final short[] sdf = new short[SAMPLES_PER_BRICK];
        boolean dirty = true;
        long revision = 1L;

        Brick(int bx, int by, int bz) {
            this.bx = bx; this.by = by; this.bz = bz;
            Arrays.fill(sdf, FAR_OUTSIDE);
        }
    }

    static final class RayHit {
        float x, y, z;
        float nx, ny, nz;
        float t;
        boolean hit;

        RayHit clear() {
            hit = false;
            x = y = z = nx = ny = nz = t = 0f;
            return this;
        }
    }

    static final class BrushResult {
        boolean changed;
        int changedSamples;
        int touchedBricks;
        int allocatedBricks;
        float centerX, centerY, centerZ;
        float effectiveDepth;
    }

    static final class Stats {
        final int bricks;
        final long sampleBytes;
        final long negativeSamples;
        final float minX, minY, minZ, maxX, maxY, maxZ;

        Stats(int bricks, long sampleBytes, long negativeSamples,
              float minX, float minY, float minZ,
              float maxX, float maxY, float maxZ) {
            this.bricks = bricks;
            this.sampleBytes = sampleBytes;
            this.negativeSamples = negativeSamples;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    private final PrimitiveLongIntMap brickLookup = new PrimitiveLongIntMap(512);
    private Brick[] bricks = new Brick[256];
    private int brickCount;

    long fieldVersion = 1L;
    long allocationVersion = 1L;

    private int minBx = Integer.MAX_VALUE, minBy = Integer.MAX_VALUE, minBz = Integer.MAX_VALUE;
    private int maxBx = Integer.MIN_VALUE, maxBy = Integer.MIN_VALUE, maxBz = Integer.MIN_VALUE;

    private int[] changedBrickIds = new int[128];
    private int changedBrickCount;
    private int[] changedStamp = new int[256];
    private int stampGeneration = 1;

    private AvsVolume() {}

    static AvsVolume createSphere(float radius) {
        if (!(radius > 0f) || !Float.isFinite(radius)) {
            throw new IllegalArgumentException("radius");
        }
        AvsVolume v = new AvsVolume();
        float pad = BAND_WORLD + VOXEL_SIZE * 2f;
        int minG = floorGrid(-radius - pad);
        int maxG = ceilGrid(radius + pad);
        int minB = Math.floorDiv(minG, BRICK_SIZE) - 1;
        int maxB = Math.floorDiv(maxG, BRICK_SIZE) + 1;

        for (int bz = minB; bz <= maxB; bz++) {
            for (int by = minB; by <= maxB; by++) {
                for (int bx = minB; bx <= maxB; bx++) {
                    Brick b = v.ensureBrick(bx, by, bz);
                    int baseX = bx * BRICK_SIZE;
                    int baseY = by * BRICK_SIZE;
                    int baseZ = bz * BRICK_SIZE;
                    for (int lz = 0; lz < BRICK_SIZE; lz++) {
                        int gz = baseZ + lz;
                        float z = gz * VOXEL_SIZE;
                        for (int ly = 0; ly < BRICK_SIZE; ly++) {
                            int gy = baseY + ly;
                            float y = gy * VOXEL_SIZE;
                            for (int lx = 0; lx < BRICK_SIZE; lx++) {
                                int gx = baseX + lx;
                                float x = gx * VOXEL_SIZE;
                                float d = (float)Math.sqrt(x*x + y*y + z*z) - radius;
                                b.sdf[index(lx, ly, lz)] = encode(d);
                            }
                        }
                    }
                    b.dirty = true;
                }
            }
        }
        v.fieldVersion++;
        v.markAllDirty();
        return v;
    }

    int brickCount() { return brickCount; }

    Brick brick(int id) {
        if (id < 0 || id >= brickCount) return null;
        return bricks[id];
    }

    int brickId(int bx, int by, int bz) {
        return brickLookup.get(brickKey(bx, by, bz), -1);
    }

    boolean isBrickDirty(int id) {
        Brick b = brick(id);
        return b != null && b.dirty;
    }

    void clearBrickDirty(int id) {
        Brick b = brick(id);
        if (b != null) b.dirty = false;
    }

    int dirtyBrickCount() {
        int n = 0;
        for (int i = 0; i < brickCount; i++) if (bricks[i].dirty) n++;
        return n;
    }

    void markAllDirty() {
        for (int i = 0; i < brickCount; i++) {
            bricks[i].dirty = true;
            bricks[i].revision++;
        }
    }

    float sampleGrid(int gx, int gy, int gz) {
        int bx = Math.floorDiv(gx, BRICK_SIZE);
        int by = Math.floorDiv(gy, BRICK_SIZE);
        int bz = Math.floorDiv(gz, BRICK_SIZE);
        int id = brickId(bx, by, bz);
        if (id < 0) return decode(FAR_OUTSIDE);
        int lx = Math.floorMod(gx, BRICK_SIZE);
        int ly = Math.floorMod(gy, BRICK_SIZE);
        int lz = Math.floorMod(gz, BRICK_SIZE);
        return decode(bricks[id].sdf[index(lx, ly, lz)]);
    }

    short sampleGridQuantized(int gx, int gy, int gz) {
        int bx = Math.floorDiv(gx, BRICK_SIZE);
        int by = Math.floorDiv(gy, BRICK_SIZE);
        int bz = Math.floorDiv(gz, BRICK_SIZE);
        int id = brickId(bx, by, bz);
        if (id < 0) return FAR_OUTSIDE;
        int lx = Math.floorMod(gx, BRICK_SIZE);
        int ly = Math.floorMod(gy, BRICK_SIZE);
        int lz = Math.floorMod(gz, BRICK_SIZE);
        return bricks[id].sdf[index(lx, ly, lz)];
    }

    float sample(float x, float y, float z) {
        float gx = x / VOXEL_SIZE;
        float gy = y / VOXEL_SIZE;
        float gz = z / VOXEL_SIZE;
        int ix = fastFloor(gx);
        int iy = fastFloor(gy);
        int iz = fastFloor(gz);
        float tx = gx - ix, ty = gy - iy, tz = gz - iz;

        float c000 = sampleGrid(ix, iy, iz);
        float c100 = sampleGrid(ix + 1, iy, iz);
        float c010 = sampleGrid(ix, iy + 1, iz);
        float c110 = sampleGrid(ix + 1, iy + 1, iz);
        float c001 = sampleGrid(ix, iy, iz + 1);
        float c101 = sampleGrid(ix + 1, iy, iz + 1);
        float c011 = sampleGrid(ix, iy + 1, iz + 1);
        float c111 = sampleGrid(ix + 1, iy + 1, iz + 1);

        float x00 = lerp(c000, c100, tx);
        float x10 = lerp(c010, c110, tx);
        float x01 = lerp(c001, c101, tx);
        float x11 = lerp(c011, c111, tx);
        float y0 = lerp(x00, x10, ty);
        float y1 = lerp(x01, x11, ty);
        return lerp(y0, y1, tz);
    }

    void gradient(float x, float y, float z, float[] out) {
        float e = VOXEL_SIZE;
        float dx = sample(x + e, y, z) - sample(x - e, y, z);
        float dy = sample(x, y + e, z) - sample(x, y - e, z);
        float dz = sample(x, y, z + e) - sample(x, y, z - e);
        float len = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 1e-8f || !Float.isFinite(len)) {
            out[0] = 0f; out[1] = 0f; out[2] = 1f;
        } else {
            out[0] = dx / len; out[1] = dy / len; out[2] = dz / len;
        }
    }

    RayHit raycast(float ox, float oy, float oz, float dx, float dy, float dz, RayHit out) {
        if (out == null) out = new RayHit();
        out.clear();
        float dlen = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (dlen < 1e-8f || brickCount == 0) return out;
        dx /= dlen; dy /= dlen; dz /= dlen;

        float minX = minBx * BRICK_SIZE * VOXEL_SIZE - VOXEL_SIZE;
        float minY = minBy * BRICK_SIZE * VOXEL_SIZE - VOXEL_SIZE;
        float minZ = minBz * BRICK_SIZE * VOXEL_SIZE - VOXEL_SIZE;
        float maxX = (maxBx + 1) * BRICK_SIZE * VOXEL_SIZE + VOXEL_SIZE;
        float maxY = (maxBy + 1) * BRICK_SIZE * VOXEL_SIZE + VOXEL_SIZE;
        float maxZ = (maxBz + 1) * BRICK_SIZE * VOXEL_SIZE + VOXEL_SIZE;

        float[] interval = rayBox(ox, oy, oz, dx, dy, dz, minX, minY, minZ, maxX, maxY, maxZ);
        if (interval == null) return out;
        float t0 = Math.max(0f, interval[0]);
        float t1 = interval[1];
        if (!(t1 > t0)) return out;

        float step = VOXEL_SIZE * 0.45f;
        float prevT = t0;
        float prev = sample(ox + dx*prevT, oy + dy*prevT, oz + dz*prevT);

        for (float t = t0 + step; t <= t1 + step * 0.5f; t += step) {
            float tt = Math.min(t, t1);
            float cur = sample(ox + dx*tt, oy + dy*tt, oz + dz*tt);
            if (prev > 0f && cur <= 0f) {
                float lo = prevT, hi = tt;
                for (int i = 0; i < 10; i++) {
                    float mid = (lo + hi) * 0.5f;
                    float mv = sample(ox + dx*mid, oy + dy*mid, oz + dz*mid);
                    if (mv > 0f) lo = mid; else hi = mid;
                }
                float hitT = (lo + hi) * 0.5f;
                out.t = hitT;
                out.x = ox + dx*hitT;
                out.y = oy + dy*hitT;
                out.z = oz + dz*hitT;
                float[] g = new float[3];
                gradient(out.x, out.y, out.z, g);
                out.nx = g[0]; out.ny = g[1]; out.nz = g[2];
                out.hit = true;
                return out;
            }
            prev = cur;
            prevT = tt;
            if (tt >= t1) break;
        }
        return out;
    }

    BrushResult applyClay(float hitX, float hitY, float hitZ,
                           float nx, float ny, float nz,
                           float radius, float strength, BrushMode mode) {
        BrushResult result = new BrushResult();
        if (mode == null || !(radius > VOXEL_SIZE * 0.5f) || !(strength > 0f)) return result;
        float nlen = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (nlen < 1e-8f) return result;
        nx /= nlen; ny /= nlen; nz /= nlen;

        float depth = Math.max(VOXEL_SIZE * 0.45f, strength * 2.10f);
        depth = Math.min(depth, radius * 0.45f);
        float sign = mode == BrushMode.ADD ? 1f : -1f;

        // Clay is an oblate implicit blob: the slider Size controls the broad
        // tangent footprint while Strength controls shallow build/cut depth.
        // This avoids turning every dab into a full-radius sphere protrusion.
        float cx = hitX + nx * depth * 0.28f * sign;
        float cy = hitY + ny * depth * 0.28f * sign;
        float cz = hitZ + nz * depth * 0.28f * sign;

        BrushResult csg = applyEllipsoidCsg(
                cx, cy, cz, nx, ny, nz,
                radius, depth,
                mode == BrushMode.ADD
        );
        csg.centerX = cx; csg.centerY = cy; csg.centerZ = cz;
        csg.effectiveDepth = depth;
        return csg;
    }

    private BrushResult applyEllipsoidCsg(float cx, float cy, float cz,
                                           float nx, float ny, float nz,
                                           float tangentRadius, float normalRadius,
                                           boolean add) {
        BrushResult result = new BrushResult();
        float reach = tangentRadius + BAND_WORLD;
        changedBrickCount = 0;
        int stamp = nextStamp();
        int beforeBricks = brickCount;

        int minGx = floorGrid(cx - reach), maxGx = ceilGrid(cx + reach);
        int minGy = floorGrid(cy - reach), maxGy = ceilGrid(cy + reach);
        int minGz = floorGrid(cz - reach), maxGz = ceilGrid(cz + reach);
        allocateRegion(minGx,maxGx,minGy,maxGy,minGz,maxGz);

        int changedSamples = 0;
        float invT = 1f / Math.max(tangentRadius, 1e-6f);
        float invN = 1f / Math.max(normalRadius, 1e-6f);
        float distanceScale = Math.min(tangentRadius, normalRadius);

        for (int gz=minGz; gz<=maxGz; gz++) {
            float z=gz*VOXEL_SIZE;
            for (int gy=minGy; gy<=maxGy; gy++) {
                float y=gy*VOXEL_SIZE;
                for (int gx=minGx; gx<=maxGx; gx++) {
                    float x=gx*VOXEL_SIZE;
                    float dx=x-cx,dy=y-cy,dz=z-cz;
                    float axial=dx*nx+dy*ny+dz*nz;
                    float d2=dx*dx+dy*dy+dz*dz;
                    float tangent2=Math.max(0f,d2-axial*axial);
                    float q=(float)Math.sqrt(tangent2*invT*invT + axial*axial*invN*invN)-1f;
                    float brush=q*distanceScale;
                    if (brush > BAND_WORLD) continue;
                    changedSamples += applySampleCsg(gx,gy,gz,brush,add,stamp);
                }
            }
        }
        if (changedSamples>0) {
            fieldVersion++;
            markNeighborhoodDirty();
            result.changed=true;
        }
        result.changedSamples=changedSamples;
        result.touchedBricks=changedBrickCount;
        result.allocatedBricks=brickCount-beforeBricks;
        return result;
    }

    BrushResult applySphereCsg(float cx, float cy, float cz, float radius, boolean add) {
        BrushResult result = new BrushResult();
        if (!(radius > 0f) || !Float.isFinite(radius)) return result;

        changedBrickCount = 0;
        int stamp = nextStamp();
        int beforeBricks = brickCount;

        float reach = radius + BAND_WORLD;
        int minGx = floorGrid(cx - reach), maxGx = ceilGrid(cx + reach);
        int minGy = floorGrid(cy - reach), maxGy = ceilGrid(cy + reach);
        int minGz = floorGrid(cz - reach), maxGz = ceilGrid(cz + reach);

        allocateRegion(minGx,maxGx,minGy,maxGy,minGz,maxGz);

        int changedSamples = 0;
        for (int gz = minGz; gz <= maxGz; gz++) {
            float z = gz * VOXEL_SIZE;
            for (int gy = minGy; gy <= maxGy; gy++) {
                float y = gy * VOXEL_SIZE;
                for (int gx = minGx; gx <= maxGx; gx++) {
                    float x = gx * VOXEL_SIZE;
                    float dx = x - cx, dy = y - cy, dz = z - cz;
                    float brush = (float)Math.sqrt(dx*dx + dy*dy + dz*dz) - radius;
                    if (Math.abs(brush) > BAND_WORLD && brush > 0f) continue;

                    changedSamples += applySampleCsg(gx,gy,gz,brush,add,stamp);
                }
            }
        }

        if (changedSamples > 0) {
            fieldVersion++;
            markNeighborhoodDirty();
            result.changed = true;
        }
        result.changedSamples = changedSamples;
        result.touchedBricks = changedBrickCount;
        result.allocatedBricks = brickCount - beforeBricks;
        return result;
    }

    private void allocateRegion(int minGx,int maxGx,int minGy,int maxGy,int minGz,int maxGz) {
        int minBBx=Math.floorDiv(minGx,BRICK_SIZE)-1, maxBBx=Math.floorDiv(maxGx,BRICK_SIZE)+1;
        int minBBy=Math.floorDiv(minGy,BRICK_SIZE)-1, maxBBy=Math.floorDiv(maxGy,BRICK_SIZE)+1;
        int minBBz=Math.floorDiv(minGz,BRICK_SIZE)-1, maxBBz=Math.floorDiv(maxGz,BRICK_SIZE)+1;
        for(int bz=minBBz;bz<=maxBBz;bz++) for(int by=minBBy;by<=maxBBy;by++) for(int bx=minBBx;bx<=maxBBx;bx++) {
            ensureBrick(bx,by,bz);
        }
    }

    private int applySampleCsg(int gx,int gy,int gz,float brush,boolean add,int stamp) {
        int bx=Math.floorDiv(gx,BRICK_SIZE),by=Math.floorDiv(gy,BRICK_SIZE),bz=Math.floorDiv(gz,BRICK_SIZE);
        int id=brickId(bx,by,bz);
        Brick b=bricks[id];
        int si=index(Math.floorMod(gx,BRICK_SIZE),Math.floorMod(gy,BRICK_SIZE),Math.floorMod(gz,BRICK_SIZE));
        float old=decode(b.sdf[si]);
        float next=add?Math.min(old,brush):Math.max(old,-brush);
        short q=encode(next);
        if(q==b.sdf[si]) return 0;
        b.sdf[si]=q;
        if(changedStamp[id]!=stamp){changedStamp[id]=stamp;addChangedBrick(id);}
        return 1;
    }

    Stats stats() {
        long negative = 0;
        for (int b = 0; b < brickCount; b++) {
            for (short q : bricks[b].sdf) if (q < 0) negative++;
        }
        float minX = minBx == Integer.MAX_VALUE ? 0f : minBx * BRICK_SIZE * VOXEL_SIZE;
        float minY = minBy == Integer.MAX_VALUE ? 0f : minBy * BRICK_SIZE * VOXEL_SIZE;
        float minZ = minBz == Integer.MAX_VALUE ? 0f : minBz * BRICK_SIZE * VOXEL_SIZE;
        float maxX = maxBx == Integer.MIN_VALUE ? 0f : (maxBx + 1) * BRICK_SIZE * VOXEL_SIZE;
        float maxY = maxBy == Integer.MIN_VALUE ? 0f : (maxBy + 1) * BRICK_SIZE * VOXEL_SIZE;
        float maxZ = maxBz == Integer.MIN_VALUE ? 0f : (maxBz + 1) * BRICK_SIZE * VOXEL_SIZE;
        return new Stats(brickCount, (long)brickCount * SAMPLES_PER_BRICK * 2L, negative,
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    String fieldHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            Integer[] ids = new Integer[brickCount];
            for (int i = 0; i < brickCount; i++) ids[i] = i;
            Arrays.sort(ids, (a,b) -> {
                Brick A = bricks[a], B = bricks[b];
                if (A.bx != B.bx) return Integer.compare(A.bx, B.bx);
                if (A.by != B.by) return Integer.compare(A.by, B.by);
                return Integer.compare(A.bz, B.bz);
            });
            ByteBuffer buf = ByteBuffer.allocate(12 + SAMPLES_PER_BRICK * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int id : ids) {
                Brick b = bricks[id];
                buf.clear();
                buf.putInt(b.bx).putInt(b.by).putInt(b.bz);
                for (short q : b.sdf) buf.putShort(q);
                md.update(buf.array(), 0, buf.position());
            }
            StringBuilder sb = new StringBuilder();
            for (byte v : md.digest()) sb.append(String.format("%02x", v & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    AvsSnapshot snapshot() {
        return AvsSnapshot.capture(this);
    }

    void restore(AvsSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot");
        brickLookup.clear();
        brickCount = 0;
        minBx = minBy = minBz = Integer.MAX_VALUE;
        maxBx = maxBy = maxBz = Integer.MIN_VALUE;
        ensureBrickArray(snapshot.brickCount);
        ensureStampCapacity(snapshot.brickCount);

        for (int i = 0; i < snapshot.brickCount; i++) {
            Brick b = ensureBrick(snapshot.bx[i], snapshot.by[i], snapshot.bz[i]);
            System.arraycopy(snapshot.samples, i * SAMPLES_PER_BRICK, b.sdf, 0, SAMPLES_PER_BRICK);
            b.dirty = true;
            b.revision++;
        }
        fieldVersion++;
        allocationVersion++;
        markAllDirty();
    }

    private Brick ensureBrick(int bx, int by, int bz) {
        long key = brickKey(bx, by, bz);
        int existing = brickLookup.get(key, -1);
        if (existing >= 0) return bricks[existing];
        if (brickCount >= MAX_BRICKS) throw new IllegalStateException("AVS brick budget exceeded");
        ensureBrickArray(brickCount + 1);
        ensureStampCapacity(brickCount + 1);
        int id = brickCount++;
        Brick b = new Brick(bx, by, bz);
        bricks[id] = b;
        brickLookup.put(key, id, -1);
        minBx = Math.min(minBx, bx); minBy = Math.min(minBy, by); minBz = Math.min(minBz, bz);
        maxBx = Math.max(maxBx, bx); maxBy = Math.max(maxBy, by); maxBz = Math.max(maxBz, bz);
        allocationVersion++;
        return b;
    }

    private void markNeighborhoodDirty() {
        for (int i = 0; i < changedBrickCount; i++) {
            Brick c = bricks[changedBrickIds[i]];
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int id = brickId(c.bx + dx, c.by + dy, c.bz + dz);
                        if (id >= 0) {
                            Brick b = bricks[id];
                            b.dirty = true;
                            b.revision++;
                        }
                    }
                }
            }
        }
    }

    private void addChangedBrick(int id) {
        if (changedBrickCount == changedBrickIds.length) {
            changedBrickIds = Arrays.copyOf(changedBrickIds, changedBrickIds.length * 2);
        }
        changedBrickIds[changedBrickCount++] = id;
    }

    private int nextStamp() {
        if (stampGeneration == Integer.MAX_VALUE) {
            Arrays.fill(changedStamp, 0);
            stampGeneration = 1;
        }
        return stampGeneration++;
    }

    private void ensureBrickArray(int needed) {
        if (bricks.length >= needed) return;
        int cap = bricks.length;
        while (cap < needed) cap = cap + cap / 2 + 64;
        bricks = Arrays.copyOf(bricks, cap);
    }

    private void ensureStampCapacity(int needed) {
        if (changedStamp.length >= needed) return;
        int cap = changedStamp.length;
        while (cap < needed) cap = cap + cap / 2 + 64;
        changedStamp = Arrays.copyOf(changedStamp, cap);
    }

    private static int index(int x, int y, int z) {
        return x + BRICK_SIZE * (y + BRICK_SIZE * z);
    }

    static short encode(float worldDistance) {
        float q = worldDistance / VOXEL_SIZE * Q_PER_VOXEL;
        int qi = Math.round(q);
        if (qi == 0) qi = worldDistance < 0f ? -1 : 1;
        if (qi > 32760) qi = 32760;
        if (qi < -32760) qi = -32760;
        return (short)qi;
    }

    static float decode(short q) {
        return (q / Q_PER_VOXEL) * VOXEL_SIZE;
    }

    private static long brickKey(int bx, int by, int bz) {
        final int bias = 1 << 20;
        long x = (bx + bias) & 0x1fffffL;
        long y = (by + bias) & 0x1fffffL;
        long z = (bz + bias) & 0x1fffffL;
        return (x << 42) | (y << 21) | z;
    }

    private static float[] rayBox(float ox, float oy, float oz, float dx, float dy, float dz,
                                  float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ) {
        float tmin = -Float.MAX_VALUE;
        float tmax = Float.MAX_VALUE;
        float[] o = {ox, oy, oz};
        float[] d = {dx, dy, dz};
        float[] mn = {minX, minY, minZ};
        float[] mx = {maxX, maxY, maxZ};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-8f) {
                if (o[a] < mn[a] || o[a] > mx[a]) return null;
                continue;
            }
            float inv = 1f / d[a];
            float t0 = (mn[a] - o[a]) * inv;
            float t1 = (mx[a] - o[a]) * inv;
            if (t0 > t1) { float tmp = t0; t0 = t1; t1 = tmp; }
            tmin = Math.max(tmin, t0);
            tmax = Math.min(tmax, t1);
            if (tmax < tmin) return null;
        }
        return new float[]{tmin, tmax};
    }

    private static int floorGrid(float world) {
        return fastFloor(world / VOXEL_SIZE);
    }

    private static int ceilGrid(float world) {
        return (int)Math.ceil(world / VOXEL_SIZE);
    }

    private static int fastFloor(float v) {
        int i = (int)v;
        return v < i ? i - 1 : i;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
