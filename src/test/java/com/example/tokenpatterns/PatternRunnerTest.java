package com.example.tokenpatterns;

import com.example.tokenpatterns.domain.PatternRunRequest;
import com.example.tokenpatterns.domain.PatternRunResult;
import com.example.tokenpatterns.service.PatternCatalog;
import com.example.tokenpatterns.service.PatternRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
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
                    pattern.sampleInput(),
                    "demo"));

            assertThat(result.output()).as(pattern.id()).isNotBlank();
            assertThat(result.trace()).as(pattern.id()).isNotEmpty();
            assertThat(result.metrics().orchestrationSteps()).isEqualTo(result.trace().size());
            assertThat(result.mode()).isEqualTo("demo");
        }
    }

    @Test
    void repeatedCacheRequestSkipsTheModel() {
        PatternRunRequest request = new PatternRunRequest(
                "caching",
                "What is idempotency and why does it matter for retries?",
                "demo");

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
                "Explain routing; Explain RAG; Explain caching",
                "demo"));

        assertThat(result.metrics().concurrency()).isEqualTo(3);
        assertThat(result.metrics().modelCalls()).isEqualTo(3);
        assertThat(result.output()).contains("1.", "2.", "3.");
    }
}