package com.fallpoint.pocketsculpt;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public final class SculptCoreSelfTest {
    private static final float RADIUS = 0.327f;
    private static final float STRENGTH = 0.0263f;

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        SculptMesh mesh = SculptMesh.createIcoSphere(4, 1f);

        require(mesh.positions.length / 3 == 2562, "unexpected level-4 icosphere vertex count");
        require(mesh.indices.length / 3 == 5120, "unexpected level-4 icosphere triangle count");
        require(isClosedTwoManifold(mesh.indices), "icosphere must be a closed two-manifold");
        require(mesh.isHealthy(), "fresh mesh failed health checks");

        float initialMaxEdge = maxEdge(mesh);
        hammerOneRegion(mesh, 700, BrushMode.ADD);
        require(mesh.isHealthy(), "mesh became unhealthy during repeated add strokes");
        require(maxEdge(mesh) <= initialMaxEdge * 2.36f + 1e-4f,
                "fixed-topology edge stretch guard failed during repeated add strokes");

        hammerOneRegion(mesh, 700, BrushMode.SUBTRACT);
        require(mesh.isHealthy(), "mesh became unhealthy during repeated subtract strokes");
        require(maxEdge(mesh) <= initialMaxEdge * 2.36f + 1e-4f,
                "fixed-topology edge stretch guard failed during repeated subtract strokes");

        mixedStress(mesh, 1600);
        require(mesh.isHealthy(), "mesh became unhealthy during mixed stress test");
        require(allFinite(mesh.positions), "mesh contains non-finite coordinates after stress test");

        SculptMesh.Hit hit = mesh.raycast(
                new float[]{0f, 0f, 4f},
                new float[]{0f, 0f, -1f}
        );
        require(hit != null && Float.isFinite(hit.t) && hit.t > 0f, "raycast failed after stress test");

        System.out.printf(
                Locale.US,
                "Sculpt core self-test passed: %d vertices, %d triangles, max edge %.5f%n",
                mesh.positions.length / 3,
                mesh.indices.length / 3,
                maxEdge(mesh)
        );
    }

    private static void hammerOneRegion(SculptMesh mesh, int strokes, BrushMode mode) {
        int vertex = maxZVertex(mesh.positions);
        for (int i = 0; i < strokes; i++) {
            int base = vertex * 3;
            float cx = mesh.positions[base];
            float cy = mesh.positions[base + 1];
            float cz = mesh.positions[base + 2];
            float len = length(cx, cy, cz);
            require(len > 1e-6f, "hammer target collapsed to origin");

            float nx = cx / len;
            float ny = cy / len;
            float nz = cz / len;
            mesh.applyBrush(
                    cx, cy, cz,
                    nx, ny, nz,
                    new float[]{-nx, -ny, -nz},
                    RADIUS,
                    STRENGTH,
                    mode,
                    true
            );
            mesh.recalculateNormals();

            if ((i + 1) % 100 == 0) {
                require(mesh.isHealthy(), "mesh health failed during concentrated stress");
            }
        }
    }

    private static void mixedStress(SculptMesh mesh, int strokes) {
        Random random = new Random(0x5C01A7L);
        int vertexCount = mesh.positions.length / 3;

        for (int i = 0; i < strokes; i++) {
            int vertex = random.nextInt(vertexCount);
            int base = vertex * 3;
            float cx = mesh.positions[base];
            float cy = mesh.positions[base + 1];
            float cz = mesh.positions[base + 2];
            float len = length(cx, cy, cz);
            if (len < 1e-6f) continue;

            float nx = cx / len;
            float ny = cy / len;
            float nz = cz / len;
            float[] view = new float[]{-nx, -ny, -nz};

            BrushMode mode;
            int selector = i % 13;
            if (selector == 0) mode = BrushMode.SMOOTH;
            else mode = (selector & 1) == 0 ? BrushMode.ADD : BrushMode.SUBTRACT;

            mesh.applyBrush(
                    cx, cy, cz,
                    nx, ny, nz,
                    view,
                    RADIUS,
                    STRENGTH,
                    mode,
                    true
            );

            if (i % 7 == 0) {
                mesh.applyBrush(
                        -cx, cy, cz,
                        -nx, ny, nz,
                        new float[]{nx, -ny, -nz},
                        RADIUS,
                        STRENGTH * 0.75f,
                        mode,
                        false
                );
            }

            mesh.recalculateNormals();
            if ((i + 1) % 200 == 0) {
                require(mesh.isHealthy(), "mesh health failed during mixed stress");
            }
        }
    }

    private static boolean isClosedTwoManifold(int[] indices) {
        Map<Long, Integer> counts = new HashMap<>();
        for (int i = 0; i < indices.length; i += 3) {
            addEdge(counts, indices[i], indices[i + 1]);
            addEdge(counts, indices[i + 1], indices[i + 2]);
            addEdge(counts, indices[i + 2], indices[i]);
        }
        for (int count : counts.values()) {
            if (count != 2) return false;
        }
        return true;
    }

    private static void addEdge(Map<Long, Integer> counts, int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        long key = (((long) min) << 32) | (max & 0xffffffffL);
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static int maxZVertex(float[] positions) {
        int best = 0;
        for (int i = 1; i < positions.length / 3; i++) {
            if (positions[i * 3 + 2] > positions[best * 3 + 2]) best = i;
        }
        return best;
    }

    private static float maxEdge(SculptMesh mesh) {
        float max = 0f;
        for (int i = 0; i < mesh.indices.length; i += 3) {
            int a = mesh.indices[i];
            int b = mesh.indices[i + 1];
            int c = mesh.indices[i + 2];
            max = Math.max(max, edge(mesh.positions, a, b));
            max = Math.max(max, edge(mesh.positions, b, c));
            max = Math.max(max, edge(mesh.positions, c, a));
        }
        return max;
    }

    private static float edge(float[] positions, int a, int b) {
        int ia = a * 3;
        int ib = b * 3;
        return length(
                positions[ib] - positions[ia],
                positions[ib + 1] - positions[ia + 1],
                positions[ib + 2] - positions[ia + 2]
        );
    }

    private static boolean allFinite(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) return false;
        }
        return true;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
