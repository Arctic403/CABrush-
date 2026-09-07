package com.fallpoint.pocketsculpt;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

public class SculptSurfaceView extends GLSurfaceView {
    private final SculptRenderer renderer;
    private float lastSingleX;
    private float lastSingleY;
    private float lastMidX;
    private float lastMidY;
    private float lastPinchDistance;
    private boolean twoFingerGesture;

    public SculptSurfaceView(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        renderer = new SculptRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void setBrushMode(BrushMode mode) {
        renderer.setBrushMode(mode);
    }

    public void setBrushRadius(float radius) {
        renderer.setBrushRadius(radius);
    }

    public void setBrushStrength(float strength) {
        renderer.setBrushStrength(strength);
    }

    public boolean toggleSymmetry() {
        return renderer.toggleSymmetry();
    }

    public void undo() {
        queueEvent(renderer::undo);
        requestRender();
    }

    public void redo() {
        queueEvent(renderer::redo);
        requestRender();
    }

    public void resetMesh() {
        queueEvent(renderer::resetMesh);
        requestRender();
    }

    @Override
    public void onPause() {
        // Finish an in-flight stroke before the GL thread pauses. Without this,
        // an app switch can leave strokeActive latched and the next touch is
        // ignored because beginStroke() thinks the old stroke is still active.
        queueEvent(renderer::endStroke);
        twoFingerGesture = false;
        super.onPause();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int count = event.getPointerCount();

        if (action == MotionEvent.ACTION_DOWN) {
            twoFingerGesture = false;
            lastSingleX = event.getX();
            lastSingleY = event.getY();
            queueEvent(renderer::beginStroke);
            sculptAt(lastSingleX, lastSingleY);
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN && count >= 2) {
            twoFingerGesture = true;
            queueEvent(renderer::endStroke);
            setTwoFingerReference(event);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (count >= 2) {
                twoFingerGesture = true;
                handleTwoFinger(event);
            } else if (!twoFingerGesture) {
                // Android may batch several touch samples into one ACTION_MOVE.
                // Consume the historical path first so curved strokes do not get
                // replaced by one long straight chord on slower devices.
                int history = event.getHistorySize();
                for (int h = 0; h < history; h++) {
                    consumeSingleSample(event.getHistoricalX(0, h), event.getHistoricalY(0, h));
                }
                consumeSingleSample(event.getX(), event.getY());
            }
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP) {
            if (count <= 2) {
                twoFingerGesture = true;
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            queueEvent(renderer::endStroke);
            twoFingerGesture = false;
            requestRender();
            return true;
        }

        return true;
    }

    private void consumeSingleSample(float x, float y) {
        float dx = x - lastSingleX;
        float dy = y - lastSingleY;
        if (dx * dx + dy * dy < 9f) return;
        sculptAt(x, y);
        lastSingleX = x;
        lastSingleY = y;
    }

    private void sculptAt(float x, float y) {
        queueEvent(() -> renderer.sculptAt(x, y));
        requestRender();
    }

    private void setTwoFingerReference(MotionEvent event) {
        float x0 = event.getX(0);
        float y0 = event.getY(0);
        float x1 = event.getX(1);
        float y1 = event.getY(1);
        lastMidX = (x0 + x1) * 0.5f;
        lastMidY = (y0 + y1) * 0.5f;
        lastPinchDistance = distance(x0, y0, x1, y1);
    }

    private void handleTwoFinger(MotionEvent event) {
        float x0 = event.getX(0);
        float y0 = event.getY(0);
        float x1 = event.getX(1);
        float y1 = event.getY(1);
        float midX = (x0 + x1) * 0.5f;
        float midY = (y0 + y1) * 0.5f;
        float pinch = distance(x0, y0, x1, y1);

        float orbitDx = midX - lastMidX;
        float orbitDy = midY - lastMidY;
        float zoomDelta = pinch - lastPinchDistance;

        queueEvent(() -> renderer.adjustCamera(orbitDx, orbitDy, zoomDelta));
        requestRender();

        lastMidX = midX;
        lastMidY = midY;
        lastPinchDistance = pinch;
    }

    private float distance(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
