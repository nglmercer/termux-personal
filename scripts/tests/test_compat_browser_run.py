#!/usr/bin/env python3
"""Exercise explicit, per-process browser compatibility without launching Android apps."""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/compat-v2/termux-compat"


class BrowserRunTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name) / "files with space"
        self.shim = self.root / "compat-v1" / "termux-open-url"
        self.shim.parent.mkdir(parents=True)
        self.shim.write_text("#!" + sys.executable + "\nimport json,os,sys\n"
                             "print(json.dumps({'browser':os.environ.get('BROWSER'), 'path':os.environ.get('PATH'), 'args':sys.argv[1:]}))\n")
        self.shim.chmod(0o700)
        prefix = self.root / "usr"
        (prefix / "bin").mkdir(parents=True)
        self.sha = prefix / "bin" / "sha256sum"
        self.sha.write_text("#!/bin/sh\nprintf '%s  %s\\n' eef8f41780ea128de23320dfd591b5839267b1812ff33e2c16682ef276e21a28 \"$1\"\n")
        self.sha.chmod(0o700)
        self.env = dict(os.environ, TERMUX_APP__FILES_DIR=str(self.root), PREFIX=str(prefix), PATH="/usr/bin:/bin")
        self.env.pop("BROWSER", None)

    def tearDown(self):
        self.temp.cleanup()

    def run_mode(self, *args, env=None):
        return subprocess.run(["/bin/sh", str(SCRIPT), "browser-run", *args], env=self.env if env is None else env,
                              text=True, capture_output=True, check=False)

    def test_sets_browser_only_for_child_and_preserves_arguments(self):
        args = ["literal space", "$(touch /not-executed)", "https://example.com/?state=private"]
        result = self.run_mode("--", str(self.shim), *args)
        self.assertEqual(0, result.returncode)
        value = json.loads(result.stdout)
        self.assertEqual(str(self.shim), value["browser"])
        self.assertEqual("/usr/bin:/bin", value["path"])
        self.assertEqual(args, value["args"])
        self.assertNotIn("BROWSER", self.env)

    def test_explicit_path_prioritizes_wrapper_for_relative_command(self):
        env = dict(self.env, BROWSER="custom browser", PATH="/usr/bin:" + str(self.shim.parent) + ":/bin")
        result = self.run_mode("--path", "--", "termux-open-url", "https://example.com/", env=env)
        self.assertEqual(0, result.returncode)
        value = json.loads(result.stdout)
        self.assertEqual("custom browser", value["browser"])
        self.assertEqual(str(self.shim.parent) + ":" + env["PATH"], value["path"])
        self.assertEqual(["https://example.com/"], value["args"])
        self.assertEqual("/usr/bin:" + str(self.shim.parent) + ":/bin", env["PATH"])

    def test_existing_browser_including_empty_is_not_overridden(self):
        for browser in ("custom-browser", ""):
            env = dict(self.env, BROWSER=browser, TERMUX_APP__FILES_DIR="relative")
            result = self.run_mode("--", str(self.shim), env=env)
            self.assertEqual(0, result.returncode)
            self.assertEqual(browser, json.loads(result.stdout)["browser"])

    def test_missing_wrapper_or_invalid_arguments_fail_without_running_command(self):
        self.shim.unlink()
        result = self.run_mode("--", "/bin/true")
        self.assertEqual(1, result.returncode)
        self.assertIn("wrapper unavailable", result.stderr)
        self.assertEqual(2, self.run_mode("--").returncode)
        self.assertEqual(2, self.run_mode("/bin/true").returncode)
        self.assertEqual(1, self.run_mode("--path", "--", "/bin/true", env=dict(self.env, BROWSER="custom")).returncode)

    def test_child_exit_status_is_preserved(self):
        result = self.run_mode("--", "/bin/sh", "-c", "exit 37")
        self.assertEqual(37, result.returncode)

    def test_conflicting_or_unverifiable_wrapper_is_never_used(self):
        self.sha.write_text("#!/bin/sh\nprintf '%064d  file\\n' 0\n")
        result = self.run_mode("--", "/bin/true")
        self.assertEqual(1, result.returncode)
        self.assertIn("conflicts", result.stderr)
        self.sha.unlink()
        result = self.run_mode("--", "/bin/true")
        self.assertEqual(1, result.returncode)
        self.assertIn("sha256sum is unavailable", result.stderr)

    @unittest.skipUnless(shutil.which("sha256sum"), "sha256sum required")
    def test_real_digest_accepts_packaged_v1_wrapper(self):
        self.shim.write_bytes((SCRIPT.parents[1] / "compat-v1" / "termux-open-url").read_bytes())
        self.shim.chmod(0o700)
        self.sha.unlink()
        self.sha.symlink_to(shutil.which("sha256sum"))
        self.assertEqual(0, self.run_mode("--", "/bin/true").returncode)
        self.shim.write_bytes(self.shim.read_bytes() + b"# changed\n")
        self.assertEqual(1, self.run_mode("--", "/bin/true").returncode)


if __name__ == "__main__":
    unittest.main()
