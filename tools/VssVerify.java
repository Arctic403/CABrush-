package com.fallpoint.pocketsculpt;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Verification Snapshot System v3.
 *
 * This is intentionally not a "did Java compile?" test. It fuzzes topology,
 * proves BVH results against brute force, verifies rollback/snapshots/render
 * chunking, records performance/memory, runs minimal sculpt consumers, and
 * emits visual evidence plus a machine-readable dump before Android builds.
 */
public final class VssVerify {
    private static final int IMAGE = 640;
    private static final long BASE_SEED = 0xCA_B3_2026_0907L;

    private static final List<String> failures = new ArrayList<>();
    private static final List<String> screenshots = new ArrayList<>();
    private static final List<Scenario> scenarios = new ArrayList<>();
    private static final List<ScaleResult> scales = new ArrayList<>();
    private static final Perf perf = new Perf();

    private static final class Scenario {
        String name;
        boolean pass = true;
        long durationMs;
        int attempted, accepted, rejected;
        String detail = "";
        String hash = "";
    }

    private static final class ScaleResult {
        int subdivision;
        int vertices, faces, halfEdges, bvhNodes, chunks;
        long coreBytes, bvhBytes, renderBytes;
        double rayP50Us, rayP95Us, rayP99Us;
    }

    private static final class Perf {
        double bvhRayP50Us, bvhRayP95Us, bvhRayP99Us;
        double bruteRayP50Us, bruteRayP95Us, bruteRayP99Us;
        double topologyP50Us, topologyP95Us, topologyP99Us;
        double snapshotP50Us, snapshotP95Us, snapshotP99Us;
    }

    private static final class Tri {
        final int a, b, c;
        final float depth, shade;
        Tri(int a, int b, int c, float depth, float shade) {
            this.a = a; this.b = b; this.c = c; this.depth = depth; this.shade = shade;
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        System.setProperty("java.awt.headless", "true");
        File out = new File(args.length > 0 ? args[0] : "build/vss/report");
        if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);

        long started = System.currentTimeMillis();
        List<BufferedImage> contactImages = new ArrayList<>();
        List<String> contactLabels = new ArrayList<>();

        try {
            baseline(out, contactImages, contactLabels);
            topologyPrimitives(out, contactImages, contactLabels);
            topologyFuzz(out, contactImages, contactLabels);
            selfIntersectionGuard();
            transactionRollback();
            snapshotRoundTrip();
            bvhVerification();
            renderChunkVerification();
            deterministicReplay();
            minimalClay(out, contactImages, contactLabels);
            scaleAndMemory();
            benchmarkCore();
        } catch (Throwable t) {
            fail("uncaught: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            t.printStackTrace(System.err);
        }

        try {
            BufferedImage sheet = contactSheet(contactImages, contactLabels);
            File f = new File(out, "99-contact-sheet.png");
            ImageIO.write(sheet, "png", f);
            screenshots.add(f.getName());
        } catch (Throwable t) {
            fail("contact sheet: " + t.getMessage());
        }

        long duration = System.currentTimeMillis() - started;
        writeJson(out, duration);
        writeText(out, duration);

        boolean pass = failures.isEmpty();
        System.out.println("CABrush Core 0.3.1 VSS: " + (pass ? "PASS" : "FAIL"));
        System.out.println("Scenarios: " + scenarios.size() + "  failures=" + failures.size());
        System.out.println("Evidence: " + out.getAbsolutePath());
        if (!pass) {
            for (String failure : failures) System.err.println("FAIL: " + failure);
            System.exit(2);
        }
    }

