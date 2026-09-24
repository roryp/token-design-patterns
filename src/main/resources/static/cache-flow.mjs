const routes = {
  hit: ["input", "cache", "hit", "model", "output"],
  "miss-written": ["input", "cache", "miss", "model", "output"],
  miss: ["input", "cache", "miss", "model", "output"],
  bypassed: ["input", "model", "output"],
  unknown: ["input", "cache", "model", "output"]
};

function providerCount(value) {
  if (value == null) return null;
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error("The provider cache receipt contains an invalid token count.");
  }
  return value;
}

export function cacheFlowPlan(metrics) {
  const reads = providerCount(metrics.cachedInputTokens);
  const writes = providerCount(metrics.cacheWriteTokens);
  const outcome = Object.hasOwn(routes, metrics.cacheStatus) ? metrics.cacheStatus : "unknown";
  if ((outcome === "hit" && !(reads > 0))
      || (outcome === "miss-written" && (reads !== 0 || !(writes > 0)))
      || (["miss", "bypassed"].includes(outcome) && (reads !== 0 || writes !== 0))) {
    throw new Error("The provider cache status and token counts disagree.");
  }
  const format = value => value == null ? "unknown" : new Intl.NumberFormat().format(value);
  const explanations = {
    hit: `${format(reads)} input tokens reused. Terra still generated a fresh answer.`,
    "miss-written": `No input reused; ${format(writes)} input tokens written for possible later reuse.`,
    miss: "The provider reported no cache reads or writes. Terra generated a fresh answer.",
    bypassed: "Cache explicitly bypassed: zero reads and writes. Terra generated a fresh answer.",
    unknown: "Cache telemetry is incomplete. No HIT or MISS branch is inferred."
  };
  return {
    outcome,
    route: [...routes[outcome]],
    branch: outcome === "hit" ? "hit" : ["miss", "miss-written"].includes(outcome) ? "miss" : null,
    writes: writes > 0,
    readLabel: reads == null ? "Read usage unknown" : `${format(reads)} input tokens reused`,
    writeLabel: writes == null ? "Cache-write usage unknown"
      : writes > 0 ? `Store reusable prefix: ${format(writes)} tokens` : "No cache writes reported",
    summary: explanations[outcome]
      + (outcome === "hit" && writes > 0 ? ` Also wrote ${format(writes)} input tokens.` : "")
      + (outcome === "unknown" && writes > 0 ? ` Confirmed writes: ${format(writes)} input tokens.` : "")
  };
}
