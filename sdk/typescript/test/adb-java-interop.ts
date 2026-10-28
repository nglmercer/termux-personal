import assert from "node:assert/strict";
import { AdbTransport, TermuxClient } from "../src/index.ts";

const transport = AdbTransport.connect({ adbPath: process.argv[2], socketPath: process.argv[3], serial: "test-serial" });
const client = await TermuxClient.fromTransport(transport);
try {
  assert.equal((await client.describe()).protocolVersion, 1);
  const { result } = await client.request("session.write", { id: "session-1", length: 4 }, { binary: new Uint8Array([0, 255, 27, 10]) });
  assert.deepEqual(result, { ok: true });
} finally { client.close(); }
console.log("TypeScript ADB bridge and Java v1 interoperability passed");
