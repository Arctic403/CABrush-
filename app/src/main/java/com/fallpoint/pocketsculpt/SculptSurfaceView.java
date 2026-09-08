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
    private long inputSequence;

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
                if (EngineDiagnostics.isEnabled()) {
                    EngineDiagnostics.record("input", "pinch",
                            "factor=" + factor
                                    + " focus=" + detector.getFocusX() + "," + detector.getFocusY()
                                    + " span=" + detector.getCurrentSpan());
                }
                queueTracked("zoom", () -> renderer.zoom(factor));
                return true;
            }
        });

        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.record("input", "surface_view_created",
                    "render_mode=continuous preserve_egl=true");
        }
    }

    public void setBrushMode(BrushMode mode) {
        queueTracked("set_brush_mode", () -> renderer.setBrushMode(mode));
    }

    public void setBrushRadius(float radius) {
        queueTracked("set_brush_radius", () -> renderer.setBrushRadius(radius));
    }

    public void setBrushStrength(float strength) {
        queueTracked("set_brush_strength", () -> renderer.setBrushStrength(strength));
    }

    public void resetMesh() {
        queueTracked("reset_mesh", renderer::resetMesh);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        final int action = event.getActionMasked();

        if (EngineDiagnostics.isEnabled()) {
            inputSequence++;
            StringBuilder detail = new StringBuilder(192);
            detail.append("seq=").append(inputSequence)
                    .append(" action=").append(actionName(action))
                    .append(" action_index=").append(event.getActionIndex())
                    .append(" pointers=").append(event.getPointerCount())
                    .append(" history=").append(event.getHistorySize())
                    .append(" event_time_ms=").append(event.getEventTime())
                    .append(" down_time_ms=").append(event.getDownTime())
                    .append(" sculpting=").append(sculpting)
                    .append(" navigating=").append(navigating);
            for (int i = 0; i < event.getPointerCount(); i++) {
                detail.append(" p").append(i)
                        .append("{id=").append(event.getPointerId(i))
                        .append(",x=").append(event.getX(i))
                        .append(",y=").append(event.getY(i))
                        .append(",pressure=").append(event.getPressure(i))
                        .append(",size=").append(event.getSize(i))
                        .append("}");
            }
            EngineDiagnostics.record("input", "motion", detail.toString());
            EngineDiagnostics.counter("input.motion_events", 1L);
            EngineDiagnostics.counter("input.historical_points", event.getHistorySize());
        }

        if (action == MotionEvent.ACTION_DOWN) {
            sculpting = true;
            navigating = false;
            final float x = event.getX();
            final float y = event.getY();
            queueTracked("stroke_begin", () -> {
                renderer.beginStroke();
                renderer.sculptAt(x, y);
            });
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() >= 2) {
            if (sculpting) {
                sculpting = false;
                queueTracked("stroke_end_multitouch", renderer::endStroke);
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
                queueTracked("orbit", () -> renderer.orbit(dx, dy));
                return true;
            }

            if (sculpting && event.getPointerCount() == 1) {
                final int history = event.getHistorySize();
                for (int h = 0; h < history; h++) {
                    final float hx = event.getHistoricalX(0, h);
                    final float hy = event.getHistoricalY(0, h);
                    queueTracked("sculpt_historical", () -> renderer.sculptAt(hx, hy));
                }
                final float x = event.getX();
                final float y = event.getY();
                queueTracked("sculpt_current", () -> renderer.sculptAt(x, y));
                return true;
            }
        }

        if (action == MotionEvent.ACTION_POINTER_UP) {
            navigating = event.getPointerCount() - 1 >= 2;
            sculpting = false;
            queueTracked("stroke_end_pointer_up", renderer::endStroke);
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (sculpting) queueTracked("stroke_end", renderer::endStroke);
            sculpting = false;
            navigating = false;
            return true;
        }

        return true;
    }

    @Override
    public void onPause() {
        queueTracked("stroke_end_pause", renderer::endStroke);
        sculpting = false;
        navigating = false;
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.record("input", "surface_pause", "");
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (EngineDiagnostics.isEnabled()) {
            EngineDiagnostics.record("input", "surface_resume", "");
        }
    }

    private void queueTracked(String label, Runnable action) {
        if (!EngineDiagnostics.isEnabled()) {
            queueEvent(action);
            return;
        }
        final long queued = System.nanoTime();
        EngineDiagnostics.counter("gl_queue.enqueued", 1L);
        queueEvent(() -> {
            long waitNs = System.nanoTime() - queued;
            EngineDiagnostics.counter("gl_queue.executed", 1L);
            EngineDiagnostics.gauge("gl_queue.last_wait_ms", waitNs / 1_000_000.0);
            EngineDiagnostics.record("gl_queue", "execute",
                    "label=" + label + " wait_ns=" + waitNs);
            if (waitNs > 150_000_000L) {
                EngineDiagnostics.anomaly("gl_queue_stall",
                        "label=" + label + " wait_ns=" + waitNs);
            }
            action.run();
        });
    }

    private static String actionName(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN: return "DOWN";
            case MotionEvent.ACTION_UP: return "UP";
            case MotionEvent.ACTION_MOVE: return "MOVE";
            case MotionEvent.ACTION_CANCEL: return "CANCEL";
            case MotionEvent.ACTION_POINTER_DOWN: return "POINTER_DOWN";
            case MotionEvent.ACTION_POINTER_UP: return "POINTER_UP";
            default: return String.valueOf(action);
        }
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
