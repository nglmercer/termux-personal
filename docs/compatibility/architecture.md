# Compatibility architecture audit

Audit of checkout a3d964973230964640e6bc7abff5e91ab0324e6b. This is an inventory, not an implementation of the proposed compatibility layer.

* Terminal links use `ShareUtils.openUrl`, which dispatches an `ACTION_VIEW` intent. Other UI paths also launch intents directly.
* `TermuxOpenReceiver` is private. It dispatches non-file URIs from a broadcast with `NEW_TASK`; file paths are separately converted to provider content URIs with read grants. Changes to browser launching must preserve the latter path and its existing access checks.
* Shell environment construction begins in `AndroidShellEnvironment`, `TermuxShellEnvironment` and `TermuxAppShellEnvironment`. Interactive profile scripts can subsequently change it. Managed jobs have a separate execution path; see `docs/next-generation/managed-jobs-appshell-audit.md`.
* `ShellCompatibility.ensure()` writes an app-owned profile script, prepends a checked glibc exec shim to an existing preload, and may restart an interactive login bash once. `glibc-exec.c` switches loader and preload environment across runtime boundaries. Existing documentation says managed jobs do not receive automatic glibc compatibility.
* `termux-open` and `termux-open-url` belong to the installed termux-tools package, not this source tree. Their installed versions and command behavior need device verification before changing integration.
* ControlCore and JSON-RPC v1 are existing interfaces. No compatibility RPC is implemented by this audit.

The manifest requests internet and network-state permissions, declares the open receiver non-exported, and exposes a file provider with its existing access policy. The project builds with min SDK 21, target SDK 28 and compile SDK 36. Package visibility and activity launch behavior require tests at the relevant target/API combinations; a successful `startActivity` cannot establish visibility.

The opt-in network test uses the active Android `Network` for native DNS and HTTPS; it does not change resolver settings, trust settings, network binding, or CLI environment. The app reports its own Android trust result only. A response from the fixed endpoint does not establish the health of another host, and IPv4/IPv6 DNS records do not establish family-specific reachability. Network metadata is a snapshot and can change during the test.
