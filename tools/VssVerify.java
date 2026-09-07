package com.fallpoint.pocketsculpt;

import javax.imageio.ImageIO;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * CABrush AVS 0.1.1 Verification Snapshot System -- Surface Truth.
 *
 * The field and the disposable surface are verified separately. A green build
 * now requires the extracted surface to be closed, consistently wound across
 * chunks, deterministic, reconstructible from scratch and visually rendered
 * with the same CCW/back-face-culling rule used on Android.
 */
public final class VssVerify {
    private static final int IMAGE = 640;
    private static final long SEED = 0xCA_BA_2026_0911L;
    private static final List<String> failures = new ArrayList<>();
    private static final List<Scenario> scenarios = new ArrayList<>();
    private static final List<String> screenshots = new ArrayList<>();

    private static double rayP50Us, rayP95Us, rayP99Us;
    private static double extractP50Ms, extractP95Ms, extractP99Ms;

    private static final class Scenario {
        String name;
        boolean pass = true;
        long ms;
        int attempted, accepted, rejected;
        int bricks, chunks, vertices, triangles;
        long fieldBytes, surfaceBytes;
        String fieldHash = "";
        String surfaceHash = "";
        String detail = "";
        AvsSurfaceAudit.Report audit;
    }

    private static final class Tri {
        float[] p0, p1, p2;
        float depth, shade;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        System.setProperty("java.awt.headless", "true");
        File out = new File(args.length > 0 ? args[0] : "build/vss/report");
        if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);

        long start = System.currentTimeMillis();
        List<BufferedImage> images = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        try {
            baseline(out, images, labels);
            verifierNegativeControls();
            brickBoundaryTruth(out, images, labels);
            thinFeatureTruth(out, images, labels);
            clayGrowth(out, images, labels);
            claySubtract(out, images, labels);
            mixedStress(out, images, labels);
            incrementalEqualsFullRebuild();
            snapshotRoundTrip();
            deterministicFieldAndSurface();
            dirtyLocality();
            rebuildLoop();
            benchmark();
        } catch (Throwable t) {
            fail("uncaught: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            t.printStackTrace(System.err);
        }

        try {
            BufferedImage sheet = contactSheet(images, labels);
            File f = new File(out, "99-contact-sheet.png");
            ImageIO.write(sheet, "png", f);
            screenshots.add(f.getName());
        } catch (Throwable t) {
            fail("contact-sheet: " + t.getMessage());
        }

        long duration = System.currentTimeMillis() - start;
        writeJson(out, duration);
        writeText(out, duration);

        boolean pass = failures.isEmpty();
        System.out.println("CABrush AVS 0.1.1 Surface Truth VSS: " + (pass ? "PASS" : "FAIL"));
        System.out.println("Scenarios=" + scenarios.size() + " failures=" + failures.size());
        System.out.println("Evidence=" + out.getAbsolutePath());
        if (!pass) {
            for (String f : failures) System.err.println("FAIL: " + f);
            System.exit(2);
        }
    }

    private static void baseline(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("baseline-field-surface-truth");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        check(v.sample(0, 0, 0) < 0f, s, "center must be inside");
        check(v.sample(1.4f, 0, 0) > 0f, s, "outside point must be positive");

        AvsVolume.RayHit hit = v.raycast(0, 0, 4, 0, 0, -1, new AvsVolume.RayHit());
        check(hit.hit, s, "sphere raycast missed");
        if (hit.hit) check(Math.abs(hit.z - 1f) < AvsVolume.VOXEL_SIZE * 1.6f, s, "ray z=" + hit.z);

        AvsSurfaceCache surf = new AvsSurfaceCache(v);
        AvsSurfaceCache.RenderPlan plan = surf.currentPlan();
        auditSurface(v, plan, s, true);
        check(s.audit.signedVolume > 4.0 && s.audit.signedVolume < 4.4, s,
                "baseline signed volume unexpected=" + s.audit.signedVolume);
        fillStats(s, v, plan);
        addScreenshot(out, "00-avs-baseline.png", plan, "SURFACE TRUTH BASELINE", images, labels);
        s.ms = System.currentTimeMillis() - st;
    }

    /** Proves VSS itself rejects the two exact failure classes seen on Android. */
    private static void verifierNegativeControls() {
        Scenario s = scenario("surface-verifier-negative-controls");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        AvsSurfaceCache cache = new AvsSurfaceCache(v);
        AvsSurfaceCache.RenderPlan good = cache.currentPlan();

        AvsSurfaceCache.RenderPlan flipped = mutateFirstTriangle(good, true, false);
        AvsSurfaceAudit.Report flipAudit = AvsSurfaceAudit.inspect(v, flipped);
        check(flipAudit.sameDirectionEdges > 0 || flipAudit.sdfOrientationFailures > 0,
                s, "flipped triangle was not detected");

        AvsSurfaceCache.RenderPlan cracked = mutateFirstTriangle(good, false, true);
        AvsSurfaceAudit.Report crackAudit = AvsSurfaceAudit.inspect(v, cracked);
        check(crackAudit.boundaryEdges > 0, s, "removed triangle/crack was not detected");
        s.detail = "flipSameDir=" + flipAudit.sameDirectionEdges
                + " flipSdf=" + flipAudit.sdfOrientationFailures
                + " crackBoundary=" + crackAudit.boundaryEdges;
        s.ms = System.currentTimeMillis() - st;
    }

    private static void brickBoundaryTruth(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("brick-boundary-seam-truth");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        float b = AvsVolume.BRICK_SIZE * AvsVolume.VOXEL_SIZE;

        // Deliberately place CSG centers on/near global brick planes and corners.
        v.applySphereCsg(3f * b, 1f * b, -1f * b, 0.30f, true);
        v.applySphereCsg(2f * b, 1f * b, 0f, 0.20f, false);
        v.applySphereCsg(-3f * b, -2f * b, 1f * b, 0.27f, true);
        v.applySphereCsg(-2f * b, -1f * b, 1f * b, 0.16f, false);

        AvsSurfaceCache cache = new AvsSurfaceCache(v);
        cache.rebuildAll();
        AvsSurfaceCache.RenderPlan plan = cache.currentPlan();
        auditSurface(v, plan, s, true);
        check(s.audit.seamPositionMismatches == 0, s,
                "chunk seam positions are not bit-identical=" + s.audit.seamPositionMismatches);
        fillStats(s, v, plan);
        addScreenshot(out, "01-brick-boundary-truth.png", plan,
                "BRICK BOUNDARY / SEAM TRUTH", images, labels);
        s.ms = System.currentTimeMillis() - st;
    }

