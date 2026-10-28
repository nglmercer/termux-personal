import assert from "node:assert/strict";
import { AdbTransport, TermuxClient } from "../src/index.ts";

const client = await TermuxClient.fromTransport(AdbTransport.connect({ socketPath: process.argv[2], bridgePath: process.argv[3] }));
try {
  assert.equal((await client.describe()).protocolVersion, 1);
  const { result } = await client.request("session.write", { id: "s1", length: 3 }, { binary: new Uint8Array([0, 255, 3]) });
  assert.deepEqual(result, { ok: true });
} finally { client.close(); }
console.log("TypeScript ADB bridge to Android LocalSocket passed");
