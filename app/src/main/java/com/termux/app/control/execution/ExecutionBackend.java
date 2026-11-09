package com.termux.app.control.execution;

import java.util.List;

/**
 * Where a workspace's commands run. {@code native} is the default and the only backend that works; the isolated backend
 * exists so its absence is reported accurately instead of being faked.
 *
 * <p>Rules every implementation follows: a backend is selected explicitly (nothing is ever switched silently, in particular
 * never from native to isolated), an operation it cannot perform answers {@link Result#UNSUPPORTED} with a reason rather
 * than throwing or pretending, and native execution keeps working whatever any other backend reports.
 *
 * <p>Methods may do I/O and must run on a worker thread.
 */
public interface ExecutionBackend {
    String id();

    enum Status { UNSUPPORTED, UNPREPARED, PREPARING, STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    /** Outcome of an operation: {@code ok} with a state, or a stable reason it was not done. */
    final class Result {
        public static final Result UNSUPPORTED = new Result(false, Status.UNSUPPORTED, "unsupported");
        public final boolean ok; public final Status status; public final String reason;
        public Result(boolean ok, Status status, String reason) { this.ok = ok; this.status = status; this.reason = reason; }
        public static Result ok(Status status) { return new Result(true, status, ""); }
        public static Result refused(String reason) { return new Result(false, Status.UNSUPPORTED, reason); }
    }

    /** What this backend can do on this device now, from measured facts. */
    final class Capabilities {
        public final boolean available;
        public final List<String> blockers;
        public Capabilities(boolean available, List<String> blockers) { this.available = available; this.blockers = blockers; }
    }

    Capabilities capabilities();
    Result prepare();
    Result start();
    Result stop();
    Status status();
    /** Bounded console or log tail; empty when none. */
    byte[] logs(int maxBytes);
    /** Terminal attachment, if the backend provides one. */
    Result terminal();
    /** Applies an explicit memory and CPU allocation; native execution is sized per job instead and refuses this. */
    Result resources(long memoryBytes, int cpus);
    /** Loopback port forwards this backend needs; native services use the Android network directly and need none. */
    Result ports(List<Integer> ports);
    /** Releases everything the backend holds; always safe to call. */
    Result cleanup();
}