    private static void thinFeatureTruth(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("resolvable-thin-feature-truth");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        float radius = AvsVolume.VOXEL_SIZE * 2.8f; // safely above the sample-resolution floor
        for (int i = 0; i < 18; i++) {
            float x = 0.92f + i * radius * 0.70f;
            AvsVolume.BrushResult r = v.applySphereCsg(x, 0.08f, 0.03f, radius, true);
            s.attempted++;
            if (r.changed) s.accepted++; else s.rejected++;
        }
        AvsSurfaceCache cache = new AvsSurfaceCache(v);
        cache.rebuildAll();
        AvsSurfaceCache.RenderPlan plan = cache.currentPlan();
        auditSurface(v, plan, s, true);
        check(s.rejected == 0, s, "resolvable feature stamps rejected=" + s.rejected);
        fillStats(s, v, plan);
        addScreenshot(out, "02-thin-feature-truth.png", plan,
                "RESOLVABLE THIN FEATURE", images, labels);
        s.ms = System.currentTimeMillis() - st;
    }

    private static void clayGrowth(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("clay-add-long-extrusion-surface-truth");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        AvsSurfaceCache surf = new AvsSurfaceCache(v);
        AvsVolume.RayHit h = new AvsVolume.RayHit();
        float lastZ = 0f;

        for (int i = 1; i <= 240; i++) {
            float originZ = v.stats().maxZ + 1.5f;
            h = v.raycast(0, 0, originZ, 0, 0, -1, h);
            s.attempted++;
            if (!h.hit) {
                s.rejected++;
                failScenario(s, "ray miss at " + i);
                break;
            }
            AvsVolume.BrushResult r = v.applyClay(h.x, h.y, h.z, h.nx, h.ny, h.nz,
                    0.30f, 0.014f, BrushMode.ADD);
            if (r.changed) s.accepted++;
            else {
                s.rejected++;
                failScenario(s, "add rejected at " + i);
                break;
            }

            if (i % 40 == 0) {
                float checkOrigin = v.stats().maxZ + 1.5f;
                AvsVolume.RayHit checkHit = v.raycast(0, 0, checkOrigin, 0, 0, -1,
                        new AvsVolume.RayHit());
                check(checkHit.hit, s, "growth checkpoint ray miss " + i);
                if (checkHit.hit) {
                    check(checkHit.z > lastZ + AvsVolume.VOXEL_SIZE * 0.20f, s,
                            "Clay+ stopped growing at " + i + " z=" + checkHit.z + " last=" + lastZ);
                    lastZ = checkHit.z;
                }
            }

            if (i == 60 || i == 120 || i == 240) {
                AvsSurfaceCache.RenderPlan p = surf.currentPlan();
                AvsSurfaceAudit.Report a = AvsSurfaceAudit.inspect(v, p);
                checkSurfaceAudit(a, s, "Clay+ checkpoint " + i, true);
                addScreenshot(out, String.format(Locale.US, "03-clay-add-%03d.png", i), p,
                        "CLAY+ " + i + " / CULLING ON", images, labels);
            }
        }

        AvsSurfaceCache.RenderPlan plan = surf.currentPlan();
        auditSurface(v, plan, s, true);
        check(s.rejected == 0, s, "Clay+ rejected " + s.rejected + " dabs");
        check(lastZ > 2.0f, s, "Clay+ extension too small finalZ=" + lastZ);
        fillStats(s, v, plan);
        s.ms = System.currentTimeMillis() - st;
    }

    private static void claySubtract(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("clay-subtract-surface-truth");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        long before = v.stats().negativeSamples;
        AvsVolume.RayHit h = new AvsVolume.RayHit();

        for (int i = 0; i < 70; i++) {
            h = v.raycast(0, 0, 4, 0, 0, -1, h);
            s.attempted++;
            if (!h.hit) { s.rejected++; break; }
            AvsVolume.BrushResult r = v.applyClay(h.x, h.y, h.z, h.nx, h.ny, h.nz,
                    0.24f, 0.016f, BrushMode.SUBTRACT);
            if (r.changed) s.accepted++; else s.rejected++;
        }
        long after = v.stats().negativeSamples;
        check(after < before, s, "Clay- did not reduce volume: " + before + " -> " + after);
        check(s.accepted >= 30, s, "too few subtract dabs accepted=" + s.accepted);

        AvsSurfaceCache surf = new AvsSurfaceCache(v);
        AvsSurfaceCache.RenderPlan plan = surf.currentPlan();
        auditSurface(v, plan, s, true);
        fillStats(s, v, plan);
        addScreenshot(out, "04-clay-subtract.png", plan, "CLAY- / CULLING ON", images, labels);
        s.ms = System.currentTimeMillis() - st;
    }

