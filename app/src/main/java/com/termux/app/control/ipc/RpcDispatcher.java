package com.termux.app.control.ipc;

import com.google.gson.stream.JsonWriter;
import com.termux.app.control.ControlCore;
import com.termux.app.control.jobs.JobAuthority;
import com.termux.app.control.jobs.JobCoordinator;
import com.termux.app.control.jobs.JobOutputStore;
import com.termux.app.control.jobs.JobRecord;
import com.termux.app.control.jobs.JobSpec;
import com.termux.app.control.files.FileService;
import com.termux.app.control.process.ProcessService;
import com.termux.app.control.workspace.GitCommandBuilder;
import com.termux.app.control.workspace.WorkspaceRepository;
import com.termux.app.monitor.ProcessSnapshot;
import com.termux.app.monitor.ProcessTable;
import com.termux.terminal.OutputReplayBuffer;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One authenticated connection's JSON dispatcher. Call core operations on the service thread. */
public final class RpcDispatcher {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final ControlCore mCore;
    private final RpcNegotiation mNegotiation = new RpcNegotiation();
    private final OutputClientLease mOutput;
    private final DomainServices mServices;

    public RpcDispatcher(ControlCore core) { this(core, null, DomainServices.NONE); }

    RpcDispatcher(ControlCore core, OutputClientLease output) { this(core, output, DomainServices.NONE); }

    /** Job methods are disabled unless a coordinator and its trusted consent authority are supplied together. */
    public RpcDispatcher(ControlCore core, JobCoordinator jobs, JobAuthority jobAuthority) {
        this(core, null, new DomainServices(jobs, jobAuthority, null, null, null));
    }

    public RpcDispatcher(ControlCore core, JobCoordinator jobs, JobAuthority jobAuthority, WorkspaceRepository workspaces) {
        this(core, null, new DomainServices(jobs, jobAuthority, null, null, workspaces));
    }

    RpcDispatcher(ControlCore core, OutputClientLease output, DomainServices services) {
        if (core == null || services == null) throw new IllegalArgumentException("core and services required");
        mCore = core; mOutput = output; mServices = services;
    }

    /** Scopes must be derived from trusted peer authorization, never request parameters. */
    public byte[] dispatch(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes) {
        return dispatchResult(request, scopes, null).json;
    }

    byte[] dispatchBinary(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes, byte[] binary) {
        return dispatchResult(request, scopes, binary).json;
    }

    /** JSON response bytes plus an optional binary frame that follows them on the wire. */
    public static final class Result {
        public final byte[] json, binary;
        Result(byte[] json, byte[] binary) { this.json = json; this.binary = binary; }
    }
    private static Result json(byte[] data) { return new Result(data, null); }
    private static final class OutputValue {
        final Object metadata;
        final byte[] bytes;
        OutputValue(Object metadata, byte[] bytes) { this.metadata = metadata; this.bytes = bytes; }
    }

    public Result dispatchResult(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes, byte[] binary) {
        return dispatchResult(request, scopes, binary, null);
    }
    Result dispatchResult(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes, byte[] binary, String newToken) {
        if (request == null) return json(error(null, -32600, "Invalid Request"));
        try {
            mNegotiation.accept(request);
            if (!request.hasId && request.method.startsWith("session.output.")) return json(null);
            Object result = invoke(request, scopes, binary, newToken);
            if (!request.hasId) return json(null);
            OutputValue output = result instanceof OutputValue ? (OutputValue) result : null;
            byte[] response = response(request.id, output == null ? result : output.metadata, null, 0);
            return response.length <= MAX_RESPONSE_BYTES ? new Result(response, output == null ? null : output.bytes) :
                json(error(request.id, -32000, "Response limit exceeded"));
        } catch (RpcRequestParser.InvalidRequest e) {
            return json(request.hasId ? error(request.id, -32600, "Invalid Request") : null);
        } catch (BadParameters e) {
            return json(request.hasId ? error(request.id, -32602, "Invalid params") : null);
        } catch (UnknownMethod e) {
            return json(request.hasId ? error(request.id, -32601, "Method not found") : null);
        } catch (ControlCore.ControlException e) {
            return json(request.hasId ? error(request.id, -32001, e.code.name()) : null);
        } catch (DomainError e) {
            return json(request.hasId ? error(request.id, -32001, e.code) : null);
        } catch (RuntimeException e) {
            // Never serialize backend exceptions, command data or environment to clients.
            return json(request.hasId ? error(request.id, -32603, "Internal error") : null);
        }
    }

    public boolean isNegotiated() { return mNegotiation.isReady(); }

