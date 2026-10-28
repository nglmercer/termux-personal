# Python local control client (experimental)

The synchronous v1 client connects to an already enabled, authorized Unix socket; it does not enable local control or grant scopes. It serializes requests on a connection, negotiates version 1, and returns binary output separately from JSON metadata. `describe()` falls back to capabilities only when an older server reports that the method is missing.

```python
from termux_personal import TermuxClient, TransportError

with TermuxClient.connect("/data/data/com.termux/files/control-ipc/control.sock") as client:
    available = client.describe()
    sessions = client.sessions.list()
```

After a connection failure during a mutating request, `TransportError.outcome_unknown` is true and the connection is closed. An `RpcError` or a `ProtocolError` from an invalid mutation result can also report an unknown outcome. Do not automatically retry those requests. No job, file, workspace or process APIs are implemented here yet.

Session helpers check advertised capabilities on each operation and raise `UnavailableError` when a method is absent. `sessions.write()` injects exact bytes into a PTY and does not wait for a command or provide its exit status. `sessions.read_output()` returns offsets, bytes and an explicit gap flag.

`TermuxClient.connect_adb(serial="...")` uses the native bridge through `adb shell -T` and `run-as`; it requires an authorized device, a debuggable Termux install, enabled local control and a compatible bridge already installed in app-private storage. It does not enable any permission.

From the repository root, run `GSON_JAR=... bash scripts/test-control-python-sdk.sh` after compiling debug Java classes. This runs socket-pair tests and a host Java protocol interoperability test; Android socket permissions are tested separately.
