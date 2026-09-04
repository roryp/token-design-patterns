package com.example.tokenpatterns;

import com.example.tokenpatterns.domain.PatternRunRequest;
import com.example.tokenpatterns.domain.PatternRunResult;
import com.example.tokenpatterns.service.PatternCatalog;
import com.example.tokenpatterns.service.PatternRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(StubModelConfiguration.class)
class PatternRunnerTest {

    @Autowired
    private PatternCatalog catalog;

    @Autowired
    private PatternRunner runner;

    @BeforeEach
    void resetCache() {
        runner.clearCache();
    }

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
    void repeatedCacheRequestSkipsTheModel() {
        PatternRunRequest request = new PatternRunRequest(
                "caching",
                "What is idempotency and why does it matter for retries?");

        PatternRunResult miss = runner.run(request);
        PatternRunResult hit = runner.run(request);

        assertThat(miss.metrics().cacheHit()).isFalse();
        assertThat(miss.metrics().modelCalls()).isEqualTo(1);
        assertThat(hit.metrics().cacheHit()).isTrue();
        assertThat(hit.metrics().modelCalls()).isZero();
        assertThat(hit.metrics().observedTokens()).isZero();
        assertThat(hit.output()).isEqualTo(miss.output());
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