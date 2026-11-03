# Compatibility baseline

Checkout: a3d964973230964640e6bc7abff5e91ab0324e6b, local `main`. The local tracking branch matched at audit time; remote freshness was not verified. No compatibility implementation is claimed here.

## Source observations

* The open receiver logs the entire incoming intent and URI at verbose level and includes the URI in a no-handler error. Authentication query parameters can therefore enter logs when those messages are enabled.
* The receiver catches `ActivityNotFoundException` but does not provide a user-visible deferred browser request. Background dispatch may be restricted or silently suppressed by Android; this has not been reproduced in this audit.
* Interactive and managed execution differ: the existing implementation status explicitly reports that managed jobs do not get automatic glibc compatibility. This is not evidence that any particular binary fails.
* Browser launch, file sharing, DNS, TLS and loopback callbacks are independent paths. No network or authentication failure was reproduced during this audit.

## Verification

`bash scripts/test-glibc-exec-host.sh` passed on the host. This tests the shim with host fixtures, not Android hardware. `adb devices` showed no attached devices. Existing prior testing is recorded in `docs/next-generation/implementation-status.md`; it does not verify the requested compatibility matrix. Other compatibility phases, termux-tools versions, browser and network behavior remain unverified.

## Implementation in progress

The app now routes HTTP(S) receiver and terminal-link requests through a common web launcher. Background receiver requests are held only in a bounded, five-minute in-memory queue and presented for explicit open or copy when the activity resumes; process death drops them. Full incoming URI logging in the receiver was removed. Existing non-web and file paths remain separate. This does not intercept `termux-open-url` implementations that call `am start` directly. It does not prove Android displayed a dispatched URL. Instrumented lifecycle, shell-command, browser and ROM tests remain outstanding. DNS, TLS, runtime diagnostics and SDK integration are not implemented.

The diagnostics dialog now offers a user-initiated Android DNS and HTTPS HEAD probe to example.com. It displays active-network metadata, DNS record-family counts, HTTP status or a conservative error classification, and whether the standard Termux CA bundle is readable. It does not inspect HTTPS content or proxy credentials. The 10-second result deadline cannot cancel an Android resolver call already in progress; at most one daemon worker remains occupied, and further probes return unavailable until it finishes. It does not verify CLI resolver or trust behavior, per-family reachability, private callback servers, or device clock correctness. Android API 21–22 active-network probing remains unavailable. Probe and activity lifecycle behavior have not been tested on devices.

The Compatibility diagnostics menu also offers a read-only executable inspector for a user-supplied absolute path. It checks bounded ELF program headers or a script shebang, reports the device ABI list, recognizes common interpreter names, and checks for a corresponding glibc loader. It does not launch the file, resolve all dynamic libraries, test SELinux or kernel support, or prove the shim is active. Matching a loader alone does not prove a binary will run. Interpreter and file checks reflect the app process and can differ from a shell execution context. Input paths are not logged; script arguments are not included in results. Inspections run off the UI thread with at most one worker; an unresponsive filesystem can hold that worker until the read returns. Android and hardware validation is outstanding.

The authentication diagnostics offer an explicit temporary loopback listener test for IPv4 and IPv6. It binds only loopback addresses on ephemeral ports, sends no authentication data, and does not access a CLI's listener. A pass does not establish that an external browser can reach a CLI callback, that a port is available to another process, or that an OAuth exchange completed. Users can opt individual compatible commands into the existing receiver with `BROWSER=termux-open`; the app does not set or overwrite `BROWSER`. The upstream `termux-open-url` integration gap remains.

Foreground web dispatch failures now retain the URL for explicit open or copy, with no URL displayed in the fallback dialog. The queue remains in memory, bounded and expiring; process death discards it. The Android loopback instrumentation test is included and its APK builds, but it has not been run on a device.

An optional browser callback test now opens a temporary IPv4 loopback URL with a random one-time path and waits up to 30 seconds. It retains only the dispatch and test outcome, reads no OAuth session data, and bounds request count and header consumption. A received request establishes only that the temporary listener received the matching request; it does not establish browser visibility, a CLI callback, or an OAuth exchange. Browser and actual-device behavior remain untested. The separate loopback probe covers in-process IPv4 and IPv6 sockets; the browser probe currently uses IPv4 only.

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

A passive compatibility overview and an optional ControlCore discovery field now expose bounded device metadata and mark dynamic capabilities `UNKNOWN`. The user ID follows the same documented AOSP UID mapping as the shell environment. The snapshot is not a hardware compatibility result, is not refreshed from prior probes, and does not claim that DNS, TLS, notifications, callbacks, browser dispatch or executables work. JSON-RPC v1 and schema revision 1.1 remain unchanged; no URL-opening RPC is exposed.
