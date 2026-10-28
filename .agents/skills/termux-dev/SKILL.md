---
name: termux-dev
description: Develop with Termux on Android via adb using this debuggable fork — run shell commands, install toolchains, and build and verify any GitHub project on-device.
---

# Termux on-device development

Use this skill when the task needs a real Android Termux shell (install
packages, compile code, run a phone CLI) instead of the Linux PC. All device
work goes through one of the `tools/termux-control/` clients
(`python/termux-adb`, `js/termux-adb.mjs`, `sh/termux-adb.sh` — same CLI
surface; the shell script covers `shell`/`exec` only), which execute
commands as the Termux user on an authorized adb device. The examples below
use the Python client; swap in the Node or Bash client when that runtime
fits better.

Requires: this fork's debuggable `com.termux` build installed on the device
(release-signed Termux rejects `run-as`), at least one authorized adb device
(`adb devices`), and a bootstrapped Termux (open the app once after install).

## Run a command in Termux

```bash
python3 tools/termux-control/python/termux-adb exec '<bash script>'
```

`exec` feeds the script to Termux `bash` over adb with `HOME`, `PREFIX`,
`PATH`, and `TMPDIR` already set. Always use this helper — hand-rolled
`adb shell run-as ...` quoting breaks on adb's argument re-joining and on
multi-layer `$` expansion. Other modes: `shell` (interactive Termux shell),
`run` (send a script to a shared live session), `snapshot` / `focus` / `open`
(inspect and select sessions), `ipc` (raw control client).

## Use it from any tool, not just Python

`termux-adb` is a thin wrapper. `exec` does exactly this, which any tool
that can spawn `adb` can replicate:

1. Build this remote command as argv: `run-as com.termux`, then
   `env HOME=<home> PREFIX=<usr> PATH=<usr>/bin:/system/bin
   TMPDIR=<usr>/tmp LANG=C.UTF-8` (with `<home>` =
   `/data/data/com.termux/files/home`, `<usr>` =
   `/data/data/com.termux/files/usr`), then
   `<usr>/bin/bash -c 'cd "$HOME" && exec "$PREFIX/bin/bash" -s'`.
2. Join the argv into ONE shell-quoted string (quote every part, e.g.
   Python `shlex.quote`) and pass it as the single argument to
   `adb shell -T`. A single argument is required — adb re-joins multiple
   arguments with spaces, which breaks quoting.
3. Pipe the script bytes to that process's stdin; `bash -s` runs them.
   Using stdin (`-s`) instead of `-c` avoids every quoting layer except one.

Pure-bash equivalent (only `adb` required):

```bash
termux_exec() {
  printf '%s' "$1" | adb shell -T 'run-as com.termux env HOME=/data/data/com.termux/files/home PREFIX=/data/data/com.termux/files/usr PATH=/data/data/com.termux/files/usr/bin:/system/bin TMPDIR=/data/data/com.termux/files/usr/tmp LANG=C.UTF-8 /data/data/com.termux/files/usr/bin/bash -c '\''cd "$HOME" && exec "$PREFIX/bin/bash" -s'\'''
}
termux_exec 'git --version; rustc -vV | grep host'
```

Node.js equivalent (`spawnSync('adb', ['shell', '-T', quoted], { input })`):

