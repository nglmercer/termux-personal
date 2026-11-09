# Process reliability: what is implemented and what is not

## Failure modes this work separates

Exit 137 / `SIGKILL` has several unrelated causes and this code does not label all of them out-of-memory: the Android phantom-process killer, the low-memory killer, an app-level kill, a child that hit its own `RLIMIT_CPU`, and a deliberate stop. Evidence-based classification (`JobTerminationEvidence`, `CrashReasonClassifier`) is **not part of this change**; until it exists a signalled job only reports the signal it died from.

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

See [resource-policy.md](resource-policy.md) for semantics.

## Not implemented yet

Persistent job history and `LOST` recovery after restart; the 32-completed-jobs registry exhaustion (a finished job still holds its record and output slot for the service lifetime); termination evidence; trusted task roots for `ProcessService`; the process manager UI; diagnostic export; workspace profiles and services; backup; port tools; CLI/SDK helpers for `resources`; the optional VM backend.

## Known limits

* Descendants that call `setsid` or change process group are outside the group signal and may survive a stop. The supervisor's RSS sum also loses a descendant once its parent exits and it is reparented.
* Android may hide other processes from `/proc`; the snapshot reports `incomplete` and metrics stay `null` rather than zero.
* The sampler is a poll; the low-memory killer can act between samples.
* Native limits rely on `setrlimit`/`setpriority`, which no seccomp policy is known to block, but this has not been run on a device.
