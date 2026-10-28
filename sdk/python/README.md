# Python local control client (experimental)

The synchronous v1 client connects to an already enabled, authorized Unix socket; it does not enable local control or grant scopes. It serializes requests on a connection, negotiates version 1, and returns binary output separately from JSON metadata. `describe()` falls back to capabilities only when an older server reports that the method is missing.

```python
from termux_personal import TermuxClient, TransportError

with TermuxClient.connect("/data/data/com.termux/files/control-ipc/control.sock") as client:
    available = client.describe()
    result, _ = client.request("session.list")
```

After a connection failure during a mutating request, `TransportError.outcome_unknown` is true and the connection is closed. An `RpcError` can also report an unknown outcome. Do not automatically retry those requests. No job, file, workspace or process APIs are implemented here yet. The Unix socket transport is intended for environments that can access the socket; host ADB remains a separate debug adapter.

From the repository root, run `GSON_JAR=... bash scripts/test-control-python-sdk.sh` after compiling debug Java classes. This runs socket-pair tests and a host Java protocol interoperability test; Android socket permissions are tested separately.
