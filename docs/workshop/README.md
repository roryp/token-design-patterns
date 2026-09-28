# TokenFlow Lab workshop

[Back to the README](../../README.md)

This hands-on workshop has one module for each of the eight token design patterns in TokenFlow Lab. Each module follows the same four-part loop:

1. **Demo mission.** Run the pattern in the live lab and reach a concrete, checkable result.
2. **Examine the code.** Follow a guided reading of the agents, workflow, and metrics that produced the run.
3. **Scavenger hunt.** Find specific facts in the code and in the run output, then check them against the answer key.
4. **Q&A.** Discuss trade-offs as a group, with facilitator notes.

Every module ends with an optional stretch challenge and a collapsible answer key.

## Modules

| # | Module | Mission |
|---|---|---|
| 1 | [Router](01-router.md) | Make requests reach each of the three specialists |
| 2 | [Triage](02-triage.md) | Beat the zero-token complexity gate: predict SIMPLE or COMPLEX before every run |
| 3 | [Context compression](03-context-compression.md) | Prove that the large model never saw the full incident history |
| 4 | [RAG](04-rag.md) | Retrieve the right two chunks, then get the model to admit when it has no evidence |
| 5 | [Tool use](05-tool-use.md) | Get an exact, auditable cost and trigger the validation errors |
| 6 | [Step-back planning](06-step-back.md) | Measure what the plan costs and decide when it is worth it |
| 7 | [Caching](07-caching.md) | Produce a provider-reported MISS, then a HIT, then a MISS again |
| 8 | [Batching](08-batching.md) | Show that parallelism cuts waiting but not tokens |

The modules can be run in any order, but running them in sequence builds from the simplest workflow to the provider-level patterns.

## Before you start

**Participants** need:

- A browser pointed at a running lab: either the facilitator's Azure deployment or a local run (see [Run locally](../../README.md#run-locally)).
- The repository open in an editor or on GitHub, to follow the code links.
- Optional, for stretch challenges: Java 21 and a local clone. The unit tests use stub models and need no Azure access:

  ```bash
  sh ./mvnw test          # macOS / Linux
  .\mvnw.cmd test         # Windows
  ```

**Facilitators** need a lab with the three [model deployments](../../README.md#model-tiers). Every run in a demo mission makes real, paid model calls. Stretch challenges that only run unit tests are free.

## Orientation: reading a run

Every module relies on the same parts of the page. Point these out once at the start.

| Panel | What to look for |
|---|---|
| **Execution graph** | The path this run actually took through the pattern |
| **Observed** | Input plus output tokens, as reported by Azure. This is measured |
| **Modeled baseline** and **Projected saving** | An estimate of one large-model call with all the context. This is modeled, not measured |
| **Wall time** | End-to-end time for the run |
| **Agentic steps** | Workflow and agent invocations, including zero-token Java agents |
| **Trace** | One row per invocation, with its model, tokens, and duration |
| **AgenticScope** | The shared state each agent wrote, such as `route`, `complexity`, or `plan` |
| **What this run proved** | Takeaways the server derived from this specific run |

The rule that runs through the whole workshop: **observed tokens are measured; baselines are projected.** Whenever a participant quotes a saving, ask which of the two they are quoting.

## Where the code lives

| File | Role in the workshop |
|---|---|
| [PatternAgents.java](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java) | Every prompt and every deterministic Java agent |
| [PatternRunner.java](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java) | How each workflow is assembled, and how metrics and takeaways are computed |
| [PatternCatalog.java](../../src/main/java/com/example/tokenpatterns/service/PatternCatalog.java) | Each pattern's sample input, teaching notes, and graph |
| [TraceCollector.java](../../src/main/java/com/example/tokenpatterns/service/TraceCollector.java) | Records invocations, provider usage, and cache status |
| [OfficialSdkChatModel.java](../../src/main/java/com/example/tokenpatterns/agent/OfficialSdkChatModel.java) | The Responses API transport and its prompt-cache controls |

## Running the session

- Ask participants to **predict before they run**. Each module opens with a warm-up question; collect answers before the demo.
- Run the scavenger hunt in pairs or small teams. The first team to find every clue explains its answers to the room.
- Keep the answer keys collapsed until teams have tried every clue.
- Close each module with its Q&A questions. The facilitator notes give the points worth drawing out, not the only correct answer.

## Wrap-up questions

After the last module, ask the group:

1. Which patterns remove or downgrade model calls, which shrink the context sent to a model, and which do neither but improve something else?
2. Which three patterns rely on a Java agent that spends zero tokens? What would change if a model did that job instead?
3. For which patterns is **Projected saving** always 0%, and why is that honest rather than a weakness?
4. Pick a workload from your own team. Which two patterns would you combine for it, and which metric would tell you it worked?

<details>
<summary>Facilitator notes</summary>

1. Fewer or cheaper calls: router, triage, and tool use. Smaller context: compression and RAG. Caching keeps the same input token count but lets Azure reuse part of it at a lower rate. Neither: batching improves wall time, and step-back aims to reduce rework across turns.
2. Triage (`HeuristicTriage`), RAG (`LocalKnowledgeRetriever`), and tool use (`TokenCostCalculator`). A model would add tokens and latency, and could make probabilistic mistakes in a job with a deterministic answer.
3. Step-back, caching, and batching. `projectedBaseline` in `PatternRunner` sets their baseline equal to observed usage, because a single run cannot show content-token avoidance for them. Claiming otherwise would present a projection as a measurement.
4. Open discussion. Strong answers name a measurable signal, such as misroute rate, escalation rate, retrieval recall, cache-read share, or throughput.

</details>
