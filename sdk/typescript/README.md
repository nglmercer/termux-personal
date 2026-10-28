# TypeScript local control client (experimental)

The client connects to an already enabled local Unix socket and never grants scopes. It serializes JSON-RPC v1 requests, supports bounded binary frames, and falls back to `system.capabilities` when an older server lacks `system.describe`. The generic `ByteTransport` interface allows other byte transports to be supplied without opening a listener.

```ts
import { TermuxClient } from "./src/index.ts";

const client = await TermuxClient.connect("/data/data/com.termux/files/control-ipc/control.sock");
try {
  const capabilities = await client.describe();
  const { result } = await client.request("session.list");
} finally { client.close(); }
```

`AbortSignal` can cancel a request. Once a mutation has begun, a transport failure or cancellation produces `TransportError.outcomeUnknown`; do not blindly retry it. `RpcError.outcomeUnknown` also reports server-side uncertainty. Job, file, process and workspace APIs are not implemented yet. The Unix socket adapter requires a runtime with Node-compatible filesystem sockets; host ADB remains a separate debug adapter.

Install development dependencies with `npm ci` in this directory. From the repository root, after compiling debug Java classes, run `GSON_JAR=... bash scripts/test-control-typescript-sdk.sh`. Tests currently use Node's native TypeScript support and require a compatible Node runtime.

The socket timeout also applies while the connection is idle; an expired connection must be replaced with a new client. The SDK does not reconnect or replay requests automatically.
