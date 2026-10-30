import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";

const base = new URL("../../protocol/v1/", import.meta.url);
const load = (name: string) => JSON.parse(readFileSync(new URL(name, base), "utf8"));

test("shared fixtures cover every registered method and validate schemas", () => {
  const ajv = new Ajv2020({ strict: false });
  const request = load("schemas/request.json"), response = load("schemas/response.json");
  const params = load("schemas/method-params.json"), results = load("schemas/method-results.json");
  for (const schema of [request, response, params, results]) ajv.addSchema(schema);
  const methods = load("methods.json").methods as Array<{ name: string; binaryDirection: string }>;
  const fixtures = load("fixtures/methods.json") as Array<Record<string, any>>;
  assert.deepEqual(fixtures.map(f => f.method), methods.map(m => m.name));
  for (const fixture of fixtures) {
    const method = fixture.method as string;
    const envelope = { jsonrpc: "2.0", id: 1, method, params: fixture.params };
    const reply = { jsonrpc: "2.0", id: 1, result: fixture.result };
    for (const [uri, value] of [
      [request.$id, envelope], [response.$id, reply],
      [`${params.$id}#/$defs/${method}`, fixture.params], [`${results.$id}#/$defs/${method}`, fixture.result],
    ] as Array<[string, unknown]>) {
      const validate = ajv.getSchema(uri);
      assert.ok(validate, `missing schema ${uri}`);
      assert.ok(validate(value), `${method}: ${ajv.errorsText(validate.errors)}`);
    }
    if (fixture.requestBinaryHex) assert.equal(fixture.requestBinaryHex.length / 2, fixture.params.length);
    if (fixture.responseBinaryHex) assert.equal(fixture.responseBinaryHex.length / 2, fixture.result.length);
  }
});

test("method schemas reject traversal of the contract boundary", () => {
  const ajv = new Ajv2020({ strict: false });
  const params = load("schemas/method-params.json"); ajv.addSchema(params);
  const valid = (method: string, value: unknown) => ajv.getSchema(`${params.$id}#/$defs/${method}`)!(value);
  assert.equal(valid("session.write", { id: "s1", length: 65537 }), false);
  assert.equal(valid("session.write", { id: "s1", length: 1, scope: "SESSIONS_WRITE" }), false);
  assert.equal(valid("session.create", { columns: 1, rows: 24 }), false);
  assert.equal(valid("session.rename", { id: "s1", name: "line\nbreak" }), false);
  assert.equal(valid("session.output.resume", { id: "s1", token: "bad" }), false);
  assert.equal(valid("job.start", { executable: "/bin/true", args: "not-an-array" }), false);
  assert.equal(valid("job.start", { executable: "/bin/true", args: [], cwd: "relative" }), false);
  assert.equal(valid("job.start", { executable: "/bin/true", args: [], env: { "1BAD": "x" } }), false);
  assert.equal(valid("job.start", { executable: "/bin/true", args: [], scope: "JOBS_EXECUTE" }), false);
  assert.equal(valid("job.output.read", { id: "job_1", stream: "pty", offset: 0, limit: 4 }), false);
  assert.equal(valid("job.cancel", { id: "job_1", scope: "JOBS_CANCEL" }), false);
  assert.equal(valid("file.stat", { rootId: "home", path: "../secret" }), false);
  assert.equal(valid("file.stat", { rootId: "home", path: "/etc/passwd" }), false);
  assert.equal(valid("file.stat", { rootId: "home", path: "dir/" }), false);
  assert.equal(valid("file.list", { rootId: "home", path: "a//b" }), false);
  assert.equal(valid("file.list", { rootId: "home", path: "dir\\x" }), false);
  assert.equal(valid("file.list", { rootId: "home", path: "./dir" }), false);
  assert.equal(valid("file.read", { rootId: "Home", path: "a", offset: 0, limit: 4 }), false);
  assert.equal(valid("file.read", { rootId: "home", path: "a", offset: 0, limit: 65537 }), false);
  assert.equal(valid("file.list", { rootId: "home", path: "" }), true);
  assert.equal(valid("file.stat", { rootId: "home", path: "dir/notes.txt" }), true);
});
