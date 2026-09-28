# Module 4: Retrieval augmented generation (RAG)

[Workshop home](README.md) · Previous: [Context compression](03-context-compression.md) · Next: [Tool use](05-tool-use.md)

> **Mission:** Predict which knowledge chunks the zero-token retriever will select, then get the model to say plainly when the retrieved evidence does not answer the question.

**Idea in one line:** a Java retriever picks up to two relevant chunks from a local knowledge base, and the medium model answers only from those chunks.

## Warm-up: predict

Ask the room: *If the retriever finds nothing relevant, what should the model do: answer from its own knowledge, or refuse?*

## Part 1: Demo mission

1. Select **RAG** and run the sample question unchanged:

   ```text
   How does AgenticScope share state between agents?
   ```

   In **AgenticScope**, open `context`. It holds the chunks the generator received.

2. For each request below, **predict the chunk titles first**, then run it and check `context`. The knowledge base has seven chunks; their titles are in Part 2.

   | # | Request | Predicted chunks | Actual chunks |
   |---|---|---|---|
   | A | `When should I use a parallel mapper instead of a parallel workflow?` | | |
   | B | `How do I observe token usage per agent?` | | |
   | C | `How does AgenticScope handle retries and timeouts?` | | |
   | D | `What is the capital of France?` | | |

3. For C and D, read the answer carefully. Does the model say the context does not cover the question, or does it fill the gap from general knowledge?

**Checkpoint.** You have completed the mission when:

- [ ] You have compared your predictions with `context` for all four requests.
- [ ] You have seen the retriever return `No relevant knowledge was found for this request.`
- [ ] You have seen an answer that says what the retrieved context does *not* state.
- [ ] You have confirmed that the **Knowledge retriever** step used zero tokens.

## Part 2: Examine the code

1. **The knowledge base.** [`LocalKnowledgeRetriever` in PatternAgents.java](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L182-L284). The seven chunks are in `KNOWLEDGE`: AgenticScope, Sequential workflow, Conditional workflow, Parallel workflow, Parallel mapper, Observability, and Context engineering.
2. **Scoring.** Read `retrieveContext`, `score`, `weights`, and `terms`. Work out:
   - how a request is split into terms and which words are ignored,
   - why a title match is worth more than a body match, and
   - why a word that appears in many chunks counts for less.
3. **The generator prompt.** [`GroundedGenerator`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L286-L296). Find the sentence that governs your C and D results.
4. **The workflow.** [`runRag` in PatternRunner.java](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L206-L218).
5. **The tests.** [LocalKnowledgeRetrieverTest.java](../../src/test/java/com/example/tokenpatterns/agent/LocalKnowledgeRetrieverTest.java) pins down the behaviors you just observed.

## Part 3: Scavenger hunt

1. What is the most chunks the generator can ever receive?
2. What is the minimum word length for a term to count?
3. Name three words from `STOP_WORDS`. Is `per` one of them?
4. How much more is a title match worth than a body match?
5. What is the formula for a term's weight?
6. In request B, one of the two chunks has nothing to do with observability. Which one, and which word in the request pulled it in?
7. How does `terms` turn `parallelMapperBuilder` into separate words?
8. Which model tier generates the answer?
9. How does the RAG baseline use the knowledge base?

## Part 4: Q&A

1. Request B retrieved an irrelevant second chunk. Did it hurt the answer? How would you prevent it?
2. This retriever is lexical: it matches words, not meaning. Give a request it would miss that an embedding-based retriever would catch.
3. The teaching notes say, "Retrieval quality is the ceiling." What should you measure separately from answer quality?
4. When the retriever returns nothing, the model is still called. Would you skip the call instead? What would the user see?

<details>
<summary>Facilitator notes</summary>

1. The generator usually ignores it, but it adds tokens and can mislead. Options: add "per" to the stop words, require a minimum score, or return only chunks within a fraction of the top score. Note that removing "per" alone lets the word "agent" pull in the AgenticScope chunk second, so a score threshold is the more general fix.
2. Synonyms and paraphrases. `How do I run jobs simultaneously?` retrieves nothing, although both parallel chunks answer it. Embeddings trade that recall for cost, latency, and less predictable results.
3. Retrieval recall and precision: did the right chunk appear, and how much irrelevant text came with it? A good generator cannot recover a chunk that was never retrieved.
4. Skipping the call saves tokens and removes any risk of an invented answer, at the cost of a less helpful fixed reply. Either is valid if it is deliberate and measured.

</details>

## Stretch challenge (credential-free)

Add a test to [LocalKnowledgeRetrieverTest.java](../../src/test/java/com/example/tokenpatterns/agent/LocalKnowledgeRetrieverTest.java) asserting that `How do I observe token usage per agent?` retrieves the Observability chunk first and does **not** retrieve the Parallel mapper chunk. It fails today. Make it pass with the smallest change to the retriever, then run the retriever and runner tests to check that nothing else changed:

```bash
sh ./mvnw test -Dtest='LocalKnowledgeRetrieverTest,PatternRunnerTest'
```

## Answer key

<details>
<summary>Prediction table</summary>

| # | Chunks retrieved, best first |
|---|---|
| Sample | AgenticScope, Sequential workflow |
| A | Parallel mapper, Parallel workflow |
| B | Observability, Parallel mapper |
| C | AgenticScope, Sequential workflow. Neither mentions retries or timeouts, so the answer should say so |
| D | None: `No relevant knowledge was found for this request.` |

</details>

<details>
<summary>Scavenger hunt answers</summary>

1. Two, from `.limit(2)`.
2. Three characters.
3. Any three of the listed words, such as `about`, `the`, `what`, or `with`. `per` is not a stop word, which matters in clue 6.
4. Twice: `2 * weight` for a title match versus `weight` for a body match.
5. `1 + ln(number of chunks / number of chunks containing the term)`.
6. The Parallel mapper chunk. The word `per` matches "once per collection item".
7. A regular expression inserts a space between a lowercase letter or digit and the uppercase letter after it, giving `parallel mapper builder`.
8. The medium model.
9. Observed tokens plus twice the estimated tokens of the full knowledge base, or a minimum estimate if that is larger. It is modeled, not measured.

</details>
