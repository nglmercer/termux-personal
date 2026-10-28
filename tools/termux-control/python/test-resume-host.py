#!/usr/bin/env python3
"""Host Unix socket reconnect test with fake service and credentials."""
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile

classpath, root = sys.argv[1:3]
with tempfile.TemporaryDirectory(prefix='resume-', dir=root) as directory:
    path = os.path.join(directory, 's')
    server = subprocess.Popen(['java', '-cp', classpath, 'com.termux.app.control.ipc.HostResumeSocketSmoke', path],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        assert server.stdout.readline().strip() == 'READY'
        def exact(conn, n):
            data = bytearray()
            while len(data) < n:
                chunk = conn.recv(n-len(data))
                if not chunk: raise AssertionError('truncated frame')
                data.extend(chunk)
            return bytes(data)
        def frame(conn):
            n = struct.unpack('>I', exact(conn, 4))[0]
            assert 0 < n <= 65536
            return exact(conn, n)
        def request(conn, i, method, params):
            body = json.dumps({'jsonrpc':'2.0','id':i,'method':method,'params':params}, separators=(',',':')).encode()
            conn.sendall(struct.pack('>I', len(body)) + body)
            response = json.loads(frame(conn)); assert response['id'] == i and 'result' in response
            return response['result']
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as first:
            first.settimeout(5); first.connect(path)
            assert request(first, 1, 'system.hello', {'version':1})['version'] == 1
            token = request(first, 2, 'session.output.subscribe', {'id':'s','resumable':True})['resumeToken']
            result = request(first, 3, 'session.output.read', {'id':'s','offset':0,'limit':8})
            assert result['length'] == 5 and result['nextOffset'] == 5 and frame(first) == b'FIRST'
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as second:
            second.settimeout(5); second.connect(path)
            assert request(second, 1, 'system.hello', {'version':1})['version'] == 1
            assert request(second, 2, 'session.output.resume', {'id':'s','token':token})['ok']
            result = request(second, 3, 'session.output.read', {'id':'s','offset':5,'limit':8})
            assert result['length'] == 6 and result['nextOffset'] == 11 and frame(second) == b'SECOND'
            assert request(second, 4, 'session.output.unsubscribe', {'id':'s'})['ok']
        out, err = server.communicate(timeout=10)
        assert server.returncode == 0 and 'RESUME OK' in out, err
        print('host output replay resumed across two connections')
    finally:
        if server.poll() is None: server.kill(); server.wait()
