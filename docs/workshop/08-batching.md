# Module 8: Batching

[Workshop home](README.md) · Previous: [Caching](07-caching.md)

> **Mission:** Show, with numbers from the trace, that running independent items in parallel cuts waiting time but not tokens, and find the batch limits by hitting them.

**Idea in one line:** one to six independent items, one model call each, run concurrently. Throughput improves; content tokens do not fall.

## Warm-up: predict

Ask the room: *Three items run in parallel. Will the run take about as long as one item, or as long as three? Will it use fewer tokens than three separate runs?*

## Part 1: Demo mission

### Round 1: parallel versus sequential

1. Select **Batching** and run the sample unchanged:

   ```text
   Explain routing requests between LLM models; Explain LLM context compression; Explain provider prompt caching
   ```

2. In the **Trace**, write down the duration and tokens of each **Batch worker** call, then add them up.
3. Now run each of the three items on its own, one run at a time, and record the same numbers.

   | Run | Model calls | Observed tokens | Sum of worker durations | Wall time |
   |---|---|---|---|---|
   | Three items, one batch | | | | |
   | Item 1 alone | | | | |
   | Item 2 alone | | | | |
   | Item 3 alone | | | | |
   | Three single runs, total | | | | |

### Round 2: find the limits

Predict the result, then run each input:

| # | Input | Prediction | Result |
|---|---|---|---|
| A | `Explain an LLM token.` | | |
| B | Six items, one per line (write your own) | | |
| C | `one;two;three;four;five;six;seven` | | |
| D | `; ;` | | |

**Checkpoint.** You have completed the mission when:

- [ ] Your table shows that the batch's wall time is less than the sum of its worker durations.
- [ ] Your table shows that the batch's observed tokens are about the same as the three single runs combined.
- [ ] You have seen a one-item batch make exactly one model call.
- [ ] You have triggered both batch validation errors and seen that no items were processed.
- [ ] You have noticed that **Projected saving** is 0%.

## Part 2: Examine the code

1. **Splitting and limits.** [`splitBatch` in PatternRunner.java](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L292-L304). Note where it is called in [`run`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L70-L80): before any model is initialized.
2. **The worker.** [`BatchWorker` and `BatchWorkflow` in PatternAgents.java](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L365-L380). The worker is stateless: it reads one `item`.
3. **The fan-out.** [`runBatching`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L268-L285). `parallelMapperBuilder` applies the same worker to each item of the `items` collection on `batchExecutor`. Find how `batchExecutor` is created near the top of the class.
4. **Ordering.** In the same method, see how the answers are numbered. Do results come back in input order or completion order?
5. **Honest metrics.** The batching cases in [`projectedBaseline`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L306-L319) and [`measurementBasis`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L321-L332).
6. **The tests.** In [PatternRunnerTest.java](../../src/test/java/com/example/tokenpatterns/PatternRunnerTest.java), read `aSingleBatchItemMakesExactlyOneCallWithoutInventedTasks`, `batchAcceptsSixItemsPreservingOrderAndIgnoringEmptySeparators`, and `parallelBatchWorkersEachReportTheirOwnUsage`.

## Part 3: Scavenger hunt

1. Which two characters separate batch items?
2. What are the minimum and maximum number of items?
3. Quote the error for a seven-item batch. What does it promise?
4. What kind of executor runs the workers?
5. Which model tier runs each worker, and what length does its prompt ask for?
6. What does `metrics.concurrency` report for a batch?
7. What does the batching baseline equal?
8. Quote the third takeaway.
9. Why does `parallelBatchWorkersEachReportTheirOwnUsage` repeat its check 50 times?

## Part 4: Q&A

1. Why doesn't parallelism reduce tokens? What *would* reduce tokens for a batch of similar items?
2. The executor itself does not limit concurrency; the six-item cap does. What would you add before allowing a batch of 600?
3. Give an example of items that look independent but are not. What goes wrong if you batch them?
4. Some providers offer asynchronous batch APIs with different pricing. How is that different from what this lab does?

<details>
<summary>Facilitator notes</summary>

1. Each item still sends its own prompt and gets its own answer, so the content is the same; only the waiting overlaps. Tokens fall only if you change the content, for example by sharing a cached prefix, shortening the per-item prompt, or combining items into one prompt, which couples their failures.
2. A bounded pool or semaphore sized to the provider's rate and concurrency limits, retry with backoff for `429` responses, and partial-failure reporting. The takeaway says to bound fan-out to provider limits.
3. Items where one depends on another's answer, such as "summarize the report" and "translate the summary". In parallel, the second item runs without the first's output.
4. Asynchronous batch jobs trade latency, often hours, for throughput or price. This lab runs synchronous, concurrent calls and claims no price or token change.

</details>

## Stretch challenge (credential-free)

Add a test to [PatternRunnerTest.java](../../src/test/java/com/example/tokenpatterns/PatternRunnerTest.java) that runs a two-item batch separated by a newline, for example `"Explain retries\nExplain backoff"`, and asserts that:

- `result.metrics().modelCalls()` is 2 and `result.metrics().concurrency()` is 2, and
- `result.output()` has two lines, starting with `1. ` and `2. `.

```bash
sh ./mvnw test -Dtest=PatternRunnerTest
```

## Answer key

<details>
<summary>Round 2 results</summary>

| # | Result |
|---|---|
| A | One item, exactly one model call |
| B | Six calls in parallel, answers numbered in input order |
| C | Rejected: "A batch accepts at most six items. Split the request into smaller batches; no items were processed." |
| D | Rejected: "Enter at least one non-empty batch item, separated by semicolons or newlines." |

</details>

<details>
<summary>Scavenger hunt answers</summary>

1. Semicolon (`;`) and newline. Runs of separators and blank items are ignored.
2. One to six.
3. "A batch accepts at most six items. Split the request into smaller batches; no items were processed." Nothing is silently dropped.
4. A virtual-thread-per-task executor: `Executors.newVirtualThreadPerTaskExecutor()`.
5. The medium model; one concise sentence per item.
6. The number of items dispatched.
7. Observed tokens, so avoided tokens and projected saving are zero.
8. "Bound fan-out to provider rate and concurrency limits."
9. Parallel workers share one agent proxy and scope, so a race could attribute usage to the wrong call. Repeating the run makes an intermittent race likely to show up.

</details>
