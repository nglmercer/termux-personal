#!/usr/bin/env python3
"""Exercise the per-command static Linux binary adapter without Android or proot."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/compat-v2/termux-glibc-run"


class GlibcRunTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.prefix = self.root / "usr with space"
        (self.prefix / "bin").mkdir(parents=True)
        (self.prefix / "etc/tls").mkdir(parents=True)
        (self.prefix / "tmp").mkdir()
        self.resolver = self.prefix / "etc/resolv.conf"
        self.hosts = self.prefix / "etc/hosts"
        self.cert = self.prefix / "etc/tls/cert.pem"
        self.resolver.write_text("nameserver 8.8.8.8\n", encoding="ascii")
        self.hosts.write_text("127.0.0.1 localhost\n", encoding="ascii")
        self.cert.write_text("fixture ca\n", encoding="ascii")
        self.log = self.root / "proot-log.json"
        proot = self.prefix / "bin/proot"
        proot.write_text("#!" + sys.executable + "\n"
            "import json,os,sys\n"
            "with open(os.environ['FAKE_PROOT_LOG'], 'w') as output:\n"
            "    json.dump({'args': sys.argv[1:], 'ssl': os.environ.get('SSL_CERT_FILE'), "
            "'hasSsl': 'SSL_CERT_FILE' in os.environ}, output)\n"
            "sys.exit(int(os.environ.get('FAKE_PROOT_EXIT', '0')))\n", encoding="ascii")
        proot.chmod(0o700)
        self.program = self.root / "program with space"
        self.program.write_text("#!/bin/sh\nexit 0\n", encoding="ascii")
        self.program.chmod(0o700)
        self.env = dict(os.environ, PREFIX=str(self.prefix), FAKE_PROOT_LOG=str(self.log),
                        PATH="/usr/bin:/bin")
        self.env.pop("SSL_CERT_FILE", None)

    def tearDown(self):
        self.temp.cleanup()

    def run_adapter(self, *args, env=None):
        return subprocess.run(["/bin/sh", str(SCRIPT), *args], env=self.env if env is None else env,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)

    def invocation(self):
        with self.log.open(encoding="ascii") as data:
            value = json.load(data)
        args = list(value["args"])
        bindings = {}
        while args and args[0] == "-b":
            host, guest = args[1].split(":", 1)
            bindings[guest] = host
            args = args[2:]
        value["command"] = args
        value["bindings"] = bindings
        return value

    def test_maps_linux_files_and_preserves_command_arguments(self):
        arguments = ["literal space", "$(touch /not-executed)", "--json"]
        result = self.run_adapter("--", str(self.program), *arguments)
        self.assertEqual(0, result.returncode, result.stderr)
        value = self.invocation()
        self.assertEqual([str(self.program), *arguments], value["command"])
        self.assertEqual(str(self.resolver), value["bindings"]["/etc/resolv.conf"])
        self.assertEqual(str(self.hosts), value["bindings"]["/etc/hosts"])
        self.assertEqual(str(self.prefix / "tmp"), value["bindings"]["/tmp"])
        for guest in ("/etc/ssl/certs/ca-certificates.crt",
                      "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
                      "/etc/pki/tls/certs/ca-bundle.crt",
                      "/etc/ssl/ca-bundle.pem",
                      "/etc/pki/tls/cacert.pem",
                      "/etc/ssl/cert.pem"):
            self.assertEqual(str(self.cert), value["bindings"][guest])
        self.assertTrue(value["hasSsl"])
        self.assertEqual(str(self.cert), value["ssl"])
        self.assertNotIn("SSL_CERT_FILE", self.env)

    def test_resolves_command_through_absolute_path_entries(self):
        tools = self.root / "tools"
        tools.mkdir()
        tool = tools / "static-tool"
        tool.write_text("#!/bin/sh\nexit 0\n", encoding="ascii")
        tool.chmod(0o700)
        env = dict(self.env, PATH=str(tools) + ":/usr/bin:/bin")
        result = self.run_adapter("static-tool", "argument", env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([str(tool), "argument"], self.invocation()["command"])

    def test_caller_ca_file_including_empty_is_preserved_and_disables_bundle_mapping(self):
        for setting in ("/private/custom-ca.pem", ""):
            with self.subTest(setting=setting):
                if self.log.exists(): self.log.unlink()
                env = dict(self.env, SSL_CERT_FILE=setting)
                result = self.run_adapter(str(self.program), env=env)
                self.assertEqual(0, result.returncode, result.stderr)
                value = self.invocation()
                self.assertEqual(setting, value["ssl"])
                self.assertTrue(value["hasSsl"])
                self.assertNotIn("/etc/ssl/certs/ca-certificates.crt", value["bindings"])
                self.assertNotIn("/etc/ssl/cert.pem", value["bindings"])

    def test_missing_ca_requires_an_explicit_caller_alternative(self):
        self.cert.unlink()
        result = self.run_adapter(str(self.program))
        self.assertEqual(1, result.returncode)
        self.assertIn("CA bundle is missing or unreadable", result.stderr)
        self.assertFalse(self.log.exists())
        env = dict(self.env, SSL_CERT_FILE="/private/custom-ca.pem")
        result = self.run_adapter(str(self.program), env=env)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_required_files_or_proot_fail_before_running(self):
        cases = []
        cases.append((lambda: self.resolver.unlink(), "resolver configuration"))
        cases.append((lambda: self.hosts.unlink(), "hosts file"))
        cases.append((lambda: (self.prefix / "tmp").rmdir(), "temporary directory"))
        cases.append((lambda: (self.prefix / "bin/proot").unlink(), "proot is unavailable"))
        for mutate, message in cases:
            with self.subTest(message=message):
                self.tearDown()
                self.setUp()
                mutate()
                env = dict(self.env, PATH="")
                result = self.run_adapter(str(self.program), env=env)
                self.assertEqual(1, result.returncode)
                self.assertIn(message, result.stderr)
                self.assertFalse(self.log.exists())

    def test_rejects_invalid_input_without_running_proot(self):
        result = self.run_adapter()
        self.assertEqual(2, result.returncode)
        self.assertIn("Usage:", result.stderr)
        self.assertEqual(0, self.run_adapter("--help").returncode)
        self.assertEqual(2, self.run_adapter("--unknown").returncode)
        self.assertEqual(1, self.run_adapter("definitely-missing-command").returncode)
        self.program.chmod(0o600)
        result = self.run_adapter(str(self.program))
        self.assertEqual(1, result.returncode)
        self.assertIn("not an executable regular file", result.stderr)
        self.assertFalse(self.log.exists())

    def test_rejects_non_absolute_or_colon_prefix(self):
        for prefix in ("relative", "/tmp/has:colon"):
            with self.subTest(prefix=prefix):
                result = self.run_adapter(str(self.program), env=dict(self.env, PREFIX=prefix))
                self.assertEqual(1, result.returncode)
                self.assertIn("PREFIX", result.stderr)
                self.assertFalse(self.log.exists())

    def test_proot_exit_status_is_preserved(self):
        result = self.run_adapter(str(self.program), env=dict(self.env, FAKE_PROOT_EXIT="37"))
        self.assertEqual(37, result.returncode)


if __name__ == "__main__":
    unittest.main()
