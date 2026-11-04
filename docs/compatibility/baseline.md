# Compatibility baseline

Checkout: a3d964973230964640e6bc7abff5e91ab0324e6b, local `main`. The local tracking branch matched at audit time; remote freshness was not verified. No compatibility implementation is claimed here.

Follow-up on 2026-11-03 at 21:17 UTC: `git ls-remote origin refs/heads/main` returned `34717be087a140b6e21fdd848df9a33640fdbd9d`. That commit was an ancestor of local `HEAD` (`26b8ba17`) at the time of the check, so the current local work already included the then-current remote `main`. This is a point-in-time observation, not a guarantee that the remote has not changed since.

## Source observations

* The open receiver logs the entire incoming intent and URI at verbose level and includes the URI in a no-handler error. Authentication query parameters can therefore enter logs when those messages are enabled.
* The receiver catches `ActivityNotFoundException` but does not provide a user-visible deferred browser request. Background dispatch may be restricted or silently suppressed by Android; this has not been reproduced in this audit.
* Interactive and managed execution differ: the existing implementation status explicitly reports that managed jobs do not get automatic glibc compatibility. This is not evidence that any particular binary fails.
* Browser launch, file sharing, DNS, TLS and loopback callbacks are independent paths. No network or authentication failure was reproduced during this audit.

## Verification

`bash scripts/test-glibc-exec-host.sh` passed on the host. This tests the shim with host fixtures, not Android hardware. `adb devices` showed no attached devices. Existing prior testing is recorded in `docs/next-generation/implementation-status.md`; it does not verify the requested compatibility matrix. Other compatibility phases, termux-tools versions, browser and network behavior remain unverified.

Additional host checks on 2026-11-03 passed: the compatibility shell tests (12), Python SDK tests (35), TypeScript SDK tests (38), both SDKs' Java v1 and ADB-bridge interoperability, PTY spawn/output/exit and injected pre-fork failures, managed process launch/signal/path checks, document path checks, and shared-image URI selection. The PTY host script needed `_GNU_SOURCE` for Linux declarations when compiling with strict C11; this affects the host harness, not Android's native build. These results do not establish Android intent grants, Android process behavior, or ROM compatibility.

The host shim checks also exercise malformed ELF64 program-header and interpreter offsets and sizes. They verify conservative pass-through of these fixtures, not execution on Android or support for ELF headers beyond the shim's first-page inspection limit.

## Implementation in progress

The app now routes HTTP(S) receiver and terminal-link requests through a common web launcher. Background receiver requests are held only in a bounded, five-minute in-memory queue and presented for explicit open or copy when the activity resumes; process death drops them. Full incoming URI logging in the receiver was removed. Existing non-web and file paths remain separate. This does not intercept `termux-open-url` implementations that call `am start` directly. It does not prove Android displayed a dispatched URL. Instrumented lifecycle, shell-command, browser and ROM tests remain outstanding; later sections describe the partial diagnostics and SDK integration now implemented.

For a background receiver request that is queued, the receiver also attempts a generic toast asking the user to open Termux. It includes no link or authorization parameters and does not start an activity. Toast delivery can be suppressed by Android or a ROM, and neither delivery nor process survival is guaranteed; the queued dialog is available only while the in-memory request remains alive and unexpired. Host tests inspect the toast text, but background behavior on Android remains unverified.

The web URL policy accepts internationalized domain names after validating an ASCII-compatible representation, and dispatches the original URL. It still rejects user information, malformed ports and ambiguous escaped authorities. Host tests cover the validation and intent data; browser handling on Android has not been verified.

The diagnostics dialog offers a user-initiated Android DNS and HTTPS HEAD probe to example.com and, when safe, one TCP sample per resolved address family. It displays active-network metadata, DNS record-family counts, HTTP status or a conservative error classification, and whether the standard Termux CA bundle is readable. It does not inspect HTTPS content or proxy credentials. The 12-second result deadline cannot cancel an Android resolver or proxy-selector call already in progress; at most one daemon worker remains occupied, and further probes return unavailable until it finishes. These tests do not establish general per-family reachability, CLI resolver or trust behavior, private callback servers, or device clock correctness. Android API 21–22 active-network probing remains unavailable. Probe and activity lifecycle behavior have not been tested on devices.

