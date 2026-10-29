package com.termux.app.control.jobs;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.EnumSet;
import com.termux.app.control.ControlCore;

public class JobAuthorityTest {
    @Test public void trustedScopesMapIndependentlyAndDoNotImplyExecution() {
        JobAuthority authority = new JobAuthority();
        JobAuthority.Lease lease = authority.grantScopes(EnumSet.of(ControlCore.Scope.JOBS_READ,
            ControlCore.Scope.JOBS_OUTPUT, ControlCore.Scope.FILES_WRITE));
        assertTrue(authority.allows(lease, JobAuthority.Permission.READ));
        assertTrue(authority.allows(lease, JobAuthority.Permission.OUTPUT));
        assertFalse(authority.allows(lease, JobAuthority.Permission.EXECUTE));
        assertFalse(authority.allows(lease, JobAuthority.Permission.CANCEL));
    }
    @Test public void permissionsAreCopiedAndOldLeasesAreInvalidated() {
        JobAuthority authority = new JobAuthority();
        EnumSet<JobAuthority.Permission> permissions = EnumSet.of(JobAuthority.Permission.READ);
        JobAuthority.Lease first = authority.grant(permissions);
        permissions.add(JobAuthority.Permission.EXECUTE);
        assertTrue(authority.allows(first, JobAuthority.Permission.READ));
        assertFalse(authority.allows(first, JobAuthority.Permission.EXECUTE));
        JobAuthority.Lease second = authority.grant(EnumSet.of(JobAuthority.Permission.EXECUTE));
        assertFalse(authority.allows(first, JobAuthority.Permission.READ));
        assertTrue(authority.allows(second, JobAuthority.Permission.EXECUTE));
        authority.revoke();
        assertFalse(authority.allows(second, JobAuthority.Permission.EXECUTE));
    }

    @Test public void foreignLeaseWithSameEpochCannotAuthorize() {
        JobAuthority first = new JobAuthority(), second = new JobAuthority();
        JobAuthority.Lease local = first.grant(EnumSet.of(JobAuthority.Permission.EXECUTE));
        JobAuthority.Lease foreign = second.grant(EnumSet.of(JobAuthority.Permission.EXECUTE));
        assertEquals(local.epoch(), foreign.epoch());
        assertFalse(first.allows(foreign, JobAuthority.Permission.EXECUTE));
        assertFalse(first.allows(null, JobAuthority.Permission.EXECUTE));
    }
}
