# Module 3: Context compression

[Workshop home](README.md) · Previous: [Triage](02-triage.md) · Next: [RAG](04-rag.md)

> **Mission:** Prove from the trace that the expensive model never saw the full incident history, then find a question that the compressed summary cannot answer.

**Idea in one line:** the small model condenses a long history into durable facts, and the large model reasons over that compact working set instead of the whole transcript.

## Warm-up: predict

Ask the room: *The small model still has to read the whole incident history. So where is the saving?*

## Part 1: Demo mission

1. Select **Context compression** and run the sample question unchanged:

   ```text
   Based on the incident history, what is the most likely cause of the repeated 429 responses?
   ```

   The request box holds only the question. The server adds a fixed eight-entry incident timeline, which you will find in Part 2.

2. In the **Trace**, compare the two model calls:

   | Agent | Model | Input tokens | Output tokens |
   |---|---|---|---|
   | Context compressor | | | |
   | Focused answerer | | | |

3. In **AgenticScope**, open `compactContext`. This is everything the large model received about the incident.
4. Now try to break the summary. Ask about a detail that is in the timeline but may not survive compression, for example:

   ```text
   Based on the incident history, how far below the normal weekday peak was traffic, and at what time were pods restarted?
   ```

   Read `compactContext` again. Did the details survive? Did the answer admit uncertainty or invent a value?

**Checkpoint.** You have completed the mission when:

- [ ] You have shown that the Focused answerer's input is smaller than the Context compressor's input.
- [ ] You have read `compactContext` and can name the durable facts it kept.
- [ ] You have found, or failed to find, a detail the summary dropped, and checked how the answer handled it.

## Part 2: Examine the code

1. **The source history.** [`LONG_INCIDENT_CONTEXT` in PatternRunner.java](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L48-L57). Read the timeline yourself and decide what the root cause is before the model does.
2. **The compressor prompt.** [`ContextCompressor` in PatternAgents.java](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L157-L168). Note the four things it keeps and what it is told to drop.
3. **The answerer prompt.** [`FocusedAnswerer`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L170-L180). Note that it takes `compactContext` and `request`, but not `context`.
4. **The workflow.** [`runCompression`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L189-L204). A two-step `sequenceBuilder()`: the only link between the agents is the `compactContext` key in `AgenticScope`.
5. **The baseline.** Find the compression case in [`projectedBaseline`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L306-L319) and the helper `estimateTokens` at the bottom of the file.

## Part 3: Scavenger hunt

1. How many timestamped entries does the incident history contain, and at what time did the team agree on the fix?
2. Which model tier compresses, and which answers?
3. Under which `AgenticScope` key is the summary stored?
4. Name the four categories of fact the compressor is told to keep.
5. What instruction does the Focused answerer get about missing details?
6. `estimateTokens` does not call a tokenizer. How does it estimate tokens?
7. How does the compression baseline use the incident history?
8. `sanitizeScope` changes what the **AgenticScope** panel shows. What does it do to long values?

## Part 4: Q&A

1. The compressor reads the whole history on every run. When does compression actually pay back?
2. Your break-the-summary experiment: what kinds of detail are most at risk, and why is that dangerous in an incident review?
3. The teaching notes say, "Preserve source references." How would you add them without making the summary large again?
4. Would you run compression before RAG, after it, or neither? Why?

<details>
<summary>Facilitator notes</summary>

1. In one turn, the saving is that the expensive model reads less, while the cheap model reads more. It pays back fully when the compact working set is reused across many turns or agents instead of re-sending the transcript each time.
2. Exact numbers, timestamps, and dead ends that explain why an option was rejected. Losing them can make a confident answer wrong, which is why the answerer is told to call out uncertainty.
3. Keep short pointers, such as the timestamp or line of each retained fact, so a reader or a follow-up agent can fetch the original.
4. It depends on the data. RAG narrows what is sent; compression shortens what is sent. For a long session with retrieved documents, both can apply, but each step is another place to lose facts.

</details>

## Stretch challenge (credential-free)

Add a test to [PatternRunnerTest.java](../../src/test/java/com/example/tokenpatterns/PatternRunnerTest.java) that runs the `compression` sample and asserts that the `Focused answerer` trace event has fewer input tokens than the `Context compressor` event. Find each event in `result.trace()` by its `agent()` name and compare `inputTokens()`.

```bash
sh ./mvnw test -Dtest=PatternRunnerTest
```

## Answer key

<details>
<summary>Scavenger hunt answers</summary>

1. Eight entries, from 09:02 to 09:47. The team agreed on the fix at 09:47.
2. The small model compresses; the large model answers.
3. `compactContext`.
4. Signal, constraint, likely cause, and next actions.
5. "Call out uncertainty rather than inventing details."
6. About four characters per token: code points divided by 4, rounded up.
7. Observed tokens plus twice the estimated tokens of the history, or a minimum estimate if that is larger. It is modeled, not measured.
8. String values longer than 700 characters are cut to 699 characters and an ellipsis.

</details>