During that explicit network test, the worker also inspects up to 8 KiB each from `/etc/resolv.conf`, `$PREFIX/etc/resolv.conf`, and `$PREFIX/glibc/etc/resolv.conf`. It reports absence or unreadability, unrecognized input, and whether parsed numeric nameserver sets differ from the selected Android network's exposed resolver set. Addresses, comments and file contents are not reported. A match does not establish which file a binary uses or whether DNS works; a difference may be intentional under a VPN, Private DNS, proxy, custom resolver or process binding. Address ordering and resolver options are not compared, scoped IPv6 addresses are reported unrecognized, and network configuration can change during inspection. Files are never modified. A filesystem read can outlive the 12-second deadline and occupy the same bounded worker. Host parsing and privacy tests pass; device behavior remains untested.

The Compatibility diagnostics menu also offers a read-only executable inspector for a user-supplied absolute path. It checks bounded ELF program headers or a script shebang, reports the device ABI list, recognizes common interpreter names, and checks for a corresponding glibc loader. It does not launch the file, resolve all dynamic libraries, test SELinux or kernel support, or prove the shim is active. Matching a loader alone does not prove a binary will run. Interpreter and file checks reflect the app process and can differ from a shell execution context. Input paths are not logged; script arguments are not included in results. Inspections run off the UI thread with at most one worker; an unresponsive filesystem can hold that worker until the read returns. Android and hardware validation is outstanding.

The inspector can read ELF headers beyond the first 4096 bytes, but the automatic glibc exec shim cannot. When a matching loader exists but the interpreter or its program header is outside that bound, or the file has more than 64 program headers, the inspector reports the automatic shim limitation instead of presenting it as an available automatic adapter. Other execution adapters and the binary itself remain untested.

For scripts, the inspector also checks whether the literal shebang interpreter is a regular executable file visible to the app process. A missing literal path is reported as `MISSING_INTERPRETER`; Termux execution shims can rewrite shebangs, so this result alone does not prove the script cannot run in a shell.

The existing glibc exec shim recognizes `ld-linux` interpreters, not musl. Its glibc-side fallback restores the saved Bionic preload environment to other children without proving their C runtime; a musl child may therefore inherit preload libraries built for Bionic. In a Linux x86-64 host experiment, musl 1.2.6 loaded this app's Android x86-64 exec shim and ran a simple musl child, including after a glibc-side handoff. Adding the bundled x86-64 `libtermux-exec-ld-preload.so` caused relocation failures for Bionic-only symbols; an explicit run with `LD_PRELOAD` removed started that host fixture. This is a host result, not an Android, other-ABI, or arbitrary-application result. The executable inspector calls out this risk; it does not modify preload settings or establish musl compatibility.

When a known musl ELF fails specifically while loading incompatible preloads, the optional app-owned adapter can be selected for an individual command:

```sh
"$TERMUX_APP__FILES_DIR/compat-v1/termux-run-clean" /absolute/path/to/program [arguments]
```

The adapter removes entries exactly matching `TERMUX_GLIBC_EXEC` and the known `$PREFIX/lib/libtermux-exec-{,direct-,linker-}ld-preload.so` paths from that command's `LD_PRELOAD`; it retains the order and values of other entries. It does not resolve symlinks or recognize alternative names, so an incompatible preload may remain. The change applies to the command and its children, and can change path rewriting, script handling and other behavior. Installation is app-owned, versioned, and refuses to overwrite a conflicting entry. It does not alter the shell's environment or change normal command resolution. The diagnostics dialog reports the installation status. Host tests exercise filtering and a musl fixture with a user preload preserved; installation and execution have not been tested on Android.

