package com.termux.app.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reports, from facts it was given, which resource and process features this device can provide and what Android
 * restrictions may affect long builds. It reads nothing itself and changes nothing: every capability comes from a measured
 * input, never a constant, and unknown stays unknown.
 *
 * <p>It never runs or schedules a command that modifies device settings ({@code device_config}, {@code settings global},
 * {@code oom_score_adj}, lmkd). The ADB commands it lists are for the user to copy and run themselves, with the caveat
 * that availability and effect depend on the Android build and manufacturer.
 */
public final class DeviceCompatibilityAdvisor {
    private DeviceCompatibilityAdvisor() { }

    public enum Status { SUPPORTED, UNSUPPORTED, UNKNOWN, INFO }

    /** Measured facts; null means "could not be determined". */
    public static final class Inputs {
        public Integer sdkInt;
        public String release, manufacturer, model, kernelVersion;
        public List<String> abis = new ArrayList<>();
        public Boolean nativeLauncherAvailable, memoryApiAvailable, thermalApiAvailable, procListingVisible, foregroundServiceRunning,
            ignoringBatteryOptimizations, notificationsAllowed;
        /** Number of other same-UID processes visible in /proc, if it could be listed. */
        public Integer visibleProcessCount;
    }

    public static final class Item {
        public final String id; public final Status status; public final String detail, advice;
        Item(String id, Status status, String detail, String advice) { this.id = id; this.status = status; this.detail = detail; this.advice = advice; }
    }

    public static final class Report {
        public final List<Item> items; public final List<String> adbDiagnostics, adbOptionalChanges;
        Report(List<Item> items, List<String> diagnostics, List<String> changes) {
            this.items = Collections.unmodifiableList(items); adbDiagnostics = Collections.unmodifiableList(diagnostics); adbOptionalChanges = Collections.unmodifiableList(changes);
        }
        public Item item(String id) { for (Item i : items) if (i.id.equals(id)) return i; return null; }

        /** Capability flags in the shape of the {@code resource.capabilities} result; values derive from the items, never constants. */
        public JsonObject capabilitiesJson() {
            JsonObject limits = new JsonObject();
            boolean nativeOk = is("native_launcher");
            limits.addProperty("managedJobs", true);
            limits.addProperty("memorySoftMonitoring", is("memory_api") && is("process_metrics"));
            limits.addProperty("rlimitAs", nativeOk);
            limits.addProperty("rlimitCpu", nativeOk);
            limits.addProperty("cgroups", false);
            limits.addProperty("safeProcessStop", nativeOk);
            limits.addProperty("virtualMachine", false);
            JsonObject root = new JsonObject();
            root.add("resourceLimits", limits);
            return root;
        }
        private boolean is(String id) { Item i = item(id); return i != null && i.status == Status.SUPPORTED; }

        public JsonObject toJson() {
            JsonArray array = new JsonArray();
            for (Item i : items) {
                JsonObject o = new JsonObject();
                o.addProperty("id", i.id); o.addProperty("status", i.status.name().toLowerCase(Locale.ROOT));
                o.addProperty("detail", i.detail); o.addProperty("advice", i.advice);
                array.add(o);
            }
            JsonObject root = capabilitiesJson();
            root.add("items", array);
            JsonArray diag = new JsonArray(); for (String c : adbDiagnostics) diag.add(c);
            JsonArray change = new JsonArray(); for (String c : adbOptionalChanges) change.add(c);
            root.add("adbDiagnostics", diag); root.add("adbOptionalChangesNotRunByTermux", change);
            return root;
        }
    }

    public static Report advise(Inputs in) {
        if (in == null) throw new IllegalArgumentException("Missing inputs");
        List<Item> items = new ArrayList<>();
        items.add(new Item("platform", Status.INFO, "Android " + orUnknown(in.release) + " (API " + orUnknown(in.sdkInt) + "), kernel " + orUnknown(in.kernelVersion)
            + ", ABI " + (in.abis.isEmpty() ? "unknown" : joinAbis(in.abis)), ""));
        items.add(flag("native_launcher", in.nativeLauncherAvailable, "owned-child launcher (stop, RLIMIT_AS/CPU, nice)",
            "Without it managed jobs cannot be stopped or limited; they still run."));
        items.add(flag("memory_api", in.memoryApiAvailable, "device memory from ActivityManager", "Pressure-based admission falls back to conservative defaults."));
        items.add(thermal(in));
        items.add(procMetrics(in));
        items.add(new Item("low_memory_killer", Status.INFO, "Android's low-memory killer can end processes at any time, including between two samples of this app.",
            "Sampling reduces risk and gives evidence; it cannot guarantee a build survives. Lower parallelism (-j) and use a protected profile for big builds."));
        items.add(phantom(in));
        items.add(oem(in));
        items.add(flag("foreground_service", in.foregroundServiceRunning, "Termux foreground service is running",
            "A foreground service keeps the process at a higher priority; without it Android may kill background work sooner."));
        items.add(battery(in));
        if (Boolean.FALSE.equals(in.notificationsAllowed))
            items.add(new Item("notifications", Status.UNSUPPORTED, "notification permission is denied", "The foreground-service notification may be hidden; the service still runs on most versions."));
        items.add(new Item("cgroups", Status.UNSUPPORTED, "not attempted: writing cgroup limits needs privileges this app does not have", "Use per-job RLIMIT_* and parallelism limits instead."));
        items.add(new Item("virtual_machine", Status.UNSUPPORTED, "no isolated execution backend is available", ""));
        return new Report(items, diagnostics(in), optionalChanges());
    }

