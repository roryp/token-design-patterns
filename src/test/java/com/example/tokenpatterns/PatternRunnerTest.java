package com.example.tokenpatterns;

import com.example.tokenpatterns.domain.PatternRunRequest;
import com.example.tokenpatterns.domain.PatternRunResult;
import com.example.tokenpatterns.service.PatternCatalog;
import com.example.tokenpatterns.service.PatternRunner;
import com.example.tokenpatterns.agent.ModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@Import(StubModelConfiguration.class)
class PatternRunnerTest {

    @Autowired
    private PatternCatalog catalog;

    @Autowired
    private PatternRunner runner;

    @Test
    void runsEveryPatternWithoutExternalCredentials() {
        for (var pattern : catalog.all()) {
            PatternRunResult result = runner.run(new PatternRunRequest(
                    pattern.id(),
                    pattern.sampleInput()));

            assertThat(result.output()).as(pattern.id()).isNotBlank();
            assertThat(result.trace()).as(pattern.id()).isNotEmpty();
            assertThat(result.metrics().orchestrationSteps()).isEqualTo(result.trace().size());
        }
    }

    @Test
    void repeatedCacheRequestsAlwaysCallTheModelAndClaimNoAvoidedTokens() {
        PatternRunRequest request = new PatternRunRequest(
                "caching",
                "What is idempotency and why does it matter for retries?");

        for (int attempt = 0; attempt < 2; attempt++) {
            PatternRunResult result = runner.run(request);
            assertThat(result.metrics().modelCalls()).isEqualTo(1);
            assertThat(result.metrics().observedTokens()).isPositive();
            assertThat(result.metrics().inputTokens() + result.metrics().outputTokens())
                    .isEqualTo(result.metrics().observedTokens());
            assertThat(result.metrics().cacheStatus()).isEqualTo("miss-written");
            assertThat(result.metrics().cachedInputTokens()).isZero();
            assertThat(result.metrics().cacheWriteTokens()).isPositive();
            assertThat(result.metrics().projectedBaselineTokens()).isEqualTo(result.metrics().observedTokens());
            assertThat(result.metrics().avoidedTokens()).isZero();
            assertThat(result.metrics().projectedSavingsPercent()).isZero();
            assertThat(result.output()).contains("Idempotency", "idempotency key");
        }
    }

    @Test
    void providerReadFixtureDoesNotRemoveCachedTokensFromObservedUsage() {
        PatternRunResult result = runner.run(new PatternRunRequest(
                "caching", "Explain idempotency. " + StubChatModel.CACHE_HIT_FIXTURE));
        assertThat(result.metrics().cacheStatus()).isEqualTo("hit");
        assertThat(result.metrics().cachedInputTokens()).isPositive();
        assertThat(result.metrics().cacheWriteTokens()).isZero();
        assertThat(result.metrics().observedTokens()).isGreaterThan(result.metrics().cachedInputTokens());
        assertThat(result.metrics().modelCalls()).isEqualTo(1);
        assertThat(result.metrics().avoidedTokens()).isZero();
    }

    @Test
    void bypassUsesTheSameInstructionsButNoProviderCache() {
        String input = "Explain idempotency.";
        PatternRunResult enabled = runner.run(new PatternRunRequest("caching", input, true));
        PatternRunResult bypassed = runner.run(new PatternRunRequest("caching", input, false));
        assertThat(bypassed.metrics().cacheStatus()).isEqualTo("bypassed");
        assertThat(bypassed.metrics().cachedInputTokens()).isZero();
        assertThat(bypassed.metrics().cacheWriteTokens()).isZero();
        assertThat(bypassed.metrics().modelCalls()).isEqualTo(1);
        assertThat(bypassed.metrics().inputTokens()).isEqualTo(enabled.metrics().inputTokens());
    }

    @Test
    void missingProviderTelemetryIsUnknownRatherThanZeroOrAMiss() {
        PatternRunResult result = runner.run(new PatternRunRequest(
                "caching", "Explain idempotency. " + StubChatModel.CACHE_UNKNOWN_FIXTURE));
        assertThat(result.metrics().cacheStatus()).isEqualTo("unknown");
        assertThat(result.metrics().cachedInputTokens()).isNull();
        assertThat(result.metrics().cacheWriteTokens()).isNull();
        assertThat(result.metrics().reasoningTokens()).isNull();
        assertThat(result.takeaways().getFirst()).contains("incomplete");
    }

