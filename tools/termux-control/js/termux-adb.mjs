#!/usr/bin/env node
// termux-adb.mjs -- control a debuggable Termux installation through an
// authorized ADB device. Node.js port of python/termux-adb with the same
// CLI surface. Runs on Node.js and Bun, no dependencies.
//
//   termux-adb.mjs [-s SERIAL] shell                      # interactive shell
//   termux-adb.mjs [-s SERIAL] exec 'pwd; id'             # run and wait
//   termux-adb.mjs [-s SERIAL] exec < script.sh           # stdin script
//   termux-adb.mjs [-s SERIAL] run [--session ID] [--name N] [--focus|--open] [script]
//   termux-adb.mjs [-s SERIAL] {focus|open|snapshot} <session>
//   termux-adb.mjs [-s SERIAL] ipc <client args...>
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';

const ROOT = '/data/data/com.termux/files';
const ENV = [
  'HOME=' + ROOT + '/home', 'PREFIX=' + ROOT + '/usr',
  'PATH=' + ROOT + '/usr/bin:/system/bin', 'TMPDIR=' + ROOT + '/usr/tmp', 'LANG=C.UTF-8',
];
const CLIENT = ['run-as', 'com.termux', ROOT + '/termux-control-adb', '--socket', ROOT + '/control-ipc/control.sock'];

// Minimal shlex.quote: safe chars pass through, the rest is single-quoted.
const q = (s) => (/^[A-Za-z0-9_@%+=:,./-]+$/.test(s) ? s : `'${s.replace(/'/g, `'\\''`)}'`);

function fail(msg) { process.stderr.write(String(msg) + '\n'); process.exit(1); }
try { spawnSync('adb', ['version'], { stdio: 'ignore' }); } catch { fail('adb was not found in PATH'); }

const raw = process.argv.slice(2);
let serial = null;
if (raw[0] === '-s') { serial = raw[1] ?? fail('missing serial'); raw.splice(0, 2); }
const adb = (...args) => ['adb', ...(serial ? ['-s', serial] : []), ...args];
const mode = raw[0] ?? fail('usage: termux-adb.mjs [-s SERIAL] {shell|run|exec|focus|open|snapshot|ipc} ...');

const remote = (interactive) => adb('shell', interactive ? '-t' : '-T',
  ['run-as', 'com.termux', 'env', ...ENV, ROOT + '/usr/bin/bash', '-c',
   interactive ? 'cd "$HOME" && exec "$PREFIX/bin/bash" -l' : 'cd "$HOME" && exec "$PREFIX/bin/bash" -s']
    .map(q).join(' '));

function runAdb(args, input, inherit) {
  const r = spawnSync('adb', args.slice(1), {
    input, stdio: inherit ? 'inherit' : ['pipe', 'pipe', 'inherit'],
  });
  if (r.error) fail('adb was not found in PATH');
  return r;
}

const shellBlob = (parts) => adb('shell', '-T', parts.map(q).join(' '));

function callIpc(parts, data) {
  const r = runAdb(shellBlob([...CLIENT, ...parts]), data, false);
  if (r.status !== 0) process.exit(r.status ?? 1);
  try { return JSON.parse(r.stdout.toString()).result; }
  catch { fail('Invalid response from termux-control'); }
}

function resolve(selector) {
  const sessions = callIpc(['list']).sessions;
  const matches = sessions.filter((s) => s.id === selector || s.name === selector);
  if (matches.length !== 1) fail('Session not found or name is ambiguous; use its ID');
  return matches[0].id;
}

function focus(id, openApp) {
  callIpc(['focus', id]);
  if (openApp) {
    const r = runAdb(adb('shell', 'am', 'start', '-n', 'com.termux/.app.TermuxActivity'), undefined, false);
    if (r.status !== 0) process.exit(r.status ?? 1);
  }
}

function readStdin() {
  if (process.stdin.isTTY) return undefined;
  const data = fs.readFileSync(0, 'utf8');
  return data.length ? data : undefined;
}

if (mode === 'ipc') {
  const rest = raw.slice(1);
  const r = runAdb(shellBlob(CLIENT.concat(rest)), undefined, rest.includes('attach'));
  process.stdout.write(r.stdout ?? Buffer.alloc(0));
  process.exit(r.status ?? 1);
}
if (mode === 'shell') {
  const r = runAdb(remote(true), undefined, true);
  process.exit(r.status ?? 1);
}
if (mode === 'exec') {
  const script = raw[1] !== undefined ? raw[1] + '\n' : readStdin();
  const r = runAdb(remote(false), script, script === undefined);
  process.stdout.write(r.stdout ?? Buffer.alloc(0));
  process.exit(r.status ?? 1);
}
if (mode === 'run' || mode === 'focus' || mode === 'open' || mode === 'snapshot') {
  if (mode !== 'run') {
    const id = resolve(raw[1] ?? fail('missing session'));
    if (mode === 'snapshot') process.stdout.write(callIpc(['snapshot', id]).text);
    else focus(id, mode === 'open');
    process.exit(0);
  }
  let session = null, name = 'adb', doFocus = false, doOpen = false;
  const rest = [];
  for (let i = 1; i < raw.length; i++) {
    if (raw[i] === '--session') session = raw[++i];
    else if (raw[i] === '--name') name = raw[++i];
    else if (raw[i] === '--focus') doFocus = true;
    else if (raw[i] === '--open') doOpen = true;
    else rest.push(raw[i]);
  }
  let script = rest.length ? rest.join(' ') : undefined;
  if (script === undefined) {
    const stdin = readStdin();
    if (stdin === undefined) fail('Empty script');
    script = stdin;
  }
  if (!script) fail('Empty script');
  const encoded = Buffer.from(script).toString('base64');
  const payload = 'printf %s ' + encoded + ' | base64 -d | bash\n';
  if (payload.length > 3000) fail('Script is too long for a terminal input line; use exec for larger scripts');
  const id = session ? resolve(session) : callIpc(['create', '80', '24', name]).id;
  process.stderr.write('Session: ' + id + '\n');
  if (doFocus || doOpen) focus(id, doOpen);
  callIpc(['write', id], payload);
  process.exit(0);
}
fail('usage: termux-adb.mjs [-s SERIAL] {shell|run|exec|focus|open|snapshot|ipc} ...');
