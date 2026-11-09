package com.termux.app.control.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides, from measured facts, whether this app could run an isolated Linux guest. Pure logic: the caller supplies the
 * facts, so the answer is testable and nothing is assumed from the Android version alone.
 *
 * <p>The verdict is {@code supported} only when every prerequisite holds, including a packaged runtime whose integrity was
 * verified. No such runtime ships with this app, so on every device the honest answer today is "not supported", with the
 * reasons listed. Unknown facts count against support: a missing fact is never treated as a pass.
 */
public final class VirtualizationCapabilityDetector {
    private VirtualizationCapabilityDetector() { }

    public enum Status { MET, NOT_MET, UNKNOWN }

    /** Measured facts; null means "could not be determined". */
    public static final class Facts {
        public Integer sdkInt;
        public List<String> abis = new ArrayList<>();
        /** {@code PackageManager.hasSystemFeature("android.software.virtualization_framework")}. */
        public Boolean avfSystemFeature;
        /** Whether this app holds {@code android.permission.MANAGE_VIRTUAL_MACHINE} (signature-level; normally unavailable to apps). */
        public Boolean holdsManageVirtualMachine;
        /** Whether {@code /dev/kvm} can be opened read-write by this app. */
        public Boolean kvmAccessible;
        public Long availableStorageBytes, availableMemoryBytes;
        /** Whether a guest runtime (hypervisor binary or library) is packaged with the app. */
        public Boolean runtimePackaged;
        /** Whether packaged guest images and runtime binaries have verified integrity data. */
        public Boolean runtimeIntegrityVerified;
        public Boolean licenseReviewed;
    }

    public static final class Requirement {
        public final String id; public final Status status; public final String detail;
        Requirement(String id, Status status, String detail) { this.id = id; this.status = status; this.detail = detail; }
    }

    public static final class Report {
        public final boolean supported;
        public final List<Requirement> requirements;
        /** Why the isolated backend is unavailable, for the capability description; empty only when supported. */
        public final List<String> blockers;
        Report(boolean supported, List<Requirement> requirements, List<String> blockers) {
            this.supported = supported;
            this.requirements = Collections.unmodifiableList(requirements); this.blockers = Collections.unmodifiableList(blockers);
        }
    }

    static final long MIN_STORAGE_BYTES = 2L << 30, MIN_MEMORY_BYTES = 2L << 30;

    public static Report detect(Facts f) {
        if (f == null) throw new IllegalArgumentException("Missing facts");
        List<Requirement> r = new ArrayList<>();
        boolean arm64 = f.abis != null && f.abis.contains("arm64-v8a") || f.abis != null && f.abis.contains("x86_64");
        r.add(new Requirement("architecture", f.abis == null || f.abis.isEmpty() ? Status.UNKNOWN : arm64 ? Status.MET : Status.NOT_MET,
            "a 64-bit ABI is required for a practical guest"));
        r.add(new Requirement("android_version", f.sdkInt == null ? Status.UNKNOWN : f.sdkInt >= 34 ? Status.MET : Status.NOT_MET,
            "the platform virtualization framework needs Android 14 or newer"));
        r.add(new Requirement("avf_feature", flag(f.avfSystemFeature),
            "the device must declare android.software.virtualization_framework; a recent Android version does not imply it"));
        r.add(new Requirement("avf_permission", flag(f.holdsManageVirtualMachine),
            "creating a virtual machine through the platform needs MANAGE_VIRTUAL_MACHINE, a signature-level permission this app does not hold"));
        r.add(new Requirement("hardware_acceleration", flag(f.kvmAccessible),
            "/dev/kvm must be usable by the app; without it a guest runs fully emulated and is slow"));
        r.add(new Requirement("storage", f.availableStorageBytes == null ? Status.UNKNOWN : f.availableStorageBytes >= MIN_STORAGE_BYTES ? Status.MET : Status.NOT_MET,
            "at least 2 GiB free for a guest image"));
        r.add(new Requirement("memory", f.availableMemoryBytes == null ? Status.UNKNOWN : f.availableMemoryBytes >= MIN_MEMORY_BYTES ? Status.MET : Status.NOT_MET,
            "at least 2 GiB available to allocate to a guest"));
        r.add(new Requirement("runtime_packaged", flag(f.runtimePackaged), "a hypervisor or guest runtime must ship with the app; none does"));
        r.add(new Requirement("integrity", flag(f.runtimeIntegrityVerified), "runtime binaries and guest images need verified integrity data"));
        r.add(new Requirement("license_review", flag(f.licenseReviewed), "redistributing a hypervisor and guest images needs a completed license review"));
        // Two routes can provide a guest: the platform framework (feature and permission) or a bundled emulator. Either needs the
        // shared prerequisites below, and a bundled runtime additionally needs packaging, integrity and license review.
        boolean platformRoute = met(r, "android_version") && met(r, "avf_feature") && met(r, "avf_permission");
        boolean bundledRoute = met(r, "runtime_packaged") && met(r, "integrity") && met(r, "license_review");
        boolean shared = met(r, "architecture") && met(r, "storage") && met(r, "memory");
        boolean supported = shared && (platformRoute || bundledRoute);
        List<String> blockers = new ArrayList<>();
        if (!supported) for (Requirement req : r) if (req.status != Status.MET && !req.id.equals("hardware_acceleration"))
            blockers.add(req.id + (req.status == Status.UNKNOWN ? " (unknown)" : "") + ": " + req.detail);
        return new Report(supported, r, blockers);
    }

    private static Status flag(Boolean value) { return value == null ? Status.UNKNOWN : value ? Status.MET : Status.NOT_MET; }

    private static boolean met(List<Requirement> list, String id) {
        for (Requirement req : list) if (req.id.equals(id)) return req.status == Status.MET;
        return false;
    }

    /** Collects the facts on a device. Reads files, so call it off the main thread. */
    public static Facts collect(android.content.Context context) {
        Facts f = new Facts();
        f.sdkInt = android.os.Build.VERSION.SDK_INT;
        f.abis = new ArrayList<>(java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS));
        try { f.avfSystemFeature = context.getPackageManager().hasSystemFeature("android.software.virtualization_framework"); }
        catch (RuntimeException e) { f.avfSystemFeature = null; }
        try {
            f.holdsManageVirtualMachine = context.checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) { f.holdsManageVirtualMachine = null; }
        java.io.File kvm = new java.io.File("/dev/kvm");
        f.kvmAccessible = kvm.exists() && kvm.canRead() && kvm.canWrite();
        try { f.availableStorageBytes = context.getFilesDir().getUsableSpace(); } catch (RuntimeException e) { f.availableStorageBytes = null; }
        com.termux.app.monitor.DeviceSnapshot device = com.termux.app.monitor.DeviceSnapshot.collect(context);
        f.availableMemoryBytes = device.availableMemoryBytes;
        // Nothing is packaged, verified or reviewed: these are facts about this build, not guesses.
        f.runtimePackaged = false; f.runtimeIntegrityVerified = false; f.licenseReviewed = false;
        return f;
    }
}
