#!/usr/bin/env python3
"""Native client to simulated Java RPC loop with local PTYs, not Android socket/service."""
import fcntl
import os
import select
import subprocess
import sys
import tempfile
import termios
import time

classpath, root, binary = sys.argv[1:4]
master, slave = os.openpty()
target_master, target_slave = os.openpty()
fcntl.ioctl(slave, termios.TIOCSWINSZ, __import__('struct').pack('HHHH', 24, 80, 0, 0))
original = termios.tcgetattr(slave)
target = subprocess.Popen(['sleep', '30'], stdin=target_slave, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
with tempfile.TemporaryDirectory(prefix='at-', dir=root) as directory:
    path = os.path.join(directory, 's')
    server = subprocess.Popen(['java', '-cp', classpath, 'com.termux.app.control.ipc.HostAttachSocketSmoke', path, str(target.pid)],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    client = None
    try:
        assert server.stdout.readline().strip() == 'READY'
        client = subprocess.Popen([binary, '--socket', path, 'attach', 's'], stdin=slave, stdout=slave, stderr=subprocess.PIPE)
        data = b''; deadline = time.monotonic() + 10
        while b'HOST-ATTACH-READY' not in data and time.monotonic() < deadline:
            ready, _, _ = select.select([master], [], [], max(0, deadline - time.monotonic()))
            if ready: data += os.read(master, 65536)
        assert b'HOST-ATTACH-READY' in data, data
        os.write(master, b'\x03\x1a')
        time.sleep(.3)
        os.write(master, b'\x1d.')
        assert client.wait(timeout=10) == 0, client.stderr.read()
        out, err = server.communicate(timeout=10)
        assert server.returncode == 0 and 'ATTACH OK' in out, err
        assert termios.tcgetattr(slave) == original
        print('interactive attach to Java host protocol loop passed')
    finally:
        if client is not None and client.poll() is None: client.kill(); client.wait()
        if server.poll() is None: server.kill(); server.wait()
        target.terminate(); target.wait()
        os.close(master); os.close(slave); os.close(target_master); os.close(target_slave)
