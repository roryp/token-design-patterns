# Module 7: Caching

[Workshop home](README.md) · Previous: [Step-back planning](06-step-back.md) · Next: [Batching](08-batching.md)

> **Mission:** Produce a provider-reported **MISS**, then a **HIT**, then a **MISS** again after **Start over**, and explain from Azure's usage data why each result happened and why **Observed** tokens did not fall on the HIT.

**Idea in one line:** Azure caches long, stable instructions on the first call and reuses them on the next. Every test still calls the model and generates a fresh answer.

## Warm-up: predict

Ask the room: *On a cache HIT, will the **Observed** token count go down? Will the answer be the same as last time?*

## Part 1: Demo mission

1. Select **Caching**. There is no request box: every test sends this browser session's instructions and the same fixed question.
2. Open **Instructions for this browser session**. Read the first line: it names a session ID. Scroll to see how long the shared policy is.
3. Click **Run cache test 1**. Record the result.
4. Click **Run cache test 2**. Record the result.
5. Click **Start over**, then run the next test. Record the result.

   | Test | HIT or MISS | Reused from cache | Saved to cache | Input tokens | Observed | Same answer as before? |
   |---|---|---|---|---|---|---|
   | 1 | | | | | | |
   | 2 | | | | | | |
   | After **Start over** | | | | | | |

6. Compare the session line in **Instructions for this browser session** before and after **Start over**.

> A HIT is Azure's decision. If test 2 misses, run test 3 and record both.

**Checkpoint.** You have completed the mission when:

- [ ] Test 1 was a MISS that saved roughly 1,770 tokens to the cache.
- [ ] Test 2 was a HIT that reused roughly the same number of tokens.
- [ ] The test after **Start over** was a MISS again, with a different session line.
- [ ] You can explain why **Observed** and input tokens stayed about the same on the HIT.

### Optional: talk to the API directly

These calls go to the lab's HTTP API. Replace `$LAB` with `http://localhost:8080` or your deployed URL. Only the `POST` makes a paid model call.

```bash
LAB=http://localhost:8080
SESSION=3f0c2a9e-6b1d-4c7a-9e2f-5d8b1a4c7e60

# The exact, public instructions for a session. Compare the first line with the UI.
curl -s "$LAB/api/cache-policy?session=$SESSION" | head -c 400; echo

# A run with caching turned off. Look for metrics.cacheStatus.
curl -s -X POST "$LAB/api/runs" -H "Content-Type: application/json" \
  -d "{\"patternId\":\"caching\",\"input\":\"What is idempotency and why does it matter for retries?\",\"cacheEnabled\":false,\"cacheSession\":\"$SESSION\"}"

# An invalid session is rejected before any model call.
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$LAB/api/runs" -H "Content-Type: application/json" \
  -d '{"patternId":"caching","input":"What is idempotency?","cacheSession":"not-a-uuid"}'

# The retired "clear cache" endpoint.
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "$LAB/api/cache"
```

## Part 2: Examine the code

