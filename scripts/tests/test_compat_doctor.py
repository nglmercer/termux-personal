#!/usr/bin/env python3
"""Exercise CLI diagnostics without contacting the network or exposing environment values."""
import http.server
import json
import os
import pathlib
import shutil
import ssl
import subprocess
import tempfile
import threading
import unittest

DOCTOR = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/compat-v1/termux-compat"


class DoctorTest(unittest.TestCase):
    DOCTOR = DOCTOR
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.tmp.name)
        (root / "bin").mkdir()
        (root / "etc/tls").mkdir(parents=True)
        (root / "etc/tls/cert.pem").write_text("fixture", encoding="ascii")
        self.command(root, "timeout", '#!/bin/sh\nshift 3\nexec "$@"\n')
        self.command(root, "curl", '#!/bin/sh\nprintf "%s\\n" "$@" > "$FAKE_ARGS"\nprintf "%s" "$FAKE_CURL_OUT"\nprintf "%s\\n" "password=secret" >&2\nexit "$FAKE_CURL_EXIT"\n')
        self.command(root, "getent", '#!/bin/sh\necho "access_token=secret"\nexit "$FAKE_DNS_EXIT"\n')
        self.env = dict(os.environ, PREFIX=str(root), FAKE_ARGS=str(root / "args"),
                        FAKE_CURL_OUT="200", FAKE_CURL_EXIT="0", FAKE_DNS_EXIT="0",
                        HTTPS_PROXY="http://user:secret@example.invalid:8080", SSL_CERT_FILE="/private/secret.pem")

    @staticmethod
    def command(root, name, content):
        executable = root / "bin" / name
        executable.write_text(content, encoding="ascii")
        executable.chmod(0o700)

    def tearDown(self):
        self.tmp.cleanup()

    def run_doctor(self, *args, **extra):
        return subprocess.run(["/bin/sh", str(self.DOCTOR), *args], env=dict(self.env, **extra),
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)

    def test_json_is_sanitized_and_tls_verification_remains_enabled(self):
        result = self.run_doctor("doctor", "--json", FAKE_CURL_OUT="403")
        self.assertEqual(0, result.returncode)
        self.assertEqual({"dns": "RESOLVED", "https": "HTTP_RESPONSE", "httpStatus": 403, "caBundle": "READABLE"}, json.loads(result.stdout))
        self.assertNotIn("secret", result.stdout + result.stderr)
        args = pathlib.Path(self.env["FAKE_ARGS"]).read_text().splitlines()
        self.assertEqual("-q", args[0])
        self.assertIn("--no-insecure", args)
        self.assertIn("--no-proxy-insecure", args)
        self.assertIn("--max-time", args)
        self.assertNotIn("--location", args)

    def test_failure_layers_do_not_expose_command_output(self):
        for code, expected in [(5, "PROXY_FAILURE"), (6, "DNS_FAILURE"), (7, "CONNECT_FAILURE"),
                               (28, "TIMEOUT_OR_KILLED"), (35, "TLS_HANDSHAKE_FAILURE"),
                               (60, "TLS_CERTIFICATE_FAILURE"), (77, "CA_BUNDLE_ERROR")]:
            with self.subTest(code=code):
                result = self.run_doctor("doctor", "--json", FAKE_CURL_OUT="token=secret", FAKE_CURL_EXIT=str(code), FAKE_DNS_EXIT="2")
                value = json.loads(result.stdout)
                self.assertEqual(expected, value["https"])
                self.assertEqual("LOOKUP_FAILED", value["dns"])
                self.assertIsNone(value["httpStatus"])
                self.assertNotIn("secret", result.stdout + result.stderr)

    def test_missing_tools_are_reported_without_a_false_pass(self):
        (pathlib.Path(self.env["PREFIX"]) / "bin/getent").unlink()
        (pathlib.Path(self.env["PREFIX"]) / "bin/curl").unlink()
        value = json.loads(self.run_doctor("doctor", "--json").stdout)
        self.assertEqual("UNAVAILABLE", value["dns"])
        self.assertEqual("UNAVAILABLE", value["https"])

    @unittest.skipUnless(shutil.which("curl") and shutil.which("openssl"), "curl and openssl required")
    def test_real_local_https_trust_and_certificate_failure(self):
        root = pathlib.Path(self.env["PREFIX"])
        openssl = shutil.which("openssl")
        for name in ("server", "other"):
            subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                            "-keyout", str(root / (name + ".key")), "-out", str(root / (name + ".pem")),
                            "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost", "-days", "1"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_HEAD(self):
                self.send_response(403)
                self.end_headers()
            def log_message(self, *args):
                pass

        class Server(http.server.ThreadingHTTPServer):
            def handle_error(self, request, client_address):
                # A peer rejecting a test certificate can close during the TLS handshake.
                pass

        server = Server(("127.0.0.1", 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(root / "server.pem", root / "server.key")
        server.socket = context.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            # This test-only wrapper substitutes the fixed public URL with the local server.
            wrapper = root / "bin/curl"
            wrapper.write_text("#!/usr/bin/python3\nimport os,sys\n"
                + "args=[('https://localhost:%d/' if a == 'https://example.com/' else a) for a in sys.argv[1:]]\n" % server.server_port
                + "os.execv(%r, [%r] + args)\n" % (shutil.which("curl"), shutil.which("curl")), encoding="ascii")
            wrapper.chmod(0o700)
            keylog = root / "unexpected-ssl-keys"
            common = dict(NO_PROXY="localhost,127.0.0.1", no_proxy="localhost,127.0.0.1", SSL_CERT_FILE="", SSLKEYLOGFILE=str(keylog))
            trusted = self.run_doctor("doctor", "--json", CURL_CA_BUNDLE=str(root / "server.pem"), **common)
            value = json.loads(trusted.stdout)
            self.assertEqual("HTTP_RESPONSE", value["https"])
            self.assertEqual(403, value["httpStatus"])
            untrusted = self.run_doctor("doctor", "--json", CURL_CA_BUNDLE=str(root / "other.pem"), **common)
            self.assertEqual("TLS_CERTIFICATE_FAILURE", json.loads(untrusted.stdout)["https"])
            self.assertFalse(keylog.exists())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_browser_value_and_bad_arguments_are_not_disclosed(self):
        result = self.run_doctor("browser", BROWSER="secret-browser-value")
        self.assertIn("BROWSER: SET", result.stdout)
        self.assertNotIn("secret", result.stdout)
        self.assertEqual(2, self.run_doctor("network", "extra").returncode)


class DoctorV2Test(DoctorTest):
    DOCTOR = DOCTOR.parents[1] / "compat-v2" / "termux-compat"


if __name__ == "__main__":
    unittest.main()
