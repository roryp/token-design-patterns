# Module 2: Triage

[Workshop home](README.md) · Previous: [Router](01-router.md) · Next: [Context compression](03-context-compression.md)

**Idea:** a deterministic Java rule sends routine requests to the small model and complex ones to the large model, spending zero tokens on the decision.

## Try it

Select **Triage**. Before each run, guess SIMPLE or COMPLEX, then check `complexity` in **AgenticScope**.

- `What does HTTP 429 mean and what should a client do?` (SIMPLE)
- `Design a secure distributed multi-region architecture for a payment system, including migration trade-offs.` (COMPLEX)
- `Explain our architecture.` (SIMPLE: one keyword is not enough)

The **Triage gate** row in the **Trace** shows zero tokens.

## Read the code

- [`HeuristicTriage`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L77-L130): adds points for domain terms, design intent, and length; subtracts for short definitions and "briefly". A score of 2 or more is COMPLEX.
- [HeuristicTriageTest.java](../../src/test/java/com/example/tokenpatterns/agent/HeuristicTriageTest.java): the rule's calibration set.

## Discuss

1. Adding "Briefly," to a complex request makes it SIMPLE. Bug or feature?
2. When would you replace this rule with a small-model classifier?

<details>
<summary>Suggested answers</summary>

1. Mostly a feature: the user asked for a short answer. It is also easy to game, so calibrate with real traffic.
2. When the signals are about meaning rather than wording. The classifier then costs tokens on every request.

</details>
