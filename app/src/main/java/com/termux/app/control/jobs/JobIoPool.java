package com.termux.app.control.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded worker reservations for the three process pipes of each managed job. */
public final class JobIoPool implements AutoCloseable {
    public static final int MAX_JOBS = 4;
    private static final int TASKS_PER_JOB = 3;
    private final int mMaxJobs;
    private final ThreadPoolExecutor mWorkers;
    private final Set<Lease> mLeases = new HashSet<>();
    private boolean mClosed;

    public JobIoPool(int maxJobs) {
        if (maxJobs < 1 || maxJobs > MAX_JOBS) throw new IllegalArgumentException("Invalid job limit");
        mMaxJobs = maxJobs;
        AtomicInteger ids = new AtomicInteger();
        mWorkers = new ThreadPoolExecutor(maxJobs * TASKS_PER_JOB, maxJobs * TASKS_PER_JOB, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(maxJobs * TASKS_PER_JOB), task -> {
                Thread thread = new Thread(task, "TermuxJobIO-" + ids.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        mWorkers.allowCoreThreadTimeOut(true);
    }

    /** Must be called before launching a process; null means the worker quota is exhausted. */
    public synchronized Lease tryReserve() {
        if (mClosed) throw new IllegalStateException("Job I/O pool closed");
        if (mLeases.size() >= mMaxJobs) return null;
        Lease lease = new Lease(this);
        mLeases.add(lease);
        return lease;
    }

    private synchronized void release(Lease lease) { mLeases.remove(lease); }

    @Override public void close() {
        List<Lease> leases;
        synchronized (this) {
            if (mClosed) return;
            mClosed = true;
            leases = new ArrayList<>(mLeases);
        }
        for (Lease lease : leases) lease.shutdownNow();
        for (Runnable task : mWorkers.shutdownNow()) {
            if (task instanceof TrackedTask) ((TrackedTask) task).discard();
        }
    }

    private static final class TrackedTask implements Runnable {
        private final Lease mLease;
        private final Runnable mCommand;
        private final java.util.concurrent.atomic.AtomicBoolean mFinished = new java.util.concurrent.atomic.AtomicBoolean();
        TrackedTask(Lease lease, Runnable command) { mLease = lease; mCommand = command; }
        @Override public void run() { try { mCommand.run(); } finally { finish(); } }
        void discard() { if (mCommand instanceof Future<?>) ((Future<?>) mCommand).cancel(true); finish(); }
        private void finish() { if (mFinished.compareAndSet(false, true)) mLease.finished(mCommand); }
    }

    /** A one-job executor; close releases its reservation only after all submitted tasks actually exit. */
    public static final class Lease extends AbstractExecutorService implements AutoCloseable {
        private final JobIoPool mPool;
        private final Set<Runnable> mRunning = new HashSet<>();
        private int mSubmitted;
        private boolean mShutdown, mReleasing, mReleased;

        private Lease(JobIoPool pool) { mPool = pool; }

        @Override public void execute(Runnable command) {
            if (command == null) throw new NullPointerException("Missing task");
            synchronized (this) {
                if (mShutdown || mSubmitted >= TASKS_PER_JOB) throw new RejectedExecutionException("Job I/O reservation unavailable");
                if (!mRunning.add(command)) throw new RejectedExecutionException("Task already submitted");
                mSubmitted++;
            }
            TrackedTask tracked = new TrackedTask(this, command);
            try {
                mPool.mWorkers.execute(tracked);
            } catch (RuntimeException | Error e) {
                tracked.discard();
                throw e;
            }
        }

        private void finished(Runnable command) {
            boolean release;
            synchronized (this) {
                mRunning.remove(command);
                release = beginRelease();
                notifyAll();
            }
            if (release) completeRelease();
        }

        /** Call only while holding the lease lock; release the pool lock separately. */
        private boolean beginRelease() {
            if (!mShutdown || !mRunning.isEmpty() || mReleasing || mReleased) return false;
            mReleasing = true;
            return true;
        }
        private void completeRelease() {
            mPool.release(this);
            synchronized (this) { mReleased = true; mReleasing = false; notifyAll(); }
        }

        @Override public void shutdown() {
            boolean release;
            synchronized (this) {
                mShutdown = true;
                release = beginRelease();
                notifyAll();
            }
            if (release) completeRelease();
        }

        @Override public List<Runnable> shutdownNow() {
            List<Runnable> running;
            boolean release;
            synchronized (this) {
                mShutdown = true;
                running = new ArrayList<>(mRunning);
                release = beginRelease();
                notifyAll();
            }
            if (release) completeRelease();
            for (Runnable task : running) if (task instanceof Future<?>) ((Future<?>) task).cancel(true);
            return Collections.emptyList(); // Tasks remain accounted until their wrappers finish or are discarded.
        }

        @Override public synchronized boolean isShutdown() { return mShutdown; }
        @Override public synchronized boolean isTerminated() { return mReleased; }
        @Override public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            if (unit == null || timeout < 0) throw new IllegalArgumentException("Invalid timeout");
            long remaining = unit.toNanos(timeout), deadline = System.nanoTime() + remaining;
            while (!isTerminated()) {
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = deadline - System.nanoTime();
            }
            return true;
        }
        @Override public void close() { shutdown(); }
    }
}
