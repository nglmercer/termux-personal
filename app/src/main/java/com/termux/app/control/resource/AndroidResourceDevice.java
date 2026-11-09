package com.termux.app.control.resource;

import android.content.Context;
import com.termux.app.control.jobs.ManagedNative;
import com.termux.app.control.jobs.PressureTracker;
import com.termux.app.monitor.DeviceCompatibilityAdvisor;
import com.termux.app.monitor.DeviceSnapshot;
import com.termux.app.monitor.ProcessSnapshot;
import com.termux.shared.termux.TermuxConstants;
import java.io.File;
import java.util.Set;

/** Production {@link ResourceService.Device}. Every call reads system state and must run on a worker thread. */
public final class AndroidResourceDevice implements ResourceService.Device {
    private final Context mContext;
    private final long mTicks, mPageSize;

    public AndroidResourceDevice(Context context) {
        mContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        mTicks = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
        mPageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGE_SIZE);
    }

    @Override public PressureTracker.Reading reading() {
        DeviceSnapshot d = DeviceSnapshot.collect(mContext);
        return new PressureTracker.Reading(d.availableMemoryBytes, d.totalMemoryBytes, d.lowMemory, d.thermalStatus);
    }
    @Override public DeviceCompatibilityAdvisor.Inputs advisorInputs() {
        return DeviceCompatibilityAdvisor.collect(mContext, ManagedNative.isLibraryAvailable());
    }
    @Override public int cpuCount() { return Math.max(1, Runtime.getRuntime().availableProcessors()); }
    @Override public ProcessSnapshot processes(Set<Integer> roots) { return ProcessSnapshot.collect(new File("/proc"), android.os.Process.myUid(), roots); }
    @Override public long ticksPerSecond() { return mTicks; }
    @Override public long pageSizeBytes() { return mPageSize; }
    @Override public String systemSummary() {
        return "Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + "), " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
    }
    @Override public String homePath() { return TermuxConstants.TERMUX_HOME_DIR_PATH; }
    @Override public String prefixPath() { return TermuxConstants.TERMUX_PREFIX_DIR_PATH; }
}