    private static Item flag(String id, Boolean value, String what, String ifMissing) {
        if (value == null) return new Item(id, Status.UNKNOWN, what + ": could not be determined", ifMissing);
        return value ? new Item(id, Status.SUPPORTED, what, "") : new Item(id, Status.UNSUPPORTED, what + " is not available", ifMissing);
    }

    private static Item thermal(Inputs in) {
        if (in.sdkInt != null && in.sdkInt < 29) return new Item("thermal_api", Status.UNSUPPORTED, "thermal status needs Android 10", "Thermal throttling is not considered.");
        return flag("thermal_api", in.thermalApiAvailable, "thermal status from PowerManager", "Thermal throttling is not considered.");
    }

    private static Item procMetrics(Inputs in) {
        if (in.procListingVisible == null) return new Item("process_metrics", Status.UNKNOWN, "/proc visibility could not be determined", "");
        if (!in.procListingVisible) return new Item("process_metrics", Status.UNSUPPORTED, "/proc cannot be listed by this app", "Job memory will be unknown (null), not zero.");
        return new Item("process_metrics", Status.SUPPORTED, "/proc readable for the app's own processes"
            + (in.visibleProcessCount == null ? "" : " (" + in.visibleProcessCount + " visible)"), "Other apps' processes are hidden on Android 7+, so views are partial.");
    }

    private static Item phantom(Inputs in) {
        if (in.sdkInt == null) return new Item("phantom_process_policy", Status.UNKNOWN, "Android version unknown", "");
        if (in.sdkInt < 31) return new Item("phantom_process_policy", Status.INFO, "Android 11 and older do not have the phantom-process killer.", "");
        return new Item("phantom_process_policy", Status.INFO,
            "Android 12 and newer may end child processes of apps that spawn many or CPU-heavy children. The limit and its behavior vary by Android build and manufacturer; no universal number applies.",
            "If long builds die with exit 137 despite free memory, check the diagnostics below. Prefer fewer parallel jobs.");
    }

    private static Item oem(Inputs in) {
        String m = in.manufacturer == null ? "" : in.manufacturer.toLowerCase(Locale.ROOT);
        for (String known : Arrays.asList("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "samsung", "meizu", "asus", "tecno", "infinix"))
            if (m.contains(known)) return new Item("oem_background_restrictions", Status.INFO,
                orUnknown(in.manufacturer) + " devices often add background-process limits beyond stock Android.",
                "Look for an app-specific battery or autostart setting for Termux in the system settings. Termux cannot change it.");
        return new Item("oem_background_restrictions", Status.INFO, "No manufacturer-specific restrictions are known for " + orUnknown(in.manufacturer) + ".", "");
    }

    private static Item battery(Inputs in) {
        if (in.ignoringBatteryOptimizations == null) return new Item("battery_optimization", Status.UNKNOWN, "battery optimization state unknown", "");
        return in.ignoringBatteryOptimizations ? new Item("battery_optimization", Status.SUPPORTED, "Termux is exempt from battery optimization", "")
            : new Item("battery_optimization", Status.INFO, "Termux is subject to battery optimization", "Long background work may be deferred or ended; exempting Termux is the user's choice in system settings.");
    }

    /** Read-only commands. */
    private static List<String> diagnostics(Inputs in) {
        List<String> commands = new ArrayList<>();
        commands.add("adb shell getprop ro.build.version.release");
        if (in.sdkInt != null && in.sdkInt >= 31) {
            commands.add("adb shell device_config get activity_manager max_phantom_processes");
            commands.add("adb shell settings get global settings_enable_monitor_phantom_procs");
        }
        commands.add("adb shell dumpsys meminfo com.termux");
        commands.add("adb logcat -d -b events | grep -i \"am_kill\\|lmk\"");
        return commands;
    }

    /** Changes. Listed for the user to run knowingly; Termux never runs them. */
    private static List<String> optionalChanges() {
        return Arrays.asList(
            "adb shell device_config put activity_manager max_phantom_processes 2147483647   # changes system behavior; reverts on reboot or by Android; support varies",
            "adb shell settings put global settings_enable_monitor_phantom_procs false      # disables a platform safeguard; support varies");
    }

    /** String.join needs API 26, and the app supports API 21. */
    private static String joinAbis(List<String> abis) {
        StringBuilder out = new StringBuilder();
        for (String abi : abis) { if (out.length() > 0) out.append(','); out.append(abi); }
        return out.toString();
    }

    private static String orUnknown(Object value) { return value == null ? "unknown" : String.valueOf(value); }

    /** Collects the inputs from the running device. Call off the main thread: it reads files. */
    public static Inputs collect(android.content.Context context, boolean nativeLauncher) {
        Inputs in = new Inputs();
        in.sdkInt = android.os.Build.VERSION.SDK_INT; in.release = android.os.Build.VERSION.RELEASE;
        in.manufacturer = android.os.Build.MANUFACTURER; in.model = android.os.Build.MODEL;
        in.kernelVersion = System.getProperty("os.version");
        in.abis = new ArrayList<>(Arrays.asList(android.os.Build.SUPPORTED_ABIS));
        in.nativeLauncherAvailable = nativeLauncher;
        DeviceSnapshot device = DeviceSnapshot.collect(context);
        in.memoryApiAvailable = device.totalMemoryBytes != null;
        in.thermalApiAvailable = device.thermalStatus != null;
        try {
            String[] names = new java.io.File("/proc").list();
            in.procListingVisible = names != null && names.length > 0;
            if (names != null) { int count = 0; for (String n : names) if (!n.isEmpty() && Character.isDigit(n.charAt(0))) count++; in.visibleProcessCount = count; }
        } catch (SecurityException e) { in.procListingVisible = false; }
        try {
            android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null) in.ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (RuntimeException ignored) { }
        return in;
    }
}
