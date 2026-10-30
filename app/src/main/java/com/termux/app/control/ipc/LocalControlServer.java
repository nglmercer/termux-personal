package com.termux.app.control.ipc;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.StructTimeval;

import com.termux.app.control.ControlCore;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in, same-UID, filesystem Unix socket. No component starts this automatically. */
public final class LocalControlServer implements AutoCloseable {
    private static final int MAX_CLIENTS = 20;
    private static final int IDLE_TIMEOUT_MS = 5000;
    public interface StopListener { void onStopped(LocalControlServer server); }
    private final Context mContext;
    private final StopListener mStopListener;
    private final ControlCore mCore;
    private final com.termux.app.control.OutputSubscriptions mOutputRegistry;
    private final ServiceThreadDispatch mDispatch;
    private final Set<ControlCore.Scope> mScopes;
    private final Set<LocalSocket> mClients = Collections.synchronizedSet(new HashSet<>());
    private final ThreadPoolExecutor mWorkers = new ThreadPoolExecutor(0, MAX_CLIENTS, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>());
    private static final int NEW = 0, STARTING = 1, RUNNING = 2, STOPPED = 3;
    private final AtomicInteger mState = new AtomicInteger(NEW);
    private boolean mClosed;
    private LocalSocket mOwner;
    private LocalServerSocket mServer;
    private File mSocketFile;
    private long mSocketDev, mSocketInode;
    private volatile DomainServices mServices = DomainServices.NONE;

    /**
     * Trusted domain wiring. It must be supplied before start, so every accepted connection sees the same services.
     * Clients cannot supply or change them. A null service hides its methods from discovery.
     */
    public synchronized void configureDomains(com.termux.app.control.jobs.JobCoordinator jobs,
                                              com.termux.app.control.jobs.JobAuthority jobAuthority,
                                              com.termux.app.control.process.ProcessService processes,
                                              com.termux.app.control.files.FileService files,
                                              com.termux.app.control.workspace.WorkspaceRepository workspaces) {
        if (mState.get() != NEW) throw new IllegalStateException("Domains must be configured before start");
        // A client-facing file service must not be redirectable by a concurrent same-UID directory swap.
        if (files != null && !files.raceSafe()) throw new IllegalArgumentException("File service is not race-safe");
        mServices = new DomainServices(jobs, jobAuthority, processes, files, workspaces);
    }

    /** Scopes must come from an explicit trusted local configuration, not a client request. */
    public LocalControlServer(Context context, ControlCore core, ServiceThreadDispatch dispatch,
                              Set<ControlCore.Scope> scopes, StopListener stopListener,
                              com.termux.app.control.OutputSubscriptions outputRegistry) {
        if (context == null || core == null || dispatch == null || scopes == null)
            throw new IllegalArgumentException("Missing server configuration");
        mContext = context.getApplicationContext();
        mStopListener = stopListener;
        mOutputRegistry = outputRegistry;
        mCore = core;
        mDispatch = dispatch;
        mScopes = Collections.unmodifiableSet(scopes.isEmpty() ? EnumSet.noneOf(ControlCore.Scope.class) : EnumSet.copyOf(scopes));
    }

