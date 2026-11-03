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

The host shim checks also exercise malformed ELF64 program-header and interpreter offsets and sizes. They verify conservative pass-through of these fixtures, not execution on Android or support for ELF headers beyond the shim's first-page inspection limit.

## Implementation in progress

The app now routes HTTP(S) receiver and terminal-link requests through a common web launcher. Background receiver requests are held only in a bounded, five-minute in-memory queue and presented for explicit open or copy when the activity resumes; process death drops them. Full incoming URI logging in the receiver was removed. Existing non-web and file paths remain separate. This does not intercept `termux-open-url` implementations that call `am start` directly. It does not prove Android displayed a dispatched URL. Instrumented lifecycle, shell-command, browser and ROM tests remain outstanding; later sections describe the partial diagnostics and SDK integration now implemented.

The web URL policy accepts internationalized domain names after validating an ASCII-compatible representation, and dispatches the original URL. It still rejects user information, malformed ports and ambiguous escaped authorities. Host tests cover the validation and intent data; browser handling on Android has not been verified.

The diagnostics dialog offers a user-initiated Android DNS and HTTPS HEAD probe to example.com and, when safe, one TCP sample per resolved address family. It displays active-network metadata, DNS record-family counts, HTTP status or a conservative error classification, and whether the standard Termux CA bundle is readable. It does not inspect HTTPS content or proxy credentials. The 12-second result deadline cannot cancel an Android resolver or proxy-selector call already in progress; at most one daemon worker remains occupied, and further probes return unavailable until it finishes. These tests do not establish general per-family reachability, CLI resolver or trust behavior, private callback servers, or device clock correctness. Android API 21–22 active-network probing remains unavailable. Probe and activity lifecycle behavior have not been tested on devices.

During that explicit network test, the worker also inspects up to 8 KiB each from `/etc/resolv.conf`, `$PREFIX/etc/resolv.conf`, and `$PREFIX/glibc/etc/resolv.conf`. It reports absence or unreadability, unrecognized input, and whether parsed numeric nameserver sets differ from the selected Android network's exposed resolver set. Addresses, comments and file contents are not reported. A match does not establish which file a binary uses or whether DNS works; a difference may be intentional under a VPN, Private DNS, proxy, custom resolver or process binding. Address ordering and resolver options are not compared, scoped IPv6 addresses are reported unrecognized, and network configuration can change during inspection. Files are never modified. A filesystem read can outlive the 12-second deadline and occupy the same bounded worker. Host parsing and privacy tests pass; device behavior remains untested.

The Compatibility diagnostics menu also offers a read-only executable inspector for a user-supplied absolute path. It checks bounded ELF program headers or a script shebang, reports the device ABI list, recognizes common interpreter names, and checks for a corresponding glibc loader. It does not launch the file, resolve all dynamic libraries, test SELinux or kernel support, or prove the shim is active. Matching a loader alone does not prove a binary will run. Interpreter and file checks reflect the app process and can differ from a shell execution context. Input paths are not logged; script arguments are not included in results. Inspections run off the UI thread with at most one worker; an unresponsive filesystem can hold that worker until the read returns. Android and hardware validation is outstanding.

For scripts, the inspector also checks whether the literal shebang interpreter is a regular executable file visible to the app process. A missing literal path is reported as `MISSING_INTERPRETER`; Termux execution shims can rewrite shebangs, so this result alone does not prove the script cannot run in a shell.

The existing glibc exec shim recognizes `ld-linux` interpreters, not musl. Its glibc-side fallback restores the saved Bionic preload environment to other children without proving their C runtime; a musl child may therefore inherit preload libraries built for Bionic. Whether a particular musl loader accepts those libraries has not been tested. The executable inspector calls out this uncertainty; it does not modify the user's preload settings or establish musl compatibility.

The authentication diagnostics offer an explicit temporary loopback listener test for IPv4 and IPv6. It binds only loopback addresses on ephemeral ports, sends no authentication data, and does not access a CLI's listener. A pass does not establish that an external browser can reach a CLI callback, that a port is available to another process, or that an OAuth exchange completed. Users can opt individual compatible commands into the existing receiver with `BROWSER=termux-open`; the app does not set or overwrite `BROWSER`. The upstream `termux-open-url` integration gap remains.

Foreground web dispatch failures now retain the URL for explicit open or copy, with no URL displayed in the fallback dialog. The queue remains in memory, bounded and expiring; process death discards it. The Android loopback instrumentation test is included and its APK builds, but it has not been run on a device.

Deferred requests stay in the queue while their dialog is displayed, so a recreated activity can offer the same request again. An explicit open, copy or cancellation consumes it; a failed open keeps it available for copy or cancellation. Requests still expire after five minutes, including while a dialog is visible, and a stale dialog cannot consume a later request. Host tests cover queue identity and expiry; Android lifecycle behavior remains unverified.

Host-side receiver tests check that a readable file produces a `content://` view or send intent with a read grant and no write grant, including the inner intent of a chooser, and that a missing file is not dispatched. These tests inspect constructed intents only; they do not verify an external app's provider access, Android grant enforcement, or actual file sharing.

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

Android HTTPS diagnostics classify typed certificate-path and peer-verification failures separately from generic handshake failures. Expired and not-yet-valid evidence is reported when exposed by the runtime; API 24 reason codes are accessed only on supported Android versions. The report recommends checking the certificate validity period and clock but cannot independently determine whether the clock is wrong. It also checks readability at three fixed Termux bundle locations without reading certificates or modifying trust. Readability is not proof of certificate validity or trust, and Android app, user-installed and CLI trust stores are not automatically compared. Local host TLS tests cover a trusted certificate and an unrelated untrusted certificate; Android device, user-CA and clock behavior remain untested.

See [TLS diagnostics and per-process CA configuration](tls.md) for tool-specific examples and the limits of the separate Android and CLI observations.