    private Object invoke(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes, byte[] binary, String newToken)
        throws ControlCore.ControlException, BadParameters, UnknownMethod, DomainError {
        switch (request.method) {
            case "system.hello":
                return Collections.singletonMap("version", mCore.hello(ControlCore.PROTOCOL_VERSION));
            case "system.capabilities":
                parameters(request.params);
                List<String> methods = new ArrayList<>();
                for (ControlMethodRegistry.Descriptor descriptor : ControlMethodRegistry.available(mCore, scopes, features()))
                    methods.add(descriptor.name);
                return Collections.singletonMap("methods", methods);
            case "system.describe": {
                parameters(request.params);
                List<Object> descriptions = new ArrayList<>();
                for (ControlMethodRegistry.Descriptor descriptor : ControlMethodRegistry.available(mCore, scopes, features()))
                    descriptions.add(descriptor.describe());
                Map<String, Object> limits = new LinkedHashMap<>();
                limits.put("maxFrameBytes", FrameCodec.MAX_FRAME_BYTES);
                limits.put("maxJsonRequestBytes", RpcRequestParser.MAX_REQUEST_BYTES);
                limits.put("maxBinaryChunkBytes", ControlCore.MAX_WRITE_BYTES);
                limits.put("hardMemoryLimitAvailable", false);
                // Cancel of a running job and wall timeouts need an owned child; queued cancel always works.
                if (mServices.jobs != null) limits.put("jobStopAvailable", mServices.jobs.canStopRunningJobs());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("protocolVersion", ControlCore.PROTOCOL_VERSION); result.put("schemaRevision", "1.1");
                result.put("methods", descriptions); result.put("limits", limits);
                return result;
            }
            case "session.list":
                parameters(request.params);
                List<ControlCore.SessionInfo> sessions = mCore.listSessions(scopes);
                if (sessions.size() > 128) throw new ControlCore.ControlException(ControlCore.Error.LIMIT_REACHED);
                List<Object> values = new ArrayList<>();
                for (ControlCore.SessionInfo item : sessions) values.add(session(item));
                return Collections.singletonMap("sessions", values);
            case "session.create": {
                Map<?, ?> p = parameters(request.params, "name", "columns", "rows", "cellWidth", "cellHeight");
                String name = optionalString(p, "name");
                return session(mCore.createSession(scopes, name, number(p, "columns"), number(p, "rows"),
                    optionalNumber(p, "cellWidth"), optionalNumber(p, "cellHeight")));
            }
            case "session.write": {
                Map<?, ?> p = parameters(request.params, "id", "length");
                int length = number(p, "length");
                if (binary == null || binary.length != length) throw new BadParameters();
                mCore.writeSession(scopes, string(p, "id"), binary);
                return Collections.singletonMap("ok", true);
            }
            case "session.rename": {
                Map<?, ?> p = parameters(request.params, "id", "name");
                mCore.renameSession(scopes, string(p, "id"), string(p, "name"));
                return Collections.singletonMap("ok", true);
            }
            case "session.focus": {
                Map<?, ?> p = parameters(request.params, "id");
                mCore.focusSession(scopes, string(p, "id"));
                return Collections.singletonMap("ok", true);
            }
            case "session.snapshot": {
                Map<?, ?> p = parameters(request.params, "id");
                return Collections.singletonMap("text", mCore.snapshotSession(scopes, string(p, "id")));
            }
            case "session.resize": {
                Map<?, ?> p = parameters(request.params, "id", "columns", "rows", "cellWidth", "cellHeight");
                mCore.resizeSession(scopes, string(p, "id"), number(p, "columns"), number(p, "rows"),
                    optionalNumber(p, "cellWidth"), optionalNumber(p, "cellHeight"));
                return Collections.singletonMap("ok", true);
            }
            case "session.output.subscribe": {
                Map<?, ?> p = parameters(request.params, "id", "resumable");
                if (mOutput == null) throw new UnknownMethod();
                Object requested = p.get("resumable");
                if (p.containsKey("resumable") && !(requested instanceof Boolean)) throw new BadParameters();
                if (Boolean.TRUE.equals(requested)) {
                    if (scopes == null || !scopes.contains(ControlCore.Scope.SESSIONS_OUTPUT_RESUME))
                        throw new ControlCore.ControlException(ControlCore.Error.DENIED);
                    if (newToken == null) throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
                    mOutput.get().subscribeResumable(string(p, "id"), newToken);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("ok", true); result.put("resumeToken", newToken);
                    return result;
                }
                mOutput.get().subscribe(string(p, "id"));
                return Collections.singletonMap("ok", true);
            }
            case "session.output.resume": {
                Map<?, ?> p = parameters(request.params, "id", "token");
                if (mOutput == null) throw new UnknownMethod();
                mOutput.get().resume(string(p, "id"), string(p, "token"));
                return Collections.singletonMap("ok", true);
            }
            case "session.output.unsubscribe": {
                Map<?, ?> p = parameters(request.params, "id");
                if (mOutput == null) throw new UnknownMethod();
                mOutput.get().unsubscribe(string(p, "id"));
                return Collections.singletonMap("ok", true);
            }
            case "session.output.read": {
                Map<?, ?> p = parameters(request.params, "id", "offset", "limit");
                if (mOutput == null) throw new UnknownMethod();
                OutputReplayBuffer.ReadResult value = mOutput.get().read(string(p, "id"), longNumber(p, "offset"), number(p, "limit"));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("offset", value.offset); metadata.put("nextOffset", value.nextOffset);
                metadata.put("endOffset", value.endOffset); metadata.put("gap", value.gap); metadata.put("length", value.data.length);
                return new OutputValue(metadata, value.data.length == 0 ? null : value.data);
            }
            case "session.close": {
                Map<?, ?> p = parameters(request.params, "id");
                mCore.closeSession(scopes, string(p, "id"));
                return Collections.singletonMap("accepted", true);
            }
            case "job.start": return jobStart(request.params, scopes);
            case "job.list": return jobList(request.params, scopes);
            case "job.get": return jobGet(request.params, scopes);
            case "job.output.read": return jobOutputRead(request.params, scopes);
            case "job.cancel": return jobCancel(request.params, scopes);
            case "process.list": return processList(request.params, scopes);
            case "process.signal": return processSignal(request.params, scopes);
            case "file.roots": return fileRoots(request.params, scopes);
            case "file.list": return fileList(request.params, scopes);
            case "file.stat": return fileStat(request.params, scopes);
            case "file.read": return fileRead(request.params, scopes);
            case "file.mkdir": return fileMkdir(request.params, scopes);
            case "file.rename": return fileRename(request.params, scopes);
            case "file.delete": return fileDelete(request.params, scopes);
            case "file.transfer.begin": return fileTransferBegin(request.params, scopes);
            case "file.transfer.write": return fileTransferWrite(request.params, scopes, binary);
            case "file.transfer.commit": return fileTransferCommit(request.params, scopes);
            case "file.transfer.abort": return fileTransferAbort(request.params, scopes);
            case "workspace.list": return workspaceList(request.params, scopes);
            case "workspace.get": return workspaceGet(request.params, scopes);
            case "workspace.create": return workspaceCreate(request.params, scopes);
            case "workspace.update": return workspaceUpdate(request.params, scopes);
            case "workspace.remove": return workspaceRemove(request.params, scopes);
            case "workspace.select": return workspaceSelect(request.params, scopes);
            case "workspace.tasks": return workspaceTasks(request.params, scopes);
            case "workspace.task.run": return workspaceTaskRun(request.params, scopes);
            case "workspace.git": return workspaceGit(request.params, scopes);
            case "workspace.git.change": return workspaceGitChange(request.params, scopes);
            default: throw new UnknownMethod();
        }
    }

