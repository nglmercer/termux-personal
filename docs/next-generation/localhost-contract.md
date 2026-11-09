# Local endpoint contract for workspace services (issue #16)

How an Android or browser frontend finds and opens an HTTP/WebSocket backend that runs as a Termux workspace service, even when the process restarts. Termux selects the service explicitly, hands out its loopback address and a per-run identity and credential, and never retries anything. **The backend checks its own clients**; Termux does not proxy or inspect application traffic.

## What Termux provides

A service definition may opt in with `"credential": true` (it needs at least one port; the first declared port is the endpoint). Absent or false, the service starts exactly as before, with no new environment.

On **every** launch (start, restart, automatic restart after a crash) Termux creates:

| Value | Where | Notes |
|---|---|---|
| `instanceId` | always, in memory | 128 random bits, hex. Identifies one run. |
| credential | only with the opt-in | 256 random bits, hex. Dropped as soon as the run ends. |

With the opt-in, the service's environment gets `TERMUX_SERVICE_INSTANCE_ID`, `TERMUX_SERVICE_TOKEN`, `TERMUX_SERVICE_PORT` and `TERMUX_SERVICE_ORIGIN` (`http://127.0.0.1:PORT`). The values live only in memory: job records, history, diagnostics and service status never contain them, and nothing logs them.

`workspace.service.endpoint {workspaceId, serviceId}` (needs `WORKSPACES_READ` **and** `JOBS_EXECUTE`, because the credential controls the backend) returns:

```json
{"endpoint": {"workspaceId": "ws_…", "serviceId": "api", "state": "running",
  "instanceId": "0123…cdef", "port": 3000, "url": "http://127.0.0.1:3000/", "origin": "http://127.0.0.1:3000",
  "listening": true, "credentialRequired": true, "credential": "ab…ab"}}
```

* `url` and `origin` always use the IPv4 literal `127.0.0.1`, never `localhost` (which is `::1` on some devices).
* `instanceId` and `credential` are null unless the service is `running`; `listening` is true only for a running run whose port accepts connections.
* CLI: `termux-control workspaces endpoint WORKSPACE SERVICE` (prints the credential; do not paste it into logs). SDKs: `services.endpoint()`.

## What the client must do

1. Ask for the endpoint; use only `url`/`origin` from the answer.
2. **A port is not an identity.** Anything can listen on it (another program, a previous crashed run's replacement). Authenticate every request with the credential and, when it matters, confirm `instanceId` through the backend (the reference backend answers `GET /__termux/instance`).
3. If `instanceId` changed, it is a different run: the old credential is invalid, and any operation whose response was lost has an **unknown outcome**. Read state from the backend before sending it again; never replay automatically. A refusal from the new run does not prove the old one did nothing.
4. On disconnect, background or process death, ask for the endpoint again; do not keep reconnecting in a loop to a stopped service.

## What the backend must do

The reference backend, `tools/termux-service-example/server.mjs` (Node.js 18+, no dependencies), implements all of this:

* Refuse to start without the credential (fail closed), and listen on `127.0.0.1:PORT` only. If the port is taken, exit (98) instead of choosing another port.
* Check `Host` equals `127.0.0.1:PORT` (defeats DNS rebinding: 421 otherwise).
* Check `Origin`, when present, equals `TERMUX_SERVICE_ORIGIN` (403 otherwise), for HTTP and for the WebSocket upgrade.
* Accept `Authorization: Bearer CREDENTIAL` (constant-time comparison). A page loaded once with the header gets an `HttpOnly; SameSite=Strict` session cookie valid only for this run. Cookies are scoped by host, not port, so the cookie name includes the port. WebSocket clients that cannot set headers pass `termux.bearer.CREDENTIAL` as a `Sec-WebSocket-Protocol`.
* Answer `401` without detail, send `Cache-Control: no-store` and `Referrer-Policy: no-referrer`, and never log headers or the credential.

## Network exposure and Android traffic policy

* Nothing new listens on the LAN. Termux's own relay (`workspace.service.proxy`) still binds loopback unless `confirmLan` is given.
* The app targets SDK 28, so cleartext HTTP is blocked by default on Android 9+. `res/xml/network_security_config.xml` permits cleartext **only** to `127.0.0.1` (needed by local clients in the app process, such as an opt-in viewer); `localhost` and every other host keep the default, and trust anchors are unchanged. Android 7.0–8.1 apply the same file; on Android 5–6 (no network security config) cleartext was already allowed by default.

## Limits, stated plainly

* Every process with the Termux UID can read the service's environment (`/proc/PID/environ`) and can connect to the local control socket with whatever consent was granted. The credential separates **browsers, web pages and other apps** from the backend, not Termux processes from each other.
* The backend's checks are the security boundary; Termux cannot enforce them for a backend that ignores the credential.
* Credentials rotate per run, not on a timer. A long-running run keeps its credential until it stops.

## Device validation

`LocalEndpointInstrumentedTest` runs the reference backend under the real Node.js as a workspace service on a phone (POCO X3 Pro, Android 16 / API 36, arm64-v8a, Node v24.18.0; `OK (3 tests)`, twice):

| Case | Observed |
|---|---|
| port already taken by another socket | the backend exits instead of moving; the endpoint reports no instance, no credential, `listening: false`, although something accepts connections on the port |
| request without credential | 401 |
| foreign `Origin` (HTTP and WebSocket) | 403 |
| `Host: evil.example:PORT` (DNS rebinding) | 421 |
| WebSocket with the credential | 101 |
| `HttpURLConnection` to `http://127.0.0.1:PORT` / to `http://localhost:PORT` | allowed / refused by the network security config |
| a slow non-idempotent `POST` cut by a service restart | the client gets an error; the new run reports a new `instanceId`, a new credential, and 0 operations (nothing was replayed) |
| the old credential against the new run | 401 |
| the port on the device's non-loopback IPv4 address | connection refused |

Not covered: an Android frontend app other than the tests, Android versions other than 16, and the WebView viewer (issue #14).