`RuntimeAdapterInstrumentedTest` is prepared to exercise installation and filtering through Android's shell and linker. One case uses a symlink to the app shim as a stand-in for a Termux preload; another uses the actual installed `termux-exec` library and skips if the bootstrap is absent. Both retain a system library as an additional preload. The [upstream v2.4.0 source](https://github.com/termux/termux-exec-package/blob/v2.4.0/lib/termux-exec_nos_c/tre/src/termux/api/termux_exec/service/ld_preload/direct/exec/ExecIntercept.c) exempts `/system/bin/sh` from its general system-program preload removal, which is relevant to the adapter's shebang, but configuration and runtime behavior may differ. The test APK builds, but neither case has run on Android, and neither includes a real musl process.

As a broader explicit test, `env -u LD_PRELOAD /absolute/path/to/program [arguments]` removes **all** preloads for that command and its children, including user-defined libraries and Termux execution helpers. Neither invocation supplies a missing interpreter or libraries, addresses an ABI mismatch, or proves compatibility. Restore the original invocation to stop using the mode. No environment setting is changed globally, and neither invocation has been tested on Android.

The optional host experiment can be repeated after building the Android debug native libraries and running `bash scripts/test-glibc-exec-host.sh`. Set `MUSL_CC` to a musl compiler wrapper, `MUSL_LOADER` to its absolute dynamic loader path, and `ANDROID_EXEC_SHIM` to the absolute path of the built Android x86-64 `libtermux-glibc-exec.so`; then run `bash scripts/test-musl-preload-host.sh`. To reproduce the bundled-preload failure as well, set `TERMUX_EXEC_SHIM` to the absolute path of `lib/libtermux-exec-ld-preload.so` extracted from the x86-64 bootstrap. It requires a Linux x86-64 host and does not install a toolchain. The recorded run used the [musl 1.2.6 release](https://musl.libc.org/releases/musl-1.2.6.tar.gz), SHA-256 `d585fd3b613c66151fc3249e8ed44f77020cb5e6c1e635a616d3f9f82460512a`.

The authentication diagnostics offer an explicit temporary loopback listener test for IPv4 and IPv6. It binds only loopback addresses on ephemeral ports, sends no authentication data, and does not access a CLI's listener. A pass does not establish that an external browser can reach a CLI callback, that a port is available to another process, or that an OAuth exchange completed. Users can opt individual compatible commands into the existing receiver with `BROWSER=termux-open`; the app does not set or overwrite `BROWSER`. The upstream `termux-open-url` integration gap remains.

Foreground web dispatch failures now retain the URL for explicit open or copy, with no URL displayed in the fallback dialog. The queue remains in memory, bounded and expiring; process death discards it. The Android loopback instrumentation test is included and its APK builds, but it has not been run on a device.

Deferred requests stay in the queue while their dialog is displayed, so a recreated activity can offer the same request again. An explicit open, copy or cancellation consumes it; a failed open keeps it available for copy or cancellation. Requests expire after five minutes, including while a dialog is visible, and a stale dialog cannot consume a later request. A single main-thread callback removes expired queue entries even if no further requests arrive, and a request releases its URL reference on expiry or consumption. The handler uses Android uptime scheduling and may run late during device sleep or a blocked main thread; access checks use elapsed time and still reject expired requests. This releases references but cannot guarantee immediate garbage collection or erase copies held by Android or another application. Host tests cover queue identity and expiry; Android lifecycle and scheduling behavior remain unverified.

An additional instrumentation test covers the eight-request bound, invalid schemes, expiry and stale request identity in the Android app process. Its APK builds, but it has not run on a device and does not verify Android's background activity restrictions or browser behavior.

Host-side receiver tests check that a readable file produces a `content://` view or send intent with a read grant and no write grant, including the inner intent of a chooser, and that a missing file is not dispatched. These tests inspect constructed intents only; they do not verify an external app's provider access, Android grant enforcement, or actual file sharing.

The receiver catches a `SecurityException` from file and non-web activity launches and attempts a generic notice without displaying or logging the URI. Android may suppress background toasts; these paths and provider grants still require real-device testing.

The shared URL/chooser helper also omits URI data, shared intent extras and exception messages from its failure logs. Host tests inject secrets into both a URL and an exception and exercise direct and chooser failures. Android system and receiving-app logs remain outside this check.

The receiver's unsupported-action branch now performs the `ACTION_VIEW` fallback it previously logged but did not apply. HTTP(S) fallback requests consequently use the web policy, while file fallback requests retain the existing read-only content URI path. Raw unsupported action strings and unreadable file paths are no longer written to the receiver log. Host tests cover both fallback routes; Android execution remains unverified.

The existing `am` socket path previously logged full command arguments and peer command lines, which could include authorization URLs even when the URL-opening receiver itself was quiet. Socket accept/close logs now use numeric peer identifiers, and the `am` command log omits arguments. Termux's `am` socket error notifications and logs include a numeric error code instead of peer command lines or exception text. The socket's response to its authorized local caller is unchanged and may still contain caller-supplied data; Android system or third-party application logging is outside this check. A host test covers error-summary redaction, but end-to-end Android log and notification behavior has not been tested.

An optional browser callback test opens a temporary IPv4 or IPv6 loopback URL with a random one-time path and waits up to 30 seconds. The user chooses one family for each test; IPv6 binding can fail if the environment does not support it. The test retains only the dispatch and outcome, reads no OAuth session data, and bounds request count and header consumption. A received request establishes only that the temporary listener received the matching request; it does not establish browser visibility, a CLI callback, or an OAuth exchange. Browser and actual-device behavior remain untested. The separate loopback probe covers in-process IPv4 and IPv6 sockets.

An instrumentation test can explicitly exercise an external `ACTION_VIEW` handler against the temporary listener. It is skipped unless opted in, because it opens another app and may require the browser's first-run setup. With the matching debug app and instrumentation APK installed on a selected test device, run:

```sh
adb -s DEVICE_SERIAL shell am instrument -w \
  -e class com.termux.app.compat.ExternalBrowserCallbackInstrumentedTest \
  -e compatExternalBrowser true com.termux.test/androidx.test.runner.AndroidJUnitRunner
```

The test defaults to an IPv4 listener. Add `-e compatBrowserAddress ipv6` before the runner name to test an IPv6 `::1` listener instead; run the two families independently. IPv6 binding can fail on environments without IPv6 loopback. The test reports whether dispatch was accepted and the temporary listener received its random path; it does not identify which app handled the intent, prove browser visibility, or verify a CLI's authentication exchange. Record the selected default handler and any first-run interactions separately. The test APK builds, but neither external-handler test has yet run on Android.

For a CLI that honors `BROWSER`, select the installed wrapper explicitly for that invocation:

```sh
BROWSER="$TERMUX_APP__FILES_DIR/compat-v1/termux-open-url" your-command
```

To select it for calls resolved by name in the current shell only:

```sh
export PATH="$TERMUX_APP__FILES_DIR/compat-v1:$PATH"
```

This alters command resolution for that shell and its children, and does not intercept absolute paths to the package-owned command. Undo by restoring the prior `PATH` or starting a fresh shell. The wrapper does not automatically override existing user settings. A conflicting existing app-owned path is left untouched and reported as `CONFLICT` in Compatibility diagnostics. If a failed or interrupted installation leaves an incomplete file, it must be inspected before removal or replacement. Local host script tests exercise argument routing, but Android shell execution, managed jobs, user-profile behavior, upgrade and rollback are not verified.

The bootstrap audit also found that its `xdg-open` link targets `termux-open`. The wrapper's installer pins its app-owned directory and uses exclusive, no-follow creation; a conflict is not overwritten. Robolectric's `Os` shadow does not implement the exclusive/no-follow flags, so host mocks cannot validate those flags. Instrumented tests for existing files and symlinks are included but have not run on Android.

The optional `termux-compat` shell diagnostic is installed beside the URL wrapper without changing `PATH` or package-owned files. Run it directly, or add its directory to `PATH` explicitly:

```sh
"$TERMUX_APP__FILES_DIR/compat-v1/termux-compat" doctor
"$TERMUX_APP__FILES_DIR/compat-v1/termux-compat" doctor --json
"$TERMUX_APP__FILES_DIR/compat-v1/termux-compat" network
"$TERMUX_APP__FILES_DIR/compat-v1/termux-compat" browser
"$TERMUX_APP__FILES_DIR/compat-v1/termux-compat" runtime
```

`doctor` and `network` explicitly contact example.com. They run the installed Termux `getent` when present and `curl` with bounded timeouts; absent tools are reported unavailable. The HTTPS check ignores curl config files and disables TLS key logging for its subprocess, but retains proxy and CA environment overrides. It leaves certificate verification enabled, suppresses response data and command errors, and emits only fixed classifications and a numeric HTTP status. `doctor --json` is sanitized. These checks describe that process environment and those specific tools, not arbitrary executables or Android networking. The runtime command reports only the kernel machine name and points to the app's executable inspector. Actual Termux/Android execution and device behavior remain untested.

A passive compatibility overview and an optional ControlCore discovery field now expose bounded device metadata and mark dynamic capabilities `UNKNOWN`. The user ID follows the same documented AOSP UID mapping as the shell environment. The snapshot is not a hardware compatibility result and does not claim that DNS, TLS, notifications, callbacks, browser dispatch or executables work. JSON-RPC v1 and schema revision 1.1 remain unchanged; no URL-opening RPC is exposed.

A completed user-initiated Android network probe can additionally appear in `system.describe` as a separate `networkObservation` for example.com. It expires after 60 seconds and is cleared on observed default-network, link or capability changes; the active network and a configuration fingerprint are rechecked when read. On API levels below 24 or when callback registration or state queries fail, it is omitted. Network callbacks and changes can race, so this is short-lived historical evidence rather than proof of current or universal connectivity. The broad DNS and TLS capability fields stay `UNKNOWN`. No URL, response content, DNS address, proxy address or credentials are included. This path has not been tested on Android hardware.

The Android test now reports IPv4 and IPv6 TCP port-443 samples separately. It uses at most one resolved address per family, sends no application data, and uses the process default socket route rather than binding around VPN routing. It skips the direct samples if a VPN, proxy, explicit process network binding, or uncertain policy is detected. Policy is checked before and after each sample, but network or proxy changes can race with these checks. A successful TCP sample does not verify TLS, other addresses or global family reachability; a failure does not identify its cause. DNS and HTTPS use the process default network and can themselves observe a network change during the test. Mocked policy tests and a host loopback socket test pass; actual OEM/VPN behavior remains untested.

Connection failures report `CONNECTION_REFUSED` only when Android exposes a structured `ECONNREFUSED` cause. An unqualified connection exception remains inconclusive, and a refused HTTPS connection may be to a proxy rather than the target server. Host tests cover classification; an Android test for a reserved loopback port is included but has not been run on a device.

Android HTTPS diagnostics classify typed certificate-path and peer-verification failures separately from generic handshake failures. Expired and not-yet-valid evidence is reported when exposed by the runtime; API 24 reason codes are accessed only on supported Android versions. The report recommends checking the certificate validity period and clock but cannot independently determine whether the clock is wrong. It also checks readability at three fixed Termux bundle locations without reading certificates or modifying trust. Readability is not proof of certificate validity or trust, and Android app, user-installed and CLI trust stores are not automatically compared. Local host TLS tests cover a trusted certificate and an unrelated untrusted certificate; Android device, user-CA and clock behavior remain untested.

See [TLS diagnostics and per-process CA configuration](tls.md) for tool-specific examples and the limits of the separate Android and CLI observations.
