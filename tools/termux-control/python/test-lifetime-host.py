#!/usr/bin/env python3
"""Host socket test for >1000 requests with real worker-side throttling."""
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time

classpath, root = sys.argv[1:3]
with tempfile.TemporaryDirectory(prefix='life-', dir=root) as directory:
    path = os.path.join(directory, 's')
    server = subprocess.Popen(['java', '-cp', classpath, 'com.termux.app.control.ipc.HostLifetimeSocketSmoke', path],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        assert server.stdout.readline().strip() == 'READY'
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
            conn.settimeout(10)
            conn.connect(path)
            def request(i, method, params):
                body = json.dumps({'jsonrpc':'2.0', 'id':i, 'method':method, 'params':params}).encode()
                conn.sendall(struct.pack('>I', len(body)) + body)
                def exact(n):
                    data = bytearray()
                    while len(data) < n:
                        part = conn.recv(n - len(data))
                        if not part: raise AssertionError('connection ended')
                        data.extend(part)
                    return bytes(data)
                size = struct.unpack('>I', exact(4))[0]
                return json.loads(exact(size))
            assert request(1, 'system.hello', {'version':1})['result']['version'] == 1
            start = time.monotonic()
            for i in range(1005): assert request(2, 'session.list', {})['result']['sessions'] == []
            elapsed = time.monotonic() - start
            assert elapsed >= 10, f'rate limiter did not apply: {elapsed}'
        out, err = server.communicate(timeout=10)
        assert server.returncode == 0 and 'LIFETIME OK' in out, err
        print('host connection processed 1005 requests with rate limiting')
    finally:
        if server.poll() is None: server.kill(); server.wait()
