package com.termux.app.control.execution;

import java.util.List;

/**
 * Placeholder for an isolated guest. It is deliberately not an implementation: feasibility was reviewed (see
 * docs/next-generation/execution-backend.md) and the prerequisites for shipping one are not met, so every operation answers
 * {@code UNSUPPORTED} and says why. It starts no process, opens no socket and writes no file, so it cannot affect native
 * execution. Support can only appear when {@link VirtualizationCapabilityDetector} reports all requirements met, which
 * requires a packaged, integrity-checked and license-reviewed runtime that this build does not contain.
 */
public final class IsolatedVmBackend implements ExecutionBackend {
    public static final String ID = "isolated-vm";
    private final VirtualizationCapabilityDetector.Report mReport;

    public IsolatedVmBackend(VirtualizationCapabilityDetector.Report report) {
        if (report == null) throw new IllegalArgumentException("Missing capability report");
        mReport = report;
    }

    @Override public String id() { return ID; }
    @Override public Capabilities capabilities() { return new Capabilities(false, mReport.blockers); }

    private Result refused() { return Result.refused(mReport.blockers.isEmpty() ? "no isolated runtime is implemented in this build" : mReport.blockers.get(0)); }

    @Override public Result prepare() { return refused(); }
    @Override public Result start() { return refused(); }
    @Override public Result stop() { return Result.ok(Status.UNSUPPORTED); } // nothing can be running, so stopping is trivially done
    @Override public Status status() { return Status.UNSUPPORTED; }
    @Override public byte[] logs(int maxBytes) { return new byte[0]; }
    @Override public Result terminal() { return refused(); }
    @Override public Result resources(long memoryBytes, int cpus) { return refused(); }
    @Override public Result ports(List<Integer> ports) { return refused(); }
    @Override public Result cleanup() { return Result.ok(Status.UNSUPPORTED); }
}
