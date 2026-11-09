package com.termux.app.control.execution;

import static org.junit.Assert.*;

import com.termux.app.control.execution.VirtualizationCapabilityDetector.Facts;
import com.termux.app.control.execution.VirtualizationCapabilityDetector.Report;
import com.termux.app.control.execution.VirtualizationCapabilityDetector.Status;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ExecutionBackendTest {
    private static final long GIB = 1L << 30;

    /** A device with everything a guest could need, short of the runtime packaging this build does not have. */
    private static Facts capableDevice() {
        Facts f = new Facts();
        f.sdkInt = 35; f.abis = Arrays.asList("arm64-v8a"); f.avfSystemFeature = true; f.holdsManageVirtualMachine = false;
        f.kvmAccessible = false; f.availableStorageBytes = 20 * GIB; f.availableMemoryBytes = 4 * GIB;
        f.runtimePackaged = false; f.runtimeIntegrityVerified = false; f.licenseReviewed = false;
        return f;
    }

    private static Status status(Report report, String id) {
        for (VirtualizationCapabilityDetector.Requirement r : report.requirements) if (r.id.equals(id)) return r.status;
        throw new AssertionError(id);
    }

    @Test public void aRecentAndroidVersionAloneNeverImpliesSupport() {
        Facts f = capableDevice();
        Report report = VirtualizationCapabilityDetector.detect(f);
        assertFalse(report.supported);
        assertEquals(Status.MET, status(report, "android_version"));
        assertEquals(Status.NOT_MET, status(report, "avf_permission"));
        assertEquals(Status.NOT_MET, status(report, "runtime_packaged"));
        assertFalse(report.blockers.isEmpty());
        assertTrue(report.blockers.toString().contains("MANAGE_VIRTUAL_MACHINE"));
    }

    @Test public void unknownFactsCountAgainstSupportAndAreNamedAsUnknown() {
        Report report = VirtualizationCapabilityDetector.detect(new Facts());
        assertFalse(report.supported);
        for (VirtualizationCapabilityDetector.Requirement r : report.requirements) assertEquals(r.id, Status.UNKNOWN, r.status);
        assertTrue(report.blockers.toString().contains("(unknown)"));
    }

    @Test public void thresholdsAreEnforcedForStorageMemoryArchitectureAndVersion() {
        Facts f = capableDevice(); f.availableStorageBytes = 1 * GIB;
        assertEquals(Status.NOT_MET, status(VirtualizationCapabilityDetector.detect(f), "storage"));
        f = capableDevice(); f.availableMemoryBytes = 1 * GIB;
        assertEquals(Status.NOT_MET, status(VirtualizationCapabilityDetector.detect(f), "memory"));
        f = capableDevice(); f.abis = Arrays.asList("armeabi-v7a");
        assertEquals(Status.NOT_MET, status(VirtualizationCapabilityDetector.detect(f), "architecture"));
        f = capableDevice(); f.sdkInt = 33;
        assertEquals(Status.NOT_MET, status(VirtualizationCapabilityDetector.detect(f), "android_version"));
    }

    @Test public void supportRequiresAWholeRouteNotJustSomeRequirements() {
        // Platform route: version, feature and permission, plus the shared prerequisites.
        Facts platform = capableDevice(); platform.holdsManageVirtualMachine = true;
        assertTrue(VirtualizationCapabilityDetector.detect(platform).supported);
        Facts noFeature = platform; noFeature.avfSystemFeature = false;
        assertFalse(VirtualizationCapabilityDetector.detect(noFeature).supported);
        // Bundled route: needs packaging, verified integrity and a completed license review together.
        Facts bundled = capableDevice();
        bundled.runtimePackaged = true; bundled.runtimeIntegrityVerified = true;
        assertFalse("without a license review a bundled runtime is not usable", VirtualizationCapabilityDetector.detect(bundled).supported);
        bundled.licenseReviewed = true;
        assertTrue(VirtualizationCapabilityDetector.detect(bundled).supported);
        bundled.availableStorageBytes = 100L;
        assertFalse("the shared prerequisites still apply", VirtualizationCapabilityDetector.detect(bundled).supported);
    }

    @Test public void hardwareAccelerationIsReportedButNeverBlocksOnItsOwn() {
        Facts f = capableDevice(); f.holdsManageVirtualMachine = true; f.kvmAccessible = false;
        Report report = VirtualizationCapabilityDetector.detect(f);
        assertEquals(Status.NOT_MET, status(report, "hardware_acceleration"));
        assertTrue(report.supported);
    }

    @Test public void nativeIsTheDefaultAndNeedsNoPreparation() {
        ExecutionBackends backends = new ExecutionBackends(VirtualizationCapabilityDetector.detect(capableDevice()));
        assertEquals("native", backends.defaultBackend().id());
        assertTrue(backends.defaultBackend().capabilities().available);
        assertTrue(backends.defaultBackend().prepare().ok);
        assertEquals(ExecutionBackend.Status.RUNNING, backends.defaultBackend().status());
        assertFalse("native does not become a second launcher", backends.defaultBackend().start().ok);
        assertNull("an unknown id is not silently replaced by the default", backends.byId("qemu"));
        assertNull(backends.byId(null));
        assertTrue(backends.usable("native"));
        assertFalse(backends.usable("isolated-vm"));
        assertFalse(backends.usable("qemu"));
        assertEquals(2, backends.all().size());
    }

    @Test public void theIsolatedBackendRefusesEverythingWithTheDetectedReasonAndStartsNothing() {
        Report report = VirtualizationCapabilityDetector.detect(capableDevice());
        ExecutionBackend vm = new ExecutionBackends(report).byId("isolated-vm");
        assertFalse(vm.capabilities().available);
        assertEquals(report.blockers, vm.capabilities().blockers);
        for (ExecutionBackend.Result r : new ExecutionBackend.Result[] {vm.prepare(), vm.start(), vm.terminal(), vm.resources(2L << 30, 2), vm.ports(Collections.singletonList(8080))}) {
            assertFalse(r.ok);
            assertEquals(ExecutionBackend.Status.UNSUPPORTED, r.status);
            assertFalse("a reason is always given", r.reason.isEmpty());
        }
        assertEquals(ExecutionBackend.Status.UNSUPPORTED, vm.status());
        assertEquals(0, vm.logs(1024).length);
        assertTrue("stopping and cleaning up nothing is always safe", vm.stop().ok && vm.cleanup().ok);
    }

    @Test public void thePlaceholderStaysUnsupportedEvenWhenTheDetectorSaysSupported() {
        // The detector can say yes only once a runtime is packaged and reviewed; until an implementation exists the backend still refuses.
        Facts f = capableDevice(); f.holdsManageVirtualMachine = true;
        Report yes = VirtualizationCapabilityDetector.detect(f);
        assertTrue(yes.supported);
        ExecutionBackend vm = new IsolatedVmBackend(yes);
        assertFalse(vm.prepare().ok);
        assertFalse(vm.capabilities().available);
        assertEquals("no isolated runtime is implemented in this build", vm.prepare().reason);
    }
}
