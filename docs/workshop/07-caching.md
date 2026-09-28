# Module 7: Caching

[Workshop home](README.md) · Previous: [Step-back planning](06-step-back.md) · Next: [Batching](08-batching.md)

**Idea:** Azure caches long, stable instructions on the first call and reuses them on the next. Every test still calls the model and gets a fresh answer.

## Try it

1. Select **Caching** and open **Instructions for this browser session**. The first line is a session ID.
2. Click **Run cache test 1**: a MISS that saves about 1,770 tokens to the cache.
3. Click **Run cache test 2**: a HIT that reuses them. If it misses, run test 3.
4. Click **Start over**: the session ID changes, so the next test is a MISS again.

On the HIT, **Observed** tokens do not go down.

## Read the code

- [CacheInstructions.java](../../src/main/java/com/example/tokenpatterns/agent/CacheInstructions.java): the session line followed by the shared policy.
- [`cacheStatus`](../../src/main/java/com/example/tokenpatterns/service/TraceCollector.java#L156-L169): HIT or MISS comes only from Azure's reported usage.

## Discuss

1. If **Observed** tokens did not fall, what did the cache save?
2. Why is the unique session line first rather than last?

<details>
<summary>Suggested answers</summary>

1. Cached input tokens are billed at a lower rate and can be faster. They are still input tokens.
2. A cache matches from the first token, so a unique first line makes test 1 a genuine MISS.

</details>
