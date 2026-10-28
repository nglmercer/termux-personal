import assert from "node:assert/strict";
import { TermuxClient } from "../src/index.ts";

const client = await TermuxClient.connect(process.argv[2]);
try {
  const description = await client.describe();
  assert.equal(description.protocolVersion, 1);
  assert.ok(Array.isArray(description.methods) && description.methods.some((method: { name: string }) => method.name === "session.write"));
  const response = await client.request("session.write", { id: "session-1", length: 4 }, { binary: new Uint8Array([0, 255, 27, 10]) });
  assert.deepEqual(response.result, { ok: true });
} finally { client.close(); }
console.log("TypeScript SDK and Java v1 protocol interoperability passed");
