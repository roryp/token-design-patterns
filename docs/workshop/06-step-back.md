# Module 6: Step-back planning

[Workshop home](README.md) · Previous: [Tool use](05-tool-use.md) · Next: [Caching](07-caching.md)

> **Mission:** Measure exactly what the plan costs on a hard task and on a trivial one, and decide, with numbers, when step-back planning is worth it.

**Idea in one line:** the small model drafts a short plan and the large model follows it. The plan adds tokens to this run; the hoped-for return is less rework across runs.

## Warm-up: predict

Ask the room: *This pattern always shows a 0% projected saving. Why would anyone use it?*

## Part 1: Demo mission

1. Select **Step-back planning** and run the sample goal unchanged:

   ```text
   Design a safe migration from a monolith to event-driven services without a big-bang rewrite.
   ```

2. In **AgenticScope**, read `plan`. Then read the answer. Does each paragraph of the answer follow one step of the plan?
3. Record the first line of **What this run proved**. It has the form "The plan cost *N* output tokens and framed a *M* token answer."
4. Now run a trivial request through the same pattern:

   ```text
   What is a Java record?
   ```

5. Run the same trivial request with **Triage** selected, and compare.

   | Run | Pattern | Model calls | Planner output tokens | Observed tokens | Wall time |
   |---|---|---|---|---|---|
   | Migration | Step-back | | | | |
   | Java record | Step-back | | | | |
   | Java record | Triage | | — | | |

**Checkpoint.** You have completed the mission when:

- [ ] You have read the plan and matched it to the answer's paragraphs.
- [ ] You have recorded the plan's output-token cost for both step-back runs.
- [ ] You can state, with numbers from your table, how much the plan added to the trivial request compared with triage.
- [ ] You have noticed that **Projected saving** is 0% for both step-back runs.

## Part 2: Examine the code

1. **The planner.** [`StepBackPlanner` in PatternAgents.java](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L331-L340). Note how tightly the plan's shape is specified.
2. **The executor.** [`PlanExecutor`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L342-L353). It reads both `plan` and `request`. Find the instruction that stops it from reopening decisions.
3. **The workflow.** [`runStepBack` in PatternRunner.java](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L234-L249).
4. **Honest metrics.** In the same file, read the step-back cases in [`projectedBaseline`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L306-L319), [`measurementBasis`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L321-L332), and [`takeaways`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L356-L361). `trace.outputTokensFor(...)` is how the takeaway gets the plan's cost.
5. **The guard test.** `stepBackClaimsNoSingleTurnTokenSaving` in [PatternRunnerTest.java](../../src/test/java/com/example/tokenpatterns/PatternRunnerTest.java) fails if anyone makes this pattern claim a saving.

## Part 3: Scavenger hunt

1. How many planning steps must the planner produce, and what three topics must they cover?
2. Which model tier plans, and which executes?
3. What is the executor's word limit?
4. What does the step-back baseline equal?
5. Quote the measurement basis for step-back.
6. Which three things does the third takeaway say to measure across turns?
7. In [PatternCatalog.java](../../src/main/java/com/example/tokenpatterns/service/PatternCatalog.java#L185-L209), what does **Watch out** say about trivial work?
8. What is the primary metric listed for this pattern?

## Part 4: Q&A

1. Your trivial request paid for a plan it did not need. Which earlier module already has the tool to avoid that, and how would you combine the two?
2. The planner runs on the small model and the executor on the large one. What would you expect if you swapped them?
3. "Rework and retry rate" cannot be measured in one run. Design a measurement: what would you log, and over how many runs?
4. Is a plan the same as compression? Both write a short state that a larger model reads.

<details>
<summary>Facilitator notes</summary>

1. Triage. Put the `HeuristicTriage` gate in front and use a conditional workflow: simple requests go straight to a responder, and complex ones go through the planner and executor. The Watch out note says the same: gate this pattern by complexity.
2. A large-model plan may be better but costs more per plan token, and a small-model executor may not follow a good plan well. The lab puts cheap tokens into framing and expensive tokens into the final answer.
3. Log, per task, the number of attempts until acceptance, follow-up corrections, and total tokens across all attempts, with and without the planner, over enough tasks to compare distributions rather than single runs.
4. No. Compression removes information from an existing context. A plan adds new structure, such as constraints, order, and validation, that did not exist before.

</details>

## Stretch challenge (credential-free)

Add a test to [PatternRunnerTest.java](../../src/test/java/com/example/tokenpatterns/PatternRunnerTest.java) that runs the step-back sample and asserts that:

- `result.scope()` contains a non-blank `plan`, and
- in `result.trace()`, the `Step-back planner` event comes before the `Plan executor` event, by comparing their `sequence()` values.

```bash
sh ./mvnw test -Dtest=PatternRunnerTest
```

## Answer key

<details>
<summary>Scavenger hunt answers</summary>

1. Exactly three short steps, covering constraints, sequencing, and validation.
2. The small model plans; the large model executes.
3. At most 200 words, with one short paragraph per plan step.
4. Observed tokens, so avoided tokens and projected saving are zero.
5. "Planning adds tokens to this turn; step-back pays back across avoided retries and rework, which one run cannot measure."
6. Rework avoided, retry rate, and completion rate.
7. "For trivial work the planning call is overhead. Gate this pattern by complexity."
8. Rework and retry rate.

</details>
