package com.fallpoint.pocketsculpt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SculptMesh {
    final float[] positions;
    final float[] normals;
    final int[] indices;
    private final float[] originalPositions;
    private final int[][] neighbors;

    private SculptMesh(float[] positions, int[] indices) {
        this.positions = positions;
        this.originalPositions = positions.clone();
        this.indices = indices;
        this.normals = new float[positions.length];
        this.neighbors = buildNeighbors(positions.length / 3, indices);
        recalculateNormals();
    }

    static SculptMesh createUvSphere(int stacks, int slices, float radius) {
        int vertexCount = (stacks + 1) * (slices + 1);
        float[] vertices = new float[vertexCount * 3];
        int v = 0;

        for (int stack = 0; stack <= stacks; stack++) {
            float phi = (float) Math.PI * stack / stacks;
            float y = (float) Math.cos(phi) * radius;
            float ring = (float) Math.sin(phi) * radius;
            for (int slice = 0; slice <= slices; slice++) {
                float theta = (float) (Math.PI * 2.0) * slice / slices;
                vertices[v++] = ring * (float) Math.sin(theta);
                vertices[v++] = y;
                vertices[v++] = ring * (float) Math.cos(theta);
            }
        }

        int[] triangles = new int[stacks * slices * 6];
        int t = 0;
        int row = slices + 1;
        for (int stack = 0; stack < stacks; stack++) {
            for (int slice = 0; slice < slices; slice++) {
                int a = stack * row + slice;
                int b = a + row;
                int c = a + 1;
                int d = b + 1;

                triangles[t++] = a;
                triangles[t++] = b;
                triangles[t++] = c;
                triangles[t++] = c;
                triangles[t++] = b;
                triangles[t++] = d;
            }
        }
        return new SculptMesh(vertices, triangles);
    }

    void applyBrush(float cx, float cy, float cz, float radius, float strength, BrushMode mode) {
        float radiusSq = radius * radius;
        int count = positions.length / 3;
        float[] nextPositions = mode == BrushMode.SMOOTH ? positions.clone() : null;

        for (int i = 0; i < count; i++) {
            int base = i * 3;
            float dx = positions[base] - cx;
            float dy = positions[base + 1] - cy;
            float dz = positions[base + 2] - cz;
            float distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > radiusSq) continue;

            float distance = (float) Math.sqrt(distanceSq);
            float u = 1f - distance / radius;
            float falloff = u * u * (3f - 2f * u); // smoothstep-shaped brush

            if (mode == BrushMode.SMOOTH) {
                int[] adjacent = neighbors[i];
                if (adjacent.length == 0) continue;
                float ax = 0f, ay = 0f, az = 0f;
                for (int neighbor : adjacent) {
                    int nb = neighbor * 3;
                    ax += positions[nb];
                    ay += positions[nb + 1];
                    az += positions[nb + 2];
                }
                float inv = 1f / adjacent.length;
                ax *= inv;
                ay *= inv;
                az *= inv;
                float blend = Math.min(0.72f, strength * 10f) * falloff;
                nextPositions[base] = lerp(positions[base], ax, blend);
                nextPositions[base + 1] = lerp(positions[base + 1], ay, blend);
                nextPositions[base + 2] = lerp(positions[base + 2], az, blend);
            } else {
                float direction = mode == BrushMode.ADD ? 1f : -1f;
                float amount = strength * falloff * direction;
                positions[base] += normals[base] * amount;
                positions[base + 1] += normals[base + 1] * amount;
                positions[base + 2] += normals[base + 2] * amount;
            }
        }

        if (nextPositions != null) {
            System.arraycopy(nextPositions, 0, positions, 0, positions.length);
        }
    }

    float[] raycast(float[] origin, float[] direction) {
        float closestT = Float.POSITIVE_INFINITY;
        float hitX = 0f, hitY = 0f, hitZ = 0f;
        boolean found = false;

        for (int i = 0; i < indices.length; i += 3) {
            int i0 = indices[i] * 3;
            int i1 = indices[i + 1] * 3;
            int i2 = indices[i + 2] * 3;
            float t = intersectTriangle(
                    origin, direction,
                    positions[i0], positions[i0 + 1], positions[i0 + 2],
                    positions[i1], positions[i1 + 1], positions[i1 + 2],
                    positions[i2], positions[i2 + 1], positions[i2 + 2]
            );
            if (t > 0f && t < closestT) {
                closestT = t;
                hitX = origin[0] + direction[0] * t;
                hitY = origin[1] + direction[1] * t;
                hitZ = origin[2] + direction[2] * t;
                found = true;
            }
        }
        return found ? new float[]{hitX, hitY, hitZ} : null;
    }

    void recalculateNormals() {
        for (int i = 0; i < normals.length; i++) normals[i] = 0f;
        for (int i = 0; i < indices.length; i += 3) {
            int ia = indices[i] * 3;
            int ib = indices[i + 1] * 3;
            int ic = indices[i + 2] * 3;

            float abx = positions[ib] - positions[ia];
            float aby = positions[ib + 1] - positions[ia + 1];
            float abz = positions[ib + 2] - positions[ia + 2];
            float acx = positions[ic] - positions[ia];
            float acy = positions[ic + 1] - positions[ia + 1];
            float acz = positions[ic + 2] - positions[ia + 2];

            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;

            normals[ia] += nx; normals[ia + 1] += ny; normals[ia + 2] += nz;
            normals[ib] += nx; normals[ib + 1] += ny; normals[ib + 2] += nz;
            normals[ic] += nx; normals[ic + 1] += ny; normals[ic + 2] += nz;
        }

        for (int i = 0; i < normals.length; i += 3) {
            float x = normals[i];
            float y = normals[i + 1];
            float z = normals[i + 2];
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len > 1e-8f) {
                normals[i] = x / len;
                normals[i + 1] = y / len;
                normals[i + 2] = z / len;
            }
        }
    }

    float[] copyPositions() {
        return positions.clone();
    }

    void setPositions(float[] snapshot) {
        if (snapshot.length != positions.length) return;
        System.arraycopy(snapshot, 0, positions, 0, positions.length);
    }

    void reset() {
        System.arraycopy(originalPositions, 0, positions, 0, positions.length);
    }

    private static float intersectTriangle(
            float[] origin, float[] direction,
            float v0x, float v0y, float v0z,
            float v1x, float v1y, float v1z,
            float v2x, float v2y, float v2z
    ) {
        final float epsilon = 1e-7f;
        float e1x = v1x - v0x;
        float e1y = v1y - v0y;
        float e1z = v1z - v0z;
        float e2x = v2x - v0x;
        float e2y = v2y - v0y;
        float e2z = v2z - v0z;

        float px = direction[1] * e2z - direction[2] * e2y;
        float py = direction[2] * e2x - direction[0] * e2z;
        float pz = direction[0] * e2y - direction[1] * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < epsilon) return -1f;

        float invDet = 1f / det;
        float tx = origin[0] - v0x;
        float ty = origin[1] - v0y;
        float tz = origin[2] - v0z;
        float u = (tx * px + ty * py + tz * pz) * invDet;
        if (u < 0f || u > 1f) return -1f;

        float qx = ty * e1z - tz * e1y;
        float qy = tz * e1x - tx * e1z;
        float qz = tx * e1y - ty * e1x;
        float v = (direction[0] * qx + direction[1] * qy + direction[2] * qz) * invDet;
        if (v < 0f || u + v > 1f) return -1f;

        float t = (e2x * qx + e2y * qy + e2z * qz) * invDet;
        return t > epsilon ? t : -1f;
    }

    private static int[][] buildNeighbors(int vertexCount, int[] indices) {
        List<Set<Integer>> sets = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) sets.add(new HashSet<>());
        for (int i = 0; i < indices.length; i += 3) {
            int a = indices[i];
            int b = indices[i + 1];
            int c = indices[i + 2];
            sets.get(a).add(b); sets.get(a).add(c);
            sets.get(b).add(a); sets.get(b).add(c);
            sets.get(c).add(a); sets.get(c).add(b);
        }
        int[][] result = new int[vertexCount][];
        for (int i = 0; i < vertexCount; i++) {
            Set<Integer> set = sets.get(i);
            int[] values = new int[set.size()];
            int k = 0;
            for (int value : set) values[k++] = value;
            result[i] = values;
        }
        return result;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
