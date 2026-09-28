# Module 4: Retrieval augmented generation (RAG)

[Workshop home](README.md) · Previous: [Context compression](03-context-compression.md) · Next: [Tool use](05-tool-use.md)

**Idea:** a Java retriever picks up to two relevant chunks from a small knowledge base, and the model answers only from them.

## Try it

Select **RAG** and run each request. Open `context` in **AgenticScope** to see the retrieved chunks.

- `How does AgenticScope share state between agents?` (relevant chunks found)
- `What is the capital of France?` (nothing found: the model should say it has no evidence)

The **Knowledge retriever** row in the **Trace** shows zero tokens.

## Read the code

- [`LocalKnowledgeRetriever`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L182-L284): scores chunks by matching words, with title matches worth double.
- [`GroundedGenerator`](../../src/main/java/com/example/tokenpatterns/agent/PatternAgents.java#L286-L296): find the sentence that stops it answering from general knowledge.

## Discuss

1. This retriever matches words, not meaning. What request would it miss?
2. Why is retrieval quality the ceiling for answer quality?

<details>
<summary>Suggested answers</summary>

1. Synonyms, such as `How do I run jobs simultaneously?`. Embeddings catch these, at extra cost.
2. The model cannot use a chunk that was never retrieved. Measure retrieval separately from answers.

</details>
