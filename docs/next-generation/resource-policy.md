# Managed job resource policy

An optional `resources` object on `job.start` asks for resource handling for one managed job. A request without it behaves exactly as it always did, including its idempotency fingerprint. Nothing here applies to interactive terminal sessions, `RUN_COMMAND` plugin calls or other processes of the app.

```json
{"resources": {"profile": "balanced", "memorySoftBytes": 1073741824, "cpuTimeSeconds": null,
               "addressSpaceBytes": null, "maxOutputBytes": 131072, "processConcurrencyHint": 2,
               "niceLevel": 5, "onPressure": "warn", "requireNice": false, "applyBuildPreset": true,
               "testConcurrency": 1}}
```

Every member is optional and `null` means unset. Out-of-range or inconsistent values are rejected with `INVALID_REQUEST`; they are never clamped. Wrong JSON types or unknown members are invalid params.

## What each field really does

| Field | Enforced by | Guarantee |
|---|---|---|
| `memorySoftBytes` | supervisor sampling | **Observed only.** RSS of the job's process tree is compared to it at each sample. Nothing stops a process from exceeding it between samples. |
| `addressSpaceBytes` | `RLIMIT_AS` in the child before `execve` | Virtual address space, **not** resident memory. Breaks runtimes that reserve large ranges (JVM, V8, some Rust allocators), so no profile ever sets it. On the tested Android 16 device even `cat` aborts below 4 GiB, so the device's measured minimum is enforced: a lower request is refused as `UNSUPPORTED`. |
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

Executables are matched by exact basename; anything else is never rewritten, flags after `--` are ignored, and the user's arguments are only ever added to. `N` is capped at the CPU count. Node's `--max-old-space-size` is added only when `job.start` also sets `resources.nodeHeapMiB` (64 to 16384) together with `applyBuildPreset`; a heap limit without the preset is refused, and stored task presets cannot carry it. `job.preview` and `workspace.task.preview` return the effective command (arguments, whether a preset applied, what changed and why) without starting anything.

When a preset writes a worker count and the request gave no `processConcurrencyHint`, the job's recorded policy (in the preview, in `job.resource.get` and in the idempotency fingerprint) carries the count really written. A hint the caller gave is kept exactly as given. Earlier versions recorded the unset hint while writing the derived count into the command.

### Test runners: `testConcurrency`

`resources.testConcurrency` (1–16, `null` = absent) is a separate, explicit opt-in. It needs `applyBuildPreset: true` (otherwise `INVALID_REQUEST`), it is a planning option of `job.start`/`job.preview` only (like `nodeHeapMiB`), and a workspace or task profile never turns it on. It is not a global thread or process cap.

| Command | Added | Left unchanged when |
|---|---|---|
| `cargo test`, `cargo t`, with an optional `+toolchain` and the no-value flags `-v`/`-q`/`--offline`/`--frozen`/`--locked` before the subcommand | `--test-threads=N` right after the first `--`; `-- --test-threads=N` appended when there is no `--` | `--test-threads=N` or `--test-threads N` in the harness section, `RUST_TEST_THREADS` in the job's `env`, `--no-run`, or any other option before the subcommand (ambiguous) |
| `node --test`, `nodejs --test`, where `--test` is among Node's own options (before the script and before `--`) | `--test-concurrency=N` right after `--test` | `--test-concurrency` already among Node's options, or `NODE_OPTIONS` in the job's `env` (its value is not inspected, so a conflict cannot be ruled out) |

Build jobs and test threads are decided independently: `cargo test -j 4` keeps `-j 4` and still gets the harness limit, and `CARGO_BUILD_JOBS` only suppresses the `-j`. Every addition or skip appears in the preview's `notes`.

Limits of this option, stated so it is not over-trusted:

* **Cargo:** only the standard libtest harness understands `--test-threads`. The command name does not prove that every target uses it: a target with `harness = false`, `cargo nextest`, a custom runner or a wrapper script may reject or ignore the flag. Manifests are not inspected. Opt in only when you know the runner.
* **Node:** `--test-concurrency` exists in Node 18.19.0+ (18.x), 20.10.0+ (20.x) and 21+. Nothing checks the version: `job.preview` never runs `node --version` or any other binary. An older runtime rejects the flag; the job then fails with Node's error and is not retried without it. The flag limits how many test files run at once, not the threads or processes the tests themselves create.
* `npm test`, `npm run`, `pnpm`, `yarn`, shell scripts and interactive sessions are never rewritten.

### Running exactly what was previewed

`job.preview` and `job.start` share the planning code, but sharing code is not enough: the worker recommendation follows the current pressure and free admission slots, so two calls can plan different commands. `job.preview` therefore returns `previewFingerprint`, a 64-hex-digit digest of the materialized plan (executable, arguments, cwd, workspace, `env`, stdin, timeout, presentation and the effective resource policy), derived from the existing job fingerprint. `job.start` accepts it as `expectedPreviewFingerprint`:

* if the new plan matches, that one materialized spec is admitted; nothing transforms it again while it is queued or launched;
* if it differs, the start fails with the existing `CONFLICT` error before anything is admitted;
* a start without it behaves as before.

To make a reviewed plan reproducible, start with the same fields and pin `processConcurrencyHint` to the `resources.processConcurrencyHint` the preview reported. The fingerprint only detects a change. It grants nothing, it is not an authorization and it does not replace consent or the path policy. Arguments and environment are hashed in memory, never logged. `clientRequestId` replay rules are unchanged: if a start's response is lost, read the job state before sending it again, and do not read a later `CONFLICT` as proof that the earlier request never started.

