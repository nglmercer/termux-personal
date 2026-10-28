import assert from "node:assert/strict";
import { TermuxClient } from "../src/index.ts";

const client = await TermuxClient.connect(process.argv[2]);
try {
  const description = await client.describe();
  assert.equal(description.protocolVersion, 1);
  assert.ok(Array.isArray(description.methods) && description.methods.some((method: { name: string }) => method.name === "session.write"));
  await client.sessions.write("session-1", new Uint8Array([0, 255, 27, 10]));
} finally { client.close(); }
console.log("TypeScript SDK and Java v1 protocol interoperability passed");