    private static void mixedStress(File out, List<BufferedImage> images, List<String> labels) throws Exception {
        Scenario s = scenario("hostile-mixed-csg-2000-surface-truth");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        Random rnd = new Random(SEED);
        AvsVolume.RayHit h = new AvsVolume.RayHit();
        AvsSurfaceCache surf = new AvsSurfaceCache(v);

        for (int i = 0; i < 2000; i++) {
            float[] d = randomDirection(rnd);
            h = v.raycast(d[0] * 7f, d[1] * 7f, d[2] * 7f, -d[0], -d[1], -d[2], h);
            s.attempted++;
            if (!h.hit) { s.rejected++; continue; }
            BrushMode mode = (i % 5 == 0 || i % 7 == 0) ? BrushMode.SUBTRACT : BrushMode.ADD;
            float radius = 0.09f + rnd.nextFloat() * 0.17f;
            float strength = 0.006f + rnd.nextFloat() * 0.014f;
            AvsVolume.BrushResult r = v.applyClay(h.x, h.y, h.z, h.nx, h.ny, h.nz,
                    radius, strength, mode);
            if (r.changed) s.accepted++; else s.rejected++;

            if ((i + 1) % 500 == 0) {
                check(v.brickCount() < AvsVolume.MAX_BRICKS, s,
                        "brick budget runaway at " + (i + 1));
                AvsSurfaceCache.RenderPlan p = surf.currentPlan();
                AvsSurfaceAudit.Report a = AvsSurfaceAudit.inspect(v, p);
                checkSurfaceAudit(a, s, "mixed checkpoint " + (i + 1), true);
                if (i + 1 == 1000 || i + 1 == 2000) {
                    addScreenshot(out, String.format(Locale.US, "05-mixed-csg-%04d.png", i + 1), p,
                            "MIXED CSG " + (i + 1) + " / CULLING ON", images, labels);
                }
            }
        }

        AvsSurfaceCache.RenderPlan plan = surf.currentPlan();
        auditSurface(v, plan, s, true);
        check(s.accepted > 1500, s,
                "too many stress rejections accepted=" + s.accepted + " rejected=" + s.rejected);

        boolean anyHit = false;
        for (int i = 0; i < 32; i++) {
            float[] d = randomDirection(rnd);
            if (v.raycast(d[0] * 7f, d[1] * 7f, d[2] * 7f,
                    -d[0], -d[1], -d[2], h).hit) {
                anyHit = true;
                break;
            }
        }
        check(anyHit, s, "raycast lost stressed surface");
        fillStats(s, v, plan);
        s.ms = System.currentTimeMillis() - st;
    }

    /** Dirty rebuild must be byte-for-byte equivalent to throwing the cache away. */
    private static void incrementalEqualsFullRebuild() {
        Scenario s = scenario("incremental-equals-full-rebuild");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        AvsSurfaceCache incrementalCache = new AvsSurfaceCache(v);
        incrementalCache.currentPlan();

        float b = AvsVolume.BRICK_SIZE * AvsVolume.VOXEL_SIZE;
        v.applySphereCsg(3f * b, 0.2f, -0.15f, 0.31f, true);
        v.applySphereCsg(2.5f * b, 0.2f, -0.15f, 0.13f, false);
        AvsSurfaceCache.RenderPlan incremental = incrementalCache.currentPlan();
        String incrementalHash = AvsSurfaceAudit.surfaceHash(incremental);
        AvsSurfaceAudit.Report incrementalAudit = AvsSurfaceAudit.inspect(v, incremental);
        checkSurfaceAudit(incrementalAudit, s, "incremental", true);

        AvsSurfaceCache fullCache = new AvsSurfaceCache(v);
        fullCache.rebuildAll();
        AvsSurfaceCache.RenderPlan full = fullCache.currentPlan();
        String fullHash = AvsSurfaceAudit.surfaceHash(full);
        AvsSurfaceAudit.Report fullAudit = AvsSurfaceAudit.inspect(v, full);
        checkSurfaceAudit(fullAudit, s, "full", true);
        check(incrementalHash.equals(fullHash), s,
                "incremental surface differs from full rebuild\ninc=" + incrementalHash + "\nfull=" + fullHash);
        s.surfaceHash = fullHash;
        s.fieldHash = v.fieldHash();
        s.detail = "rebuiltChunks=" + incremental.rebuiltChunks + " fullHash=" + fullHash;
        s.ms = System.currentTimeMillis() - st;
    }

    private static void snapshotRoundTrip() {
        Scenario s = scenario("snapshot-bit-exact-field-and-surface");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        for (int i = 0; i < 18; i++) v.applySphereCsg(0.2f * i, 0, 0, 0.18f, true);
        AvsSnapshot snap = v.snapshot();
        String beforeField = v.fieldHash();
        AvsSurfaceCache beforeCache = new AvsSurfaceCache(v);
        beforeCache.rebuildAll();
        String beforeSurface = AvsSurfaceAudit.surfaceHash(beforeCache.currentPlan());

        for (int i = 0; i < 12; i++) v.applySphereCsg(0, 0, 0.15f * i, 0.16f, false);
        snap.restoreInto(v);
        String afterField = v.fieldHash();
        AvsSurfaceCache afterCache = new AvsSurfaceCache(v);
        afterCache.rebuildAll();
        String afterSurface = AvsSurfaceAudit.surfaceHash(afterCache.currentPlan());
        check(beforeField.equals(afterField), s, "snapshot field hash mismatch");
        check(beforeSurface.equals(afterSurface), s, "snapshot surface hash mismatch");
        s.fieldHash = afterField;
        s.surfaceHash = afterSurface;
        s.ms = System.currentTimeMillis() - st;
    }

    private static void deterministicFieldAndSurface() {
        Scenario s = scenario("deterministic-field-and-surface-replay");
        long st = System.currentTimeMillis();
        String[] a = replayHashes(SEED + 10);
        String[] b = replayHashes(SEED + 10);
        check(a[0].equals(b[0]), s, "same seed produced different field hashes");
        check(a[1].equals(b[1]), s, "same field produced different surface hashes");
        s.fieldHash = a[0];
        s.surfaceHash = a[1];
        s.ms = System.currentTimeMillis() - st;
    }

    private static String[] replayHashes(long seed) {
        AvsVolume v = AvsVolume.createSphere(1f);
        Random r = new Random(seed);
        AvsVolume.RayHit h = new AvsVolume.RayHit();
        for (int i = 0; i < 240; i++) {
            float[] d = randomDirection(r);
            h = v.raycast(d[0] * 5f, d[1] * 5f, d[2] * 5f, -d[0], -d[1], -d[2], h);
            if (!h.hit) continue;
            BrushMode m = (i & 3) == 0 ? BrushMode.SUBTRACT : BrushMode.ADD;
            v.applyClay(h.x, h.y, h.z, h.nx, h.ny, h.nz,
                    0.12f + r.nextFloat() * 0.10f,
                    0.007f + r.nextFloat() * 0.010f, m);
        }
        AvsSurfaceCache cache = new AvsSurfaceCache(v);
        cache.rebuildAll();
        return new String[]{v.fieldHash(), AvsSurfaceAudit.surfaceHash(cache.currentPlan())};
    }

