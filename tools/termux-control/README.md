# termux-control prototype

## ADB access to a debug build

On an authorized computer, run a shell as the app UID using Android's
`run-as`; it requires ADB, an unlocked/authorized device and a debuggable
`com.termux` installation. Three clients share one CLI surface — pick the
runtime you have:

| Tool | Runtime | Modes |
| ---- | ------- | ----- |
| `python/termux-adb` | Python 3, no dependencies | `shell run exec focus open snapshot ipc` |
| `js/termux-adb.mjs` | Node.js or Bun, no dependencies | `shell run exec focus open snapshot ipc` |
| `sh/termux-adb.sh` | Bash only | `shell exec` (session IPC modes need one of the above) |

This is independent of the optional local control socket:
`termux-adb shell` opens an independent interactive shell, `termux-adb exec 'pwd; id'`
runs an independent script and waits for its exit status. Shell commands have
the app's permissions and access to its files. Use `-s SERIAL` before the command
when multiple devices are connected. ADB's shell exit status is propagated.

`termux-adb run 'pwd; id'` creates a named, visible Termux session and sends the
script to it asynchronously; `run < script.sh` reads from stdin. The command
prints the new session ID to stderr. `run --session ID 'command'` reuses an exact
ID or unique name. `--name NAME` names new sessions, `--focus` selects the session,
and `--open` also opens the app. `termux-adb focus ID`, `open ID` and `snapshot ID`
select, open, or read the most recent transcript respectively. The snapshot is
limited to the last 8000 characters and requires output permission. Session
scripts run in a child Bash, do not report their exit code to the host and cannot
read interactive input from the terminal. Encoded input is limited to 3000 bytes
to avoid terminal line limits. Sending a command to a busy or partially
typed session can affect whatever is running there; use a fresh session when unsure.
ADB success confirms input was accepted, not that the script finished successfully.

For IPC, enable Local control from Settings or the terminal's context menu, then
run `termux-adb ipc capabilities` or `termux-adb ipc list`. Write and output access
must be selected in the dialog. The IPC client is separate from the APK. For an
arm64 device, build with an Android NDK and install the client for the debug app:

```
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang" -O2 -std=c11 -Wall -Wextra -Werror tools/termux-control/termux-control.c -o /tmp/termux-control-adb
adb push /tmp/termux-control-adb /data/local/tmp/termux-control-adb
adb shell run-as com.termux cp /data/local/tmp/termux-control-adb files/termux-control-adb
adb shell run-as com.termux chmod 700 files/termux-control-adb
```

The listener is temporary and must be enabled again after the service stops. ADB
shell access through `run-as` does not depend on that setting and works only for
debuggable installations; it is not an SSH server or a remote network service.

A small native client for the opt-in local control socket. It is not included in the APK, bootstrap or a released Termux package yet. The listener is disabled by default; Local control offers read-only or read/write access until the service stops. All processes running under the app UID share the selected access. The client can send bounded binary input, follow newly captured output, or interactively attach to a specific session. The interactive path is a draft and has not been tested on Android devices.

Build locally with `make -C tools/termux-control` using a native Android/Termux C toolchain. The default socket path is `$HOME/../control-ipc/control.sock`; use `--socket PATH` or `TERMUX_CONTROL_SOCKET` if the home directory is nonstandard. Commands are `capabilities`, `list`, `create COLS ROWS [NAME]`, `rename ID NAME`, `resize ID COLS ROWS`, `close ID`, and `write ID` (reads 1–65536 bytes from stdin), `follow ID` (writes captured output bytes to stdout), and `attach ID` (interactive raw-terminal input/output). `write` sends the supplied bytes exactly and does not add Enter; bytes supplied by the caller may themselves contain command-executing control characters. Other command results are JSON; errors go to stderr and return a nonzero exit status. The client checks that the socket peer has the same UID. A timeout after a request is sent may mean its outcome is unknown; inspect session state before retrying a mutating request.

For release, this source must be packaged and reviewed in the appropriate `termux-tools` or `termux-packages` repository for each supported ABI, with tests and an upgrade path. Building or copying this prototype locally does not establish a packaging or release strategy. No Python or Java runtime is needed by the client; `test-client.py` and `test-java-interop.py` are host-only tests; the latter checks the native client against the Java protocol loop, not Android LocalSocket.

From the repository root, `bash scripts/test-control-client-host.sh` runs the native mock-socket tests and compatibility checks for the three ADB adapters. The adapter checks use a mock `adb` and require Python 3, Node.js and Bash.

The `bridge` command copies bytes between stdin/stdout and the authorized local socket without negotiating on behalf of the caller. It is intended for debug SDK transports over `adb run-as`; the caller must perform the normal handshake and follow v1 framing. The bridge verifies the socket peer UID and does not enable local control. It has bounded in-memory buffers and does not add a listener.