    @Test
    void knownCacheReadDoesNotFabricateMissingWriteTelemetry() {
        PatternRunResult result = runner.run(new PatternRunRequest(
                "caching", "Explain idempotency. " + StubChatModel.CACHE_HIT_FIXTURE + " " + StubChatModel.CACHE_WRITE_UNKNOWN_FIXTURE));
        assertThat(result.metrics().cacheStatus()).isEqualTo("hit");
        assertThat(result.metrics().cacheWriteTokens()).isNull();
        assertThat(result.takeaways().getFirst()).contains("unknown");
    }

    @Test
    void batchMapperRunsEachIndependentItem() {
        PatternRunResult result = runner.run(new PatternRunRequest(
                "batching",
                "Explain routing; Explain RAG; Explain caching"));

        assertThat(result.metrics().concurrency()).isEqualTo(3);
        assertThat(result.metrics().modelCalls()).isEqualTo(3);
        assertThat(result.output()).contains("1.", "2.", "3.");
    }

    @Test
    void aSingleBatchItemMakesExactlyOneCallWithoutInventedTasks() {
        PatternRunResult result = runner.run(new PatternRunRequest(
                "batching", "  Explain an LLM token.  "));
        assertThat(result.metrics().modelCalls()).isEqualTo(1);
        assertThat(result.metrics().concurrency()).isEqualTo(1);
        assertThat(result.scope().get("items")).isEqualTo(java.util.List.of("Explain an LLM token."));
        assertThat(result.output()).startsWith("1.").doesNotContain("2.", "trade-off", "give one metric");
    }

    @Test
    void batchAcceptsSixItemsPreservingOrderAndIgnoringEmptySeparators() {
        PatternRunResult result = runner.run(new PatternRunRequest(
                "batching", "LLM routing;\nContext compression\n\nRetrieval grounding; ;Prompt caching;Tool calling;Batch throughput;\n"));
        assertThat(result.metrics().modelCalls()).isEqualTo(6);
        assertThat(result.metrics().concurrency()).isEqualTo(6);
        assertThat(result.scope().get("items")).isEqualTo(java.util.List.of(
                "LLM routing", "Context compression", "Retrieval grounding", "Prompt caching", "Tool calling", "Batch throughput"));
        assertThat(result.output()).contains("1.", "2.", "3.", "4.", "5.", "6.");
    }

    @Test
    void invalidBatchesAreRejectedBeforeModelsAreInitialized() {
        ModelCatalog models = mock(ModelCatalog.class);
        PatternRunner isolated = new PatternRunner(new PatternCatalog(), models);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> isolated.run(new PatternRunRequest("batching", "one;two;three;four;five;six;seven")))
                .withMessageContaining("at most six")
                .withMessageContaining("no items were processed");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> isolated.run(new PatternRunRequest("batching", " ;\n;;  ")))
                .withMessageContaining("at least one");
        verifyNoInteractions(models);
    }

    /** Planning adds tokens to the turn it runs in, so no single-turn saving may be claimed. */
    @Test
    void stepBackClaimsNoSingleTurnTokenSaving() {
        PatternRunResult result = runner.run(new PatternRunRequest(
                "step-back",
                "Design a safe migration from a monolith to event-driven services."));

        assertThat(result.metrics().projectedBaselineTokens()).isEqualTo(result.metrics().observedTokens());
        assertThat(result.metrics().avoidedTokens()).isZero();
        assertThat(result.metrics().projectedSavingsPercent()).isZero();
        assertThat(result.metrics().basis()).contains("retries");
        assertThat(result.takeaways().getFirst()).matches("The plan cost \\d+ output tokens and framed a \\d+ token answer\\.");
    }

    /** Parallel workers share one agent proxy and scope, so usage must come from each model call itself. */
    @Test
    void parallelBatchWorkersEachReportTheirOwnUsage() {
        for (int attempt = 1; attempt <= 50; attempt++) {
            PatternRunResult result = runner.run(new PatternRunRequest(
                    "batching",
                    "Explain routing; Explain RAG; Explain caching"));

            var modelEvents = result.trace().stream()
                    .filter(event -> "model".equals(event.kind()))
                    .toList();

            assertThat(modelEvents).as("attempt %d", attempt).hasSize(3);
            assertThat(modelEvents).as("attempt %d", attempt).allSatisfy(event ->
                    assertThat(event.inputTokens() + event.outputTokens()).isPositive());
            assertThat(result.metrics().observedTokens()).as("attempt %d", attempt)
                    .isEqualTo(modelEvents.stream()
                            .mapToInt(event -> event.inputTokens() + event.outputTokens())
                            .sum());
        }
    }
}