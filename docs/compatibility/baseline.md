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
