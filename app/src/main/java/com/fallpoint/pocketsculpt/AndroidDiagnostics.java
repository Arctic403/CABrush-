package com.fallpoint.pocketsculpt;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android owner for PocketSculpt's deep engine diagnostics.
 *
 * The single Gradle switch cabrush.devMode=on/off controls whether this is
 * installed. With it off no writer thread, crash hook, event listener or
 * diagnostic I/O is created.
 */
final class AndroidDiagnostics {
    private final Context context;
    private final ExecutorService writer;
    private final AtomicBoolean dumpPending = new AtomicBoolean();
    private final Thread.UncaughtExceptionHandler previousHandler;
    private volatile String lastDumpPath = "";

    private AndroidDiagnostics(Context context) {
        this.context = context.getApplicationContext();
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PocketSculpt-DiagnosticWriter");
            t.setDaemon(true);
            return t;
        });
        this.previousHandler = Thread.getDefaultUncaughtExceptionHandler();

        EngineDiagnostics.setDumpListener(this::requestDump);
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                EngineDiagnostics.record(
                        "crash",
                        "uncaught_exception",
                        "thread=" + thread.getName()
                                + " type=" + throwable.getClass().getName()
                                + " message=" + String.valueOf(throwable.getMessage())
                );
                writeDump("uncaught-" + throwable.getClass().getSimpleName(), throwable);
            } catch (Throwable ignored) {
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            }
        });

        seedDeviceState();
        EngineDiagnostics.record("android", "diagnostics_installed",
                "pid=" + android.os.Process.myPid());
    }

    static AndroidDiagnostics install(Context context) {
        EngineDiagnostics.configure(BuildConfig.ENGINE_DIAGNOSTICS);
        if (!BuildConfig.ENGINE_DIAGNOSTICS) return null;
        return new AndroidDiagnostics(context);
    }

    void requestManualDump(String reason) {
        if (!BuildConfig.ENGINE_DIAGNOSTICS) return;
        EngineDiagnostics.record("diagnostics", "manual_dump_requested", "reason=" + reason);
        requestDump("manual-" + reason);
    }

    String lastDumpPath() {
        return lastDumpPath;
    }

    void onLifecycle(String state) {
        if (!BuildConfig.ENGINE_DIAGNOSTICS) return;
        EngineDiagnostics.record("android", "lifecycle", state);
        sampleRuntimeState();
    }

    void shutdown() {
        if (!BuildConfig.ENGINE_DIAGNOSTICS) return;
        EngineDiagnostics.record("android", "diagnostics_shutdown", "");
        EngineDiagnostics.setDumpListener(null);
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        writer.shutdown();
    }

    private void requestDump(String reason) {
        if (!BuildConfig.ENGINE_DIAGNOSTICS) return;
        if (!dumpPending.compareAndSet(false, true)) {
            EngineDiagnostics.counter("diagnostics.dump_coalesced", 1L);
            return;
        }
        writer.execute(() -> {
            try {
                writeDump(reason, null);
            } catch (Throwable t) {
                EngineDiagnostics.record("diagnostics", "dump_failed",
                        t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            } finally {
                dumpPending.set(false);
            }
        });
    }

    private synchronized void writeDump(String reason, Throwable crash) throws IOException {
        sampleRuntimeState();
        EngineDiagnostics.Snapshot snapshot = EngineDiagnostics.snapshot();

        File root = context.getExternalFilesDir(null);
        if (root == null) root = context.getFilesDir();
        File diagnosticsRoot = new File(root, "diagnostics");
        if (!diagnosticsRoot.exists() && !diagnosticsRoot.mkdirs()) {
            throw new IOException("cannot create " + diagnosticsRoot);
        }

        String stamp = fileStamp(System.currentTimeMillis());
        File dir = new File(diagnosticsRoot, "PocketSculpt-" + stamp + "-" + sanitize(reason));
        if (!dir.mkdirs()) throw new IOException("cannot create " + dir);

        writeManifest(new File(dir, "dump.json"), snapshot, reason, crash);
        writeEvents(new File(dir, "events.jsonl"), snapshot);
        writeThreads(new File(dir, "threads.txt"));
        writeSystem(new File(dir, "system.txt"), snapshot, reason);
        if (crash != null) writeCrash(new File(dir, "crash.txt"), crash);

        lastDumpPath = dir.getAbsolutePath();
        try (FileWriter latest = new FileWriter(new File(diagnosticsRoot, "latest.txt"))) {
            latest.write(lastDumpPath);
            latest.write('\n');
        }

        EngineDiagnostics.counter("diagnostics.dumps_written", 1L);
        EngineDiagnostics.state("diagnostics.last_dump", lastDumpPath);
        EngineDiagnostics.record("diagnostics", "dump_complete",
                "reason=" + reason + " path=" + lastDumpPath
                        + " events=" + snapshot.events.size());
    }

    private void seedDeviceState() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        EngineDiagnostics.state("build.version_name", BuildConfig.VERSION_NAME);
        EngineDiagnostics.state("build.version_code", String.valueOf(BuildConfig.VERSION_CODE));
        EngineDiagnostics.state("build.dev_mode", String.valueOf(BuildConfig.ENGINE_DIAGNOSTICS));
        EngineDiagnostics.state("device.manufacturer", Build.MANUFACTURER);
        EngineDiagnostics.state("device.model", Build.MODEL);
        EngineDiagnostics.state("device.device", Build.DEVICE);
        EngineDiagnostics.state("device.sdk", String.valueOf(Build.VERSION.SDK_INT));
        EngineDiagnostics.state("device.release", Build.VERSION.RELEASE);
        EngineDiagnostics.state("device.abis", Arrays.toString(Build.SUPPORTED_ABIS));
        EngineDiagnostics.state("display.pixels", dm.widthPixels + "x" + dm.heightPixels);
        EngineDiagnostics.state("display.density", String.valueOf(dm.density));
    }

    private void sampleRuntimeState() {
        Runtime rt = Runtime.getRuntime();
        long javaUsed = rt.totalMemory() - rt.freeMemory();
        EngineDiagnostics.gauge("memory.java_used_mb", javaUsed / 1048576.0);
        EngineDiagnostics.gauge("memory.java_total_mb", rt.totalMemory() / 1048576.0);
        EngineDiagnostics.gauge("memory.java_max_mb", rt.maxMemory() / 1048576.0);
        EngineDiagnostics.gauge("memory.native_heap_mb",
                Debug.getNativeHeapAllocatedSize() / 1048576.0);
        EngineDiagnostics.gauge("process.uptime_ms", SystemClock.uptimeMillis());

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            EngineDiagnostics.gauge("memory.system_available_mb", mi.availMem / 1048576.0);
            EngineDiagnostics.gauge("memory.system_threshold_mb", mi.threshold / 1048576.0);
            EngineDiagnostics.state("memory.low", String.valueOf(mi.lowMemory));
        }

        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                EngineDiagnostics.gauge("device.thermal_status", pm.getCurrentThermalStatus());
            }
        }
    }

    private void writeManifest(File file, EngineDiagnostics.Snapshot s,
                               String reason, Throwable crash) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("{\n");
            w.write("  \"schema\":\"pocketsculpt-engine-dump-v1\",\n");
            w.write("  \"generated_at\":\"" + esc(isoTime(s.capturedWallMillis)) + "\",\n");
            w.write("  \"reason\":\"" + esc(reason) + "\",\n");
            w.write("  \"dev_mode\":" + BuildConfig.ENGINE_DIAGNOSTICS + ",\n");
            w.write("  \"session_elapsed_ns\":" + s.sessionElapsedNanos + ",\n");
            w.write("  \"event_capacity\":" + EngineDiagnostics.EVENT_CAPACITY + ",\n");
            w.write("  \"events_retained\":" + s.events.size() + ",\n");
            w.write("  \"events_dropped\":" + s.droppedEvents + ",\n");
            w.write("  \"crash\":" + (crash == null ? "null" :
                    "{\"type\":\"" + esc(crash.getClass().getName())
                            + "\",\"message\":\"" + esc(String.valueOf(crash.getMessage())) + "\"}") + ",\n");
            writeLongMap(w, "counters", s.counters, true);
            writeDoubleMap(w, "gauges", s.gauges, true);
            writeStringMap(w, "state", s.state, false);
            w.write("}\n");
        }
    }

    private static void writeEvents(File file, EngineDiagnostics.Snapshot s) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            for (EngineDiagnostics.Event e : s.events) {
                w.write("{\"seq\":" + e.sequence
                        + ",\"elapsed_ns\":" + e.elapsedNanos
                        + ",\"thread_id\":" + e.threadId
                        + ",\"thread\":\"" + esc(e.threadName)
                        + "\",\"category\":\"" + esc(e.category)
                        + "\",\"name\":\"" + esc(e.name)
                        + "\",\"detail\":\"" + esc(e.detail)
                        + "\"}\n");
            }
        }
    }

    private static void writeThreads(File file) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread t = entry.getKey();
                w.write("\"" + t.getName() + "\" id=" + t.getId()
                        + " state=" + t.getState()
                        + " priority=" + t.getPriority()
                        + " daemon=" + t.isDaemon() + "\n");
                for (StackTraceElement frame : entry.getValue()) {
                    w.write("    at " + frame + "\n");
                }
                w.write("\n");
            }
        }
    }

    private static void writeCrash(File file, Throwable crash) throws IOException {
        StringWriter sw = new StringWriter();
        crash.printStackTrace(new PrintWriter(sw));
        try (FileWriter w = new FileWriter(file)) {
            w.write(sw.toString());
        }
    }

    private static void writeSystem(File file, EngineDiagnostics.Snapshot s,
                                    String reason) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("PocketSculpt deep engine diagnostics\n");
            w.write("Reason: " + reason + "\n");
            w.write("Captured: " + isoTime(s.capturedWallMillis) + "\n");
            w.write("Session ns: " + s.sessionElapsedNanos + "\n");
            w.write("Events retained: " + s.events.size()
                    + " dropped: " + s.droppedEvents + "\n\n");

            w.write("[STATE]\n");
            for (Map.Entry<String, String> e : s.state.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
            w.write("\n[GAUGES]\n");
            for (Map.Entry<String, Double> e : s.gauges.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
            w.write("\n[COUNTERS]\n");
            for (Map.Entry<String, Long> e : s.counters.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        }
    }

    private static void writeLongMap(BufferedWriter w, String name,
                                     Map<String, Long> map, boolean commaAfter) throws IOException {
        w.write("  \"" + name + "\":{");
        boolean first = true;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!first) w.write(",");
            first = false;
            w.write("\"" + esc(e.getKey()) + "\":" + e.getValue());
        }
        w.write("}" + (commaAfter ? "," : "") + "\n");
    }

    private static void writeDoubleMap(BufferedWriter w, String name,
                                       Map<String, Double> map, boolean commaAfter) throws IOException {
        w.write("  \"" + name + "\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : map.entrySet()) {
            if (!first) w.write(",");
            first = false;
            w.write("\"" + esc(e.getKey()) + "\":" + e.getValue());
        }
        w.write("}" + (commaAfter ? "," : "") + "\n");
    }

    private static void writeStringMap(BufferedWriter w, String name,
                                       Map<String, String> map, boolean commaAfter) throws IOException {
        w.write("  \"" + name + "\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) w.write(",");
            first = false;
            w.write("\"" + esc(e.getKey()) + "\":\"" + esc(e.getValue()) + "\"");
        }
        w.write("}" + (commaAfter ? "," : "") + "\n");
    }

    private static String isoTime(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    private static String fileStamp(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "dump";
        return s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
