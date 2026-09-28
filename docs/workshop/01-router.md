# Module 1: Router

[Workshop home](README.md) · Next: [Triage](02-triage.md)

> **Mission:** Send requests that reach each of the three specialists (Knowledge, Code, and Architecture) and explain from the trace why only one ran each time.

**Idea in one line:** a small, cheap model classifies the request, and only the matching specialist runs.

## Warm-up: predict

Before running anything, ask the room: *If every request went to the large model, and the router sends most requests to the small or medium model, where does the saving come from, and where could it be lost?*

Write the answers down; you will revisit them in the Q&A.

## Part 1: Demo mission

1. Open the lab and select **Router**.
2. Run the sample request unchanged:

   ```text
   Why does my Java stream return an empty list after I add a filter?
   ```

3. Replace the request and run it again for each of the other specialists. Try these, or write your own:

   ```text
   What is idempotency?
   ```

   ```text
   Recommend an architecture for a multi-region payment service with one trade-off and a safe rollout plan.
   ```

4. After each run, record the results in a table like this:

   | Request | `route` in AgenticScope | Specialist in the trace | Model | Observed tokens |
   |---|---|---|---|---|
   | Java stream | | | | |
   | Idempotency | | | | |
   | Architecture | | | | |

**Checkpoint.** You have completed the mission when:

- [ ] Your table has all three routes: `CODE`, `KNOWLEDGE`, and `ARCHITECTURE`.
- [ ] Each trace shows exactly two model calls: the route classifier and one specialist.
- [ ] You can say which deployment answered each request.

> The classifier is a model, so a route is its decision rather than a rule. If a request lands somewhere unexpected, keep it: it is material for the Q&A.

## Part 2: Examine the code

Open these in order.

1. **The classifier prompt.** [`RouteClassifier` in PatternAgents.java](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L32-L42). Note the `outputKey` in the `@Agent` annotation and the exact labels the prompt allows.
2. **The three specialists.** [`CodeSpecialist`, `KnowledgeSpecialist`, and `ArchitectureSpecialist`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L44-L75). Each prompt is short and scoped to one job.
3. **The workflow.** [`runRouter` in PatternRunner.java](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L136-L164). Two builders are combined:
   - `conditionalBuilder()` gives each specialist a predicate over `AgenticScope`.
   - `sequenceBuilder()` runs the classifier first and the conditional block second.
4. **The model tiers.** In the same method, find the `.chatModel(...)` call on each agent. This is where the cost of each route is decided.
5. **The baseline.** [`projectedBaseline`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L306-L319). Find the router case and note how the modeled baseline is derived.

## Part 3: Scavenger hunt

Find each answer in the code or in your runs.

1. Which model tier runs the route classifier?
2. Under which `AgenticScope` key does the classifier write its decision?
3. What exact string must the classifier return for the Code specialist to run?
4. Which specialist runs on the **medium** model, and which on the **large** model?
5. The Knowledge specialist has a length limit in its prompt. What is it?
6. What multiplier of observed tokens does the router's modeled baseline use?
7. Which of the three takeaways in **What this run proved** tells you what to measure besides tokens?
8. In [PatternCatalog.java](../../src/main/java/com/example/tokenpatterns/service/PatternCatalog.java#L48-L78), what does the pattern list under **Watch out**?

## Part 4: Q&A

1. The predicates compare the route with `"CODE".equals(...)`. What happens if the classifier returns `code` or `CODE.`? How would you make the router safer?
2. The projected saving is based on a multiplier, not on a real large-model run. What would you need to measure to turn it into evidence?
3. When does routing cost *more* than sending everything to the large model?
4. Triage (the next module) uses a Java rule instead of a model to choose a path. Why might the router still need a model to classify?

<details>
<summary>Facilitator notes</summary>

1. No predicate matches, so no specialist runs and no answer is written. Mitigations: normalize the label (trim, uppercase, strip punctuation), add a default branch to a capable specialist, and count unknown labels as misroutes.
2. Run the same requests through the large model and compare observed tokens and answer quality, or sample production traffic both ways. Until then, the number is a projection and must be labeled as one.
3. When misroutes are common: a weak answer from the small model leads to a retry or escalation, so you pay for two answers. The classifier call is also pure overhead for workloads that almost always need the large model.
4. The categories are semantic (code versus concept versus design), so keyword rules would misroute often. A small model is a cheap way to make that judgment; triage works with a rule because it only estimates complexity.

</details>

## Stretch challenge (credential-free)

Add a test to [PatternRunnerTest.java](../../src/test/java/com/example/tokenpatterns/PatternRunnerTest.java) that runs the `router` pattern with `What is idempotency?` and asserts that:

- `result.scope().get("route")` is `KNOWLEDGE`, and
- the trace contains `Knowledge specialist` but neither `Code specialist` nor `Architecture specialist`.

Use `aDefinitionThatMentionsArchitectureTakesTheFastPath` as a model. The test-only `StubChatModel` decides routes by keyword, so the test needs no Azure access. Run it with `sh ./mvnw test -Dtest=PatternRunnerTest`.

## Answer key

<details>
<summary>Scavenger hunt answers</summary>

1. The small model: `.chatModel(models.small())` on `RouteClassifier`.
2. `route`, from `outputKey = "route"`.
3. Exactly `CODE`. The predicate is `"CODE".equals(scope.readState("route", ""))`.
4. Code runs on the medium model; Architecture runs on the large model. Knowledge runs on the small model.
5. No more than four sentences.
6. 2.5 × observed tokens, or a minimum estimate if that is larger. It is modeled, not measured.
7. "Track misroutes alongside token savings."
8. Routing errors can cost more than model savings. Keep a fallback and measure misroutes.

</details>
