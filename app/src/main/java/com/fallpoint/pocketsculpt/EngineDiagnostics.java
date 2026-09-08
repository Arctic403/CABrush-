package com.fallpoint.pocketsculpt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-wide diagnostic bus.
 *
 * This class intentionally has no Android dependencies so the same AVS core can
 * still be compiled by VSS. Runtime diagnostics are opt-in and become near-zero
 * cost when disabled: every entry point returns before allocating event text.
 *
 * The Android shell installs a DumpListener only when BuildConfig says the
 * developer diagnostic system is enabled.
 */
final class EngineDiagnostics {
    static final int SCHEMA_VERSION = 1;
    static final int EVENT_CAPACITY = 65_536;
    private static final long ANOMALY_DUMP_COOLDOWN_MS = 15_000L;

    interface DumpListener {
        void requestDump(String reason);
    }

    static final class Event {
        final long sequence;
        final long elapsedNanos;
        final long threadId;
        final String threadName;
        final String category;
        final String name;
        final String detail;

        Event(long sequence, long elapsedNanos, long threadId, String threadName,
              String category, String name, String detail) {
            this.sequence = sequence;
            this.elapsedNanos = elapsedNanos;
            this.threadId = threadId;
            this.threadName = threadName;
            this.category = category;
            this.name = name;
            this.detail = detail;
        }
    }

    static final class Snapshot {
        final long capturedWallMillis;
        final long sessionElapsedNanos;
        final long droppedEvents;
        final List<Event> events;
        final Map<String, Long> counters;
        final Map<String, Double> gauges;
        final Map<String, String> state;

        Snapshot(long capturedWallMillis, long sessionElapsedNanos, long droppedEvents,
                 List<Event> events, Map<String, Long> counters,
                 Map<String, Double> gauges, Map<String, String> state) {
            this.capturedWallMillis = capturedWallMillis;
            this.sessionElapsedNanos = sessionElapsedNanos;
            this.droppedEvents = droppedEvents;
            this.events = events;
            this.counters = counters;
            this.gauges = gauges;
            this.state = state;
        }
    }

    private static final Object LOCK = new Object();
    private static final Event[] RING = new Event[EVENT_CAPACITY];
    private static final LinkedHashMap<String, Long> COUNTERS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Double> GAUGES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> STATE = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Long> LAST_ANOMALY_DUMP = new LinkedHashMap<>();

    private static volatile boolean enabled;
    private static volatile DumpListener dumpListener;
    private static long sessionStartNanos = System.nanoTime();
    private static long nextSequence;
    private static long droppedEvents;

    private EngineDiagnostics() {}

    static void configure(boolean value) {
        if (value) {
            synchronized (LOCK) {
                if (!enabled) {
                    sessionStartNanos = System.nanoTime();
                    nextSequence = 0L;
                    droppedEvents = 0L;
                    COUNTERS.clear();
                    GAUGES.clear();
                    STATE.clear();
                    LAST_ANOMALY_DUMP.clear();
                    for (int i = 0; i < RING.length; i++) RING[i] = null;
                }
                enabled = true;
            }
            record("diagnostics", "enabled", "schema=" + SCHEMA_VERSION
                    + " capacity=" + EVENT_CAPACITY);
        } else {
            enabled = false;
            dumpListener = null;
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void setDumpListener(DumpListener listener) {
        if (!enabled) return;
        dumpListener = listener;
    }

    static void record(String category, String name, String detail) {
        if (!enabled) return;
        Thread thread = Thread.currentThread();
        long elapsed = System.nanoTime() - sessionStartNanos;
        synchronized (LOCK) {
            long seq = nextSequence++;
            int slot = (int) (seq % EVENT_CAPACITY);
            if (RING[slot] != null && seq >= EVENT_CAPACITY) droppedEvents++;
            RING[slot] = new Event(
                    seq,
                    elapsed,
                    thread.getId(),
                    thread.getName(),
                    safe(category),
                    safe(name),
                    detail == null ? "" : detail
            );
        }
    }

    static void counter(String name, long delta) {
        if (!enabled) return;
        synchronized (LOCK) {
            Long old = COUNTERS.get(name);
            COUNTERS.put(name, (old == null ? 0L : old) + delta);
        }
    }

    static void gauge(String name, double value) {
        if (!enabled || !Double.isFinite(value)) return;
        synchronized (LOCK) {
            GAUGES.put(name, value);
        }
    }

    static void state(String name, String value) {
        if (!enabled) return;
        synchronized (LOCK) {
            STATE.put(name, value == null ? "" : value);
        }
    }

    static void anomaly(String code, String detail) {
        if (!enabled) return;
        record("anomaly", code, detail);
        counter("anomaly." + safe(code), 1L);

        DumpListener listener = dumpListener;
        if (listener == null) return;

        long now = System.currentTimeMillis();
        boolean request = false;
        synchronized (LOCK) {
            Long last = LAST_ANOMALY_DUMP.get(code);
            if (last == null || now - last >= ANOMALY_DUMP_COOLDOWN_MS) {
                LAST_ANOMALY_DUMP.put(code, now);
                request = true;
            }
        }
        if (request) {
            try {
                listener.requestDump("anomaly-" + safe(code));
            } catch (Throwable ignored) {
                // Diagnostics must never be able to crash the sculpt engine.
            }
        }
    }

    static Snapshot snapshot() {
        synchronized (LOCK) {
            long end = nextSequence;
            long start = Math.max(0L, end - EVENT_CAPACITY);
            ArrayList<Event> events = new ArrayList<>((int) Math.min(end, EVENT_CAPACITY));
            for (long seq = start; seq < end; seq++) {
                Event e = RING[(int) (seq % EVENT_CAPACITY)];
                if (e != null && e.sequence == seq) events.add(e);
            }
            return new Snapshot(
                    System.currentTimeMillis(),
                    System.nanoTime() - sessionStartNanos,
                    droppedEvents,
                    events,
                    new LinkedHashMap<>(COUNTERS),
                    new LinkedHashMap<>(GAUGES),
                    new LinkedHashMap<>(STATE)
            );
        }
    }

    static long nowNanos() {
        return enabled ? System.nanoTime() : 0L;
    }

    static void timed(String category, String name, long startNanos, String detail) {
        if (!enabled || startNanos == 0L) return;
        long duration = System.nanoTime() - startNanos;
        gauge(category + "." + name + ".last_ms", duration / 1_000_000.0);
        record(category, name, (detail == null || detail.isEmpty() ? "" : detail + " ")
                + "duration_ns=" + duration);
    }

    private static String safe(String s) {
        if (s == null || s.isEmpty()) return "unknown";
        return s;
    }
}
