# Module 1: Router

[Workshop home](README.md) · Next: [Triage](02-triage.md)

**Idea:** a small model classifies the request, and only the matching specialist runs.

## Try it

Select **Router** and run each request. Check `route` in **AgenticScope** and which specialist appears in the **Trace**.

- `Why does my Java stream return an empty list after I add a filter?` (CODE)
- `What is idempotency?` (KNOWLEDGE)
- `Recommend an architecture for a multi-region payment service with one trade-off and a safe rollout plan.` (ARCHITECTURE)

Each run makes exactly two model calls: the classifier and one specialist.

## Read the code

- [`RouteClassifier` and the three specialists](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L32-L75)
- [`runRouter`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L136-L164): a sequence of the classifier and a conditional block. Each `.chatModel(...)` call sets the cost of a route.

## Discuss

1. What happens if the classifier returns `code` instead of `CODE`?
2. When does routing cost more than sending everything to the large model?

<details>
<summary>Suggested answers</summary>

1. No specialist matches, so there is no answer. Normalize the label and add a default branch.
2. When misroutes are common: a weak small-model answer leads to a retry, so you pay twice.

</details>
