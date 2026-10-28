import os
import select
import subprocess
import sys
import tempfile
from pathlib import Path

with tempfile.TemporaryDirectory(prefix="termux-adb-java-") as directory:
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
    try:
        ready, _, _ = select.select([server.stdout], [], [], 10)
        assert ready and server.stdout.readline().strip() == "READY"
        env = dict(os.environ, MOCK_BRIDGE=os.path.abspath(sys.argv[2]))
        subprocess.run(["node", "sdk/typescript/test/adb-java-interop.ts", str(adb), path], check=True, timeout=10, env=env)
        stdout, stderr = server.communicate(timeout=10)
        assert server.returncode == 0 and "OK" in stdout, stderr
    finally:
        if server.poll() is None:
            server.kill()
            server.communicate()
