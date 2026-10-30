package com.termux.app.control.jobs;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Launches a previously admitted job on a worker using AppShell's managed capture path. */
public final class AppShellJobAdapter {
    interface Backend {
        Process launch(JobSpec spec, String executable, String cwd, AppShellJobCapture capture, Completion completion);
        /** True only when every launched child is an {@link OwnedChildProcess} that can be signalled safely. */
        default boolean ownsChildIdentity() { return false; }
    }
    interface Completion { void finished(Integer exitCode, boolean captureFailed); }

    private final Backend mBackend;

    public AppShellJobAdapter(Context context) {
        if (context == null) throw new IllegalArgumentException("Missing application context");
        mBackend = new AndroidBackend(context);
    }

    AppShellJobAdapter(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("Missing backend");
        mBackend = backend;
    }

    /**
     * The caller must authorize and resolve both absolute paths before calling this method. The
     * returned owned handle is for the coordinator; it must never be replaced by a numeric PID.
     * A callback may complete the already-published record before this method returns.
     */
    @Nullable public Process launch(JobRegistry.Entry entry, JobSpec spec, String executable, String cwd) {
        return launch(entry, spec, executable, cwd, null);
    }

    @Nullable public Process launch(JobRegistry.Entry entry, JobSpec spec, String executable, String cwd,
                                    @Nullable OwnedProcessControl control) {
        validate(entry, spec, executable, cwd, control);
        if (!entry.record().begin()) return null;
        return launchBegun(entry, spec, executable, cwd, control);
    }

    /** Coordinator must have atomically moved the published record to STARTING. */
    @Nullable Process launchBegun(JobRegistry.Entry entry, JobSpec spec, String executable, String cwd,
                                  @Nullable OwnedProcessControl control) {
        validate(entry, spec, executable, cwd, control);
        JobRecord record = entry.record();
        JobRecord.State state = record.snapshot().state;
        if (state != JobRecord.State.STARTING && state != JobRecord.State.CANCELLING)
            throw new IllegalStateException("Job was not prepared for launch");
        AtomicBoolean completed = new AtomicBoolean();
        Completion callback = (exitCode, captureFailed) -> {
            if (!completed.compareAndSet(false, true)) return;
            // An accepted stop request decides the outcome even if the child exited first.
            JobRecord.StopReason reason = record.snapshot().stopReason;
            if (reason == JobRecord.StopReason.CANCEL)
                record.complete(JobRecord.State.CANCELLED, null, null, System.currentTimeMillis());
            else if (reason == JobRecord.StopReason.TIMEOUT)
                record.complete(JobRecord.State.TIMED_OUT, null, null, System.currentTimeMillis());
            else if (captureFailed || exitCode == null)
                record.complete(JobRecord.State.FAILED, exitCode, JobRecord.Failure.CAPTURE_FAILED, System.currentTimeMillis());
            else record.complete(JobRecord.State.EXITED, exitCode, null, System.currentTimeMillis());
            if (control != null) control.completed();
        };
        try {
            Process process = mBackend.launch(spec, executable, cwd, entry.capture(), callback);
            if (process == null) {
                entry.capture().discardIfUnstarted();
                if (completed.compareAndSet(false, true))
                    record.complete(JobRecord.State.FAILED, null, JobRecord.Failure.START_FAILED, System.currentTimeMillis());
                if (control != null) control.completed();
                return null;
            }
            record.started(null, System.currentTimeMillis());
            if (control != null) control.attach(process);
            return process;
        } catch (RuntimeException | Error e) {
            entry.capture().discardIfUnstarted();
            if (completed.compareAndSet(false, true))
                record.complete(JobRecord.State.FAILED, null, JobRecord.Failure.START_FAILED, System.currentTimeMillis());
            if (control != null) control.completed();
            throw e;
        }
    }

    /** Whether stop requests can terminate a running child through its owned handle. */
    public boolean ownsChildIdentity() { return mBackend.ownsChildIdentity(); }

    private static void validate(JobRegistry.Entry entry, JobSpec spec, String executable, String cwd,
                                 OwnedProcessControl control) {
        if (entry == null || !entry.matches(spec) || !isNormalizedAbsolute(executable) || !isNormalizedAbsolute(cwd) ||
            (spec.executable().startsWith("/") && !spec.executable().equals(executable)) ||
            (spec.workingDirectory() != null && !spec.workingDirectory().equals(cwd)) ||
            (control != null && !control.owns(entry.record())))
            throw new IllegalArgumentException("Resolved absolute executable and cwd required");
    }

    static boolean isNormalizedAbsolute(String path) {
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0 || (path.length() > 1 && path.endsWith("/"))) return false;
        if (path.length() == 1) return true;
        for (String part : path.substring(1).split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static final class AndroidBackend implements Backend {
        private final Context mContext;
        AndroidBackend(Context context) { mContext = context; }

        @Override public Process launch(JobSpec spec, String executable, String cwd,
                                        AppShellJobCapture capture, Completion completion) {
            ExecutionCommand command = new ExecutionCommand(TermuxShellManager.getNextShellId(), executable,
                spec.arguments().toArray(new String[0]), null, cwd, ExecutionCommand.Runner.APP_SHELL.getName(), false);
            command.setShellCommandShellEnvironment = true;
            TermuxShellEnvironment environment = new TermuxShellEnvironment() {
                @Override @NonNull public String[] setupShellCommandArguments(@NonNull String path, @Nullable String[] arguments) {
                    return ShellUtils.setupShellCommandArguments(path, arguments);
                }
                /** Jobs start no shell, so take PATH from what an interactive login shell would set. */
                @Override @NonNull public HashMap<String, String> getEnvironment(@NonNull Context context, boolean isFailSafe) {
                    HashMap<String, String> base = super.getEnvironment(context, isFailSafe);
                    if (!isFailSafe) {
                        String path = ShellPath.forJobs(base);
                        if (path != null) base.put("PATH", path);
                    }
                    return base;
                }
            };
            AppShell.ManagedLauncher launcher = !ownsChildIdentity() ? null : (argv, environ, directory) -> {
                OwnedChildProcess process = OwnedChildProcess.launch(argv, environ, directory);
                if (process == null) throw new java.io.IOException("Managed launcher unavailable");
                return process;
            };
            AppShell shell = AppShell.executeManaged(mContext, command, finished -> {
                ExecutionCommand result = finished.getExecutionCommand();
                completion.finished(result.resultData.exitCode, result.isStateFailed());
            }, environment, new HashMap<>(spec.environment()), capture, launcher);
            return shell == null ? null : shell.getProcess();
        }

        /** No fallback to Runtime.exec once the library loaded: that child could not be signalled safely. */
        @Override public boolean ownsChildIdentity() { return ManagedNative.isLibraryAvailable(); }
    }
}
