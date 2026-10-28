#!/usr/bin/env python3
"""Host pseudo-terminal and mock-protocol tests; no Android LocalSocket or live Termux service."""
import fcntl
import json
import os
import select
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import termios
import threading
import time

binary, root = map(os.path.abspath, sys.argv[1:3])

def exact(conn, n):
    result = bytearray()
    while len(result) < n:
        chunk = conn.recv(n - len(result))
        if not chunk:
            raise EOFError()
        result.extend(chunk)
    return bytes(result)

def receive(conn):
    size = struct.unpack('>I', exact(conn, 4))[0]
    assert 0 < size <= 65536
    return json.loads(exact(conn, size))

def send(conn, request_id, result=None, error=None):
    response = {'jsonrpc': '2.0', 'id': request_id}
    response['error' if error is not None else 'result'] = error if error is not None else result
    body = json.dumps(response, separators=(',', ':')).encode()
    conn.sendall(struct.pack('>I', len(body)) + body)

def wait_for(master, pattern, timeout=12):
    deadline = time.monotonic() + timeout
    result = b''
    while pattern not in result and time.monotonic() < deadline:
        ready, _, _ = select.select([master], [], [], max(0, deadline - time.monotonic()))
        if ready:
            result += os.read(master, 65536)
    assert pattern in result, result
    return result

def run(mode):
    local_master, local_slave = os.openpty()
    target_master, target_slave = os.openpty()
    fcntl.ioctl(local_slave, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 80, 0, 0))
    original = termios.tcgetattr(local_slave)
    target = subprocess.Popen(['sleep', '30'], stdin=local_slave if mode == 'same_tty' else target_slave,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    errors, writes = [], []
    ready_for_input = threading.Event()
    with tempfile.TemporaryDirectory(prefix='ta-', dir=root) as directory:
        path = os.path.join(directory, 's')
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(path); listener.listen(1)
        def server():
            try:
                with listener.accept()[0] as conn:
                    conn.settimeout(10)
                    req = receive(conn); assert req['method'] == 'system.hello'; send(conn, 1, {'version': 1})
                    req = receive(conn); assert req['method'] == 'session.list'
                    peer_pid = struct.unpack('3i', conn.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))[0]
                    pid = peer_pid if mode == 'self' else 2147483647 if mode == 'missing_pid' else target.pid
                    send(conn, 6, {'sessions': [{'id': 's', 'name': None, 'state': 'running', 'pid': pid}]})
                    if mode in ('self', 'same_tty', 'missing_pid'):
                        try: receive(conn); raise AssertionError('unsafe attachment must not subscribe')
                        except EOFError: return
                    req = receive(conn); assert req['method'] == 'session.output.subscribe'; send(conn, 2, {'ok': True})
                    req = receive(conn); assert req['method'] == 'session.resize' and req['params']['columns'] == 80 and req['params']['rows'] == 24
                    if mode == 'resize_rejected': send(conn, 5, error={'code': -32001, 'message': 'CONFLICT'})
                    else: send(conn, 5, {'ok': True})
                    while True:
                        try: req = receive(conn)
                        except (EOFError, ConnectionResetError): break
                        if req['method'] == 'session.output.read':
                            offset = req['params']['offset']
                            data = b'READY' if not ready_for_input.is_set() else b''
                            send(conn, 3, {'offset': offset, 'nextOffset': offset + len(data), 'endOffset': offset + len(data), 'gap': False, 'length': len(data)})
                            if data:
                                conn.sendall(struct.pack('>I', len(data)) + data)
                                ready_for_input.set()
                        elif req['method'] == 'session.resize':
                            send(conn, 5, {'ok': True})
                        elif req['method'] == 'session.write':
                            data = exact(conn, struct.unpack('>I', exact(conn, 4))[0]); writes.append(data)
                            assert len(data) == req['params']['length']
                            if mode == 'write_denied': send(conn, 4, error={'code': -32001, 'message': 'DENIED'})
                            else: send(conn, 4, {'ok': True})
                        else: raise AssertionError(req)
            except BaseException as exc: errors.append(exc)
            finally: listener.close()
        thread = threading.Thread(target=server); thread.start()
        client = subprocess.Popen([binary, '--socket', path, 'attach', 's'], stdin=local_slave, stdout=local_slave, stderr=subprocess.PIPE)
        try:
            if mode not in ('self', 'same_tty', 'missing_pid'):
                assert ready_for_input.wait(12), 'output was not subscribed'
                wait_for(local_master, b'READY')
                if mode == 'signal': client.send_signal(signal.SIGTERM)
                elif mode == 'suspend':
                    client.send_signal(signal.SIGTSTP)
                    deadline = time.monotonic() + 10
                    stopped = False
                    while time.monotonic() < deadline:
                        pid, status = os.waitpid(client.pid, os.WUNTRACED | os.WNOHANG)
                        if pid and os.WIFSTOPPED(status): stopped = True; break
                        time.sleep(.02)
                    assert stopped and termios.tcgetattr(local_slave) == original, 'terminal was not restored before suspension'
                    client.send_signal(signal.SIGCONT)
                    deadline = time.monotonic() + 10
                    while termios.tcgetattr(local_slave) == original and time.monotonic() < deadline: time.sleep(.02)
                    assert termios.tcgetattr(local_slave) != original, 'terminal did not return to raw mode'
                    os.write(local_master, b'\x1d.')
                else:
                    os.write(local_master, b'a\x03\x1a\x1d\x1db')
                    deadline = time.monotonic() + 10
                    while not writes and time.monotonic() < deadline: time.sleep(.02)
                    assert writes == [b'a\x03\x1a\x1db'], writes
                    if mode != 'write_denied': os.write(local_master, b'\x1d.')
            code = client.wait(timeout=12)
            thread.join(10)
            assert not thread.is_alive() and not errors, errors
            assert termios.tcgetattr(local_slave) == original, 'terminal was not restored'
            stderr = client.stderr.read()
            if mode in ('self', 'same_tty', 'missing_pid', 'write_denied', 'signal'): assert code != 0, (code, stderr)
            else: assert code == 0, (code, stderr)
            if mode == 'self': assert b'ancestor' in stderr or b'itself' in stderr
            if mode == 'same_tty': assert b'itself' in stderr
            if mode == 'missing_pid': assert b'could not be safely verified' in stderr
            if mode == 'resize_rejected': assert b'resize was rejected' in stderr
        finally:
            if client.poll() is None: client.kill(); client.wait()
            target.terminate(); target.wait()
            os.close(local_master); os.close(local_slave); os.close(target_master); os.close(target_slave)
            listener.close()

for case in ('self', 'same_tty', 'missing_pid', 'good', 'resize_rejected', 'write_denied', 'signal', 'suspend'):
    run(case)
print('interactive attach pseudo-terminal mock tests passed')
