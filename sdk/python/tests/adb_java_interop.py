"""Python SDK through a simulated ADB command and native bridge into the Java server."""
import os
import select
import subprocess
import sys
import tempfile
from pathlib import Path
from termux_personal import TermuxClient

with tempfile.TemporaryDirectory(prefix="termux-py-adb-") as directory:
    path = str(Path(directory) / "socket")
    adb = Path(directory) / "adb"
    adb.write_text('''#!/usr/bin/env python3
import os, shlex, sys
args = sys.argv[1:]
assert args[:4] == ['-s', 'test-serial', 'shell', '-T']
remote = shlex.split(args[4])
assert remote[:2] == ['run-as', 'com.termux'] and remote[-1] == 'bridge'
os.execv(os.environ['MOCK_BRIDGE'], [os.environ['MOCK_BRIDGE'], '--socket', remote[-2], 'bridge'])
''')
    adb.chmod(0o700)
    server = subprocess.Popen(["java", "-cp", sys.argv[1], "com.termux.app.control.ipc.HostRpcSocketSmoke", path],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    previous = os.environ.get("MOCK_BRIDGE")
    os.environ["MOCK_BRIDGE"] = os.path.abspath(sys.argv[2])
    try:
        ready, _, _ = select.select([server.stdout], [], [], 10)
        assert ready and server.stdout.readline().strip() == "READY"
        with TermuxClient.connect_adb(adb_path=str(adb), socket_path=path, serial="test-serial") as client:
            assert client.describe()["protocolVersion"] == 1
            result, _ = client.request("session.write", {"id": "session-1", "length": 4}, binary=b"\x00\xff\x1b\n")
            assert result == {"ok": True}
        stdout, stderr = server.communicate(timeout=10)
        assert server.returncode == 0 and "OK" in stdout, stderr
    finally:
        if previous is None:
            os.environ.pop("MOCK_BRIDGE", None)
        else:
            os.environ["MOCK_BRIDGE"] = previous
        if server.poll() is None:
            server.kill()
            server.communicate()
print("Python ADB bridge and Java v1 interoperability passed")
