#!/usr/bin/env python3
"""Verify both shim sides pass FIFO execution through without blocking or reading it."""
import os
import pathlib
import subprocess
import sys
import tempfile

out = pathlib.Path(sys.argv[1]).resolve()
loader = pathlib.Path(sys.argv[2]).resolve()
base_env = dict(os.environ, LC_ALL="C")
for name in ("LD_PRELOAD", "TERMUX_GLIBC_EXE", "TERMUX_BIONIC_LD_PRELOAD", "TERMUX_GLIBC_PRELOAD"):
    base_env.pop(name, None)

with tempfile.TemporaryDirectory(dir=out) as directory:
    fifo = pathlib.Path(directory) / "fifo"
    os.mkfifo(fifo, 0o700)
    for side in (0, 1):
        env = dict(base_env)
        probe = [str(out / "probe"), "run", str(fifo)]
        if side == 0:
            env["LD_PRELOAD"] = str(out / "side0.so")
            command = probe
        else:
            command = [str(loader), "--library-path", str(out / "prefix/glibc/lib"),
                       "--preload", str(out / "side1.so"), *probe]
        # No writer: the shim must not wait for a FIFO peer.
        result = subprocess.run(command, env=env, capture_output=True, timeout=3)
        assert result.returncode == 4 and b"Permission denied" in result.stderr, (side, result.returncode, result.stderr)
        # With a writer, inspecting an attempted executable must not consume FIFO contents.
        fd = os.open(fifo, os.O_RDWR | os.O_NONBLOCK | os.O_CLOEXEC)
        try:
            os.write(fd, b"fifo-payload")
            result = subprocess.run(command, env=env, capture_output=True, timeout=3)
            assert result.returncode == 4 and b"Permission denied" in result.stderr, (side, result.returncode, result.stderr)
            assert os.read(fd, 100) == b"fifo-payload", "shim consumed FIFO contents"
        finally:
            os.close(fd)
print("glibc shim FIFO pass-through checks passed")
