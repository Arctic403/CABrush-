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
        gpuByBrick = new GpuChunk[Math.max(256, mesh.volume.brickCount() + 64)];
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

        AvsSurfaceCache.RenderPlan plan = mesh.renderPlan();
        syncGpuChunks(plan);

        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES30.glUniform3f(uLightDirection, -0.35f, 0.72f, 0.58f);
        GLES30.glEnableVertexAttribArray(aPosition);
        GLES30.glEnableVertexAttribArray(aNormal);

        for (AvsSurfaceCache.Chunk source : plan.chunks) {
            GpuChunk chunk = gpuByBrick[source.brickId];
            if (chunk == null || chunk.indexCount == 0) continue;
            chunk.positionBuffer.position(0);
            chunk.normalBuffer.position(0);
            chunk.indexBuffer.position(0);
            GLES30.glVertexAttribPointer(aPosition, 3, GLES30.GL_FLOAT, false, 0, chunk.positionBuffer);
            GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, 0, chunk.normalBuffer);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, chunk.indexCount, GLES30.GL_UNSIGNED_SHORT, chunk.indexBuffer);
        }

        GLES30.glDisableVertexAttribArray(aPosition);
        GLES30.glDisableVertexAttribArray(aNormal);
    }

    public void setBrushMode(BrushMode mode) {
        if (mode != null) brushMode = mode;
    }

    public void setBrushRadius(float radius) {
        brushRadius = clamp(radius, AvsVolume.VOXEL_SIZE * 1.5f, 0.70f);
    }

    public void setBrushStrength(float strength) {
        brushStrength = clamp(strength, 0.001f, 0.060f);
    }

    public void resetMesh() {
        if (mesh == null) return;
        mesh.reset();
        strokeActive = false;
        hasLastDab = false;
        Arrays.fill(gpuByBrick, null);
    }

    public void beginStroke() {
        strokeActive = mesh != null;
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
                lastDabX = screenX; lastDabY = screenY; hasLastDab = true;
            }
            return;
        }

        float dx = screenX - lastDabX;
        float dy = screenY - lastDabY;
        float distance = (float)Math.sqrt(dx*dx + dy*dy);
        float spacing = brushSpacingPixels();
        if (distance < spacing) return;

        int steps = Math.min(48, Math.max(1, (int)(distance / spacing)));
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
    }

    public void zoom(float scaleFactor) {
        if (!Float.isFinite(scaleFactor) || scaleFactor <= 0f) return;
        cameraDistance = clamp(cameraDistance / scaleFactor, 2.0f, 10.0f);
    }

    private boolean sculptDab(float sx, float sy) {
        updateMatrices();
        Ray ray = screenRay(sx, sy);
        if (ray == null) return false;
        SculptMesh.Hit hit = mesh.raycast(ray.origin, ray.direction);
        if (hit == null) return false;
        return mesh.applyClay(hit.x,hit.y,hit.z,hit.nx,hit.ny,hit.nz,brushRadius,brushStrength,brushMode);
    }

    private float brushSpacingPixels() {
        float projected = brushRadius * height / Math.max(1.5f, cameraDistance);
        return clamp(projected * 0.15f, 2.5f, 24f);
    }

    private void syncGpuChunks(AvsSurfaceCache.RenderPlan plan) {
        int needed = mesh.volume.brickCount();
        if (gpuByBrick.length < needed) {
            int cap = gpuByBrick.length;
            while (cap < needed) cap = cap + cap/2 + 64;
            gpuByBrick = Arrays.copyOf(gpuByBrick, cap);
        }
        for (AvsSurfaceCache.Chunk source : plan.chunks) {
            GpuChunk old = gpuByBrick[source.brickId];
            if (old == null || old.revision != source.revision) {
                gpuByBrick[source.brickId] = new GpuChunk(source);
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
        Matrix.invertM(inverseMvp,0,mvp,0);
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
