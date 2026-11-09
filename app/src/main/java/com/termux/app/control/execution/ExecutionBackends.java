package com.termux.app.control.execution;

import java.util.Arrays;
import java.util.List;

/** The known backends and the rule that native is the default. Selecting any other backend is always an explicit act. */
public final class ExecutionBackends {
    private final NativeExecutionBackend mNative = new NativeExecutionBackend();
    private final IsolatedVmBackend mIsolated;

    public ExecutionBackends(VirtualizationCapabilityDetector.Report virtualization) { mIsolated = new IsolatedVmBackend(virtualization); }

    public ExecutionBackend defaultBackend() { return mNative; }

    /** The backend with this ID, or null. An unknown ID is not replaced by the default: the caller must handle it. */
    public ExecutionBackend byId(String id) {
        if (NativeExecutionBackend.ID.equals(id)) return mNative;
        if (IsolatedVmBackend.ID.equals(id)) return mIsolated;
        return null;
    }

    public List<ExecutionBackend> all() { return Arrays.<ExecutionBackend>asList(mNative, mIsolated); }

    /** True only for a backend that reports itself available. Never falls back to another backend. */
    public boolean usable(String id) {
        ExecutionBackend backend = byId(id);
        return backend != null && backend.capabilities().available;
    }
}
