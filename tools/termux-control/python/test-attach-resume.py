#!/usr/bin/env python3
"""Interactive reconnect and uncertain write test with host pseudo-terminals."""
import fcntl, json, os, select, socket, struct, subprocess, sys, tempfile, termios, threading, time
binary, root = map(os.path.abspath, sys.argv[1:3])
token = 'b' * 64

def exact(conn, n):
    data = bytearray()
    while len(data) < n:
        chunk = conn.recv(n-len(data))
        if not chunk: raise EOFError()
        data.extend(chunk)
    return bytes(data)
def receive(conn): return json.loads(exact(conn, struct.unpack('>I', exact(conn, 4))[0]))
def send(conn, id, obj):
    data = json.dumps({'jsonrpc':'2.0','id':id,'result':obj}).encode()
    conn.sendall(struct.pack('>I',len(data)) + data)
def hello(conn):
    conn.settimeout(8); assert receive(conn)['method'] == 'system.hello'; send(conn, 1, {'version':1})
def output(conn, offset, data):
    send(conn, 3, {'offset':offset,'nextOffset':offset+len(data),'endOffset':offset+len(data),'gap':False,'length':len(data)})
    if data: conn.sendall(struct.pack('>I',len(data)) + data)

def run(mode):
    lm, ls = os.openpty(); tm, ts = os.openpty()
    fcntl.ioctl(ls, termios.TIOCSWINSZ, struct.pack('HHHH',24,80,0,0))
    original = termios.tcgetattr(ls)
    target = subprocess.Popen(['sleep','30'], stdin=ts, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    errors = []; resumed = threading.Event()
    try:
        with tempfile.TemporaryDirectory(prefix='attach-resume-', dir=root) as directory:
            path = os.path.join(directory, 's')
            listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM); listener.bind(path); listener.listen(4)
            def serve():
                try:
                    with listener.accept()[0] as conn:
                        hello(conn)
                        assert receive(conn)['method'] == 'session.list'; send(conn, 6, {'sessions':[{'id':'s','name':None,'state':'running','pid':target.pid}]})
                        req = receive(conn); assert req['params']['resumable'] is True
                        send(conn, 2, {'ok':True,'resumeToken':token})
                        assert receive(conn)['method'] == 'session.resize'; send(conn, 5, {'ok':True})
                        assert receive(conn)['method'] == 'session.output.read'; output(conn, 0, b'FIRST')
                        assert receive(conn)['method'] == 'session.output.read'
                    with listener.accept()[0] as conn:
                        hello(conn)
                        req = receive(conn); assert req['method'] == 'session.output.resume' and req['params']['token'] == token
                        send(conn, 6, {'ok':True})
                        assert receive(conn)['method'] == 'session.resize'; send(conn, 5, {'ok':True})
                        req = receive(conn); assert req['method'] == 'session.output.read' and req['params']['offset'] == 5
                        output(conn, 5, b'SECOND'); resumed.set()
                        while True:
                            req = receive(conn)
                            if req['method'] == 'session.output.read': output(conn, 11, b'')
                            elif req['method'] == 'session.write':
                                assert exact(conn, struct.unpack('>I', exact(conn,4))[0]) == b'x'
                                if mode == 'uncertain': return
                                send(conn, 4, {'ok':True})
                            elif req['method'] == 'session.output.unsubscribe': send(conn, 7, {'ok':True}); return
                            else: raise AssertionError(req)
                except BaseException as e: errors.append(e)
                finally: listener.close()
            thread = threading.Thread(target=serve); thread.start()
            client = subprocess.Popen([binary,'--socket',path,'--resume','attach','s'], stdin=ls, stdout=ls, stderr=subprocess.PIPE)
            assert resumed.wait(10), errors
            os.write(lm, b'x' if mode == 'uncertain' else b'\x1d.')
            _, err = client.communicate(timeout=10)
            thread.join(5)
            assert not thread.is_alive() and not errors, errors
            assert client.returncode == (1 if mode == 'uncertain' else 0), err
            assert termios.tcgetattr(ls) == original
            assert b'output subscription resumed' in err
            if mode == 'uncertain': assert b'input outcome may be unknown' in err
    finally:
        target.terminate(); target.wait()
        for fd in (lm,ls,tm,ts): os.close(fd)
for mode in ('detach','uncertain'): run(mode)
print('interactive reconnect, detach, unknown input and terminal restoration passed')
