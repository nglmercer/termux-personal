#!/usr/bin/env python3
"""Host compatibility tests for the existing ADB adapter entry points."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
ADAPTERS = [
    ["python3", str(ROOT / "tools/termux-control/python/termux-adb")],
    ["node", str(ROOT / "tools/termux-control/js/termux-adb.mjs")],
    ["bash", str(ROOT / "tools/termux-control/sh/termux-adb.sh")],
]
FAKE_ADB = '''#!/usr/bin/env python3
import json, os, sys
if sys.argv[1:] == ['version']:
    sys.exit(0)
with open(os.environ['ADB_CAPTURE'], 'w') as capture:
    json.dump({'args': sys.argv[1:], 'stdin': sys.stdin.read()}, capture)
sys.exit(int(os.environ.get('ADB_STATUS', '0')))
'''


class AdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="termux-adb-test-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        fake = self.directory / "adb"
        fake.write_text(FAKE_ADB)
        fake.chmod(0o700)
        self.capture = self.directory / "capture.json"
        self.env = dict(os.environ, PATH=str(self.directory) + os.pathsep + os.environ["PATH"], ADB_CAPTURE=str(self.capture))

    def invoke(self, adapter, args, data=None, status=0):
        env = dict(self.env, ADB_STATUS=str(status))
        result = subprocess.run(adapter + args, input=data, text=True, capture_output=True, env=env)
        self.assertEqual(status, result.returncode, result.stderr)
        return json.loads(self.capture.read_text())

    def test_exec_preserves_script_and_target(self):
        script = "printf '%s\\n' \"$HOME\"; echo 'quoted value'"
        for adapter in ADAPTERS:
            with self.subTest(adapter=adapter):
                record = self.invoke(adapter, ["-s", "serial-1", "exec", script])
                self.assertEqual(script + "\n", record["stdin"])
                self.assertEqual(["-s", "serial-1", "shell", "-T"], record["args"][:4])
                remote = shlex.split(record["args"][4])
                self.assertEqual(["run-as", "com.termux", "env"], remote[:3])
                self.assertIn("HOME=/data/data/com.termux/files/home", remote)
                self.assertEqual('cd "$HOME" && exec "$PREFIX/bin/bash" -s', remote[-1])

    def test_exec_stdin_and_exit_status(self):
        script = "echo first\nprintf '%s' second\n"
        for adapter in ADAPTERS:
            with self.subTest(adapter=adapter):
                record = self.invoke(adapter, ["exec"], data=script, status=7)
                self.assertEqual(script, record["stdin"])

    def test_ipc_arguments_preserve_shell_quoting(self):
        for adapter in ADAPTERS[:2]:
            with self.subTest(adapter=adapter):
                record = self.invoke(adapter, ["ipc", "rename", "session id", "a'b"])
                self.assertEqual(["shell", "-T"], record["args"][:2])
                remote = shlex.split(record["args"][2])
                self.assertEqual(["rename", "session id", "a'b"], remote[-3:])


if __name__ == "__main__":
    unittest.main()