For a step-by-step guide from an interactive terminal to a protected managed job (preview, start, observe, cancel, history), see the walkthrough in [tools/termux-control/README.md](../../tools/termux-control/README.md#walkthrough-a-long-build-as-a-protected-managed-job).

## Legacy `RUN_COMMAND` output

`StreamGobbler` now reads stdout/stderr as bytes and retains at most 4 MiB per stream (`ResultData.stdoutDroppedBytes`/`stderrDroppedBytes` count the rest; the plugin result's original-length extras include them). A single huge line, binary output and invalid UTF-8 no longer grow memory, and the pipe is drained to EOF so the child never blocks. Line terminators are normalized exactly as `BufferedReader.readLine()` did, so small outputs are unchanged. The head of the output is kept, not the tail.

## Device policy and workspace configuration (PR 3)

`DeviceResourcePolicy` holds the device-aware numbers in one place: heavy slots from total RAM and pressure, workers per job from the CPU count shared among concurrent jobs, and a memory observation threshold from total and available RAM. They are starting heuristics and none of them is a performance claim. Admission, build presets and the supervisor all use it, so they cannot disagree. A job without its own `memorySoftBytes` and with a profile gets a derived threshold at its first sample; that threshold is only observed and recorded unless the job chose `stop_job`.

A workspace may carry an optional `resourceProfile`, a task preset may carry optional `resources`, and a workspace may define up to eight `services`. All are optional and absent by default: a v1 file written before they existed loads unchanged, is saved without them, and its tasks run exactly as before. A bad optional field is dropped when loading (the workspace and its tasks survive); it is never treated as file corruption. Running a task uses the preset's own policy, else the workspace profile, else nothing; a known build tool then gets the device-aware worker count.

### Services

A service is a managed job started from a stored definition (`workspace.service.*`). It uses the one coordinator, so limits, admission, history and ownership-safe stopping apply. State is in memory: nothing starts after a restart. Restart policy defaults to `never`; `on-failure` restarts after a non-zero exit, a signal or a failed start; `always` after any exit. Restarts back off from 1 s up to 30 s, at most five per ten minutes, then the service stays in `crash_loop`. A service whose last run ended by a resource-guard stop or a possible memory-pressure kill is `memory_stopped` and is not retried. An explicit stop, revoked consent or a removed definition beat any pending restart. `restart` waits for the old job to end and never runs two copies.

### Ports and proxies

`workspace.service.ports` observes each declared port by connecting: `listening` on loopback, `lanExposed` only if it also accepts connections on a non-loopback address of the device, and a `conflict` when another service declares it or something else holds it. `workspace.service.proxy` relays a local port to `127.0.0.1:targetPort` on request. It listens on loopback by default; any other address needs `confirmLan`. The target is always loopback, addresses must be IP literals, ports below 1024 are refused, and there are at most four proxies and 32 connections with an idle timeout.

### Backups

`workspace.backup.export` writes a ZIP or tar.gz to app-private storage and returns its ID; `workspace.backup.import` restores from that ID into the same workspace. Export skips `node_modules`, `.gradle`, `target`, `.git`, `cache`, temporary files, symbolic links and, unless `includeSensitive` is set, files that look like credentials. Restore treats the archive as hostile: unsafe names, links and devices are refused and counted, writes never go through a symbolic link, the size, file count, depth and compression ratio are bounded, and existing files are kept unless `overwrite` is set. `overwrite` and `applyConfig` (which replaces the workspace's tasks and services from the archive) need `confirm`. Both methods take `async: true` to run in the background and return an operation at once: `workspace.backup.status` reports progress (files and bytes) and, when finished, the result; `workspace.backup.cancel` stops it cooperatively between chunks and removes its temporary files. One operation runs at a time (a second is refused as `RESOURCE_LIMITED`), the last eight are remembered, and nothing resumes after a restart. It needs Android 8.0 (`java.nio.file`): below that the methods are not advertised.

### Protocol additions

`resource.capabilities`, `resource.snapshot`, `job.resource.get`, `job.history.list|get`, `job.diagnostics.export`, `job.processes` (all `JOBS_READ`; `job.processes` also `PROCESSES_READ`, and diagnostics output needs `JOBS_OUTPUT`), `workspace.service.list|status|health|ports` (`WORKSPACES_READ`), `.start|.stop|.restart` (`WORKSPACES_READ` and `JOBS_EXECUTE`), `.logs` (`WORKSPACES_READ` and `JOBS_OUTPUT`), `.proxy` (`WORKSPACES_WRITE`), `workspace.backup.export` (`WORKSPACES_WRITE`, `FILES_READ`), `.import` (`WORKSPACES_WRITE`, `FILES_WRITE`), `.status` (`WORKSPACES_READ`) and `.cancel` (`WORKSPACES_WRITE`), and the read-only previews `job.preview` (`JOBS_READ`) and `workspace.task.preview` (`WORKSPACES_READ`). Each is advertised only when its service exists and its scopes are granted. Existing methods and the JSON-RPC envelope are unchanged. `process.list` still reports `managedJobId: null`; `job.processes` carries the association. The Python and TypeScript SDKs and the `termux-control` CLI expose all of them.
