# Process reliability: what is implemented and what is not

## Failure modes this work separates

Exit 137 / `SIGKILL` has several unrelated causes and this code does not label all of them out-of-memory: the Android phantom-process killer, the low-memory killer, an app-level kill, a child that hit its own `RLIMIT_CPU`, and a deliberate stop. Evidence-based classification is described below.

## Scope of protection

| Process | What the app can do |
|---|---|
| Managed job (`job.start`) | admit, observe, limit (in the child), stop through its owned handle |
| Interactive terminal session | nothing new; never limited, never stopped by the supervisor |
| Android and other apps | nothing |

## Implemented in this change

* Bounded legacy output capture (`StreamGobbler`, `AppShell`, `ResultData`).
* `JobResourcePolicy`, native `RLIMIT_AS`/`RLIMIT_CPU`/`nice` in the owned child (`managed-child.c`, JNI ABI marker 3).
* `ResourceAdmissionController`, `PressureTracker`, `JobResourceSupervisor`.
* Opt-in build presets, `resources` on `job.start`, `failureCode: limit_apply_failed`, `RESOURCE_LIMITED`.
* Termination evidence, persistent history with recovery, registry eviction, diagnostics (see below).

See [resource-policy.md](resource-policy.md) for semantics.

## Diagnostics, history and recovery

