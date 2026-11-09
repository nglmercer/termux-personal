# Managed job resource policy

An optional `resources` object on `job.start` asks for resource handling for one managed job. A request without it behaves exactly as it always did, including its idempotency fingerprint. Nothing here applies to interactive terminal sessions, `RUN_COMMAND` plugin calls or other processes of the app.

```json
{"resources": {"profile": "balanced", "memorySoftBytes": 1073741824, "cpuTimeSeconds": null,
               "addressSpaceBytes": null, "maxOutputBytes": 131072, "processConcurrencyHint": 2,
               "niceLevel": 5, "onPressure": "warn", "requireNice": false, "applyBuildPreset": true}}
```

Every member is optional and `null` means unset. Out-of-range or inconsistent values are rejected with `INVALID_REQUEST`; they are never clamped. Wrong JSON types or unknown members are invalid params.

## What each field really does

| Field | Enforced by | Guarantee |
|---|---|---|
| `memorySoftBytes` | supervisor sampling | **Observed only.** RSS of the job's process tree is compared to it at each sample. Nothing stops a process from exceeding it between samples. |
| `addressSpaceBytes` | `RLIMIT_AS` in the child before `execve` | Virtual address space, **not** resident memory. Breaks runtimes that reserve large ranges (JVM, V8, some Rust allocators), so no profile ever sets it. |
| `cpuTimeSeconds` | `RLIMIT_CPU` in the child | CPU seconds, not wall time and not a percentage. `SIGXCPU` at the limit, `SIGKILL` five seconds later. The wall-clock `timeoutMs` stays separate. |
| `niceLevel` | `setpriority` in the child | Lowers priority only (0–19). Best effort unless `requireNice`. |
| `maxOutputBytes` | `JobOutputStore` | Retention per stream, 1 KiB – 256 KiB (the replay buffer's capacity). Larger requests are rejected. |
| `processConcurrencyHint` | build presets, admission advice | A recommendation, not a cap. `RLIMIT_NPROC` is per UID and would limit every other Termux process, so it is not used. |
| `onPressure` | supervisor | `warn` (default), `pause_admission`, `stop_job` (opt-in, see below). |

An explicit `addressSpaceBytes` or `cpuTimeSeconds` is a hard limit and is **required**: if the device cannot apply it, the job does not start (`UNSUPPORTED` when native limits are unavailable; a job that fails because the child could not apply it ends `FAILED` with `failureCode: "limit_apply_failed"`). The child reports this explicitly, so an `exec` failure is never mistaken for a limit failure.

`RLIMIT_RSS` is not used (Linux does not enforce it). The app process itself is never limited: limits are applied only in the forked child. An existing tighter hard limit is kept and counts as applied.

## Profiles

Profiles supply defaults only for fields left unset. They never set a hard limit.

| Profile | Priority | Concurrency hint | Admission |
|---|---|---|---|
| `unrestricted` | unchanged | none | bypasses admission and supervision |
| `protected` | nice 10 | 1 | runs alone |
| `balanced` (default when `resources` is present) | nice 5 | 2 | shared slots |
| `performance` | unchanged | 4 | shared slots |
| `custom` | only what you set | only what you set | shared slots |

## Admission

Jobs with a profile other than `unrestricted` go through `ResourceAdmissionController`, a bounded FIFO queue (8) in front of the existing launch workers. It decides *when* a job may start; the one launcher is unchanged.

* **Critical pressure** admits nothing, and nothing for a 15 s cooldown after pressure drops.
* **Elevated pressure** halves the slot count (never below one) and `pause_admission` on a running job blocks admission.
* **Slots** come from total RAM (<3 GiB: 1, <6: 2, <10: 3, else 4), capped by the four I/O workers, and are 1 until the first device reading arrives. These are starting heuristics.
* **Headroom:** a job started in the last 10 s has not ramped up, so its estimated memory (`memorySoftBytes` or 768 MiB) is held back from what the next job sees.
* A job cancelled while queued is never launched. The reservation is released exactly once on exit, failed launch or cancel.
* A full queue answers `RESOURCE_LIMITED` (a reserved v1 domain code). There is no new error name for busy/pressure/quota; those map to `RESOURCE_LIMITED`, and unsupported hard limits to `UNSUPPORTED`.

Pressure is derived by `PressureTracker` from available RAM (enter below max(512 MiB, 12 %) elevated and max(256 MiB, 6 %) critical, exit 25 % higher), Android's low-memory flag (always critical) and thermal status (severe → elevated, critical → critical). Escalation needs 2 consecutive samples, recovery 3.

## Supervision

`JobResourceSupervisor` samples only while a policy job is tracked or jobs wait for admission (every 2 s, 1 s under pressure), on one background thread, with no overlapping scans. Idle means nothing is scheduled. Unmeasurable values are `null`, never zero. A process is attributed to a job by PID **and** start time, so a recycled PID is detected and ignored.

`stop_job` is the only action that stops a job. It stops only a job that set it, only through that job's owned handle (TERM, then KILL after the grace), only after two consecutive critical samples (or over `memorySoftBytes` while pressure is elevated), at most one job per 30 s, largest first. It never signals an observed PID and never touches interactive terminals. A userspace supervisor cannot promise to react before Android's low-memory killer.

## Build presets

`"applyBuildPreset": true` adds a worker count recommended by the profile to a **known** tool and nothing else:

| Tool | Added | Skipped when |
|---|---|---|
| `cargo build/check/test/run/clippy/doc/bench/install` | `-j N` after the subcommand | `-j`/`--jobs` present or `CARGO_BUILD_JOBS` set |
| `gradle`, `gradlew` | `--max-workers=N` | `--max-workers` present |
| `ninja` | `-j N` first | `-j` present |
| `make`, `gmake` | `-jN` first | `-j`/`--jobs` present or `MAKEFLAGS` set |

Executables are matched by exact basename; anything else is never rewritten, flags after `--` are ignored, and the user's arguments are only ever added to. `N` is capped at the CPU count. Node's `--max-old-space-size` is implemented in `BuildPresets` but not yet reachable from `job.start`. A preview API for the effective command is not implemented yet.

## Legacy `RUN_COMMAND` output

`StreamGobbler` now reads stdout/stderr as bytes and retains at most 4 MiB per stream (`ResultData.stdoutDroppedBytes`/`stderrDroppedBytes` count the rest; the plugin result's original-length extras include them). A single huge line, binary output and invalid UTF-8 no longer grow memory, and the pipe is drained to EOF so the child never blocks. Line terminators are normalized exactly as `BufferedReader.readLine()` did, so small outputs are unchanged. The head of the output is kept, not the tail.
