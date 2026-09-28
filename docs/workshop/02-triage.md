# Module 2: Triage

[Workshop home](README.md) · Previous: [Router](01-router.md) · Next: [Context compression](03-context-compression.md)

> **Mission:** Play "beat the gate". Predict whether the zero-token triage gate will classify each request as SIMPLE (small model) or COMPLEX (large model), then run it and score your predictions.

**Idea in one line:** a deterministic Java rule sends routine requests to the small model and reserves the large model for complex work, without spending a single token on the decision.

## Warm-up: predict

Ask the room: *Should the word "architecture" alone be enough to send a request to the large model?* Take a quick vote.

## Part 1: Demo mission

1. Select **Triage** and run the sample request unchanged:

   ```text
   What does HTTP 429 mean and what should a client do?
   ```

2. Run the complex prompt from the README:

   ```text
   Design a secure distributed multi-region architecture for a payment system, including migration trade-offs.
   ```

3. Now play the game. For each request below, **write down SIMPLE or COMPLEX first**, then run it and read `complexity` in the **AgenticScope** panel.

   | # | Request | Your prediction | Actual |
   |---|---|---|---|
   | A | `What is a distributed system?` | | |
   | B | `Explain our architecture.` | | |
   | C | `Recommend an architecture for our cache.` | | |
   | D | `What is distributed architecture migration?` | | |
   | E | `How should we plan a secure multi-region failover?` | | |
   | F | `Briefly, how should we plan a secure multi-region failover?` | | |

4. Finally, write one request of your own that you think will fool the gate, and test it.

**Checkpoint.** You have completed the mission when:

- [ ] You have seen both a **Fast-path responder** run (small model) and a **Deep reasoning responder** run (large model).
- [ ] You have confirmed in the trace that the **Triage gate** step used zero tokens.
- [ ] You have scored all six predictions and can explain at least one surprise.

## Part 2: Examine the code

1. **The gate.** [`HeuristicTriage` in PatternAgents.java](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L77-L130). This is a plain Java class with an `@Agent` method, so Agentic runs it like any other agent. Read `complexity(...)` line by line. It adds up a score from four kinds of signal:
   - `DOMAIN_TERMS`: counted, but capped.
   - `DESIGN_INTENT`: words that ask for a decision or analysis.
   - Request length.
   - `DEFINITION` and `BRIEF`: signals that pull a short request back down.
2. **The two responders.** [`FastPathResponder` and `DeepReasoningResponder`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L132-L155). Compare their prompts: the deep path is given a word budget and a fixed structure.
3. **The workflow.** [`runTriage` in PatternRunner.java](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L166-L187). `new HeuristicTriage(trace)` is passed the trace so a zero-token step still appears in the trace.
4. **The tests.** [HeuristicTriageTest.java](../../src/test/java/com/example/tokenpatterns/agent/HeuristicTriageTest.java). Each row is a request and its expected class. This is the calibration set for the rule.

## Part 3: Scavenger hunt

1. What is the maximum number of points that `DOMAIN_TERMS` can contribute, however many domain words appear?
2. What score does a request need to be COMPLEX?
3. At what two character lengths does a request earn extra points?
4. A short request that starts with "What is" loses points. How many, and what is the length limit for that rule?
5. Which pattern flags the phrase "in one sentence"?
6. What word limit does the deep reasoning prompt set?
7. What multiplier of observed tokens does the triage baseline use?
8. Find the test row in `HeuristicTriageTest` that contains "architecture" and is still expected to be SIMPLE.

## Part 4: Q&A

1. Request C escalated but request B did not. Is that the behavior you want? What one extra signal made the difference?
2. Request E escalated but F, the same request with "Briefly," in front, did not. Is that a bug or a feature?
3. The takeaway says, "Calibrate escalation rules with real production samples." How would you build that calibration set, and what would you measure?
4. When would you replace this rule with a small-model classifier, as the router does?

<details>
<summary>Facilitator notes</summary>

1. "Recommend" is design intent, so one domain term plus one intent word reaches a score of 2. It is defensible: a recommendation request is asking for judgment. It also shows that the rule rewards phrasing, which is why it needs calibration.
2. Arguably a feature: the user asked for a short answer, which the small model can give. It is also an easy way to game the gate, so watch for users who learn it.
3. Label real requests by the tier that actually answered them well, add them as test rows, and track escalation rate and the rate of simple-path answers that needed a follow-up.
4. When the signals are semantic rather than lexical, or when the rule's test set keeps growing exceptions. The trade-off is that the classifier then spends tokens on every request.

</details>

## Stretch challenge (credential-free)

Add your "fool the gate" request, and the result you believe is *correct*, as a new row in [HeuristicTriageTest.java](../../src/test/java/com/example/tokenpatterns/agent/HeuristicTriageTest.java):

```bash
sh ./mvnw test -Dtest=HeuristicTriageTest
```

If it fails, decide as a team: is the rule wrong, or is your expectation wrong? If you change the rule, rerun the whole class so every existing row still passes.

## Answer key

<details>
<summary>Prediction game</summary>

| # | Result | Why |
|---|---|---|
| Sample | SIMPLE | No domain terms or intent; "What does" is a short definition (−2) |
| Complex prompt | COMPLEX | Domain terms capped at 2, plus "Design" (+1) = 3 |
| A | SIMPLE | "distributed" (+1), short definition (−2) = −1 |
| B | SIMPLE | "architecture" (+1) only. One keyword is not enough |
| C | COMPLEX | "architecture" (+1) and "Recommend" (+1) = 2 |
| D | SIMPLE | Three domain terms, capped at 2, plus "migration" as intent (+1), minus the short definition (−2) = 1 |
| E | COMPLEX | "secure", "multi-region", "failover", capped at 2, plus "plan" (+1) = 3 |
| F | SIMPLE | Same as E, but "Briefly" (−2) = 1 |

</details>

<details>
<summary>Scavenger hunt answers</summary>

1. 2, from `Math.min(2, domainTerms)`.
2. 2 or more: `score >= 2 ? "COMPLEX" : "SIMPLE"`.
3. More than 180 characters (+1) and more than 400 characters (+1 more).
4. 2 points, for requests of at most 100 characters.
5. `BRIEF`.
6. At most 150 words.
7. 2.0 × observed tokens, or a minimum estimate if that is larger. It is modeled, not measured.
8. `What does the word architecture mean? Answer briefly. | SIMPLE`.

</details>