```js
import { spawnSync } from 'node:child_process';
const root = '/data/data/com.termux/files';
const argv = ['run-as', 'com.termux', 'env',
  `HOME=${root}/home`, `PREFIX=${root}/usr`,
  `PATH=${root}/usr/bin:/system/bin`, `TMPDIR=${root}/usr/tmp`, 'LANG=C.UTF-8',
  `${root}/usr/bin/bash`, '-c', 'cd "$HOME" && exec "$PREFIX/bin/bash" -s'];
const q = (s) => /^[A-Za-z0-9_@%+=:,./-]+$/.test(s) ? s : `'${s.replace(/'/g, `'"'"'`)}'`;
const r = spawnSync('adb', ['shell', '-T', argv.map(q).join(' ')], { input: script });
```

The other modes map to these primitives: `shell` is the same command with
`adb shell -t` and `bash -l` (interactive pty); `run` base64-encodes the
script and IPC-`write`s it into a live session; `snapshot`/`focus`/`open`
are IPC calls (`open` additionally runs
`adb shell am start -n com.termux/.app.TermuxActivity`); `ipc` passes
arguments straight to the on-device `termux-control-adb` client. With
several devices, prefix every form with `adb -s <serial>`
(`termux-adb -s <serial> ...`).

## Set up a project

Work in the private Termux home — Android shared storage may prohibit
execution and Unix sockets. Clone any public repo with git (or
`gh repo clone`); private repos need `gh auth login` first.

```bash
python3 tools/termux-control/python/termux-adb exec 'cd $HOME
test -d <repo> || git clone https://github.com/<owner>/<repo>.git
cd <repo> && git checkout <branch>'
```

Install the toolchain for the project's stack (all available in Termux;
versions below verified on arm64, POCO F4):

```bash
python3 tools/termux-control/python/termux-adb exec 'apt-get update && apt-get install -y git gh termux-tools <stack packages>'
```

| Stack  | Packages                                          | Verified versions                          |
| ------ | ------------------------------------------------- | ------------------------------------------ |
| Rust   | `rust clang make cmake pkg-config python protobuf ca-certificates` | rustc/cargo 1.99.1 (`aarch64-linux-android`), clang 21.1.8, protoc 36.2 |
| JS/TS  | `bun` (pinned by most repos) or `nodejs`          | bun 1.4.4, nodejs 26.12.1                  |
| Python | `python`                                          | python 3.14.8                              |
| Go     | `golang`                                          | go 1.27.3                                  |

Then follow that repo's own docs to build and run. On phones start
resource-hungry builds conservatively (`CARGO_BUILD_JOBS=2` for Rust, one
job on low-memory phones) and keep Termux foregrounded — Android can stop
backgrounded Termux.

## Verify and list commands

`examples/verify.sh` checks the device environment (env, toolchain, native
Android compiler) and, when pointed at a project, that project's checkout and
smoke command. `examples/list-commands.sh` prints any CLI's help plus a
best-effort probe of its subcommands:

```bash
python3 tools/termux-control/python/termux-adb exec "$(cat .agents/skills/termux-dev/examples/verify.sh)"
python3 tools/termux-control/python/termux-adb exec "REPO_DIR=<repo> VERIFY_CMD='<smoke command>' EXPECT_ANDROID_HOST=1 $(cat .agents/skills/termux-dev/examples/verify.sh)"
python3 tools/termux-control/python/termux-adb exec "BIN=<binary path> $(cat .agents/skills/termux-dev/examples/list-commands.sh)"
```

`REPO_DIR` is relative to `$HOME`; `VERIFY_CMD` runs from the repo root and
must exit 0. With no subcommand arguments, `list-commands.sh` probes help
output for candidate subcommands automatically.

## Worked example: TikTools-app

Setup: Rust stack packages above, then in Termux
`git clone https://github.com/nglmercer/TikTools-app.git`, branch `remake`,
`export CARGO_BUILD_JOBS=2`, `bash scripts/termux/check.sh`,
`cargo build -p tiktools-cli --locked` (~7 min on-device). Verified:
`check.sh` passes, `system info` reports `AndroidOther` / `0.3.3`, sandbox
verify green (85/85 API coverage, 22/22 smoke):

```bash
python3 tools/termux-control/python/termux-adb exec "REPO_DIR=TikTools-app VERIFY_CMD='./target/debug/tiktools --json api verify --sandbox' EXPECT_ANDROID_HOST=1 $(cat .agents/skills/termux-dev/examples/verify.sh)"
python3 tools/termux-control/python/termux-adb exec "BIN=\$HOME/TikTools-app/target/debug/tiktools $(cat .agents/skills/termux-dev/examples/list-commands.sh)"
```

## Rules

- Never paste session cookies or other secrets into commands, scripts, or
  logs; read them interactively on-device instead.
- Never delete locks or sockets of a running on-device host; keep app homes
  identical across sessions.
- Report desktop-only capabilities (WebView, audio, hotkeys, notifications)
  as unavailable on Android, never as passed.
