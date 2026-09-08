package com.fallpoint.pocketsculpt;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Android GL shell for CABrush AVS. CPU sculpt truth lives only in AvsVolume. */
public final class SculptRenderer implements GLSurfaceView.Renderer {
    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] vp = new float[16];
    private final float[] mvp = new float[16];
    private final float[] inverseMvp = new float[16];
    private final float[] unprojectIn = new float[4];
    private final float[] unprojectOut = new float[4];
    private final float[] rayNear = new float[3];
    private final float[] rayFar = new float[3];
    private final Ray rayScratch = new Ray();

    private SculptMesh mesh;
    private GpuChunk[] gpuByBrick = new GpuChunk[256];

    private int program;
    private int aPosition;
    private int aNormal;
    private int uMvp;
    private int uLightDirection;

    private int width = 1;
    private int height = 1;

    private BrushMode brushMode = BrushMode.ADD;
    private float brushRadius = 0.30f;
    private float brushStrength = 0.020f;

    private float yaw = 22f;
    private float pitch = 8f;
    private float cameraDistance = 4.0f;

    private boolean strokeActive;
    private boolean hasLastDab;
    private float lastDabX, lastDabY;

    // Deep diagnostics state. It is inert when EngineDiagnostics is disabled.
    private long frameNumber;
    private long lastFrameStartNanos;
    private long frameBatchRenderNanos;
    private long frameBatchIntervalNanos;
    private long frameBatchMaxRenderNanos;
    private long frameBatchMaxIntervalNanos;
    private int frameBatchCount;

    private long strokeId;
    private long strokeStartNanos;
    private int strokeDabCount;
    private int strokeChangedDabs;
    private int strokeRayMisses;
    private boolean strokeHasFirstHit;
    private float firstHitX, firstHitY, firstHitZ;
    private float firstHitNx, firstHitNy, firstHitNz;
    private float previousHitX, previousHitY, previousHitZ, previousHitT;
    private float previousHitScreenX, previousHitScreenY;
    private boolean hasPreviousHit;
    private float strokeMaxNormalExcursion;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        long start = EngineDiagnostics.nowNanos();
        GLES30.glClearColor(0.075f, 0.086f, 0.102f, 1f);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        // AVS Surface Truth contract: extractor emits outward CCW faces.
        GLES30.glFrontFace(GLES30.GL_CCW);
        GLES30.glCullFace(GLES30.GL_BACK);

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES30.glGetAttribLocation(program, "aPosition");
        aNormal = GLES30.glGetAttribLocation(program, "aNormal");
        uMvp = GLES30.glGetUniformLocation(program, "uMvp");
        uLightDirection = GLES30.glGetUniformLocation(program, "uLightDirection");

        if (mesh == null) mesh = SculptMesh.createSphere();
        Matrix.setIdentityM(model, 0);
        gpuByBrick = new GpuChunk[Math.max(256, mesh.volume.brickCount() + 64)];

        if (EngineDiagnostics.isEnabled()) {
            String vendor = String.valueOf(GLES30.glGetString(GLES30.GL_VENDOR));
            String renderer = String.valueOf(GLES30.glGetString(GLES30.GL_RENDERER));
            String version = String.valueOf(GLES30.glGetString(GLES30.GL_VERSION));
            String shading = String.valueOf(GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION));
            EngineDiagnostics.state("gl.vendor", vendor);
            EngineDiagnostics.state("gl.renderer", renderer);
            EngineDiagnostics.state("gl.version", version);
            EngineDiagnostics.state("gl.shading_language", shading);
            EngineDiagnostics.state("gl.extensions",
                    String.valueOf(GLES30.glGetString(GLES30.GL_EXTENSIONS)));
            int[] glValue = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, glValue, 0);
            EngineDiagnostics.state("gl.max_texture_size", String.valueOf(glValue[0]));
            GLES30.glGetIntegerv(GLES30.GL_MAX_VERTEX_ATTRIBS, glValue, 0);
            EngineDiagnostics.state("gl.max_vertex_attribs", String.valueOf(glValue[0]));
            GLES30.glGetIntegerv(GLES30.GL_MAX_VERTEX_UNIFORM_VECTORS, glValue, 0);
            EngineDiagnostics.state("gl.max_vertex_uniform_vectors", String.valueOf(glValue[0]));
            GLES30.glGetIntegerv(GLES30.GL_MAX_FRAGMENT_UNIFORM_VECTORS, glValue, 0);
            EngineDiagnostics.state("gl.max_fragment_uniform_vectors", String.valueOf(glValue[0]));
            EngineDiagnostics.state("renderer.front_face", "CCW");
            EngineDiagnostics.state("renderer.cull_face", "BACK");
            EngineDiagnostics.record("renderer", "surface_created",
                    "vendor=" + vendor + " renderer=" + renderer
                            + " version=" + version + " glsl=" + shading);
            EngineDiagnostics.timed("renderer", "surface_create", start,
                    "bricks=" + mesh.volume.brickCount());
            checkGlErrors("surface_created");
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        GLES30.glViewport(0, 0, this.width, this.height);
        Matrix.perspectiveM(projection, 0, 42f, (float)this.width / this.height, 0.1f, 100f);
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.state("renderer.viewport", this.width + "x" + this.height);
            EngineDiagnostics.record("renderer", "surface_changed",
                    "width=" + this.width + " height=" + this.height
                            + " aspect=" + ((float)this.width / this.height));
            checkGlErrors("surface_changed");
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (mesh == null) return;
        long frameStart = EngineDiagnostics.nowNanos();
        long intervalNs = 0L;
        if (EngineDiagnostics.isEnabled() && lastFrameStartNanos != 0L) {
            intervalNs = frameStart - lastFrameStartNanos;
        }
        if (EngineDiagnostics.isEnabled()) lastFrameStartNanos = frameStart;

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        updateMatrices();

        AvsSurfaceCache.RenderPlan plan = mesh.renderPlan();
        syncGpuChunks(plan);

        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES30.glUniform3f(uLightDirection, -0.35f, 0.72f, 0.58f);
        GLES30.glEnableVertexAttribArray(aPosition);
        GLES30.glEnableVertexAttribArray(aNormal);

        int drawCalls = 0;
        int indicesDrawn = 0;
        for (AvsSurfaceCache.Chunk source : plan.chunks) {
            GpuChunk chunk = gpuByBrick[source.brickId];
            if (chunk == null || chunk.indexCount == 0) continue;
            chunk.positionBuffer.position(0);
            chunk.normalBuffer.position(0);
            chunk.indexBuffer.position(0);
            GLES30.glVertexAttribPointer(aPosition, 3, GLES30.GL_FLOAT, false, 0, chunk.positionBuffer);
            GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, 0, chunk.normalBuffer);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, chunk.indexCount, GLES30.GL_UNSIGNED_SHORT, chunk.indexBuffer);
            drawCalls++;
            indicesDrawn += chunk.indexCount;
        }

        GLES30.glDisableVertexAttribArray(aPosition);
        GLES30.glDisableVertexAttribArray(aNormal);

        if (EngineDiagnostics.isEnabled()) {
            long renderNs = System.nanoTime() - frameStart;
            frameNumber++;
            frameBatchCount++;
            frameBatchRenderNanos += renderNs;
            frameBatchIntervalNanos += intervalNs;
            frameBatchMaxRenderNanos = Math.max(frameBatchMaxRenderNanos, renderNs);
            frameBatchMaxIntervalNanos = Math.max(frameBatchMaxIntervalNanos, intervalNs);

            EngineDiagnostics.counter("renderer.frames", 1L);
            EngineDiagnostics.counter("renderer.draw_calls", drawCalls);
            EngineDiagnostics.counter("renderer.indices_drawn", indicesDrawn);
            EngineDiagnostics.gauge("renderer.last_frame_render_ms", renderNs / 1_000_000.0);
            if (intervalNs > 0L) {
                EngineDiagnostics.gauge("renderer.last_frame_interval_ms", intervalNs / 1_000_000.0);
            }

            if (frameBatchCount >= 30) {
                double avgRenderMs = frameBatchRenderNanos / (double) frameBatchCount / 1_000_000.0;
                double avgIntervalMs = frameBatchIntervalNanos / (double) frameBatchCount / 1_000_000.0;
                EngineDiagnostics.gauge("renderer.avg_render_ms_30", avgRenderMs);
                EngineDiagnostics.gauge("renderer.avg_interval_ms_30", avgIntervalMs);
                EngineDiagnostics.gauge("renderer.max_render_ms_30",
                        frameBatchMaxRenderNanos / 1_000_000.0);
                EngineDiagnostics.gauge("renderer.max_interval_ms_30",
                        frameBatchMaxIntervalNanos / 1_000_000.0);
                EngineDiagnostics.record("renderer", "frame_batch",
                        "frame=" + frameNumber
                                + " count=" + frameBatchCount
                                + " avg_render_ms=" + avgRenderMs
                                + " max_render_ms=" + (frameBatchMaxRenderNanos / 1_000_000.0)
                                + " avg_interval_ms=" + avgIntervalMs
                                + " max_interval_ms=" + (frameBatchMaxIntervalNanos / 1_000_000.0)
                                + " draw_calls=" + drawCalls
                                + " chunks=" + plan.chunks.length
                                + " triangles=" + plan.totalTriangles);
                frameBatchCount = 0;
                frameBatchRenderNanos = 0L;
                frameBatchIntervalNanos = 0L;
                frameBatchMaxRenderNanos = 0L;
                frameBatchMaxIntervalNanos = 0L;
                checkGlErrors("frame_batch");
            }

            if (frameNumber > 5 && intervalNs > 250_000_000L) {
                EngineDiagnostics.anomaly("frame_stall",
                        "frame=" + frameNumber
                                + " interval_ns=" + intervalNs
                                + " render_ns=" + renderNs
                                + " dirty=" + plan.dirtyBefore
                                + " rebuilt=" + plan.rebuiltChunks
                                + " triangles=" + plan.totalTriangles);
            }

            if ((frameNumber % 120L) == 0L) {
                Runtime rt = Runtime.getRuntime();
                EngineDiagnostics.gauge("memory.java_used_mb",
                        (rt.totalMemory() - rt.freeMemory()) / 1048576.0);
                EngineDiagnostics.gauge("memory.java_total_mb",
                        rt.totalMemory() / 1048576.0);
            }
        }
    }

    public void setBrushMode(BrushMode mode) {
        if (mode != null) brushMode = mode;
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.state("brush.mode", String.valueOf(brushMode));
            EngineDiagnostics.record("brush", "mode_changed", "mode=" + brushMode);
        }
    }

    public void setBrushRadius(float radius) {
        float requested = radius;
        brushRadius = clamp(radius, AvsVolume.VOXEL_SIZE * 1.5f, 0.70f);
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.state("brush.radius", String.valueOf(brushRadius));
            EngineDiagnostics.record("brush", "radius_changed",
                    "requested=" + requested + " effective=" + brushRadius);
        }
    }

    public void setBrushStrength(float strength) {
        float requested = strength;
        brushStrength = clamp(strength, 0.001f, 0.060f);
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.state("brush.strength", String.valueOf(brushStrength));
            EngineDiagnostics.record("brush", "strength_changed",
                    "requested=" + requested + " effective=" + brushStrength);
        }
    }

    public void resetMesh() {
        if (mesh == null) return;
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.record("mesh", "reset_requested",
                    "field_hash_before=" + mesh.fieldHash());
        }
        mesh.reset();
        strokeActive = false;
        hasLastDab = false;
        Arrays.fill(gpuByBrick, null);
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.record("mesh", "reset_complete",
                    "field_hash_after=" + mesh.fieldHash()
                            + " bricks=" + mesh.stats().bricks);
        }
    }

    public void beginStroke() {
        strokeActive = mesh != null;
        hasLastDab = false;
        hasPreviousHit = false;
        strokeHasFirstHit = false;
        strokeDabCount = 0;
        strokeChangedDabs = 0;
        strokeRayMisses = 0;
        strokeMaxNormalExcursion = 0f;
        if (EngineDiagnostics.isEnabled()) {
            strokeId++;
            strokeStartNanos = System.nanoTime();
            String hash = mesh == null ? "" : mesh.fieldHash();
            EngineDiagnostics.state("stroke.active_id", String.valueOf(strokeId));
            EngineDiagnostics.state("stroke.start_field_hash", hash);
            EngineDiagnostics.record("stroke", "begin",
                    "id=" + strokeId
                            + " mode=" + brushMode
                            + " radius=" + brushRadius
                            + " strength=" + brushStrength
                            + " field_hash=" + hash
                            + " yaw=" + yaw + " pitch=" + pitch
                            + " camera_distance=" + cameraDistance);
        }
    }

    public void endStroke() {
        if (EngineDiagnostics.isEnabled() && strokeActive && mesh != null) {
            long durationNs = strokeStartNanos == 0L ? 0L : System.nanoTime() - strokeStartNanos;
            AvsVolume.Stats stats = mesh.stats();
            String hash = mesh.fieldHash();
            EngineDiagnostics.state("stroke.end_field_hash", hash);
            EngineDiagnostics.state("avs.brick_count", String.valueOf(stats.bricks));
            EngineDiagnostics.record("stroke", "end",
                    "id=" + strokeId
                            + " dabs=" + strokeDabCount
                            + " changed_dabs=" + strokeChangedDabs
                            + " ray_misses=" + strokeRayMisses
                            + " max_normal_excursion=" + strokeMaxNormalExcursion
                            + " duration_ns=" + durationNs
                            + " bricks=" + stats.bricks
                            + " negative_samples=" + stats.negativeSamples
                            + " field_hash=" + hash);
            EngineDiagnostics.gauge("stroke.last_duration_ms", durationNs / 1_000_000.0);
            EngineDiagnostics.gauge("stroke.last_dabs", strokeDabCount);
        }
        strokeActive = false;
        hasLastDab = false;
        hasPreviousHit = false;
    }

    public void sculptAt(float screenX, float screenY) {
        if (mesh == null || !strokeActive) return;
        if (!hasLastDab) {
            if (sculptDab(screenX, screenY)) {
                lastDabX = screenX;
                lastDabY = screenY;
                hasLastDab = true;
            }
            return;
        }

        float dx = screenX - lastDabX;
        float dy = screenY - lastDabY;
        float distance = (float)Math.sqrt(dx*dx + dy*dy);
        float spacing = brushSpacingPixels();
        if (distance < spacing) {
            if (EngineDiagnostics.isEnabled()) {
                EngineDiagnostics.counter("stroke.samples_below_spacing", 1L);
            }
            return;
        }

        int steps = Math.min(48, Math.max(1, (int)(distance / spacing)));
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.record("stroke", "resample_segment",
                    "id=" + strokeId
                            + " from=" + lastDabX + "," + lastDabY
                            + " to=" + screenX + "," + screenY
                            + " distance_px=" + distance
                            + " spacing_px=" + spacing
                            + " steps=" + steps);
            if (steps == 48) {
                EngineDiagnostics.anomaly("stroke_resample_cap",
                        "distance_px=" + distance + " spacing_px=" + spacing);
            }
        }

        float sx = lastDabX, sy = lastDabY;
        for (int i=1;i<=steps;i++) {
            float travel = Math.min(distance, spacing * i);
            float t = travel / Math.max(distance, 1e-6f);
            sculptDab(sx + dx*t, sy + dy*t);
        }
        float consumed = Math.min(distance, spacing * steps);
        float t = consumed / Math.max(distance, 1e-6f);
        lastDabX = sx + dx*t;
        lastDabY = sy + dy*t;
    }

    public void orbit(float dxPixels, float dyPixels) {
        yaw += dxPixels * 0.22f;
        pitch = clamp(pitch + dyPixels * 0.22f, -82f, 82f);
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.state("camera.yaw", String.valueOf(yaw));
            EngineDiagnostics.state("camera.pitch", String.valueOf(pitch));
            EngineDiagnostics.record("camera", "orbit",
                    "dx_px=" + dxPixels + " dy_px=" + dyPixels
                            + " yaw=" + yaw + " pitch=" + pitch);
        }
    }

    public void zoom(float scaleFactor) {
        if (!Float.isFinite(scaleFactor) || scaleFactor <= 0f) return;
        float before = cameraDistance;
        cameraDistance = clamp(cameraDistance / scaleFactor, 2.0f, 10.0f);
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.state("camera.distance", String.valueOf(cameraDistance));
            EngineDiagnostics.record("camera", "zoom",
                    "scale=" + scaleFactor
                            + " before=" + before
                            + " after=" + cameraDistance);
        }
    }

    private boolean sculptDab(float sx, float sy) {
        long dabStart = EngineDiagnostics.nowNanos();
        updateMatrices();
        Ray ray = screenRay(sx, sy);
        if (ray == null) {
            strokeRayMisses++;
            if (EngineDiagnostics.isEnabled()) {
                EngineDiagnostics.record("stroke", "dab_no_ray",
                        "id=" + strokeId + " screen=" + sx + "," + sy);
            }
            return false;
        }

        SculptMesh.Hit hit = mesh.raycast(ray.origin, ray.direction);
        if (hit == null) {
            strokeRayMisses++;
            if (EngineDiagnostics.isEnabled()) {
                EngineDiagnostics.record("stroke", "dab_ray_miss",
                        "id=" + strokeId
                                + " screen=" + sx + "," + sy
                                + " origin=" + ray.origin[0] + "," + ray.origin[1] + "," + ray.origin[2]
                                + " dir=" + ray.direction[0] + "," + ray.direction[1] + "," + ray.direction[2]);
            }
            return false;
        }

        AvsVolume.BrushResult result = mesh.applyClay(
                hit.x, hit.y, hit.z,
                hit.nx, hit.ny, hit.nz,
                brushRadius, brushStrength, brushMode
        );
        strokeDabCount++;
        if (result.changed) strokeChangedDabs++;

        if (EngineDiagnostics.isEnabled()) {
            if (!strokeHasFirstHit) {
                strokeHasFirstHit = true;
                firstHitX = hit.x;
                firstHitY = hit.y;
                firstHitZ = hit.z;
                firstHitNx = hit.nx;
                firstHitNy = hit.ny;
                firstHitNz = hit.nz;
            }

            float deltaFirstX = hit.x - firstHitX;
            float deltaFirstY = hit.y - firstHitY;
            float deltaFirstZ = hit.z - firstHitZ;
            float normalExcursion = deltaFirstX * firstHitNx
                    + deltaFirstY * firstHitNy
                    + deltaFirstZ * firstHitNz;
            strokeMaxNormalExcursion = Math.max(strokeMaxNormalExcursion, normalExcursion);

            float hitMove = 0f;
            float screenMove = 0f;
            float tDelta = 0f;
            if (hasPreviousHit) {
                float hx = hit.x - previousHitX;
                float hy = hit.y - previousHitY;
                float hz = hit.z - previousHitZ;
                hitMove = (float)Math.sqrt(hx*hx + hy*hy + hz*hz);
                float spx = sx - previousHitScreenX;
                float spy = sy - previousHitScreenY;
                screenMove = (float)Math.sqrt(spx*spx + spy*spy);
                tDelta = hit.t - previousHitT;
            }

            float firstNormalDot = hit.nx * firstHitNx
                    + hit.ny * firstHitNy
                    + hit.nz * firstHitNz;
            long dabNs = dabStart == 0L ? 0L : System.nanoTime() - dabStart;

            EngineDiagnostics.counter("stroke.dabs", 1L);
            EngineDiagnostics.counter("stroke.changed_dabs", result.changed ? 1L : 0L);
            EngineDiagnostics.gauge("stroke.last_dab_ms", dabNs / 1_000_000.0);
            EngineDiagnostics.gauge("stroke.last_hit_t", hit.t);
            EngineDiagnostics.gauge("stroke.max_normal_excursion", strokeMaxNormalExcursion);
            EngineDiagnostics.state("stroke.last_hit",
                    hit.x + "," + hit.y + "," + hit.z);
            EngineDiagnostics.record("stroke", "dab",
                    "id=" + strokeId
                            + " index=" + strokeDabCount
                            + " screen=" + sx + "," + sy
                            + " ray_o=" + ray.origin[0] + "," + ray.origin[1] + "," + ray.origin[2]
                            + " ray_d=" + ray.direction[0] + "," + ray.direction[1] + "," + ray.direction[2]
                            + " hit=" + hit.x + "," + hit.y + "," + hit.z
                            + " normal=" + hit.nx + "," + hit.ny + "," + hit.nz
                            + " hit_t=" + hit.t
                            + " hit_move=" + hitMove
                            + " screen_move=" + screenMove
                            + " hit_t_delta=" + tDelta
                            + " first_normal_dot=" + firstNormalDot
                            + " normal_excursion=" + normalExcursion
                            + " radius=" + brushRadius
                            + " strength=" + brushStrength
                            + " effective_depth=" + result.effectiveDepth
                            + " changed=" + result.changed
                            + " changed_samples=" + result.changedSamples
                            + " tested_samples=" + result.testedSamples
                            + " touched_bricks=" + result.touchedBricks
                            + " allocated_bricks=" + result.allocatedBricks
                            + " duration_ns=" + dabNs);

            // Diagnostic only: identify the exact class of "clay grows by
            // chasing its newly-created surface" failure without changing the
            // sculpt result. The dump preserves all preceding rays/dabs.
            if (brushMode == BrushMode.ADD
                    && strokeDabCount >= 8
                    && firstNormalDot > 0.78f
                    && normalExcursion > Math.max(brushRadius * 1.50f, 0.30f)) {
                EngineDiagnostics.anomaly("clay_self_extrusion",
                        "stroke=" + strokeId
                                + " dab=" + strokeDabCount
                                + " normal_excursion=" + normalExcursion
                                + " radius=" + brushRadius
                                + " strength=" + brushStrength
                                + " hit_t_delta=" + tDelta
                                + " first_normal_dot=" + firstNormalDot);
            }

            previousHitX = hit.x;
            previousHitY = hit.y;
            previousHitZ = hit.z;
            previousHitT = hit.t;
            previousHitScreenX = sx;
            previousHitScreenY = sy;
            hasPreviousHit = true;
        }
        return result.changed;
    }

    private float brushSpacingPixels() {
        float projected = brushRadius * height / Math.max(1.5f, cameraDistance);
        float spacing = clamp(projected * 0.15f, 2.5f, 24f);
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.gauge("brush.spacing_px", spacing);
            EngineDiagnostics.gauge("brush.projected_radius_px", projected);
        }
        return spacing;
    }

    private void syncGpuChunks(AvsSurfaceCache.RenderPlan plan) {
        int needed = mesh.volume.brickCount();
        if (gpuByBrick.length < needed) {
            int old = gpuByBrick.length;
            int cap = gpuByBrick.length;
            while (cap < needed) cap = cap + cap/2 + 64;
            gpuByBrick = Arrays.copyOf(gpuByBrick, cap);
            if (EngineDiagnostics.isEnabled()) {
                EngineDiagnostics.record("gpu", "chunk_table_grow",
                        "old=" + old + " new=" + cap + " needed=" + needed);
            }
        }

        int uploads = 0;
        long uploadBytes = 0L;
        long start = EngineDiagnostics.nowNanos();
        for (AvsSurfaceCache.Chunk source : plan.chunks) {
            GpuChunk old = gpuByBrick[source.brickId];
            if (old == null || old.revision != source.revision) {
                gpuByBrick[source.brickId] = new GpuChunk(source);
                uploads++;
                uploadBytes += source.estimatedBytes();
            }
        }
        if (EngineDiagnostics.isEnabled() && uploads > 0) {
            long ns = start == 0L ? 0L : System.nanoTime() - start;
            EngineDiagnostics.counter("gpu.chunk_uploads", uploads);
            EngineDiagnostics.counter("gpu.upload_bytes", uploadBytes);
            EngineDiagnostics.gauge("gpu.last_sync_ms", ns / 1_000_000.0);
            EngineDiagnostics.record("gpu", "sync_chunks",
                    "uploads=" + uploads
                            + " bytes=" + uploadBytes
                            + " plan_chunks=" + plan.chunks.length
                            + " duration_ns=" + ns);
            if (ns > 100_000_000L) {
                EngineDiagnostics.anomaly("slow_gpu_sync",
                        "duration_ns=" + ns
                                + " uploads=" + uploads
                                + " bytes=" + uploadBytes);
            }
        }
    }

    private void updateMatrices() {
        float yawRad = (float)Math.toRadians(yaw);
        float pitchRad = (float)Math.toRadians(pitch);
        float cosPitch = (float)Math.cos(pitchRad);
        float eyeX = cameraDistance * cosPitch * (float)Math.sin(yawRad);
        float eyeY = cameraDistance * (float)Math.sin(pitchRad);
        float eyeZ = cameraDistance * cosPitch * (float)Math.cos(yawRad);
        Matrix.setLookAtM(view,0,eyeX,eyeY,eyeZ,0f,0f,0f,0f,1f,0f);
        Matrix.multiplyMM(vp,0,projection,0,view,0);
        Matrix.multiplyMM(mvp,0,vp,0,model,0);
        boolean invert = Matrix.invertM(inverseMvp,0,mvp,0);
        if (EngineDiagnostics.isEnabled() && !invert) {
            EngineDiagnostics.anomaly("matrix_inversion_failed",
                    "yaw=" + yaw + " pitch=" + pitch
                            + " distance=" + cameraDistance
                            + " viewport=" + width + "x" + height);
        }
    }

    private Ray screenRay(float x,float y) {
        float nx=(2f*x/Math.max(1,width))-1f;
        float ny=1f-(2f*y/Math.max(1,height));
        if(!unprojectInto(nx,ny,-1f,rayNear) || !unprojectInto(nx,ny,1f,rayFar)) return null;
        float dx=rayFar[0]-rayNear[0],dy=rayFar[1]-rayNear[1],dz=rayFar[2]-rayNear[2];
        float len=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(len<1e-8f) return null;
        rayScratch.origin[0]=rayNear[0]; rayScratch.origin[1]=rayNear[1]; rayScratch.origin[2]=rayNear[2];
        rayScratch.direction[0]=dx/len; rayScratch.direction[1]=dy/len; rayScratch.direction[2]=dz/len;
        return rayScratch;
    }

    private boolean unprojectInto(float x,float y,float z,float[] dst) {
        unprojectIn[0]=x;unprojectIn[1]=y;unprojectIn[2]=z;unprojectIn[3]=1f;
        Matrix.multiplyMV(unprojectOut,0,inverseMvp,0,unprojectIn,0);
        if(Math.abs(unprojectOut[3])<1e-8f || !Float.isFinite(unprojectOut[3])) return false;
        float iw=1f/unprojectOut[3];
        dst[0]=unprojectOut[0]*iw;dst[1]=unprojectOut[1]*iw;dst[2]=unprojectOut[2]*iw;
        return Float.isFinite(dst[0])&&Float.isFinite(dst[1])&&Float.isFinite(dst[2]);
    }

    private void checkGlErrors(String stage) {
        if (!EngineDiagnostics.isEnabled()) return;
        int count = 0;
        for (int i = 0; i < 8; i++) {
            int error = GLES30.glGetError();
            if (error == GLES30.GL_NO_ERROR) break;
            count++;
            EngineDiagnostics.record("gpu", "gl_error",
                    "stage=" + stage + " code=0x" + Integer.toHexString(error));
        }
        if (count > 0) {
            EngineDiagnostics.anomaly("gl_error",
                    "stage=" + stage + " count=" + count);
        }
    }

    private static int buildProgram(String vs,String fs) {
        int v=compileShader(GLES30.GL_VERTEX_SHADER,vs);
        int f=compileShader(GLES30.GL_FRAGMENT_SHADER,fs);
        int p=GLES30.glCreateProgram();
        GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);
        int[] status=new int[1];GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,status,0);
        if(status[0]==0){String log=GLES30.glGetProgramInfoLog(p);GLES30.glDeleteProgram(p);throw new IllegalStateException("GL link failed: "+log);}
        GLES30.glDeleteShader(v);GLES30.glDeleteShader(f);return p;
    }

    private static int compileShader(int type,String source) {
        int s=GLES30.glCreateShader(type);GLES30.glShaderSource(s,source);GLES30.glCompileShader(s);
        int[] status=new int[1];GLES30.glGetShaderiv(s,GLES30.GL_COMPILE_STATUS,status,0);
        if(status[0]==0){String log=GLES30.glGetShaderInfoLog(s);GLES30.glDeleteShader(s);throw new IllegalStateException("GL shader failed: "+log);}
        return s;
    }

    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}

    private static final class GpuChunk {
        final long revision;
        final FloatBuffer positionBuffer;
        final FloatBuffer normalBuffer;
        final ShortBuffer indexBuffer;
        final int indexCount;

        GpuChunk(AvsSurfaceCache.Chunk src) {
            revision=src.revision;
            positionBuffer=ByteBuffer.allocateDirect(src.positions.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            normalBuffer=ByteBuffer.allocateDirect(src.normals.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            indexBuffer=ByteBuffer.allocateDirect(src.indices.length*2).order(ByteOrder.nativeOrder()).asShortBuffer();
            positionBuffer.put(src.positions).position(0);
            normalBuffer.put(src.normals).position(0);
            indexBuffer.put(src.indices).position(0);
            indexCount=src.indices.length;
        }
    }

    private static final class Ray {
        final float[] origin=new float[3];
        final float[] direction=new float[3];
    }

    private static final String VERTEX_SHADER =
            "#version 300 es\nuniform mat4 uMvp;\nin vec3 aPosition;\nin vec3 aNormal;\nout vec3 vNormal;\n"+
            "void main(){vNormal=aNormal;gl_Position=uMvp*vec4(aPosition,1.0);}\n";
    private static final String FRAGMENT_SHADER =
            "#version 300 es\nprecision mediump float;\nuniform vec3 uLightDirection;\nin vec3 vNormal;\nout vec4 fragColor;\n"+
            "void main(){vec3 n=normalize(vNormal);float d=max(dot(n,normalize(uLightDirection)),0.0);"+
            "float rim=pow(1.0-abs(n.z),2.0)*0.12;float s=0.25+d*0.68+rim;fragColor=vec4(vec3(s),1.0);}\n";
}
