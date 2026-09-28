# TokenFlow Lab workshop

[Back to the README](../../README.md)

One short module per pattern. Each module has three steps:

1. **Try it.** Run the pattern in the lab.
2. **Read the code.** Open two or three links to see how it works.
3. **Discuss.** Talk through a couple of questions. Suggested answers are collapsed below them.

| # | Module | What you will see |
|---|---|---|
| 1 | [Router](01-router.md) | A small model picks one specialist |
| 2 | [Triage](02-triage.md) | A zero-token Java rule picks the small or large model |
| 3 | [Context compression](03-context-compression.md) | The large model reads a short summary, not the full history |
| 4 | [RAG](04-rag.md) | The model answers only from retrieved chunks |
| 5 | [Tool use](05-tool-use.md) | Java does the arithmetic; the model explains it |
| 6 | [Step-back planning](06-step-back.md) | A cheap plan frames an expensive answer |
| 7 | [Caching](07-caching.md) | Azure reports a cache MISS, then a HIT |
| 8 | [Batching](08-batching.md) | Parallel calls cut waiting, not tokens |

## Before you start

- Open a running lab: the facilitator's Azure deployment or a [local run](../../README.md#run-locally). Every run makes real, paid model calls.
- Keep the repository open to follow the code links.

## Reading a run

- **Trace**: one row per agent, with its model, tokens, and duration. Java agents show zero tokens.
- **AgenticScope**: the state each agent wrote, such as `route` or `plan`.
- **Observed** tokens are measured by Azure. **Modeled baseline** and **Projected saving** are estimates. When someone quotes a saving, ask which one it is.
