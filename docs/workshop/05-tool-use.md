# Module 5: Tool use

[Workshop home](README.md) · Previous: [RAG](04-rag.md) · Next: [Step-back planning](06-step-back.md)

**Idea:** Java reads the token counts and rates and does the arithmetic; the small model only explains the verified result.

## Try it

Select **Tool use** and run the sample:

```text
Estimate monthly cost for 50M input tokens and 10M output tokens at $0.15/$0.60 per million.
```

Check `toolResult` in **AgenticScope**: `50M input × $0.15/M + 10M output × $0.60/M = $13.50`.

Now try an unclear request, such as `How much do 50M tokens cost at $0.15 per million?`. It is rejected before any model is called.

## Read the code

- [TokenCostRequest.java](../../src/main/java/com/example/tokenpatterns/agent/TokenCostRequest.java): parses the request with `BigDecimal` and rejects anything ambiguous.
- [`TokenCostCalculator` and `ToolGroundedExplainer`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L298-L329): a zero-token Java agent, then a model told not to redo the math.

## Discuss

1. A model can do arithmetic. Why take that job away from it?
2. What changes when a tool can send an email or charge a card?

<details>
<summary>Suggested answers</summary>

1. Java is exact, auditable, and free. A model can be confidently wrong.
2. You need authorization, timeouts, auditing, and confirmation for irreversible actions.

</details>