1. **The instructions.** [CacheInstructions.java](../../src/main/java/com/example/tokenpatterns/agent/CacheInstructions.java) builds the session line followed by the shared [cache-policy.txt](../../src/main/resources/prompts/cache-policy.txt).
2. **The agent.** [`CacheableAnswerer` in PatternAgents.java](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L355-L363). The user message is only the request. Where do the instructions come from?
3. **The workflow.** [`runCaching` in PatternRunner.java](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L251-L266). Find `systemMessageProvider` and the choice between `cachedMedium()` and `medium()`.
4. **The cache controls.** [`toSdkRequest` in OfficialSdkChatModel.java](../../src/main/java/com/example/tokenpatterns/agent/OfficialSdkChatModel.java#L120-L175). Trace what changes when `caching` is true: the cache key, the TTL, and the breakpoint on the system message text. Read the comment about `EXPLICIT` mode without a breakpoint.
5. **HIT or MISS.** [`cacheStatus` in TraceCollector.java](../../src/main/java/com/example/tokenpatterns/service/TraceCollector.java#L156-L169). The status comes only from Azure's `cached_tokens` and `cache_write_tokens`.
6. **Why the Responses API.** [Provider caching in the operations guide](../operations.md#provider-caching).

## Part 3: Scavenger hunt

1. What is the prompt cache key?
2. What cache TTL does the lab request?
3. Which message carries the cache breakpoint, and why is the question never cached?
4. Which deployment, by tier and name, answers the cache tests?
5. What format must `cacheSession` have? What happens to an uppercase UUID?
6. List the five possible values of `cacheStatus` and the evidence for each.
7. If Azure reports cache reads but omits cache writes, what does the takeaway say about writes?
8. What does `DELETE /api/cache` return, and why can't the app clear the cache?
9. In the September 2026 trials, how often did test 2 hit with the Responses API, and how often with Chat Completions?
10. What is the projected saving for caching, and why?

## Part 4: Q&A

1. On the HIT, **Observed** did not go down. So what did the cache actually save?
2. Why does the lab put a unique line *first* rather than last?
3. `/api/cache-policy` is public. What must never go into the cached instructions?
4. What kinds of prompt in your own systems are long, stable, and shared enough to cache? What would break the prefix?
5. Is a local answer cache, which stores and replays answers, the same pattern? Why does the lab avoid one?

<details>
<summary>Facilitator notes</summary>

1. Cached tokens are still input tokens, so the count does not change. Reused tokens are billed at a lower rate and can reduce latency. Saving to the cache can add a charge, so a prefix that is never reused costs more.
2. A cache matches the prefix from the first token. A unique first line makes the whole prefix new, so test 1 is a genuine MISS. A unique last line would leave the shared policy cacheable from earlier sessions.
3. Secrets, customer data, or anything private. The endpoint returns the instructions verbatim.
4. System prompts, tool schemas, and long reference documents. Any change before the breakpoint, including a timestamp or a reordered section, creates a new prefix.
5. No. An answer cache skips the model and can return stale or wrong answers for a question that only looks similar. The lab calls the model every time and caches only the instructions, so every answer is fresh.

</details>

## Stretch challenge (credential-free)

The unit tests simulate Azure's cache telemetry with fixtures in [StubChatModel.java](../../src/test/java/com/example/tokenpatterns/StubChatModel.java). Run the caching tests:

```bash
sh ./mvnw test -Dtest=PatternRunnerTest
```

Then, for each of `CACHE_HIT_FIXTURE`, `CACHE_UNKNOWN_FIXTURE`, and `CACHE_WRITE_UNKNOWN_FIXTURE`, find the test in [PatternRunnerTest.java](../../src/test/java/com/example/tokenpatterns/PatternRunnerTest.java) that uses it and explain which real-world Azure response it stands for. Which test proves that missing telemetry is never reported as a MISS?

## Answer key

<details>
<summary>Demo results</summary>

- **Test 1:** MISS. Reused 0; saved about 1,770. The session line made the prefix new.
- **Test 2:** HIT. Reused about 1,770; saved 0. Input and observed tokens are about the same as test 1, and the answer is freshly generated, so it is usually worded differently.
- **After Start over:** MISS again. A new session ID means a new prefix.
- **API calls:** the bypass run reports `"cacheStatus":"bypassed"`; the invalid session returns `400`; `DELETE /api/cache` returns `410`.

</details>

<details>
<summary>Scavenger hunt answers</summary>

1. `tokenflow-policy-v1`.
2. 30 minutes.
3. The single leading system message, which holds the instructions. The question is in the user message after the breakpoint, so it is never part of the cached prefix.
4. The medium tier, Terra (`gpt-5.6-terra` by default), through `cachedMedium()`.
5. A lowercase version 4 UUID. An uppercase UUID is rejected with `400` before any model is called.
6. `hit`: positive cached-input count. `miss-written`: zero reads and positive writes. `miss`: zero reads and writes with caching on. `bypassed`: zero reads and writes with caching off. `unknown`: incomplete telemetry.
7. That writes are unknown. Missing telemetry is never treated as zero.
8. `410 Gone`. Azure manages the prompt cache; the application has no way to clear it and does not pretend to.
9. Responses API: 21 of 21 sessions. Chat Completions: 11 of 48.
10. 0%. The baseline equals observed usage, because every test still sends and bills all the input tokens.

</details>

<details>
<summary>Stretch challenge answers</summary>

- `CACHE_HIT_FIXTURE`: Azure read the prefix from cache (`providerReadFixtureDoesNotRemoveCachedTokensFromObservedUsage`).
- `CACHE_UNKNOWN_FIXTURE`: Azure returned no cache details at all (`missingProviderTelemetryIsUnknownRatherThanZeroOrAMiss`, which is also the test that proves missing telemetry is not a MISS).
- `CACHE_WRITE_UNKNOWN_FIXTURE`, combined with a hit: Azure reported reads but not writes (`knownCacheReadDoesNotFabricateMissingWriteTelemetry`).

</details>
