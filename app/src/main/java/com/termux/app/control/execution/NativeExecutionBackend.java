package com.termux.app.control.execution;

import java.util.Collections;
import java.util.List;

/**
 * The default backend: commands run as native Termux processes through the one job coordinator. This class launches
 * nothing itself (that would be a second launcher); it describes the backend so a caller can discover that native
 * execution needs no preparation and is always available.
 */
public final class NativeExecutionBackend implements ExecutionBackend {
    public static final String ID = "native";
    @Override public String id() { return ID; }
    @Override public Capabilities capabilities() { return new Capabilities(true, Collections.<String>emptyList()); }
    @Override public Result prepare() { return Result.ok(Status.RUNNING); }
    @Override public Result start() { return Result.refused("native commands are started as managed jobs, not as a backend"); }
    @Override public Result stop() { return Result.refused("native commands are stopped through their jobs"); }
    @Override public Status status() { return Status.RUNNING; }
    @Override public byte[] logs(int maxBytes) { return new byte[0]; }
    @Override public Result terminal() { return Result.refused("native terminals are Termux sessions"); }
    @Override public Result resources(long memoryBytes, int cpus) { return Result.refused("native execution is limited per job, not per backend"); }
    @Override public Result ports(List<Integer> ports) { return Result.ok(Status.RUNNING); }
    @Override public Result cleanup() { return Result.ok(Status.RUNNING); }
}
