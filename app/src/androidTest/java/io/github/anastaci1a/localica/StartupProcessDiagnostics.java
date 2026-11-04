package io.github.anastaci1a.localica;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in external controller: instruments the TEST package, never the measured app.
 * No target classes, Compose test clock, performance thresholds or production hooks.
 * Input milestones are accessibility observations, not proof of presented pixels.
 */
public final class StartupProcessDiagnostics extends Instrumentation {
    private static final String APP = "io.github.anastaci1a.localica.debug";
    private static final long WINDOW_MS = 15_000;
    private Bundle arguments;
    private UiAutomation automation;
    private File commandDirectory;
    private final Map<String, File> commandScripts = new HashMap<>();
    private final AtomicReference<long[]> scrollEvent = new AtomicReference<>();
    private volatile long gestureStart;
    private boolean replay;
    private volatile long eventWindowStart = Long.MAX_VALUE;
    private final java.util.concurrent.CopyOnWriteArrayList<long[]> rawScrollEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<String> rawSelectionEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, AtomicInteger> eventCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private Rect replayArtists, replayAlbums, replayBounds;

    @Override public void onCreate(Bundle args) {
        arguments = args == null ? new Bundle() : new Bundle(args);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            require("disposable-emulator".equals(arguments.getString("startupDiagnostics")), "Explicit opt-in required");
            require((APP + ".test").equals(getTargetContext().getPackageName()), "Must instrument own test package");
            require(getTargetContext().getApplicationInfo().uid != getTargetContext().getPackageManager().getApplicationInfo(APP, 0).uid,
                    "Controller must run outside target UID");
            String label = arguments.getString("label", "trial");
            require(label.matches("[A-Za-z0-9_-]{1,80}"), "Invalid label");
            replay = "replay".equals(arguments.getString("inputMode", "replay"));
            boolean trace = "true".equals(arguments.getString("trace", "false"));
            File directory = new File(getTargetContext().getExternalFilesDir(null), "startup-diagnostics/" + label);
            require(!directory.exists() && directory.mkdirs(), "Refuse overwriting previous evidence");
            commandDirectory = new File(directory, "commands");
            require(commandDirectory.mkdir(), "Cannot create command directory");
            automation = getUiAutomation();
            automation.setOnAccessibilityEventListener(event -> {
                if (APP.contentEquals(event.getPackageName() == null ? "" : event.getPackageName()) &&
                        event.getEventTime() >= eventWindowStart && event.getEventTime() < eventWindowStart + WINDOW_MS) {
                    eventCounts.computeIfAbsent(event.getEventType(), ignored -> new AtomicInteger()).incrementAndGet();
                    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED && rawScrollEvents.size() < 1000)
                        rawScrollEvents.add(new long[] {event.getEventTime(), event.getScrollX(), event.getScrollY(),
                            event.getMaxScrollX(), event.getMaxScrollY(),
                            Build.VERSION.SDK_INT >= 28 ? event.getScrollDeltaX() : 0,
                            Build.VERSION.SDK_INT >= 28 ? event.getScrollDeltaY() : 0});
                    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED && rawSelectionEvents.size() < 1000)
                        rawSelectionEvents.add(event.getEventTime() + " " + event.getText() + " " + event.getContentDescription());
                }
                if (APP.contentEquals(event.getPackageName() == null ? "" : event.getPackageName()) &&
                        event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED && event.getMaxScrollY() > 0 &&
                        event.getEventTime() >= gestureStart) {
                    // A tab pager's horizontal scroll event cannot certify a gallery response.
                    scrollEvent.compareAndSet(null, new long[] {event.getEventTime(), event.getScrollX(),
                            event.getScrollY(), event.getMaxScrollX(), event.getMaxScrollY()});
                }
            });
            String geometry = arguments.getString("replayGeometry");
            if (replay && geometry != null) {
                require(geometry.matches("[0-9]+(,[0-9]+){11}"), "Invalid replay geometry");
                String[] values = geometry.split(","); int[] coordinates = new int[12];
                for (int i = 0; i < coordinates.length; i++) coordinates[i] = Integer.parseInt(values[i]);
                replayArtists = new Rect(coordinates[0], coordinates[1], coordinates[2], coordinates[3]);
                replayAlbums = new Rect(coordinates[4], coordinates[5], coordinates[6], coordinates[7]);
                replayBounds = new Rect(coordinates[8], coordinates[9], coordinates[10], coordinates[11]);
                require(!replayArtists.isEmpty() && !replayAlbums.isEmpty() && replayBounds.contains(replayArtists) &&
                        replayBounds.contains(replayAlbums), "Replay targets outside discovered bounds");
            } else if (replay) {
                require("true".equals(arguments.getString("discoveryOnly")), "Replay requires a separate discovery invocation");
                long discoveryStart = SystemClock.uptimeMillis();
                write(directory, "discovery-launch.txt", shell("am start -W -f 0x10008000 -n " + APP + "/io.github.anastaci1a.localica.MainActivity"));
                do {
                    ObservedNode discovery = snapshot(automation.getRootInActiveWindow());
                    if (discovery != null && APP.equals(discovery.packageName)) {
                        replayArtists = findNavigation(discovery, "Artists");
                        replayAlbums = findNavigation(discovery, "Albums");
                        replayBounds = new Rect(discovery.bounds);
                    }
                    if (replayArtists != null && replayAlbums != null) break;
                    SystemClock.sleep(100);
                } while (SystemClock.uptimeMillis() - discoveryStart < 60_000);
                require(replayArtists != null && replayAlbums != null, "Discovery requires both navigation targets");
                write(directory, "discovery.json", new JSONObject().put("outsideMeasurement", true)
                        .put("elapsedMs", SystemClock.uptimeMillis() - discoveryStart)
                        .put("geometry", geometry(replayArtists) + "," + geometry(replayAlbums) + "," + geometry(replayBounds))
                        .put("artists", replayArtists.toShortString()).put("albums", replayAlbums.toShortString())
                        .put("gestureBounds", replayBounds.toShortString()).toString(2));
                result.putString("stream", "Geometry discovered outside measurement; reseed before replay.\n");
                finish(0, result); return;
            }
            JSONObject identity = new JSONObject().put("api", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                    .put("controllerPid", android.os.Process.myPid()).put("controllerPackage", getTargetContext().getPackageName())
                    .put("target", APP).put("trace", trace).put("windowMs", WINDOW_MS)
                    .put("fixture", arguments.getString("fixture", "unspecified"))
                    .put("inputMode", replay ? "scheduled-replay" : "adaptive-polling")
                    .put("observer", replay ? "Coordinates discovered before force-stop; scheduled input and vertical accessibility events; readiness unmeasured" :
                            "Accessibility root polling 100ms; first observed shell/content and input response; not presented-pixel latency")
                    .put("coldDefinition", "am force-stop and no target PID before launch; filesystem/OS caches not cleared")
                    .put("device", shell("wm size; wm density; dumpsys package " + APP + " | grep versionName"));
            write(directory, "identity.json", identity.toString(2));
            String coldPid = measure(directory, "cold", trace, null);
            // Process-warm control: same process/caches, fresh activity/task.
            // Background loading can continue; this is separate from resume latency.
            SystemClock.sleep(3_000);
            measure(directory, "warm", trace, coldPid);
            result.putString("stream", "Evidence: " + directory + "\n");
            finish(0, result);
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure));
            finish(1, result);
        }
    }

    private String measure(File directory, String mode, boolean trace, String expectedPid) throws Exception {
        File output = new File(directory, mode);
        require(output.mkdir(), "Cannot create trial directory");
        if (mode.equals("cold")) {
            shell("am force-stop " + APP);
            require(shell("pidof " + APP).trim().isEmpty(), "Cold target still running");
        } else {
            require(expectedPid.equals(shell("pidof " + APP).trim()), "Warm process replaced before launch");
        }
        shell("dumpsys gfxinfo " + APP + " reset");
        if (trace) write(output, "trace-start.txt", shell("atrace --async_start -b 32768 -a " + APP + " sched gfx view dalvik am wm binder_driver"));
        JSONObject report = new JSONObject().put("mode", mode).put("windowMs", WINDOW_MS)
                .put("launchUptimeMs", SystemClock.uptimeMillis());
        long start = report.getLong("launchUptimeMs");
        rawScrollEvents.clear(); rawSelectionEvents.clear(); eventCounts.clear(); eventWindowStart = start;
        JSONArray inputs = new JSONArray();
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicInteger snapshots = new AtomicInteger();
        AtomicLong samplingMs = new AtomicLong();
        AtomicReference<Throwable> samplingFailure = new AtomicReference<>();
        // gfxinfo retains a bounded ring. Overlapping one-second snapshots prevent a
        // settled 60Hz window from silently replacing its startup frames before finish.
        Thread frameSampler = new Thread(() -> {
            try {
                while (sampling.get()) {
                    Thread.sleep(1_000);
                    if (!sampling.get() || SystemClock.uptimeMillis() - start >= WINDOW_MS) break;
                    long before = SystemClock.uptimeMillis();
                    String frames = frameSnapshot();
                    samplingMs.addAndGet(SystemClock.uptimeMillis() - before);
                    write(output, String.format(java.util.Locale.ROOT, "gfxinfo-%03d.txt", snapshots.incrementAndGet()), frames);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) { samplingFailure.set(failure); }
        }, "startup-frame-snapshots");
        frameSampler.start();
        try {
            write(output, "launch.txt", shell("am start -W -f 0x10008000 -n " + APP + "/io.github.anastaci1a.localica.MainActivity"));
            if (replay) {
                replayWindow(report, inputs, start);
            } else {
            long shellAt = 0, firstContent = 0;
            int step = 0, polls = 0;
            JSONObject navigation = null;
            long navigationAt = 0;
            boolean navigated = false;
            long accessibilityReadMs = 0, accessibilityNodes = 0;
            while (SystemClock.uptimeMillis() - start < WINDOW_MS) {
                long readStart = SystemClock.uptimeMillis();
                ObservedNode root = snapshot(automation.getRootInActiveWindow());
                accessibilityReadMs += SystemClock.uptimeMillis() - readStart;
                polls++;
                if (root != null) accessibilityNodes += root.nodes;
                if (SystemClock.uptimeMillis() - start >= WINDOW_MS) break;
                if (root != null) {
                        if (APP.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                            Rect artists = find(root, "Artists", false);
                            Rect albums = find(root, "Albums", false);
                            if (shellAt == 0 && artists != null && albums != null) shellAt = SystemClock.uptimeMillis();
                            if (firstContent == 0 && (hasPrefix(root, "Diagnostic album") || hasPrefix(root, "Diagnostic artist")))
                                firstContent = SystemClock.uptimeMillis();
                            if ((step == 1 || step == 4) && navigation != null &&
                                    !navigation.has("selectedObservedMs") && selected(root, step == 1 ? "Artists" : "Albums")) {
                                navigation.put("selectedObservedMs", SystemClock.uptimeMillis() - start)
                                        .put("selectionResponseMs", SystemClock.uptimeMillis() - navigationAt);
                            }
                            if (step == 0 && artists != null) {
                                navigationAt = SystemClock.uptimeMillis();
                                navigation = new JSONObject().put("kind", "tap-artists").put("requestedMs", navigationAt - start);
                                tap(artists);
                                navigation.put("injectionReturnedMs", SystemClock.uptimeMillis() - start);
                                inputs.put(navigation);
                                step = 1;
                            } else if (step == 1 && selected(root, "Artists") && hasPrefix(root, "Diagnostic artist") &&
                                    !hasPrefix(root, "Diagnostic album")) {
                                navigation.put("contentObservedMs", SystemClock.uptimeMillis() - start)
                                        .put("responseMs", SystemClock.uptimeMillis() - navigationAt);
                                navigated = true;
                                // Begin a real-time drag as soon as the navigated content exists.
                                Rect bounds = new Rect();
                                root.getBoundsInScreen(bounds);
                                JSONObject swipe = new JSONObject().put("kind", "gallery-drag");
                                gestureStart = SystemClock.uptimeMillis();
                                scrollEvent.set(null);
                                swipe.put("requestedMs", gestureStart - start);
                                swipe(bounds, 20);
                                swipe.put("injectionReturnedMs", SystemClock.uptimeMillis() - start);
                                inputs.put(swipe);
                                navigation = swipe;
                                step = 2;
                            } else if (step == 2 && scrollEvent.get() != null) {
                                recordScroll(navigation, start);
                                step = 3;
                            } else if (step == 3 && albums != null) {
                                navigationAt = SystemClock.uptimeMillis();
                                navigation = new JSONObject().put("kind", "tap-albums").put("requestedMs", navigationAt - start);
                                tap(albums);
                                navigation.put("injectionReturnedMs", SystemClock.uptimeMillis() - start);
                                inputs.put(navigation);
                                step = 4;
                            } else if (step == 4 && selected(root, "Albums") && hasPrefix(root, "Diagnostic album")) {
                                navigation.put("contentObservedMs", SystemClock.uptimeMillis() - start)
                                        .put("responseMs", SystemClock.uptimeMillis() - navigationAt);
                                Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
                                navigation = new JSONObject().put("kind", "gallery-fling");
                                gestureStart = SystemClock.uptimeMillis();
                                scrollEvent.set(null);
                                navigation.put("requestedMs", gestureStart - start);
                                swipe(bounds, 6);
                                navigation.put("injectionReturnedMs", SystemClock.uptimeMillis() - start);
                                inputs.put(navigation);
                                step = 5;
                            } else if (step == 5 && scrollEvent.get() != null) {
                                recordScroll(navigation, start);
                                step = 6;
                            }
                        }
                }
                SystemClock.sleep(100);
            }
            report.put("shellObservedMs", shellAt == 0 ? JSONObject.NULL : shellAt - start)
                    .put("firstContentObservedMs", firstContent == 0 ? JSONObject.NULL : firstContent - start)
                    .put("inputs", inputs).put("polls", polls).put("navigationContentObserved", navigated)
                    .put("accessibilityReadMs", accessibilityReadMs).put("accessibilityNodes", accessibilityNodes)
                    .put("controlLoopEndedMs", SystemClock.uptimeMillis() - start)
                    .put("dragScrollEventObserved", step >= 3).put("flingScrollEventObserved", step == 6)
                    .put("firstFiveSecondInputAttempt", inputs.length() > 0 && inputs.getJSONObject(0).getLong("requestedMs") <= 5_000);
            }
        } finally {
            eventWindowStart = Long.MAX_VALUE;
            JSONObject counts = new JSONObject();
            for (Map.Entry<Integer, AtomicInteger> entry : eventCounts.entrySet())
                counts.put(AccessibilityEvent.eventTypeToString(entry.getKey()), entry.getValue().get());
            JSONArray rawEvents = new JSONArray();
            for (long[] event : rawScrollEvents) {
                JSONArray values = new JSONArray(); for (long value : event) values.put(value); rawEvents.put(values);
            }
            write(output, "accessibility-events.json", new JSONObject().put("eventTypeCounts", counts)
                    .put("scrollColumns", "eventUptimeMs,scrollX,scrollY,maxScrollX,maxScrollY,deltaX,deltaY")
                    .put("scrolls", rawEvents).put("selections", new JSONArray(rawSelectionEvents)).toString(2));
            sampling.set(false);
            frameSampler.interrupt();
            frameSampler.join(5_000);
            report.put("frameSamplerStopped", !frameSampler.isAlive()).put("frameSnapshots", snapshots.get())
                    .put("frameSnapshotCommandMs", samplingMs.get())
                    .put("frameSamplerFailure", samplingFailure.get() == null ? JSONObject.NULL : samplingFailure.get().toString());
            if (trace) capture(output, "trace.txt", "atrace --async_stop");
            write(output, "gfxinfo.txt", frameSnapshot());
            write(output, "cpuinfo.txt", shell("dumpsys cpuinfo"));
            write(output, "meminfo.txt", shell("dumpsys meminfo " + APP));
            write(output, "threads.txt", shell("startup_pid=$(pidof " + APP + "); if [ -n \"$startup_pid\" ]; then " +
                    "for startup_task in /proc/$startup_pid/task/*; do printf '%s ' \"${startup_task##*/}\"; cat \"$startup_task/comm\"; done; fi"));
            write(output, "milestones.json", report.toString(2));
        }
        String pid = shell("pidof " + APP).trim();
        require(!pid.isEmpty(), "Target exited");
        if (expectedPid != null) require(expectedPid.equals(pid), "Warm target was process-cold");
        report.put("pid", pid).put("sameProcessAsCold", expectedPid == null ? JSONObject.NULL : expectedPid.equals(pid));
        write(output, "milestones.json", report.toString(2));
        // Return to Albums outside the measurement; warm activity starts from a comparable tab.
        if (replay) { tap(replayAlbums); }
        else {
            ObservedNode root = snapshot(automation.getRootInActiveWindow());
            if (root != null) {
                Rect albums = find(root, "Albums", false); if (albums != null) tap(albums);
            }
        }
        return pid;
    }

    private static String geometry(Rect bounds) {
        return bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom;
    }
    private static Rect findNavigation(ObservedNode node, String label) {
        Rect result = null;
        if (node.visible && !node.bounds.isEmpty() && (matches(node.text, label, false) || matches(node.description, label, false)))
            result = new Rect(node.bounds);
        for (ObservedNode child : node.children) {
            Rect candidate = findNavigation(child, label);
            if (candidate != null && (result == null || candidate.centerY() > result.centerY())) result = candidate;
        }
        return result;
    }

    private void replayWindow(JSONObject report, JSONArray inputs, long start) throws Exception {
        long[] planned = {2_000, 4_500, 8_000, 10_500};
        String[] kinds = {"tap-artists", "gallery-drag", "tap-albums", "gallery-fling"};
        JSONObject pendingScroll = null, drag = null, fling = null;
        int next = 0;
        while (SystemClock.uptimeMillis() - start < WINDOW_MS) {
            if (pendingScroll != null && scrollEvent.get() != null) {
                recordScroll(pendingScroll, start); pendingScroll = null;
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            if (next < planned.length && elapsed >= planned[next]) {
                JSONObject input = new JSONObject().put("kind", kinds[next]).put("plannedMs", planned[next])
                        .put("requestedMs", elapsed).put("scheduleDelayMs", elapsed - planned[next]);
                if (next == 0 || next == 2) tap(next == 0 ? replayArtists : replayAlbums);
                else {
                    gestureStart = SystemClock.uptimeMillis(); scrollEvent.set(null);
                    swipe(replayBounds, next == 1 ? 20 : 6);
                    pendingScroll = input;
                    if (next == 1) drag = input; else fling = input;
                }
                input.put("injectionReturnedMs", SystemClock.uptimeMillis() - start);
                inputs.put(input); next++;
            }
            SystemClock.sleep(20);
        }
        if (pendingScroll != null && scrollEvent.get() != null) recordScroll(pendingScroll, start);
        report.put("inputMode", "scheduled-replay").put("inputs", inputs)
                .put("shellObservedMs", JSONObject.NULL).put("firstContentObservedMs", JSONObject.NULL)
                .put("navigationContentObserved", false).put("navigationReadiness", "unmeasured; no hierarchy reads inside window")
                .put("accessibilityReadMs", 0).put("accessibilityNodes", 0).put("polls", 0)
                .put("controlLoopEndedMs", SystemClock.uptimeMillis() - start)
                .put("dragScrollEventObserved", drag != null && drag.has("scrollEventMs"))
                .put("flingScrollEventObserved", fling != null && fling.has("scrollEventMs"))
                .put("firstFiveSecondInputAttempt", inputs.length() > 0 && inputs.getJSONObject(0).getLong("requestedMs") <= 5_000);
    }

    private static final class ObservedNode {
        String text, description, packageName;
        boolean visible, selected;
        final Rect bounds = new Rect();
        final ArrayList<ObservedNode> children = new ArrayList<>();
        long nodes = 1;
        String getPackageName() { return packageName; }
        void getBoundsInScreen(Rect out) { out.set(bounds); }
    }
    private static ObservedNode snapshot(AccessibilityNodeInfo live) {
        if (live == null) return null;
        try {
            ObservedNode result = new ObservedNode();
            result.text = live.getText() == null ? null : live.getText().toString();
            result.description = live.getContentDescription() == null ? null : live.getContentDescription().toString();
            result.packageName = live.getPackageName() == null ? null : live.getPackageName().toString();
            result.visible = live.isVisibleToUser(); result.selected = live.isSelected();
            live.getBoundsInScreen(result.bounds);
            // Sample two cells in long scrollable galleries. Navigation and page
            // containers retain their full structure; this is a content-presence probe.
            int children = live.getChildCount();
            if (live.isScrollable() && children > 5) children = 2;
            for (int i = 0; i < children; i++) {
                AccessibilityNodeInfo child = Build.VERSION.SDK_INT >= 33
                        ? live.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST)
                        : live.getChild(i);
                ObservedNode observed = snapshot(child);
                if (observed != null) { result.children.add(observed); result.nodes += observed.nodes; }
            }
            return result;
        } finally { live.recycle(); }
    }
    private static Rect find(ObservedNode node, String text, boolean prefix) {
        if (matches(node.text, text, prefix) || matches(node.description, text, prefix)) {
            if (node.visible && !node.bounds.isEmpty()) return new Rect(node.bounds);
        }
        for (ObservedNode child : node.children) {
            Rect match = find(child, text, prefix); if (match != null) return match;
        }
        return null;
    }

    private static boolean hasPrefix(ObservedNode root, String prefix) { return find(root, prefix, true) != null; }
    private void recordScroll(JSONObject navigation, long start) throws Exception {
        long[] event = scrollEvent.get();
        navigation.put("scrollEventMs", event[0] - start).put("responseMs", event[0] - gestureStart)
                .put("observedMs", SystemClock.uptimeMillis() - start)
                .put("scrollX", event[1]).put("scrollY", event[2]).put("maxScrollX", event[3]).put("maxScrollY", event[4]);
    }
    private static boolean matches(CharSequence value, String text, boolean prefix) {
        return value != null && (prefix ? value.toString().startsWith(text) : value.toString().equals(text));
    }
    private static boolean selected(ObservedNode node, String text) {
        if (node.selected && find(node, text, false) != null) return true;
        for (ObservedNode child : node.children) {
            if (selected(child, text)) return true;
        }
        return false;
    }
    private void tap(Rect bounds) {
        long at = SystemClock.uptimeMillis();
        pointer(at, at, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY());
        SystemClock.sleep(40);
        pointer(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY());
    }
    private void swipe(Rect bounds, int steps) {
        long at = SystemClock.uptimeMillis();
        float x = bounds.centerX(), y = bounds.top + bounds.height() * .72f;
        pointer(at, at, MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= steps; i++) {
            SystemClock.sleep(16);
            pointer(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y - bounds.height() * .4f * i / steps);
        }
        pointer(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y - bounds.height() * .4f);
    }
    private void pointer(long down, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(automation.injectInputEvent(event, false), "Input injection refused"); }
        finally { event.recycle(); }
    }
    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("/system/bin/sh " + commandScript(command).getAbsolutePath()));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }
    private String frameSnapshot() throws Exception {
        String raw = shell("dumpsys gfxinfo " + APP + " framestats");
        return "# capturedAtNanos=" + System.nanoTime() + "\n" + raw;
    }
    private void capture(File directory, String name, String command) throws Exception {
        // Trace buffers can be tens of MiB; retain bytes without a second full-size
        // String/byte-array copy in the controller's heap between paired windows.
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("/system/bin/sh " + commandScript(command).getAbsolutePath()));
             FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }
    private synchronized File commandScript(String command) throws Exception {
        File existing = commandScripts.get(command);
        if (existing != null) return existing;
        File script = new File(commandDirectory, "command-" + commandScripts.size() + ".sh");
        require(!script.getAbsolutePath().matches(".*\\s.*"), "Command path contains whitespace");
        // UiAutomation's command API does not interpret shell operators or capture
        // stderr. A script gives pipes/substitutions real shell semantics and retains errors.
        write(commandDirectory, script.getName(), "#!/system/bin/sh\nexec 2>&1\n" + command + "\n");
        commandScripts.put(command, script);
        return script;
    }
    private static void write(File directory, String name, String value) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name))) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
