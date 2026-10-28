package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
import com.termux.app.control.OutputSubscriptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/** Protocol loop for one already accepted connection. The transport supplies verified peer credentials. */
final class RpcConnection {
    interface Running { boolean get(); }
    interface RequestPermit { void await() throws InterruptedException; }
    private final ControlCore mCore;
    private final ServiceThreadDispatch mDispatch;
    private final Set<ControlCore.Scope> mScopes;
    private final Running mRunning;
    private final OutputSubscriptions mOutputRegistry;
    private final RequestPermit mPermit;
    private final java.security.SecureRandom mTokenRandom;

    RpcConnection(ControlCore core, ServiceThreadDispatch dispatch, Set<ControlCore.Scope> scopes, Running running) {
        this(core, dispatch, scopes, running, null);
    }

    RpcConnection(ControlCore core, ServiceThreadDispatch dispatch, Set<ControlCore.Scope> scopes, Running running,
                  OutputSubscriptions outputRegistry) {
        this(core, dispatch, scopes, running, outputRegistry, new RequestRateLimiter(System.nanoTime())::await);
    }
    RpcConnection(ControlCore core, ServiceThreadDispatch dispatch, Set<ControlCore.Scope> scopes, Running running,
                  OutputSubscriptions outputRegistry, RequestPermit permit) {
        if (permit == null) throw new IllegalArgumentException("Missing request limiter");
        mCore = core; mDispatch = dispatch; mScopes = scopes; mRunning = running; mOutputRegistry = outputRegistry; mPermit = permit;
        // Constructed on the socket worker; no secure-random operation runs on the service thread.
        mTokenRandom = scopes != null && scopes.contains(ControlCore.Scope.SESSIONS_OUTPUT_RESUME) ? new java.security.SecureRandom() : null;
    }

    private String freshToken() {
        byte[] bytes = new byte[32]; mTokenRandom.nextBytes(bytes);
        char[] hex = new char[64], digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { hex[i * 2] = digits[(bytes[i] & 255) >>> 4]; hex[i * 2 + 1] = digits[bytes[i] & 15]; }
        java.util.Arrays.fill(bytes, (byte) 0);
        return new String(hex);
    }

    /** Peer UID is supplied by the operating system, never by the incoming frame. */
    void serve(int ownerUid, int peerUid, InputStream input, OutputStream output) throws IOException {
        if (ownerUid < 0 || peerUid != ownerUid) return;
        FrameCodec frames = new FrameCodec(RpcRequestParser.MAX_REQUEST_BYTES);
        RpcRequestParser parser = new RpcRequestParser();
        OutputClientLease lease = mOutputRegistry == null ? null : new OutputClientLease(mOutputRegistry, mDispatch, mScopes);
        RpcDispatcher dispatcher = new RpcDispatcher(mCore, lease);
        try {
        while (mRunning.get()) {
            try { mPermit.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            if (!mRunning.get()) return;
            byte[] body = frames.read(input);
            if (body == null) return;
            if (!mRunning.get()) return;
            RpcRequestParser.Request request;
            try { request = parser.parse(body); }
            catch (RpcRequestParser.InvalidRequest e) {
                frames.write(output, RpcDispatcher.invalidRequest(e.parseError));
                return;
            }
            byte[] binary = null;
            if ("session.write".equals(request.method)) {
                RpcDispatcher.BinaryPreparation preparation = dispatcher.prepareBinary(request, mScopes);
                if (preparation.length == 0) {
                    if (preparation.error != null) frames.write(output, preparation.error);
                    return;
                }
                binary = frames.read(input);
                if (!mRunning.get()) return;
                if (binary == null || binary.length != preparation.length) {
                    byte[] error = RpcDispatcher.invalidBinary(request);
                    if (error != null) frames.write(output, error);
                    return;
                }
            }
            final byte[] payload = binary;
            final String token = mTokenRandom != null && "session.output.subscribe".equals(request.method) && request.hasId ? freshToken() : null;
            RpcDispatcher.Result response;
            try {
                response = mDispatch.call(() -> !mRunning.get() ? null : dispatcher.dispatchResult(request, mScopes, payload, token), 3000);
            } catch (ServiceThreadDispatch.DispatchException e) {
                byte[] failure = RpcDispatcher.transportFailure(request, e.reason);
                if (failure != null) frames.write(output, failure);
                return;
            }
            if (response != null && mRunning.get()) {
                if (response.json != null) frames.write(output, response.json);
                if (response.binary != null && mRunning.get()) frames.write(output, response.binary);
            }
            if (!dispatcher.isNegotiated()) return;
        }
        } finally {
            if (lease != null) lease.close();
        }
    }
}
