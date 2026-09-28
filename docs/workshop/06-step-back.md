# Module 6: Step-back planning

[Workshop home](README.md) · Previous: [Tool use](05-tool-use.md) · Next: [Caching](07-caching.md)

**Idea:** the small model drafts a short plan and the large model follows it. The plan adds tokens now in the hope of less rework later.

## Try it

Select **Step-back planning** and run the sample goal. Read `plan` in **AgenticScope**, then check that the answer follows it.

Then run `What is a Java record?`. The plan is pure overhead for a trivial request. **Projected saving** is 0% for both runs.

## Read the code

- [`StepBackPlanner` and `PlanExecutor`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L331-L353)
- [`runStepBack`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L234-L249)

## Discuss

1. This pattern always shows 0% projected saving. Why use it?
2. How would you avoid paying for a plan on trivial requests?

<details>
<summary>Suggested answers</summary>

1. Its payoff is fewer retries and less rework across turns, which one run cannot measure.
2. Put the triage gate in front, so only complex requests are planned.

</details>
