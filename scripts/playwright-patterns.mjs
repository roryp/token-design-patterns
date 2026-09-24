export async function testPatterns(page, suite = "desktop") {
  if (!["desktop", "mobile", "branches", "inputs", "cache"].includes(suite)) {
    throw new Error(`Unknown browser suite: ${suite}`);
  }
  // Separate suites keep real model calls within the MCP tool's execution window.
  const results = [];
  const errors = [];
  const expectedCalls = {
    router: 2, triage: 1, compression: 2, rag: 1,
    "tool-use": 1, "step-back": 2, caching: 1, batching: 3
  };
  const onError = error => errors.push(error.message);
  page.on("pageerror", onError);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.reload({ waitUntil: "domcontentloaded", timeout: 90000 });
  await page.locator('.pattern-button[data-pattern-id="router"]').waitFor();

  function check(condition, message) {
    if (!condition) throw new Error(message);
  }

  async function submit(id, input, keyboard = false) {
    await page.locator(`.pattern-button[data-pattern-id="${id}"]`).click();
    if (input !== undefined) await page.locator("#promptInput").fill(input);
    const pending = page.waitForResponse(response =>
      response.url().endsWith("/api/runs")
      && response.request().method() === "POST"
      && response.request().postDataJSON().patternId === id,
    { timeout: 180000 });
    if (keyboard) await page.locator("#promptInput").press("Control+Enter");
    else await page.locator("#runButton").click();
    check(await page.locator("#runButton").isDisabled(), "Duplicate submissions are not blocked");
    const response = await pending;
    const data = await response.json();
    await page.waitForFunction(() => !document.querySelector("#runButton").disabled);
    return { response, data, request: response.request().postDataJSON() };
  }

  async function validate(id, result, calls) {
    const { response, data } = result;
    check(response.ok(), `${id}: HTTP ${response.status()}: ${data.detail}`);
    const metrics = data.metrics;
    const models = data.trace.filter(event => event.kind === "model");
    check(data.patternId === id && data.output?.trim(), `${id}: missing or mismatched answer`);
    check(metrics.modelCalls === calls && models.length === calls, `${id}: wrong number of model calls`);
    check(metrics.inputTokens + metrics.outputTokens === metrics.observedTokens, `${id}: inconsistent usage`);
    check(models.reduce((total, event) => total + event.inputTokens + event.outputTokens, 0)
      === metrics.observedTokens, `${id}: trace usage differs from totals`);
    check(models.every(event => event.inputTokens > 0 && event.outputTokens > 0), `${id}: unmeasured model span`);
    check(data.trace.filter(event => event.kind !== "model")
      .every(event => event.inputTokens === 0 && event.outputTokens === 0), `${id}: non-model tokens`);
    check(await page.locator(".trace-item.revealed").count() === data.trace.length, `${id}: hidden trace rows`);
    check(Number((await page.locator("#observedValue").innerText()).replace(/\D/g, ""))
      === metrics.observedTokens, `${id}: displayed usage mismatch`);
    check(!(await page.locator("#errorBanner").innerText()), `${id}: unexpected error banner`);
    const graph = await page.locator("#flowSvg").evaluate(svg => ({
      nodes: [...svg.querySelectorAll(".flow-node.visited")].map(node => node.dataset.nodeId),
      active: svg.querySelectorAll(".active").length
    }));
    check(graph.nodes.includes("output") && graph.active === 0, `${id}: unfinished animation`);
    for (const event of data.trace.filter(event => event.nodeId)) {
      check(graph.nodes.includes(event.nodeId), `${id}: missing animated node ${event.nodeId}`);
    }
    check(!await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), `${id}: page overflow`);
    if (["step-back", "caching", "batching"].includes(id)) {
      check(metrics.avoidedTokens === 0 && metrics.projectedSavingsPercent === 0
        && metrics.projectedBaselineTokens === metrics.observedTokens, `${id}: false content-token saving`);
    }
    if (id !== "caching") {
      check(metrics.cachedInputTokens === 0 && metrics.cacheWriteTokens === 0
        && metrics.cacheStatus === "bypassed", `${id}: unexpected provider cache use`);
      return { metrics, models, graph };
    }
    // Everything the page shows for a cache test must come from this model call's provider usage.
    const word = { hit: "HIT", "miss-written": "MISS", miss: "MISS", bypassed: "BYPASSED" }[metrics.cacheStatus] ?? "UNKNOWN";
    const cache = await page.locator('#flowSvg [data-node-id="cache"]').evaluate(node => ({
      hit: node.classList.contains("cache-hit"),
      miss: node.classList.contains("cache-miss"),
      label: node.querySelector(".node-label").textContent
    }));
    check(cache.label === word && cache.hit === (word === "HIT") && cache.miss === (word === "MISS"),
      `Cache node shows ${cache.label}; the provider reported ${metrics.cacheStatus}`);
    check(["instructions", "cache", "model"].every(node => graph.nodes.includes(node)), "The cache test path was not animated");
    check(metrics.cacheEnabled === true && models[0].cachedInputTokens === metrics.cachedInputTokens
      && models[0].cacheWriteTokens === metrics.cacheWriteTokens, "Cache totals differ from the model call");
    check(await page.locator("#promptInput").isHidden(), "Caching shows a prompt box");
    const session = await page.locator("#cacheInstructions").getAttribute("data-session");
    const question = await page.locator("#cacheQuestion").innerText();
    check(result.request.cacheSession === session, "The test did not send this browser session's instructions");
    check(question.includes(`“${result.request.input}”`) && data.scope.request === result.request.input,
      "The test did not send the fixed question shown on the page");
    const history = await page.locator("#cacheHistory li").allInnerTexts();
    const test = history.length;
    check(history.at(-1)?.startsWith(`Test ${test}: ${word}`), "The test history does not show this provider result");
    check((await page.locator("#flowStatus").innerText()).startsWith(`Test ${test}: ${word}`), "The cache status differs");
    check(await page.locator("#answerBody .mini-badge", { hasText: `Cache ${word}` }).count() === 1, "The answer badge differs");
    if (test === 1) {
      check(metrics.cacheStatus === "miss-written" && metrics.cachedInputTokens === 0 && metrics.cacheWriteTokens >= 1024,
        `Test 1 of a new session must MISS and write the instructions, but was ${metrics.cacheStatus}`);
    }
    if (word === "HIT") check(metrics.cachedInputTokens >= 1024, "A HIT reused fewer than 1,024 tokens");
    return { metrics, models, graph, test, session };
  }

  async function scenario(name, action) {
    try {
      results.push({ name, passed: true, ...await action() });
    } catch (error) {
      results.push({ name, passed: false, error: error.message });
    }
  }

  try {
    for (const width of suite === "desktop" ? [1440] : suite === "mobile" ? [390] : []) {
      await page.setViewportSize({ width, height: 1000 });
      for (const [id, calls] of Object.entries(expectedCalls)) {
        await scenario(`${width}/${id}`, async () => {
          const result = await submit(id);
          const { metrics, models, graph } = await validate(id, result, calls);
          const answer = result.data.output;
          if (id === "router") {
            check(/filter|predicate|stream/i.test(answer), "Router answer is off topic");
            check(graph.nodes.includes("medium") && !graph.nodes.includes("small")
              && !graph.nodes.includes("large"), "Wrong router branch");
          }
          if (id === "triage") {
            check(/429|rate|throttl/i.test(answer) && graph.nodes.includes("fast")
              && !graph.nodes.includes("deep"), "Wrong simple-triage result");
          }
          if (id === "compression") {
            const source = models.find(event => event.agent === "Context compressor");
            const focused = models.find(event => event.agent === "Focused answerer");
            check(focused.inputTokens < source.inputTokens, "Compression did not reduce downstream context");
            check(/retr|concurren|throttl/i.test(answer), "Compression lost the incident evidence");
          }
          if (id === "rag") check(/AgenticScope|shared state/i.test(answer), "RAG answer is off topic");
          if (id === "tool-use") check(/13[.,]50/.test(answer), "The answer changed the deterministic $13.50 result");
          if (id === "step-back") check(answer.trim().split(/\s+/).length <= 200, "Plan executor exceeds 200 words");
          if (id === "caching") check(/idempoten/i.test(answer), "Caching answer is off topic");
          if (id === "batching") {
            check(metrics.concurrency === 3 && ["1.", "2.", "3."].every(label => answer.includes(label)),
              "Batch output is not ordered");
            const routingAnswer = answer.split("\n")[0];
            check(/language model|LLM|models?/i.test(routingAnswer)
              && /task|complexity|cost|latency|capabilit|quality/i.test(routingAnswer),
            "Batch routing answer does not describe selecting a model for the request");
          }
          return { calls: metrics.modelCalls, observed: metrics.observedTokens, cache: metrics.cacheStatus };
        });
      }
    }

    for (const branch of suite === "branches" ? [
      { name: "router-knowledge", id: "router", input: "Define an LLM token in two sentences.", calls: 2, node: "small" },
      { name: "router-architecture", id: "router", input: "Recommend an architecture for a multi-region payment service with one trade-off and a safe rollout plan.", calls: 2, node: "large" },
      { name: "triage-deep", id: "triage", input: "Design a secure distributed multi-region architecture for a payment system, including migration trade-offs.", calls: 1, node: "deep" }
    ] : []) {
      await scenario(branch.name, async () => {
        const result = await submit(branch.id, branch.input, true);
        const { metrics, graph } = await validate(branch.id, result, branch.calls);
        check(graph.nodes.includes(branch.node), `Expected branch ${branch.node}`);
        if (branch.id === "triage") {
          check(/decision frame/i.test(result.data.output) && /next step/i.test(result.data.output)
            && /validation/i.test(result.data.output), "Deep triage lost its three-section recommendation");
          check(result.data.output.trim().split(/\s+/).length <= 150, "Deep triage exceeds its 150-word scope");
        }
        return { calls: metrics.modelCalls, branch: branch.node };
      });
    }

    if (suite === "cache") {
      const sessionPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
      let firstSession = "";
      const cacheTest = ({ test, metrics }) => ({
        test, cache: metrics.cacheStatus, read: metrics.cachedInputTokens,
        written: metrics.cacheWriteTokens, input: metrics.inputTokens
      });
      await scenario("cache-instructions-without-a-prompt-box", async () => {
        await page.locator('.pattern-button[data-pattern-id="caching"]').click();
        check(await page.locator("#promptInput").isHidden() && await page.locator("#promptLabel").isHidden(),
          "Caching still shows a prompt box");
        check(await page.locator('input[type="checkbox"]').count() === 0, "Caching exposes a bypass switch");
        check(await page.locator("#runButton").innerText() === "Run cache test 1", "A new session does not start at test 1");
        check(await page.locator("#cacheResetButton").isVisible(), "Start over is missing");
        firstSession = await page.locator("#cacheInstructions").getAttribute("data-session");
        check(sessionPattern.test(firstSession), "The browser session has no cache session id");
        const pending = page.waitForResponse(response => response.url().includes("/api/cache-policy?session="));
        await page.locator("#cacheInstructions summary").click();
        const response = await pending;
        check(response.ok(), "The session instructions are not available");
        check(decodeURIComponent(response.url().split("session=")[1] ?? "") === firstSession,
          "Loaded another session's instructions");
        const text = await response.text();
        await page.waitForFunction(() => document.querySelector("#cacheInstructionsText").textContent.startsWith("Cache test session "));
        check(await page.locator("#cacheInstructionsText").textContent() === text,
          "The dropdown differs from the instructions the server sends to the model");
        check(text.startsWith(`Cache test session ${firstSession}.`) && text.includes("IDEMPOTENCY")
          && text.includes("PROVIDER PROMPT CACHES"), "The session instructions are incomplete");
        return { session: firstSession, characters: text.length };
      });
      await scenario("cache-test-1-misses-and-writes", async () => {
        const run = await validate("caching", await submit("caching"), 1);
        check(run.test === 1 && run.session === firstSession, "Test 1 did not use the new session");
        return cacheTest(run);
      });
      await scenario("cache-test-2-hits", async () => {
        const attempts = [];
        for (let attempt = 2; attempt <= 6; attempt++) {
          const run = await validate("caching", await submit("caching"), 1);
          check(run.test === attempt && run.session === firstSession, "The tests did not share one session");
          attempts.push(cacheTest(run));
          if (run.metrics.cacheStatus === "hit") return { test2Hit: attempt === 2, hitOnTest: attempt, attempts };
        }
        throw new Error(`Azure reported no HIT in tests 2-6: ${JSON.stringify(attempts)}`);
      });
      await scenario("cache-start-over-misses-again", async () => {
        await page.locator("#cacheResetButton").click();
        const session = await page.locator("#cacheInstructions").getAttribute("data-session");
        check(sessionPattern.test(session) && session !== firstSession, "Start over kept the old session");
        check(await page.locator("#cacheHistory li").count() === 0 && await page.locator("#cacheReceipt").isHidden(),
          "Start over kept old results");
        check(await page.locator("#runButton").innerText() === "Run cache test 1", "Start over did not restart at test 1");
        const run = await validate("caching", await submit("caching"), 1);
        check(run.test === 1 && run.session === session, "The restarted test did not use the new session");
        return cacheTest(run);
      });
    }

    if (suite === "inputs") {
      await scenario("batch-one-item", async () => {
        const input = "Define a token in an LLM.";
        const result = await submit("batching", input);
        await validate("batching", result, 1);
        check(JSON.stringify(result.data.scope.items) === JSON.stringify([input]), "Single-item batch invented work");
      });
      await scenario("batch-six-items", async () => {
        const input = "LLM routing;Context compression;Retrieval grounding;Prompt caching;Tool calling;Batch throughput";
        const result = await submit("batching", input);
        await validate("batching", result, 6);
        check(JSON.stringify(result.data.scope.items) === JSON.stringify(input.split(";")), "Batch items changed or reordered");
      });
      for (const invalid of [
        { name: "batch-seven-items-rejected", input: "one;two;three;four;five;six;seven", expected: /at most six/i },
        { name: "batch-empty-items-rejected", input: ";; ;", expected: /at least one/i }
      ]) {
        await scenario(invalid.name, async () => {
          const result = await submit("batching", invalid.input);
          check(result.response.status() === 400 && invalid.expected.test(result.data.detail), "Invalid batch was not rejected clearly");
          check(await page.locator("#errorBanner").isVisible(), "Batch validation was not shown");
          check(await page.locator("#observedValue").innerText() === "—", "Failed run retained old metrics");
        });
      }
      await scenario("empty-input-feedback", async () => {
        await page.locator('.pattern-button[data-pattern-id="triage"]').click();
        await page.locator("#promptInput").fill("   ");
        let posts = 0;
        const onRequest = request => {
          if (request.url().endsWith("/api/runs") && request.method() === "POST") posts++;
        };
        page.on("request", onRequest);
        try {
          await page.locator("#runButton").click();
          check(await page.locator("#errorBanner").isVisible()
            && /enter a request/i.test(await page.locator("#errorBanner").innerText()), "Empty input failed silently");
          check(await page.locator("#promptInput").getAttribute("aria-invalid") === "true", "Missing accessible validation state");
          check(posts === 0, "Empty input invoked the provider");
        } finally {
          page.off("request", onRequest);
        }
      });
      await scenario("mobile-graph-scroll-reset", async () => {
        await page.setViewportSize({ width: 390, height: 844 });
        await page.locator('.pattern-button[data-pattern-id="router"]').click();
        await page.locator("#flowViewport").evaluate(element => { element.scrollLeft = element.scrollWidth; });
        await page.locator('.pattern-button[data-pattern-id="triage"]').click();
        check(await page.locator("#flowViewport").evaluate(element => element.scrollLeft) === 0, "New graph inherited old scrolling");
      });
    }
    return {
      origin: await page.evaluate(() => location.origin),
      suite,
      realProviderRequests: true,
      passed: results.filter(result => result.passed).length,
      failed: results.filter(result => !result.passed).length,
      pageErrors: errors,
      results
    };
  } finally {
    page.off("pageerror", onError);
  }
}
