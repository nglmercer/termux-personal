package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import com.termux.app.control.ipc.RpcDispatcher;
import com.termux.app.control.ipc.RpcRequestParser;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Job RPC behavior over the real coordinator, with a scripted child instead of an Android launcher. */
public class RpcJobDispatcherTest {
    private static final Set<ControlCore.Scope> READ = EnumSet.of(ControlCore.Scope.JOBS_READ);
    private static final Set<ControlCore.Scope> EXECUTE_ONLY = EnumSet.of(ControlCore.Scope.JOBS_EXECUTE);
    private static final Set<ControlCore.Scope> ALL_JOBS = EnumSet.of(ControlCore.Scope.JOBS_EXECUTE,
        ControlCore.Scope.JOBS_READ, ControlCore.Scope.JOBS_OUTPUT, ControlCore.Scope.JOBS_CANCEL);
    private static final String HELLO = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}";
    private static final String START = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"job.start\",\"params\":{\"executable\":\"/bin/echo\",\"args\":[\"hello\"]}}";

    private static final class NoSessions implements ControlCore.Backend {
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        @Override public ControlCore.SessionInfo create(String name, int columns, int rows, int cellWidth, int cellHeight) throws ControlCore.ControlException {
            throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
        }
        @Override public void write(String id, byte[] data) { }
        @Override public void rename(String id, String name) { }
        @Override public void close(String id) { }
        @Override public void resize(String id, int columns, int rows, int cellWidth, int cellHeight) { }
    }

    /** Exposes only what the capture reads: stdout/stderr bytes and an exit code of zero. */
    private static final class ScriptedProcess extends Process {
        private final InputStream stdout;
        ScriptedProcess(byte[] out) { stdout = new ByteArrayInputStream(out); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }

    private static final class Harness {
        final JobAuthority authority = new JobAuthority();
        final JobRegistry registry = new JobRegistry(new JobIoPool(2), new JobOutputStore(16, 4, 128), 4);
        final CountDownLatch launching = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger launches = new AtomicInteger();
        final RpcRequestParser parser = new RpcRequestParser();
        final RpcDispatcher dispatcher;
        final JobCoordinator coordinator;

        Harness(byte[] stdout, boolean blockLaunch) throws Exception {
            authority.grantScopes(ALL_JOBS);
            AppShellJobAdapter adapter = new AppShellJobAdapter((spec, executable, cwd, capture, completion) -> {
                launches.incrementAndGet();
                launching.countDown();
                if (blockLaunch) await(release);
                Process process = new ScriptedProcess(stdout);
                Thread worker = new Thread(() -> {
                    try { completion.finished(capture.captureAndWait(process), false); }
                    catch (Exception e) { completion.finished(null, true); }
                }, "ScriptedJobCapture");
                worker.setDaemon(true);
                worker.start();
                return process;
            });
            JobCoordinator.Policy policy = new JobCoordinator.Policy() {
                @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long epoch) {
                    return new JobCoordinator.Resolution("/bin/echo", "/");
                }
                @Override public boolean authorizeReplay(JobSpec spec, long epoch) { return true; }
            };
            coordinator = new JobCoordinator(registry, adapter, policy, authority, JobIoPool.MAX_JOBS);
            dispatcher = new RpcDispatcher(new ControlCore(new NoSessions()), coordinator, authority);
            rpc(HELLO, Collections.<ControlCore.Scope>emptySet());
        }

        JsonObject rpc(String json, Set<ControlCore.Scope> scopes) throws Exception {
            return object(dispatcher.dispatch(parser.parse(bytes(json)), scopes));
        }

        RpcDispatcher.Result raw(String json, Set<ControlCore.Scope> scopes) throws Exception {
            return dispatcher.dispatchResult(parser.parse(bytes(json)), scopes, null);
        }

        List<String> methods(Set<ControlCore.Scope> scopes) throws Exception {
            JsonArray array = rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"system.capabilities\"}", scopes)
                .getAsJsonObject("result").getAsJsonArray("methods");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) names.add(array.get(i).getAsString());
            return names;
        }

        String startJob(String clientRequestId) throws Exception {
            JsonObject response = rpc(startWith("[\"hello\"]", clientRequestId), EXECUTE_ONLY);
            return response.getAsJsonObject("result").getAsJsonObject("job").get("id").getAsString();
        }

        /** Polls job.get with the same consent; fails the test if the job never reaches a terminal state. */
        JsonObject waitTerminal(String id) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (true) {
                JsonObject job = rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"job.get\",\"params\":{\"id\":\"" + id + "\"}}", ALL_JOBS)
                    .getAsJsonObject("result").getAsJsonObject("job");
                String state = job.get("state").getAsString();
                if (Arrays.asList("exited", "failed", "cancelled", "timed_out", "lost").contains(state)) return job;
                if (System.nanoTime() > deadline) fail("job did not finish; last state " + state);
                Thread.sleep(5);
            }
        }

        void stop() { release.countDown(); coordinator.stopAccepting(); }
    }

    @Test public void jobMethodsStayAbsentWithoutCoordinator() throws Exception {
        RpcRequestParser parser = new RpcRequestParser();
        RpcDispatcher dispatcher = new RpcDispatcher(new ControlCore(new NoSessions()));
        dispatcher.dispatch(parser.parse(bytes(HELLO)), Collections.<ControlCore.Scope>emptySet());
        JsonObject caps = object(dispatcher.dispatch(parser.parse(bytes(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"system.capabilities\"}")), ALL_JOBS));
        assertFalse(caps.toString().contains("job."));
        JsonObject missing = object(dispatcher.dispatch(parser.parse(bytes(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"job.list\"}")), ALL_JOBS));
        assertEquals(-32601, missing.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test public void discoveryListsOnlyJobMethodsWhoseScopesAreGranted() throws Exception {
        Harness h = new Harness(new byte[0], false);
        try {
            List<String> readOnly = h.methods(READ);
            assertTrue(readOnly.containsAll(Arrays.asList("job.list", "job.get")));
            assertFalse(readOnly.contains("job.start"));
            assertFalse(readOnly.contains("job.output.read"));
            assertFalse(readOnly.contains("job.cancel"));
            assertTrue(h.methods(ALL_JOBS).containsAll(
                Arrays.asList("job.start", "job.list", "job.get", "job.output.read", "job.cancel")));
        } finally { h.stop(); }
    }

    @Test public void startRequiresExecuteScopeValidParametersAndCurrentConsent() throws Exception {
        Harness h = new Harness(new byte[0], false);
        try {
            assertEquals("DENIED", h.rpc(START, READ).getAsJsonObject("error").get("message").getAsString());
            assertEquals(-32602, h.rpc(START.replace("[\"hello\"]", "\"hello\""), EXECUTE_ONLY)
                .getAsJsonObject("error").get("code").getAsInt());
            assertEquals("INVALID_REQUEST", h.rpc(START.replace("\"params\":{", "\"params\":{\"cwd\":\"tmp\","), EXECUTE_ONLY)
                .getAsJsonObject("error").get("message").getAsString());
            assertEquals("INVALID_REQUEST", h.rpc(START.replace("\"params\":{", "\"params\":{\"env\":{\"1BAD\":\"x\"},"), EXECUTE_ONLY)
                .getAsJsonObject("error").get("message").getAsString());
            h.authority.revoke();
            assertEquals("DENIED", h.rpc(START, EXECUTE_ONLY).getAsJsonObject("error").get("message").getAsString());
            assertEquals(0, h.launches.get());
        } finally { h.stop(); }
    }

    @Test public void replayReturnsSameJobAndNeverExposesCommandData() throws Exception {
        Harness h = new Harness(new byte[0], false);
        try {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"job.start\",\"params\":{\"executable\":\"/bin/echo\","
                + "\"args\":[\"hello\"],\"env\":{\"TOKEN\":\"very-secret\"},\"clientRequestId\":\"req-1\"}}";
            JsonObject first = h.rpc(request, EXECUTE_ONLY);
            JsonObject second = h.rpc(request.replace("\"id\":5", "\"id\":6"), EXECUTE_ONLY);
            String id = first.getAsJsonObject("result").getAsJsonObject("job").get("id").getAsString();
            assertEquals(id, second.getAsJsonObject("result").getAsJsonObject("job").get("id").getAsString());
            JsonObject conflict = h.rpc(request.replace("\"hello\"", "\"other\"").replace("\"id\":5", "\"id\":7"), EXECUTE_ONLY);
            assertEquals("CONFLICT", conflict.getAsJsonObject("error").get("message").getAsString());
            String text = first.toString() + second.toString();
            assertFalse(text.contains("very-secret"));
            assertFalse(text.contains("/bin/echo"));
            assertFalse(text.contains("\"args\""));
            assertFalse(text.contains("\"env\""));
        } finally { h.stop(); }
    }

    @Test public void getAndListExposeRetainedMetadataAfterExit() throws Exception {
        Harness h = new Harness(new byte[0], false);
        try {
            String id = h.startJob("req-2");
            JsonObject job = h.waitTerminal(id);
            assertEquals("exited", job.get("state").getAsString());
            assertEquals(0, job.get("exitCode").getAsInt());
            assertTrue(job.get("failureCode").isJsonNull());
            assertEquals(0, job.getAsJsonObject("output").get("stdoutEndOffset").getAsInt());

            JsonObject list = h.rpc("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"job.list\",\"params\":{\"limit\":10}}", READ)
                .getAsJsonObject("result");
            assertEquals(1, list.getAsJsonArray("jobs").size());
            assertTrue(list.get("nextCursor").isJsonNull());

            assertEquals("INVALID_REQUEST", h.rpc("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"job.list\",\"params\":{\"cursor\":5}}", READ)
                .getAsJsonObject("error").get("message").getAsString());
            assertEquals("DENIED", h.rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"job.get\",\"params\":{\"id\":\"" + id + "\"}}", EXECUTE_ONLY)
                .getAsJsonObject("error").get("message").getAsString());
        } finally { h.stop(); }
    }

    @Test public void outputReadReturnsStdoutAsBinaryFrameAndNoFrameWhenEmpty() throws Exception {
        Harness h = new Harness("hi\n".getBytes(StandardCharsets.UTF_8), false);
        try {
            String id = h.startJob("req-3");
            h.waitTerminal(id);
            RpcDispatcher.Result stdout = h.raw("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"job.output.read\","
                + "\"params\":{\"id\":\"" + id + "\",\"stream\":\"stdout\",\"offset\":0,\"limit\":16}}", ALL_JOBS);
            JsonObject metadata = object(stdout.json).getAsJsonObject("result");
            assertEquals(3, metadata.get("length").getAsInt());
            assertEquals(3, metadata.get("endOffset").getAsInt());
            assertTrue(metadata.get("finished").getAsBoolean());
            assertEquals("hi\n", new String(stdout.binary, StandardCharsets.UTF_8));

            RpcDispatcher.Result stderr = h.raw("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"job.output.read\","
                + "\"params\":{\"id\":\"" + id + "\",\"stream\":\"stderr\",\"offset\":0,\"limit\":16}}", ALL_JOBS);
            assertEquals(0, object(stderr.json).getAsJsonObject("result").get("length").getAsInt());
            assertNull(stderr.binary);

            assertEquals(-32602, h.rpc("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"job.output.read\","
                + "\"params\":{\"id\":\"" + id + "\",\"stream\":\"pty\",\"offset\":0,\"limit\":16}}", ALL_JOBS)
                .getAsJsonObject("error").get("code").getAsInt());
        } finally { h.stop(); }
    }

    @Test public void cancelIsUnsupportedForClaimedJobAndDeniedWithoutCancelScope() throws Exception {
        Harness h = new Harness(new byte[0], true);
        try {
            String id = h.startJob("req-4");
            assertTrue(h.launching.await(2, TimeUnit.SECONDS));
            String cancel = "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"job.cancel\",\"params\":{\"id\":\"" + id + "\"}}";
            assertEquals("UNSUPPORTED", h.rpc(cancel, ALL_JOBS).getAsJsonObject("error").get("message").getAsString());
            assertEquals("DENIED", h.rpc(cancel, READ).getAsJsonObject("error").get("message").getAsString());
            assertEquals("NOT_FOUND", h.rpc("{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"job.cancel\","
                + "\"params\":{\"id\":\"job_missing\"}}", ALL_JOBS).getAsJsonObject("error").get("message").getAsString());
            h.release.countDown();
            h.waitTerminal(id);
        } finally { h.stop(); }
    }

    @Test public void revokedConsentDeniesEveryJobMethod() throws Exception {
        Harness h = new Harness(new byte[0], false);
        try {
            h.authority.revoke();
            String[] requests = {
                "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"job.list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"job.get\",\"params\":{\"id\":\"job_1\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"job.output.read\",\"params\":{\"id\":\"job_1\",\"stream\":\"stdout\",\"offset\":0,\"limit\":4}}",
                "{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"job.cancel\",\"params\":{\"id\":\"job_1\"}}",
            };
            for (String request : requests) {
                assertEquals(request, "DENIED", h.rpc(request, ALL_JOBS).getAsJsonObject("error").get("message").getAsString());
            }
        } finally { h.stop(); }
    }

    private static String startWith(String argsJson, String clientRequestId) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"job.start\",\"params\":{\"executable\":\"/bin/echo\","
            + "\"args\":" + argsJson + ",\"clientRequestId\":\"" + clientRequestId + "\"}}";
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static JsonObject object(byte[] value) {
        return JsonParser.parseString(new String(value, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void await(CountDownLatch gate) {
        try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