    private static void dirtyLocality() {
        Scenario s = scenario("dirty-chunk-locality");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        AvsSurfaceCache surf = new AvsSurfaceCache(v);
        surf.currentPlan();
        int totalBricks = v.brickCount();
        AvsVolume.RayHit h = v.raycast(0, 0, 4, 0, 0, -1, new AvsVolume.RayHit());
        check(h.hit, s, "locality ray miss");
        if (h.hit) v.applyClay(h.x, h.y, h.z, h.nx, h.ny, h.nz, 0.12f, 0.008f, BrushMode.ADD);
        AvsSurfaceCache.RenderPlan next = surf.currentPlan();
        check(next.rebuiltChunks > 0, s, "no dirty chunks rebuilt");
        check(next.rebuiltChunks < totalBricks / 2, s,
                "local dab rebuilt too much: " + next.rebuiltChunks + "/" + totalBricks);
        AvsSurfaceAudit.Report audit = AvsSurfaceAudit.inspect(v, next);
        checkSurfaceAudit(audit, s, "dirty locality", true);
        fillStats(s, v, next);
        s.detail = "rebuilt=" + next.rebuiltChunks + " totalBricks=" + totalBricks;
        s.ms = System.currentTimeMillis() - st;
    }

    private static void rebuildLoop() {
        Scenario s = scenario("surface-cache-drop-rebuild-loop");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        for (int i = 0; i < 36; i++) {
            float a = i * 0.27f;
            v.applySphereCsg((float) Math.cos(a) * 0.95f,
                    (float) Math.sin(a * 0.7f) * 0.65f,
                    (float) Math.sin(a) * 0.95f,
                    0.18f, true);
        }

        String expected = null;
        for (int i = 0; i < 12; i++) {
            AvsSurfaceCache cache = new AvsSurfaceCache(v);
            cache.rebuildAll();
            AvsSurfaceCache.RenderPlan p = cache.currentPlan();
            AvsSurfaceAudit.Report a = AvsSurfaceAudit.inspect(v, p);
            checkSurfaceAudit(a, s, "rebuild " + i, true);
            String hash = AvsSurfaceAudit.surfaceHash(p);
            if (expected == null) expected = hash;
            else check(expected.equals(hash), s, "rebuild hash changed at " + i);
        }
        s.surfaceHash = expected == null ? "" : expected;
        s.fieldHash = v.fieldHash();
        s.ms = System.currentTimeMillis() - st;
    }

    private static void benchmark() {
        Scenario s = scenario("surface-performance-smoke");
        long st = System.currentTimeMillis();
        AvsVolume v = AvsVolume.createSphere(1f);
        Random rnd = new Random(SEED + 99);
        AvsVolume.RayHit hit = new AvsVolume.RayHit();
        for (int i = 0; i < 220; i++) {
            float[] d = randomDirection(rnd);
            hit = v.raycast(d[0] * 5f, d[1] * 5f, d[2] * 5f, -d[0], -d[1], -d[2], hit);
            if (hit.hit) v.applyClay(hit.x, hit.y, hit.z, hit.nx, hit.ny, hit.nz,
                    0.16f, 0.010f, BrushMode.ADD);
        }

        double[] ray = new double[240];
        for (int i = 0; i < ray.length; i++) {
            float[] d = randomDirection(rnd);
            long t0 = System.nanoTime();
            v.raycast(d[0] * 6f, d[1] * 6f, d[2] * 6f, -d[0], -d[1], -d[2], hit);
            ray[i] = (System.nanoTime() - t0) / 1000.0;
        }
        Arrays.sort(ray);
        rayP50Us = pct(ray, .50);
        rayP95Us = pct(ray, .95);
        rayP99Us = pct(ray, .99);

        double[] extract = new double[16];
        AvsSurfaceCache.RenderPlan last = null;
        for (int i = 0; i < extract.length; i++) {
            AvsSurfaceCache cache = new AvsSurfaceCache(v);
            long t0 = System.nanoTime();
            cache.rebuildAll();
            last = cache.currentPlan();
            extract[i] = (System.nanoTime() - t0) / 1_000_000.0;
            if (last.totalTriangles == 0) failScenario(s, "empty benchmark surface");
        }
        Arrays.sort(extract);
        extractP50Ms = pct(extract, .50);
        extractP95Ms = pct(extract, .95);
        extractP99Ms = pct(extract, .99);
        if (last != null) {
            AvsSurfaceAudit.Report a = AvsSurfaceAudit.inspect(v, last);
            checkSurfaceAudit(a, s, "benchmark", true);
            fillStats(s, v, last);
        }
        s.detail = String.format(Locale.US,
                "ray p95=%.2fus full-extract p95=%.2fms", rayP95Us, extractP95Ms);
        s.ms = System.currentTimeMillis() - st;
    }

    private static void auditSurface(AvsVolume v, AvsSurfaceCache.RenderPlan plan,
                                     Scenario s, boolean requirePositiveVolume) {
        AvsSurfaceAudit.Report a = AvsSurfaceAudit.inspect(v, plan);
        s.audit = a;
        checkSurfaceAudit(a, s, "surface", requirePositiveVolume);
        s.fieldHash = v.fieldHash();
        s.surfaceHash = AvsSurfaceAudit.surfaceHash(plan);
    }

    private static void checkSurfaceAudit(AvsSurfaceAudit.Report a, Scenario s,
                                          String where, boolean requirePositiveVolume) {
        check(a.triangles > 0, s, where + " has no triangles");
        check(a.boundaryEdges == 0, s, where + " boundary edges=" + a.boundaryEdges);
        check(a.nonManifoldEdges == 0, s, where + " non-manifold edges=" + a.nonManifoldEdges);
        check(a.sameDirectionEdges == 0, s, where + " inconsistent directed edges=" + a.sameDirectionEdges);
        check(a.degenerateTriangles == 0, s, where + " degenerate triangles=" + a.degenerateTriangles);
        check(a.duplicateTriangles == 0, s, where + " duplicate triangles=" + a.duplicateTriangles);
        check(a.seamPositionMismatches == 0, s, where + " seam bit mismatches=" + a.seamPositionMismatches);
        check(a.sdfOrientationFailureRate() <= 0.03, s,
                where + " SDF-side orientation failure rate=" + a.sdfOrientationFailureRate());
        check(a.normalOrientationFailures <= Math.max(4, a.triangles / 30), s,
                where + " shading-normal disagreement=" + a.normalOrientationFailures + "/" + a.triangles);
        if (requirePositiveVolume) check(a.signedVolume > 0.0, s,
                where + " signed volume reversed=" + a.signedVolume);
    }

