#!/usr/bin/env python3
"""Client replay tests with a mock Unix server; no Android or real PTY."""
import json, os, socket, struct, subprocess, sys, tempfile, threading

binary, root = map(os.path.abspath, sys.argv[1:3])
token = 'a' * 64

def exact(conn, size):
    data = bytearray()
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk: raise EOFError()
        data.extend(chunk)
    return bytes(data)

def recv(conn):
    size = struct.unpack('>I', exact(conn, 4))[0]
    assert 0 < size <= 65536
    return json.loads(exact(conn, size))

def send(conn, id, result=None, error=None):
    body = json.dumps({'jsonrpc':'2.0', 'id':id, **({'result':result} if error is None else {'error':error})}).encode()
    conn.sendall(struct.pack('>I', len(body)) + body)

def hello(conn):
    conn.settimeout(5)
    assert recv(conn)['method'] == 'system.hello'
    send(conn, 1, {'version':1})

def run(mode):
    errors = []
    with tempfile.TemporaryDirectory(prefix='resume-client-', dir=root) as directory:
        path = os.path.join(directory, 's')
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(path); listener.listen(4)
        def serve():
            try:
                with listener.accept()[0] as conn:
                    hello(conn)
                    request = recv(conn)
                    assert request['method'] == 'session.output.subscribe' and request['params'] == {'id':'s','resumable':True}
                    send(conn, 2, {'ok':True, 'resumeToken': 'bad' if mode == 'bad_token' else token})
                    if mode == 'bad_token':
                        try: recv(conn); raise AssertionError('invalid token accepted')
                        except EOFError: return
                    request = recv(conn); assert request['params']['offset'] == 0
                    send(conn, 3, {'offset':0,'nextOffset':5,'endOffset':5,'gap':False,'length':5})
                    conn.sendall(struct.pack('>I', 5) + b'FIRST')
                    request = recv(conn); assert request['params']['offset'] == 5
                    if mode == 'partial':
                        send(conn, 3, {'offset':5,'nextOffset':11,'endOffset':11,'gap':False,'length':6})
                        conn.sendall(struct.pack('>I', 6) + b'SE')
                if mode == 'conflict':
                    with listener.accept()[0] as conn:
                        hello(conn)
                        assert recv(conn)['method'] == 'session.output.resume'
                        send(conn, 6, error={'code':-32001,'message':'CONFLICT'})
                with listener.accept()[0] as conn:
                    hello(conn)
                    request = recv(conn)
                    assert request['method'] == 'session.output.resume' and request['params'] == {'id':'s','token':token}
                    send(conn, 6, {'ok':True})
                    request = recv(conn); assert request['method'] == 'session.output.read' and request['params']['offset'] == 5
                    start = 7 if mode == 'gap' else 5
                    data = b'COND' if mode == 'gap' else b'SECOND'
                    send(conn, 3, {'offset':start,'nextOffset':11,'endOffset':11,'gap':start != 5,'length':len(data)})
                    conn.sendall(struct.pack('>I',len(data)) + data)
                    assert recv(conn)['params']['offset'] == 11
                    send(conn, 3, error={'code':-32001,'message':'NOT_RUNNING'})
            except BaseException as error: errors.append(error)
            finally: listener.close()
        thread = threading.Thread(target=serve); thread.start()
        result = subprocess.run([binary, '--socket', path, '--resume', 'follow', 's'], capture_output=True, timeout=20)
        thread.join(6)
        assert not thread.is_alive() and not errors, errors
        assert result.returncode != 0
        if mode == 'bad_token': assert result.stdout == b'' and b'invalid subscription' in result.stderr
        else:
            assert result.stdout == (b'FIRSTCOND' if mode == 'gap' else b'FIRSTSECOND'), (mode, result.stdout, result.stderr)
            assert b'output subscription resumed' in result.stderr
            assert (b'output gap' in result.stderr) == (mode == 'gap')
            assert token.encode() not in result.stderr

for mode in ('disconnect', 'partial', 'gap', 'conflict', 'bad_token'): run(mode)
print('native client reconnect, partial frame, gap, conflict and token checks passed')
