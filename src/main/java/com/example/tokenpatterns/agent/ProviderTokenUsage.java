package com.example.tokenpatterns.agent;

import dev.langchain4j.model.output.TokenUsage;

import java.util.Objects;

/**
 * Provider-reported totals and their subsets. Cache reads/writes are part of input tokens;
 * reasoning is part of output tokens. A missing counter is unknown, not zero.
 */
public final class ProviderTokenUsage extends TokenUsage {

    private final Integer cachedInputTokens;
    private final Integer cacheWriteTokens;
    private final Integer reasoningTokens;

    /** Creates an observation with known provider totals and nullable subset counters. */
    public ProviderTokenUsage(int inputTokens, int outputTokens, int totalTokens,
                              Integer cachedInputTokens, Integer cacheWriteTokens, Integer reasoningTokens) {
        this(Integer.valueOf(inputTokens), Integer.valueOf(outputTokens), Integer.valueOf(totalTokens),
                cachedInputTokens, cacheWriteTokens, reasoningTokens);
    }

    // Aggregation with a generic TokenUsage can introduce unknown totals; never coerce those to zero.
    private ProviderTokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens,
                               Integer cachedInputTokens, Integer cacheWriteTokens, Integer reasoningTokens) {
        super(inputTokens, outputTokens, totalTokens);
        nonnegative("inputTokens", inputTokens);
        nonnegative("outputTokens", outputTokens);
        nonnegative("totalTokens", totalTokens);
        nonnegative("cachedInputTokens", cachedInputTokens);
        nonnegative("cacheWriteTokens", cacheWriteTokens);
        nonnegative("reasoningTokens", reasoningTokens);
        if (inputTokens != null && outputTokens != null && totalTokens != null
                && (long) inputTokens + outputTokens != totalTokens) {
            throw new IllegalArgumentException("Provider total tokens must equal input plus output tokens");
        }
        if (totalTokens != null && ((inputTokens != null && inputTokens > totalTokens)
                || (outputTokens != null && outputTokens > totalTokens))) {
            throw new IllegalArgumentException("Provider input/output tokens cannot exceed total tokens");
        }
        long knownCacheTokens = (cachedInputTokens == null ? 0L : cachedInputTokens)
                + (cacheWriteTokens == null ? 0L : cacheWriteTokens);
        if (inputTokens != null && knownCacheTokens > inputTokens) {
            throw new IllegalArgumentException("Provider cached plus cache-write tokens cannot exceed input tokens");
        }
        if (outputTokens != null && reasoningTokens != null && reasoningTokens > outputTokens) {
            throw new IllegalArgumentException("Provider reasoning tokens cannot exceed output tokens");
        }
        this.cachedInputTokens = cachedInputTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.reasoningTokens = reasoningTokens;
    }

    public Integer cachedInputTokens() {
        return cachedInputTokens;
    }

    public Integer cacheWriteTokens() {
        return cacheWriteTokens;
    }

    public Integer reasoningTokens() {
        return reasoningTokens;
    }

    /** A sum is known only when both operands report the counter. */
    @Override
    public ProviderTokenUsage add(TokenUsage that) {
        if (that == null) {
            return this;
        }
        ProviderTokenUsage provider = that instanceof ProviderTokenUsage usage ? usage : null;
        return new ProviderTokenUsage(
                knownSum(inputTokenCount(), that.inputTokenCount()),
                knownSum(outputTokenCount(), that.outputTokenCount()),
                knownSum(totalTokenCount(), that.totalTokenCount()),
                knownSum(cachedInputTokens, provider == null ? null : provider.cachedInputTokens),
                knownSum(cacheWriteTokens, provider == null ? null : provider.cacheWriteTokens),
                knownSum(reasoningTokens, provider == null ? null : provider.reasoningTokens));
    }

    private static Integer knownSum(Integer first, Integer second) {
        return first == null || second == null ? null : Math.addExact(first, second);
    }

    private static void nonnegative(String name, Integer value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("Provider " + name + " cannot be negative");
        }
    }

    @Override
    public boolean equals(Object other) {
        return super.equals(other)
                && other instanceof ProviderTokenUsage usage
                && Objects.equals(cachedInputTokens, usage.cachedInputTokens)
                && Objects.equals(cacheWriteTokens, usage.cacheWriteTokens)
                && Objects.equals(reasoningTokens, usage.reasoningTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), cachedInputTokens, cacheWriteTokens, reasoningTokens);
    }

    @Override
    public String toString() {
        return "ProviderTokenUsage{inputTokens=" + inputTokenCount()
                + ", outputTokens=" + outputTokenCount()
                + ", totalTokens=" + totalTokenCount()
                + ", cachedInputTokens=" + cachedInputTokens
                + ", cacheWriteTokens=" + cacheWriteTokens
                + ", reasoningTokens=" + reasoningTokens + "}";
    }
}
