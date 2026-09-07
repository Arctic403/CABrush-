package com.fallpoint.pocketsculpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;

/** Deterministic AVS field snapshot used for reset, VSS and future undo/crash recovery. */
final class AvsSnapshot {
    static final int SCHEMA_VERSION = 1;

    final int brickCount;
    final int[] bx, by, bz;
    final short[] samples;

    private AvsSnapshot(int brickCount, int[] bx, int[] by, int[] bz, short[] samples) {
        this.brickCount = brickCount;
        this.bx = bx; this.by = by; this.bz = bz;
        this.samples = samples;
    }

    static AvsSnapshot capture(AvsVolume volume) {
        int n = volume.brickCount();
        int[] bx = new int[n], by = new int[n], bz = new int[n];
        short[] samples = new short[n * AvsVolume.SAMPLES_PER_BRICK];
        for (int i = 0; i < n; i++) {
            AvsVolume.Brick b = volume.brick(i);
            bx[i] = b.bx; by[i] = b.by; bz[i] = b.bz;
            System.arraycopy(b.sdf, 0, samples, i * AvsVolume.SAMPLES_PER_BRICK, AvsVolume.SAMPLES_PER_BRICK);
        }
        return new AvsSnapshot(n, bx, by, bz, samples);
    }

    void restoreInto(AvsVolume volume) {
        volume.restore(this);
    }

    String sha256() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(SCHEMA_VERSION).putInt(brickCount).putInt(AvsVolume.BRICK_SIZE).putInt(AvsVolume.SAMPLES_PER_BRICK);
            md.update(b.array());
            ByteBuffer row = ByteBuffer.allocate(12 + AvsVolume.SAMPLES_PER_BRICK * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < brickCount; i++) {
                row.clear();
                row.putInt(bx[i]).putInt(by[i]).putInt(bz[i]);
                int base = i * AvsVolume.SAMPLES_PER_BRICK;
                for (int s = 0; s < AvsVolume.SAMPLES_PER_BRICK; s++) row.putShort(samples[base + s]);
                md.update(row.array(), 0, row.position());
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
