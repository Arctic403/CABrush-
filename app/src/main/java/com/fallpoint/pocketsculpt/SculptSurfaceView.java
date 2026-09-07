package com.fallpoint.pocketsculpt;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

public final class SculptSurfaceView extends GLSurfaceView {
    private final SculptRenderer renderer;
    private final ScaleGestureDetector scaleDetector;

    private boolean sculpting = false;
    private boolean navigating = false;
    private float lastNavX;
    private float lastNavY;

    public SculptSurfaceView(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);

        renderer = new SculptRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                final float factor = detector.getScaleFactor();
                queueEvent(() -> renderer.zoom(factor));
                return true;
            }
        });
    }

    public void setBrushMode(BrushMode mode) {
        queueEvent(() -> renderer.setBrushMode(mode));
    }

    public void setBrushRadius(float radius) {
        queueEvent(() -> renderer.setBrushRadius(radius));
    }

    public void setBrushStrength(float strength) {
        queueEvent(() -> renderer.setBrushStrength(strength));
    }

    public void resetMesh() {
        queueEvent(renderer::resetMesh);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            sculpting = true;
            navigating = false;
            final float x = event.getX();
            final float y = event.getY();
            queueEvent(() -> {
                renderer.beginStroke();
                renderer.sculptAt(x, y);
            });
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() >= 2) {
            if (sculpting) {
                sculpting = false;
                queueEvent(renderer::endStroke);
            }
            navigating = true;
            lastNavX = centroidX(event);
            lastNavY = centroidY(event);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() >= 2 || navigating) {
                final float cx = centroidX(event);
                final float cy = centroidY(event);
                final float dx = cx - lastNavX;
                final float dy = cy - lastNavY;
                lastNavX = cx;
                lastNavY = cy;
                queueEvent(() -> renderer.orbit(dx, dy));
                return true;
            }

            if (sculpting && event.getPointerCount() == 1) {
                final int history = event.getHistorySize();
                for (int h = 0; h < history; h++) {
                    final float hx = event.getHistoricalX(0, h);
                    final float hy = event.getHistoricalY(0, h);
                    queueEvent(() -> renderer.sculptAt(hx, hy));
                }
                final float x = event.getX();
                final float y = event.getY();
                queueEvent(() -> renderer.sculptAt(x, y));
                return true;
            }
        }

        if (action == MotionEvent.ACTION_POINTER_UP) {
            navigating = event.getPointerCount() - 1 >= 2;
            sculpting = false;
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (sculpting) queueEvent(renderer::endStroke);
            sculpting = false;
            navigating = false;
            return true;
        }

        return true;
    }

    @Override
    public void onPause() {
        queueEvent(renderer::endStroke);
        sculpting = false;
        navigating = false;
        super.onPause();
    }

    private static float centroidX(MotionEvent event) {
        int count = event.getPointerCount();
        float sum = 0f;
        for (int i = 0; i < count; i++) sum += event.getX(i);
        return sum / Math.max(1, count);
    }

    private static float centroidY(MotionEvent event) {
        int count = event.getPointerCount();
        float sum = 0f;
        for (int i = 0; i < count; i++) sum += event.getY(i);
        return sum / Math.max(1, count);
    }
}
