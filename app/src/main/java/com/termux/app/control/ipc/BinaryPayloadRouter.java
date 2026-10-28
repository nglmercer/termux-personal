package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Selects a binary-frame preparer before a connection reads request payload bytes. */
final class BinaryPayloadRouter {
    private interface Preparer {
        RpcDispatcher.BinaryPreparation prepare(RpcDispatcher dispatcher, RpcRequestParser.Request request,
                                                 Set<ControlCore.Scope> scopes);
    }
    private static final Map<String, Preparer> REQUESTS;
    static {
        Map<String, Preparer> routes = new LinkedHashMap<>();
        routes.put("session.write", RpcDispatcher::prepareSessionWrite);
        REQUESTS = Collections.unmodifiableMap(routes);
    }

    static boolean expectsRequestFrame(String method) { return REQUESTS.containsKey(method); }
    static Set<String> requestMethods() { return REQUESTS.keySet(); }
    static RpcDispatcher.BinaryPreparation prepare(RpcDispatcher dispatcher, RpcRequestParser.Request request,
                                                     Set<ControlCore.Scope> scopes) {
        Preparer preparer = REQUESTS.get(request.method);
        if (preparer == null) throw new IllegalArgumentException("No binary request route");
        return preparer.prepare(dispatcher, request, scopes);
    }
    private BinaryPayloadRouter() { }
}
