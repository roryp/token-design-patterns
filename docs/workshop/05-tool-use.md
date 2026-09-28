# Module 5: Tool use

[Workshop home](README.md) · Previous: [RAG](04-rag.md) · Next: [Step-back planning](06-step-back.md)

> **Mission:** Get an exact, auditable cost from the deterministic calculator in three different phrasings, then trigger the validation errors that stop an unclear request before any model is called.

**Idea in one line:** Java reads the stated token counts and rates and does the arithmetic; the small model only explains the verified result.

## Warm-up: predict

Ask the room: *A language model can do arithmetic. Why would you take that job away from it?*

## Part 1: Demo mission

### Round 1: exact answers

Run each request with **Tool use** selected. Write the calculator result from the **Trace** or `toolResult` in **AgenticScope**, and check it with a calculator of your own.

| # | Request | `toolResult` |
|---|---|---|
| A | `Estimate monthly cost for 50M input tokens and 10M output tokens at $0.15/$0.60 per million.` (the sample) | |
| B | `input tokens: 1.5M and output tokens: 250k at $2.50 per million input and $10 per million output` | |
| C | `2B input tokens, no output tokens, $0.15/$0.60 per million` | |

### Round 2: make it refuse

Each request below is rejected. Predict the reason, run it, and compare your prediction with the error message.

| # | Request | Predicted reason | Actual message |
|---|---|---|---|
| D | `How much do 50M tokens cost at $0.15 per million?` | | |
| E | `50M input tokens and 10M output tokens and 5M input tokens at $0.15/$0.60 per million` | | |
| F | `-5M input tokens and 10M output tokens at $0.15/$0.60 per million` | | |
| G | `1.5 input tokens and 2 output tokens at $1/$2 per million` | | |
| H | `input tokens: 1.5M, output tokens: 250k, $2.50 per million input and $10 per million output` | | |

**Checkpoint.** You have completed the mission when:

- [ ] Your three results in Round 1 match your own arithmetic exactly.
- [ ] You have confirmed that the **Cost calculator** step used zero tokens and the explainer ran on the small model.
- [ ] You have seen at least four different rejection messages, and noticed that a rejected request produces no trace because no model was called.
- [ ] You can explain why H is rejected even though B, which is almost the same, is accepted.

## Part 2: Examine the code

1. **Validation first.** [`run` in PatternRunner.java](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L70-L80). Find the `tool-use` check that happens before `modelCatalog.models()` is called.
2. **The parser.** [TokenCostRequest.java](../../src/main/java/com/example/tokenpatterns/agent/TokenCostRequest.java). Read the class comment, then the named patterns: `COUNT_BEFORE_LABEL`, `LABEL_BEFORE_COUNT`, `NO_TOKENS`, `RATE_PAIR`, and `LABELED_RATE`. Then read `parse`, `single`, `total`, and `describe`.
3. **The tool as an agent.** [`TokenCostCalculator` in PatternAgents.java](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L298-L317). A plain Java `@Agent` whose `outputKey` is `toolResult`.
4. **The explainer.** [`ToolGroundedExplainer`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L319-L329). Note what it is told not to do.
5. **The workflow.** [`runToolUse`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L220-L232).
6. **The tests.** [TokenCostRequestTest.java](../../src/test/java/com/example/tokenpatterns/agent/TokenCostRequestTest.java). Two tables: accepted phrasings with their totals, and rejected phrasings with their messages.

## Part 3: Scavenger hunt

1. Which Java type holds the counts, rates, and total, and why does that matter for money?
2. Which two synonyms does the parser accept for "input" and "output"?
3. List the unit words the parser accepts for thousand, million, and billion.
4. What is the largest token count the parser accepts?
5. Write the exact `describe()` output for request A.
6. What does the explainer prompt forbid?
7. What multiplier of observed tokens does the tool-use baseline use?
8. Which takeaway warns you about the next step, tools that change things?
9. In the rejected-phrasings test table, find the row showing that `1,5M` is not read as 1.5 million.

## Part 4: Q&A

1. Request H was rejected because of a comma after `1.5M`. Is that parser too strict? What is the cost of being too lenient?
2. The parser rejects rather than guesses. Where else in your systems should a model's input be validated before any tokens are spent?
3. This tool has no side effects. What changes when a tool can send an email, charge a card, or write to a database?
4. Where is the line between "the model explains" and "the model decides"? Could the explainer's words still mislead even when the number is right?

<details>
<summary>Facilitator notes</summary>

1. In `LABEL_BEFORE_COUNT`, a count may not be followed directly by a comma, a period, a word character, or a slash, so `1.5M,` is not read. The test table shows semicolons working as separators. A lenient parser that misreads one number produces a confident, wrong bill; rejecting with a clear message costs one retry.
2. Anywhere the input has a schema: IDs, dates, amounts, enum values. Rejecting early saves tokens and makes errors deterministic.
3. The takeaway lists authorization and timeouts. Add idempotency, auditing, confirmation for irreversible actions, and a least-privilege identity for the tool.
4. The explainer can frame a correct number badly, such as calling a monthly figure yearly. Keep its prompt narrow and show the tool result alongside the explanation, as the trace does.

</details>

## Stretch challenge (credential-free)

Add request B as a new accepted row in `readsValuesByTheirLabelsNotTheirPositions` in [TokenCostRequestTest.java](../../src/test/java/com/example/tokenpatterns/agent/TokenCostRequestTest.java), and request H as a new rejected row in `rejectsMissingNegativeRepeatedOrAmbiguousValuesInsteadOfGuessing`. Both tables use `|` as the column delimiter.

```bash
sh ./mvnw test -Dtest=TokenCostRequestTest
```

## Answer key

<details>
<summary>Round 1 and Round 2</summary>

| # | Result |
|---|---|
| A | `50M input × $0.15/M + 10M output × $0.60/M = $13.50` |
| B | `1.5M input × $2.50/M + 0.25M output × $10.00/M = $6.25` |
| C | `2000M input × $0.15/M + 0M output × $0.60/M = $300.00` |
| D | The request must state an input token count. "50M tokens" has no input or output label |
| E | The request states more than one input token count |
| F | Token counts and rates cannot be negative |
| G | Token counts must be whole tokens |
| H | The request must state an input token count. The comma directly after `1.5M` stops the label-first pattern from matching |

</details>

<details>
<summary>Scavenger hunt answers</summary>

1. `BigDecimal`. It represents decimal amounts such as $0.135 exactly, without binary floating-point rounding.
2. `prompt` for input and `completion` for output.
3. `k` and `thousand`; `m`, `mn`, and `million`; `b`, `bn`, and `billion`.
4. 1,000,000,000,000,000 (`MAX_TOKENS`).
5. `50M input × $0.15/M + 10M output × $0.60/M = $13.50`.
6. It must not redo or alter the arithmetic.
7. 1.55 × observed tokens, or a minimum estimate if that is larger. It is modeled, not measured.
8. "Add authorization and timeouts before tools perform side effects."
9. `1,5M input tokens and 10M output tokens at $0.15/$0.60 per million | must state an input token count`.

</details>