    /**
     * Connection-scoped scopes gate every job method, then the server-owned consent lease gates the
     * coordinator. Neither is derived from request fields.
     */
    private JobCoordinator requireJobs() throws UnknownMethod {
        if (mServices.jobs == null) throw new UnknownMethod();
        return mServices.jobs;
    }

    private JobAuthority.Lease jobLease(Set<ControlCore.Scope> scopes, ControlCore.Scope required) throws DomainError {
        if (scopes == null || !scopes.contains(required)) throw new DomainError("DENIED");
        JobAuthority.Lease lease = mServices.jobAuthority.currentLease();
        if (lease == null) throw new DomainError("DENIED");
        return lease;
    }

    private Object jobStart(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        JobCoordinator jobs = requireJobs();
        JobAuthority.Lease lease = jobLease(scopes, ControlCore.Scope.JOBS_EXECUTE);
        Map<?, ?> p = parameters(params, "executable", "args", "cwd", "workspaceId", "env", "stdin", "timeoutMs",
            "presentation", "clientRequestId");
        JobSpec spec;
        try {
            spec = new JobSpec(string(p, "executable"), stringList(p, "args"), optionalString(p, "cwd"),
                optionalString(p, "workspaceId"), stringMap(p, "env"), optionalStdin(p), longNumberOrDefault(p, "timeoutMs", 0),
                presentation(p), optionalString(p, "clientRequestId"));
        } catch (IllegalArgumentException e) {
            throw new DomainError("INVALID_REQUEST");
        }
        return startedJob(jobs.start(spec, lease));
    }

    /** Shared by job.start and workspace.task.run so both report admission the same way. */
    private static Object startedJob(JobCoordinator.StartResult result) throws DomainError {
        switch (result.status) {
            case ACCEPTED:
            case REPLAY:
                return Collections.singletonMap("job", job(result.job, result.output));
            case CONFLICT: throw new DomainError("CONFLICT");
            case DENIED: throw new DomainError("DENIED");
            case LIMIT_REACHED: throw new DomainError("LIMIT_REACHED");
            case UNSUPPORTED: throw new DomainError("UNSUPPORTED");
            default: throw new DomainError("UNAVAILABLE");
        }
    }