`follow` requires the separately enabled terminal-output scope. It captures output only after subscribing, reports overwritten gaps to stderr, and ordinarily ends on disconnect or when the session closes. Restarting `follow` begins a new capture and can miss output. Do not follow the same session in which the command runs while stdout goes to that session: it can create a feedback loop. Use another session or redirect stdout to a file or pipe. Output can contain terminal control sequences and secrets.

`attach ID` requires read, write and terminal-output access and an interactive terminal for both stdin and stdout. It looks up the live session PID, rejects a direct same-terminal or ancestor attachment, and fails closed when it cannot inspect the target's standard descriptors or process ancestry. Run it from an independent terminal. Indirect forwarding (for example a multiplexer or nested terminal ultimately displayed in the target session) cannot be reliably detected and can still create an output feedback loop. Ctrl-] then `.` detaches; Ctrl-] twice sends one Ctrl-]. Other bytes, including Ctrl-C and Ctrl-Z, are forwarded unchanged. The client restores local terminal settings on normal exit, handled termination and suspension; uncatchable termination cannot be handled.

Attachment uses the current bounded polling protocol, with no screen snapshot or pre-subscription history. Gaps are reported; a disconnect ends an ordinary attachment. Resize requests follow local terminal geometry; the app can reject them when its local UI owns the target geometry. Android `/proc` restrictions, peer credentials, terminal resizing, keyboard behavior, live PTY concurrency and recovery still require device tests.

Active socket connections have no fixed lifetime; incoming frames still have a five-second deadline and requests are rate-limited. With the app's separate output-resume option enabled, `termux-control --resume follow ID` or `termux-control --resume attach ID` requests a short-lived replay token. After an output-read transport failure, the client tries to reconnect and resume from the last output offset fully written to stdout. Retry attempts start for up to 10 seconds; an individual connection or request may take longer. The server rejects tickets after 15 seconds, and overwritten output is reported as a gap. Resume is best effort: a service restart, stopped listener, expiry, session exit or failed reauthentication ends recovery. The client does not retry input writes with an unknown outcome, and it exits if an input or resize request loses its connection. An ordinary attachment exits on disconnect. Explicit detachment unsubscribes the retained ticket when possible. Output and replay tokens are sensitive and remain local to the process. Device testing is still required.

## Managed jobs and workspaces

These commands call fixed v1 methods. Each one needs the matching local consent on the device, and each is absent from `capabilities` until consent is granted.

```text
termux-control jobs list
termux-control jobs get ID
termux-control jobs cancel ID              # TERM to the job's process group, KILL after 10 s
termux-control jobs start   [OPTIONS] -- EXECUTABLE [ARG...]
termux-control jobs run     [OPTIONS] -- EXECUTABLE [ARG...]
termux-control jobs preview [OPTIONS] -- EXECUTABLE [ARG...]   # job.preview: prints the planned command as JSON, starts nothing
termux-control jobs logs ID [--stream stdout|stderr] [--follow]
termux-control jobs resource ID | processes ID | history | history-get ID | diagnostics ID
termux-control workspaces list
termux-control workspaces get ID
termux-control workspaces tasks ID
termux-control workspaces git ID status|branch|diff|log   # read-only; other subcommands are refused before connecting
termux-control workspaces run ID TASK      # runs a stored preset in the workspace directory
termux-control workspaces create NAME ROOT PATH   # ROOT is a configured file root, PATH a directory under it
termux-control workspaces update ID NAME ROOT PATH
termux-control workspaces select ID
termux-control workspaces remove ID
```

`OPTIONS` for `start`, `run` and `preview`:

