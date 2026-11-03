#!/usr/bin/env python3
"""Host checks for the opt-in runtime wrapper; Android execution is not exercised."""
import os
import pathlib
import subprocess
import tempfile
import unittest

WRAPPER = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/compat-v1/termux-run-clean"


class RuntimeAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.prefix = pathlib.Path(self.temp.name)
        (self.prefix / "custom1.so").touch()
        self.shim = str(self.prefix / "shim[1].so")
        self.custom = str(self.prefix / "custom[1].so")
        self.exec_preload = str(self.prefix / "lib/libtermux-exec-ld-preload.so")
        self.env = dict(os.environ, PREFIX=str(self.prefix), TERMUX_GLIBC_EXEC=self.shim)
        self.env.pop("LD_PRELOAD", None)

    def tearDown(self):
        self.temp.cleanup()

    def call(self, preload, *args):
        env = self.env.copy()
        if preload is not None:
            env["LD_PRELOAD"] = preload
        return subprocess.run(["/bin/sh", str(WRAPPER), *args], env=env, text=True, capture_output=True)

    def test_removes_only_recognized_entries_and_preserves_arguments(self):
        preload = f"{self.shim} {self.custom}:{self.exec_preload}"
        command = 'printf "%s|%s|%s" "${LD_PRELOAD-unset}" "$1" "$2"'
        result = self.call(preload, "/bin/sh", "-c", command, "test", "a b", "*literal*")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"{self.custom}|a b|*literal*", result.stdout)

    def test_empty_result_is_unset_and_unrecognized_preload_is_unchanged(self):
        command = 'printf "%s" "${LD_PRELOAD-unset}"'
        self.assertEqual("unset", self.call(f"{self.shim}:{self.exec_preload}", "/bin/sh", "-c", command).stdout)
        self.assertEqual(self.custom, self.call(self.custom, "/bin/sh", "-c", command).stdout)
        self.assertEqual("unset", self.call(None, "/bin/sh", "-c", command).stdout)
        self.assertEqual("", self.call("", "/bin/sh", "-c", command).stdout)

    def test_exact_matches_only_and_all_bundled_variants(self):
        directory = self.prefix / "lib"
        direct = str(directory / "libtermux-exec-direct-ld-preload.so")
        linker = str(directory / "libtermux-exec-linker-ld-preload.so")
        decoy = self.exec_preload + ".custom"
        command = 'printf "%s" "${LD_PRELOAD-unset}"'
        result = self.call(f"{decoy}:{direct} {linker}:{self.custom}:{self.custom}", "/bin/sh", "-c", command)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"{decoy}:{self.custom}:{self.custom}", result.stdout)

    def test_requires_absolute_path_and_preserves_exit_status(self):
        self.assertEqual(2, self.call(None).returncode)
        self.assertEqual(2, self.call(None, "relative").returncode)
        self.assertEqual(23, self.call(None, "/bin/sh", "-c", "exit 23").returncode)


if __name__ == "__main__":
    unittest.main()
