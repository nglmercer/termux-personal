#!/usr/bin/env python3
"""Host native client to Java protocol loop; not Android or a real PTY."""
import os
import select
import subprocess
import sys
import tempfile

classpath, root, binary = sys.argv[1:4]
with tempfile.TemporaryDirectory(prefix="f-", dir=root) as directory:
    path = os.path.join(directory, "s")
    server = subprocess.Popen(["java", "-cp", classpath, "com.termux.app.control.ipc.HostOutputSocketSmoke", path],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    client = None
    try:
        assert server.stdout.readline().strip() == "READY"
        client = subprocess.Popen([binary, "--socket", path, "follow", "s"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        ready, _, _ = select.select([client.stdout], [], [], 5)
        assert ready and os.read(client.stdout.fileno(), 4) == bytes([0, 255, 27, 10])
        client.terminate(); client.wait(timeout=5)
        server.wait(timeout=5)
        assert server.returncode == 0, server.stderr.read()
        print("native output reader and Java host socket passed")
    finally:
        if client is not None and client.poll() is None:
            client.kill(); client.wait()
        if server.poll() is None:
            server.kill(); server.wait()
