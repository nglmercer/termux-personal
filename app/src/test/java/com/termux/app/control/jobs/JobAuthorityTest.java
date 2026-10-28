package com.termux.app.control.jobs;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.EnumSet;

public class JobAuthorityTest {
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
