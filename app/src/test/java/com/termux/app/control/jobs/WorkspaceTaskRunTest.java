package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import com.termux.app.control.files.FileService;
import com.termux.app.control.ipc.RpcDispatcher;
import com.termux.app.control.ipc.RpcRequestParser;
import com.termux.app.control.workspace.WorkspaceRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** A stored preset runs as a managed job in the workspace directory, with its stored argv and real exit code. */
public class WorkspaceTaskRunTest {
    private static final Set<ControlCore.Scope> RUN = EnumSet.of(ControlCore.Scope.WORKSPACES_READ, ControlCore.Scope.JOBS_EXECUTE);
    private static final Set<ControlCore.Scope> ALL = EnumSet.of(ControlCore.Scope.WORKSPACES_READ, ControlCore.Scope.JOBS_EXECUTE,
        ControlCore.Scope.JOBS_READ, ControlCore.Scope.JOBS_OUTPUT, ControlCore.Scope.JOBS_CANCEL);

    private static final class NoSessions implements ControlCore.Backend {
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        @Override public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) throws ControlCore.ControlException { throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE); }
        @Override public void write(String id, byte[] data) { }
        @Override public void rename(String id, String name) { }
        @Override public void close(String id) { }
        @Override public void resize(String id, int c, int r, int w, int h) { }
    }

    /** Exit code is what captureAndWait reports, so the job's exit status comes from the scripted child. */
    private static final class ScriptedProcess extends Process {
        private final int exit;
        ScriptedProcess(int exit) { this.exit = exit; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return exit; }
        @Override public int exitValue() { return exit; }
        @Override public void destroy() { }
    }

    private File base, home, bin, store, tool;
    private final List<String> launchedExecutables = new CopyOnWriteArrayList<>();
    private final List<String> launchedDirectories = new CopyOnWriteArrayList<>();
    private final List<List<String>> launchedArguments = new CopyOnWriteArrayList<>();
    private WorkspaceRepository repository;
    private RpcDispatcher dispatcher;
    private final RpcRequestParser parser = new RpcRequestParser();

    @Before public void setUp() throws Exception {
        base = Files.createTempDirectory("task-run").toFile();
        home = new File(base, "home");
        assertTrue(new File(home, "projects/app").mkdirs());
        bin = new File(base, "bin");
        assertTrue(bin.mkdir());
        tool = new File(bin, "tool");
        Files.write(tool.toPath(), "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.US_ASCII));
        assertTrue(tool.setExecutable(true));
        store = new File(base, "store");
        assertTrue(store.mkdir());

        Map<String, File> roots = new HashMap<>();
        roots.put("home", home);
        FileService files = new FileService(roots);
        repository = new WorkspaceRepository(new File(store, "v1.json"), files);
        repository.create("app", "home", "projects/app", Collections.singletonList(
            new WorkspaceRepository.TaskPreset("test", "Run tests", tool.getCanonicalPath(), Arrays.asList("test", "--workspace"), 0)));

        JobPathPolicy policy = new JobPathPolicy(Collections.singletonList(bin.getPath()), Collections.singletonList(home.getPath()),
            home.getPath(), Collections.singletonMap("git", tool.getCanonicalPath()), repository::directory);
        JobAuthority authority = new JobAuthority();
        authority.grantScopes(EnumSet.of(ControlCore.Scope.JOBS_EXECUTE, ControlCore.Scope.JOBS_READ, ControlCore.Scope.JOBS_OUTPUT, ControlCore.Scope.JOBS_CANCEL));
        AppShellJobAdapter adapter = new AppShellJobAdapter((spec, executable, cwd, capture, completion) -> {
            launchedExecutables.add(executable);
            launchedDirectories.add(cwd);
            launchedArguments.add(spec.arguments());
            Process process = new ScriptedProcess(3);
            new Thread(() -> {
                try { completion.finished(capture.captureAndWait(process), false); }
                catch (Exception e) { completion.finished(null, true); }
            }, "ScriptedTaskCapture").start();
            return process;
        });
        JobRegistry registry = new JobRegistry(new JobIoPool(2), new JobOutputStore(16, 4, 128), 4);
        JobCoordinator coordinator = new JobCoordinator(registry, adapter, policy, authority, JobIoPool.MAX_JOBS);
        dispatcher = new RpcDispatcher(new ControlCore(new NoSessions()), coordinator, authority, repository);
        dispatcher.dispatch(parser.parse(bytes("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}")),
            Collections.<ControlCore.Scope>emptySet());
    }

    @After public void tearDown() { delete(base); }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private JsonObject rpc(String json, Set<ControlCore.Scope> scopes) throws Exception {
        return JsonParser.parseString(new String(dispatcher.dispatch(parser.parse(bytes(json)), scopes), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String run(String workspaceId, String taskId, String extra) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace.task.run\",\"params\":{\"workspaceId\":\"" + workspaceId
            + "\",\"taskId\":\"" + taskId + "\"" + extra + "}}";
    }

    private String workspaceId() throws Exception {
        return repository.list().get(0).id;
    }

    private JsonObject waitTerminal(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            JsonObject job = rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"job.get\",\"params\":{\"id\":\"" + id + "\"}}", ALL)
                .getAsJsonObject("result").getAsJsonObject("job");
            if (!job.get("state").getAsString().matches("queued|starting|running|cancelling")) return job;
            if (System.nanoTime() > deadline) fail("job did not finish");
            Thread.sleep(5);
        }
    }

    @Test public void presetRunsInWorkspaceDirectoryWithStoredArgvAndReportsExitCode() throws Exception {
        String workspace = workspaceId();
        JsonObject response = rpc(run(workspace, "test", ""), RUN).getAsJsonObject("result");
        String jobId = response.getAsJsonObject("job").get("id").getAsString();
        JsonObject finished = waitTerminal(jobId);
        assertEquals("exited", finished.get("state").getAsString());
        assertEquals(3, finished.get("exitCode").getAsInt());
        assertEquals(new File(home, "projects/app").getCanonicalPath(), launchedDirectories.get(0));
        assertEquals(tool.getCanonicalPath(), launchedExecutables.get(0));
        assertEquals(Arrays.asList("test", "--workspace"), launchedArguments.get(0));
    }

    @Test public void clientCannotSupplyDirectoryOrArgvThroughTheRequest() throws Exception {
        String workspace = workspaceId();
        assertEquals(-32602, rpc(run(workspace, "test", ",\"cwd\":\"/\""), RUN).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32602, rpc(run(workspace, "test", ",\"args\":[\"rm\"]"), RUN).getAsJsonObject("error").get("code").getAsInt());
        assertTrue(launchedDirectories.isEmpty());
    }

    @Test public void readAndExecuteScopesAreBothRequired() throws Exception {
        String workspace = workspaceId();
        assertEquals("DENIED", rpc(run(workspace, "test", ""), EnumSet.of(ControlCore.Scope.WORKSPACES_READ))
            .getAsJsonObject("error").get("message").getAsString());
        assertEquals("DENIED", rpc(run(workspace, "test", ""), EnumSet.of(ControlCore.Scope.JOBS_EXECUTE))
            .getAsJsonObject("error").get("message").getAsString());
        assertTrue(launchedDirectories.isEmpty());
    }

    @Test public void unknownTaskAndVanishedWorkspaceAreReported() throws Exception {
        String workspace = workspaceId();
        assertEquals("NOT_FOUND", rpc(run(workspace, "missing", ""), RUN).getAsJsonObject("error").get("message").getAsString());
        assertTrue(new File(home, "projects/app").delete());
        assertEquals("UNAVAILABLE", rpc(run(workspace, "test", ""), RUN).getAsJsonObject("error").get("message").getAsString());
        assertTrue(launchedDirectories.isEmpty());
    }

    @Test public void executableOutsideConfiguredRootsIsDenied() throws Exception {
        repository.create("other", "home", "projects/app", Collections.singletonList(
            new WorkspaceRepository.TaskPreset("shell", "Shell", "/bin/sh", Collections.<String>emptyList(), 0)));
        String other = repository.list().get(1).id;
        assertEquals("DENIED", rpc(run(other, "shell", ""), RUN).getAsJsonObject("error").get("message").getAsString());
        assertTrue(launchedExecutables.isEmpty());
    }

    @Test public void timedPresetIsUnsupportedUntilTerminationIsSafe() throws Exception {
        repository.create("timed", "home", "projects/app", Collections.singletonList(
            new WorkspaceRepository.TaskPreset("slow", "Slow", tool.getCanonicalPath(), Collections.<String>emptyList(), 120000)));
        String timed = repository.list().get(1).id;
        assertEquals("UNSUPPORTED", rpc(run(timed, "slow", ""), RUN).getAsJsonObject("error").get("message").getAsString());
    }

    @Test public void replayWithSameClientRequestReturnsSameJob() throws Exception {
        String workspace = workspaceId();
        String first = rpc(run(workspace, "test", ",\"clientRequestId\":\"task-1\""), RUN).getAsJsonObject("result")
            .getAsJsonObject("job").get("id").getAsString();
        String second = rpc(run(workspace, "test", ",\"clientRequestId\":\"task-1\""), RUN).getAsJsonObject("result")
            .getAsJsonObject("job").get("id").getAsString();
        assertEquals(first, second);
        waitTerminal(first);
        assertEquals(1, launchedDirectories.size());
    }

    @Test public void gitStatusRunsFixedArgvInWorkspaceDirectory() throws Exception {
        String workspace = workspaceId();
        String jobId = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace.git\",\"params\":{\"workspaceId\":\""
            + workspace + "\",\"subcommand\":\"status\"}}", RUN).getAsJsonObject("result").getAsJsonObject("job").get("id").getAsString();
        assertEquals(3, waitTerminal(jobId).get("exitCode").getAsInt());
        assertEquals(tool.getCanonicalPath(), launchedExecutables.get(0));
        assertEquals(new File(home, "projects/app").getCanonicalPath(), launchedDirectories.get(0));
        assertEquals(Arrays.asList("status", "--porcelain=v1", "--branch"), launchedArguments.get(0));
    }

    @Test public void gitRefusesMutatingAndUnknownSubcommands() throws Exception {
        String workspace = workspaceId();
        assertEquals(-32602, rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"workspace.git\",\"params\":{\"workspaceId\":\""
            + workspace + "\",\"subcommand\":\"commit\"}}", RUN).getAsJsonObject("error").get("code").getAsInt());
        assertTrue(launchedExecutables.isEmpty());
    }
}
