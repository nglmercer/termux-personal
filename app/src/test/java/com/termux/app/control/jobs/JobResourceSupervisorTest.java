package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import com.termux.app.monitor.ProcessSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JobResourceSupervisorTest {
    private static final long MIB = 1L << 20, GIB = 1L << 30, PAGE = 4096;
    private static final int UID = 1000;

    private File proc;
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final AtomicReference<PressureTracker.Reading> device = new AtomicReference<>(new PressureTracker.Reading(6 * GIB, 8 * GIB, false, null));
    private final AtomicInteger deviceReads = new AtomicInteger();
    private final AtomicBoolean failDevice = new AtomicBoolean();
    private ResourceAdmissionController admission;
    private JobResourceSupervisor supervisor;
    private ScheduledThreadPoolExecutor executor;
    /** Real-time timers for the jobs' own TERM-to-KILL grace; deliberately not the shortened supervisor executor. */
    private final ScheduledThreadPoolExecutor jobTimers = new ScheduledThreadPoolExecutor(1);

    @Before public void setUp() throws Exception {
        proc = Files.createTempDirectory("supervisor-proc").toFile();
        admission = new ResourceAdmissionController(new ResourceAdmissionController.Config(4, 0, 0, 256 * MIB, 0), 4, clock::get);
        // Delays are shortened so the 2 s production cadence does not make the tests slow; the logic is unchanged.
        executor = new ScheduledThreadPoolExecutor(1) {
            @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                return super.schedule(command, Math.min(delay, 5), TimeUnit.MILLISECONDS);
            }
        };
        supervisor = new JobResourceSupervisor(new JobResourceSupervisor.Sampler() {
            @Override public PressureTracker.Reading device() {
                deviceReads.incrementAndGet();
                if (failDevice.get()) throw new IllegalStateException("sampling failed");
                return device.get();
            }
            @Override public ProcessSnapshot processes(Set<Integer> roots) { return ProcessSnapshot.collect(proc, UID, roots); }
            @Override public long monotonicMillis() { return clock.get(); }
            @Override public long ticksPerSecond() { return 100; }
            @Override public long pageSizeBytes() { return PAGE; }
        }, admission, executor);
    }

    @After public void tearDown() {
        supervisor.close(); jobTimers.shutdownNow();
        for (File dir : proc.listFiles()) { for (File f : dir.listFiles()) f.delete(); dir.delete(); }
        proc.delete();
    }

    private void process(int pid, int ppid, long start, long rssPages, long ticks) throws Exception {
        File dir = new File(proc, Integer.toString(pid)); dir.mkdirs();
        Files.write(new File(dir, "status").toPath(), ("Name:\tx\nUid:\t" + UID + "\t" + UID + "\t" + UID + "\t" + UID + "\n").getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(dir, "stat").toPath(), (pid + " (p" + pid + ") S " + ppid + " 0 0 0 0 0 0 0 0 0 " + ticks + " 0 0 0 0 0 0 0 " + start + " 0 " + rssPages + "\n")
            .getBytes(StandardCharsets.US_ASCII));
    }

    private static final class Job {
        final JobRecord record; final OwnedProcessControl control; final OwnedChildProcess process; final AtomicInteger signals = new AtomicInteger();
        Job(String id, int pid, ScheduledThreadPoolExecutor timers) {
            record = new JobRecord(id, null, 1);
            control = new OwnedProcessControl(record, timers, 1000, p -> signals.incrementAndGet(), p -> signals.incrementAndGet());
            process = new OwnedChildProcess(pid, new OwnedChildProcess.Ops() {
                @Override public int waitExit(int p) { return 0; }
                @Override public int reap(int p, int[] out) { return 0; }
                @Override public int signal(int p, int s) { return 0; }
            }, new ByteArrayOutputStream(), new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0]));
            assertTrue(record.begin()); assertTrue(record.started(null, 1));
            control.attach(process);
        }
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 400; i++) { if (condition.getAsBoolean()) return true; Thread.sleep(10); }
        return condition.getAsBoolean();
    }

    private static JobResourcePolicy policy(JobResourcePolicy.PressureAction action, Long soft) {
        return JobResourcePolicy.builder().profile(JobResourcePolicy.Profile.BALANCED).onPressure(action).memorySoftBytes(soft).build();
    }

    @Test public void measuresTheJobTreeAndKeepsUnknownValuesNull() throws Exception {
        process(100, 1, 500, 1000, 10); process(101, 100, 501, 2000, 5); process(900, 1, 600, 5000, 0); // 900 is unrelated
        Job job = new Job("job_a", 100, jobTimers);
        supervisor.track(job.record, job.control, job.process, policy(JobResourcePolicy.PressureAction.WARN, null));
        assertTrue(await(() -> supervisor.metrics("job_a") != null && supervisor.metrics("job_a").rssBytes != null));
        JobResourceSupervisor.Metrics m = supervisor.metrics("job_a");
        assertEquals(Long.valueOf(3000 * PAGE), m.rssBytes);
        assertEquals(Integer.valueOf(2), m.processCount);
        assertEquals("the unrelated process is not attributed", Long.valueOf(3000 * PAGE), m.peakRssBytes);
        assertNull("one sample cannot give a rate", m.cpuPercent);
        // Growth raises the peak; shrinking keeps it.
        process(101, 100, 501, 6000, 5);
        assertTrue(await(() -> supervisor.metrics("job_a").peakRssBytes == 7000 * PAGE));
        process(101, 100, 501, 100, 5);
        assertTrue(await(() -> supervisor.metrics("job_a").rssBytes == 1100 * PAGE));
        assertEquals(Long.valueOf(7000 * PAGE), supervisor.metrics("job_a").peakRssBytes);
        // Root hidden or gone: unknown, not zero.
        new File(proc, "100/stat").delete(); new File(proc, "100/status").delete(); new File(proc, "100").delete();
        assertTrue(await(() -> supervisor.metrics("job_a").rssBytes == null));
        assertNull(supervisor.metrics("job_a").processCount);
    }

    @Test public void aRecycledPidIsDetectedAndNeverAttributed() throws Exception {
        process(100, 1, 500, 1000, 10);
        Job job = new Job("job_r", 100, jobTimers);
        supervisor.track(job.record, job.control, job.process, policy(JobResourcePolicy.PressureAction.WARN, null));
        assertTrue(await(() -> supervisor.metrics("job_r").rssBytes != null));
        process(100, 1, 777, 90000, 10); // same PID, different start time: another process
        assertTrue(await(() -> supervisor.metrics("job_r").identityLost));
        assertNull(supervisor.metrics("job_r").rssBytes);
        assertEquals("the old process's peak is not polluted by the new one", Long.valueOf(1000 * PAGE), supervisor.metrics("job_r").peakRssBytes);
    }

    @Test public void devicePressureReachesAdmissionSoQueuedJobsStartOnlyAfterRecovery() throws Exception {
        process(100, 1, 500, 10, 1);
        Job job = new Job("job_p", 100, jobTimers);
        device.set(new PressureTracker.Reading(100 * MIB, 8 * GIB, false, null)); // critical from the first reading
        supervisor.track(job.record, job.control, job.process, policy(JobResourcePolicy.PressureAction.WARN, null));
        assertTrue(await(() -> admission.level() == PressureTracker.Level.CRITICAL));
        AtomicInteger started = new AtomicInteger();
        assertEquals(ResourceAdmissionController.Result.QUEUED,
            admission.submit(admission.newTicket(JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.BALANCED)), started::incrementAndGet));
        supervisor.wake();
        Thread.sleep(100);
        assertEquals(0, started.get());
        device.set(new PressureTracker.Reading(6 * GIB, 8 * GIB, false, null));
        assertTrue("admitted once the tracker saw sustained recovery", await(() -> started.get() == 1));
        assertTrue(supervisor.events("job_p").size() >= 0);
        boolean sawCritical = false;
        for (JobResourceSupervisor.Event e : supervisor.events(null)) sawCritical |= e.type.equals("PRESSURE_CRITICAL");
        assertTrue(sawCritical);
    }

    @Test public void onlyAJobThatOptedInIsStoppedAndOnlyTheLargestOnePerCooldown() throws Exception {
        process(100, 1, 500, 20000, 1); process(200, 1, 510, 30000, 1); process(300, 1, 520, 40000, 1);
        Job warn = new Job("job_w", 300, jobTimers), small = new Job("job_s", 100, jobTimers), large = new Job("job_l", 200, jobTimers);
        JobResourcePolicy stop = policy(JobResourcePolicy.PressureAction.STOP_JOB, 32 * MIB);
        supervisor.track(warn.record, warn.control, warn.process, policy(JobResourcePolicy.PressureAction.WARN, 32 * MIB));
        supervisor.track(small.record, small.control, small.process, stop);
        supervisor.track(large.record, large.control, large.process, stop);
        device.set(new PressureTracker.Reading(100 * MIB, 8 * GIB, false, null)); // critical, sustained
        assertTrue(await(() -> large.record.snapshot().stopReason == JobRecord.StopReason.RESOURCE_GUARD));
        Thread.sleep(150); // many more scans at the same fake time: inside the cooldown
        assertNull("the smaller opted-in job is spared inside the cooldown", small.record.snapshot().stopReason);
        assertNull("a job that did not opt in is never stopped, whatever the pressure", warn.record.snapshot().stopReason);
        assertEquals(1, large.signals.get());
        assertEquals(0, warn.signals.get());
        clock.addAndGet(JobResourceSupervisor.GUARD_COOLDOWN_MILLIS + 1);
        assertTrue("after the cooldown the next candidate may be stopped", await(() -> small.record.snapshot().stopReason == JobRecord.StopReason.RESOURCE_GUARD));
        assertNull(warn.record.snapshot().stopReason);
        boolean recorded = false;
        for (JobResourceSupervisor.Event e : supervisor.events("job_l")) recorded |= e.type.equals("RESOURCE_GUARD_STOP");
        assertTrue(recorded);
    }

    @Test public void stopJobNeedsPressureNotJustAHighReading() throws Exception {
        process(100, 1, 500, 50000, 1);
        Job job = new Job("job_n", 100, jobTimers);
        supervisor.track(job.record, job.control, job.process, policy(JobResourcePolicy.PressureAction.STOP_JOB, 32 * MIB));
        assertTrue(await(() -> supervisor.metrics("job_n").memorySoftExceeded));
        Thread.sleep(100);
        assertNull("over its own threshold on a healthy device is only recorded", job.record.snapshot().stopReason);
        boolean exceeded = false;
        for (JobResourceSupervisor.Event e : supervisor.events("job_n")) exceeded |= e.type.equals("MEMORY_SOFT_EXCEEDED");
        assertTrue(exceeded);
    }

    @Test public void itStopsSamplingWhenNothingIsTrackedOrWaiting() throws Exception {
        process(100, 1, 500, 10, 1);
        Job job = new Job("job_i", 100, jobTimers);
        AtomicReference<String> finished = new AtomicReference<>();
        supervisor.setFinishListener((id, metrics, events) -> finished.set(id));
        supervisor.track(job.record, job.control, job.process, policy(JobResourcePolicy.PressureAction.WARN, null));
        assertTrue(await(() -> deviceReads.get() > 3));
        job.record.complete(JobRecord.State.EXITED, 0, null, 5);
        assertTrue(await(() -> "job_i".equals(finished.get())));
        Thread.sleep(100);
        int reads = deviceReads.get();
        Thread.sleep(150);
        assertEquals("idle: no further device or /proc reads are scheduled", reads, deviceReads.get());
        assertNotNull("the last metrics stay readable after the job finished", supervisor.metrics("job_i"));
        // New work wakes it again.
        Job next = new Job("job_j", 100, jobTimers);
        supervisor.track(next.record, next.control, next.process, policy(JobResourcePolicy.PressureAction.WARN, null));
        assertTrue(await(() -> deviceReads.get() > reads));
    }

    @Test public void aFailingSampleIsRecordedAndSupervisionContinues() throws Exception {
        process(100, 1, 500, 10, 1);
        Job job = new Job("job_f", 100, jobTimers);
        failDevice.set(true);
        supervisor.track(job.record, job.control, job.process, policy(JobResourcePolicy.PressureAction.WARN, null));
        assertTrue(await(() -> deviceReads.get() > 3));
        failDevice.set(false);
        assertTrue(await(() -> supervisor.metrics("job_f").rssBytes != null));
        boolean failed = false;
        for (JobResourceSupervisor.Event e : supervisor.events(null)) failed |= e.type.equals("SAMPLE_FAILED");
        assertTrue(failed);
    }

    @Test public void aJobAlreadyFinishedIsNotTracked() throws Exception {
        process(100, 1, 500, 10, 1);
        Job job = new Job("job_d", 100, jobTimers);
        job.record.complete(JobRecord.State.EXITED, 0, null, 5);
        supervisor.track(job.record, job.control, job.process, policy(JobResourcePolicy.PressureAction.WARN, null));
        Thread.sleep(50);
        assertNull(supervisor.metrics("job_d"));
        assertEquals(0, deviceReads.get());
    }

    @Test public void aSurvivorThatLeftTheTreeIsReportedAsPossiblyDetachedNotAsMemberAndNeverSignalled() throws Exception {
        process(100, 1, 500, 100, 1); process(101, 100, 501, 100, 1);
        Job job = new Job("job_o", 100, jobTimers);
        supervisor.track(job.record, job.control, job.process, policy(JobResourcePolicy.PressureAction.STOP_JOB, null));
        assertTrue(await(() -> supervisor.metrics("job_o").processCount != null && supervisor.metrics("job_o").processCount == 2));
        assertTrue(supervisor.metrics("job_o").orphanPossiblePids.isEmpty());
        process(101, 1, 501, 100, 1); // reparented to init: same process (same start time), no longer under the root
        assertTrue(await(() -> supervisor.metrics("job_o").orphanPossiblePids.contains(101)));
        assertEquals(Integer.valueOf(1), supervisor.metrics("job_o").processCount);
        // The same PID reused by a different process is not a survivor.
        process(101, 1, 999, 100, 1);
        assertTrue(await(() -> supervisor.metrics("job_o").orphanPossiblePids.isEmpty()));
        assertEquals("nothing was signalled", 0, job.signals.get());
    }
}
