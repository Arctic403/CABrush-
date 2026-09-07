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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class VssVerify {
    private static final int IMAGE_SIZE = 640;

    private static final class ScenarioResult {
        String name;
        int requested;
        int accepted;
        int rejected;
        long durationMs;
        boolean healthy;
        String sha256;
        SculptMesh.Metrics metrics;
        File screenshot;
        String note = "";
    }

    private static final class Tri {
        final int a, b, c;
        final float depth;
        final float shade;
        Tri(int a, int b, int c, float depth, float shade) {
            this.a = a; this.b = b; this.c = c; this.depth = depth; this.shade = shade;
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        System.setProperty("java.awt.headless", "true");

        File outDir = new File(args.length > 0 ? args[0] : "build/vss/report");
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("Could not create VSS output directory: " + outDir);
        }

        long started = System.currentTimeMillis();
        List<ScenarioResult> results = new ArrayList<>();
        List<BufferedImage> images = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        boolean pass = true;
        String failure = "";

        try {
            SculptMesh baseline = SculptMesh.createSphere();
            require(baseline.positions.length / 3 == 2562, "sphere vertex count changed");
            require(baseline.indices.length / 3 == 5120, "sphere triangle count changed");
            require(baseline.isClosedTwoManifold(), "baseline is not closed two-manifold");
            require(baseline.isHealthy(), "baseline geometry is unhealthy");

            File baselineFile = new File(outDir, "00-baseline.png");
            BufferedImage baselineImage = renderMesh(baseline, "BASELINE");
            ImageIO.write(baselineImage, "png", baselineFile);
            images.add(baselineImage);
            labels.add("Baseline");

            results.add(runScenario(outDir, "01-clay-add-hammer", BrushMode.ADD, 2500,
                    0xCABA0001L, 0.30f, 0.018f, true, images, labels));
            results.add(runScenario(outDir, "02-clay-subtract-hammer", BrushMode.SUBTRACT, 2500,
                    0xCABA0002L, 0.30f, 0.018f, true, images, labels));
            results.add(runAlternating(outDir, "03-alternating-random", 5000,
                    0xCABA0003L, images, labels));
            results.add(runAbuse(outDir, "04-max-strength-abuse", 2000,
                    0xCABA0004L, images, labels));

            for (ScenarioResult r : results) {
                if (!r.healthy) pass = false;
                if (r.accepted == 0) {
                    pass = false;
                    r.note += " no strokes accepted;";
                }
                if (r.metrics == null || r.metrics.minQuality < 0.08f - 1e-4f) {
                    pass = false;
                    r.note += " triangle quality below floor;";
                }
                if (r.metrics == null || !Float.isFinite(r.metrics.maxRadius) || r.metrics.maxRadius > 2.35f) {
                    pass = false;
                    r.note += " radial runaway;";
                }
            }

            ScenarioResult add = results.get(0);
            ScenarioResult sub = results.get(1);
            require(add.metrics.maxRadius > 1.01f, "Clay+ produced no measurable outward form");
            require(sub.metrics.maxRadius <= 1.25f, "Clay- caused unexpected outward runaway");

            BufferedImage contact = contactSheet(images, labels);
            ImageIO.write(contact, "png", new File(outDir, "99-contact-sheet.png"));
        } catch (Throwable t) {
            pass = false;
            failure = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            t.printStackTrace(System.err);
        }

        long duration = System.currentTimeMillis() - started;
        writeDump(outDir, pass, failure, duration, results);
        writeTextDump(outDir, pass, failure, duration, results);

        System.out.println("CABrush VSS: " + (pass ? "PASS" : "FAIL"));
        System.out.println("Evidence: " + outDir.getAbsolutePath());
        if (!pass) System.exit(2);
    }

    private static ScenarioResult runScenario(File outDir, String name, BrushMode mode, int operations,
            long seed, float baseRadius, float baseStrength, boolean capBiased,
            List<BufferedImage> images, List<String> labels) throws Exception {
        SculptMesh mesh = SculptMesh.createSphere();
        Random random = new Random(seed);
        ScenarioResult result = new ScenarioResult();
        result.name = name;
        result.requested = operations;

        long start = System.currentTimeMillis();
        for (int i = 0; i < operations; i++) {
            float[] dir = capBiased ? capDirection(random, i) : randomDirection(random);
            SculptMesh.Hit hit = hitFromDirection(mesh, dir);
            if (hit == null) { result.rejected++; continue; }

            float radius = clamp(baseRadius * (0.72f + random.nextFloat() * 0.60f), 0.07f, 0.60f);
            float strength = clamp(baseStrength * (0.65f + random.nextFloat() * 0.85f), 0.001f, 0.055f);
            float[] view = {-dir[0], -dir[1], -dir[2]};
            if (mesh.applyClay(hit.x, hit.y, hit.z, hit.nx, hit.ny, hit.nz, view, radius, strength, mode)) {
                result.accepted++;
            } else {
                result.rejected++;
            }
            if ((i % 125) == 0 && !mesh.isHealthy()) {
                result.note = "mesh failed health check at operation " + i;
                break;
            }
        }
        finishScenario(outDir, result, mesh, images, labels);
        result.durationMs = System.currentTimeMillis() - start;
        return result;
    }

    private static ScenarioResult runAlternating(File outDir, String name, int operations, long seed,
            List<BufferedImage> images, List<String> labels) throws Exception {
        SculptMesh mesh = SculptMesh.createSphere();
        Random random = new Random(seed);
        ScenarioResult result = new ScenarioResult();
        result.name = name;
        result.requested = operations;
        long start = System.currentTimeMillis();

        for (int i = 0; i < operations; i++) {
            float[] dir = randomDirection(random);
            SculptMesh.Hit hit = hitFromDirection(mesh, dir);
            if (hit == null) { result.rejected++; continue; }
            BrushMode mode = ((i / 17) & 1) == 0 ? BrushMode.ADD : BrushMode.SUBTRACT;
            float radius = 0.08f + random.nextFloat() * 0.46f;
            float strength = 0.002f + random.nextFloat() * 0.034f;
            float[] view = {-dir[0], -dir[1], -dir[2]};
            if (mesh.applyClay(hit.x, hit.y, hit.z, hit.nx, hit.ny, hit.nz, view, radius, strength, mode)) {
                result.accepted++;
            } else {
                result.rejected++;
            }
            if ((i % 125) == 0 && !mesh.isHealthy()) {
                result.note = "mesh failed health check at operation " + i;
                break;
            }
        }
        finishScenario(outDir, result, mesh, images, labels);
        result.durationMs = System.currentTimeMillis() - start;
        return result;
    }

    private static ScenarioResult runAbuse(File outDir, String name, int operations, long seed,
            List<BufferedImage> images, List<String> labels) throws Exception {
        SculptMesh mesh = SculptMesh.createSphere();
        Random random = new Random(seed);
        ScenarioResult result = new ScenarioResult();
        result.name = name;
        result.requested = operations;
        long start = System.currentTimeMillis();

        for (int i = 0; i < operations; i++) {
            float[] dir = (i % 5 == 0) ? new float[]{0f, 0f, 1f} : randomDirection(random);
            SculptMesh.Hit hit = hitFromDirection(mesh, dir);
            if (hit == null) { result.rejected++; continue; }
            BrushMode mode = (i & 1) == 0 ? BrushMode.ADD : BrushMode.SUBTRACT;
            float radius = 0.46f + random.nextFloat() * 0.22f;
            float strength = 0.045f + random.nextFloat() * 0.015f;
            float[] view = {-dir[0], -dir[1], -dir[2]};
            if (mesh.applyClay(hit.x, hit.y, hit.z, hit.nx, hit.ny, hit.nz, view, radius, strength, mode)) {
                result.accepted++;
            } else {
                result.rejected++;
            }
            if ((i % 50) == 0 && !mesh.isHealthy()) {
                result.note = "mesh failed health check at operation " + i;
                break;
            }
        }
        finishScenario(outDir, result, mesh, images, labels);
        result.durationMs = System.currentTimeMillis() - start;
        return result;
    }

    private static void finishScenario(File outDir, ScenarioResult result, SculptMesh mesh,
            List<BufferedImage> images, List<String> labels) throws Exception {
        result.healthy = mesh.isHealthy();
        result.metrics = mesh.metrics();
        result.sha256 = hashPositions(mesh.positions);
        File imageFile = new File(outDir, result.name + ".png");
        BufferedImage image = renderMesh(mesh, result.name.toUpperCase(Locale.US));
        ImageIO.write(image, "png", imageFile);
        result.screenshot = imageFile;
        images.add(image);
        labels.add(result.name);
    }

    private static SculptMesh.Hit hitFromDirection(SculptMesh mesh, float[] directionFromCenter) {
        float[] origin = {directionFromCenter[0] * 3.2f, directionFromCenter[1] * 3.2f, directionFromCenter[2] * 3.2f};
        float[] ray = {-directionFromCenter[0], -directionFromCenter[1], -directionFromCenter[2]};
        return mesh.raycast(origin, ray);
    }

    private static float[] capDirection(Random random, int operation) {
        if ((operation % 4) == 0) return new float[]{0f, 0f, 1f};
        float x = (random.nextFloat() - 0.5f) * 0.48f;
        float y = (random.nextFloat() - 0.5f) * 0.48f;
        return normalize(x, y, 1f);
    }

    private static float[] randomDirection(Random random) {
        float z = random.nextFloat() * 2f - 1f;
        float theta = random.nextFloat() * (float)(Math.PI * 2.0);
        float r = (float)Math.sqrt(Math.max(0f, 1f - z * z));
        return new float[]{r * (float)Math.cos(theta), z, r * (float)Math.sin(theta)};
    }

    private static float[] normalize(float x, float y, float z) {
        float len = (float)Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / len, y / len, z / len};
    }

    private static BufferedImage renderMesh(SculptMesh mesh, String title) {
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(18, 21, 25));
        g.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);

        int vertexCount = mesh.positions.length / 3;
        float[] tx = new float[vertexCount], ty = new float[vertexCount], tz = new float[vertexCount];
        float yaw = (float)Math.toRadians(28), pitch = (float)Math.toRadians(-12);
        float cy = (float)Math.cos(yaw), sy = (float)Math.sin(yaw);
        float cp = (float)Math.cos(pitch), sp = (float)Math.sin(pitch);
        float maxExtent = 1f;

        for (int i = 0; i < vertexCount; i++) {
            int b = i * 3;
            float x = mesh.positions[b], y = mesh.positions[b + 1], z = mesh.positions[b + 2];
            float x1 = cy * x + sy * z;
            float z1 = -sy * x + cy * z;
            float y1 = cp * y - sp * z1;
            float z2 = sp * y + cp * z1;
            tx[i] = x1; ty[i] = y1; tz[i] = z2;
            maxExtent = Math.max(maxExtent, Math.max(Math.abs(x1), Math.abs(y1)));
        }

        float scale = (IMAGE_SIZE * 0.39f) / maxExtent;
        float center = IMAGE_SIZE * 0.5f;
        List<Tri> tris = new ArrayList<>(mesh.indices.length / 3);
        for (int i = 0; i < mesh.indices.length; i += 3) {
            int a = mesh.indices[i], b = mesh.indices[i + 1], c = mesh.indices[i + 2];
            float abx = tx[b] - tx[a], aby = ty[b] - ty[a], abz = tz[b] - tz[a];
            float acx = tx[c] - tx[a], acy = ty[c] - ty[a], acz = tz[c] - tz[a];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            if (nz <= 0f) continue;
            float nLen = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nLen < 1e-8f) continue;
            nx /= nLen; ny /= nLen; nz /= nLen;
            float light = clamp(nx * -0.25f + ny * 0.55f + nz * 0.78f, 0f, 1f);
            tris.add(new Tri(a, b, c, (tz[a] + tz[b] + tz[c]) / 3f, 0.25f + light * 0.68f));
        }

        tris.sort(Comparator.comparingDouble(t -> t.depth));
        g.setStroke(new BasicStroke(0.55f));
        for (Tri tri : tris) {
            int[] xs = {Math.round(center + tx[tri.a] * scale), Math.round(center + tx[tri.b] * scale), Math.round(center + tx[tri.c] * scale)};
            int[] ys = {Math.round(center - ty[tri.a] * scale), Math.round(center - ty[tri.b] * scale), Math.round(center - ty[tri.c] * scale)};
            int gray = Math.max(0, Math.min(255, Math.round(tri.shade * 255f)));
            Polygon poly = new Polygon(xs, ys, 3);
            g.setColor(new Color(gray, gray, gray));
            g.fillPolygon(poly);
            g.setColor(new Color(35, 39, 45, 105));
            g.drawPolygon(poly);
        }

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
        g.drawString(title, 18, 28);
        SculptMesh.Metrics m = mesh.metrics();
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        g.drawString("v=" + (mesh.positions.length / 3) + "  t=" + (mesh.indices.length / 3), 18, 49);
        g.drawString(String.format(Locale.US, "edge %.4f..%.4f  qmin %.3f", m.minEdge, m.maxEdge, m.minQuality), 18, 67);
        g.dispose();
        return image;
    }

    private static BufferedImage contactSheet(List<BufferedImage> images, List<String> labels) {
        int cols = 2, cellW = 360, cellH = 392;
        int rows = (images.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * cellW, rows * cellH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(12, 14, 17));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        for (int i = 0; i < images.size(); i++) {
            int col = i % cols, row = i / cols;
            int x = col * cellW, y = row * cellH;
            g.drawImage(images.get(i), x, y, cellW, cellW, null);
            g.setColor(Color.WHITE);
            g.drawString(labels.get(i), x + 10, y + cellW + 21);
        }
        g.dispose();
        return sheet;
    }

    private static void writeDump(File outDir, boolean pass, String failure, long durationMs,
            List<ScenarioResult> results) throws Exception {
        try (FileWriter w = new FileWriter(new File(outDir, "dump.json"))) {
            w.write("{\n");
            w.write("  \"schema\": \"cabrush-vss-dump-v1\",\n");
            w.write("  \"generated_at\": \"" + escape(Instant.now().toString()) + "\",\n");
            w.write("  \"result\": \"" + (pass ? "PASS" : "FAIL") + "\",\n");
            w.write("  \"duration_ms\": " + durationMs + ",\n");
            w.write("  \"failure\": \"" + escape(failure) + "\",\n");
            w.write("  \"sphere\": {\"vertices\": 2562, \"triangles\": 5120},\n");
            w.write("  \"tests\": {\"fixed_topology\": true, \"only_brushes\": [\"ADD\", \"SUBTRACT\"], \"screenshots_required\": true, \"triangle_quality_floor\": 0.08},\n");
            w.write("  \"scenarios\": [\n");
            for (int i = 0; i < results.size(); i++) {
                ScenarioResult r = results.get(i);
                w.write("    {\n");
                w.write("      \"name\": \"" + escape(r.name) + "\",\n");
                w.write("      \"requested\": " + r.requested + ", \"accepted\": " + r.accepted + ", \"rejected\": " + r.rejected + ",\n");
                w.write("      \"duration_ms\": " + r.durationMs + ", \"healthy\": " + r.healthy + ",\n");
                w.write("      \"position_sha256\": \"" + escape(r.sha256) + "\",\n");
                w.write("      \"screenshot\": \"" + escape(r.screenshot == null ? "" : r.screenshot.getName()) + "\",\n");
                if (r.metrics != null) {
                    w.write(String.format(Locale.US,
                            "      \"metrics\": {\"min_edge\": %.8f, \"max_edge\": %.8f, \"min_area2\": %.8f, \"min_quality\": %.8f, \"max_radius\": %.8f},\n",
                            r.metrics.minEdge, r.metrics.maxEdge, r.metrics.minArea2, r.metrics.minQuality, r.metrics.maxRadius));
                } else {
                    w.write("      \"metrics\": null,\n");
                }
                w.write("      \"note\": \"" + escape(r.note) + "\"\n");
                w.write("    }" + (i + 1 < results.size() ? "," : "") + "\n");
            }
            w.write("  ]\n}\n");
        }
    }

    private static void writeTextDump(File outDir, boolean pass, String failure, long durationMs,
            List<ScenarioResult> results) throws Exception {
        try (FileWriter w = new FileWriter(new File(outDir, "dump.txt"))) {
            w.write("CABrush Verification Snapshot System (VSS)\n");
            w.write("RESULT: " + (pass ? "PASS" : "FAIL") + "\n");
            w.write("Duration: " + durationMs + " ms\n");
            if (!failure.isEmpty()) w.write("Failure: " + failure + "\n");
            for (ScenarioResult r : results) {
                w.write("\n[" + r.name + "]\n");
                w.write("requested=" + r.requested + " accepted=" + r.accepted + " rejected=" + r.rejected + "\n");
                w.write("healthy=" + r.healthy + " sha256=" + r.sha256 + "\n");
                if (r.metrics != null) {
                    w.write(String.format(Locale.US,
                            "minEdge=%.6f maxEdge=%.6f minArea2=%.6f minQuality=%.6f maxRadius=%.6f\n",
                            r.metrics.minEdge, r.metrics.maxEdge, r.metrics.minArea2, r.metrics.minQuality, r.metrics.maxRadius));
                }
                if (!r.note.isEmpty()) w.write("note=" + r.note + "\n");
            }
        }
    }

    private static String hashPositions(float[] positions) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : positions) {
            buffer.clear();
            buffer.putFloat(f);
            md.update(buffer.array());
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