    private static void baseline(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("baseline-kernel");
        long start = System.currentTimeMillis();
        MeshKernel mesh = MeshKernel.createIcoSphere(4, 1f);
        check(mesh.liveVertexCount == 2562, s, "expected 2562 vertices");
        check(mesh.liveFaceCount == 5120, s, "expected 5120 faces");
        check(mesh.halfEdgeCount == 15360, s, "expected 15360 half-edges");
        GeometryValidator.Report report = GeometryValidator.validate(mesh, true);
        check(report.ok, s, "validator: " + report);
        s.hash = MeshSnapshot.capture(mesh).sha256();
        addScreenshot(out, "00-baseline-kernel.png", mesh, "CORE 0.3 BASELINE", images, labels);
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void topologyPrimitives(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("topology-primitives");
        long start = System.currentTimeMillis();
        MeshKernel mesh = MeshKernel.createIcoSphere(2, 1f);
        MeshSnapshot original = MeshSnapshot.capture(mesh);

        mesh.ensureConnectivity();
        int h = mesh.halfEdgeCount / 7;
        int a = mesh.heOrigin[h], b = mesh.heDest[h];
        GeometryTransaction.Result split = GeometryOps.splitEdge(mesh, a, b);
        s.attempted++;
        if (split.committed) s.accepted++; else s.rejected++;
        check(split.committed, s, "split failed: " + split.detail);
        check(GeometryValidator.validate(mesh, true).ok, s, "post-split invalid");
        check(GeometryQuality.countSelfIntersections(mesh, 1) == 0, s, "split introduced self-intersection");
        addScreenshot(out, "01-topology-split.png", mesh, "EDGE SPLIT", images, labels);

        // A quality-aware flip is allowed to decline. On a near-equilateral
        // icosphere there may be no beneficial diagonal. The important
        // primitive contract is: rejection is atomic, and any accepted flip is
        // geometrically sane.
        mesh.ensureConnectivity();
        int flipAttempts = Math.min(mesh.halfEdgeCount, 128);
        boolean flipCommitted = false;
        for (int i = 0; i < flipAttempts; i++) {
            MeshSnapshot beforeFlip = MeshSnapshot.capture(mesh);
            int fh = (i * 37) % mesh.halfEdgeCount;
            GeometryTransaction.Result r = GeometryOps.flipEdge(mesh, mesh.heOrigin[fh], mesh.heDest[fh]);
            s.attempted++;
            if (r.committed) {
                s.accepted++;
                flipCommitted = true;
                check(GeometryValidator.validate(mesh, true).ok, s, "accepted flip invalid");
                break;
            } else {
                s.rejected++;
                check(beforeFlip.sha256().equals(MeshSnapshot.capture(mesh).sha256()), s,
                        "rejected flip mutated mesh");
            }
        }

        // Collapse only a short edge created by the split. This exercises the
        // link condition + placement filter instead of arbitrary simplification.
        mesh.ensureConnectivity();
        boolean collapsed = false;
        float shortest = Float.POSITIVE_INFINITY;
        int ca = MeshKernel.INVALID, cb = MeshKernel.INVALID;
        for (int i = 0; i < mesh.halfEdgeCount; i++) {
            int u = mesh.heOrigin[i], v = mesh.heDest[i];
            float len = GeometryValidator.edge(mesh, u, v);
            if (len < shortest) { shortest = len; ca = u; cb = v; }
        }
        if (ca != MeshKernel.INVALID) {
            GeometryTransaction.Result r = GeometryOps.collapseEdge(mesh, ca, cb);
            s.attempted++;
            if (r.committed) { s.accepted++; collapsed = true; } else s.rejected++;
        }
        check(collapsed, s, "safe short-edge collapse could not be committed");
        check(GeometryValidator.validate(mesh, true).ok, s, "post-collapse invalid");
        check(GeometryQuality.countSelfIntersections(mesh, 1) == 0, s, "collapse introduced self-intersection");
        addScreenshot(out, "02-topology-primitives.png", mesh,
                flipCommitted ? "SPLIT + SAFE FLIP + COLLAPSE" : "SPLIT + FLIP REJECT + COLLAPSE",
                images, labels);

        original.restoreInto(mesh);
        check(MeshSnapshot.capture(mesh).sha256().equals(original.sha256()), s, "primitive reset hash mismatch");
        s.hash = MeshSnapshot.capture(mesh).sha256();
        s.detail = "flipCommitted=" + flipCommitted;
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void topologyFuzz(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("topology-fuzz-1500");
        long start = System.currentTimeMillis();
        MeshKernel mesh = MeshKernel.createIcoSphere(2, 1f);
        GeometryQuality.Stats baseline = GeometryQuality.measure(mesh);
        Random random = new Random(BASE_SEED ^ 0x101L);
        LongList timings = new LongList(2048);

        final int targetFaces = mesh.liveFaceCount;
        for (int i = 1; i <= 1500; i++) {
            mesh.ensureConnectivity();
            if (mesh.halfEdgeCount == 0) break;

            // Exercise hostile choices, but choose edge scale the same way a
            // real isotropic-remesh policy does: long edges are split, short
            // edges are collapsed, and flips are only accepted if quality is
            // not degraded.
            int h;
            int choice;
            if (mesh.liveFaceCount > targetFaces * 1.18f) {
                h = sampledExtremeEdge(mesh, random, false);
                choice = 1; // collapse shortest sampled edge
            } else if (mesh.liveFaceCount < targetFaces * 0.82f) {
                h = sampledExtremeEdge(mesh, random, true);
                choice = 0; // split longest sampled edge
            } else {
                int roll = random.nextInt(100);
                if (roll < 38) { choice = 2; h = random.nextInt(mesh.halfEdgeCount); }
                else if (roll < 70) { choice = 0; h = sampledExtremeEdge(mesh, random, true); }
                else { choice = 1; h = sampledExtremeEdge(mesh, random, false); }
            }

            int a = mesh.heOrigin[h], b = mesh.heDest[h];
            long t0 = System.nanoTime();
            GeometryTransaction.Result r;
            if (choice == 0) r = GeometryOps.splitEdge(mesh, a, b);
            else if (choice == 1) r = GeometryOps.collapseEdge(mesh, a, b);
            else r = GeometryOps.flipEdge(mesh, a, b);
            timings.add(System.nanoTime() - t0);

            s.attempted++;
            if (r.committed) s.accepted++; else s.rejected++;

            if ((i % 50) == 0) {
                GeometryValidator.Report report = GeometryValidator.validate(mesh, true);
                check(report.ok, s, "fuzz invalid at " + i + ": " + report);
                check(GeometryQuality.countSelfIntersections(mesh, 1) == 0, s,
                        "self-intersection at " + i);
                checkShapeEnvelope(mesh, baseline, s, "fuzz@" + i, 0.10, 0.10, 2.05f, 0.78f, 1.16f);
                if (!s.pass) break;
            }
            if (i == 250 || i == 750 || i == 1500) {
                addScreenshot(out, String.format(Locale.US, "03-topology-fuzz-%04d.png", i),
                        mesh, "TOPOLOGY FUZZ " + i, images, labels);
            }
        }

        check(s.accepted >= 180, s, "too few safe topology mutations accepted: " + s.accepted);
        check(GeometryValidator.validate(mesh, true).ok, s, "final fuzz mesh invalid");
        check(GeometryQuality.countSelfIntersections(mesh, 1) == 0, s, "final fuzz self-intersection");
        checkShapeEnvelope(mesh, baseline, s, "fuzz-final", 0.10, 0.10, 2.05f, 0.78f, 1.16f);

        GeometryQuality.Stats endStats = GeometryQuality.measure(mesh);
        s.hash = MeshSnapshot.capture(mesh).sha256();
        s.detail = String.format(Locale.US,
                "V=%d F=%d areaDrift=%.4f volumeDrift=%.4f qMin=%.3f edgeMax=%.4f radial=%.4f..%.4f",
                mesh.liveVertexCount, mesh.liveFaceCount,
                relativeDrift(endStats.area, baseline.area),
                relativeDrift(endStats.signedVolume, baseline.signedVolume),
                endStats.minQuality, endStats.maxEdge, endStats.minRadius, endStats.maxRadius);
        double[] q = timings.quantilesMicros();
        perf.topologyP50Us = q[0]; perf.topologyP95Us = q[1]; perf.topologyP99Us = q[2];
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void selfIntersectionGuard() {
        Scenario s = scenario("self-intersection-detector");
        long start = System.currentTimeMillis();
        MeshKernel mesh = MeshKernel.createIcoSphere(2, 1f);
        int v = mesh.activeVertexIds()[0];
        float x = mesh.x(v), y = mesh.y(v), z = mesh.z(v);
        mesh.setVertexPosition(v, -x * 1.5f, -y * 1.5f, -z * 1.5f);
        mesh.recomputeNormalsAll();
        int intersections = GeometryQuality.countSelfIntersections(mesh, 10);
        check(intersections > 0, s, "known folded mesh was not detected");
        s.detail = "detectedPairs>=" + intersections;
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void transactionRollback() {
        Scenario s = scenario("transaction-rollback");
        long start = System.currentTimeMillis();
        MeshKernel mesh = MeshKernel.createIcoSphere(3, 1f);
        MeshSnapshot before = MeshSnapshot.capture(mesh);
        int vertex = mesh.activeVertexIds()[17];
        GeometryTransaction tx = new GeometryTransaction(mesh);
        int[] ids = {vertex};
        tx.beginVertexEdit(ids, 1);
        int b = vertex * 3;
        mesh.positions[b] = Float.NaN;
        GeometryTransaction.Result result = tx.commitVertexEdit();
        check(!result.committed, s, "invalid transaction unexpectedly committed");
        check(before.sha256().equals(MeshSnapshot.capture(mesh).sha256()), s, "rollback was not bit-exact");
        check(GeometryValidator.validate(mesh, true).ok, s, "rollback mesh invalid");
        s.hash = MeshSnapshot.capture(mesh).sha256();
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void snapshotRoundTrip() {
        Scenario s = scenario("snapshot-roundtrip");
        long start = System.currentTimeMillis();
        MeshKernel mesh = MeshKernel.createIcoSphere(3, 1f);
        MeshSnapshot snapshot = MeshSnapshot.capture(mesh);
        String expected = snapshot.sha256();

        mesh.ensureConnectivity();
        int h = mesh.halfEdgeCount / 3;
        GeometryOps.splitEdge(mesh, mesh.heOrigin[h], mesh.heDest[h]);
        snapshot.restoreInto(mesh);

        check(expected.equals(MeshSnapshot.capture(mesh).sha256()), s, "snapshot hash mismatch");
        check(GeometryValidator.validate(mesh, true).ok, s, "restored mesh invalid");

        LongList times = new LongList(64);
        for (int i = 0; i < 50; i++) {
            long t0 = System.nanoTime();
            MeshSnapshot.capture(mesh).toBytes();
            times.add(System.nanoTime() - t0);
        }
        double[] q = times.quantilesMicros();
        perf.snapshotP50Us = q[0]; perf.snapshotP95Us = q[1]; perf.snapshotP99Us = q[2];
        s.hash = expected;
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void bvhVerification() {
        Scenario s = scenario("bvh-vs-bruteforce");
        long start = System.currentTimeMillis();
        MeshKernel mesh = MeshKernel.createIcoSphere(4, 1f);
        SculptBvh bvh = new SculptBvh(mesh);
        Random random = new Random(BASE_SEED ^ 0x202L);
        SculptBvh.RayHit fast = new SculptBvh.RayHit();
        SculptBvh.RayHit brute = new SculptBvh.RayHit();
        LongList fastTimes = new LongList(1000);
        LongList bruteTimes = new LongList(1000);

        for (int i = 0; i < 750; i++) {
            float[] d = randomDirection(random);
            float ox = d[0] * 3.2f, oy = d[1] * 3.2f, oz = d[2] * 3.2f;
            float dx = -d[0], dy = -d[1], dz = -d[2];

            long t0 = System.nanoTime();
            bvh.raycast(ox, oy, oz, dx, dy, dz, fast);
            fastTimes.add(System.nanoTime() - t0);
            t0 = System.nanoTime();
            SculptBvh.bruteRaycast(mesh, ox, oy, oz, dx, dy, dz, brute);
            bruteTimes.add(System.nanoTime() - t0);

            check(fast.faceId != MeshKernel.INVALID && brute.faceId != MeshKernel.INVALID, s, "ray miss " + i);
            check(Math.abs(fast.t - brute.t) < 2e-5f, s, "ray t mismatch " + i + ": " + fast.t + " vs " + brute.t);
            float dp = distSq(fast.x, fast.y, fast.z, brute.x, brute.y, brute.z);
            check(dp < 1e-8f, s, "ray point mismatch " + i);
            if (!s.pass) break;
        }

        // Refit correctness after a real geometry edit.
        int v = mesh.activeVertexIds()[101];
        GeometryTransaction tx = new GeometryTransaction(mesh);
        int[] one = {v};
        tx.beginVertexEdit(one, 1);
        int vb = v * 3;
        mesh.positions[vb] *= 1.03f; mesh.positions[vb + 1] *= 1.03f; mesh.positions[vb + 2] *= 1.03f;
        check(tx.commitVertexEdit().committed, s, "test deformation rejected");
        bvh.refit();

        for (int i = 0; i < 100; i++) {
            float[] d = randomDirection(random);
            bvh.raycast(d[0] * 3f, d[1] * 3f, d[2] * 3f, -d[0], -d[1], -d[2], fast);
            SculptBvh.bruteRaycast(mesh, d[0] * 3f, d[1] * 3f, d[2] * 3f, -d[0], -d[1], -d[2], brute);
            check(Math.abs(fast.t - brute.t) < 2e-5f, s, "post-refit mismatch " + i);
        }

        double[] fq = fastTimes.quantilesMicros();
        double[] bq = bruteTimes.quantilesMicros();
        perf.bvhRayP50Us = fq[0]; perf.bvhRayP95Us = fq[1]; perf.bvhRayP99Us = fq[2];
        perf.bruteRayP50Us = bq[0]; perf.bruteRayP95Us = bq[1]; perf.bruteRayP99Us = bq[2];
        check(bvh.nodeCount() > 1 && bvh.leafCount() > 1, s, "BVH did not partition");
        s.hash = MeshSnapshot.capture(mesh).sha256();
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void renderChunkVerification() {
        Scenario s = scenario("render-chunk-16bit");
        long start = System.currentTimeMillis();
        MeshKernel mesh = MeshKernel.createIcoSphere(7, 1f);
        RenderChunkBuilder builder = new RenderChunkBuilder(mesh);
        RenderChunkBuilder.RenderPlan plan = builder.currentPlan();

        check(plan.totalTriangles() == mesh.liveFaceCount, s, "render triangle count mismatch");
        check(plan.chunks.length >= 3, s, "large mesh was not chunked");
        for (RenderChunkBuilder.Chunk chunk : plan.chunks) {
            check(chunk.stableVertexIds.length <= RenderChunkBuilder.MAX_CHUNK_VERTICES, s, "chunk vertex overflow");
            for (short raw : chunk.indices) {
                int idx = raw & 0xffff;
                check(idx < chunk.stableVertexIds.length, s, "16-bit index out of range");
                if (!s.pass) break;
            }
        }
        s.hash = MeshSnapshot.capture(mesh).sha256();
        s.detail = "chunks=" + plan.chunks.length + " renderBytes=" + plan.estimatedBytes();
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void deterministicReplay() {
        Scenario s = scenario("deterministic-topology-replay");
        long start = System.currentTimeMillis();
        String h1 = replayTopology(BASE_SEED ^ 0x303L, 450);
        String h2 = replayTopology(BASE_SEED ^ 0x303L, 450);
        check(h1.equals(h2), s, "same seed produced different hashes");
        s.hash = h1;
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static String replayTopology(long seed, int operations) {
        MeshKernel mesh = MeshKernel.createIcoSphere(2, 1f);
        Random random = new Random(seed);
        for (int i = 0; i < operations; i++) {
            mesh.ensureConnectivity();
            int h = random.nextInt(mesh.halfEdgeCount);
            int a = mesh.heOrigin[h], b = mesh.heDest[h];
            int op = random.nextInt(3);
            if (mesh.liveFaceCount > 550) op = 1;
            if (mesh.liveFaceCount < 260) op = 0;
            if (op == 0) GeometryOps.splitEdge(mesh, a, b);
            else if (op == 1) GeometryOps.collapseEdge(mesh, a, b);
            else GeometryOps.flipEdge(mesh, a, b);
        }
        return MeshSnapshot.capture(mesh).sha256();
    }

    private static void minimalClay(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("minimal-clay-consumer");
        long start = System.currentTimeMillis();
        SculptMesh sculpt = SculptMesh.createSphere();
        Random random = new Random(BASE_SEED ^ 0x404L);
        GeometryQuality.Stats baseline = GeometryQuality.measure(sculpt.kernel);

        addScreenshot(out, "10-clay-start.png", sculpt.kernel, "CLAY START", images, labels);

        int addAccepted = 0;
        int addRejected = 0;
        float[] checkpointRadius = new float[3];
        int checkpointIndex = 0;
        for (int i = 1; i <= 700; i++) {
            float jitterX = (random.nextFloat() - 0.5f) * 0.22f;
            float jitterY = (random.nextFloat() - 0.5f) * 0.22f;
            float[] d = normalize(jitterX, jitterY, 1f);
            SculptBvh.RayHit hit = sculpt.bvh.raycast(d[0] * 3.2f, d[1] * 3.2f, d[2] * 3.2f,
                    -d[0], -d[1], -d[2], new SculptBvh.RayHit());
            if (hit.faceId == MeshKernel.INVALID) { addRejected++; continue; }
            boolean changed = sculpt.applyClay(
                    hit.x, hit.y, hit.z, hit.nx, hit.ny, hit.nz,
                    new float[]{-d[0], -d[1], -d[2]},
                    0.28f, 0.014f, BrushMode.ADD, i * 0.02f, hit.faceId
            );
            s.attempted++;
            if (changed) { s.accepted++; addAccepted++; }
            else { s.rejected++; addRejected++; }

            if (i == 175 || i == 350 || i == 700) {
                GeometryQuality.Stats stats = GeometryQuality.measure(sculpt.kernel);
                checkpointRadius[checkpointIndex++] = stats.maxRadius;
                addScreenshot(out, String.format(Locale.US, "11-clay-add-%03d.png", i),
                        sculpt.kernel, "CLAY + " + i, images, labels);
                check(stats.minQuality >= 0.15f, s,
                        "Clay+ conditioning too poor at " + i + ": q=" + stats.minQuality);
                check(stats.maxEdge <= baseline.maxEdge * 7.0f, s,
                        "Clay+ created runaway edge at " + i + ": " + stats.maxEdge);
                check(stats.maxRadius <= baseline.maxRadius + 0.75f, s,
                        "Clay+ radial spike at " + i + ": " + stats.maxRadius);
                check(countVerticesBeyondRadius(sculpt.kernel, baseline.maxRadius + 0.08f) >= 20, s,
                        "Clay+ buildup is too needle-like at " + i);
            }
        }

        // Core 0.3.1 deliberately still has fixed topology. A long stationary
        // Clay stroke is allowed to saturate safely; what must not happen is
        // the old shard/toothpick failure. Require the brush to work initially
        // and require all later rejection to leave geometry healthy.
        check(addAccepted >= 40, s, "Clay+ did not produce a usable initial buildup: accepted=" + addAccepted);
        check(checkpointRadius[0] > baseline.maxRadius + 0.02f, s,
                "Clay+ produced no measurable volume");
        check(checkpointRadius[1] + 1e-4f >= checkpointRadius[0], s,
                "Clay+ checkpoint regressed unexpectedly");

        int subAccepted = 0;
        int subRejected = 0;
        float beforeSubtractRadius = GeometryQuality.measure(sculpt.kernel).maxRadius;
        for (int i = 1; i <= 350; i++) {
            float[] d = normalize(
                    (random.nextFloat() - 0.5f) * 0.20f,
                    (random.nextFloat() - 0.5f) * 0.20f,
                    1f
            );
            SculptBvh.RayHit hit = sculpt.bvh.raycast(d[0] * 3.2f, d[1] * 3.2f, d[2] * 3.2f,
                    -d[0], -d[1], -d[2], new SculptBvh.RayHit());
            if (hit.faceId == MeshKernel.INVALID) { subRejected++; continue; }
            boolean changed = sculpt.applyClay(
                    hit.x, hit.y, hit.z, hit.nx, hit.ny, hit.nz,
                    new float[]{-d[0], -d[1], -d[2]},
                    0.26f, 0.012f, BrushMode.SUBTRACT, i * 0.02f, hit.faceId
            );
            s.attempted++;
            if (changed) { s.accepted++; subAccepted++; }
            else { s.rejected++; subRejected++; }
        }
        addScreenshot(out, "12-clay-subtract.png", sculpt.kernel, "CLAY - AFTER ADD", images, labels);

        GeometryValidator.Report report = sculpt.validate();
        check(report.ok, s, "Clay consumer invalid: " + report);
        GeometryQuality.Stats finalStats = GeometryQuality.measure(sculpt.kernel);
        check(subAccepted >= 20, s, "Clay- did not produce measurable carving: accepted=" + subAccepted);
        check(finalStats.maxRadius <= beforeSubtractRadius + 0.03f, s,
                "Clay- unexpectedly increased radial spike");

        StringBuilder reasons = new StringBuilder();
        for (GeometryValidator.Reason reason : GeometryValidator.Reason.values()) {
            long count = sculpt.sculptEngine.rejectionCount(reason);
            if (count == 0) continue;
            if (reasons.length() > 0) reasons.append(',');
            reasons.append(reason.name()).append('=').append(count);
        }

        s.hash = sculpt.snapshot().sha256();
        s.detail = String.format(Locale.US,
                "addAccepted=%d addRejected=%d subAccepted=%d subRejected=%d "
                        + "radialStart=%.4f radial175=%.4f radial350=%.4f radial700=%.4f "
                        + "qMin=%.3f rejectsByReason=[%s] fixedTopologySaturationExpected=true",
                addAccepted, addRejected, subAccepted, subRejected,
                baseline.maxRadius, checkpointRadius[0], checkpointRadius[1], checkpointRadius[2],
                finalStats.minQuality, reasons.toString());
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void scaleAndMemory() {
        Scenario s = scenario("scale-memory");
        long start = System.currentTimeMillis();
        int[] subdivisions = {4, 5, 6, 7};
        for (int sub : subdivisions) {
            MeshKernel mesh = MeshKernel.createIcoSphere(sub, 1f);
            SculptBvh bvh = new SculptBvh(mesh);
            RenderChunkBuilder.RenderPlan plan = new RenderChunkBuilder(mesh).currentPlan();

            ScaleResult r = new ScaleResult();
            r.subdivision = sub;
            r.vertices = mesh.liveVertexCount;
            r.faces = mesh.liveFaceCount;
            r.halfEdges = mesh.halfEdgeCount;
            r.bvhNodes = bvh.nodeCount();
            r.chunks = plan.chunks.length;
            r.coreBytes = mesh.estimatedCoreBytes();
            r.bvhBytes = bvh.estimatedBytes();
            r.renderBytes = plan.estimatedBytes();

            LongList ray = new LongList(128);
            Random random = new Random(BASE_SEED + sub);
            SculptBvh.RayHit hit = new SculptBvh.RayHit();
            int rays = sub == 7 ? 64 : 128;
            for (int i = 0; i < rays; i++) {
                float[] d = randomDirection(random);
                long t0 = System.nanoTime();
                bvh.raycast(d[0] * 3f, d[1] * 3f, d[2] * 3f, -d[0], -d[1], -d[2], hit);
                ray.add(System.nanoTime() - t0);
            }
            double[] q = ray.quantilesMicros();
            r.rayP50Us = q[0]; r.rayP95Us = q[1]; r.rayP99Us = q[2];
            scales.add(r);

            check(r.coreBytes + r.bvhBytes + r.renderBytes < 220L * 1024L * 1024L,
                    s, "scale " + sub + " exceeds 220MiB staging budget");
        }
        ScaleResult high = scales.get(scales.size() - 1);
        check(high.vertices >= 160_000, s, "high-density scale tier missing");
        check(high.chunks >= 3, s, "high-density chunking missing");

        // Explicit 250k planning projection: measured per-live-element footprint
        // is reported in dump; hard runtime caps remain 500k V / 1M F.
        s.detail = "highestMeasuredV=" + high.vertices + " highestMeasuredF=" + high.faces;
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static void benchmarkCore() {
        Scenario s = scenario("performance-sanity");
        long start = System.currentTimeMillis();
        check(perf.bvhRayP95Us > 0, s, "BVH benchmark missing");
        check(perf.bruteRayP95Us > 0, s, "brute benchmark missing");
        // Do not gate on a particular CI machine speed; gate on acceleration
        // direction. The BVH should be faster at the 5k-triangle baseline.
        check(perf.bvhRayP95Us < perf.bruteRayP95Us, s,
                "BVH p95 not faster than brute force: " + perf.bvhRayP95Us + " vs " + perf.bruteRayP95Us);
        s.durationMs = System.currentTimeMillis() - start;
    }

    private static int countVerticesBeyondRadius(MeshKernel mesh, float radius) {
        int count = 0;
        for (int v = 0; v < mesh.vertexHighWater; v++) {
            if (!mesh.isVertexAlive(v)) continue;
            float r = MeshKernel.length(mesh.x(v), mesh.y(v), mesh.z(v));
            if (r > radius) count++;
        }
        return count;
    }

    private static int sampledExtremeEdge(MeshKernel mesh, Random random, boolean longest) {
        int best = random.nextInt(mesh.halfEdgeCount);
        float bestLen = GeometryValidator.edge(mesh, mesh.heOrigin[best], mesh.heDest[best]);
        int samples = Math.min(48, mesh.halfEdgeCount);
        for (int i = 1; i < samples; i++) {
            int h = random.nextInt(mesh.halfEdgeCount);
            float len = GeometryValidator.edge(mesh, mesh.heOrigin[h], mesh.heDest[h]);
            if ((longest && len > bestLen) || (!longest && len < bestLen)) {
                best = h; bestLen = len;
            }
        }
        return best;
    }

    private static void checkShapeEnvelope(MeshKernel mesh, GeometryQuality.Stats baseline,
            Scenario s, String label, double maxAreaDrift, double maxVolumeDrift,
            float maxEdgeFactor, float minRadiusFactor, float maxRadiusFactor) {
        GeometryQuality.Stats now = GeometryQuality.measure(mesh);
        check(relativeDrift(now.area, baseline.area) <= maxAreaDrift, s,
                label + " area drift=" + relativeDrift(now.area, baseline.area));
        check(relativeDrift(now.signedVolume, baseline.signedVolume) <= maxVolumeDrift, s,
                label + " volume drift=" + relativeDrift(now.signedVolume, baseline.signedVolume));
        check(now.maxEdge <= baseline.maxEdge * maxEdgeFactor, s,
                label + " maxEdge=" + now.maxEdge + " baseline=" + baseline.maxEdge);
        check(now.minRadius >= baseline.minRadius * minRadiusFactor, s,
                label + " minRadius=" + now.minRadius);
        check(now.maxRadius <= baseline.maxRadius * maxRadiusFactor, s,
                label + " maxRadius=" + now.maxRadius);
        check(now.minQuality >= GeometryValidator.MIN_TRIANGLE_QUALITY - 1e-5f, s,
                label + " qMin=" + now.minQuality);
    }

    private static double relativeDrift(double value, double baseline) {
        return Math.abs(value - baseline) / Math.max(1e-12, Math.abs(baseline));
    }

    private static Scenario scenario(String name) {
        Scenario s = new Scenario();
        s.name = name;
        scenarios.add(s);
        return s;
    }

    private static void check(boolean condition, Scenario s, String detail) {
        if (condition) return;
        s.pass = false;
        if (!s.detail.isEmpty()) s.detail += "; ";
        s.detail += detail;
        fail(s.name + ": " + detail);
    }

    private static void fail(String message) {
        failures.add(message);
    }

    private static void addScreenshot(File out, String name, MeshKernel mesh, String title,
            List<BufferedImage> images, List<String> labels) throws Exception {
        BufferedImage image = renderMesh(mesh, title);
        File file = new File(out, name);
        ImageIO.write(image, "png", file);
        screenshots.add(name);
        images.add(image);
        labels.add(title);
    }

    private static BufferedImage renderMesh(MeshKernel mesh, String title) {
        BufferedImage image = new BufferedImage(IMAGE, IMAGE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(18, 21, 25));
        g.fillRect(0, 0, IMAGE, IMAGE);

        int[] ids = mesh.activeVertexIds();
        float[] tx = new float[mesh.vertexHighWater];
        float[] ty = new float[mesh.vertexHighWater];
        float[] tz = new float[mesh.vertexHighWater];
        float yaw = (float)Math.toRadians(28), pitch = (float)Math.toRadians(-12);
        float cy = (float)Math.cos(yaw), sy = (float)Math.sin(yaw);
        float cp = (float)Math.cos(pitch), sp = (float)Math.sin(pitch);
        float maxExtent = 1f;
        for (int v : ids) {
            float x = mesh.x(v), y = mesh.y(v), z = mesh.z(v);
            float x1 = cy * x + sy * z;
            float z1 = -sy * x + cy * z;
            float y1 = cp * y - sp * z1;
            float z2 = sp * y + cp * z1;
            tx[v] = x1; ty[v] = y1; tz[v] = z2;
            maxExtent = Math.max(maxExtent, Math.max(Math.abs(x1), Math.abs(y1)));
        }

        float scale = (IMAGE * 0.39f) / maxExtent;
        float center = IMAGE * 0.5f;
        List<Tri> tris = new ArrayList<>(mesh.liveFaceCount);
        for (int f = 0; f < mesh.faceHighWater; f++) {
            if (!mesh.isFaceAlive(f)) continue;
            int a = mesh.faceA[f], b = mesh.faceB[f], c = mesh.faceC[f];
            float abx = tx[b] - tx[a], aby = ty[b] - ty[a], abz = tz[b] - tz[a];
            float acx = tx[c] - tx[a], acy = ty[c] - ty[a], acz = tz[c] - tz[a];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            if (nz <= 0f) continue;
            float nlen = MeshKernel.length(nx, ny, nz);
            if (nlen < 1e-10f) continue;
            nx /= nlen; ny /= nlen; nz /= nlen;
            float light = clamp(nx * -0.25f + ny * 0.55f + nz * 0.78f, 0f, 1f);
            tris.add(new Tri(a, b, c, (tz[a] + tz[b] + tz[c]) / 3f, 0.25f + light * 0.68f));
        }
        tris.sort(Comparator.comparingDouble(t -> t.depth));
        g.setStroke(new BasicStroke(0.5f));
        for (Tri t : tris) {
            int[] xs = {Math.round(center + tx[t.a] * scale), Math.round(center + tx[t.b] * scale), Math.round(center + tx[t.c] * scale)};
            int[] ys = {Math.round(center - ty[t.a] * scale), Math.round(center - ty[t.b] * scale), Math.round(center - ty[t.c] * scale)};
            int gray = Math.max(0, Math.min(255, Math.round(t.shade * 255f)));
            Polygon p = new Polygon(xs, ys, 3);
            g.setColor(new Color(gray, gray, gray));
            g.fillPolygon(p);
            if (mesh.liveFaceCount < 20_000) {
                g.setColor(new Color(35, 39, 45, 85));
                g.drawPolygon(p);
            }
        }

        GeometryValidator.Report report = GeometryValidator.validate(mesh, true);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        g.drawString(title, 18, 27);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.drawString("V=" + mesh.liveVertexCount + " F=" + mesh.liveFaceCount
                + " HE=" + mesh.halfEdgeCount + " " + (report.ok ? "VALID" : "INVALID"), 18, 46);
        g.dispose();
        return image;
    }

    private static BufferedImage contactSheet(List<BufferedImage> images, List<String> labels) {
        int cols = 3, cell = 280, labelH = 34;
        int rows = (images.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * cell, rows * (cell + labelH), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(12, 14, 17));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        for (int i = 0; i < images.size(); i++) {
            int col = i % cols, row = i / cols;
            int x = col * cell, y = row * (cell + labelH);
            g.drawImage(images.get(i), x, y, cell, cell, null);
            g.setColor(Color.WHITE);
            String label = labels.get(i);
            if (label.length() > 32) label = label.substring(0, 32);
            g.drawString(label, x + 8, y + cell + 20);
        }
        g.dispose();
        return sheet;
    }

    private static void writeJson(File out, long durationMs) throws Exception {
        try (FileWriter w = new FileWriter(new File(out, "dump.json"))) {
            w.write("{\n");
            field(w, 1, "schema", "cabrush-core-dump-v3", true);
            field(w, 1, "generated_at", Instant.now().toString(), true);
            field(w, 1, "result", failures.isEmpty() ? "PASS" : "FAIL", true);
            field(w, 1, "duration_ms", durationMs, true);
            w.write("  \"architecture\": {\n");
            field(w, 2, "kernel", "packed-stable-id-half-edge", true);
            field(w, 2, "spatial_index", "sculpt-bvh", true);
            field(w, 2, "transactions", "vertex-journal+topology-snapshot", true);
            field(w, 2, "geometry_truth", "quality+normal+area+volume+self-intersection", true);
            field(w, 2, "render_indices", "16-bit-chunked", true);
            field(w, 2, "snapshot_schema", MeshSnapshot.SCHEMA_VERSION, false);
            w.write("  },\n");
            w.write("  \"resource_budgets\": {\n");
            field(w, 2, "max_vertices", MeshKernel.MAX_VERTICES, true);
            field(w, 2, "recommended_working_vertices", 250000, true);
            field(w, 2, "max_faces", MeshKernel.MAX_FACES, true);
            field(w, 2, "max_half_edges", MeshKernel.MAX_HALF_EDGES, true);
            field(w, 2, "max_render_chunk_vertices", RenderChunkBuilder.MAX_CHUNK_VERTICES, false);
            w.write("  },\n");
            w.write("  \"performance_us\": {\n");
            writeQuantileGroup(w, "bvh_raycast", perf.bvhRayP50Us, perf.bvhRayP95Us, perf.bvhRayP99Us, true);
            writeQuantileGroup(w, "brute_raycast", perf.bruteRayP50Us, perf.bruteRayP95Us, perf.bruteRayP99Us, true);
            writeQuantileGroup(w, "topology_transaction", perf.topologyP50Us, perf.topologyP95Us, perf.topologyP99Us, true);
            writeQuantileGroup(w, "snapshot", perf.snapshotP50Us, perf.snapshotP95Us, perf.snapshotP99Us, false);
            w.write("  },\n");

            w.write("  \"scales\": [\n");
            for (int i = 0; i < scales.size(); i++) {
                ScaleResult r = scales.get(i);
                w.write("    {");
                w.write("\"subdivision\":" + r.subdivision + ",\"vertices\":" + r.vertices
                        + ",\"faces\":" + r.faces + ",\"half_edges\":" + r.halfEdges
                        + ",\"bvh_nodes\":" + r.bvhNodes + ",\"render_chunks\":" + r.chunks
                        + ",\"core_bytes\":" + r.coreBytes + ",\"bvh_bytes\":" + r.bvhBytes
                        + ",\"render_bytes\":" + r.renderBytes
                        + String.format(Locale.US, ",\"ray_p50_us\":%.3f,\"ray_p95_us\":%.3f,\"ray_p99_us\":%.3f",
                        r.rayP50Us, r.rayP95Us, r.rayP99Us));
                w.write("}" + (i + 1 < scales.size() ? "," : "") + "\n");
            }
            w.write("  ],\n");

            w.write("  \"scenarios\": [\n");
            for (int i = 0; i < scenarios.size(); i++) {
                Scenario s = scenarios.get(i);
                w.write("    {");
                w.write("\"name\":\"" + esc(s.name) + "\",\"pass\":" + s.pass
                        + ",\"duration_ms\":" + s.durationMs
                        + ",\"attempted\":" + s.attempted + ",\"accepted\":" + s.accepted
                        + ",\"rejected\":" + s.rejected
                        + ",\"hash\":\"" + esc(s.hash) + "\",\"detail\":\"" + esc(s.detail) + "\"}");
                w.write(i + 1 < scenarios.size() ? ",\n" : "\n");
            }
            w.write("  ],\n");

            w.write("  \"screenshots\": [");
            for (int i = 0; i < screenshots.size(); i++) {
                if (i > 0) w.write(",");
                w.write("\"" + esc(screenshots.get(i)) + "\"");
            }
            w.write("],\n");

            w.write("  \"failures\": [");
            for (int i = 0; i < failures.size(); i++) {
                if (i > 0) w.write(",");
                w.write("\"" + esc(failures.get(i)) + "\"");
            }
            w.write("]\n}\n");
        }
    }

    private static void writeText(File out, long durationMs) throws Exception {
        try (FileWriter w = new FileWriter(new File(out, "dump.txt"))) {
            w.write("CABrush Core 0.3.1 - Verification Snapshot System v3\n");
            w.write("RESULT: " + (failures.isEmpty() ? "PASS" : "FAIL") + "\n");
            w.write("Duration: " + durationMs + " ms\n\n");
            w.write("Architecture: packed stable-ID mesh + half-edge connectivity + SculptBVH + atomic transactions + 16-bit render chunks\n");
            w.write("Budgets: V=" + MeshKernel.MAX_VERTICES + " F=" + MeshKernel.MAX_FACES
                    + " HE=" + MeshKernel.MAX_HALF_EDGES + " chunkV=" + RenderChunkBuilder.MAX_CHUNK_VERTICES + "\n\n");
            for (Scenario s : scenarios) {
                w.write("[" + (s.pass ? "PASS" : "FAIL") + "] " + s.name
                        + "  " + s.durationMs + "ms  attempted=" + s.attempted
                        + " accepted=" + s.accepted + " rejected=" + s.rejected + "\n");
                if (!s.hash.isEmpty()) w.write("  hash=" + s.hash + "\n");
                if (!s.detail.isEmpty()) w.write("  " + s.detail + "\n");
            }
            w.write("\nPerformance (microseconds)\n");
            w.write(String.format(Locale.US, "BVH ray    p50 %.3f p95 %.3f p99 %.3f\n", perf.bvhRayP50Us, perf.bvhRayP95Us, perf.bvhRayP99Us));
            w.write(String.format(Locale.US, "Brute ray  p50 %.3f p95 %.3f p99 %.3f\n", perf.bruteRayP50Us, perf.bruteRayP95Us, perf.bruteRayP99Us));
            w.write(String.format(Locale.US, "Topology   p50 %.3f p95 %.3f p99 %.3f\n", perf.topologyP50Us, perf.topologyP95Us, perf.topologyP99Us));
            w.write(String.format(Locale.US, "Snapshot   p50 %.3f p95 %.3f p99 %.3f\n", perf.snapshotP50Us, perf.snapshotP95Us, perf.snapshotP99Us));

            w.write("\nScale results\n");
            for (ScaleResult r : scales) {
                w.write(String.format(Locale.US,
                        "sub%d V=%d F=%d HE=%d nodes=%d chunks=%d core=%.2fMiB bvh=%.2fMiB render=%.2fMiB rayP95=%.3fus\n",
                        r.subdivision, r.vertices, r.faces, r.halfEdges, r.bvhNodes, r.chunks,
                        mib(r.coreBytes), mib(r.bvhBytes), mib(r.renderBytes), r.rayP95Us));
            }

            if (!failures.isEmpty()) {
                w.write("\nFailures\n");
                for (String f : failures) w.write("- " + f + "\n");
            }
        }
    }

    private static void field(FileWriter w, int indent, String key, String value, boolean comma) throws Exception {
        spaces(w, indent);
        w.write("\"" + esc(key) + "\":\"" + esc(value) + "\"" + (comma ? "," : "") + "\n");
    }
    private static void field(FileWriter w, int indent, String key, long value, boolean comma) throws Exception {
        spaces(w, indent);
        w.write("\"" + esc(key) + "\":" + value + (comma ? "," : "") + "\n");
    }
    private static void writeQuantileGroup(FileWriter w, String name, double p50, double p95, double p99, boolean comma) throws Exception {
        w.write(String.format(Locale.US, "    \"%s\":{\"p50\":%.3f,\"p95\":%.3f,\"p99\":%.3f}%s\n",
                esc(name), p50, p95, p99, comma ? "," : ""));
    }
    private static void spaces(FileWriter w, int levels) throws Exception {
        for (int i = 0; i < levels * 2; i++) w.write(' ');
    }

    private static float[] randomDirection(Random random) {
        float z = random.nextFloat() * 2f - 1f;
        float theta = random.nextFloat() * (float)(Math.PI * 2.0);
        float r = (float)Math.sqrt(Math.max(0f, 1f - z * z));
        return new float[]{r * (float)Math.cos(theta), z, r * (float)Math.sin(theta)};
    }

    private static float[] normalize(float x, float y, float z) {
        float len = MeshKernel.length(x, y, z);
        return new float[]{x / len, y / len, z / len};
    }

    private static float distSq(float ax, float ay, float az, float bx, float by, float bz) {
        float x = ax - bx, y = ay - by, z = az - bz;
        return x * x + y * y + z * z;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double mib(long bytes) { return bytes / 1048576.0; }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static final class LongList {
        long[] data;
        int size;
        LongList(int cap) { data = new long[Math.max(16, cap)]; }
        void add(long value) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = value;
        }
        double[] quantilesMicros() {
            if (size == 0) return new double[]{0,0,0};
            long[] copy = Arrays.copyOf(data, size);
            Arrays.sort(copy);
            return new double[]{
                    copy[(int)Math.floor((size - 1) * 0.50)] / 1000.0,
                    copy[(int)Math.floor((size - 1) * 0.95)] / 1000.0,
                    copy[(int)Math.floor((size - 1) * 0.99)] / 1000.0
            };
        }
    }
}
