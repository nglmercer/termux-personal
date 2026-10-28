import select
import subprocess
import sys
import tempfile
from pathlib import Path

with tempfile.TemporaryDirectory(prefix="termux-ts-java-") as directory:
    path = str(Path(directory) / "socket")
    server = subprocess.Popen(["java", "-cp", sys.argv[1], "com.termux.app.control.ipc.HostRpcSocketSmoke", path],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        ready, _, _ = select.select([server.stdout], [], [], 10)
        assert ready and server.stdout.readline().strip() == "READY"
        subprocess.run(["node", "sdk/typescript/test/java-interop.ts", path], check=True, timeout=10)
        stdout, stderr = server.communicate(timeout=10)
        assert server.returncode == 0 and "OK" in stdout, stderr
    finally:
        if server.poll() is None:
            server.kill()
            server.communicate()