    private static AvsSurfaceCache.RenderPlan mutateFirstTriangle(AvsSurfaceCache.RenderPlan source,
                                                                   boolean flip,
                                                                   boolean remove) {
        AvsSurfaceCache.RenderPlan out = new AvsSurfaceCache.RenderPlan();
        out.chunks = new AvsSurfaceCache.Chunk[source.chunks.length];
        boolean mutated = false;
        int totalTris = 0, totalVerts = 0;
        for (int i = 0; i < source.chunks.length; i++) {
            AvsSurfaceCache.Chunk c = source.chunks[i];
            short[] idx = c.indices.clone();
            if (!mutated && idx.length >= 3) {
                if (remove) idx = Arrays.copyOfRange(idx, 3, idx.length);
                else if (flip) {
                    short t = idx[0]; idx[0] = idx[1]; idx[1] = t;
                }
                mutated = true;
            }
            AvsSurfaceCache.Chunk nc = new AvsSurfaceCache.Chunk(
                    c.brickId, c.bx, c.by, c.bz, c.revision,
                    c.positions.clone(), c.normals.clone(), idx,
                    c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ);
            out.chunks[i] = nc;
            totalTris += idx.length / 3;
            totalVerts += nc.positions.length / 3;
        }
        out.fieldVersion = source.fieldVersion;
        out.surfaceVersion = source.surfaceVersion;
        out.totalTriangles = totalTris;
        out.totalVertices = totalVerts;
        return out;
    }

    private static void fillStats(Scenario s, AvsVolume v, AvsSurfaceCache.RenderPlan p) {
        AvsVolume.Stats st = v.stats();
        s.bricks = st.bricks;
        s.fieldBytes = st.sampleBytes;
        s.chunks = p.chunks.length;
        s.vertices = p.totalVertices;
        s.triangles = p.totalTriangles;
        s.surfaceBytes = p.estimatedBytes();
    }

    private static float[] randomDirection(Random r) {
        float z = r.nextFloat() * 2f - 1f;
        float a = r.nextFloat() * (float) (Math.PI * 2);
        float q = (float) Math.sqrt(Math.max(0f, 1f - z * z));
        return new float[]{q * (float) Math.cos(a), z, q * (float) Math.sin(a)};
    }

    private static void addScreenshot(File out, String name,
                                      AvsSurfaceCache.RenderPlan p, String label,
                                      List<BufferedImage> images,
                                      List<String> labels) throws Exception {
        BufferedImage img = render(p, label);
        File f = new File(out, name);
        ImageIO.write(img, "png", f);
        screenshots.add(name);
        images.add(img);
        labels.add(label);
    }

    /**
     * Headless Android-equivalent visibility rule: only CCW/front-facing
     * triangles survive the camera-facing test. Broken winding therefore shows
     * up as literal holes in VSS screenshots instead of being hidden by a
     * two-sided debug renderer.
     */
    private static BufferedImage render(AvsSurfaceCache.RenderPlan plan, String title) {
        BufferedImage img = new BufferedImage(IMAGE, IMAGE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(18, 21, 25));
        g.fillRect(0, 0, IMAGE, IMAGE);

        float yaw = (float) Math.toRadians(28);
        float pitch = (float) Math.toRadians(-12);
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        List<Tri> tris = new ArrayList<>();
        float max = 1f;

        for (AvsSurfaceCache.Chunk c : plan.chunks) {
            float[] t = new float[c.positions.length];
            for (int i = 0; i < c.positions.length; i += 3) {
                float x = c.positions[i], y = c.positions[i + 1], z = c.positions[i + 2];
                float x1 = cy * x + sy * z;
                float z1 = -sy * x + cy * z;
                float y1 = cp * y - sp * z1;
                float z2 = sp * y + cp * z1;
                t[i] = x1; t[i + 1] = y1; t[i + 2] = z2;
                max = Math.max(max, Math.max(Math.abs(x1), Math.abs(y1)));
            }
            for (int i = 0; i < c.indices.length; i += 3) {
                int ia = (c.indices[i] & 0xffff) * 3;
                int ib = (c.indices[i + 1] & 0xffff) * 3;
                int ic = (c.indices[i + 2] & 0xffff) * 3;
                float abx = t[ib] - t[ia], aby = t[ib + 1] - t[ia + 1], abz = t[ib + 2] - t[ia + 2];
                float acx = t[ic] - t[ia], acy = t[ic + 1] - t[ia + 1], acz = t[ic + 2] - t[ia + 2];
                float nx = aby * acz - abz * acy;
                float ny = abz * acx - abx * acz;
                float nz = abx * acy - aby * acx;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1e-8f) continue;
                nx /= nl; ny /= nl; nz /= nl;
                if (nz <= 0f) continue; // back-face culling on
                Tri tr = new Tri();
                tr.p0 = new float[]{t[ia], t[ia + 1], t[ia + 2]};
                tr.p1 = new float[]{t[ib], t[ib + 1], t[ib + 2]};
                tr.p2 = new float[]{t[ic], t[ic + 1], t[ic + 2]};
                tr.depth = (t[ia + 2] + t[ib + 2] + t[ic + 2]) / 3f;
                float light = Math.max(0f, Math.min(1f, nx * -0.25f + ny * 0.55f + nz * 0.78f));
                tr.shade = 0.25f + light * 0.68f;
                tris.add(tr);
            }
        }
        tris.sort((a, b) -> Float.compare(a.depth, b.depth));
        float scale = IMAGE * 0.39f / max;
        float centerX = IMAGE * 0.5f, centerY = IMAGE * 0.52f;
        for (Tri t : tris) {
            int[] xs = {
                    Math.round(centerX + t.p0[0] * scale),
                    Math.round(centerX + t.p1[0] * scale),
                    Math.round(centerX + t.p2[0] * scale)};
            int[] ys = {
                    Math.round(centerY - t.p0[1] * scale),
                    Math.round(centerY - t.p1[1] * scale),
                    Math.round(centerY - t.p2[1] * scale)};
            int gray = Math.max(0, Math.min(255, Math.round(t.shade * 255f)));
            g.setColor(new Color(gray, gray, gray));
            g.fillPolygon(new Polygon(xs, ys, 3));
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
        g.drawString(title, 18, 28);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        g.drawString("CCW + BACK CULL | chunks=" + plan.chunks.length
                + " verts=" + plan.totalVertices + " tris=" + plan.totalTriangles, 18, 49);
        g.dispose();
        return img;
    }

