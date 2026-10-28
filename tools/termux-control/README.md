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

`follow` requires the separately enabled terminal-output scope. It captures output only after subscribing, reports overwritten gaps to stderr, and ordinarily ends on disconnect or when the session closes. Restarting `follow` begins a new capture and can miss output. Do not follow the same session in which the command runs while stdout goes to that session: it can create a feedback loop. Use another session or redirect stdout to a file or pipe. Output can contain terminal control sequences and secrets.

`attach ID` requires read, write and terminal-output access and an interactive terminal for both stdin and stdout. It looks up the live session PID, rejects a direct same-terminal or ancestor attachment, and fails closed when it cannot inspect the target's standard descriptors or process ancestry. Run it from an independent terminal. Indirect forwarding (for example a multiplexer or nested terminal ultimately displayed in the target session) cannot be reliably detected and can still create an output feedback loop. Ctrl-] then `.` detaches; Ctrl-] twice sends one Ctrl-]. Other bytes, including Ctrl-C and Ctrl-Z, are forwarded unchanged. The client restores local terminal settings on normal exit, handled termination and suspension; uncatchable termination cannot be handled.

Attachment uses the current bounded polling protocol, with no screen snapshot or pre-subscription history. Gaps are reported; a disconnect ends an ordinary attachment. Resize requests follow local terminal geometry; the app can reject them when its local UI owns the target geometry. Android `/proc` restrictions, peer credentials, terminal resizing, keyboard behavior, live PTY concurrency and recovery still require device tests.

Active socket connections have no fixed lifetime; incoming frames still have a five-second deadline and requests are rate-limited. With the app's separate output-resume option enabled, `termux-control --resume follow ID` or `termux-control --resume attach ID` requests a short-lived replay token. After an output-read transport failure, the client tries to reconnect and resume from the last output offset fully written to stdout. Retry attempts start for up to 10 seconds; an individual connection or request may take longer. The server rejects tickets after 15 seconds, and overwritten output is reported as a gap. Resume is best effort: a service restart, stopped listener, expiry, session exit or failed reauthentication ends recovery. The client does not retry input writes with an unknown outcome, and it exits if an input or resize request loses its connection. An ordinary attachment exits on disconnect. Explicit detachment unsubscribes the retained ticket when possible. Output and replay tokens are sensitive and remain local to the process. Device testing is still required.
