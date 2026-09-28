# Module 3: Context compression

[Workshop home](README.md) · Previous: [Triage](02-triage.md) · Next: [RAG](04-rag.md)

**Idea:** the small model condenses a long history into key facts, and the large model reasons over that summary instead of the full transcript.

## Try it

Select **Context compression** and run the sample question. The server adds a fixed incident timeline.

In the **Trace**, compare input tokens: the **Focused answerer** (large model) reads far less than the **Context compressor** (small model). Open `compactContext` in **AgenticScope** to see everything the large model received.

## Read the code

- [`ContextCompressor` and `FocusedAnswerer`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L157-L180): the answerer takes `compactContext`, not the full `context`.
- [`runCompression`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L189-L204)

## Discuss

1. The small model still reads the whole history. Where is the saving?
2. What kinds of detail are most at risk of being dropped?

<details>
<summary>Suggested answers</summary>

1. The expensive model reads less. It pays off most when the summary is reused across many turns.
2. Exact numbers and timestamps. That is why the answerer is told to call out uncertainty.

</details>
