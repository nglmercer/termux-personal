#!/usr/bin/env node
// Adaptive mobile automation helper (JS parity with python/mobile and sh/mobile).
// One command surface across ROMs; never guesses, always probes first.
import { spawnSync } from 'node:child_process';

const ADB_HELPER = 'tools/termux-control/js/termux-adb.mjs';
const RUNNER = 'com.termux.test/androidx.test.runner.AndroidJUnitRunner';
const TEST_CLASS = 'com.termux.app.control.AgentCommandInstrumentedTest';
const RESULT = '/sdcard/termux-agent/result.json';

function adb(serial, args) {
  return spawnSync('adb', [...(serial ? ['-s', serial] : []), ...args], { encoding: 'utf8' });
}
function sh(serial, script) {
  const result = adb(serial, ['shell', '-T', script]);
  return [result.status, ((result.stdout || '') + (result.stderr || '')).trim()];
}
function pickSerial(explicit) {
  if (explicit) return explicit;
  const output = adb(null, ['devices']).stdout || '';
  const devices = output.split('\n').slice(1).map(l => l.split(/\s+/)).filter(p => p[1] === 'device').map(p => p[0]);
  return devices.length === 1 ? devices[0] : null;
}
function probe(serial) {
  const report = { serial };
  let out;
  [, out] = sh(serial, 'getprop ro.product.model'); report.model = out;
  [, out] = sh(serial, 'getprop ro.build.version.release'); report.android = out;
  [, out] = sh(serial, 'getprop ro.debuggable'); report.debuggable = out === '1';
  [, out] = sh(serial, 'pm path com.termux'); report.termux_installed = out.startsWith('package:');
  const [code, runAs] = sh(serial, 'run-as com.termux id');
  report.run_as_works = code === 0 && runAs.includes('uid=');
  [, out] = sh(serial, 'am list instrumentation 2>/dev/null; pm path com.termux.test');
  report.instrumentation_available = out.includes('package:');
  report.path = report.run_as_works ? 'run-as' : report.instrumentation_available ? 'instrumented' : 'blocked-by-rom';
  return report;
}

const args = process.argv.slice(2);
const mode = args[0];
const serial = pickSerial(process.env.ADB_SERIAL);
if (!serial) { console.error('no authorized adb device found'); process.exit(3); }

if (mode === 'device') { console.log(serial); process.exit(0); }
if (mode === 'info') { console.log(JSON.stringify(probe(serial), null, 2)); process.exit(0); }
if (mode === 'exec') {
  const script = args.slice(1).join(' ');
  const report = probe(serial);
  if (report.path === 'run-as') {
    const result = spawnSync('node', [ADB_HELPER, '-s', serial, 'exec', script], { stdio: 'inherit' });
    process.exit(result.status ?? 1);
  }
  if (report.path === 'instrumented') {
    const encoded = Buffer.from(script, 'utf8').toString('base64').replace(/=+$/, '');
    adb(serial, ['shell', `rm -f ${RESULT}`]);
    const run = adb(serial, ['shell', `am instrument -w -r -e class ${TEST_CLASS} -e cmd ${encoded} ${RUNNER}`]);
    const result = adb(serial, ['shell', `cat ${RESULT}`]);
    if (result.status === 0 && result.stdout.trim()) {
      console.log(result.stdout.trim());
      const parsed = JSON.parse(result.stdout.trim());
      process.exit(parsed.ok ? 0 : 1);
    }
    console.error((run.stdout || '') + (run.stderr || ''));
    process.exit(1);
  }
  console.error(`automatic exec unavailable on ${report.model} (path=${report.path})`);
  process.exit(2);
}
console.error('usage: mobile.mjs <info|device|exec> [script]');
process.exit(2);