| Option | Request field | Meaning |
|---|---|---|
| `--cwd DIR` | `cwd` | Absolute working directory inside an authorized root (the Termux home). |
| `--workspace ID` | `workspaceId` | Run in a workspace directory. |
| `--timeout MS` | `timeoutMs` | Wall-clock limit; needs `jobStopAvailable`. |
| `--profile NAME` | `resources.profile` | `unrestricted`, `protected`, `balanced`, `performance` or `custom`. |
| `--build-preset` | `resources.applyBuildPreset` | Opt in to adding worker flags to a known build tool (see [resource-policy.md](../../docs/next-generation/resource-policy.md#build-presets)). Without `--profile` it uses `balanced`. |
| `--workers N` | `resources.processConcurrencyHint` | 1–16. The worker count a preset writes; advice, not a process cap. |
| `--test-concurrency N` | `resources.testConcurrency` | 1–16, needs `--build-preset`. Adds `--test-threads=N` to `cargo test` (libtest) or `--test-concurrency=N` to `node --test`. Not a global cap. |
| `--expect-fingerprint HEX` | `expectedPreviewFingerprint` | `start`/`run` only: the `previewFingerprint` printed by `jobs preview`. A changed plan is refused as `CONFLICT` and nothing starts. |

Without `--profile`, `--build-preset` and `--workers` the request carries no `resources`, exactly as before. There is no `--env` option and the CLI does not copy the calling shell's environment into the job: a variable you `export` before running `termux-control` is not guaranteed to reach the job. Use the Python or TypeScript SDK (or raw JSON-RPC) when a job needs explicit `env`.

`jobs start`, `jobs run` and `jobs preview` send the executable and each argument as separate JSON strings; nothing is joined into a shell string, so run a shell explicitly (`-- $PREFIX/bin/sh -c '...'`) when you want one. `EXECUTABLE` is an absolute path under the Termux prefix or a server-approved name such as `git`. `start` prints the accepted job record. `run` also copies the job's stdout and stderr to its own, then exits with the job's exit code. A job that did not exit normally gives a fixed status instead: 124 timed out, 130 cancelled, 125 failed or lost. `--timeout` and cancelling a running job need `jobStopAvailable` in `system.describe`. `jobs logs` prints the output available now, or with `--follow` polls until the stream is finished; a gap message on stderr means older bytes were overwritten.

### Walkthrough: a long build as a protected managed job

This moves a heavy build out of an interactive session into a managed job that waits for admission, runs at lower priority and can be observed and stopped. It is a mitigation, not a guarantee: Android can still end any process (low-memory killer, phantom-process limits, the app being stopped), and the `protected` profile controls only the admission of managed jobs that use it. It does not limit interactive sessions, other jobs without a profile or anything else in Termux.

1. **Enable Local control** (Settings → Local control, or the terminal's context menu) with the Jobs options *See jobs* (`preview`, `get`, `resource`, `history-get`), *Run commands* (`start`, `run`), *Read job output* (`logs`, `run`) and *Cancel jobs*, plus *List processes* for `jobs processes`. *Run commands* gives full Termux access to any client running as the app. The listener stops with the service; enable it again after a restart.
2. **Have the client and the tools.** `termux-control` is not shipped in the APK or a Termux package: build it with `make -C tools/termux-control` (see above) and put it on your `PATH`. Install the toolchain you will run (`pkg install rust`, `pkg install nodejs`). Executables are given as absolute paths: in the shipped configuration only `git` has a short alias, so write `"$PREFIX/bin/cargo"`, not `cargo`. The project must be inside the authorized root (the Termux home); do not widen roots for this.
3. **Check what is available**: `termux-control capabilities` lists the methods your consent allows; `termux-control resources capabilities` reports what this device can enforce and measure.
4. **Preview** the effective command. Nothing is started:

   ```sh
   termux-control jobs preview --cwd "$PWD" --profile protected --build-preset --test-concurrency 1 -- \
     "$PREFIX/bin/cargo" test
   ```

   `arguments` shows what would run (here `test -j 1 -- --test-threads=1`), `notes` explains every addition or skip, `resources.processConcurrencyHint` is the worker count really written, and `previewFingerprint` identifies this exact plan.
5. **Start** it. Either write the limits yourself, which works with any version of the app that has `jobs start`:

   ```sh
   termux-control jobs run --cwd "$PWD" --profile protected -- \
     "$PREFIX/bin/cargo" test -j 1 -- --test-threads=1
   ```

   or start exactly what you previewed. The worker recommendation follows pressure and free slots, so pin the count you saw and pass the fingerprint; if anything else changed, the start fails with `CONFLICT` and nothing is launched:

   ```sh
   termux-control jobs start --expect-fingerprint FINGERPRINT --workers 1 \
     --cwd "$PWD" --profile protected --build-preset --test-concurrency 1 -- "$PREFIX/bin/cargo" test
   ```

   `run` streams the output and exits with the job's status. `start` prints the job record and returns; keep its `id`.
6. **Observe** from another terminal (replace `JOB_ID`):

   ```sh
   termux-control jobs get JOB_ID                       # state and exit code
   termux-control jobs resource JOB_ID                  # applied limits, sampled RSS/CPU, events
   termux-control jobs processes JOB_ID                 # the job's process tree, as visible in /proc
   termux-control jobs logs JOB_ID --stream stdout --follow
   termux-control jobs logs JOB_ID --stream stderr
   ```

7. **Cancel** if needed: `termux-control jobs cancel JOB_ID` (TERM to the job's process group, KILL after 10 s).
8. **Recover metadata later**: `termux-control jobs history-get JOB_ID` returns the end state and termination evidence after the job left memory or the service restarted. History keeps metadata only; output lives in a bounded in-memory buffer and is lost with the service or when overwritten (a gap is reported).

If a response to `start` is lost, the outcome is unknown: check `jobs list`/`jobs get` before sending it again. A `CONFLICT` from a later start does not prove that an earlier request never started.

**Without a managed job**, limit the tools by hand in an ordinary session. These commands are not supervised, queued or recorded just because they carry these flags:

```sh
cargo test -j 1 -- --test-threads=1     # -j: build jobs; --test-threads: libtest harness threads
node --test --test-concurrency=1        # Node 18.19+, 20.10+ or 21+
```

Other commands send only identifiers and the subcommand; the server supplies the executable, arguments and directory. A lost response to a mutating command (`cancel`, `start`, `run`, `git`, workspace changes) prints that the outcome may be unknown, and the client never retries it. Exit status 2 means a usage error that never reached the socket; 1 means a transport, protocol or server error.
