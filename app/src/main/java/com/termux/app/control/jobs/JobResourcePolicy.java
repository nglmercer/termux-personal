package com.termux.app.control.jobs;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable, validated resource configuration for one managed job. It describes what the job asks for;
 * {@link #resolve} turns it into what this device can actually enforce.
 *
 * <p>Semantics, so callers never overstate a guarantee:
 * <ul>
 * <li>{@code memorySoftBytes} is an <em>observed</em> threshold. The supervisor compares sampled RSS against it
 * and acts per {@link PressureAction}; nothing stops the process from exceeding it between samples.</li>
 * <li>{@code addressSpaceBytes} is {@code RLIMIT_AS}: virtual address space, not resident memory. It can break the
 * JVM, V8 and other runtimes that reserve large ranges, so it is never defaulted by a profile.</li>
 * <li>{@code cpuTimeSeconds} is {@code RLIMIT_CPU}: CPU seconds, not wall time and not a percentage. The job's
 * wall-clock timeout stays the separate {@code timeoutMillis} of the job specification.</li>
 * <li>{@code niceLevel} only lowers priority (0..19). It is best effort unless {@code requireNice} is set.</li>
 * <li>An explicit address-space or CPU limit is a hard limit and is therefore <em>required</em>: if it cannot be
 * applied, the job does not start. It is never silently dropped.</li>
 * <li>{@code processConcurrencyHint} is advice used by build presets and admission, not an enforced cap:
 * {@code RLIMIT_NPROC} is per UID and would limit every other Termux process.</li>
 * </ul>
 * A job without a policy keeps exactly the historical behavior.
 */
public final class JobResourcePolicy {
    public enum Profile {
        /** Historical behavior: no priority change, no supervision action. */
        UNRESTRICTED,
        /** Stability first: lowest priority, one heavy job. */
        PROTECTED,
        /** Default for development builds: moderate priority and concurrency. */
        BALANCED,
        /** Throughput first, still without bypassing Android's limits. */
        PERFORMANCE,
        /** Only what the caller states; profile defaults add nothing. */
        CUSTOM;

        public String wireName() { return name().toLowerCase(Locale.ROOT); }
        public static Profile parse(String value) {
            for (Profile profile : values()) if (profile.wireName().equals(value)) return profile;
            throw new IllegalArgumentException("Unknown resource profile");
        }
    }

    public enum PressureAction {
        /** Record and report only. */
        WARN,
        /** Stop admitting new heavy jobs while pressure lasts. */
        PAUSE_ADMISSION,
        /** Stop this job through its owned handle. Opt-in: never a default. */
        STOP_JOB;

        public String wireName() { return name().toLowerCase(Locale.ROOT); }
        public static PressureAction parse(String value) {
            for (PressureAction action : values()) if (action.wireName().equals(value)) return action;
            throw new IllegalArgumentException("Unknown pressure action");
        }
    }

    public static final long MIN_MEMORY_SOFT_BYTES = 16L << 20;
    public static final long MAX_BYTES = 1L << 40;
    public static final long MIN_ADDRESS_SPACE_BYTES = 64L << 20;
    public static final long MAX_CPU_TIME_SECONDS = 30L * 24 * 3600;
    public static final int MIN_OUTPUT_BYTES = 1024;
    /** Per stream; bounded by the replay buffer that retains it. */
    public static final int MAX_OUTPUT_BYTES = JobOutputStore.DEFAULT_STREAM_BYTES;
    public static final int MAX_CONCURRENCY_HINT = 16;
    public static final int MAX_NICE = 19;

    private final Profile mProfile;
    private final long mMemorySoftBytes, mAddressSpaceBytes, mCpuTimeSeconds;
    private final int mMaxOutputBytes, mConcurrencyHint, mNiceLevel;
    private final PressureAction mOnPressure;
    private final boolean mRequireNice;

    private JobResourcePolicy(Builder b) {
        mProfile = b.profile; mMemorySoftBytes = b.memorySoftBytes; mAddressSpaceBytes = b.addressSpaceBytes;
        mCpuTimeSeconds = b.cpuTimeSeconds; mMaxOutputBytes = b.maxOutputBytes; mConcurrencyHint = b.concurrencyHint;
        mNiceLevel = b.niceLevel; mOnPressure = b.onPressure; mRequireNice = b.requireNice;
    }

    public static Builder builder() { return new Builder(); }
    public static JobResourcePolicy ofProfile(Profile profile) { return builder().profile(profile).build(); }

    public Profile profile() { return mProfile; }
    /** Zero means no soft threshold. */
    public long memorySoftBytes() { return mMemorySoftBytes; }
    /** Zero means no address-space limit. */
    public long addressSpaceBytes() { return mAddressSpaceBytes; }
    /** Zero means no CPU-time limit. */
    public long cpuTimeSeconds() { return mCpuTimeSeconds; }
    /** Per-stream retention requested, or zero for the store default. */
    public int maxOutputBytes() { return mMaxOutputBytes; }
    /** Zero means no hint. */
    public int processConcurrencyHint() { return mConcurrencyHint; }
    /** Negative means unchanged. */
    public int niceLevel() { return mNiceLevel; }
    public PressureAction onPressure() { return mOnPressure; }
    public boolean requireNice() { return mRequireNice; }

    /** Per-stream output capacity to reserve for this job. */
    public int outputStreamBytes() { return mMaxOutputBytes == 0 ? JobOutputStore.DEFAULT_STREAM_BYTES : mMaxOutputBytes; }

    /** What the running device can enforce; supplied by the owner from real detection, never assumed. */
    public static final class Capabilities {
        public final boolean nativeLimits;
        public Capabilities(boolean nativeLimits) { this.nativeLimits = nativeLimits; }
    }

    /** The limits that will really be requested of the child, and what could not be provided. */
    public static final class Effective {
        public final JobResourcePolicy policy;
        final ManagedNative.NativeLimits nativeLimits;
        /** Settings that were requested but cannot be enforced here, as stable identifiers. */
        public final List<String> unsupported;
        /** True when a required limit cannot be enforced, so the job must not start. */
        public final boolean blocked;
        private Effective(JobResourcePolicy policy, ManagedNative.NativeLimits limits, List<String> unsupported, boolean blocked) {
            this.policy = policy; this.nativeLimits = limits;
            this.unsupported = Collections.unmodifiableList(unsupported); this.blocked = blocked;
        }
    }

    /**
     * Applies profile defaults for fields the caller left unset, then checks each request against what the
     * device can enforce. Hard limits are never defaulted by a profile.
     */
    public Effective resolve(Capabilities capabilities) {
        if (capabilities == null) throw new IllegalArgumentException("Missing capabilities");
        int nice = mNiceLevel >= 0 ? mNiceLevel : profileNice(mProfile);
        List<String> unsupported = new ArrayList<>();
        boolean blocked = false;
        int required = 0;
        if (mAddressSpaceBytes != 0) required |= ManagedNative.NativeLimits.REQUIRE_ADDRESS_SPACE;
        if (mCpuTimeSeconds != 0) required |= ManagedNative.NativeLimits.REQUIRE_CPU_TIME;
        if (nice >= 0 && mRequireNice) required |= ManagedNative.NativeLimits.REQUIRE_NICE;
        if (!capabilities.nativeLimits) {
            if (mAddressSpaceBytes != 0) { unsupported.add("addressSpaceBytes"); blocked = true; }
            if (mCpuTimeSeconds != 0) { unsupported.add("cpuTimeSeconds"); blocked = true; }
            if (nice >= 0) { unsupported.add("niceLevel"); if (mRequireNice) blocked = true; }
        }
        ManagedNative.NativeLimits limits = capabilities.nativeLimits ?
            new ManagedNative.NativeLimits(mAddressSpaceBytes, mCpuTimeSeconds, nice, required) : ManagedNative.NativeLimits.NONE;
        return new Effective(this, limits, unsupported, blocked);
    }

    /** Priority a profile applies when none is given; negative leaves the priority unchanged. */
    static int profileNice(Profile profile) {
        switch (profile) {
            case PROTECTED: return 10;
            case BALANCED: return 5;
            default: return -1;
        }
    }

    /** Concurrency a profile suggests when none is given; zero means no hint. */
    public int effectiveConcurrencyHint() {
        if (mConcurrencyHint != 0) return mConcurrencyHint;
        switch (mProfile) {
            case PROTECTED: return 1;
            case BALANCED: return 2;
            case PERFORMANCE: return 4;
            default: return 0;
        }
    }

    /** Canonical bytes for the job idempotency fingerprint: the same request always hashes the same. */
    void writeFingerprint(DataOutputStream out) throws IOException {
        out.writeUTF("resources.v1");
        out.writeUTF(mProfile.wireName());
        out.writeLong(mMemorySoftBytes); out.writeLong(mAddressSpaceBytes); out.writeLong(mCpuTimeSeconds);
        out.writeInt(mMaxOutputBytes); out.writeInt(mConcurrencyHint); out.writeInt(mNiceLevel);
        out.writeUTF(mOnPressure.wireName()); out.writeBoolean(mRequireNice);
    }

    /** Only what differs from "unset", so a stored or returned policy stays small and round-trips exactly. */
    public com.google.gson.JsonObject toJson() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("profile", mProfile.wireName());
        if (mMemorySoftBytes != 0) o.addProperty("memorySoftBytes", mMemorySoftBytes);
        if (mAddressSpaceBytes != 0) o.addProperty("addressSpaceBytes", mAddressSpaceBytes);
        if (mCpuTimeSeconds != 0) o.addProperty("cpuTimeSeconds", mCpuTimeSeconds);
        if (mMaxOutputBytes != 0) o.addProperty("maxOutputBytes", mMaxOutputBytes);
        if (mConcurrencyHint != 0) o.addProperty("processConcurrencyHint", mConcurrencyHint);
        if (mNiceLevel >= 0) o.addProperty("niceLevel", mNiceLevel);
        o.addProperty("onPressure", mOnPressure.wireName());
        if (mRequireNice) o.addProperty("requireNice", true);
        return o;
    }

    /** Strict reader with the same validation as the builder; throws IllegalArgumentException on anything invalid. */
    public static JobResourcePolicy fromJson(com.google.gson.JsonObject o) {
        if (o == null) throw new IllegalArgumentException("Missing resources");
        Builder b = builder();
        try {
            if (has(o, "profile")) b.profile(Profile.parse(o.get("profile").getAsString()));
            if (has(o, "onPressure")) b.onPressure(PressureAction.parse(o.get("onPressure").getAsString()));
            b.memorySoftBytes(optLong(o, "memorySoftBytes")).addressSpaceBytes(optLong(o, "addressSpaceBytes"))
                .cpuTimeSeconds(optLong(o, "cpuTimeSeconds")).maxOutputBytes(optLong(o, "maxOutputBytes"))
                .processConcurrencyHint(optLong(o, "processConcurrencyHint")).niceLevel(optLong(o, "niceLevel"));
            if (has(o, "requireNice")) b.requireNice(o.get("requireNice").getAsBoolean());
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            throw new IllegalArgumentException("Invalid resources", e);
        }
        return b.build();
    }

    private static boolean has(com.google.gson.JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull(); }
    private static Long optLong(com.google.gson.JsonObject o, String key) { return has(o, key) ? Long.valueOf(o.get(key).getAsLong()) : null; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof JobResourcePolicy)) return false;
        JobResourcePolicy o = (JobResourcePolicy) other;
        return mProfile == o.mProfile && mMemorySoftBytes == o.mMemorySoftBytes && mAddressSpaceBytes == o.mAddressSpaceBytes &&
            mCpuTimeSeconds == o.mCpuTimeSeconds && mMaxOutputBytes == o.mMaxOutputBytes && mConcurrencyHint == o.mConcurrencyHint &&
            mNiceLevel == o.mNiceLevel && mOnPressure == o.mOnPressure && mRequireNice == o.mRequireNice;
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(mProfile, mMemorySoftBytes, mAddressSpaceBytes, mCpuTimeSeconds, mMaxOutputBytes,
            mConcurrencyHint, mNiceLevel, mOnPressure, mRequireNice);
    }

    /** Unset fields stay at "none"; {@link #build()} rejects every invalid value instead of clamping it. */
    public static final class Builder {
        private Profile profile = Profile.BALANCED;
        private long memorySoftBytes, addressSpaceBytes, cpuTimeSeconds;
        private int maxOutputBytes, concurrencyHint, niceLevel = -1;
        private PressureAction onPressure = PressureAction.WARN;
        private boolean requireNice;

        public Builder profile(Profile value) { profile = value; return this; }
        public Builder memorySoftBytes(Long value) { memorySoftBytes = value == null ? 0 : positive(value, "memorySoftBytes"); return this; }
        public Builder addressSpaceBytes(Long value) { addressSpaceBytes = value == null ? 0 : positive(value, "addressSpaceBytes"); return this; }
        public Builder cpuTimeSeconds(Long value) { cpuTimeSeconds = value == null ? 0 : positive(value, "cpuTimeSeconds"); return this; }
        public Builder maxOutputBytes(Long value) {
            maxOutputBytes = value == null ? 0 : (int) Math.min(Integer.MAX_VALUE, positive(value, "maxOutputBytes")); return this;
        }
        public Builder processConcurrencyHint(Long value) {
            concurrencyHint = value == null ? 0 : (int) Math.min(Integer.MAX_VALUE, positive(value, "processConcurrencyHint")); return this;
        }
        public Builder niceLevel(Long value) {
            if (value == null) { niceLevel = -1; return this; }
            if (value < 0 || value > MAX_NICE) throw new IllegalArgumentException("niceLevel must be 0.." + MAX_NICE);
            niceLevel = value.intValue(); return this;
        }
        public Builder onPressure(PressureAction value) { onPressure = value; return this; }
        public Builder requireNice(boolean value) { requireNice = value; return this; }

        /** Rejects zero and negative values explicitly: "unlimited" is expressed by omitting the field. */
        private static long positive(long value, String name) {
            if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        public JobResourcePolicy build() {
            if (profile == null || onPressure == null) throw new IllegalArgumentException("Invalid resource policy");
            if (memorySoftBytes != 0 && (memorySoftBytes < MIN_MEMORY_SOFT_BYTES || memorySoftBytes > MAX_BYTES))
                throw new IllegalArgumentException("memorySoftBytes out of range");
            if (addressSpaceBytes != 0 && (addressSpaceBytes < MIN_ADDRESS_SPACE_BYTES || addressSpaceBytes > MAX_BYTES))
                throw new IllegalArgumentException("addressSpaceBytes out of range");
            if (cpuTimeSeconds > MAX_CPU_TIME_SECONDS) throw new IllegalArgumentException("cpuTimeSeconds out of range");
            if (maxOutputBytes != 0 && (maxOutputBytes < MIN_OUTPUT_BYTES || maxOutputBytes > MAX_OUTPUT_BYTES))
                throw new IllegalArgumentException("maxOutputBytes must be " + MIN_OUTPUT_BYTES + ".." + MAX_OUTPUT_BYTES + " per stream");
            if (concurrencyHint > MAX_CONCURRENCY_HINT) throw new IllegalArgumentException("processConcurrencyHint out of range");
            // Combinations that cannot mean what the caller intends are rejected rather than reinterpreted.
            if (onPressure != PressureAction.WARN && memorySoftBytes == 0 && profile == Profile.UNRESTRICTED)
                throw new IllegalArgumentException("A pressure action needs a supervised profile or a memory threshold");
            if (requireNice && niceLevel < 0 && profileNice(profile) < 0)
                throw new IllegalArgumentException("requireNice needs a niceLevel or a profile that sets one");
            if (addressSpaceBytes != 0 && memorySoftBytes != 0 && addressSpaceBytes < memorySoftBytes)
                throw new IllegalArgumentException("addressSpaceBytes below memorySoftBytes would stop the job before the threshold");
            return new JobResourcePolicy(this);
        }
    }
}
