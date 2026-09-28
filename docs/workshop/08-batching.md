# Module 8: Batching

[Workshop home](README.md) · Previous: [Caching](07-caching.md)

**Idea:** one to six independent items, one model call each, run in parallel. Waiting time drops; tokens do not.

## Try it

Select **Batching** and run the sample (three items separated by semicolons). In the **Trace**, compare the sum of the **Batch worker** durations with the run's wall time: the wall time is shorter. **Projected saving** is 0%.

Try seven items, such as `one;two;three;four;five;six;seven`. The batch is rejected and nothing is processed.

## Read the code

- [`splitBatch`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L292-L304): splitting and the six-item limit.
- [`runBatching`](../../src/main/java/com/example/tokenpatterns/service/PatternRunner.java#L268-L285): `parallelMapperBuilder` runs the same worker for each item.

## Discuss

1. Why doesn't parallelism reduce tokens?
2. Give an example of items that look independent but are not.

<details>
<summary>Suggested answers</summary>

1. Each item still sends its own prompt and gets its own answer. Only the waiting overlaps.
2. "Summarize the report" and "translate the summary": the second needs the first's output.

</details>
