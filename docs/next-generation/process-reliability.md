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

## Developer experience (PR 3)

Device-aware resource policy, workspace profiles and per-task resources, managed workspace services with restart policies, workspace backup and restore, port observation and loopback proxies, and the `resource.*`, `job.resource/history/diagnostics/processes` and `workspace.service/backup.*` methods with CLI, Python and TypeScript support. See [resource-policy.md](resource-policy.md).

## Not implemented yet

A working isolated execution backend: only the abstraction and capability detection exist, and the VM is reported as unsupported (see [execution-backend.md](execution-backend.md)). The UI and everything native have not been run on a physical Android device.

## Test environment

The unit suite passes in full (0 failures) on JDK 21. On JDK 27 the 35 Robolectric tests fail to start (`ClassReader`), identically with and without these changes, because Robolectric 4.10 cannot instrument classes with that JDK. Run Gradle with `-Dorg.gradle.java.home=<JDK 21>` to run them.

## Known limits

* Descendants that call `setsid` or change process group are outside the group signal and may survive a stop. The supervisor's RSS sum also loses a descendant once its parent exits and it is reparented.
* Android may hide other processes from `/proc`; the snapshot reports `incomplete` and metrics stay `null` rather than zero.
* The sampler is a poll; the low-memory killer can act between samples.
* Native limits rely on `setrlimit`/`setpriority`, which no seccomp policy is known to block, but this has not been run on a device.
