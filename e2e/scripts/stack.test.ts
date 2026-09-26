import assert from "node:assert/strict";
import { test } from "node:test";
import { ensureStack } from "../stack.ts";
import config from "../playwright.config.ts";

test("a healthy existing deployment is reused without service commands", async () => {
  await ensureStack("https://custom.example.test", {
    responds: async () => true,
    start: () => assert.fail("must not start an already healthy deployment"),
    wait: async () => assert.fail("must not wait again"),
  });
});

test("a stopped local stack is started and never registered for teardown", async () => {
  const actions: string[] = [];
  await ensureStack("http://localhost:8084", {
    responds: async () => false,
    start: () => { actions.push("start"); },
    wait: async () => { actions.push("ready"); },
  });
  assert.deepEqual(actions, ["start", "ready"]);
  assert.equal(config.globalTeardown, undefined);
});

test("readiness failure preserves the started stack and its existing data", async () => {
  let starts = 0;
  await assert.rejects(ensureStack("http://localhost:8084", {
    responds: async () => false,
    start: () => { starts++; },
    wait: async () => { throw new Error("database unavailable"); },
  }), /database unavailable/);
  assert.equal(starts, 1);
  assert.equal(config.globalTeardown, undefined);
});

test("an unavailable custom target never starts the unrelated default stack", async () => {
  await assert.rejects(ensureStack("https://custom.example.test", {
    responds: async () => false,
    start: () => assert.fail("must not start the default stack for a different target"),
    wait: async () => assert.fail("must not wait for a different target"),
  }), /start that deployment/);
});
