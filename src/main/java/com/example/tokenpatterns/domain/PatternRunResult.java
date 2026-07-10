package com.example.tokenpatterns.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PatternRunResult(
        String runId,
        String patternId,
        String mode,
        String output,
        Metrics metrics,
        List<TraceEvent> trace,
        Map<String, Object> scope,
        List<String> takeaways,
        Instant completedAt) {

    public record Metrics(
            int projectedBaselineTokens,
            int observedTokens,
            int avoidedTokens,
            int projectedSavingsPercent,
            int modelCalls,
            int orchestrationSteps,
            long durationMs,
            boolean cacheHit,
            int concurrency,
            String basis) {
    }

    public record TraceEvent(
            int sequence,
            String agent,
            String nodeId,
            String kind,
            String model,
            long durationMs,
            int inputTokens,
            int outputTokens,
            String inputPreview,
            String outputPreview,
            String status) {
    }
}