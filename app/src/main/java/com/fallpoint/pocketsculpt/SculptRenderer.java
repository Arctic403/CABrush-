package com.fallpoint.pocketsculpt;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Android GL shell. CPU mesh truth is owned by MeshKernel; the renderer consumes
 * 16-bit RenderChunkBuilder plans and can recreate all GPU state after EGL loss.
 */
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
    private GpuChunk[] gpuChunks = new GpuChunk[0];
    private long uploadedTopologyVersion = Long.MIN_VALUE;
    private long uploadedGeometryVersion = Long.MIN_VALUE;

    private int program;
    private int aPosition;
    private int aNormal;
    private int uMvp;
    private int uLightDirection;

    private int width = 1;
    private int height = 1;

    private volatile BrushMode brushMode = BrushMode.ADD;
    private volatile float brushRadius = 0.30f;
    private volatile float brushStrength = 0.020f;

    private float yaw = 22f;
    private float pitch = 8f;
    private float cameraDistance = 4.0f;

    private boolean strokeActive;
    private boolean hasLastDab;
    private float lastDabX;
    private float lastDabY;
    private float accumulatedStrokeDistance;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES30.glClearColor(0.075f, 0.086f, 0.102f, 1f);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES30.glGetAttribLocation(program, "aPosition");
        aNormal = GLES30.glGetAttribLocation(program, "aNormal");
        uMvp = GLES30.glGetUniformLocation(program, "uMvp");
        uLightDirection = GLES30.glGetUniformLocation(program, "uLightDirection");

        if (mesh == null) mesh = SculptMesh.createSphere();
        Matrix.setIdentityM(model, 0);

        // EGL recreation must never reset CPU sculpt state.
        uploadedTopologyVersion = Long.MIN_VALUE;
        uploadedGeometryVersion = Long.MIN_VALUE;
        gpuChunks = new GpuChunk[0];
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        GLES30.glViewport(0, 0, this.width, this.height);
        Matrix.perspectiveM(projection, 0, 42f, (float)this.width / this.height, 0.1f, 100f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (mesh == null) return;
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        updateMatrices();

        RenderChunkBuilder.RenderPlan plan = mesh.renderPlan();
        syncGpuChunks(plan);

        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES30.glUniform3f(uLightDirection, -0.35f, 0.72f, 0.58f);

        GLES30.glEnableVertexAttribArray(aPosition);
        GLES30.glEnableVertexAttribArray(aNormal);
        for (GpuChunk chunk : gpuChunks) {
            chunk.positionBuffer.position(0);
            chunk.normalBuffer.position(0);
            chunk.indexBuffer.position(0);
            GLES30.glVertexAttribPointer(aPosition, 3, GLES30.GL_FLOAT, false, 0, chunk.positionBuffer);
            GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, 0, chunk.normalBuffer);
            GLES30.glDrawElements(
                    GLES30.GL_TRIANGLES,
                    chunk.indexCount,
                    GLES30.GL_UNSIGNED_SHORT,
                    chunk.indexBuffer
            );
        }
        GLES30.glDisableVertexAttribArray(aPosition);
        GLES30.glDisableVertexAttribArray(aNormal);
    }

    public void setBrushMode(BrushMode mode) {
        if (mode != null) brushMode = mode;
    }

    public void setBrushRadius(float radius) {
        brushRadius = clamp(radius, 0.05f, 0.70f);
    }

    public void setBrushStrength(float strength) {
        brushStrength = clamp(strength, 0.001f, 0.060f);
    }

    public void resetMesh() {
        if (mesh == null) return;
        mesh.reset();
        strokeActive = false;
        hasLastDab = false;
        accumulatedStrokeDistance = 0f;
        uploadedTopologyVersion = Long.MIN_VALUE;
        uploadedGeometryVersion = Long.MIN_VALUE;
    }

    public void beginStroke() {
        if (mesh == null) return;
        strokeActive = true;
        hasLastDab = false;
        accumulatedStrokeDistance = 0f;
    }

    public void endStroke() {
        strokeActive = false;
        hasLastDab = false;
        accumulatedStrokeDistance = 0f;
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
        float distance = (float)Math.sqrt(dx * dx + dy * dy);
        float spacing = brushSpacingPixels();
        if (distance < spacing) return;

        int steps = Math.min(48, Math.max(1, (int)(distance / spacing)));
        float startX = lastDabX;
        float startY = lastDabY;
        for (int i = 1; i <= steps; i++) {
            float travel = Math.min(distance, spacing * i);
            float t = travel / Math.max(distance, 1e-6f);
            accumulatedStrokeDistance += spacing;
            sculptDab(startX + dx * t, startY + dy * t);
        }

        float consumed = Math.min(distance, spacing * steps);
        float t = consumed / Math.max(distance, 1e-6f);
        lastDabX = startX + dx * t;
        lastDabY = startY + dy * t;
    }

    public void orbit(float dxPixels, float dyPixels) {
        yaw += dxPixels * 0.22f;
        pitch = clamp(pitch + dyPixels * 0.22f, -82f, 82f);
    }

    public void zoom(float scaleFactor) {
        if (!Float.isFinite(scaleFactor) || scaleFactor <= 0f) return;
        cameraDistance = clamp(cameraDistance / scaleFactor, 2.0f, 8.0f);
    }

    private boolean sculptDab(float screenX, float screenY) {
        updateMatrices();
        Ray ray = screenRay(screenX, screenY);
        if (ray == null) return false;

        SculptMesh.Hit hit = mesh.raycast(ray.origin, ray.direction);
        if (hit == null) return false;

        return mesh.applyClay(
                hit.x, hit.y, hit.z,
                hit.nx, hit.ny, hit.nz,
                ray.direction,
                brushRadius,
                brushStrength,
                brushMode,
                accumulatedStrokeDistance,
                hit.faceId
        );
    }

    private float brushSpacingPixels() {
        float projected = brushRadius * height / Math.max(1.5f, cameraDistance);
        return clamp(projected * 0.18f, 3.0f, 28.0f);
    }

    private void syncGpuChunks(RenderChunkBuilder.RenderPlan plan) {
        if (uploadedTopologyVersion != plan.topologyVersion || gpuChunks.length != plan.chunks.length) {
            gpuChunks = new GpuChunk[plan.chunks.length];
            for (int i = 0; i < plan.chunks.length; i++) {
                RenderChunkBuilder.Chunk source = plan.chunks[i];
                gpuChunks[i] = new GpuChunk(source);
            }
            uploadedTopologyVersion = plan.topologyVersion;
            uploadedGeometryVersion = plan.geometryVersion;
            return;
        }

        if (uploadedGeometryVersion != plan.geometryVersion) {
            for (int i = 0; i < gpuChunks.length; i++) {
                gpuChunks[i].uploadGeometry(plan.chunks[i]);
            }
            uploadedGeometryVersion = plan.geometryVersion;
        }
    }

    private void updateMatrices() {
        float yawRad = (float)Math.toRadians(yaw);
        float pitchRad = (float)Math.toRadians(pitch);
        float cosPitch = (float)Math.cos(pitchRad);
        float eyeX = cameraDistance * cosPitch * (float)Math.sin(yawRad);
        float eyeY = cameraDistance * (float)Math.sin(pitchRad);
        float eyeZ = cameraDistance * cosPitch * (float)Math.cos(yawRad);

        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0);
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        Matrix.invertM(inverseMvp, 0, mvp, 0);
    }

    private Ray screenRay(float x, float y) {
        float nx = (2f * x / Math.max(1, width)) - 1f;
        float ny = 1f - (2f * y / Math.max(1, height));

        if (!unprojectInto(nx, ny, -1f, rayNear)) return null;
        if (!unprojectInto(nx, ny, 1f, rayFar)) return null;

        float dx = rayFar[0] - rayNear[0];
        float dy = rayFar[1] - rayNear[1];
        float dz = rayFar[2] - rayNear[2];
        float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-8f) return null;

        rayScratch.origin[0] = rayNear[0];
        rayScratch.origin[1] = rayNear[1];
        rayScratch.origin[2] = rayNear[2];
        rayScratch.direction[0] = dx / len;
        rayScratch.direction[1] = dy / len;
        rayScratch.direction[2] = dz / len;
        return rayScratch;
    }

    private boolean unprojectInto(float x, float y, float z, float[] dst) {
        unprojectIn[0] = x;
        unprojectIn[1] = y;
        unprojectIn[2] = z;
        unprojectIn[3] = 1f;
        Matrix.multiplyMV(unprojectOut, 0, inverseMvp, 0, unprojectIn, 0);
        if (Math.abs(unprojectOut[3]) < 1e-8f || !Float.isFinite(unprojectOut[3])) return false;
        float invW = 1f / unprojectOut[3];
        dst[0] = unprojectOut[0] * invW;
        dst[1] = unprojectOut[1] * invW;
        dst[2] = unprojectOut[2] * invW;
        return Float.isFinite(dst[0]) && Float.isFinite(dst[1]) && Float.isFinite(dst[2]);
    }

    private static int buildProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertex);
        GLES30.glAttachShader(program, fragment);
        GLES30.glLinkProgram(program);

        int[] status = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(program);
            GLES30.glDeleteProgram(program);
            throw new IllegalStateException("GL program link failed: " + log);
        }
        GLES30.glDeleteShader(vertex);
        GLES30.glDeleteShader(fragment);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException("GL shader compile failed: " + log);
        }
        return shader;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class GpuChunk {
        final FloatBuffer positionBuffer;
        final FloatBuffer normalBuffer;
        final ShortBuffer indexBuffer;
        final int indexCount;

        GpuChunk(RenderChunkBuilder.Chunk source) {
            positionBuffer = ByteBuffer.allocateDirect(source.positions.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            normalBuffer = ByteBuffer.allocateDirect(source.normals.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            indexBuffer = ByteBuffer.allocateDirect(source.indices.length * 2)
                    .order(ByteOrder.nativeOrder()).asShortBuffer();
            indexBuffer.put(source.indices).position(0);
            indexCount = source.indices.length;
            uploadGeometry(source);
        }

        void uploadGeometry(RenderChunkBuilder.Chunk source) {
            positionBuffer.position(0);
            positionBuffer.put(source.positions).position(0);
            normalBuffer.position(0);
            normalBuffer.put(source.normals).position(0);
        }
    }

    private static final class Ray {
        final float[] origin = new float[3];
        final float[] direction = new float[3];
    }

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "uniform mat4 uMvp;\n" +
            "in vec3 aPosition;\n" +
            "in vec3 aNormal;\n" +
            "out vec3 vNormal;\n" +
            "void main(){\n" +
            "  vNormal = aNormal;\n" +
            "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "uniform vec3 uLightDirection;\n" +
            "in vec3 vNormal;\n" +
            "out vec4 fragColor;\n" +
            "void main(){\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  float diffuse = max(dot(n, normalize(uLightDirection)), 0.0);\n" +
            "  float rim = pow(1.0 - abs(n.z), 2.0) * 0.12;\n" +
            "  float shade = 0.25 + diffuse * 0.68 + rim;\n" +
            "  fragColor = vec4(vec3(shade), 1.0);\n" +
            "}\n";
}
