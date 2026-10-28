"""Python SDK against the real Java protocol loop; host Unix sockets only."""
import select
import subprocess
import sys
import tempfile
from pathlib import Path

from termux_personal import TermuxClient

with tempfile.TemporaryDirectory(prefix="termux-python-java-") as directory:
    path = str(Path(directory) / "socket")
    server = subprocess.Popen(["java", "-cp", sys.argv[1], "com.termux.app.control.ipc.HostRpcSocketSmoke", path],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        ready, _, _ = select.select([server.stdout], [], [], 10)
        assert ready and server.stdout.readline().strip() == "READY"
        with TermuxClient.connect(path) as client:
            description = client.describe()
            assert description["protocolVersion"] == 1
            assert "session.write" in [method["name"] for method in description["methods"]]
            client.sessions.write("session-1", b"\x00\xff\x1b\n")
        stdout, stderr = server.communicate(timeout=10)
        assert server.returncode == 0 and "OK" in stdout, stderr
    finally:
        if server.poll() is None:
            server.kill()
            server.communicate()
print("Python SDK and Java v1 protocol interoperability passed")