    private static BufferedImage contactSheet(List<BufferedImage> imgs, List<String> labels) {
        int cols = 2, cell = 330, labelH = 34, rows = (imgs.size() + 1) / 2;
        BufferedImage out = new BufferedImage(cols * cell, rows * (cell + labelH), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(12, 14, 17));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        for (int i = 0; i < imgs.size(); i++) {
            int x = (i % 2) * cell, y = (i / 2) * (cell + labelH);
            g.drawImage(imgs.get(i), x, y, cell, cell, null);
            g.drawString(labels.get(i), x + 8, y + cell + 21);
        }
        g.dispose();
        return out;
    }

    private static Scenario scenario(String name) {
        Scenario s = new Scenario();
        s.name = name;
        scenarios.add(s);
        return s;
    }

    private static void check(boolean ok, Scenario s, String detail) {
        if (!ok) failScenario(s, detail);
    }

    private static void failScenario(Scenario s, String detail) {
        s.pass = false;
        if (!s.detail.isEmpty()) s.detail += "; ";
        s.detail += detail;
        failures.add(s.name + ": " + detail);
    }

    private static void fail(String detail) { failures.add(detail); }

    private static double pct(double[] a, double p) {
        if (a.length == 0) return 0;
        int i = (int) Math.floor((a.length - 1) * p);
        return a[Math.max(0, Math.min(a.length - 1, i))];
    }

    private static void writeJson(File out, long duration) throws Exception {
        try (FileWriter w = new FileWriter(new File(out, "dump.json"))) {
            w.write("{\n  \"schema\":\"cabrush-avs-surface-truth-dump-v2\",\n");
            w.write("  \"generated_at\":\"" + Instant.now() + "\",\n");
            w.write("  \"result\":\"" + (failures.isEmpty() ? "PASS" : "FAIL") + "\",\n");
            w.write("  \"duration_ms\":" + duration + ",\n");
            w.write("  \"surface_contract\":{\"inside_sign\":\"negative\",\"outside_sign\":\"positive\",\"front_face\":\"CCW\",\"cull_face\":\"BACK\",\"extractor\":\"Freudenthal-6 Marching Tetrahedra\",\"canonical_edge_interpolation\":true},\n");
            w.write("  \"config\":{\"brick_size\":8,\"samples_per_brick\":512,\"sample_bytes\":2,\"voxel_size\":"
                    + AvsVolume.VOXEL_SIZE + ",\"max_bricks\":" + AvsVolume.MAX_BRICKS + "},\n");
            w.write(String.format(Locale.US,
                    "  \"performance\":{\"ray_p50_us\":%.3f,\"ray_p95_us\":%.3f,\"ray_p99_us\":%.3f,\"full_extract_p50_ms\":%.3f,\"full_extract_p95_ms\":%.3f,\"full_extract_p99_ms\":%.3f},\n",
                    rayP50Us, rayP95Us, rayP99Us,
                    extractP50Ms, extractP95Ms, extractP99Ms));
            w.write("  \"screenshots\":[");
            for (int i = 0; i < screenshots.size(); i++) {
                if (i > 0) w.write(",");
                w.write("\"" + screenshots.get(i) + "\"");
            }
            w.write("],\n  \"scenarios\":[\n");
            for (int i = 0; i < scenarios.size(); i++) {
                Scenario s = scenarios.get(i);
                w.write("    {\"name\":\"" + esc(s.name) + "\",\"pass\":" + s.pass
                        + ",\"duration_ms\":" + s.ms
                        + ",\"attempted\":" + s.attempted
                        + ",\"accepted\":" + s.accepted
                        + ",\"rejected\":" + s.rejected
                        + ",\"bricks\":" + s.bricks
                        + ",\"field_bytes\":" + s.fieldBytes
                        + ",\"chunks\":" + s.chunks
                        + ",\"surface_vertices\":" + s.vertices
                        + ",\"surface_triangles\":" + s.triangles
                        + ",\"surface_bytes\":" + s.surfaceBytes
                        + ",\"field_sha256\":\"" + esc(s.fieldHash) + "\""
                        + ",\"surface_sha256\":\"" + esc(s.surfaceHash) + "\""
                        + ",\"detail\":\"" + esc(s.detail) + "\"");
                if (s.audit != null) {
                    AvsSurfaceAudit.Report a = s.audit;
                    w.write(String.format(Locale.US,
                            ",\"surface_audit\":{\"welded_vertices\":%d,\"triangles\":%d,\"boundary_edges\":%d,\"non_manifold_edges\":%d,\"same_direction_edges\":%d,\"degenerate_triangles\":%d,\"duplicate_triangles\":%d,\"seam_position_mismatches\":%d,\"sdf_orientation_failures\":%d,\"normal_orientation_failures\":%d,\"signed_volume\":%.9f,\"min_area2\":%.9g,\"min_sdf_side_delta\":%.9g,\"min_normal_dot\":%.9g}",
                            a.weldedVertices, a.triangles, a.boundaryEdges, a.nonManifoldEdges,
                            a.sameDirectionEdges, a.degenerateTriangles, a.duplicateTriangles,
                            a.seamPositionMismatches, a.sdfOrientationFailures,
                            a.normalOrientationFailures, a.signedVolume, a.minArea2,
                            a.minSdfSideDelta, a.minNormalDot));
                }
                w.write("}" + (i + 1 < scenarios.size() ? "," : "") + "\n");
            }
            w.write("  ],\n  \"failures\":[");
            for (int i = 0; i < failures.size(); i++) {
                if (i > 0) w.write(",");
                w.write("\"" + esc(failures.get(i)) + "\"");
            }
            w.write("]\n}\n");
        }
    }

