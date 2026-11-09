# Wry/Tao on Android: isolated proof of concept (issue #15)

A minimal app built with Wry and Tao (through Tauri 2, the supported way to package them for Android) that shows local HTML and logs every lifecycle event Tao reports. It is a **separate APK** (`com.termux.wrytaopoc`) and is not part of the Termux build: the root Gradle project does not include it and nothing in the terminal app depends on it.

## Answer to the issue

**A binary started from the Termux shell cannot open a Wry/Tao window by itself.** On Android, Wry needs an Activity and a WebView that belong to the app process. `wry` generates Kotlin sources (`WryActivity`, `RustWebView`, `RustWebViewClient`, `RustWebChromeClient`, `Ipc`, `Rust`) that must be compiled into the APK, and its Rust side exports JNI functions whose names include the app's package (`Rust.onCreate`, `onResume`, `onPause`, `onDestroy`, `onWindowFocusChanged`, `ipc`, …). The Rust code is a shared library loaded by that Activity (`System.loadLibrary`), not an executable.

| Option | What it takes | Verdict |
|---|---|---|
| **Separate APK built with Wry/Tao** (this PoC) | Own package, Activity, signing key and release cycle; Kotlin glue generated per package | Works (measured below). Recommended for any Wry/Tao experiment: it isolates crashes, permissions and the WebView from the terminal. |
| **Integrate Wry/Tao into the Termux APK** | Add the generated Kotlin and a Rust `cdylib` to this app, bind the JNI symbols to `com.termux`, give Wry its own Activity, and keep it from interfering with `TermuxActivity`/`TermuxService` lifecycles; the IPC bridge is an `addJavascriptInterface` object exposed to page scripts | Not recommended now: a large native dependency and a JavaScript bridge in the terminal's process, for no capability the existing pieces lack. |
| **Termux's own viewer** (`ServiceViewerActivity`, issue #14) plus the local endpoint contract (issue #16) | Already merged | Covers "show the web UI of a local backend" without a JavaScript bridge or a native library. |
| **Run arbitrary desktop Wry/Tao programs from Termux** | Not possible as-is: they expect a desktop windowing system (GTK/WebKitGTK on Linux) | Out of scope; would need an X11/Wayland stack, not Android's WebView. |

Graphical integration stays after the headless core, as the issue says; this PoC adds nothing to the main app.

## Verified versions

| Component | Version |
|---|---|
| Rust | rustc 1.99.0, target `aarch64-linux-android` |
| Android NDK | 27.0.12077973 |
| tauri-cli | 2.11.3 |
| tauri / tauri-runtime-wry | 2.12.5 / 2.12.3 |
| **wry / tao** | **0.57.1 / 0.37.2** |
| jni / ndk | 0.21.1 / 0.9.0 |
| Android Gradle Plugin / Gradle / Kotlin | 8.11.0 / 8.14.3 / 1.9.25 (generated project; JDK 17) |
| App SDK levels | compileSdk 36, targetSdk 36, **minSdk 24** |
| Phone | POCO X3 Pro (`vayu`), Android 16 / API 36, arm64-v8a |
| WebView | com.google.android.webview 156.0.8078.47 |

`Cargo.lock` is committed so the same versions resolve again.

## Measurements on the phone

`measure-device.sh APK` installs the PoC as its own package, cold-starts it three times (`am start -W`), reads `dumpsys meminfo`, then sends it to the background, brings it back and closes it with Back. Orientative numbers, one device:

| Build | APK size | Cold start `TotalTime` | Rust `Ready` after `run()` | Memory after start |
|---|---|---|---|---|
| debug (unoptimized) | 126 MB | 743 / 624 / 561 ms | 194 / 131 / 124 ms | 158 MB PSS, 282 MB RSS |
| release (LTO, stripped, signed with the debug key) | 9.6 MB | 256 / 303 / 337 ms | 60 / 56 / 82 ms | 103 MB PSS, 228 MB RSS |

Most of the memory is the WebView renderer and the app runtime, not Rust. No comparison with other apps is claimed.

Lifecycle as observed (release): background → `focused=false`; return → `focused=true`; Back → `focused=false`, `window-destroyed`, `exit-requested`, `exit` (the Activity finishes and the event loop ends; the next launch is a new run). In the debug build the page's `visibilitychange` also reported `hidden`/`visible`. `RunEvent::Resumed` was never emitted on Android with these versions, so an app must use focus or page visibility to notice a return. Page `console.log` output does not reach logcat in the release build.

## Build

On a host with the Android SDK (here `/opt/android-sdk`), NDK 27 and JDK 17:

```sh
cd research/wry-tao-poc/src-tauri
export ANDROID_HOME=/opt/android-sdk NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973 JAVA_HOME=/path/to/jdk-17
cargo tauri android init --ci                              # generates gen/android (git-ignored)
cargo tauri android build --debug --apk --target aarch64   # or without --debug, then zipalign + apksigner
../measure-device.sh gen/android/app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb uninstall com.termux.wrytaopoc
```

A release build took about 200 s on the host. `cargo tauri icon` generated the icons from a plain square image.

**Building inside Termux: NOT_TESTED.** Termux has Rust and an NDK-compatible toolchain, but the APK step needs the Android Gradle Plugin, whose `aapt2` download is an x86-64 Linux binary that does not run on an arm64 phone; it would have to be replaced with Termux's own `aapt2` package and a Termux JDK. Only the host build is verified.

## Not covered

Other devices and Android versions; minSdk below 24 (the generated project requires 24, while Termux supports 21); IPC between the page and Rust (the PoC loads local HTML only); integration with Termux services or the local endpoint contract.
