package com.fallpoint.pocketsculpt;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class SculptRenderer implements GLSurfaceView.Renderer {
    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] vp = new float[16];
    private final float[] mvp = new float[16];
    private final float[] inverseMvp = new float[16];

    private SculptMesh mesh;
    private FloatBuffer positionBuffer;
    private FloatBuffer normalBuffer;
    private IntBuffer indexBuffer;

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

    private boolean buffersDirty = true;
    private boolean strokeActive = false;
    private boolean hasLastDab = false;
    private float lastDabX;
    private float lastDabY;

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
        allocateBuffers();
        Matrix.setIdentityM(model, 0);
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
        if (buffersDirty) uploadMesh();

        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES30.glUniform3f(uLightDirection, -0.35f, 0.72f, 0.58f);

        positionBuffer.position(0);
        normalBuffer.position(0);
        indexBuffer.position(0);

        GLES30.glEnableVertexAttribArray(aPosition);
        GLES30.glVertexAttribPointer(aPosition, 3, GLES30.GL_FLOAT, false, 0, positionBuffer);
        GLES30.glEnableVertexAttribArray(aNormal);
        GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, 0, normalBuffer);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indices.length, GLES30.GL_UNSIGNED_INT, indexBuffer);
        GLES30.glDisableVertexAttribArray(aPosition);
        GLES30.glDisableVertexAttribArray(aNormal);
    }

    public void setBrushMode(BrushMode mode) {
        if (mode != null) brushMode = mode;
    }

    public void setBrushRadius(float radius) {
        brushRadius = Math.max(0.05f, Math.min(0.70f, radius));
    }

    public void setBrushStrength(float strength) {
        brushStrength = Math.max(0.001f, Math.min(0.060f, strength));
    }

    public void resetMesh() {
        if (mesh == null) return;
        mesh.reset();
        buffersDirty = true;
        strokeActive = false;
        hasLastDab = false;
    }

    public void beginStroke() {
        if (mesh == null) return;
        strokeActive = true;
        hasLastDab = false;
    }

    public void endStroke() {
        strokeActive = false;
        hasLastDab = false;
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

        boolean changed = mesh.applyClay(
                hit.x, hit.y, hit.z,
                hit.nx, hit.ny, hit.nz,
                ray.direction,
                brushRadius,
                brushStrength,
                brushMode
        );
        if (changed) buffersDirty = true;
        return changed;
    }

    private float brushSpacingPixels() {
        float projected = brushRadius * height / Math.max(1.5f, cameraDistance);
        return clamp(projected * 0.18f, 3.0f, 28.0f);
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

        float[] near = unproject(nx, ny, -1f);
        float[] far = unproject(nx, ny, 1f);
        if (near == null || far == null) return null;

        float dx = far[0] - near[0];
        float dy = far[1] - near[1];
        float dz = far[2] - near[2];
        float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-8f) return null;

        return new Ray(near, new float[]{dx / len, dy / len, dz / len});
    }

    private float[] unproject(float x, float y, float z) {
        float[] in = {x, y, z, 1f};
        float[] out = new float[4];
        Matrix.multiplyMV(out, 0, inverseMvp, 0, in, 0);
        if (Math.abs(out[3]) < 1e-8f || !Float.isFinite(out[3])) return null;
        float invW = 1f / out[3];
        return new float[]{out[0] * invW, out[1] * invW, out[2] * invW};
    }

    private void allocateBuffers() {
        if (mesh == null) return;
        positionBuffer = ByteBuffer.allocateDirect(mesh.positions.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        normalBuffer = ByteBuffer.allocateDirect(mesh.normals.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        indexBuffer = ByteBuffer.allocateDirect(mesh.indices.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        indexBuffer.put(mesh.indices).position(0);
        buffersDirty = true;
    }

    private void uploadMesh() {
        positionBuffer.position(0);
        positionBuffer.put(mesh.positions).position(0);
        normalBuffer.position(0);
        normalBuffer.put(mesh.normals).position(0);
        buffersDirty = false;
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

    private static final class Ray {
        final float[] origin;
        final float[] direction;
        Ray(float[] origin, float[] direction) {
            this.origin = origin;
            this.direction = direction;
        }
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