    /** Bind on a background thread. Existing paths are never replaced or unlinked. */
    public synchronized void start() throws IOException {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Socket binding must not run on the main thread");
        if (mClosed || !mState.compareAndSet(NEW, STARTING))
            throw new IOException("Server already started or closed");
        File root = mContext.getFilesDir();
        if (root == null) throw new IOException("Private directory unavailable");
        File dir = new File(root, "control-ipc");
        if ((!dir.isDirectory() && !dir.mkdir()) || !dir.isDirectory()
            || !dir.getCanonicalFile().equals(new File(root.getCanonicalFile(), "control-ipc")))
            throw new IOException("Invalid private socket directory");
        try {
            StructStat dirStat = Os.lstat(dir.getAbsolutePath());
            if (!OsConstants.S_ISDIR(dirStat.st_mode) || dirStat.st_uid != Process.myUid())
                throw new IOException("Invalid socket directory owner");
            Os.chmod(dir.getAbsolutePath(), 0700);
        } catch (ErrnoException e) { throw new IOException("Cannot protect socket directory", e); }
        File path = new File(dir, "control.sock");
        LocalSocket owner = new LocalSocket(LocalSocket.SOCKET_STREAM);
        boolean bound = false;
        try {
            owner.bind(new LocalSocketAddress(path.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            bound = true;
            StructStat socketStat = Os.lstat(path.getAbsolutePath());
            if (!OsConstants.S_ISSOCK(socketStat.st_mode) || socketStat.st_uid != Process.myUid())
                throw new IOException("Invalid socket owner");
            mSocketFile = path;
            mSocketDev = socketStat.st_dev;
            mSocketInode = socketStat.st_ino;
            Os.chmod(path.getAbsolutePath(), 0600);
            LocalServerSocket server = new LocalServerSocket(owner.getFileDescriptor());
            mOwner = owner;
            mServer = server;
            if (!mState.compareAndSet(STARTING, RUNNING))
                throw new IOException("Server stopped during startup");
            new Thread(this::acceptLoop, "TermuxControlAccept").start();
        } catch (ErrnoException | IOException | RuntimeException e) {
            mState.set(STOPPED);
            try { owner.close(); } catch (IOException ignored) { }
            if (bound) removeOwnedSocket();
            throw new IOException("Cannot start local control socket", e);
        }
    }

    public boolean isRunning() { return mState.get() == RUNNING; }

    public boolean isStopping() { return mState.get() == STOPPED; }

    public boolean allowsSessionWrite() { return mScopes.contains(ControlCore.Scope.SESSIONS_WRITE); }

    public boolean allowsOutput() { return mScopes.contains(ControlCore.Scope.SESSIONS_OUTPUT); }

    public boolean allowsOutputResume() { return mScopes.contains(ControlCore.Scope.SESSIONS_OUTPUT_RESUME); }

    public boolean allowsJobExecute() { return mScopes.contains(ControlCore.Scope.JOBS_EXECUTE); }

    /** True when any managed-job scope was selected by local consent. */
    public boolean allowsJobs() {
        for (ControlCore.Scope scope : mScopes) if (scope.name().startsWith("JOBS_")) return true;
        return false;
    }

    private void acceptLoop() {
        try {
            while (isRunning()) {
                LocalSocket client = mServer.accept();
                if (!isRunning()) { client.close(); break; }
                try {
                    int peerUid = client.getPeerCredentials().getUid();
                    if (peerUid != Process.myUid()) { client.close(); continue; }
                    mClients.add(client);
                    mWorkers.execute(() -> serve(client, peerUid));
                } catch (IOException | RuntimeException e) {
                    mClients.remove(client);
                    try { client.close(); } catch (IOException ignored) { }
                }
            }
        } catch (IOException ignored) {
            // Socket closure and transient accept errors terminate this listener.
        } finally {
            close();
        }
    }

    private void serve(LocalSocket client, int peerUid) {
        try {
            client.setSoTimeout(IDLE_TIMEOUT_MS);
            Os.setsockoptTimeval(client.getFileDescriptor(), OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
                StructTimeval.fromMillis(IDLE_TIMEOUT_MS));
            InputStream input = new FrameDeadlineInput(client.getInputStream(), SystemClock::elapsedRealtime,
                client::setSoTimeout, IDLE_TIMEOUT_MS);
            RpcConnection connection = new RpcConnection(mCore, mDispatch, mScopes, this::isRunning, mOutputRegistry,
                new RequestRateLimiter(System.nanoTime())::await, mServices);
            connection.serve(Process.myUid(), peerUid, input, client.getOutputStream());
        } catch (IOException | ErrnoException | RuntimeException ignored) {
            // Never log peer requests, session content, or provider paths.
        } finally {
            mClients.remove(client);
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    /** Stop accepting work immediately, then close descriptors off the caller thread. */
    public void requestStop() {
        mState.set(STOPPED);
        new Thread(this::close, "TermuxControlStop").start();
    }

    @Override
    public synchronized void close() {
        if (mClosed) return;
        mClosed = true;
        mState.set(STOPPED);
        if (mOwner != null) {
            try { Os.shutdown(mOwner.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (Exception ignored) { }
            try { mOwner.close(); } catch (IOException ignored) { }
        }
        if (mServer != null) try { mServer.close(); } catch (IOException ignored) { }
        ArrayList<LocalSocket> clients;
        synchronized (mClients) { clients = new ArrayList<>(mClients); }
        for (LocalSocket client : clients) try { client.close(); } catch (IOException ignored) { }
        mWorkers.shutdownNow();
        removeOwnedSocket();
        if (mStopListener != null) mStopListener.onStopped(this);
    }

    private void removeOwnedSocket() {
        if (mSocketFile == null) return;
        try {
            StructStat current = Os.lstat(mSocketFile.getAbsolutePath());
            if (OsConstants.S_ISSOCK(current.st_mode) && current.st_dev == mSocketDev && current.st_ino == mSocketInode)
                Os.remove(mSocketFile.getAbsolutePath());
        } catch (ErrnoException ignored) { }
    }


}
