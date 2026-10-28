package com.termux.app.control.jobs;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Service-owned consent epochs. Never construct grants from RPC parameters. */
public final class JobAuthority {
    public enum Permission { READ, OUTPUT, EXECUTE, CANCEL }
    private long mEpoch;
    private Set<Permission> mPermissions = Collections.emptySet();
    private boolean mEnabled;

    /** Trusted local consent replaces all permissions and invalidates every older lease. */
    public synchronized Lease grant(Set<Permission> permissions) {
        if (permissions == null) throw new IllegalArgumentException("Missing consent");
        advance();
        mPermissions = Collections.unmodifiableSet(permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions));
        mEnabled = true;
        return new Lease(this, mEpoch);
    }

    /** Revocation is immediate for future lease checks and does not imply a running child stopped. */
    public synchronized void revoke() {
        advance(); mEnabled = false; mPermissions = Collections.emptySet();
    }

    private void advance() {
        if (mEpoch == Long.MAX_VALUE) throw new IllegalStateException("Authorization epoch exhausted");
        mEpoch++;
    }

    synchronized boolean allows(Lease lease, Permission permission) {
        return lease != null && lease.mAuthority == this && permission != null && mEnabled &&
            lease.mEpoch == mEpoch && mPermissions.contains(permission);
    }

    /** Opaque, immutable proof of a specific trusted local consent epoch. */
    public static final class Lease {
        private final JobAuthority mAuthority;
        private final long mEpoch;
        private Lease(JobAuthority authority, long epoch) { mAuthority = authority; mEpoch = epoch; }
        long epoch() { return mEpoch; }
    }
}
