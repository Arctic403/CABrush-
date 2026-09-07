package com.fallpoint.pocketsculpt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public final class SculptCoreSelfTest {
    private static final float RADIUS = 0.27f;
    private static final float STRENGTH = 0.0263f;

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);

        testIcosphereGuard();
        testHumanBase();
        testClayDirections();
        testSmoothActuallySmooths();
        testGrabForProportions();
        stressCharacterCore();

        System.out.println("PocketSculpt V1.3 character sculpt self-test passed.");
    }

    private static void testIcosphereGuard() {
        SculptMesh mesh = SculptMesh.createIcoSphere(4, 1f);
        require(mesh.positions.length / 3 == 2562, "unexpected level-4 icosphere vertex count");
        require(mesh.indices.length / 3 == 5120, "unexpected level-4 icosphere triangle count");
        require(mesh.isClosedTwoManifold(), "icosphere must be a closed two-manifold");
        require(mesh.isHealthy(), "fresh icosphere failed health checks");

        float initialMaxEdge = maxEdge(mesh);
        hammerOneRegion(mesh, 350, BrushMode.ADD);
        hammerOneRegion(mesh, 350, BrushMode.SUBTRACT);
        require(mesh.isHealthy(), "icosphere guard failed concentrated clay stress");
        require(maxEdge(mesh) <= initialMaxEdge * 2.36f + 1e-4f,
                "fixed-topology stretch guard failed");
    }

    private static void testHumanBase() {
        SculptMesh mesh = SculptMesh.createHumanBase();
        int vertices = mesh.positions.length / 3;
        int triangles = mesh.indices.length / 3;

        require(vertices >= 3500 && vertices <= 6000,
                "human base vertex count escaped the mobile budget: " + vertices);
        require(triangles >= 7000 && triangles <= 12000,
                "human base triangle count escaped the mobile budget: " + triangles);
        require(mesh.isClosedTwoManifold(), "human base must be watertight/two-manifold");
        require(componentCount(mesh) == 1, "human base must be one connected sculptable surface");
        require(mesh.isHealthy(), "human base failed geometry guard");

        float[] bounds = bounds(mesh.positions);
        require(bounds[3] - bounds[0] > 1.25f, "human base is too narrow to contain arms");
        require(bounds[4] - bounds[1] > 2.65f, "human base is too short");
        require(bounds[5] - bounds[2] > 0.45f, "human base lost body depth");
    }

    private static void testClayDirections() {
        SculptMesh addMesh = SculptMesh.createHumanBase();
        SculptMesh.Hit hit = frontHit(addMesh, 0f, 0.35f);
        require(hit != null, "could not ray-pick torso for Clay+ test");
        float[] before = addMesh.copyPositions();

        boolean addChanged = addMesh.applyBrush(
                hit.x, hit.y, hit.z,
                hit.nx, hit.ny, hit.nz,
                new float[]{0f, 0f, -1f},
                RADIUS, STRENGTH,
                BrushMode.ADD, true
        );
        addMesh.recalculateNormals();
        require(addChanged, "Clay+ reported no deformation");
        require(maxProjectedDelta(before, addMesh.positions, hit.nx, hit.ny, hit.nz) > 0.001f,
                "Clay+ did not build visible volume along the surface normal");
        require(addMesh.isHealthy(), "Clay+ violated geometry health");

        SculptMesh subMesh = SculptMesh.createHumanBase();
        hit = frontHit(subMesh, 0f, 0.35f);
        require(hit != null, "could not ray-pick torso for Clay- test");
        before = subMesh.copyPositions();

        boolean subChanged = subMesh.applyBrush(
                hit.x, hit.y, hit.z,
                hit.nx, hit.ny, hit.nz,
                new float[]{0f, 0f, -1f},
                RADIUS, STRENGTH,
                BrushMode.SUBTRACT, true
        );
        subMesh.recalculateNormals();
        require(subChanged, "Clay- reported no deformation");
        require(minProjectedDelta(before, subMesh.positions, hit.nx, hit.ny, hit.nz) < -0.001f,
                "Clay- did not carve inward along the surface normal");
        require(subMesh.isHealthy(), "Clay- violated geometry health");
    }

    private static void testSmoothActuallySmooths() {
        SculptMesh mesh = SculptMesh.createHumanBase();

        // Build a deliberately localized bump.
        for (int i = 0; i < 10; i++) {
            SculptMesh.Hit hit = frontHit(mesh, 0.12f, 0.35f);
            require(hit != null, "lost torso ray hit while making smooth-test bump");
            mesh.applyBrush(
                    hit.x, hit.y, hit.z,
                    hit.nx, hit.ny, hit.nz,
                    new float[]{0f, 0f, -1f},
                    0.16f, 0.045f,
                    BrushMode.ADD, true
            );
            mesh.recalculateNormals();
        }

        float energyBefore = laplacianEnergy(mesh);
        float[] before = mesh.copyPositions();
        boolean changed = false;

        for (int i = 0; i < 5; i++) {
            SculptMesh.Hit hit = frontHit(mesh, 0.12f, 0.35f);
            require(hit != null, "lost torso ray hit during Smooth test");
            changed |= mesh.applyBrush(
                    hit.x, hit.y, hit.z,
                    hit.nx, hit.ny, hit.nz,
                    new float[]{0f, 0f, -1f},
                    0.24f, 0.055f,
                    BrushMode.SMOOTH, true
            );
            mesh.recalculateNormals();
        }

        float energyAfter = laplacianEnergy(mesh);
        require(changed, "Smooth reported no deformation");
        require(totalDisplacement(before, mesh.positions) > 0.005f,
                "Smooth movement is too small to be useful");
        require(energyAfter < energyBefore,
                String.format(Locale.US, "Smooth failed to reduce roughness: %.7f -> %.7f", energyBefore, energyAfter));
        require(mesh.isHealthy(), "Smooth violated geometry health");
    }

    private static void testGrabForProportions() {
        SculptMesh mesh = SculptMesh.createHumanBase();
        SculptMesh.Hit hit = frontHit(mesh, 0.55f, 0.20f);
        require(hit != null, "could not ray-pick shoulder/arm for Grab test");

        SculptMesh.GrabHandle handle = mesh.beginGrab(
                hit.x, hit.y, hit.z,
                hit.nx, hit.ny, hit.nz,
                new float[]{0f, 0f, -1f},
                0.30f,
                true
        );
        require(handle != null && handle.count > 0, "Grab failed to capture a brush region");

        float[] before = mesh.copyPositions();
        boolean changed = mesh.applyGrab(handle, 0.16f, 0.05f, 0f, STRENGTH);
        mesh.recalculateNormals();

        require(changed, "Grab reported no deformation");
        require(totalDisplacement(before, mesh.positions) > 0.02f,
                "Grab movement is too small for proportion editing");
        require(maxAxisDelta(before, mesh.positions, 0) > 0.01f,
                "Grab did not move geometry across the screen/world X axis");
        require(mesh.isHealthy(), "Grab violated geometry health");
    }

    private static void stressCharacterCore() {
        SculptMesh mesh = SculptMesh.createHumanBase();
        Random random = new Random(0xC0FFEE13L);

        for (int i = 0; i < 700; i++) {
            float x = -0.55f + random.nextFloat() * 1.10f;
            float y = -1.20f + random.nextFloat() * 2.45f;
            SculptMesh.Hit hit = frontHit(mesh, x, y);
            if (hit == null) continue;

            BrushMode mode;
            int selector = i % 11;
            if (selector == 0 || selector == 5) mode = BrushMode.SMOOTH;
            else mode = (selector & 1) == 0 ? BrushMode.ADD : BrushMode.SUBTRACT;

            mesh.applyBrush(
                    hit.x, hit.y, hit.z,
                    hit.nx, hit.ny, hit.nz,
                    new float[]{0f, 0f, -1f},
                    0.16f + random.nextFloat() * 0.20f,
                    0.012f + random.nextFloat() * 0.028f,
                    mode,
                    true
            );
            mesh.recalculateNormals();

            if ((i + 1) % 100 == 0) {
                require(mesh.isHealthy(), "character mesh became unhealthy during mixed brush stress");
                require(allFinite(mesh.positions), "non-finite character coordinate during stress");
            }
        }

        require(mesh.isHealthy(), "character mesh unhealthy after stress");
        SculptMesh.Hit finalHit = frontHit(mesh, 0f, 0.2f);
        require(finalHit != null && finalHit.t > 0f, "raycast failed after character stress");
    }

    private static SculptMesh.Hit frontHit(SculptMesh mesh, float x, float y) {
        return mesh.raycast(
                new float[]{x, y, 4f},
                new float[]{0f, 0f, -1f}
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
        }
    }

    private static float laplacianEnergy(SculptMesh mesh) {
        int vertexCount = mesh.positions.length / 3;
        List<List<Integer>> neighbors = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) neighbors.add(new ArrayList<>());

        for (int i = 0; i < mesh.indices.length; i += 3) {
            int a = mesh.indices[i];
            int b = mesh.indices[i + 1];
            int c = mesh.indices[i + 2];
            addNeighbor(neighbors, a, b); addNeighbor(neighbors, a, c);
            addNeighbor(neighbors, b, a); addNeighbor(neighbors, b, c);
            addNeighbor(neighbors, c, a); addNeighbor(neighbors, c, b);
        }

        double sum = 0.0;
        int count = 0;
        for (int v = 0; v < vertexCount; v++) {
            List<Integer> nbs = neighbors.get(v);
            if (nbs.isEmpty()) continue;
            float ax = 0f, ay = 0f, az = 0f;
            for (int nb : nbs) {
                int b = nb * 3;
                ax += mesh.positions[b];
                ay += mesh.positions[b + 1];
                az += mesh.positions[b + 2];
            }
            float inv = 1f / nbs.size();
            ax *= inv; ay *= inv; az *= inv;
            int b = v * 3;
            float dx = mesh.positions[b] - ax;
            float dy = mesh.positions[b + 1] - ay;
            float dz = mesh.positions[b + 2] - az;
            sum += dx * dx + dy * dy + dz * dz;
            count++;
        }
        return count == 0 ? 0f : (float) (sum / count);
    }

    private static void addNeighbor(List<List<Integer>> neighbors, int a, int b) {
        List<Integer> list = neighbors.get(a);
        if (!list.contains(b)) list.add(b);
    }

    private static float totalDisplacement(float[] before, float[] after) {
        double sum = 0.0;
        for (int i = 0; i < before.length; i += 3) {
            float dx = after[i] - before[i];
            float dy = after[i + 1] - before[i + 1];
            float dz = after[i + 2] - before[i + 2];
            sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return (float) sum;
    }

    private static float maxProjectedDelta(
            float[] before, float[] after,
            float nx, float ny, float nz
    ) {
        float best = -Float.MAX_VALUE;
        for (int i = 0; i < before.length; i += 3) {
            float d = (after[i] - before[i]) * nx
                    + (after[i + 1] - before[i + 1]) * ny
                    + (after[i + 2] - before[i + 2]) * nz;
            best = Math.max(best, d);
        }
        return best;
    }

    private static float minProjectedDelta(
            float[] before, float[] after,
            float nx, float ny, float nz
    ) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < before.length; i += 3) {
            float d = (after[i] - before[i]) * nx
                    + (after[i + 1] - before[i + 1]) * ny
                    + (after[i + 2] - before[i + 2]) * nz;
            best = Math.min(best, d);
        }
        return best;
    }

    private static float maxAxisDelta(float[] before, float[] after, int axis) {
        float best = 0f;
        for (int i = axis; i < before.length; i += 3) {
            best = Math.max(best, Math.abs(after[i] - before[i]));
        }
        return best;
    }

    private static int componentCount(SculptMesh mesh) {
        int vertexCount = mesh.positions.length / 3;
        List<List<Integer>> neighbors = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) neighbors.add(new ArrayList<>());

        for (int i = 0; i < mesh.indices.length; i += 3) {
            int a = mesh.indices[i];
            int b = mesh.indices[i + 1];
            int c = mesh.indices[i + 2];
            addNeighbor(neighbors, a, b); addNeighbor(neighbors, b, a);
            addNeighbor(neighbors, b, c); addNeighbor(neighbors, c, b);
            addNeighbor(neighbors, c, a); addNeighbor(neighbors, a, c);
        }

        boolean[] seen = new boolean[vertexCount];
        int components = 0;
        int[] queue = new int[vertexCount];
        for (int start = 0; start < vertexCount; start++) {
            if (seen[start]) continue;
            components++;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int v = queue[head++];
                for (int nb : neighbors.get(v)) {
                    if (!seen[nb]) {
                        seen[nb] = true;
                        queue[tail++] = nb;
                    }
                }
            }
        }
        return components;
    }

    private static float[] bounds(float[] positions) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += 3) {
            minX = Math.min(minX, positions[i]);
            minY = Math.min(minY, positions[i + 1]);
            minZ = Math.min(minZ, positions[i + 2]);
            maxX = Math.max(maxX, positions[i]);
            maxY = Math.max(maxY, positions[i + 1]);
            maxZ = Math.max(maxZ, positions[i + 2]);
        }
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
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
