package com.fallpoint.pocketsculpt;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class SculptRenderer implements GLSurfaceView.Renderer {
    private static final int MAX_HISTORY = 20;

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
    private int uModel;
    private int uLightDirection;

    private int width = 1;
    private int height = 1;

    private volatile BrushMode brushMode = BrushMode.ADD;
    private volatile float brushRadius = 0.327f;
    private volatile float brushStrength = 0.0263f;
    private volatile boolean symmetryX = false;

    private float yaw = 22f;
    private float pitch = 8f;
    private float cameraDistance = 5.25f;

    private boolean buffersDirty = true;
    private boolean strokeActive = false;
    private boolean hasLastDab = false;
    private float lastDabX;
    private float lastDabY;
    private float[] strokeStartSnapshot;
    private SculptMesh.GrabHandle grabHandle;
    private SculptMesh.GrabHandle mirroredGrabHandle;
    private final float[] grabPlanePoint = new float[3];
    private final float[] grabPlaneNormal = new float[3];
    private final float[] grabStartWorld = new float[3];
    private boolean grabReady = false;
    private final Deque<float[]> undoStack = new ArrayDeque<>();
    private final Deque<float[]> redoStack = new ArrayDeque<>();

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
        uModel = GLES30.glGetUniformLocation(program, "uModel");
        uLightDirection = GLES30.glGetUniformLocation(program, "uLightDirection");

        if (mesh == null) {
            mesh = SculptMesh.createHumanBase();
        }
        // Android may recreate the GL context even when the Activity survives.
        // Keep the CPU mesh/history and only recreate GPU-side buffers/program state.
        allocateBuffers();
        Matrix.setIdentityM(model, 0);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        GLES30.glViewport(0, 0, this.width, this.height);
        float aspect = (float) this.width / (float) this.height;
        Matrix.perspectiveM(projection, 0, 42f, aspect, 0.1f, 100f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (mesh == null) return;
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        updateMatrices();
        if (buffersDirty) uploadMesh();

        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0);
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
        brushMode = mode;
    }

    public void setBrushRadius(float radius) {
        brushRadius = Math.max(0.08f, Math.min(0.8f, radius));
    }

    public void setBrushStrength(float strength) {
        brushStrength = Math.max(0.002f, Math.min(0.1f, strength));
    }

    public boolean toggleSymmetry() {
        symmetryX = !symmetryX;
        return symmetryX;
    }

    public void beginStroke() {
        if (mesh == null || strokeActive) return;
        strokeActive = true;
        hasLastDab = false;
        clearGrabState();
        strokeStartSnapshot = mesh.copyPositions();
    }

    public void endStroke() {
        if (!strokeActive || mesh == null) {
            clearGrabState();
            return;
        }
        strokeActive = false;
        hasLastDab = false;
        clearGrabState();
        if (strokeStartSnapshot != null && !samePositions(strokeStartSnapshot, mesh.positions)) {
            undoStack.push(strokeStartSnapshot);
            trimHistory(undoStack);
            redoStack.clear();
        }
        strokeStartSnapshot = null;
    }

    public void sculptAt(float screenX, float screenY) {
        if (mesh == null || !strokeActive) return;

        if (brushMode == BrushMode.GRAB) {
            grabAt(screenX, screenY);
            return;
        }

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
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float spacing = brushSpacingPixels();
        if (distance < spacing) return;

        float startX = lastDabX;
        float startY = lastDabY;
        int steps = Math.min(32, (int) (distance / spacing));
        float invDistance = distance > 1e-6f ? 1f / distance : 0f;

        for (int i = 1; i <= steps; i++) {
            float travel = spacing * i;
            float t = Math.min(1f, travel * invDistance);
            sculptDab(startX + dx * t, startY + dy * t);
        }

        float consumed = Math.min(distance, spacing * steps);
        float t = consumed * invDistance;
        lastDabX = startX + dx * t;
        lastDabY = startY + dy * t;
    }

    private void clearGrabState() {
        grabHandle = null;
        mirroredGrabHandle = null;
        grabReady = false;
    }

    private boolean grabAt(float screenX, float screenY) {
        updateMatrices();
        Ray ray = screenRay(screenX, screenY);
        if (ray == null) return false;

        if (!grabReady) {
            SculptMesh.Hit hit = mesh.raycast(ray.origin, ray.direction);
            if (hit == null) return false;

            grabHandle = mesh.beginGrab(
                    hit.x, hit.y, hit.z,
                    hit.nx, hit.ny, hit.nz,
                    ray.direction,
                    brushRadius,
                    true
            );
            if (grabHandle == null) return false;

            grabPlanePoint[0] = hit.x;
            grabPlanePoint[1] = hit.y;
            grabPlanePoint[2] = hit.z;
            grabPlaneNormal[0] = ray.direction[0];
            grabPlaneNormal[1] = ray.direction[1];
            grabPlaneNormal[2] = ray.direction[2];
            grabStartWorld[0] = hit.x;
            grabStartWorld[1] = hit.y;
            grabStartWorld[2] = hit.z;

            if (symmetryX && Math.abs(hit.x) * 2f >= brushRadius * 1.10f) {
                float[] mirroredView = new float[]{
                        -ray.direction[0],
                        ray.direction[1],
                        ray.direction[2]
                };
                mirroredGrabHandle = mesh.beginGrab(
                        -hit.x, hit.y, hit.z,
                        -hit.nx, hit.ny, hit.nz,
                        mirroredView,
                        brushRadius,
                        false
                );
            }
            grabReady = true;
            return true;
        }

        float[] world = intersectRayPlane(ray, grabPlanePoint, grabPlaneNormal);
        if (world == null) return false;

        float dx = world[0] - grabStartWorld[0];
        float dy = world[1] - grabStartWorld[1];
        float dz = world[2] - grabStartWorld[2];

        boolean changed = mesh.applyGrab(grabHandle, dx, dy, dz, brushStrength);
        if (mirroredGrabHandle != null) {
            boolean mirroredChanged = mesh.applyGrab(
                    mirroredGrabHandle,
                    -dx, dy, dz,
                    brushStrength
            );
            changed |= mirroredChanged;
        }

        if (changed) {
            mesh.recalculateNormals();
            buffersDirty = true;
        }
        return true;
    }

    private float[] intersectRayPlane(Ray ray, float[] point, float[] normal) {
        float denom = ray.direction[0] * normal[0]
                + ray.direction[1] * normal[1]
                + ray.direction[2] * normal[2];
        if (Math.abs(denom) < 1e-6f) return null;

        float px = point[0] - ray.origin[0];
        float py = point[1] - ray.origin[1];
        float pz = point[2] - ray.origin[2];
        float t = (px * normal[0] + py * normal[1] + pz * normal[2]) / denom;
        if (!Float.isFinite(t)) return null;

        return new float[]{
                ray.origin[0] + ray.direction[0] * t,
                ray.origin[1] + ray.direction[1] * t,
                ray.origin[2] + ray.direction[2] * t
        };
    }

    private boolean sculptDab(float screenX, float screenY) {
        updateMatrices();
        Ray ray = screenRay(screenX, screenY);
        if (ray == null) return false;

        SculptMesh.Hit hit = mesh.raycast(ray.origin, ray.direction);
        if (hit == null) return false;

        float effectiveStrength = brushStrength;
        float mirroredSeparation = Math.abs(hit.x) * 2f;
        if (symmetryX && mirroredSeparation < brushRadius * 2f) {
            // When the two symmetry brushes overlap, reduce each contribution so
            // the center seam does not receive an accidental double-strength hit.
            float overlap = 1f - mirroredSeparation / Math.max(1e-6f, brushRadius * 2f);
            effectiveStrength = brushStrength / (1f + overlap);
        }

        boolean changed = mesh.applyBrush(
                hit.x, hit.y, hit.z,
                hit.nx, hit.ny, hit.nz,
                ray.direction,
                brushRadius,
                effectiveStrength,
                brushMode,
                true
        );

        if (symmetryX) {
            if (changed) {
                // The mirrored selection relies on current normals. Refresh them
                // after the primary transaction instead of using stale pre-dab data.
                mesh.recalculateNormals();
            }

            float[] mirroredView = new float[]{
                    -ray.direction[0],
                    ray.direction[1],
                    ray.direction[2]
            };
            boolean mirroredChanged = mesh.applyBrush(
                    -hit.x, hit.y, hit.z,
                    -hit.nx, hit.ny, hit.nz,
                    mirroredView,
                    brushRadius,
                    effectiveStrength,
                    brushMode,
                    false
            );
            changed |= mirroredChanged;
        }

        if (changed) {
            mesh.recalculateNormals();
            buffersDirty = true;
        }
        // A valid ray hit still counts as a consumed dab even if the geometry
        // guard rejected movement because the fixed topology reached its limit.
        return true;
    }

    private float brushSpacingPixels() {
        float visibleWorldHeight = 2f * cameraDistance
                * (float) Math.tan(Math.toRadians(42f * 0.5f));
        float worldPerPixel = visibleWorldHeight / Math.max(1f, height);
        if (worldPerPixel < 1e-6f) return 8f;

        float spacing = (brushRadius * 0.18f) / worldPerPixel;
        return Math.max(4f, Math.min(22f, spacing));
    }

    public void adjustCamera(float dxPixels, float dyPixels, float pinchPixels) {
        yaw += dxPixels * 0.26f;
        pitch += dyPixels * 0.22f;
        pitch = Math.max(-78f, Math.min(78f, pitch));
        cameraDistance -= pinchPixels * 0.0065f;
        cameraDistance = Math.max(2.1f, Math.min(8f, cameraDistance));
    }

    public void undo() {
        if (mesh == null || undoStack.isEmpty()) return;
        hasLastDab = false;
        redoStack.push(mesh.copyPositions());
        trimHistory(redoStack);
        mesh.setPositions(undoStack.pop());
        mesh.recalculateNormals();
        buffersDirty = true;
    }

    public void redo() {
        if (mesh == null || redoStack.isEmpty()) return;
        hasLastDab = false;
        undoStack.push(mesh.copyPositions());
        trimHistory(undoStack);
        mesh.setPositions(redoStack.pop());
        mesh.recalculateNormals();
        buffersDirty = true;
    }

    public void resetMesh() {
        if (mesh == null) return;
        float[] before = mesh.copyPositions();
        mesh.reset();
        if (samePositions(before, mesh.positions)) return;

        hasLastDab = false;
        undoStack.push(before);
        trimHistory(undoStack);
        redoStack.clear();
        mesh.recalculateNormals();
        buffersDirty = true;
    }

    private void updateMatrices() {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float cp = (float) Math.cos(pitchRad);
        float eyeX = cameraDistance * cp * (float) Math.sin(yawRad);
        float eyeY = cameraDistance * (float) Math.sin(pitchRad);
        float eyeZ = cameraDistance * cp * (float) Math.cos(yawRad);

        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f);
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0);
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
    }

    private Ray screenRay(float x, float y) {
        if (!Matrix.invertM(inverseMvp, 0, mvp, 0)) return null;
        float ndcX = (2f * x / width) - 1f;
        float ndcY = 1f - (2f * y / height);

        float[] near = multiply(inverseMvp, ndcX, ndcY, -1f, 1f);
        float[] far = multiply(inverseMvp, ndcX, ndcY, 1f, 1f);
        if (Math.abs(near[3]) < 1e-6f || Math.abs(far[3]) < 1e-6f) return null;

        for (int i = 0; i < 3; i++) {
            near[i] /= near[3];
            far[i] /= far[3];
        }

        float dx = far[0] - near[0];
        float dy = far[1] - near[1];
        float dz = far[2] - near[2];
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-6f) return null;

        return new Ray(
                new float[]{near[0], near[1], near[2]},
                new float[]{dx / length, dy / length, dz / length}
        );
    }

    private float[] multiply(float[] matrix, float x, float y, float z, float w) {
        return new float[]{
                matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12] * w,
                matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13] * w,
                matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14] * w,
                matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15] * w
        };
    }

    private void allocateBuffers() {
        positionBuffer = ByteBuffer.allocateDirect(mesh.positions.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        normalBuffer = ByteBuffer.allocateDirect(mesh.normals.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        indexBuffer = ByteBuffer.allocateDirect(mesh.indices.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        indexBuffer.put(mesh.indices).position(0);
        uploadMesh();
    }

    private void uploadMesh() {
        positionBuffer.position(0);
        positionBuffer.put(mesh.positions).position(0);
        normalBuffer.position(0);
        normalBuffer.put(mesh.normals).position(0);
        buffersDirty = false;
    }

    private void trimHistory(Deque<float[]> stack) {
        while (stack.size() > MAX_HISTORY) stack.removeLast();
    }

    private boolean samePositions(float[] a, float[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-7f) return false;
        }
        return true;
    }

    private int buildProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        int result = GLES30.glCreateProgram();
        GLES30.glAttachShader(result, vertex);
        GLES30.glAttachShader(result, fragment);
        GLES30.glLinkProgram(result);
        int[] linked = new int[1];
        GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(result);
            GLES30.glDeleteProgram(result);
            throw new RuntimeException("OpenGL program link failed: " + log);
        }
        GLES30.glDeleteShader(vertex);
        GLES30.glDeleteShader(fragment);
        return result;
    }

    private int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new RuntimeException("OpenGL shader compile failed: " + log);
        }
        return shader;
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
            "precision highp float;\n" +
            "in vec3 aPosition;\n" +
            "in vec3 aNormal;\n" +
            "uniform mat4 uMvp;\n" +
            "uniform mat4 uModel;\n" +
            "out vec3 vNormal;\n" +
            "out vec3 vWorldPos;\n" +
            "void main() {\n" +
            "  vec4 world = uModel * vec4(aPosition, 1.0);\n" +
            "  vWorldPos = world.xyz;\n" +
            "  vNormal = normalize(mat3(uModel) * aNormal);\n" +
            "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec3 vNormal;\n" +
            "in vec3 vWorldPos;\n" +
            "uniform vec3 uLightDirection;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  float diffuse = max(dot(n, normalize(uLightDirection)), 0.0);\n" +
            "  float hemi = 0.28 + 0.22 * (n.y * 0.5 + 0.5);\n" +
            "  float rim = pow(1.0 - max(abs(n.z), 0.0), 3.0) * 0.10;\n" +
            "  vec3 base = vec3(0.66, 0.69, 0.73);\n" +
            "  vec3 color = base * (hemi + diffuse * 0.62) + rim;\n" +
            "  fragColor = vec4(color, 1.0);\n" +
            "}\n";
}
