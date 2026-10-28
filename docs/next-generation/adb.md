# ADB integration (work in progress)

`AdbEndpoint` is an initial numeric endpoint and six-digit pairing-code validation helper. It is not connected to the UI and does not install, run, pair, connect to, or control ADB. A complete implementation still needs a user-authorized client, connection lifecycle, privacy protections, timeout and device testing.

The existing `RUN_COMMAND` Intent continues to use its existing Android permission and `allow-external-apps` policy. ADB host-to-Termux command access is separate from Termux acting as an ADB client. See the upstream [RUN_COMMAND documentation](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) and [Android wireless debugging documentation](https://developer.android.com/studio/run/device) for current platform requirements.