    private static void writeText(File out, long duration) throws Exception {
        try (FileWriter w = new FileWriter(new File(out, "dump.txt"))) {
            w.write("CABrush AVS 0.1.1 Surface Truth VSS\nRESULT: "
                    + (failures.isEmpty() ? "PASS" : "FAIL") + "\nDuration: " + duration + " ms\n");
            w.write("Surface contract: negative=inside, positive=outside, CCW front, BACK cull\n");
            w.write(String.format(Locale.US, "Ray p50/p95/p99: %.2f / %.2f / %.2f us\n",
                    rayP50Us, rayP95Us, rayP99Us));
            w.write(String.format(Locale.US, "Full extract p50/p95/p99: %.2f / %.2f / %.2f ms\n",
                    extractP50Ms, extractP95Ms, extractP99Ms));
            for (Scenario s : scenarios) {
                w.write("\n[" + s.name + "] " + (s.pass ? "PASS" : "FAIL") + " " + s.ms + "ms\n");
                if (s.attempted > 0) w.write("ops=" + s.attempted + " accepted=" + s.accepted + " rejected=" + s.rejected + "\n");
                if (s.bricks > 0) w.write("bricks=" + s.bricks + " fieldBytes=" + s.fieldBytes
                        + " chunks=" + s.chunks + " verts=" + s.vertices + " tris=" + s.triangles
                        + " surfaceBytes=" + s.surfaceBytes + "\n");
                if (!s.fieldHash.isEmpty()) w.write("fieldSHA256=" + s.fieldHash + "\n");
                if (!s.surfaceHash.isEmpty()) w.write("surfaceSHA256=" + s.surfaceHash + "\n");
                if (s.audit != null) w.write("audit=" + s.audit + "\n");
                if (!s.detail.isEmpty()) w.write("detail=" + s.detail + "\n");
            }
            if (!failures.isEmpty()) {
                w.write("\nFAILURES\n");
                for (String f : failures) w.write("- " + f + "\n");
            }
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}

final class AvsSurfaceAudit {
    static final class Report {
        int weldedVertices;
        int triangles;
        int boundaryEdges;
        int nonManifoldEdges;
        int sameDirectionEdges;
        int degenerateTriangles;
        int duplicateTriangles;
        int seamPositionMismatches;
        int sdfOrientationFailures;
        int normalOrientationFailures;
        double signedVolume;
        float minArea2 = Float.POSITIVE_INFINITY;
        float minSdfSideDelta = Float.POSITIVE_INFINITY;
        float minNormalDot = Float.POSITIVE_INFINITY;

        boolean isClosedConsistentlyOriented() {
            return triangles > 0
                    && boundaryEdges == 0
                    && nonManifoldEdges == 0
                    && sameDirectionEdges == 0
                    && degenerateTriangles == 0
                    && duplicateTriangles == 0
                    && seamPositionMismatches == 0;
        }

        double sdfOrientationFailureRate() {
            return triangles == 0 ? 1.0 : (double) sdfOrientationFailures / triangles;
        }

        @Override public String toString() {
            return "verts=" + weldedVertices
                    + " tris=" + triangles
                    + " boundary=" + boundaryEdges
                    + " nonManifold=" + nonManifoldEdges
                    + " sameDir=" + sameDirectionEdges
                    + " degenerate=" + degenerateTriangles
                    + " duplicate=" + duplicateTriangles
                    + " seamMismatch=" + seamPositionMismatches
                    + " sdfOrientation=" + sdfOrientationFailures
                    + " normalOrientation=" + normalOrientationFailures
                    + " signedVolume=" + signedVolume;
        }
    }

    private static final float WELD_SCALE = 1_000_000f;
    private static final float AREA_EPS = 1.0e-14f;
    private static final float SIDE_EPS = AvsVolume.VOXEL_SIZE * 0.18f;
    private static final float SIDE_TOLERANCE = AvsVolume.VOXEL_SIZE * 0.015f;

    private AvsSurfaceAudit() {}

    static Report inspect(AvsVolume volume, AvsSurfaceCache.RenderPlan plan) {
        Report report = new Report();
        Map<VertexKey, Integer> welded = new HashMap<>();
        List<float[]> globalPosition = new ArrayList<>();
        List<RawPosition> rawPosition = new ArrayList<>();
        Map<Long, EdgeStat> edges = new HashMap<>();
        Set<FaceKey> faces = new HashSet<>();

        for (AvsSurfaceCache.Chunk chunk : plan.chunks) {
            int localCount = chunk.positions.length / 3;
            int[] localToGlobal = new int[localCount];

            for (int v = 0; v < localCount; v++) {
                int p = v * 3;
                float x = chunk.positions[p];
                float y = chunk.positions[p + 1];
                float z = chunk.positions[p + 2];
                VertexKey key = new VertexKey(x, y, z);
                Integer id = welded.get(key);
                RawPosition raw = new RawPosition(x, y, z);
                if (id == null) {
                    id = globalPosition.size();
                    welded.put(key, id);
                    globalPosition.add(new float[]{x, y, z});
                    rawPosition.add(raw);
                } else if (!rawPosition.get(id).equals(raw)) {
                    report.seamPositionMismatches++;
                }
                localToGlobal[v] = id;
            }

            for (int i = 0; i < chunk.indices.length; i += 3) {
                int la = chunk.indices[i] & 0xffff;
                int lb = chunk.indices[i + 1] & 0xffff;
                int lc = chunk.indices[i + 2] & 0xffff;
                if (la >= localCount || lb >= localCount || lc >= localCount) {
                    report.degenerateTriangles++;
                    continue;
                }
                int a = localToGlobal[la];
                int b = localToGlobal[lb];
                int c = localToGlobal[lc];
                if (a == b || b == c || c == a) {
                    report.degenerateTriangles++;
                    continue;
                }

                FaceKey face = new FaceKey(a, b, c);
                if (!faces.add(face)) report.duplicateTriangles++;

                float[] A = globalPosition.get(a);
                float[] B = globalPosition.get(b);
                float[] C = globalPosition.get(c);
                float abx = B[0] - A[0], aby = B[1] - A[1], abz = B[2] - A[2];
                float acx = C[0] - A[0], acy = C[1] - A[1], acz = C[2] - A[2];
                float nx = aby * acz - abz * acy;
                float ny = abz * acx - abx * acz;
                float nz = abx * acy - aby * acx;
                float area2 = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                report.minArea2 = Math.min(report.minArea2, area2);
                if (!(area2 > AREA_EPS) || !Float.isFinite(area2)) {
                    report.degenerateTriangles++;
                    continue;
                }
                nx /= area2; ny /= area2; nz /= area2;

                addEdge(edges, a, b);
                addEdge(edges, b, c);
                addEdge(edges, c, a);
                report.triangles++;

                report.signedVolume += (
                        A[0] * (B[1] * C[2] - B[2] * C[1])
                                + A[1] * (B[2] * C[0] - B[0] * C[2])
                                + A[2] * (B[0] * C[1] - B[1] * C[0])) / 6.0;

                float cx = (A[0] + B[0] + C[0]) / 3f;
                float cy = (A[1] + B[1] + C[1]) / 3f;
                float cz = (A[2] + B[2] + C[2]) / 3f;
                float outside = volume.sample(cx + nx * SIDE_EPS, cy + ny * SIDE_EPS, cz + nz * SIDE_EPS);
                float inside = volume.sample(cx - nx * SIDE_EPS, cy - ny * SIDE_EPS, cz - nz * SIDE_EPS);
                float sideDelta = outside - inside;
                report.minSdfSideDelta = Math.min(report.minSdfSideDelta, sideDelta);
                if (!Float.isFinite(sideDelta) || sideDelta < -SIDE_TOLERANCE) {
                    report.sdfOrientationFailures++;
                }

                int na = la * 3, nb = lb * 3, nc = lc * 3;
                float snx = chunk.normals[na] + chunk.normals[nb] + chunk.normals[nc];
                float sny = chunk.normals[na + 1] + chunk.normals[nb + 1] + chunk.normals[nc + 1];
                float snz = chunk.normals[na + 2] + chunk.normals[nb + 2] + chunk.normals[nc + 2];
                float sl = (float) Math.sqrt(snx * snx + sny * sny + snz * snz);
                float ndot = sl > 1e-10f ? (nx * snx + ny * sny + nz * snz) / sl : -1f;
                report.minNormalDot = Math.min(report.minNormalDot, ndot);
                if (!Float.isFinite(ndot) || ndot <= 0f) report.normalOrientationFailures++;
            }
        }

        report.weldedVertices = globalPosition.size();
        for (EdgeStat e : edges.values()) {
            if (e.count == 1) report.boundaryEdges++;
            else if (e.count != 2) report.nonManifoldEdges++;
            else if (e.balance != 0) report.sameDirectionEdges++;
        }
        if (!Float.isFinite(report.minArea2)) report.minArea2 = 0f;
        if (!Float.isFinite(report.minSdfSideDelta)) report.minSdfSideDelta = 0f;
        if (!Float.isFinite(report.minNormalDot)) report.minNormalDot = 0f;
        return report;
    }

    static String surfaceHash(AvsSurfaceCache.RenderPlan plan) {
        try {
            AvsSurfaceCache.Chunk[] chunks = plan.chunks.clone();
            Arrays.sort(chunks, Comparator
                    .comparingInt((AvsSurfaceCache.Chunk c) -> c.bx)
                    .thenComparingInt(c -> c.by)
                    .thenComparingInt(c -> c.bz));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer intBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer floatBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer shortBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
            for (AvsSurfaceCache.Chunk c : chunks) {
                intBuf.clear();
                intBuf.putInt(c.bx).putInt(c.by).putInt(c.bz).putInt(c.indices.length / 3);
                md.update(intBuf.array());
                for (float v : c.positions) {
                    floatBuf.clear(); floatBuf.putFloat(v); md.update(floatBuf.array());
                }
                for (float v : c.normals) {
                    floatBuf.clear(); floatBuf.putFloat(v); md.update(floatBuf.array());
                }
                for (short v : c.indices) {
                    shortBuf.clear(); shortBuf.putShort(v); md.update(shortBuf.array());
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte v : md.digest()) sb.append(String.format("%02x", v & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void addEdge(Map<Long, EdgeStat> edges, int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        long key = ((long) lo << 32) | (hi & 0xffffffffL);
        EdgeStat e = edges.get(key);
        if (e == null) {
            e = new EdgeStat();
            edges.put(key, e);
        }
        e.count++;
        e.balance += a < b ? 1 : -1;
    }

    private static final class EdgeStat {
        int count;
        int balance;
    }

    private static final class VertexKey {
        final int x, y, z;
        VertexKey(float x, float y, float z) {
            this.x = Math.round(x * WELD_SCALE);
            this.y = Math.round(y * WELD_SCALE);
            this.z = Math.round(z * WELD_SCALE);
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof VertexKey)) return false;
            VertexKey k = (VertexKey) o;
            return x == k.x && y == k.y && z == k.z;
        }
        @Override public int hashCode() {
            int h = x * 73856093;
            h ^= y * 19349663;
            h ^= z * 83492791;
            return h;
        }
    }

    private static final class RawPosition {
        final int x, y, z;
        RawPosition(float x, float y, float z) {
            this.x = Float.floatToRawIntBits(x);
            this.y = Float.floatToRawIntBits(y);
            this.z = Float.floatToRawIntBits(z);
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof RawPosition)) return false;
            RawPosition r = (RawPosition) o;
            return x == r.x && y == r.y && z == r.z;
        }
        @Override public int hashCode() { return x * 31 * 31 + y * 31 + z; }
    }

    private static final class FaceKey {
        final int a, b, c;
        FaceKey(int x, int y, int z) {
            int lo = Math.min(x, Math.min(y, z));
            int hi = Math.max(x, Math.max(y, z));
            int mid = x + y + z - lo - hi;
            a = lo; b = mid; c = hi;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof FaceKey)) return false;
            FaceKey f = (FaceKey) o;
            return a == f.a && b == f.b && c == f.c;
        }
        @Override public int hashCode() {
            int h = a * 73856093;
            h ^= b * 19349663;
            h ^= c * 83492791;
            return h;
        }
    }
}