* `JobTerminationEvidence` and `CrashReasonClassifier` state why a job ended and how sure that is. Causes the app itself performed or saw (cancel, timeout, resource-guard stop, exit code, failed launch, a restart that lost the job) are `confirmed`. A bare `SIGKILL` is `UNKNOWN_SIGKILL`. Sampled evidence can raise it to `POSSIBLE_MEMORY_PRESSURE` (medium only when Android's own low-memory flag was set, otherwise low) or `POSSIBLE_PHANTOM_PROCESS_POLICY` (low; only on Android 12+, with ample free memory and complete observations). A possible cause is never confirmed: the constructor rejects it. Every record lists what was observed, what is inferred and what is unavailable (the app cannot read system logcat, and `ApplicationExitInfo` does not describe a native child).
* `JobHistoryStore` keeps a bounded record per job in `files/resource-jobs/v1/history` (200 records, 4 MiB, oldest finished first; unfinished records are not trimmed). Writes are temp file, fsync, rename; unreadable records are moved aside as `.corrupt-<time>`. On open, every record that was unfinished becomes `LOST` with `SERVICE_LOST` evidence and no exit code: nothing is reattached, no PID is stored and nothing is signalled. A store with a newer layout version disables itself rather than overwrite. No command line, environment, stdin or output is persisted.
* `JobRegistry` no longer exhausts after 32 finished jobs: when full, the oldest finished record whose pipe workers have stopped is evicted with its output. A running or queued record is never evicted. The request key of an evicted record becomes a tombstone (final metadata only) for 10 minutes, at most 256, so a retry still returns the original result and a different request under the same key still conflicts. After that window the key is forgotten and a retry runs as a new request. This is the finite replay policy.
* `ProcessService` task roots are now the running managed-job children (observation only). `process.list` still reports `managedJobId: null`: both SDKs reject anything else, so the association is left for a new method. The supervisor reports processes that left a job's tree but still exist as `orphanPossiblePids`; it never signals them.
* `DeviceCompatibilityAdvisor` derives capabilities from measured inputs. It lists read-only ADB diagnostics and, separately, settings-changing commands for the user to run themselves; Termux never runs them and no universal phantom-process limit is claimed.
* `DiagnosticBundleExporter` builds a bounded, redacted bundle. Output is included only on explicit consent. The UI shares the text report through the Android share sheet.
* The existing jobs dialog shows queue position, live resources, the termination reason and its uncertainty, the persistent history and a stop button that uses the owned handle.

## Protected builds and test runners

[tools/termux-control/README.md](../../tools/termux-control/README.md#walkthrough-a-long-build-as-a-protected-managed-job) walks through moving a long build from an interactive terminal to a `protected` managed job: preview, start, observe resources and output, cancel, and recover history metadata. `resources.testConcurrency` explicitly opts into `--test-threads` for `cargo test` (standard libtest harness only) and `--test-concurrency` for `node --test`; `job.preview` returns a `previewFingerprint` that `job.start` can require so a changed plan is refused instead of launched (see [resource-policy.md](resource-policy.md#test-runners-testconcurrency)). Lowering concurrency is a mitigation. It does not prevent Android from ending processes and it was not measured to improve any particular build.

## Developer experience (PR 3)

Device-aware resource policy, workspace profiles and per-task resources, managed workspace services with restart policies, workspace backup and restore, port observation and loopback proxies, and the `resource.*`, `job.resource/history/diagnostics/processes` and `workspace.service/backup.*` methods with CLI, Python and TypeScript support. See [resource-policy.md](resource-policy.md).

## Not implemented yet

A working isolated execution backend: only the abstraction and capability detection exist, and the VM is reported as unsupported (see [execution-backend.md](execution-backend.md)). The jobs dialog was exercised by hand on one device (see below); it has no automated UI test, and other devices were not tried.

## Verification on Android

Run on a physical POCO X3 Pro (`vayu`, Android 16 / API 36, kernel 4.14, arm64-v8a) over ADB, with the debug build installed:

* `ManagedLimitsInstrumentedTest` (9 tests) passes. It confirms on bionic and the app's seccomp policy that `RLIMIT_AS`, `RLIMIT_CPU` and `nice` are applied to the child only and the app's own limits are unchanged, that a CPU-bound child is ended by its CPU limit (`SIGXCPU`/`SIGKILL`), that a required limit that cannot be applied fails the launch and is attributed to the limit, that `ProcessSnapshot` finds an owned child and its descendants, and, through the production coordinator, AppShell and the owned launcher, that a job with a resource policy reaches `RUNNING`, has exactly the requested limits, is measured through `/proc` by the supervisor, is cancelled, and releases its admission slot.
* `WorkspaceExtrasInstrumentedTest` (5 tests, repeated twice) passes on the production coordinator, AppShell and owned launcher, with `java.nio.file` and loopback sockets on this Android version: a workspace service starts, has a live owned child and stops through its owned handle with its end recorded as evidence; a failing `on-failure` service is restarted with backoff and ends in `crash_loop` after the configured number of restarts, with no further attempts; the supervisor measures real memory growth (a child holding about 40 MB), records `MEMORY_SOFT_EXCEEDED` against a 16 MiB threshold and, being warn-only, never stops the job; a tar.gz and a ZIP backup round-trip with symbolic links, sensitive files and excluded directories handled, and a hostile archive (traversal, an absolute path, a write through a planted symlink) writes only its one harmless file; a loopback proxy relays traffic and a loopback-only listener is not reported as LAN exposed.
* `RpcOnDeviceInstrumentedTest` (5 tests, repeated) drives the JSON-RPC dispatcher in process over the same real runtime: `resource.capabilities` reports from measurement that this device has the native launcher, `RLIMIT_AS`/`RLIMIT_CPU` and `/proc` monitoring and that cgroups and a VM are not available; `resource.snapshot` reports the real RAM and CPU count; a job started with a resource policy is limited, measured, listed by `job.processes`, recorded with `NORMAL_EXIT`/`confirmed` evidence and reported without leaking app paths in the shared diagnostics; an address-space limit the device cannot run is refused as `UNSUPPORTED`; `job.preview` returns a worker count capped by this device's CPUs and starts nothing; and a workspace service starts and stops over RPC.
* The whole `com.termux.app.control` instrumented package passes (27 tests, including the existing managed-native, owned-child and worker-policy tests).
* The full instrumented suite has 15 failures, all in packages this work does not touch (`compat.cli`, `compat.network`, `compat.runtime`, `extrakeys`, `filepicker`): package-private access across the test APK, an ELF-inspection expectation and a hard-link permission on this device. They are not caused by these changes.

**Findings from the device.** (1)  on this Android 16 build the dynamic linker reserves enough address space that any process, even `cat`, aborts at startup with an `RLIMIT_AS` of 2 GiB or less, and starts at 4 GiB. A small limit would therefore only produce a job that dies at once. The minimum is not a constant: `ManagedNative.minAddressSpaceBytes()` measures it once by launching a trivial child under ascending limits, and a job that asks for a lower `addressSpaceBytes` is refused before admission as `UNSUPPORTED` (an unknown minimum never blocks). The probe runs only when a job asks for an address-space limit. Other devices were not tested, which is why it is measured and not hard-coded. (2) `InetAddress.getLoopbackAddress()` is `::1` on this Android build, so a service listening on `127.0.0.1` looked closed and a relay could not reach it. The port service now uses the IPv4 literal `127.0.0.1` everywhere, as its contract says.

**Local control socket and UI, driven by hand over ADB** (with the owner enabling Local control in the app, and the CLI built on the phone from `tools/termux-control`):

* Over the real socket, `resources capabilities`, `jobs start|run|list|resource|processes|history-get` and `jobs cancel` behave as the in-process tests do. A job started without `resources` is unmanaged (`limits: null`, no metrics, as the opt-in rule requires); one started with `--profile balanced` reports `limits.profile = balanced`, live RSS/CPU/process metrics and its root process.
* The Managed jobs dialog lists real jobs, opens a detail view with the resources line, and its Cancel job button asks for confirmation, moves the job through `cancelling` to `cancelled`, and the detail then shows `USER_CANCELLED (confidence confirmed)` plus the evidence that is not available (`ApplicationExitInfo` for native children, logcat). A job that ends by itself shows `NORMAL_EXIT`.
* Replacing the app while two jobs were running made both appear in the history as `lost` / `SERVICE_LOST` after the restart, and the earlier finished jobs kept their `exited` / `NORMAL_EXIT` records. No process id is stored.
* Found and fixed here: the detail dialog showed `profile unknown` for a running job, because the stored record is only loaded for finished jobs. It now takes the profile from the live policy first. A record stored while a job runs still says `STARTING`; it is only an "unfinished" marker used for LOST recovery, and the live state comes from the registry.

Not covered on the device: the thermal and low-memory pressure paths of the supervisor (real memory pressure is deliberately not induced) and the VM backend, which reports `UNSUPPORTED` here. The x86_64 emulator was not usable on the build host (no KVM).

## Device validation of resource profiles and pressure (issue #11)

Run on 2026-11-09 with `scripts/test-control-resources-device.sh` from branch `feature/protected-builds-test-concurrency` (base commit `ab2e7b00` plus the harness changes committed with this table) on a Xiaomi POCO X3 Pro (`M2102J20SG`, `vayu`), Android 16 / API 36, ROM `BP4A.251205.006`, kernel `4.14.356-Helios`, `arm64-v8a`, debug build installed over the existing app with `adb install -r`. Toolchains in the Termux prefix: Node `v24.18.0`, Cargo `1.99.1`, rustc `1.99.1`. Result: `OK (28 tests)`, the 9 cases of `ResourcePressureInstrumentedTest` plus the existing `RpcOnDeviceInstrumentedTest`, `ManagedLimitsInstrumentedTest` and `WorkspaceExtrasInstrumentedTest`.

The new class uses the production launcher, AppShell, real child processes and the real `/proc`. Only the device reading (available/total RAM, the low-memory flag and the thermal status) is **injected** through the supervisor's `Sampler`, a test-only seam: no production endpoint fakes sensors. Nothing exhausted memory or heated the phone.

| Case | Kind | Expected | Observed |
|---|---|---|---|
| `protected` job running, `balanced` job started | instrumentation, real processes | the second waits in `queued` | as expected |
| cancel while queued | instrumentation | `cancelled`, never had a PID or start time | as expected |
| cancel while running | instrumentation | `cancelled`, slot released, next job runs | as expected (next job exited 0) |
| process table unreadable (`/proc` replaced by a missing directory) | instrumentation, injected | metrics `incomplete`, RSS and CPU `null`, never zero | as expected |
| critical memory pressure, then normal | instrumentation, injected | `critical` after two samples, nothing admitted, `normal` after three, admission resumes after the cooldown | as expected |
| `warn` / `stop_job` under critical | instrumentation, injected | only the `stop_job` job ends, as `RESOURCE_GUARD_STOP`; the `warn` job keeps running | as expected |
| `pause_admission` under severe thermal status | instrumentation, injected | `elevated`; the next job waits while the pausing job runs and is admitted once it is cancelled; the pausing job is never stopped | as expected |
| no supervised jobs | instrumentation | no device sample at all, also during an unmanaged job; sampling stops after the last supervised job | as expected |
| preview, then injected pressure, then start with `expectedPreviewFingerprint` | instrumentation, injected | `CONFLICT`, no job added | as expected |
| `node --test` on two files with `testConcurrency: 1` | instrumentation, real Node 24.18.0 | `--test-concurrency=1` right after `--test`, exit 0 | as expected |
| `cargo test --offline` on a two-test crate with `testConcurrency: 1` | instrumentation, real Cargo 1.99.1 | `test -j 1 --offline -- --test-threads=1`, exit 0 | as expected |

Notes from the run: `run-as com.termux` is refused by SELinux on this ROM, so the harness installs over the app and reads the tool versions from a job inside the test instead; `connectedDebugAndroidTest` is avoided because it uninstalls the app (and the Termux home) when it finishes.

Hysteresis, queue ordering, the admission rules, missing data and the sampler's stop are also covered by `PressureTrackerTest`, `ResourceAdmissionControllerTest` and `JobResourceSupervisorTest` on the host; the device class only adds what needs a real launcher and `/proc`. **Not covered:** real thermal or low-memory sensor transitions (deliberately not provoked), the sampling cost on the device (not measured), interaction with busy interactive sessions, the CLI walkthrough over the real socket (the client cannot be installed through `run-as` on this ROM), the effect of the test limits on memory or build time (not measured; no improvement is claimed), and other devices, ROMs and ABIs.

## Test environment

The unit suite passes in full (0 failures) on JDK 21. On JDK 27 the 35 Robolectric tests fail to start (`ClassReader`), identically with and without these changes, because Robolectric 4.10 cannot instrument classes with that JDK. Run Gradle with `-Dorg.gradle.java.home=<JDK 21>` to run them.

## Known limits

* Descendants that call `setsid` or change process group are outside the group signal and may survive a stop. The supervisor's RSS sum also loses a descendant once its parent exits and it is reparented.
* Android may hide other processes from `/proc`; the snapshot reports `incomplete` and metrics stay `null` rather than zero.
* The sampler is a poll; the low-memory killer can act between samples.
* Native limits rely on `setrlimit`/`setpriority`. They were applied and verified on one device (see above); other devices and ROMs were not tested, and the address-space minimum in particular varies.