    /**
     * Runs a stored preset as a managed job whose directory is the workspace directory. The client supplies only
     * the workspace and task IDs. Executable, arguments and directory come from trusted stored state.
     */
    private Object workspaceTaskRun(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        JobCoordinator jobs = requireJobs();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_READ);
        JobAuthority.Lease lease = jobLease(scopes, ControlCore.Scope.JOBS_EXECUTE);
        Map<?, ?> p = parameters(params, "workspaceId", "taskId", "clientRequestId");
        String workspaceId = string(p, "workspaceId");
        String taskId = string(p, "taskId");
        String clientRequestId = optionalString(p, "clientRequestId");
        if (!workspaceCall(() -> workspaces.get(workspaceId)).available) throw new DomainError("UNAVAILABLE");
        WorkspaceRepository.TaskPreset preset = null;
        for (WorkspaceRepository.TaskPreset candidate : workspaceCall(() -> workspaces.tasks(workspaceId))) {
            if (candidate.id.equals(taskId)) preset = candidate;
        }
        if (preset == null) throw new DomainError("NOT_FOUND");
        JobSpec spec;
        try {
            spec = new JobSpec(preset.executable, preset.args, null, workspaceId, Collections.<String, String>emptyMap(), null,
                preset.timeoutMillis, JobSpec.Presentation.BACKGROUND, clientRequestId);
        } catch (IllegalArgumentException e) {
            throw new DomainError("INVALID_REQUEST");
        }
        return startedJob(jobs.start(spec, lease));
    }

    private Object jobList(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        JobCoordinator jobs = requireJobs();
        JobAuthority.Lease lease = jobLease(scopes, ControlCore.Scope.JOBS_READ);
        Map<?, ?> p = parameters(params, "workspaceId", "cursor", "limit");
        int cursor = p.containsKey("cursor") ? number(p, "cursor") : 0;
        int limit = p.containsKey("limit") ? number(p, "limit") : JobOutputStore.DEFAULT_RECORD_LIMIT;
        JobCoordinator.ListResult result = jobs.list(lease, cursor, limit, optionalString(p, "workspaceId"));
        if (result.status != JobCoordinator.ReadStatus.OK) throw new DomainError(result.status.name());
        List<Object> values = new ArrayList<>();
        for (JobCoordinator.JobView view : result.jobs) values.add(job(view.job, view.output));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobs", values);
        response.put("nextCursor", result.nextCursor);
        return response;
    }

    private Object jobGet(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        JobCoordinator jobs = requireJobs();
        JobAuthority.Lease lease = jobLease(scopes, ControlCore.Scope.JOBS_READ);
        Map<?, ?> p = parameters(params, "id");
        JobCoordinator.GetResult result = jobs.get(string(p, "id"), lease);
        if (result.status != JobCoordinator.ReadStatus.OK) throw new DomainError(result.status.name());
        return Collections.singletonMap("job", job(result.job.job, result.job.output));
    }

    /** Metadata and any binary frame follow the same rule as session output: a frame only when length > 0. */
    private Object jobOutputRead(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        JobCoordinator jobs = requireJobs();
        JobAuthority.Lease lease = jobLease(scopes, ControlCore.Scope.JOBS_OUTPUT);
        Map<?, ?> p = parameters(params, "id", "stream", "offset", "limit");
        String id = string(p, "id");
        JobOutputStore.Stream stream = stream(p);
        long offset = longNumber(p, "offset");
        int limit = number(p, "limit");
        JobCoordinator.OutputResult result = jobs.readOutput(id, lease, stream, offset, limit);
        if (result.status != JobCoordinator.ReadStatus.OK) throw new DomainError(result.status.name());
        OutputReplayBuffer.ReadResult value = result.output;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("offset", value.offset);
        metadata.put("nextOffset", value.nextOffset);
        metadata.put("endOffset", value.endOffset);
        metadata.put("gap", value.gap);
        metadata.put("length", value.data.length);
        metadata.put("finished", result.finished);
        metadata.put("complete", result.complete);
        return new OutputValue(metadata, value.data.length == 0 ? null : value.data);
    }

    /** Signals an owned job by ID. A bare PID is never accepted as a target. */
    private Object processSignal(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        JobCoordinator jobs = requireJobs();
        JobAuthority.Lease lease = jobLease(scopes, ControlCore.Scope.PROCESSES_SIGNAL);
        Map<?, ?> p = parameters(params, "jobId", "signal", "clientRequestId");
        String clientRequestId = optionalString(p, "clientRequestId");
        if (clientRequestId != null && !validRequestId(clientRequestId)) throw new DomainError("INVALID_REQUEST");
        final int signal;
        switch (string(p, "signal")) {
            case "TERM": signal = com.termux.app.control.jobs.OwnedChildProcess.SIGTERM; break;
            case "KILL": signal = com.termux.app.control.jobs.OwnedChildProcess.SIGKILL; break;
            case "INT": signal = com.termux.app.control.jobs.OwnedChildProcess.SIGINT; break;
            default: throw new BadParameters();
        }
        switch (jobs.signal(string(p, "jobId"), lease, signal)) {
            case SENT: return Collections.singletonMap("sent", true);
            case DENIED: throw new DomainError("DENIED");
            case NOT_FOUND: throw new DomainError("NOT_FOUND");
            case INVALID_REQUEST: throw new DomainError("INVALID_REQUEST");
            case NOT_STARTED: case FINISHED: throw new DomainError("CONFLICT");
            case FAILED: throw new DomainError("OUTCOME_UNKNOWN");
            default: throw new DomainError("UNSUPPORTED");
        }
    }

    private Object jobCancel(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        JobCoordinator jobs = requireJobs();
        JobAuthority.Lease lease = jobLease(scopes, ControlCore.Scope.JOBS_CANCEL);
        Map<?, ?> p = parameters(params, "id", "clientRequestId");
        String clientRequestId = optionalString(p, "clientRequestId");
        if (clientRequestId != null && !validRequestId(clientRequestId)) throw new DomainError("INVALID_REQUEST");
        switch (jobs.cancel(string(p, "id"), lease)) {
            case ACCEPTED:
            case ALREADY_REQUESTED:
                // Idempotent by construction: repeating a stop reports accepted without a second signal.
                return Collections.singletonMap("accepted", true);
            case DENIED: throw new DomainError("DENIED");
            case NOT_FOUND: throw new DomainError("NOT_FOUND");
            case INVALID_REQUEST: throw new DomainError("INVALID_REQUEST");
            case FINISHED: throw new DomainError("CONFLICT");
            default: throw new DomainError("UNSUPPORTED");
        }
    }

    private Set<String> features() {
        Set<String> features = mServices.features();
        if (mOutput != null) features.add("output");
        return features;
    }

    private WorkspaceRepository requireWorkspaces() throws UnknownMethod {
        if (mServices.workspaces == null) throw new UnknownMethod();
        return mServices.workspaces;
    }

    /** Each workspace method needs exactly its own scope. WORKSPACES_WRITE does not imply job or file access. */
    private static void requireWorkspaceScope(Set<ControlCore.Scope> scopes, ControlCore.Scope required) throws DomainError {
        if (scopes == null || !scopes.contains(required)) throw new DomainError("DENIED");
    }

    private interface WorkspaceCall<T> { T call() throws WorkspaceRepository.WorkspaceException; }

    private static <T> T workspaceCall(WorkspaceCall<T> call) throws DomainError {
        try { return call.call(); }
        catch (WorkspaceRepository.WorkspaceException e) { throw new DomainError(e.code); }
    }

    private Object workspaceList(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_READ);
        parameters(params);
        List<Object> items = new ArrayList<>();
        for (WorkspaceRepository.Workspace workspace : workspaces.list()) items.add(workspace(workspace));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workspaces", items);
        response.put("activeId", workspaces.activeId());
        return response;
    }

    private Object workspaceGet(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_READ);
        String id = string(parameters(params, "id"), "id");
        return Collections.singletonMap("workspace", workspace(workspaceCall(() -> workspaces.get(id))));
    }

    private Object workspaceCreate(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_WRITE);
        Map<?, ?> p = parameters(params, "name", "rootId", "path", "taskPresets");
        String name = string(p, "name"), rootId = string(p, "rootId"), path = string(p, "path");
        List<WorkspaceRepository.TaskPreset> presets = p.containsKey("taskPresets") ? presets(p.get("taskPresets")) : Collections.<WorkspaceRepository.TaskPreset>emptyList();
        return Collections.singletonMap("workspace", workspace(workspaceCall(() -> workspaces.create(name, rootId, path, presets))));
    }

    /** Absent fields keep their stored values. Present fields are validated before anything is written. */
    private Object workspaceUpdate(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_WRITE);
        Map<?, ?> p = parameters(params, "id", "name", "rootId", "path", "taskPresets");
        String id = string(p, "id");
        String name = p.containsKey("name") ? string(p, "name") : null;
        String rootId = p.containsKey("rootId") ? string(p, "rootId") : null;
        String path = p.containsKey("path") ? string(p, "path") : null;
        List<WorkspaceRepository.TaskPreset> presets = p.containsKey("taskPresets") ? presets(p.get("taskPresets")) : null;
        return Collections.singletonMap("workspace", workspace(workspaceCall(() -> workspaces.update(id, name, rootId, path, presets))));
    }

    private Object workspaceRemove(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_WRITE);
        String id = string(parameters(params, "id"), "id");
        workspaceCall(() -> { workspaces.remove(id); return null; });
        return Collections.singletonMap("accepted", true);
    }

    /** Selection changes only the stored active pointer; it never changes a running session or job. */
    private Object workspaceSelect(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_WRITE);
        String id = string(parameters(params, "id"), "id");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workspace", workspace(workspaceCall(() -> workspaces.select(id))));
        response.put("activeId", workspaces.activeId());
        return response;
    }

    /** Lists approved presets. Nothing runs here; running a preset needs a job and is not implemented yet. */
    private Object workspaceTasks(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_READ);
        String id = string(parameters(params, "id"), "id");
        List<Object> tasks = new ArrayList<>();
        for (WorkspaceRepository.TaskPreset preset : workspaceCall(() -> workspaces.tasks(id))) tasks.add(preset(preset));
        return Collections.singletonMap("tasks", tasks);
    }

    private static List<WorkspaceRepository.TaskPreset> presets(Object value) throws BadParameters, DomainError {
        if (!(value instanceof List)) throw new BadParameters();
        List<WorkspaceRepository.TaskPreset> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            Map<?, ?> p = parameters(item, "id", "label", "executable", "args", "timeoutMs");
            try {
                result.add(new WorkspaceRepository.TaskPreset(string(p, "id"), string(p, "label"), string(p, "executable"),
                    stringList(p, "args"), longNumberOrDefault(p, "timeoutMs", 0)));
            } catch (WorkspaceRepository.WorkspaceException e) {
                throw new DomainError(e.code);
            }
        }
        return result;
    }

    private static Map<String, Object> workspace(WorkspaceRepository.Workspace workspace) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", workspace.id);
        result.put("name", workspace.name);
        result.put("rootId", workspace.rootId);
        result.put("path", workspace.path);
        result.put("createdAt", workspace.createdAt);
        result.put("updatedAt", workspace.updatedAt);
        result.put("available", workspace.available);
        List<Object> presets = new ArrayList<>();
        for (WorkspaceRepository.TaskPreset preset : workspace.taskPresets) presets.add(preset(preset));
        result.put("taskPresets", presets);
        return result;
    }

    private static Map<String, Object> preset(WorkspaceRepository.TaskPreset preset) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", preset.id);
        result.put("label", preset.label);
        result.put("executable", preset.executable);
        result.put("args", preset.args);
        result.put("timeoutMs", preset.timeoutMillis);
        return result;
    }

    /**
     * Read-only Git inspection in the workspace directory. The subcommand selects a fixed argv; the client never
     * supplies Git arguments, and the builder refuses mutating subcommands.
     */
    private Object workspaceGit(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        JobCoordinator jobs = requireJobs();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_READ);
        JobAuthority.Lease lease = jobLease(scopes, ControlCore.Scope.JOBS_EXECUTE);
        Map<?, ?> p = parameters(params, "workspaceId", "subcommand", "clientRequestId");
        String workspaceId = string(p, "workspaceId");
        String subcommand = string(p, "subcommand");
        String clientRequestId = optionalString(p, "clientRequestId");
        List<String> argv;
        try { argv = GitCommandBuilder.readOnly(subcommand); }
        catch (WorkspaceRepository.WorkspaceException e) { throw new BadParameters(); }
        if (!workspaceCall(() -> workspaces.get(workspaceId)).available) throw new DomainError("UNAVAILABLE");
        JobSpec spec;
        try {
            spec = new JobSpec(argv.get(0), new ArrayList<>(argv.subList(1, argv.size())), null, workspaceId,
                Collections.<String, String>emptyMap(), null, 0, JobSpec.Presentation.BACKGROUND, clientRequestId);
        } catch (IllegalArgumentException e) {
            throw new DomainError("INVALID_REQUEST");
        }
        return startedJob(jobs.start(spec, lease));
    }

    /**
     * A Git change in the workspace directory. It needs workspace write consent and job execution consent, both
     * granted explicitly in the app; the builder decides the argv and accepts no option from the client.
     */
    private Object workspaceGitChange(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        WorkspaceRepository workspaces = requireWorkspaces();
        JobCoordinator jobs = requireJobs();
        requireWorkspaceScope(scopes, ControlCore.Scope.WORKSPACES_WRITE);
        JobAuthority.Lease lease = jobLease(scopes, ControlCore.Scope.JOBS_EXECUTE);
        Map<?, ?> p = parameters(params, "workspaceId", "action", "branch", "paths", "message", "clientRequestId");
        String workspaceId = string(p, "workspaceId");
        String clientRequestId = optionalString(p, "clientRequestId");
        List<String> argv;
        try {
            argv = GitCommandBuilder.change(string(p, "action"), optionalString(p, "branch"),
                p.containsKey("paths") ? stringList(p, "paths") : null, optionalString(p, "message"));
        } catch (WorkspaceRepository.WorkspaceException e) { throw new BadParameters(); }
        if (!workspaceCall(() -> workspaces.get(workspaceId)).available) throw new DomainError("UNAVAILABLE");
        JobSpec spec;
        try {
            spec = new JobSpec(argv.get(0), new ArrayList<>(argv.subList(1, argv.size())), null, workspaceId,
                Collections.<String, String>emptyMap(), null, 0, JobSpec.Presentation.BACKGROUND, clientRequestId);
        } catch (IllegalArgumentException e) {
            throw new DomainError("INVALID_REQUEST");
        }
        return startedJob(jobs.start(spec, lease));
    }

    private FileService requireFiles() throws UnknownMethod {
        if (mServices.files == null) throw new UnknownMethod();
        return mServices.files;
    }

    private static void requireFileRead(Set<ControlCore.Scope> scopes) throws DomainError {
        if (scopes == null || !scopes.contains(ControlCore.Scope.FILES_READ)) throw new DomainError("DENIED");
    }

    /** Roots are named, never returned as absolute device paths. All roots are read-only in this version. */
    private Object fileRoots(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileRead(scopes);
        parameters(params);
        List<Object> roots = new ArrayList<>();
        for (String id : files.rootIds()) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("id", id);
            root.put("writable", files.writable(id) && scopes.contains(ControlCore.Scope.FILES_WRITE));
            roots.add(root);
        }
        return Collections.singletonMap("roots", roots);
    }

    private Object fileList(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileRead(scopes);
        Map<?, ?> p = parameters(params, "rootId", "path", "cursor", "limit");
        String rootId = string(p, "rootId");
        String path = p.containsKey("path") ? string(p, "path") : "";
        int cursor = p.containsKey("cursor") ? number(p, "cursor") : 0;
        int limit = p.containsKey("limit") ? number(p, "limit") : FileService.MAX_PAGE / 2;
        try {
            FileService.Page page = files.list(rootId, path, cursor, limit);
            List<Object> entries = new ArrayList<>();
            for (FileService.Entry entry : page.entries) entries.add(fileEntry(entry));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("entries", entries);
            response.put("nextCursor", page.nextCursor);
            return response;
        } catch (FileService.FileException e) {
            throw new DomainError(e.code);
        }
    }

    private Object fileStat(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileRead(scopes);
        Map<?, ?> p = parameters(params, "rootId", "path");
        try {
            return Collections.singletonMap("entry", fileEntry(files.stat(string(p, "rootId"), string(p, "path"))));
        } catch (FileService.FileException e) {
            throw new DomainError(e.code);
        }
    }

    /** Metadata and a binary frame follow the session-output rule: a frame only when length is positive. */
    private Object fileRead(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileRead(scopes);
        Map<?, ?> p = parameters(params, "rootId", "path", "offset", "limit");
        try {
            FileService.ReadResult value = files.read(string(p, "rootId"), string(p, "path"), longNumber(p, "offset"), number(p, "limit"));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("offset", value.offset);
            metadata.put("nextOffset", value.nextOffset);
            metadata.put("eof", value.eof);
            metadata.put("length", value.data.length);
            metadata.put("revision", value.revision);
            return new OutputValue(metadata, value.data.length == 0 ? null : value.data);
        } catch (FileService.FileException e) {
            throw new DomainError(e.code);
        }
    }

    /** Writes need their own consent and a writable root; reads of the result use the same service. */
    private static void requireFileWrite(Set<ControlCore.Scope> scopes) throws DomainError {
        if (scopes == null || !scopes.contains(ControlCore.Scope.FILES_WRITE)) throw new DomainError("DENIED");
    }

    private static String optionalRevision(Map<?, ?> p) throws BadParameters {
        String revision = optionalString(p, "ifRevision");
        if (revision != null && (revision.isEmpty() || revision.length() > 64)) throw new BadParameters();
        return revision;
    }

    private static void requireRequestId(Map<?, ?> p) throws BadParameters, DomainError {
        String clientRequestId = optionalString(p, "clientRequestId");
        if (clientRequestId != null && !validRequestId(clientRequestId)) throw new DomainError("INVALID_REQUEST");
    }

    private static boolean optionalFlag(Map<?, ?> p, String key) throws BadParameters {
        if (!p.containsKey(key)) return false;
        Object value = p.get(key);
        if (!(value instanceof Boolean)) throw new BadParameters();
        return (Boolean) value;
    }

    private Object fileMkdir(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileWrite(scopes);
        Map<?, ?> p = parameters(params, "rootId", "path", "clientRequestId");
        requireRequestId(p);
        try { return Collections.singletonMap("entry", fileEntry(files.mkdir(string(p, "rootId"), string(p, "path")))); }
        catch (FileService.FileException e) { throw new DomainError(e.code); }
    }

    private Object fileRename(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileWrite(scopes);
        Map<?, ?> p = parameters(params, "rootId", "path", "newPath", "ifRevision", "clientRequestId");
        requireRequestId(p);
        try {
            return Collections.singletonMap("entry", fileEntry(files.rename(string(p, "rootId"), string(p, "path"),
                string(p, "newPath"), optionalRevision(p))));
        } catch (FileService.FileException e) { throw new DomainError(e.code); }
    }

    /** Recursive deletion is destructive enough to need a separate approval that this API does not offer. */
    private Object fileDelete(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileWrite(scopes);
        Map<?, ?> p = parameters(params, "rootId", "path", "ifRevision", "recursive", "clientRequestId");
        requireRequestId(p);
        if (optionalFlag(p, "recursive")) throw new DomainError("UNSUPPORTED");
        try { files.delete(string(p, "rootId"), string(p, "path"), optionalRevision(p)); }
        catch (FileService.FileException e) { throw new DomainError(e.code); }
        return Collections.singletonMap("accepted", true);
    }

    private Object fileTransferBegin(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileWrite(scopes);
        Map<?, ?> p = parameters(params, "rootId", "path", "totalBytes", "sha256", "ifRevision", "overwrite", "clientRequestId");
        requireRequestId(p);
        try {
            com.termux.app.control.files.FileTransfers.Begin begin = files.transfers().begin(string(p, "rootId"), string(p, "path"),
                longNumber(p, "totalBytes"), optionalString(p, "sha256"), optionalRevision(p), optionalFlag(p, "overwrite"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("transferId", begin.transferId);
            result.put("chunkSize", com.termux.app.control.files.FileTransfers.CHUNK_BYTES);
            result.put("offset", begin.offset);
            return result;
        } catch (FileService.FileException e) { throw new DomainError(e.code); }
    }

    private Object fileTransferWrite(Object params, Set<ControlCore.Scope> scopes, byte[] binary)
            throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileWrite(scopes);
        Map<?, ?> p = parameters(params, "transferId", "offset", "length");
        int length = number(p, "length");
        if (binary == null || binary.length != length) throw new BadParameters();
        try {
            return Collections.singletonMap("nextOffset", files.transfers().write(string(p, "transferId"), longNumber(p, "offset"), binary));
        } catch (FileService.FileException e) { throw new DomainError(e.code); }
    }

    private Object fileTransferCommit(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileWrite(scopes);
        Map<?, ?> p = parameters(params, "transferId");
        try {
            com.termux.app.control.files.FileTransfers.Commit commit = files.transfers().commit(string(p, "transferId"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("entry", fileEntry(commit.entry));
            result.put("sha256", commit.sha256);
            result.put("committed", true);
            return result;
        } catch (FileService.FileException e) { throw new DomainError(e.code); }
    }

    private Object fileTransferAbort(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        FileService files = requireFiles();
        requireFileWrite(scopes);
        Map<?, ?> p = parameters(params, "transferId");
        try { files.transfers().abort(string(p, "transferId")); }
        catch (FileService.FileException e) { throw new DomainError(e.code); }
        return Collections.singletonMap("aborted", true);
    }

    /** Checked before the binary frame is read, so an unauthorized upload never transfers its bytes. */
    BinaryPreparation prepareFileTransferWrite(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes) {
        if (!mNegotiation.isReady()) return BinaryPreparation.rejected(request, -32600, "Invalid Request");
        if (mServices.files == null) return BinaryPreparation.rejected(request, -32601, "Method not found");
        if (scopes == null || !scopes.contains(ControlCore.Scope.FILES_WRITE))
            return BinaryPreparation.rejected(request, -32001, "DENIED");
        try {
            Map<?, ?> params = parameters(request.params, "transferId", "offset", "length");
            string(params, "transferId");
            longNumber(params, "offset");
            int length = number(params, "length");
            if (length < 1 || length > com.termux.app.control.files.FileTransfers.CHUNK_BYTES) throw new BadParameters();
            return new BinaryPreparation(length, null);
        } catch (BadParameters e) {
            return BinaryPreparation.rejected(request, -32602, "Invalid params");
        }
    }

    private static Map<String, Object> fileEntry(FileService.Entry entry) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", entry.name);
        result.put("path", entry.path);
        result.put("type", entry.type);
        result.put("size", entry.size);
        result.put("mtime", entry.mtimeMillis);
        result.put("revision", entry.revision);
        return result;
    }

    private ProcessService requireProcesses() throws UnknownMethod {
        if (mServices.processes == null) throw new UnknownMethod();
        return mServices.processes;
    }

    /** Read-only page of one fresh sample. Signalling is not offered by this method or any other. */
    private Object processList(Object params, Set<ControlCore.Scope> scopes) throws BadParameters, DomainError, UnknownMethod {
        ProcessService processes = requireProcesses();
        if (scopes == null || !scopes.contains(ControlCore.Scope.PROCESSES_READ)) throw new DomainError("DENIED");
        Map<?, ?> p = parameters(params, "scope", "cursor", "limit");
        String scope = p.containsKey("scope") ? string(p, "scope") : "owned";
        int cursor = p.containsKey("cursor") ? number(p, "cursor") : 0;
        int limit = p.containsKey("limit") ? number(p, "limit") : ProcessService.MAX_PAGE / 2;
        ProcessService.Page page;
        try { page = processes.list(scope, cursor, limit); }
        catch (IllegalArgumentException e) { throw new BadParameters(); }
        List<Object> values = new ArrayList<>();
        for (ProcessTable.Row row : page.rows) values.add(process(row));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("processes", values);
        response.put("incomplete", page.incomplete);
        response.put("missingRoots", page.missingRoots);
        response.put("nextCursor", page.nextCursor);
        return response;
    }

    /** One-shot listings have no previous sample, so CPU is unavailable; managed-job links are not yet tracked. */
    private static Map<String, Object> process(ProcessTable.Row row) {
        ProcessSnapshot.Entry entry = row.entry;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", entry.pid);
        result.put("ppid", entry.ppid);
        result.put("name", entry.name);
        result.put("state", String.valueOf(entry.state));
        result.put("rssKiB", row.rssKiB);
        result.put("cpuPercent", null);
        result.put("startTicks", entry.startTicks);
        result.put("managedJobId", null);
        result.put("visibility", "partial");
        return result;
    }

    private static boolean validRequestId(String value) {
        if (value.isEmpty() || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }

    /** Never includes executable, arguments, environment, stdin or output bytes. */
    private static Map<String, Object> job(JobRecord.Snapshot job, JobOutputStore.Offsets output) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", job.id);
        result.put("state", job.state.name().toLowerCase(java.util.Locale.ROOT));
        result.put("startedAt", job.startedAt);
        result.put("endedAt", job.endedAt);
        result.put("exitCode", job.exitCode);
        result.put("failureCode", job.failure == null ? null : job.failure.name().toLowerCase(java.util.Locale.ROOT));
        result.put("pid", job.pid);
        result.put("workspaceId", job.workspaceId);
        if (output != null) {
            Map<String, Object> offsets = new LinkedHashMap<>();
            offsets.put("stdoutEndOffset", output.stdoutEndOffset);
            offsets.put("stderrEndOffset", output.stderrEndOffset);
            result.put("output", offsets);
        }
        return result;
    }

    /** Metadata and authorization are checked before reading a separate binary frame. */
    BinaryPreparation prepareSessionWrite(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes) {
        if (!mNegotiation.isReady()) return BinaryPreparation.rejected(request, -32600, "Invalid Request");
        if (scopes == null || !scopes.contains(ControlCore.Scope.SESSIONS_WRITE))
            return BinaryPreparation.rejected(request, -32001, "DENIED");
        try {
            Map<?, ?> params = parameters(request.params, "id", "length");
            String id = string(params, "id");
            int length = number(params, "length");
            if (id.isEmpty() || id.length() > 64 || length < 1 || length > ControlCore.MAX_WRITE_BYTES)
                throw new BadParameters();
            return new BinaryPreparation(length, null);
        } catch (BadParameters e) {
            return BinaryPreparation.rejected(request, -32602, "Invalid params");
        }
    }

    static byte[] invalidBinary(RpcRequestParser.Request request) {
        return request.hasId ? error(request.id, -32602, "Invalid params") : null;
    }

    static final class BinaryPreparation {
        final int length;
        final byte[] error;
        BinaryPreparation(int length, byte[] error) { this.length = length; this.error = error; }
        static BinaryPreparation rejected(RpcRequestParser.Request request, int code, String message) {
            return new BinaryPreparation(0, request.hasId ? RpcDispatcher.error(request.id, code, message) : null);
        }
    }

    private static Map<String, Object> session(ControlCore.SessionInfo item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.id); result.put("name", item.name);
        result.put("state", item.state.name().toLowerCase(java.util.Locale.ROOT));
        result.put("pid", item.pid > 0 ? item.pid : null);
        return result;
    }

    private static Map<?, ?> parameters(Object value, String... allowed) throws BadParameters {
        if (value == null) return Collections.emptyMap();
        if (!(value instanceof Map)) throw new BadParameters();
        Map<?, ?> result = (Map<?, ?>) value;
        List<String> keys = Arrays.asList(allowed);
        for (Object key : result.keySet()) if (!keys.contains(key)) throw new BadParameters();
        return result;
    }

    private static String string(Map<?, ?> p, String key) throws BadParameters {
        Object value = p.get(key);
        if (!(value instanceof String)) throw new BadParameters();
        return (String) value;
    }

    private static String optionalString(Map<?, ?> p, String key) throws BadParameters {
        return p.containsKey(key) ? string(p, key) : null;
    }

    private static int number(Map<?, ?> p, String key) throws BadParameters {
        Object value = p.get(key);
        if (!(value instanceof BigDecimal)) throw new BadParameters();
        try { return ((BigDecimal) value).intValueExact(); }
        catch (ArithmeticException e) { throw new BadParameters(); }
    }

    private static long longNumber(Map<?, ?> p, String key) throws BadParameters {
        Object value = p.get(key);
        if (!(value instanceof BigDecimal)) throw new BadParameters();
        try { return ((BigDecimal) value).longValueExact(); }
        catch (ArithmeticException e) { throw new BadParameters(); }
    }

    private static int optionalNumber(Map<?, ?> p, String key) throws BadParameters {
        return p.containsKey(key) ? number(p, key) : 0;
    }

    public static byte[] invalidRequest(boolean parseError) {
        return error(null, parseError ? -32700 : -32600, parseError ? "Parse error" : "Invalid Request");
    }

    public static byte[] transportFailure(RpcRequestParser.Request request, ServiceThreadDispatch.Failure failure) {
        if (request == null || !request.hasId) return null;
        return error(request.id, -32002, failure.name());
    }

    private static byte[] error(Object id, int code, String message) {
        return response(id, null, message, code);
    }

    private static byte[] response(Object id, Object result, String message, int code) {
        try {
            StringWriter buffer = new StringWriter();
            JsonWriter writer = new JsonWriter(buffer);
            writer.setSerializeNulls(true);
            writer.beginObject();
            writer.name("jsonrpc").value("2.0");
            writer.name("id"); writeValue(writer, id);
            if (message == null) { writer.name("result"); writeValue(writer, result); }
            else { writer.name("error").beginObject().name("code").value(code).name("message").value(message).endObject(); }
            writer.endObject();
            writer.close();
            return buffer.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize response", e);
        }
    }

    private static void writeValue(JsonWriter writer, Object value) throws IOException {
        if (value == null) writer.nullValue();
        else if (value instanceof String) writer.value((String) value);
        else if (value instanceof Number) writer.value((Number) value);
        else if (value instanceof Boolean) writer.value((Boolean) value);
        else if (value instanceof Map) {
            writer.beginObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                writer.name((String) entry.getKey()); writeValue(writer, entry.getValue());
            }
            writer.endObject();
        } else if (value instanceof List) {
            writer.beginArray(); for (Object element : (List<?>) value) writeValue(writer, element); writer.endArray();
        } else throw new IllegalArgumentException("Unsupported response type");
    }

    private static List<String> stringList(Map<?, ?> p, String key) throws BadParameters {
        Object value = p.get(key);
        if (!(value instanceof List)) throw new BadParameters();
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) throw new BadParameters();
            result.add((String) item);
        }
        return result;
    }

    private static Map<String, String> stringMap(Map<?, ?> p, String key) throws BadParameters {
        if (!p.containsKey(key)) return Collections.emptyMap();
        Object value = p.get(key);
        if (!(value instanceof Map)) throw new BadParameters();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) throw new BadParameters();
            result.put((String) entry.getKey(), (String) entry.getValue());
        }
        return result;
    }

    private static byte[] optionalStdin(Map<?, ?> p) throws BadParameters {
        return p.containsKey("stdin") ? string(p, "stdin").getBytes(StandardCharsets.UTF_8) : null;
    }

    private static long longNumberOrDefault(Map<?, ?> p, String key, long fallback) throws BadParameters {
        return p.containsKey(key) ? longNumber(p, key) : fallback;
    }

    private static JobSpec.Presentation presentation(Map<?, ?> p) throws BadParameters {
        if (!p.containsKey("presentation")) return JobSpec.Presentation.BACKGROUND;
        String value = string(p, "presentation");
        if (value.equals("foreground")) return JobSpec.Presentation.FOREGROUND;
        if (value.equals("background")) return JobSpec.Presentation.BACKGROUND;
        throw new BadParameters();
    }

    private static JobOutputStore.Stream stream(Map<?, ?> p) throws BadParameters {
        String value = string(p, "stream");
        if (value.equals("stdout")) return JobOutputStore.Stream.STDOUT;
        if (value.equals("stderr")) return JobOutputStore.Stream.STDERR;
        throw new BadParameters();
    }

    /** Domain failures use reserved v1 names inside the existing application-error family (-32001). */
    private static final class DomainError extends Exception {
        final String code;
        DomainError(String code) { super(code); this.code = code; }
    }

    private static final class BadParameters extends Exception { }
    private static final class UnknownMethod extends Exception { }
}
