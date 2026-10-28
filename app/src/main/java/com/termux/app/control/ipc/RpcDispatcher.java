package com.termux.app.control.ipc;

import com.google.gson.stream.JsonWriter;
import com.termux.app.control.ControlCore;
import com.termux.terminal.OutputReplayBuffer;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    public RpcDispatcher(ControlCore core) { this(core, null); }

    RpcDispatcher(ControlCore core, OutputClientLease output) {
        if (core == null) throw new IllegalArgumentException("core required");
        mCore = core; mOutput = output;
    }

    /** Scopes must be derived from trusted peer authorization, never request parameters. */
    public byte[] dispatch(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes) {
        return dispatchResult(request, scopes, null).json;
    }

    byte[] dispatchBinary(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes, byte[] binary) {
        return dispatchResult(request, scopes, binary).json;
    }

    static final class Result {
        final byte[] json, binary;
        Result(byte[] json, byte[] binary) { this.json = json; this.binary = binary; }
    }
    private static Result json(byte[] data) { return new Result(data, null); }
    private static final class OutputValue {
        final Object metadata;
        final byte[] bytes;
        OutputValue(Object metadata, byte[] bytes) { this.metadata = metadata; this.bytes = bytes; }
    }

    Result dispatchResult(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes, byte[] binary) {
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
        } catch (RuntimeException e) {
            // Never serialize backend exceptions, command data or environment to clients.
            return json(request.hasId ? error(request.id, -32603, "Internal error") : null);
        }
    }

    public boolean isNegotiated() { return mNegotiation.isReady(); }

    private Object invoke(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes, byte[] binary, String newToken)
        throws ControlCore.ControlException, BadParameters, UnknownMethod {
        switch (request.method) {
            case "system.hello":
                return Collections.singletonMap("version", mCore.hello(ControlCore.PROTOCOL_VERSION));
            case "system.capabilities":
                parameters(request.params);
                List<String> methods = new ArrayList<>(mCore.capabilities());
                if (scopes == null || !scopes.contains(ControlCore.Scope.SESSIONS_READ)) methods.remove("session.list");
                if (scopes == null || !scopes.contains(ControlCore.Scope.SESSIONS_WRITE)) {
                    methods.remove("session.create"); methods.remove("session.write");
                    methods.remove("session.rename"); methods.remove("session.resize"); methods.remove("session.focus");
                }
                if (scopes == null || !scopes.contains(ControlCore.Scope.SESSIONS_WRITE) ||
                    !scopes.contains(ControlCore.Scope.SESSIONS_CLOSE)) methods.remove("session.close");
                if (scopes == null || !scopes.contains(ControlCore.Scope.SESSIONS_OUTPUT)) methods.remove("session.snapshot");
                if (mOutput != null && scopes != null && scopes.contains(ControlCore.Scope.SESSIONS_OUTPUT)) {
                    methods.add("session.output.subscribe"); methods.add("session.output.read"); methods.add("session.output.unsubscribe");
                    if (scopes.contains(ControlCore.Scope.SESSIONS_OUTPUT_RESUME)) methods.add("session.output.resume");
                }
                return Collections.singletonMap("methods", methods);
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
            default: throw new UnknownMethod();
        }
    }

    /** Metadata and authorization are checked before reading a separate binary frame. */
    BinaryPreparation prepareBinary(RpcRequestParser.Request request, Set<ControlCore.Scope> scopes) {
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

    private static final class BadParameters extends Exception { }
    private static final class UnknownMethod extends Exception { }
}
