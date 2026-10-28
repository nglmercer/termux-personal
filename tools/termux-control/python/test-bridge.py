#!/usr/bin/env python3
"""Host-only bounded binary bridge and half-close test."""
import os
import socket
import subprocess
import sys
import tempfile
import threading

binary = os.path.abspath(sys.argv[1])
payload = bytes(range(256)) * 2048
failures = []
with tempfile.TemporaryDirectory(prefix="termux-bridge-") as directory:
    path = os.path.join(directory, "socket")
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.bind(path)
    listener.listen(1)

    def server():
        try:
            with listener.accept()[0] as connection:
                received = bytearray()
                while True:
                    chunk = connection.recv(4096)
                    if not chunk:
                        break
                    received.extend(chunk)
                    connection.sendall(chunk)
                assert received == payload
        except BaseException as exc:
            failures.append(exc)

    worker = threading.Thread(target=server)
    worker.start()
    result = subprocess.run([binary, "--socket", path, "bridge"], input=payload, capture_output=True, timeout=15)
    worker.join(5)
    listener.close()
    assert not worker.is_alive()
    if failures:
        raise failures[0]
    assert result.returncode == 0, result.stderr
    assert result.stdout == payload
print("native bridge binary streaming and half-close passed")
