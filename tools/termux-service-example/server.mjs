// Reference backend for the Termux local endpoint contract (docs/next-generation/localhost-contract.md).
// Node.js 18+, no dependencies. It shows what the backend itself must check; Termux only hands out the credential.
import { createServer } from "node:http";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

const token = process.env.TERMUX_SERVICE_TOKEN;
const instanceId = process.env.TERMUX_SERVICE_INSTANCE_ID;
const port = Number(process.env.TERMUX_SERVICE_PORT);
const origin = process.env.TERMUX_SERVICE_ORIGIN;
// Fail closed: without the opt-in credential there is nothing to check clients against.
if (!token || !instanceId || !Number.isInteger(port) || origin !== `http://127.0.0.1:${port}`) {
  console.error("termux-service-example: start it as a workspace service with \"credential\": true");
  process.exit(2);
}
const host = `127.0.0.1:${port}`;
const cookieName = `termux_session_${port}`; // cookies are per host, not per port: keep names distinct
const session = randomBytes(32).toString("hex"); // valid for this run only
let operations = 0;

const same = (a, b) => typeof a === "string" && a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));
function authorized(req) {
  const auth = req.headers.authorization;
  if (typeof auth === "string" && auth.startsWith("Bearer ") && same(auth.slice(7), token)) return "bearer";
  const proto = req.headers["sec-websocket-protocol"];
  if (typeof proto === "string" && proto.split(",").map(s => s.trim()).some(p => p.startsWith("termux.bearer.") && same(p.slice(14), token))) return "bearer";
  for (const part of (req.headers.cookie || "").split(";")) {
    const [k, v] = part.trim().split("=");
    if (k === cookieName && same(v, session)) return "cookie";
  }
  return null;
}
// Host defeats DNS rebinding; Origin, when a browser sends one, must be this exact origin.
function refusal(req) {
  if (req.headers.host !== host) return 421;
  if (req.headers.origin !== undefined && req.headers.origin !== origin) return 403;
  if (!authorized(req)) return 401;
  return 0;
}
function send(res, status, type, body, extra = {}) {
  res.writeHead(status, { "content-type": type, "cache-control": "no-store", "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer", ...extra });
  res.end(body);
}

const page = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Termux service example</title><h1>Termux service example</h1><p>Instance <code id="i"></code></p><p id="ws">WebSocket: …</p>
<script>
fetch("/__termux/instance").then(r => r.json()).then(j => document.getElementById("i").textContent = j.instanceId);
const s = new WebSocket("ws://" + location.host + "/ws");
s.onopen = () => s.send("ping"); s.onmessage = e => document.getElementById("ws").textContent = "WebSocket: " + e.data;
s.onerror = () => document.getElementById("ws").textContent = "WebSocket: refused";
</script>`;

const server = createServer((req, res) => {
  const refused = refusal(req);
  if (refused) return send(res, refused, "text/plain", String(refused));
  // A bearer request gets a session cookie, so a page loaded once with the header can fetch its own resources.
  const extra = authorized(req) === "bearer" ? { "set-cookie": `${cookieName}=${session}; HttpOnly; SameSite=Strict; Path=/` } : {};
  const path = new URL(req.url, origin).pathname;
  if (path === "/__termux/instance") return send(res, 200, "application/json", JSON.stringify({ instanceId, operations }), extra);
  if (path === "/api/operation" && req.method === "POST") {
    // Slow on purpose: lets a test cut the connection mid-operation. Not idempotent, so a client must never replay it.
    return setTimeout(() => { operations++; send(res, 200, "application/json", JSON.stringify({ instanceId, operations }), extra); }, 2000);
  }
  if (path === "/") return send(res, 200, "text/html; charset=utf-8", page, extra);
  send(res, 404, "text/plain", "404", extra);
});

server.on("upgrade", (req, socket) => {
  const refused = refusal(req);
  const key = req.headers["sec-websocket-key"];
  if (refused || new URL(req.url, origin).pathname !== "/ws" || typeof key !== "string") {
    socket.end(`HTTP/1.1 ${refused || 400} Refused\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    return;
  }
  const accept = createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC11B65").digest("base64");
  const proto = (req.headers["sec-websocket-protocol"] || "").split(",").map(s => s.trim()).find(p => p.startsWith("termux.bearer."));
  socket.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
    `Sec-WebSocket-Accept: ${accept}\r\n` + (proto ? `Sec-WebSocket-Protocol: ${proto}\r\n` : "") + "\r\n");
  socket.on("data", frame => { // one small masked text frame from the client is answered with "pong <instanceId>"
    if (frame.length < 6 || (frame[0] & 0x0f) !== 1 || (frame[1] & 0x7f) > 125) return socket.end();
    const reply = Buffer.from(`pong ${instanceId}`);
    socket.write(Buffer.concat([Buffer.from([0x81, reply.length]), reply]));
  });
  socket.on("error", () => socket.destroy());
});

server.on("error", e => {
  // The declared port is part of the contract: never fall back to another one.
  console.error(`termux-service-example: cannot listen on ${host}: ${e.code}`);
  process.exit(e.code === "EADDRINUSE" ? 98 : 1);
});
// Loopback only. Exposing it on the LAN would need an explicit, separate decision.
server.listen(port, "127.0.0.1", () => console.log(`termux-service-example listening on ${origin} (instance ${instanceId})`));
