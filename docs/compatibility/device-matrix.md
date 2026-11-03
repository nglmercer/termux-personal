# Compatibility device matrix

Results below concern this compatibility audit, not prior ControlCore testing. No requested compatibility tests were run on hardware. Prior device results for other features are described in `../next-generation/implementation-status.md` and must not be extrapolated to browser, DNS, TLS or authentication compatibility.

| App commit | Android API | Target SDK | Device / ROM | ABI | Test category | Expected | Actual | Status |
|---|---|---|---|---|---|---|---|---|
| a3d964973230964640e6bc7abff5e91ab0324e6b | 34–36 | 28 | Pixel / AOSP | unknown | Compatibility suite | Pass | No device available | NOT_TESTED |
| a3d964973230964640e6bc7abff5e91ab0324e6b | unknown | 28 | Xiaomi / HyperOS | unknown | Compatibility suite | Pass | No device available | NOT_TESTED |
| a3d964973230964640e6bc7abff5e91ab0324e6b | unknown | 28 | Infinix / XOS | unknown | Compatibility suite | Pass | No device available | NOT_TESTED |
| a3d964973230964640e6bc7abff5e91ab0324e6b | unknown | 28 | Samsung / One UI | unknown | Compatibility suite | Pass | No device available | NOT_TESTED |
| a3d964973230964640e6bc7abff5e91ab0324e6b | unknown | 28 | Other customized ROM | unknown | Compatibility suite | Pass | No device available | NOT_TESTED |
| a3d964973230964640e6bc7abff5e91ab0324e6b | unknown | 28 | Emulator | unknown | API regression | Pass | Not run | NOT_TESTED |
| c96885ab | 35 | 28 | Android 15 Google APIs emulator, software emulation | x86_64 | Compatibility instrumentation | Install app and execute tests | Android system server repeatedly restarted during boot; watchdog reported a blocked main thread. Installation failed with broken pipe; tests did not run | NOT_TESTED |
| d2dd3eaa | 35 | 28 | Fresh Android 15 Google APIs emulator, software emulation | x86_64 | Compatibility instrumentation | Install app and execute tests | Initial installation failed while Android package services were still initializing; system server subsequently terminated during boot. Tests did not run | NOT_TESTED |
