import assert from "node:assert/strict";
import test from "node:test";
import { cacheFlowPlan } from "../src/main/resources/static/cache-flow.mjs";

const receipt = (cacheStatus, cachedInputTokens, cacheWriteTokens) =>
  ({ cacheStatus, cachedInputTokens, cacheWriteTokens });

test("hit still passes through the model and generates an answer", () => {
  const plan = cacheFlowPlan(receipt("hit", 1723, 0));
  assert.deepEqual(plan.route, ["input", "cache", "hit", "model", "output"]);
  assert.equal(plan.branch, "hit");
  assert.equal(plan.writes, false);
  assert.match(plan.summary, /fresh answer/);
});

test("write miss activates MISS and the return-to-cache path", () => {
  const plan = cacheFlowPlan(receipt("miss-written", 0, 1723));
  assert.deepEqual(plan.route, ["input", "cache", "miss", "model", "output"]);
  assert.equal(plan.branch, "miss");
  assert.equal(plan.writes, true);
  assert.match(plan.writeLabel, /Store reusable prefix/);
});

test("a miss is not evidence of a cache write", () => {
  const plan = cacheFlowPlan(receipt("miss", 0, 0));
  assert.equal(plan.branch, "miss");
  assert.equal(plan.writes, false);
  assert.equal(plan.writeLabel, "No cache writes reported");
});

test("a mixed read/write response activates HIT and the write-back path", () => {
  const plan = cacheFlowPlan(receipt("hit", 1024, 256));
  assert.equal(plan.branch, "hit");
  assert.equal(plan.writes, true);
  assert.match(plan.summary, /Also wrote 256/);
});

test("bypass skips the cache and both outcome branches, not the model", () => {
  const plan = cacheFlowPlan(receipt("bypassed", 0, 0));
  assert.deepEqual(plan.route, ["input", "model", "output"]);
  assert.equal(plan.branch, null);
  assert.equal(plan.writes, false);
});

test("unknown telemetry lights neither HIT nor MISS", () => {
  const plan = cacheFlowPlan(receipt("unknown", null, null));
  assert.deepEqual(plan.route, ["input", "cache", "model", "output"]);
  assert.equal(plan.branch, null);
  assert.equal(plan.writes, false);
  assert.equal(plan.writeLabel, "Cache-write usage unknown");
  assert.match(plan.summary, /No HIT or MISS branch is inferred/);
});

test("an observed read remains a hit with unknown write usage", () => {
  const plan = cacheFlowPlan(receipt("hit", 1024, null));
  assert.equal(plan.branch, "hit");
  assert.equal(plan.writes, false);
  assert.match(plan.writeLabel, /unknown/);
});

test("known writes may be displayed even when reads are unknown", () => {
  const plan = cacheFlowPlan(receipt("unknown", null, 1200));
  assert.equal(plan.branch, null);
  assert.equal(plan.writes, true);
});

test("a later run never inherits the earlier run's route or write state", () => {
  const first = cacheFlowPlan(receipt("miss-written", 0, 1723));
  first.route.push("unexpected-mutation");
  const next = cacheFlowPlan(receipt("hit", 1723, 0));
  assert.equal(next.writes, false);
  assert.deepEqual(next.route, ["input", "cache", "hit", "model", "output"]);
});

test("new or absent status values remain explicitly unknown", () => {
  for (const status of [undefined, "future-status", "toString"]) {
    const plan = cacheFlowPlan(receipt(status, null, null));
    assert.equal(plan.outcome, "unknown");
    assert.equal(plan.branch, null);
  }
});

test("inconsistent status or invalid usage fails visibly rather than inventing a path", () => {
  for (const metrics of [
    receipt("hit", 0, 1000),
    receipt("miss-written", 0, 0),
    receipt("miss", 1000, 0),
    receipt("bypassed", 0, 1000),
    receipt("hit", -1, 0),
    receipt("hit", 1.5, 0),
    receipt("hit", "1723", 0)
  ]) {
    assert.throws(() => cacheFlowPlan(metrics), /provider cache/);
  }
});
