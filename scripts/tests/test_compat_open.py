#!/usr/bin/env python3
"""Host contract test for the installed v1 wrapper; no Android activity is launched."""
import os
import pathlib
import subprocess
import tempfile
import unittest

WRAPPER = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/compat-v1/termux-open-url"


class WrapperTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.temp.name)
        (root / "bin").mkdir()
        for name in ("termux-open", "termux-open-url"):
            command = root / "bin" / name
            command.write_text('#!/bin/sh\nprintf "%s\\n" "' + name + '" "$TERMUX__USER_ID" "$@"\n', encoding="utf-8")
            command.chmod(0o700)
        self.env = dict(os.environ, PREFIX=str(root), TERMUX__USER_ID="10")

    def tearDown(self):
        self.temp.cleanup()

    def call(self, *args):
        return subprocess.check_output(["/bin/sh", str(WRAPPER), *args], env=self.env, text=True).splitlines()

    def test_web_url_uses_existing_receiver_command_with_exact_argument(self):
        url = "https://example.com/auth?state=a&code=b"
        self.assertEqual(["termux-open", "10", "--view", "--", url], self.call(url))
        self.assertEqual(["termux-open", "10", "--view", "--", "HTTP://example.com/"], self.call("HTTP://example.com/"))

    def test_missing_receiver_command_falls_back_to_upstream(self):
        (pathlib.Path(self.env["PREFIX"]) / "bin" / "termux-open").unlink()
        self.assertEqual(["termux-open-url", "10", "https://example.com/"], self.call("https://example.com/"))

    def test_explicit_component_and_non_web_keep_upstream_semantics(self):
        self.assertEqual(["termux-open-url", "10", "https://example.com/", "org.browser"], self.call("https://example.com/", "org.browser"))
        self.assertEqual(["termux-open-url", "10", "mailto:foo@example.com"], self.call("mailto:foo@example.com"))
        self.assertEqual(["termux-open-url", "10"], self.call())


if __name__ == "__main__":
    unittest.main()
