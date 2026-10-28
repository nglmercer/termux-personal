#!/usr/bin/env python3
"""Host-only CLI-to-Java protocol check; does not test Android LocalSocket."""
import json
import os
import select
import subprocess
import sys
import tempfile

client = os.path.abspath(sys.argv[1])
classpath = sys.argv[2]
base = os.path.abspath(sys.argv[3])
os.makedirs(base, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="tc-java-", dir=base) as directory:
    path = os.path.join(directory, "socket")
    server = subprocess.Popen(["java", "-cp", classpath, "com.termux.app.control.ipc.HostRpcSocketSmoke", path],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        ready, _, _ = select.select([server.stdout], [], [], 10)
        assert ready and server.stdout.readline().strip() == "READY", "server did not start"
        result = subprocess.run([client, "--socket", path, "write", "session-1"],
                                input=bytes([0, 255, 27, 10]), capture_output=True, timeout=10)
        assert result.returncode == 0, result.stderr
        assert json.loads(result.stdout)["result"]["ok"] is True
        stdout, stderr = server.communicate(timeout=10)
        assert server.returncode == 0 and "OK" in stdout, stderr
    finally:
        if server.poll() is None:
            server.kill()
            server.communicate()
print("native client and Java binary protocol interoperability passed")
